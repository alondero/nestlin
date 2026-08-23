/*
 * ra_facade.c — implementation of the flat C ABI declared in ra_facade.h.
 *
 * See ra_facade.h for the design rationale. This file is the only place in
 * Nestlin that includes rcheevos headers; everything else goes through the
 * ra_facade_* function set.
 *
 * Threading: this file is single-threaded. Nestlin calls every
 * ra_facade_evaluate_frame from the emulation thread; the JNA mapping is
 * the standard C ABI. rcheevos's internal thread pool is bounded and
 * does not call back into JVM code; the read_memory callback runs
 * synchronously on the eval thread.
 */

#include "ra_facade.h"

/* Enable rcheevos's built-in hash + identify path. Without this flag the
 * rc_client_begin_identify_and_load_game symbol isn't declared, and we'd
 * have to hash the ROM ourselves + use rc_client_begin_load_game. Nestlin
 * has no opinion on the implementation choice — turning the flag on is
 * the documented opt-in. (See rc_client.h, RC_CLIENT_SUPPORTS_HASH.) */
#define RC_CLIENT_SUPPORTS_HASH 1

#include <rc_client.h>
#include <rc_api_request.h>
#include <rc_hash.h>
#include <rc_consoles.h>
#include <rc_compat.h>

#include <stddef.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>

#if defined(_WIN32)
#  include <windows.h>
#else
#  include <time.h>
#endif

/* Cross-platform millisecond sleep used by ra_facade_wait_for_load_settle.
 * The rcheevos compat layer doesn't expose a sleep of its own, so we
 * inline the platform call here. */
static void facade_sleep_ms(int ms) {
    if (ms <= 0) return;
#if defined(_WIN32)
    Sleep((DWORD)ms);
#else
    struct timespec ts;
    ts.tv_sec = ms / 1000;
    ts.tv_nsec = (long)(ms % 1000) * 1000000L;
    while (nanosleep(&ts, &ts) == -1) {
        /* interrupted by signal — sleep the remainder */
        if (ts.tv_sec == 0 && ts.tv_nsec == 0) break;
    }
#endif
}

/* -------------------------------------------------------------------------- */
/* Build identity                                                            */
/* -------------------------------------------------------------------------- */

#define RA_FACADE_VERSION_STRING  "1.0.0"

/* -------------------------------------------------------------------------- */
/* Event queue (FIFO with fixed max size; we drain it on each evaluate_frame) */
/* -------------------------------------------------------------------------- */

#define RA_FACADE_EVENT_QUEUE_CAP 64

typedef struct ra_facade_event_queue_s {
    ra_event_t  slots[RA_FACADE_EVENT_QUEUE_CAP];
    int32_t     head;
    int32_t     tail;
    int32_t     count;
} ra_facade_event_queue_t;

static void event_queue_init(ra_facade_event_queue_t* q) {
    memset(q, 0, sizeof(*q));
}

static void event_queue_clear(ra_facade_event_queue_t* q) {
    /* Strings inside each event are in-place C arrays — nothing to free. */
    q->head = 0;
    q->tail = 0;
    q->count = 0;
}

static int event_queue_push(ra_facade_event_queue_t* q, const ra_event_t* src) {
    if (q->count >= RA_FACADE_EVENT_QUEUE_CAP) {
        /* Drop oldest — newest events are more useful for the UI. */
        q->head = (q->head + 1) % RA_FACADE_EVENT_QUEUE_CAP;
        q->count--;
    }
    q->slots[q->tail] = *src;
    q->tail = (q->tail + 1) % RA_FACADE_EVENT_QUEUE_CAP;
    q->count++;
    return 1;
}

static int event_queue_pop(ra_facade_event_queue_t* q, ra_event_t* dst) {
    if (q->count == 0) return 0;
    *dst = q->slots[q->head];
    q->head = (q->head + 1) % RA_FACADE_EVENT_QUEUE_CAP;
    q->count--;
    return 1;
}

/* -------------------------------------------------------------------------- */
/* HTTP request queue (issue #268)                                            */
/*                                                                            */
/* rcheevos's server-call shim enqueues a request; the Kotlin-side bridge      */
/* (RaHttpBridge) polls ra_facade_dequeue_http_request from a background      */
/* thread, executes the request via Java's HttpClient, then calls             */
/* ra_facade_complete_http_request to deliver the response back. Each slot    */
/* carries the generation counter at enqueue time so a stale response (the    */
/* user logged out before the request returned) is discarded instead of       */
/* poisoning the next login session.                                           */
/* -------------------------------------------------------------------------- */

#define RA_FACADE_HTTP_QUEUE_CAP 8

typedef struct ra_http_slot_s {
    /* A slot is "in flight" while the bridge is executing the request. The
     * generation on enqueue lets the completion path detect a stale response. */
    int32_t              in_flight;
    uint32_t             generation;
    rc_api_request_t     request;   /* owned by the slot; freed on completion */
    rc_client_server_callback_t callback;
    void*                callback_data;
    rc_client_t*         client;
} ra_http_slot_t;

typedef struct ra_facade_http_queue_s {
    ra_http_slot_t  slots[RA_FACADE_HTTP_QUEUE_CAP];
    int32_t         head;
    int32_t         tail;
    int32_t         count;
} ra_facade_http_queue_t;

static void http_queue_init(ra_facade_http_queue_t* q) {
    memset(q, 0, sizeof(*q));
}

static void http_queue_clear(ra_facade_http_queue_t* q) {
    /* rc_api_destroy_request frees the rc_api_request_t's owned buffer. */
    for (int32_t i = 0; i < q->count; ++i) {
        int32_t idx = (q->head + i) % RA_FACADE_HTTP_QUEUE_CAP;
        rc_api_destroy_request(&q->slots[idx].request);
    }
    q->head = 0;
    q->tail = 0;
    q->count = 0;
}

/*
 * Enqueue the request and return the slot index. Caller passes the rcheevos
 * request together with the callback trio rc_client will fire once we
 * deliver the response. The slot is "in flight" until completion. The slot
 * is OWNED by the queue; rc_api_destroy_request is called on completion OR
 * queue-clear (e.g. on logout, destroy).
 */
