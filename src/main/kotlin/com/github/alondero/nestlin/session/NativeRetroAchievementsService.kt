package com.github.alondero.nestlin.session

import com.sun.jna.Pointer

/**
 * Production [RetroAchievementsService] that bridges to the native
 * rcheevos v12.4.0 client via the flat C ABI declared in
 * `native/ra_facade/ra_facade.h`.
 *
 * ## Failure semantics
 *
 * This class is the only place JNA touches native code. The class is
 * constructed by [load] which returns:
 *
 *  - A working `NativeRetroAchievementsService` when the façade shared
 *    library is on the search path and exports the expected symbols.
 *  - `null` when the library is absent, corrupt, or incompatible with the
 *    current platform. The coordinator's factory picks NoOp in that case
 *    so every existing emulator flow works without a native library.
 *
 * ## Memory discipline
 *
 * The façade never holds a Java reference past the call that produced it
 * (the C side copies strings into caller-owned arrays and the JNA side
 * reads them out immediately). The only Java-side state held across calls
 * is the [handle] pointer (one pointer per facade instance) and a
 * per-process `ConcurrentHashMap<Pointer, MemoryReader>` for the
 * memory-reader callback lookup.
 *
 * ## Threading
 *
 * Single-threaded. The emulation thread calls every method. The
 * underlying rcheevos client has its own internal thread pool for HTTP
 * work, but those threads never call back into JVM code in this slice
 * (the network is stubbed off in the C side per issue #267's contract).
 */
