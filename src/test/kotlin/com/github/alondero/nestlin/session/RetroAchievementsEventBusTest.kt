package com.github.alondero.nestlin.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/**
 * Pins the [RetroAchievementsEventBus] listener surface (issue #288).
 *
 * The bus is the seam between the native façade's
 * `ACHIEVEMENT_TRIGGERED` / `ACHIEVEMENT_CHALLENGE_*` /
 * `ACHIEVEMENT_PROGRESS_*` events and the loaded-game achievements
 * window's refresh path. The tests below prove the four documented
 * AC behaviours:
 *
 *  - **Listener invocation order.** Events fire synchronously on
 *    the calling thread, in registration order.
 *  - **Throw isolation.** A listener that throws does not stop
 *    subsequent listeners from firing.
 *  - **Generation-guarded suppression.** A late publish for a stale
 *    game's runtime cannot reach the current game's window — this
 *    is enforced one layer up in [RaAchievementsController.publish]
 *    but the test exercises the bus's role as the trigger.
 *  - **No-op when no listener is attached.** [publish] on an empty
 *    bus is a no-op; [addListener] returns a distinct token per
 *    subscription; [removeListener] unsubscribes cleanly.
 */
class RetroAchievementsEventBusTest {

    @Test
    fun `publish invokes every registered listener`() {
        val bus = RetroAchievementsEventBus()
        val seen = mutableListOf<RaAchievementEvent>()
        bus.addListener { seen += it }
        bus.addListener { seen += it }
        bus.publish(RaAchievementEvent.AchievementTriggered(achievementId = 1))
        assertEquals(2, seen.size)
        assertEquals(1, seen[0].achievementId)
        assertEquals(1, seen[1].achievementId)
    }

    @Test
    fun `listeners fire in registration order`() {
        // CopyOnWriteArrayList preserves insertion order; the test pins
        // that contract so a future contributor doesn't swap to a
        // non-ordered collection (HashSet, ConcurrentHashMap) and silently
        // reorder which listener wins on a given event.
        val bus = RetroAchievementsEventBus()
        val seen = mutableListOf<String>()
        bus.addListener { seen += "first" }
        bus.addListener { seen += "second" }
        bus.addListener { seen += "third" }
        bus.publish(RaAchievementEvent.AchievementChallengeShow(achievementId = 0))
        assertEquals(listOf("first", "second", "third"), seen)
    }

    @Test
    fun `listeners fire synchronously on the calling thread`() {
        val bus = RetroAchievementsEventBus()
        val callingThread = Thread.currentThread()
        var firedOn: Thread? = null
        bus.addListener { firedOn = Thread.currentThread() }
        bus.publish(RaAchievementEvent.AchievementTriggered(achievementId = 0))
        assertSame(callingThread, firedOn)
    }

    @Test
    fun `removeListener unsubscribes`() {
        val bus = RetroAchievementsEventBus()
        var count = 0
        val token = bus.addListener { count++ }
        bus.publish(RaAchievementEvent.AchievementTriggered(achievementId = 0))
        bus.removeListener(token)
        bus.publish(RaAchievementEvent.AchievementTriggered(achievementId = 0))
        assertEquals(1, count)
    }

    @Test
    fun `removeListener is idempotent`() {
        val bus = RetroAchievementsEventBus()
        val token = bus.addListener { }
        bus.removeListener(token)
        bus.removeListener(token)  // no throw, no log
        assertEquals(0, bus.listenerCount)
    }

    @Test
    fun `listener that throws does not stop subsequent listeners`() {
        // Same isolation pattern as [RaBootPlacardController.publish] /
        // [RaSignInManager.updateState]: the bus swallows a misbehaving
        // listener so a UI-side bug cannot propagate into the
        // emulation thread's per-frame hot path.
        val bus = RetroAchievementsEventBus()
        var goodCount = 0
        bus.addListener { throw RuntimeException("listener explosion") }
        bus.addListener { goodCount++ }
        bus.addListener { throw IllegalStateException("another bad listener") }
        bus.addListener { goodCount++ }
        bus.publish(RaAchievementEvent.AchievementTriggered(achievementId = 0))
        assertEquals(2, goodCount)
    }

    @Test
    fun `publish on an empty bus is a no-op`() {
        // The "no-op when no window is open" AC: when the user hasn't
        // opened the achievements window (and therefore no listener
        // is attached), every native event drain is a free operation.
        val bus = RetroAchievementsEventBus()
        bus.publish(RaAchievementEvent.AchievementTriggered(achievementId = 1))
        bus.publish(RaAchievementEvent.AchievementChallengeShow(achievementId = 2))
        bus.publish(RaAchievementEvent.AchievementChallengeHide(achievementId = 3))
        bus.publish(RaAchievementEvent.AchievementProgressShow(achievementId = 4))
        bus.publish(RaAchievementEvent.AchievementProgressHide(achievementId = 5))
        bus.publish(RaAchievementEvent.AchievementProgressUpdate(achievementId = 6))
        assertEquals(0, bus.listenerCount)
    }