static int32_t http_queue_push(ra_facade_http_queue_t* q,
                               uint32_t generation,
                               const rc_api_request_t* request,
                               rc_client_server_callback_t callback,
                               void* callback_data,
                               rc_client_t* client) {
    if (q->count >= RA_FACADE_HTTP_QUEUE_CAP) {
        /* Backpressure: drop oldest so the newest login attempt can complete.
         * The dropped slot's rc_api_request_t is freed here. */
        int32_t old_idx = q->head;
        rc_api_destroy_request(&q->slots[old_idx].request);
        q->slots[old_idx].in_flight = 0;
        q->head = (q->head + 1) % RA_FACADE_HTTP_QUEUE_CAP;
        q->count--;
    }
    int32_t idx = q->tail;
    q->slots[idx].in_flight = 1;
    q->slots[idx].generation = generation;
    q->slots[idx].request = *request;        /* shallow copy of the struct */
    q->slots[idx].callback = callback;
    q->slots[idx].callback_data = callback_data;
    q->slots[idx].client = client;
    q->tail = (q->tail + 1) % RA_FACADE_HTTP_QUEUE_CAP;
    q->count++;
    return idx;
}

/* Returns 1 on match (response delivered + slot freed), 0 on stale slot. */
static int http_queue_complete(ra_facade_http_queue_t* q,
                               uint32_t generation,
                               int32_t status,
                               const char* body,
                               int32_t body_length) {
    for (int32_t i = 0; i < q->count; ++i) {
        int32_t idx = (q->head + i) % RA_FACADE_HTTP_QUEUE_CAP;
        if (!q->slots[idx].in_flight) continue;
        if (q->slots[idx].generation != generation) continue;
        /* Match found. Deliver and free. */
        rc_api_server_response_t response;
        memset(&response, 0, sizeof(response));
        response.body = body;
        response.body_length = (size_t)(body_length < 0 ? 0 : body_length);
        response.http_status_code = status;
        rc_client_server_callback_t cb = q->slots[idx].callback;
        void* cb_data = q->slots[idx].callback_data;
        rc_api_destroy_request(&q->slots[idx].request);
        q->slots[idx].in_flight = 0;
        /* Remove the slot by advancing head; since slots are FIFO-ordered by
         * generation-increment, the matching slot may be head or later. To
         * keep the simple ring buffer correct, we leave the slot zeroed and
         * shift subsequent slots forward. */
        for (int32_t j = i; j > 0; --j) {
            int32_t dst = (q->head + j) % RA_FACADE_HTTP_QUEUE_CAP;
            int32_t src = (q->head + j - 1) % RA_FACADE_HTTP_QUEUE_CAP;
            q->slots[dst] = q->slots[src];
            q->slots[src] = (ra_http_slot_t){0};
        }
        q->head = (q->head + 1) % RA_FACADE_HTTP_QUEUE_CAP;
        q->count--;
        cb(&response, cb_data);
        return 1;
    }
    return 0;
}

/* -------------------------------------------------------------------------- */
/* Opaque handle: the façade-internal state                                   */
/* -------------------------------------------------------------------------- */

struct ra_facade_s {
    rc_client_t*              client;

    /* User-supplied memory reader (set via ra_facade_set_memory_reader).
     * Always called synchronously on the eval thread. */
    ra_facade_read_memory_fn  read_fn;
    void*                     read_userdata;

    /* Event queue drained by ra_facade_poll_event. */
    ra_facade_event_queue_t   events;

    /* Generation counter — incremented on every prepare_game / unload_game /
     * destroy. Used to discard events whose origin game no longer matches
     * the active game (so an in-flight rc_client_idle callback that fires
     * after unload_game can be detected and ignored). */
    uint32_t                  generation;

    /* HTTP request queue (issue #268). Drained by the Kotlin-side bridge via
     * ra_facade_dequeue_http_request; responses delivered back via
     * ra_facade_complete_http_request. Each slot carries the generation at
     * enqueue time so a stale response (user logged out before it returned)
     * is dropped instead of poisoning the next session. */
    ra_facade_http_queue_t    http;

    /* Single-flight guard for login. 0 = idle, 1 = a login is in flight.
     * Prevents a second password/token call from queueing a duplicate request
     * while the first is pending. Cleared by login_completion_callback (or
     * by ra_facade_logout). */
    int32_t                   login_in_flight;

    /* Per-achievement list (issue #272). Allocated by
     * ra_facade_create_achievement_list, freed by
     * ra_facade_destroy_achievement_list. NULL when no list is active.
     * Also freed by ra_facade_unload_game / destroy so a partial walk
     * doesn't leak the underlying rcheevos allocation. */
    rc_client_achievement_list_t* achievement_list;

    /* Diagnostic counters — for the contract tests and the menu indicator. */
    int32_t                   hardcore_enabled_snapshot;
    int32_t                   load_state_snapshot;
};

typedef struct ra_facade_s ra_facade_t;

/* -------------------------------------------------------------------------- */
/* rcheevos event handler — bridges into the event queue                      */
/* -------------------------------------------------------------------------- */

static void copy_truncated(char* dst, size_t dst_capacity, const char* src) {
    if (dst_capacity == 0) return;
    if (src == NULL) { dst[0] = '\0'; return; }
    /* snprintf-style truncation; always NUL-terminates. */
    size_t i;
    for (i = 0; i + 1 < dst_capacity && src[i] != '\0'; ++i) {
        dst[i] = src[i];
    }
    dst[i] = '\0';
}

