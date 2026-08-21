package com.github.alondero.nestlin.session

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.IntByReference

/**
 * JNA binding for the native RetroAchievements façade declared in
 * `native/ra_facade/ra_facade.h`. Every method here maps 1:1 to a
 * `ra_facade_*` C function. See ra_facade.h for the full contract.
 *
 * The interface is `internal` so production callers go through
 * [NativeRetroAchievementsService] (which owns the lifecycle and applies
 * the documented contract from [RetroAchievementsService]) rather than
 * touching JNA directly. Tests use the binding through a thin mock to
 * verify the no-op fallback without loading the native library.
 *
 * String return values from the C side (`ra_facade_rcheevos_version`,
 * `ra_facade_version`) are static literals — JNA returns them as Java
 * strings and they live for the lifetime of the process. Treat as
 * read-only.
 */
internal interface RaFacadeBindings : Library {

    /** Pop the next event from the façade's internal queue. See [RaEvent]. */
    fun ra_facade_poll_event(handle: Pointer, out: RaEvent): Int

    /** Drop all pending events without reading them. Idempotent. */
    fun ra_facade_clear_events(handle: Pointer)

    /**
     * Create a fresh rcheevos client with the given server URL and
     * user-agent. Both arguments may be null (the C side uses defaults).
     * Returns null on allocation failure.
     */
    fun ra_facade_create(serverUrl: String?, userAgent: String?): Pointer?

    /** Tear down the client. Safe with null. */
    fun ra_facade_destroy(handle: Pointer?): Int

    /** Sign-in state. Returns 1 when the rcheevos client holds a logged-in user, 0 otherwise. */
    fun ra_facade_is_signed_in(handle: Pointer): Int

    /**
     * Begin a password login. Asynchronous; the result arrives via the HTTP
     * bridge + an eventual `SERVER_ERROR` or `isSignedIn` poll. Returns a
     * [RaStatus] code: `ERR_LIBRARY_STATE` if a login is already in flight.
     */
    fun ra_facade_begin_login_with_password(handle: Pointer, username: String, password: String): Int

    /** Begin a token login (used for restoration at startup). Same semantics as password. */
    fun ra_facade_begin_login_with_token(handle: Pointer, username: String, token: String): Int

    /** Logout. Synchronous. Idempotent. */
    fun ra_facade_logout(handle: Pointer)

    /**
     * Snapshot the signed-in user's profile into [out]. Returns `RA_OK` on
     * success or `RA_ERR_NOT_SIGNED_IN` when no user is logged in. Strings
     * are written into the struct's fixed-size arrays; the JNA side MUST
     * copy any field it wants to retain past the call.
     */
    fun ra_facade_get_user_info(handle: Pointer, out: RaUserInfo): Int

    /**
     * Pop the next pending HTTP request into [out]. Returns 1 when a
     * request was written, 0 when the queue is empty. Strings are written
     * into the struct's fixed-size arrays; the JNA side MUST copy what it
     * intends to retain past the call.
     */
    fun ra_facade_dequeue_http_request(handle: Pointer, out: RaHttpRequestSlot): Int

    /**
     * Deliver an HTTP response back to rcheevos. The generation matches
     * the one rcheevos returned via [ra_facade_dequeue_http_request];
     * mismatches are silently dropped (the user logged out before the
     * response arrived). Returns 1 if delivered, 0 if dropped.
     */
    fun ra_facade_complete_http_request(handle: Pointer, generation: Int, status: Int, body: String?, bodyLength: Int): Int

    /** Begin loading a new game from raw ROM bytes. Returns a [RaStatus] code. */
    fun ra_facade_prepare_game(handle: Pointer, romBytes: ByteArray, romLen: Int, displayName: String?): Int

    /** Feed one emulated frame into the runtime. No-op when no game is loaded. */
    fun ra_facade_evaluate_frame(handle: Pointer, frameIndex: Long)

    /** Drive rcheevos background processing (HTTP callbacks, idle timers). */
    fun ra_facade_idle(handle: Pointer)

    /** Reset the runtime to its post-prepareGame baseline. */
    fun ra_facade_reset(handle: Pointer)

    /** Unload the active game. Idempotent. */
    fun ra_facade_unload_game(handle: Pointer)

    /** Snapshot the load state. Returns a [RaLoadState] code. */
    fun ra_facade_get_load_state(handle: Pointer): Int

    /** Snapshot the active game's info into [out]. Returns a [RaStatus] code. */
    fun ra_facade_get_game_info(handle: Pointer, out: RaGameInfo): Int

    /**
     * Install the function rcheevos will call to read emulated memory.
     * The function pointer and userdata are copied; JNA-side owns the
     * JVM callback reference for as long as needed.
     */
    fun ra_facade_set_memory_reader(handle: Pointer, fn: RaReadMemoryFn?, userdata: Pointer?): Int

    /** Required buffer size for serialize. 0 if no game is loaded. */
    fun ra_facade_progress_size(handle: Pointer): Int

    /**
     * Serialize runtime progress. Writes at most [outCapacity] bytes into
     * [out]; returns the count written, or a negative [RaStatus] on error.
     */
    fun ra_facade_serialize_progress(handle: Pointer, out: ByteArray, outCapacity: Int): Int

