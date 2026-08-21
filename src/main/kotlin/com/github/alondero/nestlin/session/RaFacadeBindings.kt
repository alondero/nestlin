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

    /** Sign-in state. The no-network shim in the C side always reports 0. */
    fun ra_facade_is_signed_in(handle: Pointer): Int

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