static void handle_event(const rc_client_event_t* event, rc_client_t* client) {
    /* The client pointer is stored in the handle. Look up the handle via
     * rc_client_get_userdata so we don't have to thread it through rcheevos
     * via static state (which would not survive a second facade instance). */
    ra_facade_t* facade = (ra_facade_t*)rc_client_get_userdata(client);
    if (facade == NULL) return;

    ra_event_t out;
    memset(&out, 0, sizeof(out));
    out.type = (int32_t)event->type;

    switch (event->type) {
        case RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED:
        case RC_CLIENT_EVENT_GAME_COMPLETED:
        case RC_CLIENT_EVENT_SUBSET_COMPLETED:
        case RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_SHOW:
        case RC_CLIENT_EVENT_ACHIEVEMENT_CHALLENGE_INDICATOR_HIDE:
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_SHOW:
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_HIDE:
        case RC_CLIENT_EVENT_ACHIEVEMENT_PROGRESS_INDICATOR_UPDATE:
            if (event->achievement != NULL) {
                out.achievement_id = event->achievement->id;
                out.achievement_points = event->achievement->points;
                copy_truncated(out.achievement_title,
                               sizeof(out.achievement_title),
                               event->achievement->title);
                copy_truncated(out.achievement_description,
                               sizeof(out.achievement_description),
                               event->achievement->description);
                copy_truncated(out.achievement_badge,
                               sizeof(out.achievement_badge),
                               event->achievement->badge_name);
                out.measured_percent = event->achievement->measured_percent;
            }
            break;

        case RC_CLIENT_EVENT_LEADERBOARD_STARTED:
        case RC_CLIENT_EVENT_LEADERBOARD_FAILED:
        case RC_CLIENT_EVENT_LEADERBOARD_SUBMITTED:
        case RC_CLIENT_EVENT_LEADERBOARD_SCOREBOARD:
            if (event->leaderboard != NULL) {
                out.leaderboard_id = event->leaderboard->id;
                out.leaderboard_format = (int32_t)event->leaderboard->format;
                out.leaderboard_lower_is_better =
                    (int32_t)event->leaderboard->lower_is_better;
            }
            break;

        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_SHOW:
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_HIDE:
        case RC_CLIENT_EVENT_LEADERBOARD_TRACKER_UPDATE:
            if (event->leaderboard_tracker != NULL) {
                out.leaderboard_id = event->leaderboard_tracker->id;
                /* rcheevos's tracker struct only carries the formatted
                 * display string ("1:23.45" etc.); the format and
                 * lower-is-better bits live on the parent leaderboard
                 * (rc_client_get_leaderboard_info). For the JNA side
                 * we expose the display string under "tracker_value"
                 * for backward compatibility. */
                copy_truncated(out.leaderboard_tracker,
                               sizeof(out.leaderboard_tracker),
                               event->leaderboard_tracker->display);
            }
            break;

        case RC_CLIENT_EVENT_SERVER_ERROR:
            if (event->server_error != NULL) {
                out.server_result_code = event->server_error->result;
                out.server_related_id = event->server_error->related_id;
                copy_truncated(out.server_error_message,
                               sizeof(out.server_error_message),
                               event->server_error->error_message);
                copy_truncated(out.server_api_path,
                               sizeof(out.server_api_path),
                               event->server_error->api);
            }
            break;

        case RC_CLIENT_EVENT_RESET:
        case RC_CLIENT_EVENT_DISCONNECTED:
        case RC_CLIENT_EVENT_RECONNECTED:
        default:
            /* No payload. */
            break;
    }

    event_queue_push(&facade->events, &out);
}

/* -------------------------------------------------------------------------- */
/* rcheevos login-completion callback (issue #268)                            */
/* -------------------------------------------------------------------------- */

/*
 * Fired by rcheevos when a login round-trip settles (success OR failure).
 * Clears the single-flight guard so the UI can issue a follow-up login /
 * logout. The user_info struct is now populated on success; the SERVER_ERROR
 * event was already queued by handle_event on failure.
 *
 * The `result` is rcheevos's RC_OK (0) on success; non-zero carries a
 * server-side error message via error_message. We deliberately do not log
 * the message here — it can contain server-internal context that shouldn't
 * leak into Nestlin's log files (see redaction policy in issue #268).
 */
static void login_completion_callback(int result,
                                      const char* error_message,
                                      rc_client_t* client,
                                      void* callback_userdata) {
    (void)result;
    (void)error_message;
    ra_facade_t* facade = (ra_facade_t*)callback_userdata;
    if (facade == NULL) return;
    facade->login_in_flight = 0;
}

/* -------------------------------------------------------------------------- */
/* rcheevos server-call shim — enqueues HTTP requests for the Kotlin bridge   */
/* -------------------------------------------------------------------------- */

/*
 * rcheevos's HTTP layer calls this when it wants to send a request. We enqueue
 * the request onto the façade's HTTP queue and return immediately — the
 * Kotlin-side RaHttpBridge (src/main/kotlin/.../session/RaHttpBridge.kt)
 * drains the queue on a background thread, executes the request via Java's
 * HttpClient, and delivers the response back through
 * ra_facade_complete_http_request.
 *
 * rcheevos expects this callback to be cheap and non-blocking; the actual
 * network I/O happens off-thread. The request struct's strings are owned by
 * rcheevos for the lifetime of the callback, so a shallow copy is safe.
 */
static void server_call_shim(const rc_api_request_t* request,
                             rc_client_server_callback_t callback,
                             void* callback_data,
                             rc_client_t* client) {
    ra_facade_t* facade = (ra_facade_t*)rc_client_get_userdata(client);
    if (facade == NULL || request == NULL) {
        rc_api_server_response_t response;
        memset(&response, 0, sizeof(response));
        response.http_status_code = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
        if (callback != NULL) callback(&response, callback_data);
        return;
    }
    http_queue_push(&facade->http, facade->generation, request,
                    callback, callback_data, client);
}

/* -------------------------------------------------------------------------- */
/* rcheevos read-memory shim — bridges to the JNA-supplied function          */
/* -------------------------------------------------------------------------- */

static uint32_t read_memory_shim(uint32_t address,
                                 uint8_t* buffer,
                                 uint32_t num_bytes,
                                 rc_client_t* client) {
    ra_facade_t* facade = (ra_facade_t*)rc_client_get_userdata(client);
    if (facade == NULL || facade->read_fn == NULL || buffer == NULL) {
        if (buffer != NULL && num_bytes > 0) memset(buffer, 0, num_bytes);
        return 0;
    }
    return facade->read_fn(address, buffer, num_bytes, facade->read_userdata);
}

/* -------------------------------------------------------------------------- */
/* Lifecycle                                                                 */
/* -------------------------------------------------------------------------- */