    /**
     * Restore runtime progress. Bad data is silently absorbed (the
     * runtime is reset to its post-prepareGame baseline).
     */
    fun ra_facade_restore_progress(handle: Pointer, data: ByteArray?, dataLen: Int): Int

    /** Build-time rcheevos version string (e.g. "12.4.0"). Static literal. */
    fun ra_facade_rcheevos_version(): String

    /** Build-time façade version string. Static literal. */
    fun ra_facade_version(): String

    companion object {
        /**
         * Load the native façade library for the current platform. Returns
         * null when the library is absent / corrupt / incompatible — the
         * Kotlin-side service treats null as "fall back to NoOp".
         *
         * JNA's standard name mapping resolves the library name
         * `rcheevos_facade` to:
         *   - rcheevos_facade.dll on Windows
         *   - librcheevos_facade.so on Linux
         *   - librcheevos_facade.dylib on macOS
         *
         * Search path: (1) bundled resources under
         * `native-ra/<platform>/`, (2) the java.library.path system
         * property (set by Gradle's JavaExec from the build/native-ra
         * directory), (3) the standard OS library search path.
         */
        fun load(): RaFacadeBindings? {
            return try {
                val lib = Native.load("rcheevos_facade", RaFacadeBindings::class.java)
                lib
            } catch (e: UnsatisfiedLinkError) {
                // Library not on the path or failed to load. Logged at
                // the call site (NativeRetroAchievementsService.tryLoad)
                // so the diagnostic reaches stderr exactly once.
                null
            } catch (e: ExceptionInInitializerError) {
                // Static initializer in the native library threw — usually
                // a version skew (rcheevos expects a newer libc, etc.).
                null
            } catch (e: Throwable) {
                // Defensive: any other JNA failure (corrupt .so, missing
                // symbol, etc.) also falls back to NoOp.
                null
            }
        }
    }
}

/**
 * The C-side `ra_facade_read_memory_fn` signature. JNA maps this to a
 * Java `interface` with one method; instances are passed by reference.
 *
 * No userdata parameter — the C shim passes the façade handle as the
 * 4th argument, but JNA's standard callback mapping doesn't expose it
 * cleanly, so the JVM-side uses a [ThreadLocal] handle stack instead
 * (set by `evaluate_frame` before the native call returns). See
 * `NativeRetroAchievementsService.currentHandle`.
 */
internal fun interface RaReadMemoryFn {
    fun read(address: Int, buffer: ByteArray, numBytes: Int): Int
}

/**
 * Flat mirror of the C-side `ra_event_t` struct. JNA's
 * Structure.FieldOrder + read() / write() round-trip handles the
 * marshalling; the JVM side MUST call write() before passing to
 * poll_event and read() afterwards.
 *
 * Field widths match ra_facade.h exactly. The trailing char arrays are
 * sized by the C macros RA_FACADE_TITLE_MAX etc.
 */
internal class RaEvent : Structure() {

    @JvmField var type: Int = 0

    // Achievement fields
    @JvmField var achievementId: Int = 0
    @JvmField var achievementPoints: Int = 0
    @JvmField var achievementTitle: ByteArray = ByteArray(RA_FACADE_TITLE_MAX)
    @JvmField var achievementDescription: ByteArray = ByteArray(RA_FACADE_DESCRIPTION_MAX)
    @JvmField var achievementBadge: ByteArray = ByteArray(RA_FACADE_BADGE_MAX)

    // Leaderboard fields
    @JvmField var leaderboardId: Int = 0
    @JvmField var leaderboardFormat: Int = 0
    @JvmField var leaderboardTracker: ByteArray = ByteArray(RA_FACADE_TRACKER_MAX)
    @JvmField var leaderboardLowerIsBetter: Int = 0

    // Progress indicator
    @JvmField var measuredPercent: Float = 0f

    // Server error
    @JvmField var serverResultCode: Int = 0
    @JvmField var serverErrorMessage: ByteArray = ByteArray(RA_FACADE_ERROR_MAX)
    @JvmField var serverApiPath: ByteArray = ByteArray(RA_FACADE_API_MAX)
    @JvmField var serverRelatedId: Int = 0

    override fun getFieldOrder(): List<String> = listOf(
        "type",
        "achievementId", "achievementPoints", "achievementTitle", "achievementDescription", "achievementBadge",
        "leaderboardId", "leaderboardFormat", "leaderboardTracker", "leaderboardLowerIsBetter",
        "measuredPercent",
        "serverResultCode", "serverErrorMessage", "serverApiPath", "serverRelatedId",
    )

    companion object {
        // Must match the C macros in ra_facade.h.
        const val RA_FACADE_TITLE_MAX = 128
        const val RA_FACADE_DESCRIPTION_MAX = 256
        const val RA_FACADE_BADGE_MAX = 16
        const val RA_FACADE_TRACKER_MAX = 64
        const val RA_FACADE_ERROR_MAX = 256
        const val RA_FACADE_API_MAX = 128
    }
}

