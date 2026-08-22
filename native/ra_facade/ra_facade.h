/*
 * ra_facade.h — Flat C ABI between Nestlin (Kotlin/JNA) and rcheevos v12.4.0.
 *
 * Why this file exists
 * --------------------
 * The Nestlin project integrates RetroAchievements as an OPTIONAL, observable
 * capability (issue #267). The seam between JVM code and the rcheevos C
 * library is `RetroAchievementsService` (see
 * src/main/kotlin/.../session/RetroAchievementsService.kt). That seam is
 * deliberately narrow — no native pointers, no rcheevos headers, no JNA types
 * leak into production Kotlin code. The job of THIS file is to define the
 * narrow C ABI that JNA loads against: a flat, copied, owned-by-the-façade
 * set of functions that the JNA side can map 1:1.
 *
 * What this façade is responsible for
 * -----------------------------------
 *  - Owning the rc_client_t* and every other native pointer for the lifetime
 *    of one ra_facade_t instance. JNA treats the handle as an opaque long;
 *    the façade never exposes a rc_client_t* across the boundary.
 *  - Forcing softcore mode immediately after rc_client_create (the issue
 *    explicitly requires this — Nestlin never participates in hardcore).
 *  - Copying every string / buffer it returns out of native memory into
 *    caller-owned C arrays (no borrowed native pointers in the API surface).
 *  - Never blocking on the network during prepare_game or evaluate_frame —
 *    rcheevos does its HTTP work asynchronously; we surface the state via
 *    ra_facade_get_load_state() so the JNA layer can poll on its own clock.
 *  - Mapping every rcheevos failure path (RC_NO_GAME_LOADED, RC_INVALID_STATE,
 *    network errors, etc.) onto one of the flat RA_ERR_* codes so the JNA
 *    side never has to interpret rcheevos-specific enums.
 *
 * What this façade is NOT responsible for
 * ---------------------------------------
 *  - Authentication (login/logout, token storage) — owned by issue #268.
 *  - UI (toast, achievement popups, leaderboard tracker) — owned by the
 *    JavaFX layer, fed by events the façade queues via ra_facade_poll_event.
 *  - Save-state integration (slot manager hooks) — owned by issue #268.
 *
 * Threading model
 * ---------------
 * Single-threaded by design. Nestlin's per-frame wiring calls into the façade
 * from the emulation thread; JNA's standard mapping does not support a C
 * thread calling back into JVM-managed callbacks without an explicit
 * AttachCurrentThread. We deliberately do not wire any host callbacks into
 * rcheevos (the read-memory callback runs synchronously on the calling
 * thread, see ra_facade_set_memory_reader). For future-proofing, the
 * rc_client message / event callbacks run on the same thread that invokes
 * rc_client_idle() / rc_client_do_frame(); we drain them via a synchronous
 * queue that ra_facade_poll_event reads from.
 */

#ifndef NESTLIN_RA_FACADE_H
#define NESTLIN_RA_FACADE_H

#include <stdint.h>
#include <stddef.h>