RA_FACADE_EXPORT void* ra_facade_create(const char* server_url,
                                        const char* user_agent) {
    (void)server_url;
    (void)user_agent;
    ra_facade_t* facade = (ra_facade_t*)calloc(1, sizeof(ra_facade_t));
    if (facade == NULL) return NULL;

    event_queue_init(&facade->events);
    http_queue_init(&facade->http);
    facade->generation = 1;
    facade->login_in_flight = 0;
    facade->hardcore_enabled_snapshot = 0;
    facade->load_state_snapshot = (int32_t)RA_LOAD_STATE_IDLE;

    facade->client = rc_client_create(read_memory_shim, server_call_shim);
    if (facade->client == NULL) {
        free(facade);
        return NULL;
    }
    rc_client_set_userdata(facade->client, facade);
    rc_client_set_event_handler(facade->client, handle_event);

    /* Force softcore mode immediately (issue #267 requirement). */
    rc_client_set_hardcore_enabled(facade->client, 0);
    facade->hardcore_enabled_snapshot = rc_client_get_hardcore_enabled(facade->client);

    /* Default spectator + unofficial ON — matches what rcheevos does for
     * softcore users and avoids the server returning zero-achievement sets
     * for unofficial content. */
    rc_client_set_spectator_mode_enabled(facade->client, 1);
    rc_client_set_unofficial_enabled(facade->client, 1);

    /* Allow rcheevos to read memory outside of do_frame/idle so the read
     * shim can be invoked from background processing (e.g. achievement
     * challenge indicator evaluation between frames). */
    rc_client_set_allow_background_memory_reads(facade->client, 1);

    return facade;
}

RA_FACADE_EXPORT int32_t ra_facade_destroy(void* handle) {
    if (handle == NULL) return (int32_t)RA_OK;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client != NULL) {
        /* No explicit rc_client_unload_game call: the client is about
         * to be destroyed anyway, so the unload is redundant. Calling
         * unload_game here also crashes inside rcheevos when the
         * client never had a game loaded (issue #273 CI: SIGABRT in
         * rc_client_unload_game+0x7a after evaluate_frame / idle on
         * a bare client). rc_client_destroy below frees the entire
         * client state including any scheduled callbacks. */
        event_queue_clear(&facade->events);
        http_queue_clear(&facade->http);
        if (facade->achievement_list != NULL) {
            rc_client_destroy_achievement_list(facade->achievement_list);
            facade->achievement_list = NULL;
        }
        facade->generation++;
        rc_client_destroy(facade->client);
        facade->client = NULL;
    }
    /* Zero the handle's memory so a use-after-destroy in JNA crashes loud
     * (a JNA-side handle is a long; freeing the wrapper here means the
     * underlying rc_client_t* is gone too). */
    memset(facade, 0, sizeof(*facade));
    free(facade);
    return (int32_t)RA_OK;
}

RA_FACADE_EXPORT int32_t ra_facade_is_signed_in(void* handle) {
    if (handle == NULL) return 0;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return 0;
    return rc_client_get_user_info(facade->client) != NULL ? 1 : 0;
}

RA_FACADE_EXPORT int32_t ra_facade_begin_login_with_password(void* handle,
                                                             const char* username,
                                                             const char* password) {
    if (handle == NULL) return (int32_t)RA_ERR_NULL_HANDLE;
    if (username == NULL || password == NULL) return (int32_t)RA_ERR_INVALID_ARG;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return (int32_t)RA_ERR_DESTROYED;
    if (facade->login_in_flight) return (int32_t)RA_ERR_LIBRARY_STATE;
    facade->login_in_flight = 1;
    /* Async login — rc_client fires login_completion_callback when the
     * round-trip settles, which clears login_in_flight. The HTTP request
     * itself travels through server_call_shim → HTTP queue → Kotlin bridge
     * → complete_http_request → rcheevos callback → login_completion. */
    rc_client_begin_login_with_password(
        facade->client, username, password,
        login_completion_callback, facade);
    return (int32_t)RA_OK;
}

RA_FACADE_EXPORT int32_t ra_facade_begin_login_with_token(void* handle,
                                                          const char* username,
                                                          const char* token) {
    if (handle == NULL) return (int32_t)RA_ERR_NULL_HANDLE;
    if (username == NULL || token == NULL) return (int32_t)RA_ERR_INVALID_ARG;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return (int32_t)RA_ERR_DESTROYED;
    if (facade->login_in_flight) return (int32_t)RA_ERR_LIBRARY_STATE;
    facade->login_in_flight = 1;
    rc_client_begin_login_with_token(
        facade->client, username, token,
        login_completion_callback, facade);
    return (int32_t)RA_OK;
}

RA_FACADE_EXPORT void ra_facade_logout(void* handle) {
    if (handle == NULL) return;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return;
    /* Bump the generation so any in-flight HTTP callback the bridge might
     * still be holding is silently discarded on completion. */
    facade->generation++;
    facade->login_in_flight = 0;
    /* Drop any queued HTTP requests — they're now stale. */
    http_queue_clear(&facade->http);
    http_queue_init(&facade->http);
    rc_client_logout(facade->client);
}

RA_FACADE_EXPORT int32_t ra_facade_get_user_info(void* handle, ra_user_info_t* out) {
    if (handle == NULL || out == NULL) return (int32_t)RA_ERR_INVALID_ARG;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return (int32_t)RA_ERR_DESTROYED;
    const rc_client_user_t* user = rc_client_get_user_info(facade->client);
    if (user == NULL) return (int32_t)RA_ERR_NOT_SIGNED_IN;
    memset(out, 0, sizeof(*out));
    copy_truncated(out->username, sizeof(out->username), user->username);
    copy_truncated(out->display_name, sizeof(out->display_name), user->display_name);
    /* rc_client_user_get_image_url writes the avatar URL into a caller buffer
     * when the field is non-NULL on the user struct; on older rcheevos the
     * field may be absent, so we fall back to user->avatar_url directly. */
    char url_buffer[RA_FACADE_AVATAR_URL_MAX];
    memset(url_buffer, 0, sizeof(url_buffer));
    if (rc_client_user_get_image_url(user, url_buffer, sizeof(url_buffer)) == RC_OK) {
        copy_truncated(out->avatar_url, sizeof(out->avatar_url), url_buffer);
    } else if (user->avatar_url != NULL) {
        copy_truncated(out->avatar_url, sizeof(out->avatar_url), user->avatar_url);
    }
    copy_truncated(out->token, sizeof(out->token), user->token);
    out->score = user->score;
    out->score_softcore = user->score_softcore;
    out->num_unread_messages = user->num_unread_messages;
    return (int32_t)RA_OK;
}

