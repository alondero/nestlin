package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.Region
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Tests the default no-op [RetroAchievementsService]: every method is a pure
 * pass-through that does not throw, retains no state, and answers the
 * "is anything happening?" questions with the documented empty answer.
 *
 * The no-op is the production default until #267 ships the real client, so
 * this is also a test that a future refactor of the interface cannot silently
 * change the defaults the existing CLI/UI depends on.
 */
class NoOpRetroAchievementsServiceTest {

    private val sample = GameSessionInfo(
        displayName = "demo",
        sourcePath = Paths.get("/tmp/demo.nes"),
        romBytes = byteArrayOf(0x4E, 0x45, 0x53, 0x1A, 1, 0),
        region = Region.NTSC,
    )

    @Test
    fun `isSignedIn always returns false`() {
        assertFalse(NoOpRetroAchievementsService.isSignedIn())
    }

    @Test
    fun `prepareGame accepts any session and reports not-ready`() {
        // The no-op completes synchronously and never claims the service is
        // actively evaluating the game — that's how the coordinator knows to
        // skip every per-frame call.
        assertFalse(NoOpRetroAchievementsService.prepareGame(sample))
    }

    @Test
    fun `resetRuntime is a no-op even after prepareGame`() {
        // No observable effect, no throw — sanity that a future "remember
        // we have a game" field added to the no-op would surface here.
        NoOpRetroAchievementsService.prepareGame(sample)
        NoOpRetroAchievementsService.resetRuntime()
        assertFalse(NoOpRetroAchievementsService.isSignedIn())
    }

    @Test
    fun `evaluateFrame accepts any frame index without throwing`() {
        // The per-frame seam the coordinator's #268 wiring will call once
        // per emulated frame — the no-op must complete instantly so the
        // frame loop is not slowed by a missing prepare.
        NoOpRetroAchievementsService.evaluateFrame(0L)
        NoOpRetroAchievementsService.prepareGame(sample)
        NoOpRetroAchievementsService.evaluateFrame(42L)
        NoOpRetroAchievementsService.evaluateFrame(Long.MAX_VALUE)
    }

    @Test
    fun `serializeProgress returns null with or without a prepared game`() {
        assertNull(NoOpRetroAchievementsService.serializeProgress())
        NoOpRetroAchievementsService.prepareGame(sample)
        assertNull(NoOpRetroAchievementsService.serializeProgress())
    }

    @Test
    fun `restoreProgress accepts any buffer including null without throwing`() {
        // Real impls must be safe on null/empty/corrupt input; the no-op
        // is the trivial safe case. The point of this test is to lock the
        // contract: a coordinator that hands the no-op null must not crash.
        NoOpRetroAchievementsService.restoreProgress(null)
        NoOpRetroAchievementsService.restoreProgress(ByteArray(0))
        NoOpRetroAchievementsService.restoreProgress(byteArrayOf(1, 2, 3))
    }

    @Test
    fun `unloadGame is idempotent and never throws`() {
        // Calling unload before any prepare is the documented "safe" path.
        NoOpRetroAchievementsService.unloadGame()
        NoOpRetroAchievementsService.prepareGame(sample)
        NoOpRetroAchievementsService.unloadGame()
        NoOpRetroAchievementsService.unloadGame()
    }

    @Test
    fun `shutdown is idempotent and never throws`() {
        // The contract is "safe to call multiple times, safe to call
        // without a prior prepare, safe concurrently with in-flight
        // callbacks". The no-op is trivially all three.
        NoOpRetroAchievementsService.shutdown()
        NoOpRetroAchievementsService.shutdown()
    }

    @Test
    fun `no method retains state across calls`() {
        // The whole point of the no-op: it is stateless. Two independent
        // call sequences must produce identical observable behaviour.
        NoOpRetroAchievementsService.prepareGame(sample)
        NoOpRetroAchievementsService.evaluateFrame(7L)
        NoOpRetroAchievementsService.resetRuntime()
        val afterFirst = NoOpRetroAchievementsService.serializeProgress()
        NoOpRetroAchievementsService.unloadGame()

        NoOpRetroAchievementsService.prepareGame(sample)
        NoOpRetroAchievementsService.evaluateFrame(7L)
        NoOpRetroAchievementsService.resetRuntime()
        val afterSecond = NoOpRetroAchievementsService.serializeProgress()

        assertNull(afterFirst)
        assertNull(afterSecond)
        assertEquals(afterFirst, afterSecond)
    }
}
