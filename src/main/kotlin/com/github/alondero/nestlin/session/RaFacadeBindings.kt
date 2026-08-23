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

    // ------------------------------------------------------------------
    // Per-achievement list (issue #272 — loaded-game achievements window)
    // ------------------------------------------------------------------

    /**
     * True iff the active game has any achievements the runtime can list
     * (core or unofficial). Cheap. Returns 0 on no-game / unsigned-in.
     */
    fun ra_facade_has_achievements(handle: Pointer): Int

    /**
     * Allocate a fresh achievement list grouped by the official runtime's
     * progress buckets (Locked / Unlocked / Unsupported / Recently Unlocked
     * / Active Challenge / Almost There / Unsynced). The list lives on the
     * façade until [ra_facade_destroy_achievement_list] is called; the JNA
     * side MUST copy every field it intends to retain before destroying.
     *
     * `category` matches `RC_CLIENT_ACHIEVEMENT_CATEGORY_*`; `grouping`
     * matches `RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_*`. Nestlin only calls
     * this with `category=CORE`, `grouping=PROGRESS` (the issue #272
     * grouping).
     *
     * Returns RA_OK on success, RA_ERR_NO_GAME if no game is loaded.
     */
    fun ra_facade_create_achievement_list(handle: Pointer, category: Int, grouping: Int): Int

    /**
     * Number of buckets in the most-recently-created achievement list.
     * Zero if no list is active.
     */
    fun ra_facade_achievement_list_bucket_count(handle: Pointer): Int

    /**
     * Copy the [bucketIndex]'th bucket's label + achievement count into
     * [out]. The bucket's individual achievements are read via
     * [ra_facade_get_achievement_at]. Returns RA_OK on success.
     */
    fun ra_facade_get_achievement_bucket(handle: Pointer, bucketIndex: Int, out: RaAchievementBucketSlot): Int

    /**
     * Copy the [achievementIndex]'th achievement within [bucketIndex]'s
     * bucket into [out]. Every string field is COPIED into [out]'s
     * fixed-size arrays — the JNA side MUST copy what it wants to
     * retain past the call. Returns RA_OK on success, RA_ERR_INVALID_ARG
     * if the indices are out of range.
     */
    fun ra_facade_get_achievement_at(handle: Pointer, bucketIndex: Int, achievementIndex: Int, out: RaAchievementSlot): Int

    /**
     * Free the most-recently-created achievement list. Idempotent — a
     * second call without an intervening create is a no-op. After this
     * returns, the bucket/achievement indices are invalid; the JNA side
     * must not call the index accessors without first re-creating.
     */
    fun ra_facade_destroy_achievement_list(handle: Pointer)

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

    /**
     * Compute the official RA NES hash for the given ROM bytes. Writes a
     * 32-hex-char NUL-terminated string into `outHash` (must be at least
     * RA_FACADE_HASH_LEN=33 bytes). Returns RA_OK on success.
     */
    fun ra_facade_hash_nes_rom(romBytes: ByteArray, romLen: Int, outHash: ByteArray): Int

    /**
     * Snapshot the active game's user progress summary (issue #269 — boot
     * placard). Returns RA_OK on success, RA_ERR_NOT_SIGNED_IN or
     * RA_ERR_NO_GAME otherwise (zeroed `out`).
     */
    fun ra_facade_get_user_game_summary(handle: Pointer, out: RaUserGameSummary): Int

    /**
     * Snapshot the active game's title + image URL (issue #269 — boot placard).
     * Returns RA_OK on success, RA_ERR_NO_GAME otherwise.
     */
    fun ra_facade_get_game_summary(handle: Pointer, out: RaGameSummarySlot): Int

    /**
     * Block until the active load settles, polling every `pollMs` up to
     * `timeoutMs` total. Returns RA_OK when the load settles (READY / ABORTED),
     * RA_ERR_INTERNAL on timeout. Writes the final observed state into
     * `outState` (RA_LOAD_STATE_*).
     */
    fun ra_facade_wait_for_load_settle(handle: Pointer, timeoutMs: Int, pollMs: Int, outState: IntByReference): Int

    /**
     * Build the official RetroAchievements badge URL for `badgeName` into
     * `outUrl` (capacity `outUrlCapacity`). Returns RA_OK on success,
     * RA_ERR_BUFFER_TOO_SMALL if the destination is too small.
     */
    fun ra_facade_badge_url(badgeName: String, outUrl: ByteArray, outUrlCapacity: Int): Int

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
         *
         * ## Integrity + version validation (issue #273)
         *
         * Before JNA touches the library, [RaManifest.loadForCurrentPlatform]
         * verifies:
         *
         *   1. The manifest JSON is bundled in the JAR.
         *   2. A manifest entry exists for the current OS+arch.
         *   3. The library file is present in the JAR.
         *   4. The library size matches the manifest.
         *   5. The library SHA-256 matches the manifest.
         *
         * After JNA loads the library, this method also verifies:
         *
         *   6. `ra_facade_rcheevos_version()` matches the manifest's pinned
         *      rcheevos version.
         *   7. `ra_facade_version()` matches the manifest's pinned façade
         *      version.
         *
         * Any failure produces a one-line stderr diagnostic via
         * [logFailure] and returns null. The factory then falls back to
         * [NoOpRetroAchievementsService]. Credentials / tokens are never
         * part of any diagnostic — the failure messages name only the
         * platform, the file path, and the version strings.
         */
        fun load(): RaFacadeBindings? {
            // Pre-flight 1-5: manifest check (no JNA call yet).
            val preflight = RaManifest.loadForCurrentPlatform()
            if (preflight is RaManifest.LoadResult.Failure) {
                logFailure(preflight)
                return null
            }
            val success = preflight as RaManifest.LoadResult.Success

            // Extract the bundled library to a temp file and load by
            // absolute path. JNA's standard classpath-search rules look
            // for `linux-x86-64/librcheevos_facade.so` etc., not for
            // our nested `native-ra/linux/...` resource path — so we
            // can't rely on `Native.load("name", ...)` alone. Extracting
            // to a temp file is hermetic (no `java.library.path`
            // mutation) and the cleanup is registered with the JVM
            // shutdown hook.
            //
            // Pass the already-verified bytes from RaManifest so the
            // resource stream isn't reopened (issue #273 review: this
            // was loading the same library bytes twice in back-to-back
            // resource reads).
            val extracted = try {
                writeLibraryToTemp(success.entry, success.libraryBytes)
            } catch (e: Throwable) {
                return logFailureAndReturnNull(
                    RaManifest.LoadResult.Failure(
                        reason = RaManifest.LoadResult.Reason.NATIVE_LOAD_FAILED,
                        message = "Failed to extract ${success.entry.libraryFilename} " +
                            "from JAR resource ${success.entry.resourcePath} on " +
                            "${success.entry.platformId}: ${e.javaClass.simpleName}: ${e.message}",
                    )
                )
            }
            if (extracted == null) {
                return logFailureAndReturnNull(
                    RaManifest.LoadResult.Failure(
                        reason = RaManifest.LoadResult.Reason.LIBRARY_MISSING,
                        message = "Bundled library missing for ${success.entry.platformId} " +
                            "(expected at ${success.entry.resourcePath}).",
                    )
                )
            }

            // JNA load — may throw UnsatisfiedLinkError, ExceptionInInitializerError,
            // or other Throwable. All are normalised to null + structured log.
            val lib: RaFacadeBindings = try {
                Native.load(extracted.absolutePath, RaFacadeBindings::class.java)
                    ?: return logFailureAndReturnNull(
                        RaManifest.LoadResult.Failure(
                            reason = RaManifest.LoadResult.Reason.NATIVE_LOAD_FAILED,
                            message = "JNA returned null binding for rcheevos_facade on ${success.entry.platformId}.",
                        )
                    )
            } catch (e: UnsatisfiedLinkError) {
                return logFailureAndReturnNull(
                    RaManifest.LoadResult.Failure(
                        reason = RaManifest.LoadResult.Reason.NATIVE_LOAD_FAILED,
                        message = "UnsatisfiedLinkError loading rcheevos_facade on ${success.entry.platformId}: ${e.message}",
                    )
                )
            } catch (e: ExceptionInInitializerError) {
                return logFailureAndReturnNull(
                    RaManifest.LoadResult.Failure(
                        reason = RaManifest.LoadResult.Reason.NATIVE_LOAD_FAILED,
                        message = "Native library static initializer failed on ${success.entry.platformId}: " +
                            "${e.exception?.javaClass?.simpleName}: ${e.exception?.message}",
                    )
                )
            } catch (e: Throwable) {
                return logFailureAndReturnNull(
                    RaManifest.LoadResult.Failure(
                        reason = RaManifest.LoadResult.Reason.NATIVE_LOAD_FAILED,
                        message = "Unexpected load failure on ${success.entry.platformId}: " +
                            "${e.javaClass.simpleName}: ${e.message}",
                    )
                )
            }

            // Post-flight 6-7: version pinning via the library's own
            // exported symbols. A library whose checksum matched but
            // whose embedded version string disagrees with the manifest
            // is treated as "wrong library entirely" — the SHA covers
            // bytes, the version pin covers semantics. Read the
            // manifest once and reuse it (loadManifest() reparses the
            // JAR resource on every call — issue #273 review).
            val manifest = RaManifest.loadManifest()
            val expectedRcheevosVersion = manifest?.rcheevosVersion
            val expectedFacadeVersion = manifest?.facadeVersion
            val actualRcheevosVersion = try { lib.ra_facade_rcheevos_version() } catch (e: Throwable) { null }
            val actualFacadeVersion = try { lib.ra_facade_version() } catch (e: Throwable) { null }

            if (expectedRcheevosVersion != null && actualRcheevosVersion != null &&
                actualRcheevosVersion != expectedRcheevosVersion) {
                return logFailureAndReturnNull(
                    RaManifest.LoadResult.Failure(
                        reason = RaManifest.LoadResult.Reason.RCHEEVOS_VERSION_MISMATCH,
                        message = "rcheevos version mismatch: expected $expectedRcheevosVersion, " +
                            "library reports $actualRcheevosVersion.",
                    )
                )
            }
            if (expectedFacadeVersion != null && actualFacadeVersion != null &&
                actualFacadeVersion != expectedFacadeVersion) {
                return logFailureAndReturnNull(
                    RaManifest.LoadResult.Failure(
                        reason = RaManifest.LoadResult.Reason.FACADE_VERSION_MISMATCH,
                        message = "Façade version mismatch: expected $expectedFacadeVersion, " +
                            "library reports $actualFacadeVersion.",
                    )
                )
            }

            return lib
        }

        /**
         * Write [bytes] (already verified against the manifest's
         * SHA-256 by RaManifest) to a per-process temp file and
         * return its absolute path. JNA doesn't search the
         * `native-ra/<host>/` resource subdirectory by name, so we
         * have to load by absolute path.
         *
         * The temp file is named after the platform's library
         * filename so a debug `ls -la /tmp` is informative, and is
         * registered for deletion on JVM shutdown so the OS reclaims
         * it. We never write any user-controlled data to the file —
         * just the bytes the manifest's SHA-256 already pinned — so
         * a writable temp dir is the only filesystem permission we
         * require.
         *
         * Caller is responsible for the source of [bytes] — this
         * function does NOT re-read the JAR resource. It only
         * extracts already-loaded bytes to a file JNA can load.
         *
         * Returns null when [bytes] is empty (caller logs a
         * [RaManifest.LoadResult.Reason.LIBRARY_MISSING] diagnostic).
         */
        private fun writeLibraryToTemp(entry: RaManifest.Entry, bytes: ByteArray): java.io.File? {
            if (bytes.isEmpty()) return null
            val suffix = when {
                entry.libraryFilename.endsWith(".dll") -> ".dll"
                entry.libraryFilename.endsWith(".dylib") -> ".dylib"
                entry.libraryFilename.endsWith(".so") -> ".so"
                else -> ""
            }
            val tempDir = java.nio.file.Files.createTempDirectory("nestlin-ra-").toFile()
            tempDir.deleteOnExit()
            val tempFile = java.io.File(tempDir, entry.libraryFilename.removeSuffix(suffix) + suffix)
            tempFile.deleteOnExit()
            tempFile.writeBytes(bytes)
            return tempFile
        }

        /**
         * Log a [LoadResult.Failure] to stderr. The single-line format
         * is the contract the [RetroAchievementsServiceFactory] and the
         * JavaFX menu's availability indicator both rely on — see
         * [RetroAchievementsServiceFactory.create] for the call site
         * that translates "load returned null" into "fall back to NoOp".
         */
        private fun logFailure(failure: RaManifest.LoadResult.Failure) {
            System.err.println("[RA] Native library unavailable — ${failure.reason}: ${failure.message}")
        }

        private fun logFailureAndReturnNull(failure: RaManifest.LoadResult.Failure): RaFacadeBindings? {
            logFailure(failure)
            return null
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
fun interface RaReadMemoryFn : com.sun.jna.Callback {
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
 * Flat mirror of `ra_user_game_summary_t` (issue #269 — boot placard).
 * Populated by `ra_facade_get_user_game_summary` after a successful
 * identify+load. All fields are zero when no game is loaded OR no user
 * is signed in; the JNA layer checks both before invoking.
 */
internal class RaUserGameSummary : Structure() {
    @JvmField var numCoreAchievements: Int = 0
    @JvmField var numUnofficialAchievements: Int = 0
    @JvmField var numUnlockedAchievements: Int = 0
    @JvmField var numUnsupportedAchievements: Int = 0
    @JvmField var pointsCore: Int = 0
    @JvmField var pointsUnlocked: Int = 0
    override fun getFieldOrder(): List<String> = listOf(
        "numCoreAchievements",
        "numUnofficialAchievements",
        "numUnlockedAchievements",
        "numUnsupportedAchievements",
        "pointsCore",
        "pointsUnlocked",
    )
}

/**
 * Flat mirror of `ra_game_summary_t` (issue #269 — boot placard). Title
 * and image URL strings are NUL-terminated within their respective
 * fixed-size arrays; the JVM side MUST copy anything it intends to
 * retain past the call.
 */
internal class RaGameSummarySlot : Structure() {
    @JvmField var id: Int = 0
    @JvmField var title: ByteArray = ByteArray(RA_FACADE_TITLE_BUF_MAX)
    @JvmField var hash: ByteArray = ByteArray(RA_FACADE_HASH_LEN)
    @JvmField var badgeName: ByteArray = ByteArray(RA_FACADE_BADGE_NAME_MAX)
    @JvmField var imageUrl: ByteArray = ByteArray(RA_FACADE_URL_MAX)
    override fun getFieldOrder(): List<String> = listOf(
        "id", "title", "hash", "badgeName", "imageUrl",
    )

    companion object {
        // Must match the C macros in ra_facade.h.
        const val RA_FACADE_HASH_LEN = 33
        const val RA_FACADE_TITLE_BUF_MAX = 256
        const val RA_FACADE_URL_MAX = 512
        const val RA_FACADE_BADGE_NAME_MAX = 16
    }
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

// ---------------------------------------------------------------------------
// Achievement list (issue #272)
// ---------------------------------------------------------------------------

/**
 * Flat mirror of `ra_achievement_t` (issue #272 — loaded-game achievements
 * window). Strings are NUL-terminated within their fixed-size arrays;
 * the JVM side MUST copy anything it intends to retain past the call.
 *
 * Sized to comfortably hold rcheevos's documented maximums:
 *   - title: 128 chars
 *   - description: 256 chars
 *   - badge name: 16 chars
 *   - badge URL: 512 chars
 *   - measured progress text: 32 chars (e.g. "999/999", "00:59 / 02:00")
 */
internal class RaAchievementSlot : Structure() {
    @JvmField var id: Int = 0
    @JvmField var points: Int = 0
    @JvmField var state: Int = 0           // RC_CLIENT_ACHIEVEMENT_STATE_*
    @JvmField var category: Int = 0        // RC_CLIENT_ACHIEVEMENT_CATEGORY_*
    @JvmField var bucket: Int = 0          // RC_CLIENT_ACHIEVEMENT_BUCKET_*
    @JvmField var measuredPercent: Float = 0f
    @JvmField var title: ByteArray = ByteArray(RA_FACADE_ACH_TITLE_MAX)
    @JvmField var description: ByteArray = ByteArray(RA_FACADE_ACH_DESCRIPTION_MAX)
    @JvmField var badgeName: ByteArray = ByteArray(RA_FACADE_ACH_BADGE_NAME_MAX)
    @JvmField var badgeUrlUnlocked: ByteArray = ByteArray(RA_FACADE_ACH_URL_MAX)
    @JvmField var badgeUrlLocked: ByteArray = ByteArray(RA_FACADE_ACH_URL_MAX)
    @JvmField var measuredProgress: ByteArray = ByteArray(RA_FACADE_ACH_MEASURED_MAX)
    override fun getFieldOrder(): List<String> = listOf(
        "id", "points", "state", "category", "bucket", "measuredPercent",
        "title", "description", "badgeName", "badgeUrlUnlocked", "badgeUrlLocked",
        "measuredProgress",
    )

    companion object {
        const val RA_FACADE_ACH_TITLE_MAX = 128
        const val RA_FACADE_ACH_DESCRIPTION_MAX = 256
        const val RA_FACADE_ACH_BADGE_NAME_MAX = 16
        const val RA_FACADE_ACH_URL_MAX = 512
        const val RA_FACADE_ACH_MEASURED_MAX = 32
    }
}

/**
 * Flat mirror of `ra_achievement_bucket_t` (issue #272). The bucket's
 * label is rcheevos's official string ("Active Challenges" etc.); the
 * JVM side MUST copy the label past the call.
 */
internal class RaAchievementBucketSlot : Structure() {
    @JvmField var bucketType: Int = 0
    @JvmField var subsetId: Int = 0
    @JvmField var achievementCount: Int = 0
    @JvmField var label: ByteArray = ByteArray(RA_FACADE_BUCKET_LABEL_MAX)
    override fun getFieldOrder(): List<String> = listOf(
        "bucketType", "subsetId", "achievementCount", "label",
    )

    companion object {
        const val RA_FACADE_BUCKET_LABEL_MAX = 64
    }
}

/**
 * Achievement category bitmask — mirror of `RC_CLIENT_ACHIEVEMENT_CATEGORY_*`.
 * Nestlin's achievements window is core-only (issue #272 AC #1: "Load only
 * core achievements in this initial softcore release").
 */
internal object RaAchievementCategory {
    const val CORE = 1
    const val UNOFFICIAL = 2
    const val CORE_AND_UNOFFICIAL = 3
}

/**
 * Achievement list grouping — mirror of `RC_CLIENT_ACHIEVEMENT_LIST_GROUPING_*`.
 * Nestlin uses PROGRESS (the runtime's official bucket grouping; the
 * LOCK_STATE alternative would collapse Active Challenge / Almost There
 * into Locked and lose the issue #272 sections).
 */
internal object RaAchievementListGrouping {
    const val LOCK_STATE = 0
    const val PROGRESS = 1
}

/**
 * Achievement state — mirror of `RC_CLIENT_ACHIEVEMENT_STATE_*`. The
 * JNA side uses these to map rcheevos's `rc_client_achievement_t.state`
 * into Kotlin-friendly names without scattering magic numbers across
 * the service.
 */
internal object RaAchievementState {
    const val INACTIVE = 0   /* unprocessed */
    const val ACTIVE = 1     /* eligible to trigger */
    const val UNLOCKED = 2   /* earned by user */
    const val DISABLED = 3   /* not supported by this runtime */
}