#ifdef __cplusplus
extern "C" {
#endif

#if defined(_WIN32)
#  if defined(RA_FACADE_BUILDING)
#    define RA_FACADE_EXPORT __declspec(dllexport)
#  else
#    define RA_FACADE_EXPORT __declspec(dllimport)
#  endif
#else
#  define RA_FACADE_EXPORT __attribute__((visibility("default")))
#endif

/* -------------------------------------------------------------------------- */
/* Status codes (flat — every rcheevos failure path maps to one of these)      */
/* -------------------------------------------------------------------------- */

typedef enum ra_status_e {
    RA_OK                    =  0,
    RA_ERR_NULL_HANDLE       = -1,  /* handle is NULL or already destroyed */
    RA_ERR_INVALID_ARG       = -2,  /* NULL rom_bytes with rom_len > 0, etc. */
    RA_ERR_BUFFER_TOO_SMALL  = -3,  /* out_capacity insufficient for serialize */
    RA_ERR_NO_GAME           = -4,  /* operation requires an active game */
    RA_ERR_LIBRARY_STATE     = -5,  /* rc_client_t* is in an inconsistent state */
    RA_ERR_NOT_SIGNED_IN     = -6,  /* operation requires a logged-in user */
    RA_ERR_INTERNAL          = -7,  /* rcheevos returned an unrecognised error */
    RA_ERR_DESTROYED         = -8   /* handle was destroyed mid-call */
} ra_status_t;

/* -------------------------------------------------------------------------- */
/* Load state (subset of RC_CLIENT_LOAD_GAME_STATE_* that Nestlin surfaces)   */
/* -------------------------------------------------------------------------- */

typedef enum ra_load_state_e {
    RA_LOAD_STATE_IDLE            = 0, /* no game prepared yet */
    RA_LOAD_STATE_AWAITING_LOGIN  = 1, /* need a logged-in user (issue #268) */
    RA_LOAD_STATE_IDENTIFYING     = 2, /* hashing + server lookup */
    RA_LOAD_STATE_STARTING        = 3, /* fetching achievement set */
    RA_LOAD_STATE_READY           = 4, /* runtime active, evaluating */
    RA_LOAD_STATE_FAILED          = 5, /* network/auth error */
    RA_LOAD_STATE_ABORTED         = 6  /* explicit unload or shutdown */
} ra_load_state_t;

/* -------------------------------------------------------------------------- */
/* Event types (subset of RC_CLIENT_EVENT_TYPE_* the façade surfaces to JNA)  */
/* -------------------------------------------------------------------------- */

typedef enum ra_event_type_e {
    RA_EVENT_NONE                            =  0,
    RA_EVENT_ACHIEVEMENT_TRIGGERED           =  1,
    RA_EVENT_ACHIEVEMENT_CHALLENGE_SHOW      =  2,
    RA_EVENT_ACHIEVEMENT_CHALLENGE_HIDE      =  3,
    RA_EVENT_ACHIEVEMENT_PROGRESS_SHOW       =  4,
    RA_EVENT_ACHIEVEMENT_PROGRESS_HIDE       =  5,
    RA_EVENT_ACHIEVEMENT_PROGRESS_UPDATE     =  6,
    RA_EVENT_LEADERBOARD_STARTED             =  7,
    RA_EVENT_LEADERBOARD_FAILED              =  8,
    RA_EVENT_LEADERBOARD_SUBMITTED           =  9,
    RA_EVENT_LEADERBOARD_TRACKER_SHOW        = 10,
    RA_EVENT_LEADERBOARD_TRACKER_HIDE        = 11,
    RA_EVENT_LEADERBOARD_TRACKER_UPDATE      = 12,
    RA_EVENT_LEADERBOARD_SCOREBOARD          = 13,
    RA_EVENT_GAME_COMPLETED                  = 14,
    RA_EVENT_RESET                           = 15,
    RA_EVENT_SERVER_ERROR                    = 16,
    RA_EVENT_DISCONNECTED                    = 17,
    RA_EVENT_RECONNECTED                     = 18
} ra_event_type_t;

/* -------------------------------------------------------------------------- */
/* Game info snapshot (returned by ra_facade_get_game_info)                    */
/* -------------------------------------------------------------------------- */

typedef struct ra_game_info_s {
    int32_t  state;                  /* ra_load_state_t at the time of call */
    uint32_t game_id;                /* 0 if unknown / not loaded */
    int32_t  has_achievements;       /* 0/1 */
    int32_t  has_leaderboards;       /* 0/1 */
    int32_t  hardcore_enabled;       /* always 0 — Nestlin forces softcore */
} ra_game_info_t;

/* -------------------------------------------------------------------------- */
/* Event payload (returned by ra_facade_poll_event)                           */
/*                                                                            */
/* Strings are COPIES, owned by the façade on poll-out, overwritten on the    */
/* next poll. The JNA side MUST copy any string it intends to retain past the */
/* call.                                                                      */
/* -------------------------------------------------------------------------- */

#define RA_FACADE_TITLE_MAX        128
#define RA_FACADE_DESCRIPTION_MAX  256
#define RA_FACADE_BADGE_MAX         16
#define RA_FACADE_ERROR_MAX        256
#define RA_FACADE_API_MAX          128
#define RA_FACADE_TRACKER_MAX       64

/* HTTP bridge (issue #268) */
#define RA_FACADE_HTTP_URL_MAX            512
#define RA_FACADE_HTTP_BODY_MAX          4096
#define RA_FACADE_HTTP_CONTENT_TYPE_MAX    64

/* User info (issue #268) */
#define RA_FACADE_USERNAME_MAX           128
#define RA_FACADE_DISPLAY_NAME_MAX       128
#define RA_FACADE_AVATAR_URL_MAX         256
#define RA_FACADE_TOKEN_MAX               64

/* Snapshot of the signed-in user's profile. Strings are NUL-terminated within
 * their respective fixed-size arrays; the JVM side copies what it retains. */
typedef struct ra_user_info_s {
    char     username[RA_FACADE_USERNAME_MAX];
    char     display_name[RA_FACADE_DISPLAY_NAME_MAX];
    char     avatar_url[RA_FACADE_AVATAR_URL_MAX];
    char     token[RA_FACADE_TOKEN_MAX];
    uint32_t score;
    uint32_t score_softcore;
    uint32_t num_unread_messages;
} ra_user_info_t;

typedef struct ra_event_s {
    int32_t  type;                       /* ra_event_type_e */

    /* Populated for RA_EVENT_ACHIEVEMENT_*, RA_EVENT_GAME_COMPLETED */
    uint32_t achievement_id;
    uint32_t achievement_points;
    char     achievement_title[RA_FACADE_TITLE_MAX];
    char     achievement_description[RA_FACADE_DESCRIPTION_MAX];
    char     achievement_badge[RA_FACADE_BADGE_MAX];

    /* Populated for RA_EVENT_LEADERBOARD_TRACKER_*, RA_EVENT_LEADERBOARD_* */
    uint32_t leaderboard_id;
    int32_t  leaderboard_format;         /* 0=TIME, 1=SCORE, 2=VALUE */
    char     leaderboard_tracker[RA_FACADE_TRACKER_MAX];
    int32_t  leaderboard_lower_is_better;

    /* Populated for RA_EVENT_ACHIEVEMENT_PROGRESS_UPDATE */
    float    measured_percent;

    /* Populated for RA_EVENT_SERVER_ERROR */
    int32_t  server_result_code;
    char     server_error_message[RA_FACADE_ERROR_MAX];
    char     server_api_path[RA_FACADE_API_MAX];
    uint32_t server_related_id;
} ra_event_t;

/* -------------------------------------------------------------------------- */
/* Memory reader (caller-supplied; runs synchronously on the eval thread)    */
/* -------------------------------------------------------------------------- */

/*
 * Read `num_bytes` from `address` into `buffer`. Returns the number of bytes
 * actually read (rcheevos treats a short read as a partial fill, which is
 * fine for the indirection layer we use). Returning 0 means "address not
 * readable"; rcheevos treats that as the read returning 0.
 *
 * The function is invoked synchronously on whichever thread called
 * ra_facade_evaluate_frame(). The implementation MUST NOT call back into
 * ra_facade_* during the call (it runs under the rc_client state lock).
 */
typedef uint32_t (*ra_facade_read_memory_fn)(uint32_t address,
                                             uint8_t* buffer,
                                             uint32_t num_bytes,
                                             void* userdata);

/* -------------------------------------------------------------------------- */
/* Lifecycle                                                                 */
/* -------------------------------------------------------------------------- */

/*
 * Create a fresh rcheevos client.
 *
 * `server_url` is the RA server root (e.g. "https://retroachievements.org").
 * Pass NULL to use the default. The string is copied.
 *
 * `user_agent` is the client identifier sent in HTTP headers (e.g.
 * "Nestlin/1.0"). Pass NULL to use the default. The string is copied.
 *
 * Forces hardcore=off immediately after rc_client_create (per issue #267).
 *
 * Returns NULL on failure (out of memory, rcheevos internal init failed).
 * The caller MUST check the return value; a NULL handle passed to any
 * other ra_facade_* returns RA_ERR_NULL_HANDLE.
 */
RA_FACADE_EXPORT void* ra_facade_create(const char* server_url,
                                        const char* user_agent);

/*
 * Tear down the client. Frees every owned buffer, the rc_client_t*, the
 * queued events, and the user-supplied memory-reader closure. Safe to call
 * with NULL (no-op). After this returns, the handle is invalid and MUST
 * NOT be passed to any other ra_facade_* call.
 */
RA_FACADE_EXPORT int32_t ra_facade_destroy(void* handle);

/*
 * Sign-in state. Returns 1 when the rcheevos client currently holds a logged-in
 * user (after a successful password OR token login), 0 otherwise. The value is
 * a snapshot — call again later to re-check after a login / logout cycle.
 */
RA_FACADE_EXPORT int32_t ra_facade_is_signed_in(void* handle);

/*
 * Begin a password login. Asynchronous — the actual HTTP round-trip happens
 * via the ra_facade HTTP bridge (issue #268); rc_client fires its callback
 * when the response arrives or fails. Returns RA_OK when the request was
 * accepted; RA_ERR_LIBRARY_STATE when a login/logout is already in flight.
 *
 * `username` and `password` are UTF-8 NUL-terminated. Both are COPIED — the
 * caller may zero/free its buffers immediately on return. The password is
 * never persisted in façade-internal state and is dropped after the request
 * is enqueued.
 *
 * Single-flight: a second call before the first callback fires returns
 * RA_ERR_LIBRARY_STATE; the caller must wait for the previous attempt to
 * settle (success, failure, or explicit logout).
 */
RA_FACADE_EXPORT int32_t ra_facade_begin_login_with_password(void* handle,
                                                             const char* username,
                                                             const char* password);

/*
 * Begin a token login (used for token restoration at Nestlin startup). Same
 * semantics as ra_facade_begin_login_with_password — asynchronous, single-flight,
 * both arguments copied.
 */
RA_FACADE_EXPORT int32_t ra_facade_begin_login_with_token(void* handle,
                                                          const char* username,
                                                          const char* token);

/*
 * Logout. Tears down the current user session and invalidates the cached
 * token. Synchronous. Idempotent — safe to call when not signed in.
 */
RA_FACADE_EXPORT void ra_facade_logout(void* handle);

/*
 * Snapshot the signed-in user's profile into `out`. Strings are written into
 * fixed-size NUL-terminated buffers owned by `out`. Returns RA_OK on success,
 * RA_ERR_NOT_SIGNED_IN when no user is logged in.
 */
RA_FACADE_EXPORT int32_t ra_facade_get_user_info(void* handle,
                                                 ra_user_info_t* out);

/* -------------------------------------------------------------------------- */
/* HTTP bridge (issue #268)                                                   */
/*                                                                            */
/* rcheevos builds an HTTP request (URL + method + optional POST body) and    */
/* hands it to the server-call callback. Until #268 the façade returned a     */
/* stub CLIENT_ERROR; the HTTP bridge replaces that stub by enqueuing the     */
/* request, then waiting for the Kotlin side to POST the response back via    */
/* ra_facade_complete_http_request. The Kotlin side drives the queue from a   */
/* background thread (see src/.../session/RaHttpBridge.kt).                   */
/* -------------------------------------------------------------------------- */

/*
 * One queued HTTP request. Strings are NUL-terminated; the JVM side MUST copy
 * any field it intends to retain past the call. The generation field matches
 * the facade's current generation when the request was enqueued; the JVM
 * side uses it to drop stale responses after logout / a newer login.
 */
typedef struct ra_http_request_s {
    uint32_t generation;          /* matches facade->generation at enqueue time */
    char     url[RA_FACADE_HTTP_URL_MAX];
    char     post_data[RA_FACADE_HTTP_BODY_MAX];
    char     content_type[RA_FACADE_HTTP_CONTENT_TYPE_MAX];
    uint8_t  has_post_data;       /* 1 = POST with body, 0 = GET */
    uint8_t  reserved[3];         /* explicit padding for stable struct layout */
} ra_http_request_t;

/*
 * Pop the next pending HTTP request into `out`. Returns 1 if a request was
 * written, 0 if the queue is empty. The request is left on the queue until
 * the matching ra_facade_complete_http_request call delivers the response.
 */
RA_FACADE_EXPORT int32_t ra_facade_dequeue_http_request(void* handle,
                                                        ra_http_request_t* out);

/*
 * Deliver the HTTP response back to rcheevos. `status` is the HTTP status
 * code (e.g. 200, 401, 500); pass a negative rc_api value (e.g.
 * RC_API_SERVER_RESPONSE_RETRYABLE_CLIENT_ERROR) for transport failures
 * that rcheevos should treat as retryable. `body` may be NULL with
 * body_length=0 for empty/error responses.
 *
 * The generation in the original request and the facade's current generation
 * are compared — a stale response (from before logout / a newer login) is
 * dropped silently. Returns 1 if delivered, 0 if dropped.
 */
RA_FACADE_EXPORT int32_t ra_facade_complete_http_request(void* handle,
                                                          uint32_t generation,
                                                          int32_t status,
                                                          const char* body,
                                                          int32_t body_length);

/* -------------------------------------------------------------------------- */
/* Game lifecycle                                                            */
/* -------------------------------------------------------------------------- */

/*
 * Begin loading a new game from raw ROM bytes.
 *
 * The façade computes the NES hash via rc_hash_generate_from_buffer and
 * calls rc_client_begin_identify_and_load_game. The call returns immediately;
 * the actual fetch happens asynchronously. Poll ra_facade_get_load_state to
 * observe progress.
 *
 * `rom_bytes` is copied into rcheevos-owned memory; the caller may free its
 * copy as soon as this function returns.
 *
 * `display_name` is a UTF-8 NUL-terminated string used for logging / UI.
 * Pass NULL if you have no name. Copied.
 *
 * Returns RA_OK if the request was accepted. RA_ERR_NOT_SIGNED_IN if no user
 * is authenticated (issue #268). RA_ERR_INVALID_ARG on bad inputs.
 * RA_ERR_LIBRARY_STATE if the client is currently busy with another game.
 */
RA_FACADE_EXPORT int32_t ra_facade_prepare_game(void* handle,
                                                const uint8_t* rom_bytes,
                                                int32_t rom_len,
                                                const char* display_name);

/*
 * Feed one emulated frame into the runtime. Calls rc_client_do_frame which
 * invokes the registered memory reader synchronously and runs the condition
 * evaluator. Drains any events queued during the frame into the internal
 * event queue (call ra_facade_poll_event to read them out).
 *
 * `frame_index` is the monotonic frame counter the coordinator passed in;
 * currently only used for logging.
 *
 * Safe to call when no game is prepared (no-op).
 */
RA_FACADE_EXPORT void ra_facade_evaluate_frame(void* handle,
                                               int64_t frame_index);

/*
 * Drive rcheevos background processing (HTTP callbacks, idle timers). The
 * Nestlin loop calls this from the same per-frame tick as evaluate_frame
 * after the event drain. Cheap when there's nothing to do.
 */
RA_FACADE_EXPORT void ra_facade_idle(void* handle);

/*
 * Reset the runtime to its post-prepareGame baseline (matches the
 * RC_CLIENT_EVENT_RESET signal rcheevos sends). Does NOT unload the game
 * or change the load state.
 */
RA_FACADE_EXPORT void ra_facade_reset(void* handle);

/*
 * Unload the active game. Idempotent. After this returns, the load state
 * is RA_LOAD_STATE_IDLE and serialize returns 0.
 */
RA_FACADE_EXPORT void ra_facade_unload_game(void* handle);

/*
 * Snapshot the load state. Cheap.
 */
RA_FACADE_EXPORT int32_t ra_facade_get_load_state(void* handle);

/*
 * Snapshot the active game's info. Strings are not returned (none of the
 * fields are string-typed); the result is fully populated by the time the
 * call returns.
 *
 * Returns RA_OK on success, RA_ERR_NO_GAME if no game is loaded.
 */
RA_FACADE_EXPORT int32_t ra_facade_get_game_info(void* handle,
                                                 ra_game_info_t* out);

/* -------------------------------------------------------------------------- */
/* Memory reader (set BEFORE prepare_game; replaced on each new game)         */
/* -------------------------------------------------------------------------- */

/*
 * Install the function rcheevos will call to read emulated memory. The
 * function pointer and userdata are copied; the JNA side keeps its own
 * strong reference to the JVM callback for as long as needed.
 *
 * Pass NULL to clear the reader (rcheevos will then read zeros, which is
 * the same behaviour as having no game loaded).
 */
RA_FACADE_EXPORT int32_t ra_facade_set_memory_reader(void* handle,
                                                     ra_facade_read_memory_fn fn,
                                                     void* userdata);

/* -------------------------------------------------------------------------- */
/* Progress serialization (for save-state slot manager, issue #268)           */
/* -------------------------------------------------------------------------- */

/*
 * Compute the required buffer size for serialize_progress. Returns 0 if no
 * game is prepared or the service is unsigned-in (matches
 * RA_ERR_NO_GAME / RA_ERR_NOT_SIGNED_IN semantics — caller checks both).
 */
RA_FACADE_EXPORT int32_t ra_facade_progress_size(void* handle);

/*
 * Copy the runtime's serialized progress into `out`. The façade writes at
 * most `out_capacity` bytes and returns the count written.
 *
 * Returns 0 (and writes nothing) if no game is prepared.
 * Returns a negative ra_status_t on error.
 */
RA_FACADE_EXPORT int32_t ra_facade_serialize_progress(void* handle,
                                                      uint8_t* out,
                                                      int32_t out_capacity);

/*
 * Restore runtime progress from a previously-serialized buffer. Silently
 * no-ops on length mismatch / corrupt data per the contract on
 * RetroAchievementsService.restoreProgress — the runtime is reset to its
 * post-prepareGame baseline on bad input.
 */
RA_FACADE_EXPORT int32_t ra_facade_restore_progress(void* handle,
                                                    const uint8_t* data,
                                                    int32_t data_len);

/* -------------------------------------------------------------------------- */
/* Event queue (drain after each evaluate_frame)                              */
/* -------------------------------------------------------------------------- */

/*
 * Pop the next pending event into `out`. Returns 1 if an event was written,
 * 0 if the queue was empty. Strings in `out` are owned copies; the caller
 * must copy anything it wants to retain past the next poll.
 */
RA_FACADE_EXPORT int32_t ra_facade_poll_event(void* handle, ra_event_t* out);

/*
 * Discard all pending events without reading them. Called on
 * unload_game / shutdown so a stale event from the previous game never
 * reaches the new one's UI.
 */
RA_FACADE_EXPORT void ra_facade_clear_events(void* handle);

/* -------------------------------------------------------------------------- */
/* Diagnostic (for tests + the UI menu's availability indicator)              */
/* -------------------------------------------------------------------------- */

/*
 * Build-time rcheevos version string (e.g. "12.4.0"). The buffer is a
 * static literal — no copy needed, valid for the lifetime of the process.
 */
RA_FACADE_EXPORT const char* ra_facade_rcheevos_version(void);

/*
 * Library version of THIS façade (e.g. "1.0.0"). Same lifetime rule.
 */
RA_FACADE_EXPORT const char* ra_facade_version(void);

#ifdef __cplusplus
}  /* extern "C" */
#endif

#endif  /* NESTLIN_RA_FACADE_H */
