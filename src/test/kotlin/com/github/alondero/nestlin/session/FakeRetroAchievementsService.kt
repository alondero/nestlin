package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.testutil.failTest

/**
 * Recording fake for [RetroAchievementsService], used by every
 * [GameSessionCoordinator] test to assert on the *order* and *count* of
 * lifecycle calls without standing up a real RA client.
 *
 * The fake is intentionally a class (not a singleton) so each test can
 * construct its own — that gives the test a private call log and avoids
 * one test polluting another's view of "what was called".
 *
 * Two knobs drive the fake's behaviour:
 *
 *  - [prepareGameResult] — what `prepareGame` should return. Default `true`
 *    (the service "succeeded"). Setting it `false` simulates a network /
 *    auth failure and lets the coordinator's failure-recovery branch be
 *    exercised.
 *  - [prepareGameException] — when non-null, `prepareGame` throws the
 *    contained exception instead of returning. The coordinator's contract
 *    is "never throw", so the test asserts the coordinator absorbs this
 *    into the same recovery path as a `false` return.
 *
 * `evaluateFrame` is intentionally absent: the documented per-frame seam
 * lands in a later issue (#268). The fake satisfies the interface by
 * throwing on it, so a test that accidentally calls into it gets a
 * clear, immediate failure rather than silent acceptance.
 */
class FakeRetroAchievementsService(
    @Volatile var prepareGameResult: Boolean = true,
    @Volatile var prepareGameException: Throwable? = null,
) : RetroAchievementsService {

    /** Ordered log of every method call. Read this in test assertions. */
    val calls: MutableList<Call> = mutableListOf()

    /** Last [GameSessionInfo] passed to [prepareGame], or null if never called. */
    var lastPreparedInfo: GameSessionInfo? = null
        private set

    /** Most recent progress bytes handed to [restoreProgress]. */
    var lastRestoredProgress: ByteArray? = null
        private set

    sealed interface Call {
        data class PrepareGame(val info: GameSessionInfo) : Call
        data class PrepareGameFailed(val info: GameSessionInfo) : Call
        data class EvaluateFrame(val frameIndex: Long) : Call
        object ResetRuntime : Call
        data class SerializeProgress(val token: Int) : Call
        data class RestoreProgress(val progress: ByteArray?) : Call
        object UnloadGame : Call
        object Shutdown : Call
    }

    override fun isSignedIn(): Boolean = true  // sign-in is meaningful in tests; the no-op returns false.

    override fun prepareGame(sessionInfo: GameSessionInfo): Boolean {
        lastPreparedInfo = sessionInfo
        val ex = prepareGameException
        return if (ex != null) {
            calls += Call.PrepareGameFailed(sessionInfo)
            throw ex
        } else {
            calls += Call.PrepareGame(sessionInfo)
            prepareGameResult
        }
    }

    override fun resetRuntime() {
        calls += Call.ResetRuntime
    }

    override fun evaluateFrame(frameIndex: Long) {
        calls += Call.EvaluateFrame(frameIndex)
    }

    override fun serializeProgress(): ByteArray? {
        val token = calls.size  // monotonically increases per call → test can detect a missing call
        calls += Call.SerializeProgress(token)
        return byteArrayOf(0x52, 0x41, token.toByte())  // "RA" + a fresh byte so two saves differ
    }

    override fun restoreProgress(progress: ByteArray?) {
        lastRestoredProgress = progress
        calls += Call.RestoreProgress(progress?.copyOf())  // defensive copy — tests can mutate freely
    }

    override fun unloadGame() {
        calls += Call.UnloadGame
    }

    override fun shutdown() {
        calls += Call.Shutdown
    }

    /**
     * Assert that the call log contains [expected] in order, allowing extra
     * entries (e.g. additional `ResetRuntime` between two `PrepareGame`).
     * Returns the index of the last matched entry so a follow-up assertion
     * can pin the next event to a position relative to it.
     *
     * Throws via [failTest] so the failure mode matches the project's
     * exception-assertion style (issue #28's `kotlin.test` ban).
     */
    fun assertCallsInOrder(vararg expected: Call): Int {
        var idx = 0
        for (needle in expected) {
            val found = calls.drop(idx).indexOfFirst { it.matches(needle) }
            if (found < 0) {
                failTest(
                    "Expected $needle at position >= $idx in call log; saw:\n  " +
                        calls.withIndex().joinToString("\n  ") { (i, c) -> "$i: $c" }
                )
            }
            idx += found + 1
        }
        return idx - 1
    }

    /** Assert that the call log contains [needle] at the exact position [index]. */
    fun assertCallAt(index: Int, needle: Call) {
        if (index < 0 || index >= calls.size) {
            failTest(
                "Call index $index out of range; log has ${calls.size} entries:\n  " +
                    calls.withIndex().joinToString("\n  ") { (i, c) -> "$i: $c" }
            )
        }
        if (!calls[index].matches(needle)) {
            failTest(
                "Call at $index was ${calls[index]}, expected $needle. Full log:\n  " +
                    calls.withIndex().joinToString("\n  ") { (i, c) -> "$i: $c" }
            )
        }
    }

    private fun Call.matches(other: Call): Boolean = when (this) {
        is Call.PrepareGame -> other is Call.PrepareGame && info == other.info
        is Call.PrepareGameFailed -> other is Call.PrepareGameFailed && info == other.info
        is Call.EvaluateFrame -> other is Call.EvaluateFrame && frameIndex == other.frameIndex
        Call.ResetRuntime -> other is Call.ResetRuntime
        is Call.SerializeProgress -> other is Call.SerializeProgress  // token is auto-monotonic
        is Call.RestoreProgress -> other is Call.RestoreProgress &&
            (progress?.contentEquals(other.progress) ?: (other.progress == null))
        Call.UnloadGame -> other is Call.UnloadGame
        Call.Shutdown -> other is Call.Shutdown
    }

    @Suppress("unused")  // referenced by callers building a literal GameSessionInfo
    private fun demoInfo(): GameSessionInfo = GameSessionInfo(
        displayName = "demo",
        sourcePath = null,
        romBytes = byteArrayOf(0x4E, 0x45, 0x53, 0x1A, 1, 0),
        region = Region.NTSC,
    )
}
