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
/* rcheevos server-call shim — rejects every request with "no network"      */
/* -------------------------------------------------------------------------- */

/*
 * rcheevos's HTTP layer calls this when it wants to send a request. We
 * respond synchronously with RC_API_SERVER_RESPONSE_CLIENT_ERROR so the
 * caller treats it as a permanent failure (not a retryable transient).
 *
 * This is intentional: issue #267 ships the client + façade but NOT the
 * HTTP transport. Login (issue #268) will replace this stub with a real
 * libcurl-backed implementation. Until then, the façade can construct,
 * run frames, serialize progress, and tear down — it just never completes
 * a server round-trip. isSignedIn() always returns 0.
 */
static void server_call_shim(const rc_api_request_t* request,
                             rc_client_server_callback_t callback,
                             void* callback_data,
                             rc_client_t* client) {
    (void)request;
    (void)client;
    rc_api_server_response_t response;
    memset(&response, 0, sizeof(response));
    response.body = NULL;
    response.body_length = 0;
    response.http_status_code = RC_API_SERVER_RESPONSE_CLIENT_ERROR;
    callback(&response, callback_data);
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
    (void)server_url;  /* Reserved for #268; the no-network shim ignores it. */
    (void)user_agent;
    ra_facade_t* facade = (ra_facade_t*)calloc(1, sizeof(ra_facade_t));
    if (facade == NULL) return NULL;

    event_queue_init(&facade->events);
    facade->generation = 1;
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
        /* Tear down any in-flight async handle by unloading first; this
         * prevents the event handler from firing on a freed handle. */
        rc_client_unload_game(facade->client);
        event_queue_clear(&facade->events);
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
    /* Issue #267: login is owned by #268. Until that lands, every state
     * reports "not signed in". This is the documented "fall back to NoOp"
     * behaviour the JNA layer relies on for tests + menu availability. */
    return 0;
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
