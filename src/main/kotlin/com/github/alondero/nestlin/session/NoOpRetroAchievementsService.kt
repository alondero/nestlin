package com.github.alondero.nestlin.session

/**
 * Default [RetroAchievementsService] implementation: every method completes
 * immediately and side-effect-freely, every "is anything happening?" query
 * returns `false` / `null`. Used in production until a real client ships
 * (issue #267), in headless tools (CLI `replay`, `bootcheck`), and in any
 * test that doesn't want the seam involved.
 *
 * Constructing one is free — no native library is loaded, no socket is
 * opened, no thread is spawned. The class is stateless, so an entire
 * application can share a single instance.
 *
 * ## Issue #269 boot-placard wiring
 *
 * `prepareGame` returns `false` (the no-op "service is idle for this
 * session" semantic — gameplay proceeds, no placard is shown). `gameSummary`
 * returns `null` (no game prepared → UI shows no placard).
 */
object NoOpRetroAchievementsService : RetroAchievementsService {
    override fun isSignedIn(): Boolean = false
    override fun prepareGame(sessionInfo: GameSessionInfo, timeoutMillis: Long): Boolean = false
    override fun evaluateFrame(frameIndex: Long) = Unit
    override fun resetRuntime() = Unit
    override fun serializeProgress(): ByteArray? = null
    override fun restoreProgress(progress: ByteArray?) = Unit
    override fun unloadGame() = Unit
    override fun shutdown() = Unit
    override fun gameSummary(): RaGameSummary? = null
}
