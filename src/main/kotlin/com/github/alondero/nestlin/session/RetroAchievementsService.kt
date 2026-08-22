package com.github.alondero.nestlin.session

/**
 * Service boundary for optional RetroAchievements integration.
 *
 * This is the **only** shape a coordinator collaborator may implement; production
 * code never sees native pointers, JNA, the rcheevos client, networking, or
 * JavaFX. The default is [NoOpRetroAchievementsService] (instant and side-effect
 * free), so every existing emulator flow works with no native library and no
 * network access.
 *
 * The lifecycle is:
 *
 * ```
 *   prepareGame(info)              // a new ROM is now active
 *     ├─ evaluateFrame(...)        // one or more, per emulated frame
 *     ├─ serializeProgress()       // captured into a save state
 *     ├─ restoreProgress(bytes)    // restored from a save state
 *     ├─ resetRuntime()            // power or soft reset
 *     └─ unloadGame()              // replacing the ROM or shutting down
 *   shutdown()                     // permanent; tear down client
 * ```
 *
 * Implementations MUST be safe under the documented ordering and idempotent
 * where the spec says so (every method is naturally idempotent for the no-op;
 * real impls document their own guarantees).
 *
 * The interface deliberately does NOT expose:
 *  - native pointers (rcheevos `rc_client_t*` etc.),
 *  - callback contexts,
 *  - network state, or
 *  - UI primitives (Image, Node, PixelBuffer).
 *
 * Anything that would force a host-specific implementation to leak across the
 * boundary is out of scope. See issue #265 for the full specification and
 * issue #267 for the real native client.
 */
interface RetroAchievementsService {

    /**
     * True iff a user is currently signed in and the service is ready to
     * `prepareGame` against a real achievement set. The no-op returns false
     * unconditionally.
     */
    fun isSignedIn(): Boolean

    /**
     * Prepare the service for a new game session (issue #269).
     *
     * For the no-op this completes immediately and returns `true`. A real
     * implementation may need to identify the ROM against a remote service,
     * download achievement sets, etc. — work that can take seconds and can
     * fail on network or auth errors. Real implementations MUST block until
     * the load settles (success, failure, or the budget elapses) and return
     * within the budget supplied by the coordinator.
     *
     * The contract is: **never throw**, always return. A `false` return means
     * the prepare failed and the service is idle for this session — gameplay
     * proceeds with no achievements (issue #269 AC #12: failure never
     * prevents gameplay).
     *
     * The [RomContent.hash] is always populated by the coordinator before
     * this call, so the service never has to re-hash the bytes; the service
     * can use [GameSessionInfo.nesHash] as the canonical RA database key.
     *
     * @param sessionInfo canonical ROM identity + region
     * @param timeoutMillis upper bound on the prepare round-trip. A real
     *   implementation must return within this budget (positive integer; the
     *   coordinator clamps to a sensible default if zero).
     * @return `true` if the service is now actively evaluating the game,
     *   `false` if the prepare failed or timed out and the service is idle
     *   for this session.
     */
    fun prepareGame(sessionInfo: GameSessionInfo, timeoutMillis: Long): Boolean

    /**
     * Feed one emulated frame's worth of state into the active runtime.
     * The no-op returns immediately; a real client updates achievement
     * counters against the live memory/registers and may emit a callback
     * the coordinator's `onServiceCallEnd` hook can observe.
     *
     * The frame index is monotonic across the lifetime of one
     * `prepareGame` / `unloadGame` cycle (0 on the first frame after a
     * prepare or reset). It exists so a real implementation can detect
     * "the user rewound and re-evaluated the same frame twice" without
     * the coordinator needing to know what state the service cares about.
     *
     * No-op when no game is currently prepared; the no-op returns
     * immediately for the same reason — the coordinator's per-frame
     * wiring (issue #268) always calls this, regardless of whether the
     * service is active.
     */
    fun evaluateFrame(frameIndex: Long)

    /**
     * Reset the active runtime. No-op if no game is currently prepared.
     *
     * Called by [GameSessionCoordinator] on power and soft reset, matching
     * the real-hardware behaviour that the runtime's condition progress is
     * tied to the boot timeline.
     */
    fun resetRuntime()

    /**
     * Serialize the active runtime's condition progress to a copy-safe byte
     * buffer, or `null` if there is no active game (or the service is idle).
     *
     * The bytes are written into a Nestlin save state by the coordinator and
     * are restored verbatim via [restoreProgress]. The encoding is the
     * service's private concern — the coordinator treats the buffer as opaque.
     *
     * The returned array is a fresh copy; the caller may retain it indefinitely.
     */
    fun serializeProgress(): ByteArray?

    /**
     * Restore condition progress from a buffer previously produced by
     * [serializeProgress], or from a `null` / `empty` value to reset the
     * runtime to its post-`prepareGame` state.
     *
     * The coordinator calls this after [prepareGame] when restoring a save
     * state for the freshly-prepared game, so the runtime timeline is in sync
     * with the emulator timeline.
     *
     * Must be safe on unknown/corrupt input: a real implementation validates
     * the buffer's length/version and silently resets on mismatch rather than
     * throwing.
     */
    fun restoreProgress(progress: ByteArray?)

    /**
     * Release the active game session and any per-game resources. Idempotent:
     * safe to call when no game is prepared.
     *
     * Called by the coordinator before installing a new ROM and during
     * shutdown. The contract is: after `unloadGame` returns, the service
     * must NOT publish any further events for the previous game — even if
     * an in-flight network callback arrives after unload, the coordinator's
     * generation guard will discard it.
     */
    fun unloadGame()

    /**
     * Snapshot the active game's title, image URL, and the signed-in user's
     * progress against the core achievement set (issue #269 — boot placard).
     *
     * Returns `null` when:
     *   - the user is not signed in (the boot placard treats this as
     *     "no placard should be displayed" — AC #8), or
     *   - no game is currently prepared, or
     *   - the prepare round-trip has not yet settled on READY.
     *
     * Returns a populated [RaGameSummary] when the prepare round-trip has
     * reached READY. The coordinator uses this to distinguish "ROM recognized
     * with core achievements" (placard shows full counts) from "ROM recognized
     * without core achievements" (placard says so clearly — AC #7) from
     * "ROM unrecognized" (placard says so subtly — AC #7).
     *
     * Safe to call from any thread.
     */
    fun gameSummary(): RaGameSummary?

    /**
     * Permanent teardown: invalidate every pending callback, release client
     * resources, and disconnect any network handles. After `shutdown` the
     * service is unusable and must not respond to any other method.
     *
     * Safe to call multiple times. Safe to call without a prior
     * `prepareGame`. Safe to call concurrently with in-flight callbacks —
     * callbacks that fire after shutdown must be no-ops.
     */
    fun shutdown()
}