RA_FACADE_EXPORT int32_t ra_facade_dequeue_http_request(void* handle,
                                                        ra_http_request_t* out) {
    if (handle == NULL || out == NULL) return 0;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->http.count == 0) return 0;
    memset(out, 0, sizeof(*out));
    out->generation = facade->http.slots[facade->http.head].generation;
    const rc_api_request_t* req = &facade->http.slots[facade->http.head].request;
    if (req->url != NULL) {
        copy_truncated(out->url, sizeof(out->url), req->url);
    }
    if (req->post_data != NULL) {
        copy_truncated(out->post_data, sizeof(out->post_data), req->post_data);
        out->has_post_data = 1;
    }
    if (req->content_type != NULL) {
        copy_truncated(out->content_type, sizeof(out->content_type), req->content_type);
    }
    return 1;
}

RA_FACADE_EXPORT int32_t ra_facade_complete_http_request(void* handle,
                                                          uint32_t generation,
                                                          int32_t status,
                                                          const char* body,
                                                          int32_t body_length) {
    if (handle == NULL) return 0;
    ra_facade_t* facade = (ra_facade_t*)handle;
    /* The single-flight login_in_flight flag is cleared by
     * login_completion_callback, NOT here — a login may issue multiple HTTP
     * round-trips, and clearing on the first response would let the user
     * issue a duplicate login before rcheevos has updated user_info. */
    return http_queue_complete(&facade->http, generation, status,
                               body, body_length);
}

/* -------------------------------------------------------------------------- */
/* Game lifecycle                                                            */
/* -------------------------------------------------------------------------- */

RA_FACADE_EXPORT int32_t ra_facade_prepare_game(void* handle,
                                                const uint8_t* rom_bytes,
                                                int32_t rom_len,
                                                const char* display_name) {
    (void)display_name;
    if (handle == NULL) return (int32_t)RA_ERR_NULL_HANDLE;
    ra_facade_t* facade = (ra_facade_t*)handle;

    if (rom_bytes == NULL || rom_len <= 0) return (int32_t)RA_ERR_INVALID_ARG;

    /* Defensive: any stale events from the previous game are dropped, and
     * the generation counter is bumped so an in-flight HTTP callback that
     * arrives after this point can be detected by the JNA layer. */
    event_queue_clear(&facade->events);
    if (facade->achievement_list != NULL) {
        rc_client_destroy_achievement_list(facade->achievement_list);
        facade->achievement_list = NULL;
    }
    facade->generation++;

    rc_client_unload_game(facade->client);

    /* begin_identify_and_load_game computes the hash internally for
     * RC_CLIENT_SUPPORTS_HASH consoles (NES is supported in v12.4.0).
     * The callback signature is mandatory — we pass a no-op so the
     * async handle's eventual completion doesn't try to call into JVM. */
    rc_client_async_handle_t* async = rc_client_begin_identify_and_load_game(
        facade->client,
        RC_CONSOLE_NINTENDO,
        NULL,                                     /* no file path; bytes only */
        (const uint8_t*)rom_bytes,
        (size_t)rom_len,
        NULL,                                     /* callback */
        NULL);                                    /* callback userdata */
    (void)async;

    facade->load_state_snapshot = (int32_t)ra_facade_get_load_state(handle);

    /* Without a signed-in user (issue #268 not landed yet), the request
     * will fail at the server-call shim and the load state will settle to
     * RA_LOAD_STATE_FAILED on the next idle. The JNA layer treats a non-OK
     * load state as "prepareGame returned false". rcheevos surfaces
     * sign-in state via rc_client_get_user_info() — non-NULL = signed in. */
    if (rc_client_get_user_info(facade->client) == NULL) {
        return (int32_t)RA_ERR_NOT_SIGNED_IN;
    }
    return (int32_t)RA_OK;
}

RA_FACADE_EXPORT void ra_facade_evaluate_frame(void* handle,
                                               int64_t frame_index) {
    (void)frame_index;
    if (handle == NULL) return;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return;
    rc_client_do_frame(facade->client);
}

RA_FACADE_EXPORT void ra_facade_idle(void* handle) {
    if (handle == NULL) return;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return;
    rc_client_idle(facade->client);
    facade->load_state_snapshot = (int32_t)rc_client_get_load_game_state(facade->client);
}

RA_FACADE_EXPORT void ra_facade_reset(void* handle) {
    if (handle == NULL) return;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return;
    rc_client_reset(facade->client);
}

RA_FACADE_EXPORT void ra_facade_unload_game(void* handle) {
    if (handle == NULL) return;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return;
    event_queue_clear(&facade->events);
    /* Free any in-flight achievement list — the new game gets a fresh
     * snapshot; the old list's bucket pointers are about to be invalid. */
    if (facade->achievement_list != NULL) {
        rc_client_destroy_achievement_list(facade->achievement_list);
        facade->achievement_list = NULL;
    }
    facade->generation++;
    rc_client_unload_game(facade->client);
    facade->load_state_snapshot = (int32_t)RC_CLIENT_LOAD_GAME_STATE_NONE;
}

RA_FACADE_EXPORT int32_t ra_facade_get_load_state(void* handle) {
    if (handle == NULL) return (int32_t)RA_LOAD_STATE_IDLE;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return (int32_t)RA_LOAD_STATE_IDLE;
    int s = rc_client_get_load_game_state(facade->client);
    /* rcheevos's enum is different from ours — translate. The values match
     * by coincidence in the subset we surface (RC_CLIENT_LOAD_GAME_STATE_*
     * 0..6 == RA_LOAD_STATE_* 0..6); assert with a runtime check anyway. */
    switch (s) {
        case RC_CLIENT_LOAD_GAME_STATE_NONE:               return (int32_t)RA_LOAD_STATE_IDLE;
        case RC_CLIENT_LOAD_GAME_STATE_AWAIT_LOGIN:        return (int32_t)RA_LOAD_STATE_AWAITING_LOGIN;
        case RC_CLIENT_LOAD_GAME_STATE_IDENTIFYING_GAME:   return (int32_t)RA_LOAD_STATE_IDENTIFYING;
        case RC_CLIENT_LOAD_GAME_STATE_STARTING_SESSION:   return (int32_t)RA_LOAD_STATE_STARTING;
        case RC_CLIENT_LOAD_GAME_STATE_DONE:               return (int32_t)RA_LOAD_STATE_READY;
        case RC_CLIENT_LOAD_GAME_STATE_ABORTED:            return (int32_t)RA_LOAD_STATE_ABORTED;
        default:                                            return (int32_t)RA_LOAD_STATE_FAILED;
    }
}