/** Flat mirror of `ra_game_info_t`. */
internal class RaGameInfo : Structure() {
    @JvmField var state: Int = 0
    @JvmField var gameId: Int = 0
    @JvmField var hasAchievements: Int = 0
    @JvmField var hasLeaderboards: Int = 0
    @JvmField var hardcoreEnabled: Int = 0
    override fun getFieldOrder(): List<String> = listOf(
        "state", "gameId", "hasAchievements", "hasLeaderboards", "hardcoreEnabled",
    )
}

/**
 * Flat mirror of the C-side `ra_user_info_t`. Field widths match
 * ra_facade.h exactly; the JVM side MUST call write() before passing to
 * `ra_facade_get_user_info` and read() afterwards.
 */
internal class RaUserInfo : Structure() {
    @JvmField var username: ByteArray = ByteArray(RA_FACADE_USERNAME_MAX)
    @JvmField var displayName: ByteArray = ByteArray(RA_FACADE_DISPLAY_NAME_MAX)
    @JvmField var avatarUrl: ByteArray = ByteArray(RA_FACADE_AVATAR_URL_MAX)
    @JvmField var token: ByteArray = ByteArray(RA_FACADE_TOKEN_MAX)
    @JvmField var score: Int = 0
    @JvmField var scoreSoftcore: Int = 0
    @JvmField var numUnreadMessages: Int = 0
    override fun getFieldOrder(): List<String> = listOf(
        "username", "displayName", "avatarUrl", "token",
        "score", "scoreSoftcore", "numUnreadMessages",
    )

    companion object {
        // Must match the C macros in ra_facade.h.
        const val RA_FACADE_USERNAME_MAX = 128
        const val RA_FACADE_DISPLAY_NAME_MAX = 128
        const val RA_FACADE_AVATAR_URL_MAX = 256
        const val RA_FACADE_TOKEN_MAX = 64
    }
}

/**
 * Flat mirror of `ra_http_request_t`. One entry from the C-side HTTP queue;
 * the JVM side passes this to `ra_facade_dequeue_http_request`, copies the
 * strings it needs, then reuses the struct for the next poll.
 */
internal class RaHttpRequestSlot : Structure() {
    @JvmField var generation: Int = 0
    @JvmField var url: ByteArray = ByteArray(RA_FACADE_HTTP_URL_MAX)
    @JvmField var postData: ByteArray = ByteArray(RA_FACADE_HTTP_BODY_MAX)
    @JvmField var contentType: ByteArray = ByteArray(RA_FACADE_HTTP_CONTENT_TYPE_MAX)
    @JvmField var hasPostData: Byte = 0
    @JvmField var reserved: ByteArray = ByteArray(3)
    override fun getFieldOrder(): List<String> = listOf(
        "generation", "url", "postData", "contentType", "hasPostData", "reserved",
    )

    companion object {
        // Must match the C macros in ra_facade.h.
        const val RA_FACADE_HTTP_URL_MAX = 512
        const val RA_FACADE_HTTP_BODY_MAX = 4096
        const val RA_FACADE_HTTP_CONTENT_TYPE_MAX = 64
    }
}

// ---------------------------------------------------------------------------
// C enum constants — mirrored from ra_facade.h. Kept here (not in the
// companion) so they're easy to grep and update against the header.
// ---------------------------------------------------------------------------

internal object RaStatus {
    const val OK = 0
    const val ERR_NULL_HANDLE = -1
    const val ERR_INVALID_ARG = -2
    const val ERR_BUFFER_TOO_SMALL = -3
    const val ERR_NO_GAME = -4
    const val ERR_LIBRARY_STATE = -5
    const val ERR_NOT_SIGNED_IN = -6
    const val ERR_INTERNAL = -7
    const val ERR_DESTROYED = -8
}

internal object RaLoadState {
    const val IDLE = 0
    const val AWAITING_LOGIN = 1
    const val IDENTIFYING = 2
    const val STARTING = 3
    const val READY = 4
    const val FAILED = 5
    const val ABORTED = 6
}

internal object RaEventType {
    const val NONE = 0
    const val ACHIEVEMENT_TRIGGERED = 1
    const val ACHIEVEMENT_CHALLENGE_SHOW = 2
    const val ACHIEVEMENT_CHALLENGE_HIDE = 3
    const val ACHIEVEMENT_PROGRESS_SHOW = 4
    const val ACHIEVEMENT_PROGRESS_HIDE = 5
    const val ACHIEVEMENT_PROGRESS_UPDATE = 6
    const val LEADERBOARD_STARTED = 7
    const val LEADERBOARD_FAILED = 8
    const val LEADERBOARD_SUBMITTED = 9
    const val LEADERBOARD_TRACKER_SHOW = 10
    const val LEADERBOARD_TRACKER_HIDE = 11
    const val LEADERBOARD_TRACKER_UPDATE = 12
    const val LEADERBOARD_SCOREBOARD = 13
    const val GAME_COMPLETED = 14
    const val RESET = 15
    const val SERVER_ERROR = 16
    const val DISCONNECTED = 17
    const val RECONNECTED = 18
}