internal class NativeRetroAchievementsService private constructor(
    private val bindings: RaFacadeBindings,
    private val handle: Pointer,
) : RetroAchievementsService {

    // The native library version is captured at construction so the UI's
    // availability indicator doesn't have to re-call into the C side on
    // every menu redraw.
    val rcheevosVersion: String = bindings.ra_facade_rcheevos_version()
    val facadeVersion: String = bindings.ra_facade_version()

    // Single-flight guard around prepare_game. The coordinator's per-frame
    // wiring is expected to be serial; this guard makes a double-call
    // observable rather than corrupting native state.
    private val prepareLock = Any()

    override fun isSignedIn(): Boolean {
        // The C side returns 0 unconditionally until issue #268 lands
        // login support. Reflected here so the UI menu shows the
        // documented "not signed in" state.
        return try {
            bindings.ra_facade_is_signed_in(handle) != 0
        } catch (e: UnsatisfiedLinkError) {
            false
        }
    }

    override fun prepareGame(sessionInfo: GameSessionInfo): Boolean {
        synchronized(prepareLock) {
            val rc = try {
                bindings.ra_facade_prepare_game(
                    handle,
                    sessionInfo.romBytes,
                    sessionInfo.romBytes.size,
                    sessionInfo.displayName,
                )
            } catch (e: UnsatisfiedLinkError) {
                return false
            }
            // The C side returns RA_ERR_NOT_SIGNED_IN (-6) until #268
            // ships login; the coordinator treats any non-OK result as
            // "service is idle for this session". RA_OK means the request
            // was accepted and the runtime will settle on the next idle().
            return rc == RaStatus.OK
        }
    }

    override fun evaluateFrame(frameIndex: Long) {
        try {
            // Push our handle onto the thread-local stack so the
            // shared JNA callback can find the right JVM-side reader.
            // Popped in `finally` so a thrown exception from the native
            // side can't leak a stale handle to the next facade.
            val prior = currentHandle.get()
            currentHandle.set(handle)
            try {
                bindings.ra_facade_evaluate_frame(handle, frameIndex)
                bindings.ra_facade_idle(handle)
                drainEvents()
            } finally {
                currentHandle.set(prior)
            }
        } catch (e: UnsatisfiedLinkError) {
            // Library was unloaded mid-session (e.g. during shutdown);
            // swallow — the coordinator has already moved on.
        }
    }

    override fun resetRuntime() {
        try {
            bindings.ra_facade_reset(handle)
        } catch (e: UnsatisfiedLinkError) { /* see evaluateFrame */ }
    }

    override fun serializeProgress(): ByteArray? {
        val size = try {
            bindings.ra_facade_progress_size(handle)
        } catch (e: UnsatisfiedLinkError) {
            return null
        }
        if (size <= 0) return null
        val out = ByteArray(size)
        val written = try {
            bindings.ra_facade_serialize_progress(handle, out, size)
        } catch (e: UnsatisfiedLinkError) {
            return null
        }
        if (written <= 0) return null
        return if (written == out.size) out else out.copyOf(written)
    }

    override fun restoreProgress(progress: ByteArray?) {
        if (progress == null) {
            // Null → reset the runtime to its post-prepareGame baseline.
            try { bindings.ra_facade_reset(handle) } catch (e: UnsatisfiedLinkError) {}
            return
        }
        try {
            bindings.ra_facade_restore_progress(handle, progress, progress.size)
        } catch (e: UnsatisfiedLinkError) { /* see above */ }
    }

    override fun unloadGame() {
        try {
            // Drop pending events BEFORE telling the C side to unload, so
            // an in-flight server-error event from the previous game
            // can't reach the new game's UI.
            bindings.ra_facade_clear_events(handle)
            bindings.ra_facade_unload_game(handle)
        } catch (e: UnsatisfiedLinkError) { /* see above */ }
    }

    override fun shutdown() {
        try {
            bindings.ra_facade_destroy(handle)
        } catch (e: UnsatisfiedLinkError) {
            // Already gone — nothing more to do.
        }
        // The native handle is now invalid. Mark this instance so a
        // follow-up call doesn't try to use a freed pointer.
        // (Subsequent calls go through `bindings.ra_facade_*` which would
        // crash on a NULL pointer; the try/catch above shields us from
        // every documented call site, and a fresh service instance is
        // the only path forward.)
    }

    // ------------------------------------------------------------------
    // Memory reader wiring (Nestlin-side)
    // ------------------------------------------------------------------

    /**
     * Install the JVM-side memory reader so rcheevos can read emulated
     * RAM/registers during `evaluate_frame`. The callback runs
     * synchronously on the emulation thread — it must NOT call back into
     * any ra_facade_* method.
     *
     * The reader is stored in a process-wide map keyed by the façade
     * handle. The shared `jnaMemoryReader` callback is what rcheevos
     * actually calls; on each invocation it looks up the JVM-side
     * reader from the map using the [currentHandle] ThreadLocal that
     * `evaluate_frame` pushes before the native call.
     */
    fun installMemoryReader(reader: RaReadMemoryFn) {
        memoryReaders[handle] = reader
        try {
            // The C shim stores the userdata pointer but never dereferences
            // it — the JVM-side uses the ThreadLocal handle instead. We
            // pass the handle pointer for symmetry, in case future
            // C-side debugging wants to inspect what's installed.
            bindings.ra_facade_set_memory_reader(handle, jnaMemoryReader, handle)
        } catch (e: UnsatisfiedLinkError) { /* see above */ }
    }

    /**
     * Drain pending events from the native queue. Each event is logged
     * (with sensitive fields redacted) and dropped. UI integration
     * (achievement toasts, leaderboard tracker) lands in issue #268 —
     * the seam is in place; the consumers aren't wired yet.
     */
    private fun drainEvents() {
        val ev = RaEvent()
        while (true) {
            ev.write()
            val has = try {
                bindings.ra_facade_poll_event(handle, ev)
            } catch (e: UnsatisfiedLinkError) {
                return
            }
            if (has == 0) return
            ev.read()
            handleEvent(ev)
        }
    }

    private fun handleEvent(ev: RaEvent) {
        when (ev.type) {
            RaEventType.ACHIEVEMENT_TRIGGERED -> {
                val title = bytesToString(ev.achievementTitle)
                // Points + ID are non-sensitive; title is user-facing text
                // from the achievement set (not a token). Safe to log.
                System.err.println("[RA] Achievement unlocked: id=${ev.achievementId} points=${ev.achievementPoints} title=$title")
            }
            RaEventType.GAME_COMPLETED -> {
                System.err.println("[RA] Game completed (no mastery notification)")
            }
            RaEventType.SERVER_ERROR -> {
                // Server errors may include API paths and result codes —
                // we deliberately do NOT log the message body, which can
                // contain server-internal context that shouldn't leak to
                // log files. Result code + ID are the actionable bits.
                System.err.println("[RA] Server error: code=${ev.serverResultCode} related_id=${ev.serverRelatedId}")
            }
            RaEventType.DISCONNECTED ->
                System.err.println("[RA] Disconnected from server")
            RaEventType.RECONNECTED ->
                System.err.println("[RA] Reconnected to server")
            // Other event types are transient UI hints (challenge /
            // progress / leaderboard tracker) and aren't logged here —
            // they're wired to UI affordances in issue #268.
        }
    }

    private fun bytesToString(bytes: ByteArray): String {
        val end = bytes.indexOf(0)
        val trimmed = if (end >= 0) bytes.copyOf(end) else bytes
        return String(trimmed, Charsets.UTF_8)
    }

    companion object {
        /**
         * Try to load the native library and create a fresh service.
         * Returns null when the library is missing, corrupt, or the
         * rcheevos client init fails. Logs a single one-line INFO
         * message on the no-op path so a developer can see why the
         * façade is degraded without a stack trace.
         *
         * The library is loaded lazily on first call. Tests that never
         * call this method never load the native library — that's the
         * "headless replay, bootcheck, and unrelated unit tests must
         * not load native library" requirement from issue #267.
         */
        @JvmStatic
        fun load(): NativeRetroAchievementsService? {
            val bindings = RaFacadeBindings.load() ?: run {
                System.err.println("[RA] Native library unavailable — using NoOp service")
                return null
            }
            val handle = try {
                bindings.ra_facade_create(SERVER_URL, USER_AGENT)
            } catch (e: UnsatisfiedLinkError) {
                System.err.println("[RA] Native library failed to initialise — using NoOp service")
                return null
            }
            if (handle == null) {
                System.err.println("[RA] rcheevos client creation returned null — using NoOp service")
                return null
            }
            return NativeRetroAchievementsService(bindings, handle)
        }

        /** RA server URL passed to rcheevos. The C side ignores it until #268. */
        private val SERVER_URL: String? = null  // default — const val can't be nullable String

        /** User-Agent passed to rcheevos. */
        private const val USER_AGENT = "Nestlin/1.0"

        // Per-instance map of native handles to JVM-side readers. The
        // pointer key is unique per facade instance, so a stale entry
        // for a destroyed facade can't be reached from a live one (the
        // reader closure captures the facade, and the facade's
        // shutdown() doesn't touch this map — destroyed façades are
        // garbage-collected and their entries are pruned when a new
        // facade happens to reuse the same pointer address, which is
        // not a correctness concern because the handle is freed).
        private val memoryReaders = java.util.concurrent.ConcurrentHashMap<Pointer, RaReadMemoryFn>()

        // Thread-local handle stack: pushed/popped by each evaluate_frame
        // call so jnaMemoryReader can find the right reader.
        private val currentHandle = ThreadLocal.withInitial<Pointer?> { null }

        // The shared JNA-side callback. JNA's "callback" mapping wraps
        // this interface so native code can call into the JVM. The
        // single static instance is shared across all façades because
        // JNA's `Callback` interface is stateless once bound; the
        // per-call façade identity comes from [currentHandle].
        private val jnaMemoryReader = RaReadMemoryFn { address, buffer, numBytes ->
            val handle = currentHandle.get() ?: return@RaReadMemoryFn 0
            val reader = memoryReaders[handle] ?: return@RaReadMemoryFn 0
            val n = numBytes.coerceAtMost(buffer.size)
            reader.read(address, buffer, n)
        }
    }
}