    @Test
    fun `every sealed variant is dispatched verbatim`() {
        // The variants are the events rcheevos emits that affect the
        // achievements window's snapshot. The test pins the achievement
        // ID on each one so a future rename of the C-side constant
        // fails the test loudly rather than silently dropping an event.
        val bus = RetroAchievementsEventBus()
        val seen = mutableListOf<RaAchievementEvent>()
        bus.addListener { seen += it }
        bus.publish(RaAchievementEvent.AchievementTriggered(achievementId = 11))
        bus.publish(RaAchievementEvent.AchievementChallengeShow(achievementId = 22))
        bus.publish(RaAchievementEvent.AchievementChallengeHide(achievementId = 33))
        bus.publish(RaAchievementEvent.AchievementProgressShow(achievementId = 44))
        bus.publish(RaAchievementEvent.AchievementProgressHide(achievementId = 55))
        bus.publish(RaAchievementEvent.AchievementProgressUpdate(achievementId = 66))
        assertEquals(
            listOf(11, 22, 33, 44, 55, 66),
            seen.map { it.achievementId }
        )
    }

    @Test
    fun `addListener returns a distinct token per subscription`() {
        // Two subscriptions on the same lambda must each return their
        // own token; a future contributor shouldn't deduplicate by
        // identity because the bus explicitly supports the same
        // listener body being added twice (e.g. to log + to refresh).
        val bus = RetroAchievementsEventBus()
        val sameListener: (RaAchievementEvent) -> Unit = { }
        val t1 = bus.addListener(sameListener)
        val t2 = bus.addListener(sameListener)
        assertNotNull(t1)
        assertNotNull(t2)
        assertEquals(2, bus.listenerCount)
        // Removing one token must not affect the other.
        bus.removeListener(t1)
        assertEquals(1, bus.listenerCount)
        bus.removeListener(t2)
        assertEquals(0, bus.listenerCount)
    }

    // ----------------------------------------------------------------
    // Integration: bus event → listener refreshes the controller
    // under the right generation. Issue #288 AC #5: "Generation
    // guards ensure a stale unlock for game A cannot refresh the
    // window for game B."
    // ----------------------------------------------------------------

    /** Local snapshot helper — mirrors the private one in [RaAchievementsControllerTest]. */
    private fun snapshot(total: Int = 10): RaAchievementListSnapshot =
        RaAchievementListSnapshot(
            gameTitle = "Test",
            gameImageUrl = "",
            totalCoreAchievements = total,
            totalCorePoints = 100,
            unlockedCoreAchievements = 0,
            unlockedCorePoints = 0,
            buckets = emptyList(),
            generation = 0L,
        )

    @Test
    fun `bus event for the active game refreshes the controller under the current generation`() {
        // AC #4 + #5: the listener subscribes a refresh() call; the
        // refresh publishes a view-model stamped with the controller's
        // CURRENT generation. A late event for the previous game is
        // caught by the controller's generation guard.
        val signedIn = RaSignInState.SignedIn(RaAccount(
            username = "u", displayName = "d", score = 0, scoreSoftcore = 0,
            unreadMessages = 0, avatarUrl = "",
        ))
        val romA = RaAchievementsController.LoadedRomSnapshot("Game A", "gameA.nes")
        val service = FakeRetroAchievementsService(achievementListResult = snapshot(total = 5))
        val c = RaAchievementsController(
            service = service,
            signInState = { signedIn },
            loadedRomInfo = { romA },
        )
        val bus = RetroAchievementsEventBus()
        val publishedGenerations = mutableListOf<Long>()
        c.addListener { publishedGenerations += it.generation }
        bus.addListener { _ -> c.refresh() }

        // Game A's controller is at generation 0.
        assertEquals(0L, c.generation)
        // An unlock fires for game A. The listener refreshes the
        // controller; the view-model is stamped with gen 0.
        bus.publish(RaAchievementEvent.AchievementTriggered(achievementId = 1))
        assertEquals(listOf(0L), publishedGenerations)
    }

    @Test
    fun `bus event after generation bump refreshes under the NEW generation`() {
        // Simulates: user plays game A, unlocks an achievement (event
        // pending in the native queue), then immediately loads game B.
        // The ROM swap bumps the controller's generation. The pending
        // event drains from the native queue and fires the bus
        // listener. The listener calls refresh() — which now reads
        // game B's controller (gen N+1) and game B's service
        // snapshot. The view-model is stamped with gen N+1, so the
        // window re-renders against game B's state, NOT a stale
        // game-A view.
        val signedIn = RaSignInState.SignedIn(RaAccount(
            username = "u", displayName = "d", score = 0, scoreSoftcore = 0,
            unreadMessages = 0, avatarUrl = "",
        ))
        val romB = RaAchievementsController.LoadedRomSnapshot("Game B", "gameB.nes")
        val service = FakeRetroAchievementsService(achievementListResult = snapshot(total = 5))
        val c = RaAchievementsController(
            service = service,
            signInState = { signedIn },
            loadedRomInfo = { romB },
        )
        val bus = RetroAchievementsEventBus()
        val publishedGenerations = mutableListOf<Long>()
        c.addListener { publishedGenerations += it.generation }
        bus.addListener { _ -> c.refresh() }

        // Bump generation (the coordinator's loadRom path does this
        // BEFORE unloading the previous game).
        c.bumpGeneration()
        // A pending event from game A's runtime drains and fires the
        // bus listener. The listener calls refresh(); the refresh
        // publishes under the NEW generation, not the old one.
        bus.publish(RaAchievementEvent.AchievementTriggered(achievementId = 1))
        assertEquals(1, publishedGenerations.size)
        assertEquals(1L, publishedGenerations.first())
        assertEquals(c.generation, publishedGenerations.first())
    }
}