RA_FACADE_EXPORT int32_t ra_facade_get_game_info(void* handle,
                                                 ra_game_info_t* out) {
    if (handle == NULL || out == NULL) return (int32_t)RA_ERR_INVALID_ARG;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return (int32_t)RA_ERR_NULL_HANDLE;

    const rc_client_game_t* game = rc_client_get_game_info(facade->client);
    memset(out, 0, sizeof(*out));
    out->state = (int32_t)ra_facade_get_load_state(handle);
    out->hardcore_enabled = rc_client_get_hardcore_enabled(facade->client);
    if (game != NULL) {
        out->game_id = game->id;
    }
    out->has_achievements = rc_client_has_achievements(facade->client);
    out->has_leaderboards = rc_client_has_leaderboards(facade->client);
    return (int32_t)RA_OK;
}

/* -------------------------------------------------------------------------- */
/* Memory reader                                                             */
/* -------------------------------------------------------------------------- */

RA_FACADE_EXPORT int32_t ra_facade_set_memory_reader(void* handle,
                                                     ra_facade_read_memory_fn fn,
                                                     void* userdata) {
    if (handle == NULL) return (int32_t)RA_ERR_NULL_HANDLE;
    ra_facade_t* facade = (ra_facade_t*)handle;
    facade->read_fn = fn;
    facade->read_userdata = userdata;
    /* rc_client's read-memory function is set at create time and is
     * constant. The shim always calls back into facade->read_fn at frame
     * time, so changing the function pointer here takes effect on the
     * next rc_client_do_frame() call. */
    return (int32_t)RA_OK;
}

/* -------------------------------------------------------------------------- */
/* Hash + identity (issue #269)                                              */
/* -------------------------------------------------------------------------- */

RA_FACADE_EXPORT int32_t ra_facade_hash_nes_rom(const uint8_t* rom_bytes,
                                                int32_t rom_len,
                                                char* out_hash) {
    if (rom_bytes == NULL || rom_len <= 0) return (int32_t)RA_ERR_INVALID_ARG;
    if (out_hash == NULL) return (int32_t)RA_ERR_INVALID_ARG;
    /* rc_hash_generate_from_buffer is the same routine rc_client uses for NES
     * games; passing the explicit RC_CONSOLE_NINTENDO keeps the hash aligned
     * with the server-side expected value. */
    int ok = rc_hash_generate_from_buffer(out_hash, RC_CONSOLE_NINTENDO,
                                          rom_bytes, (size_t)rom_len);
    if (!ok) return (int32_t)RA_ERR_INTERNAL;
    return (int32_t)RA_OK;
}

RA_FACADE_EXPORT int32_t ra_facade_get_user_game_summary(void* handle,
                                                         ra_user_game_summary_t* out) {
    if (handle == NULL || out == NULL) return (int32_t)RA_ERR_INVALID_ARG;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return (int32_t)RA_ERR_DESTROYED;
    memset(out, 0, sizeof(*out));
    if (rc_client_get_user_info(facade->client) == NULL) {
        return (int32_t)RA_ERR_NOT_SIGNED_IN;
    }
    if (rc_client_is_game_loaded(facade->client) == 0) {
        return (int32_t)RA_ERR_NO_GAME;
    }
    rc_client_user_game_summary_t summary;
    memset(&summary, 0, sizeof(summary));
    rc_client_get_user_game_summary(facade->client, &summary);
    out->num_core_achievements = summary.num_core_achievements;
    out->num_unofficial_achievements = summary.num_unofficial_achievements;
    out->num_unlocked_achievements = summary.num_unlocked_achievements;
    out->num_unsupported_achievements = summary.num_unsupported_achievements;
    out->points_core = summary.points_core;
    out->points_unlocked = summary.points_unlocked;
    return (int32_t)RA_OK;
}

RA_FACADE_EXPORT int32_t ra_facade_get_game_summary(void* handle,
                                                    ra_game_summary_t* out) {
    if (handle == NULL || out == NULL) return (int32_t)RA_ERR_INVALID_ARG;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return (int32_t)RA_ERR_DESTROYED;
    memset(out, 0, sizeof(*out));
    const rc_client_game_t* game = rc_client_get_game_info(facade->client);
    if (game == NULL) return (int32_t)RA_ERR_NO_GAME;
    out->id = game->id;
    copy_truncated(out->title, sizeof(out->title), game->title);
    copy_truncated(out->hash, sizeof(out->hash), game->hash);
    copy_truncated(out->badge_name, sizeof(out->badge_name), game->badge_name);
    if (game->badge_url != NULL) {
        copy_truncated(out->image_url, sizeof(out->image_url), game->badge_url);
    } else if (game->badge_name[0] != '\0') {
        /* Fall back to the official URL helper when rcheevos hasn't populated
         * a full URL yet (typical during IDENTIFYING/STARTING before the
         * patchdata fetch resolves). */
        if (rc_client_game_get_image_url(game, out->image_url,
                                         sizeof(out->image_url)) != RC_OK) {
            out->image_url[0] = '\0';
        }
    }
    return (int32_t)RA_OK;
}

