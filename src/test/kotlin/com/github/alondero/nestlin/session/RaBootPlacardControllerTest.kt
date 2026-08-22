package com.github.alondero.nestlin.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Pins the [RaBootPlacardController] state machine (issue #269).
 *
 * The controller is the single bridge between the coordinator's
 * ROM-load lifecycle and the JavaFX boot-placard. The UI binds a
 * listener to it and renders whatever the latest event implies. The
 * tests below prove:
 *
 *  - The generation counter advances on every [bumpGeneration] call.
 *  - Events published against a stale generation are silently dropped.
 *  - Listeners are invoked synchronously on the calling thread.
 *  - The "latest" reference always reflects the most recent event.
 *  - [clear] publishes Idle without bumping the generation.
 */
class RaBootPlacardControllerTest {

    @Test
    fun `bumpGeneration advances the counter monotonically`() {
        val controller = RaBootPlacardController()
        val gen0 = controller.generation
        val gen1 = controller.bumpGeneration()
        val gen2 = controller.bumpGeneration()
        assertEquals(gen0 + 1, gen1)
        assertEquals(gen0 + 2, gen2)
        assertEquals(gen2, controller.generation)
    }

    @Test
    fun `events published against the current generation are accepted`() {
        val controller = RaBootPlacardController()
        controller.bumpGeneration()
        controller.publish(BootPlacardEvent.Idle(controller.generation))
        assertEquals(1, controller.recordedEvents.size)
    }

    @Test
    fun `events published against a stale generation are dropped`() {
        // AC #10: rapid ROM/account changes discard all stale identify,
        // load, placard, and image completions. The controller's
        // generation guard is the single enforcement point.
        val controller = RaBootPlacardController()
        controller.bumpGeneration()
        // A stray event for generation 0 — would happen if an image
        // fetch completion fires after a rapid ROM switch.
        controller.publish(BootPlacardEvent.Idle(0))
        assertEquals(0, controller.recordedEvents.size)
        // Bumping to gen2 must NOT make the gen0 event retroactively
        // valid — drop is final.
        controller.bumpGeneration()
        controller.publish(BootPlacardEvent.Idle(0))
        assertEquals(0, controller.recordedEvents.size)
        // Sanity: a current-generation event is still accepted.
        controller.publish(BootPlacardEvent.Idle(controller.generation))
        assertEquals(1, controller.recordedEvents.size)
    }

    @Test
    fun `currentEvent reflects the most recent accepted event`() {
        val controller = RaBootPlacardController()
        controller.bumpGeneration()
        val summary = RaGameSummary(
            gameId = 42,
            title = "Test Game",
            hash = "deadbeef".repeat(4),
            badgeName = "42",
            imageUrl = "https://retroachievements.org/Images/42.png",
            numCoreAchievements = 5,
            pointsCore = 50,
            numUnlockedAchievements = 2,
            pointsUnlocked = 20,
        )
        controller.publish(BootPlacardEvent.Idle(controller.generation))
        controller.publish(BootPlacardEvent.Recognized(controller.generation, summary, null))
        val current = controller.currentEvent
        assertTrue(current is BootPlacardEvent.Recognized)
        current as BootPlacardEvent.Recognized
        assertEquals(summary, current.summary)
    }

    @Test
    fun `listeners fire synchronously on the publishing thread`() {
        // The UI's listener is responsible for re-posting to the JavaFX
        // thread if it mutates scene-graph nodes. The controller
        // guarantees synchronous invocation on the calling thread.
        val controller = RaBootPlacardController()
        controller.bumpGeneration()
        val capturedThread = CopyOnWriteArrayList<Thread>()
        controller.addListener { capturedThread.add(Thread.currentThread()) }
        val callingThread = Thread.currentThread()
        controller.publish(BootPlacardEvent.Idle(controller.generation))
        assertEquals(1, capturedThread.size)
        assertSame(callingThread, capturedThread.first())
    }

    @Test
    fun `removeListener unsubscribes`() {
        val controller = RaBootPlacardController()
        controller.bumpGeneration()
        var count = 0
        val token = controller.addListener { count++ }
        controller.publish(BootPlacardEvent.Idle(controller.generation))
        controller.removeListener(token)
        controller.publish(BootPlacardEvent.Idle(controller.generation))
        assertEquals(1, count)
    }

    @Test
    fun `listener that throws does not stop other listeners`() {
        // A misbehaving observer must not poison the state machine.
        val controller = RaBootPlacardController()
        controller.bumpGeneration()
        var goodCount = 0
        controller.addListener { throw RuntimeException("listener explosion") }
        controller.addListener { goodCount++ }
        // The throw is caught internally; the second listener still fires.
        controller.publish(BootPlacardEvent.Idle(controller.generation))
        assertEquals(1, goodCount)
    }

    @Test
    fun `clear publishes Idle without bumping the generation`() {
        // unloadRom calls clear() instead of bumpGeneration()+publish(Idle).
        // The UI consumer sees the Idle event under the same generation,
        // so any in-flight image-fetch completion for the cleared game
        // is still correctly classified as stale (it was published under
        // a previous generation that was already bumped by the
        // preceding loadRom).
        val controller = RaBootPlacardController()
        controller.bumpGeneration()
        val genBefore = controller.generation
        controller.clear()
        assertEquals(genBefore, controller.generation)
        val current = controller.currentEvent
        assertTrue(current is BootPlacardEvent.Idle)
        current as BootPlacardEvent.Idle
        assertEquals(genBefore, current.generation)
    }

    @Test
    fun `recognized event with badge image publishes the image alongside the summary`() {
        val controller = RaBootPlacardController()
        controller.bumpGeneration()
        val image = java.awt.image.BufferedImage(8, 8, java.awt.image.BufferedImage.TYPE_INT_RGB)
        val summary = RaGameSummary(
            gameId = 1,
            title = "T",
            hash = "00".repeat(16),
            badgeName = "1",
            imageUrl = "u",
            numCoreAchievements = 1,
            pointsCore = 10,
            numUnlockedAchievements = 0,
            pointsUnlocked = 0,
        )
        controller.publish(BootPlacardEvent.Recognized(controller.generation, summary, image))
        val current = controller.currentEvent as BootPlacardEvent.Recognized
        assertSame(image, current.badgeImage)
    }
}
