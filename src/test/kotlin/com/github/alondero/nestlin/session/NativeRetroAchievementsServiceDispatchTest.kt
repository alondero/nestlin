package com.github.alondero.nestlin.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pins the [dispatchAchievementEvent] contract (issue #288 follow-up).
 *
 * The helper is the seam between
 * [NativeRetroAchievementsService.handleEvent]'s `ACHIEVEMENT_*` arms
 * and the listener registered on
 * [NativeRetroAchievementsService.achievementEventListener]. PR #290
 * review moved it to a top-level function (rather than a private
 * method on the service) so the dispatch semantics — null-safety +
 * throw isolation + payload shape — can be tested without standing
 * up a live service instance (which requires the JNA façade
 * library).
 *
 * What the helper guarantees:
 *  - **No-op when null** — production code that doesn't wire a
 *    listener (CLI drivers, headless bench tools) never sees the
 *    dispatch path.
 *  - **Listener fires synchronously** on the calling thread.
 *  - **Throwable isolation** — a listener that throws gets the
 *    exception caught + logged with message + stack trace; the
 *    throw does NOT propagate into the emulation thread's
 *    per-frame hot path.
 *  - **Payload shape** — the listener receives an
 *    [RaAchievementEvent] carrying the achievement ID the runtime
 *    emitted.
 */
class NativeRetroAchievementsServiceDispatchTest {

    @Test
    fun `dispatchAchievementEvent fires the listener with the achievement id`() {
        val seen = mutableListOf<RaAchievementEvent>()
        val listener: (RaAchievementEvent) -> Unit = { seen += it }
        dispatchAchievementEvent(listener, achievementId = 42)
        assertEquals(1, seen.size)
        assertEquals(42, seen.first().achievementId)
    }

    @Test
    fun `dispatchAchievementEvent fires multiple times when invoked repeatedly`() {
        // The drain path calls this for every ACHIEVEMENT_* event; the
        // listener must be invoked once per call (no caching, no
        // dedup).
        var count = 0
        val listener: (RaAchievementEvent) -> Unit = { count++ }
        dispatchAchievementEvent(listener, achievementId = 1)
        dispatchAchievementEvent(listener, achievementId = 2)
        dispatchAchievementEvent(listener, achievementId = 3)
        assertEquals(3, count)
    }

    @Test
    fun `dispatchAchievementEvent is a no-op when listener is null`() {
        // Production code that doesn't wire a listener (CLI / bench)
        // never causes the dispatch path to do anything — no exception,
        // no allocation, no allocation pressure on the emulation
        // thread's per-frame hot path.
        dispatchAchievementEvent(null, achievementId = 0)
        dispatchAchievementEvent(null, achievementId = 12345)
        // No assertion needed — the test is the absence of a thrown
        // exception.
    }

    @Test
    fun `dispatchAchievementEvent catches a throwing listener and does not propagate`() {
        // PR #290 review: throw isolation is required so a UI-side
        // bug doesn't propagate into the emulation thread. The
        // exception's class name, message, AND stack trace are
        // logged so production debugging is tractable from a single
        // stderr line.
        val exploding: (RaAchievementEvent) -> Unit = { throw IllegalStateException("listener exploded") }
        dispatchAchievementEvent(exploding, achievementId = 1)
        // No assertion needed — the test is the absence of a
        // propagated exception. (stderr captures the log line; we
        // don't assert on it because stderr is shared across tests.)
    }
}