RA_FACADE_EXPORT int32_t ra_facade_wait_for_load_settle(void* handle,
                                                        int32_t timeout_ms,
                                                        int32_t poll_ms,
                                                        int32_t* out_state) {
    if (handle == NULL) return (int32_t)RA_ERR_NULL_HANDLE;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return (int32_t)RA_ERR_DESTROYED;
    if (poll_ms <= 0) poll_ms = 50;
    if (timeout_ms <= 0) timeout_ms = 1000;
    /* Spin until we see a terminal state, the deadline passes, or the façade
     * is destroyed. We treat IDENTIFYING + STARTING as transient; READY +
     * FAILED + ABORTED + IDLE as terminal. The HTTP bridge still runs on
     * its own executor, so the load can complete between polls without
     * blocking the rcheevos thread pool. */
    int32_t elapsed = 0;
    int32_t observed = (int32_t)RA_LOAD_STATE_IDLE;
    while (elapsed < timeout_ms) {
        observed = (int32_t)rc_client_get_load_game_state(facade->client);
        switch (observed) {
            case RC_CLIENT_LOAD_GAME_STATE_DONE:
            case RC_CLIENT_LOAD_GAME_STATE_ABORTED:
                if (out_state) *out_state = (int32_t)RA_LOAD_STATE_READY;
                if (observed == RC_CLIENT_LOAD_GAME_STATE_ABORTED && out_state) {
                    *out_state = (int32_t)RA_LOAD_STATE_ABORTED;
                }
                return (int32_t)RA_OK;
            default:
                break;
        }
        /* Anything that rc_client considers "no game" maps to FAILED in our
         * flat enum; IDENTIFYING_GAME + STARTING_SESSION stay transient. */
        if (observed == RC_CLIENT_LOAD_GAME_STATE_NONE ||
            observed == RC_CLIENT_LOAD_GAME_STATE_AWAIT_LOGIN) {
            /* AWAIT_LOGIN means the load is doomed (no signed-in user) and
             * there's no point waiting — but we still drive idle() so any
             * queued SERVER_ERROR has a chance to settle. */
            if (observed == RC_CLIENT_LOAD_GAME_STATE_AWAIT_LOGIN) {
                rc_client_idle(facade->client);
                if (out_state) *out_state = (int32_t)RA_LOAD_STATE_FAILED;
                return (int32_t)RA_OK;
            }
            /* NONE: load was never prepared. Treat as failure. */
            if (out_state) *out_state = (int32_t)RA_LOAD_STATE_FAILED;
            return (int32_t)RA_OK;
        }
        rc_client_idle(facade->client);
        facade_sleep_ms(poll_ms);
        elapsed += poll_ms;
    }
    /* Timed out — observed is whatever we last saw. */
    if (out_state) *out_state = observed;
    return (int32_t)RA_ERR_INTERNAL;
}

RA_FACADE_EXPORT int32_t ra_facade_badge_url(const char* badge_name,
                                             char* out_url,
                                             int32_t out_url_capacity) {
    if (badge_name == NULL || out_url == NULL || out_url_capacity <= 0) {
        return (int32_t)RA_ERR_INVALID_ARG;
    }
    memset(out_url, 0, (size_t)out_url_capacity);
    /* rcheevos's RC_IMAGE_HOST may differ between forks; for the official
     * server the badge path is "/Images/<badge>.png". We hard-code the
     * documented path here so the image cache never depends on which
     * rcheevos build the user happens to have vendored. */
    static const char prefix[] = "https://retroachievements.org/Images/";
    static const char suffix[] = ".png";
    size_t prefix_len = sizeof(prefix) - 1;
    size_t badge_len = strlen(badge_name);
    size_t suffix_len = sizeof(suffix) - 1;
    if (prefix_len + badge_len + suffix_len + 1 > (size_t)out_url_capacity) {
        return (int32_t)RA_ERR_BUFFER_TOO_SMALL;
    }
    memcpy(out_url, prefix, prefix_len);
    memcpy(out_url + prefix_len, badge_name, badge_len);
    memcpy(out_url + prefix_len + badge_len, suffix, suffix_len + 1);
    return (int32_t)RA_OK;
}

/* -------------------------------------------------------------------------- */
/* Progress serialization                                                    */
/* -------------------------------------------------------------------------- */

RA_FACADE_EXPORT int32_t ra_facade_progress_size(void* handle) {
    if (handle == NULL) return 0;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return 0;
    if (rc_client_is_game_loaded(facade->client) == 0) return 0;
    size_t n = rc_client_progress_size(facade->client);
    if (n > 0x7FFFFFFF) return 0x7FFFFFFF;
    return (int32_t)n;
}

RA_FACADE_EXPORT int32_t ra_facade_serialize_progress(void* handle,
                                                      uint8_t* out,
                                                      int32_t out_capacity) {
    if (handle == NULL) return (int32_t)RA_ERR_NULL_HANDLE;
    if (out == NULL || out_capacity < 0) return (int32_t)RA_ERR_INVALID_ARG;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return (int32_t)RA_ERR_NULL_HANDLE;
    if (rc_client_is_game_loaded(facade->client) == 0) return 0;
    int n = rc_client_serialize_progress_sized(facade->client, out, (size_t)out_capacity);
    if (n < 0) {
        /* A negative return means the buffer was too small — the caller
         * can retry with progress_size()'s returned value. We surface
         * RA_ERR_BUFFER_TOO_SMALL so the JNA layer can branch cleanly. */
        return (int32_t)RA_ERR_BUFFER_TOO_SMALL;
    }
    /* n == 0 is a legitimate "no progress to save" result (no achievements
     * triggered yet, or unsigned-in). Distinguish from the buffer-too-small
     * negative return above by returning 0 explicitly. */
    return (int32_t)n;
}

RA_FACADE_EXPORT int32_t ra_facade_restore_progress(void* handle,
                                                    const uint8_t* data,
                                                    int32_t data_len) {
    if (handle == NULL) return (int32_t)RA_ERR_NULL_HANDLE;
    if (data == NULL || data_len < 0) return (int32_t)RA_ERR_INVALID_ARG;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return (int32_t)RA_ERR_NULL_HANDLE;
    if (rc_client_is_game_loaded(facade->client) == 0) return (int32_t)RA_ERR_NO_GAME;
    int rc = rc_client_deserialize_progress_sized(facade->client, data, (size_t)data_len);
    if (rc != 0) {
        /* Bad data — silently reset the runtime per the contract. */
        rc_client_reset(facade->client);
        return (int32_t)RA_ERR_INTERNAL;
    }
    return (int32_t)RA_OK;
}

/* -------------------------------------------------------------------------- */
/* Event queue                                                               */
/* -------------------------------------------------------------------------- */

RA_FACADE_EXPORT int32_t ra_facade_poll_event(void* handle, ra_event_t* out) {
    if (handle == NULL || out == NULL) return 0;
    ra_facade_t* facade = (ra_facade_t*)handle;
    return event_queue_pop(&facade->events, out);
}

RA_FACADE_EXPORT void ra_facade_clear_events(void* handle) {
    if (handle == NULL) return;
    ra_facade_t* facade = (ra_facade_t*)handle;
    event_queue_clear(&facade->events);
}

/* -------------------------------------------------------------------------- */
/* Achievement list (issue #272)                                              */
/*                                                                            */
/* Walks rcheevos's rc_client_achievement_list_t — the list lives on the      */
/* façade for the duration of one snapshot. The JNA side calls create,        */
/* walks bucket + achievement slots, copies each field, then destroys.        */
/* The list is auto-freed on unload_game / destroy / shutdown so a partial    */
/* walk that crashes the JNA side doesn't leak a list.                        */
/* -------------------------------------------------------------------------- */

RA_FACADE_EXPORT int32_t ra_facade_has_achievements(void* handle) {
    if (handle == NULL) return 0;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return 0;
    return rc_client_has_achievements(facade->client) != 0 ? 1 : 0;
}

RA_FACADE_EXPORT int32_t ra_facade_create_achievement_list(void* handle,
                                                           int32_t category,
                                                           int32_t grouping) {
    if (handle == NULL) return (int32_t)RA_ERR_NULL_HANDLE;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->client == NULL) return (int32_t)RA_ERR_DESTROYED;
    if (rc_client_get_user_info(facade->client) == NULL) {
        return (int32_t)RA_ERR_NOT_SIGNED_IN;
    }
    if (rc_client_is_game_loaded(facade->client) == 0) {
        return (int32_t)RA_ERR_NO_GAME;
    }
    /* Free any pre-existing list before allocating a fresh one. The
     * create call replaces the slot — leaving an old list around would
     * leak memory across rapid refreshes. */
    if (facade->achievement_list != NULL) {
        rc_client_destroy_achievement_list(facade->achievement_list);
        facade->achievement_list = NULL;
    }
    facade->achievement_list = rc_client_create_achievement_list(
        facade->client, category, grouping);
    if (facade->achievement_list == NULL) {
        return (int32_t)RA_ERR_INTERNAL;
    }
    return (int32_t)RA_OK;
}

RA_FACADE_EXPORT int32_t ra_facade_achievement_list_bucket_count(void* handle) {
    if (handle == NULL) return 0;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->achievement_list == NULL) return 0;
    if (facade->achievement_list->num_buckets > 0x7FFFFFFF) return 0x7FFFFFFF;
    return (int32_t)facade->achievement_list->num_buckets;
}

RA_FACADE_EXPORT int32_t ra_facade_get_achievement_bucket(void* handle,
                                                          int32_t bucket_index,
                                                          ra_achievement_bucket_t* out) {
    if (handle == NULL || out == NULL) return (int32_t)RA_ERR_INVALID_ARG;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->achievement_list == NULL) return (int32_t)RA_ERR_NO_GAME;
    if (bucket_index < 0 || (uint32_t)bucket_index >= facade->achievement_list->num_buckets) {
        return (int32_t)RA_ERR_INVALID_ARG;
    }
    const rc_client_achievement_bucket_t* b =
        &facade->achievement_list->buckets[bucket_index];
    memset(out, 0, sizeof(*out));
    out->bucket_type = (int32_t)b->bucket_type;
    out->subset_id = (int32_t)b->subset_id;
    if (b->num_achievements > 0x7FFFFFFF) {
        out->achievement_count = 0x7FFFFFFF;
    } else {
        out->achievement_count = (int32_t)b->num_achievements;
    }
    copy_truncated(out->label, sizeof(out->label), b->label);
    return (int32_t)RA_OK;
}

RA_FACADE_EXPORT int32_t ra_facade_get_achievement_at(void* handle,
                                                      int32_t bucket_index,
                                                      int32_t achievement_index,
                                                      ra_achievement_t* out) {
    if (handle == NULL || out == NULL) return (int32_t)RA_ERR_INVALID_ARG;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->achievement_list == NULL) return (int32_t)RA_ERR_NO_GAME;
    if (bucket_index < 0 || (uint32_t)bucket_index >= facade->achievement_list->num_buckets) {
        return (int32_t)RA_ERR_INVALID_ARG;
    }
    const rc_client_achievement_bucket_t* b =
        &facade->achievement_list->buckets[bucket_index];
    if (achievement_index < 0 || (uint32_t)achievement_index >= b->num_achievements) {
        return (int32_t)RA_ERR_INVALID_ARG;
    }
    const rc_client_achievement_t* a = b->achievements[achievement_index];
    memset(out, 0, sizeof(*out));
    out->id = (int32_t)a->id;
    out->points = (int32_t)a->points;
    out->state = (int32_t)a->state;
    out->category = (int32_t)a->category;
    out->bucket = (int32_t)a->bucket;
    out->measured_percent = a->measured_percent;
    copy_truncated(out->title, sizeof(out->title), a->title);
    copy_truncated(out->description, sizeof(out->description), a->description);
    copy_truncated(out->badge_name, sizeof(out->badge_name), a->badge_name);
    copy_truncated(out->badge_url_unlocked, sizeof(out->badge_url_unlocked), a->badge_url);
    copy_truncated(out->badge_url_locked, sizeof(out->badge_url_locked), a->badge_locked_url);
    copy_truncated(out->measured_progress, sizeof(out->measured_progress), a->measured_progress);
    return (int32_t)RA_OK;
}

RA_FACADE_EXPORT void ra_facade_destroy_achievement_list(void* handle) {
    if (handle == NULL) return;
    ra_facade_t* facade = (ra_facade_t*)handle;
    if (facade->achievement_list == NULL) return;
    rc_client_destroy_achievement_list(facade->achievement_list);
    facade->achievement_list = NULL;
}

/* -------------------------------------------------------------------------- */
/* Diagnostic                                                                */
/* -------------------------------------------------------------------------- */

RA_FACADE_EXPORT const char* ra_facade_rcheevos_version(void) {
    /* rcheevos exposes the version string through rc_version.h. We embed a
     * compile-time string here so the value is available without including
     * rc_version.h directly (and so a corrupt .so that fails to resolve
     * rc_version still reports something sensible via this symbol). */
    return "12.4.0";
}

RA_FACADE_EXPORT const char* ra_facade_version(void) {
    return RA_FACADE_VERSION_STRING;
}
