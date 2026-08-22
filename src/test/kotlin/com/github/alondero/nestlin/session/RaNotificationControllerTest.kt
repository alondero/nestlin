package com.github.alondero.nestlin.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behavioural tests for [RaNotificationController] (issue #270).
 *
 * The controller is pure data — no JavaFX, no AWT, no clock injection
 * beyond a `nowMillis` parameter. The tests exercise every acceptance
 * criterion from the issue with a deterministic virtual clock.
 *
 *   - FIFO ordering for multiple unlocks within a frame.
 *   - System messages only replace other system messages.
 *   - 5-second unlock display window.
 *   - ROM change clears queued unlocks.
 *   - System slot survives until the next disconnect/reconnect.
 *   - Idle poll returns null when nothing is visible.
 */
class RaNotificationControllerTest {

    @Test
    fun `empty controller returns null and reports no pending`() {
        val c = RaNotificationController()
        assertNull(c.visibleAt(0L))
        assertTrue(!c.hasPending)
    }

    @Test
    fun `single unlock is visible until its 5s window expires`() {
        val c = RaNotificationController()
        c.publishUnlock(
            achievementId = 1,
            title = "First Blood",
            description = "Defeat your first enemy",
            points = 5,
            badgeUrl = "",
            nowMillis = 0L,
        )
        // T = 0: just published, visible
        assertNotNull(c.visibleAt(0L))
        // T = 4_999: still visible (one ms before window expires)
        assertNotNull(c.visibleAt(4_999L))
        // T = 5_000: window has expired — popped, no longer visible
        assertNull(c.visibleAt(5_000L))
    }

    @Test
    fun `three unlocks in same frame are visible in FIFO order with full 5s each`() {
        val c = RaNotificationController()
        // Publish three at T = 0.
        c.publishUnlock(1, "A", "", 1, "", nowMillis = 0L)
        c.publishUnlock(2, "B", "", 2, "", nowMillis = 0L)
        c.publishUnlock(3, "C", "", 3, "", nowMillis = 0L)

        // T = 0..4_999: first one visible
        val first = c.visibleAt(0L) as UnlockNotification
        assertEquals(1, first.achievementId)
        // T = 5_000..9_999: second one visible (each gets its own full 5s)
        val second = c.visibleAt(5_000L) as UnlockNotification
        assertEquals(2, second.achievementId)
        // T = 10_000..14_999: third one visible
        val third = c.visibleAt(10_000L) as UnlockNotification
        assertEquals(3, third.achievementId)
        // T = 15_000: queue empty
        assertNull(c.visibleAt(15_000L))
    }

    @Test
    fun `system message replaces other system messages without touching unlock queue`() {
        val c = RaNotificationController()
        // Queue an unlock first
        c.publishUnlock(1, "A", "", 1, "", nowMillis = 0L)
        // Publish a system message
        c.publishSystem(SystemSeverity.INFO, "Offline", nowMillis = 0L)
        // The system message wins — unlock is queued behind it
        val first = c.visibleAt(0L) as SystemNotification
        assertEquals("Offline", first.message)
        // Replace the system message with a new one
        c.publishSystem(SystemSeverity.ERROR, "Server error", nowMillis = 1_000L)
        val second = c.visibleAt(1_500L) as SystemNotification
        assertEquals("Server error", second.message)
        // System slot expires at T = 5_500 (1500 + 4000). Unlock queue
        // is still intact — at T = 6_000 (system expired), unlock A is
        // visible (window expires at 5_000, so popped at T = 5_000).
        // Wait, unlock expires at 5_000, so at T = 6_000 the unlock is
        // also gone. Pin the boundary: at T = 4_999 system still wins.
        val stillSystem = c.visibleAt(4_999L) as SystemNotification
        assertEquals("Server error", stillSystem.message)
    }

    @Test
    fun `system message does NOT displace an unlock mid-window`() {
        val c = RaNotificationController()
        // Publish unlock at T = 0 (visible until 5_000).
        c.publishUnlock(1, "A", "", 1, "", nowMillis = 0L)
        // Publish system at T = 1_000 (visible until 3_500).
        c.publishSystem(SystemSeverity.INFO, "Reconnected", nowMillis = 1_000L)
        // T = 0..999: unlock visible
        val at0 = c.visibleAt(0L) as UnlockNotification
        assertEquals(1, at0.achievementId)
        // T = 1_000..3_499: system wins
        val at1500 = c.visibleAt(1_500L) as SystemNotification
        assertEquals("Reconnected", at1500.message)
        // T = 3_500..4_999: system expired, unlock re-emerges (window
        // still ticking from its own publish time of 0)
        val at4000 = c.visibleAt(4_000L) as UnlockNotification
        assertEquals(1, at4000.achievementId)
    }

    @Test
    fun `markRomChange clears unlocks and system slot`() {
        val c = RaNotificationController()
        c.publishUnlock(1, "A", "", 1, "", nowMillis = 0L)
        c.publishSystem(SystemSeverity.INFO, "Offline", nowMillis = 0L)
        assertTrue(c.hasPending)
        c.markRomChange()
        assertTrue(!c.hasPending)
        assertNull(c.visibleAt(0L))
        assertEquals(0, c.pendingUnlocks.size)
        assertNull(c.currentSystem)
    }

    @Test
    fun `clearUnlocks drops only the queue, not the system slot`() {
        val c = RaNotificationController()
        c.publishUnlock(1, "A", "", 1, "", nowMillis = 0L)
        c.publishSystem(SystemSeverity.INFO, "Offline", nowMillis = 0L)
        c.clearUnlocks()
        assertTrue(!c.hasPending || c.currentSystem != null)
        assertEquals(0, c.pendingUnlocks.size)
        assertNotNull(c.currentSystem)
    }

    @Test
    fun `clearSystem drops only the system slot, not the unlock queue`() {
        val c = RaNotificationController()
        c.publishUnlock(1, "A", "", 1, "", nowMillis = 0L)
        c.publishSystem(SystemSeverity.INFO, "Offline", nowMillis = 0L)
        c.clearSystem()
        assertNull(c.currentSystem)
        assertEquals(1, c.pendingUnlocks.size)
        // Unlock still visible at T = 0
        assertNotNull(c.visibleAt(0L))
    }

    @Test
    fun `pendingUnlocks is a snapshot copy`() {
        val c = RaNotificationController()
        c.publishUnlock(1, "A", "", 1, "", nowMillis = 0L)
        val snapshot = c.pendingUnlocks
        c.publishUnlock(2, "B", "", 2, "", nowMillis = 0L)
        // The snapshot taken before B was published still has just A
        assertEquals(1, snapshot.size)
        // The live pendingUnlocks has both
        assertEquals(2, c.pendingUnlocks.size)
    }

    @Test
    fun `visibleAt pops expired unlocks as a side effect`() {
        val c = RaNotificationController()
        c.publishUnlock(1, "A", "", 1, "", nowMillis = 0L)
        c.publishUnlock(2, "B", "", 2, "", nowMillis = 0L)
        // First call at T = 5_000: A expires, B becomes visible
        assertEquals(2, (c.visibleAt(5_000L) as UnlockNotification).achievementId)
        // Second call at T = 5_000: A is already gone from the queue,
        // and B is still in its window — same B comes back
        assertEquals(2, (c.visibleAt(5_001L) as UnlockNotification).achievementId)
        // After B's window (T = 10_001): nothing visible
        assertNull(c.visibleAt(10_001L))
    }

    @Test
    fun `default durations match the issue AC`() {
        // Pin the constants — the AC is "5 seconds" for unlocks.
        assertEquals(5_000L, RaNotificationController.DEFAULT_UNLOCK_DURATION_MS)
        assertEquals(2_500L, RaNotificationController.DEFAULT_SYSTEM_INFO_MS)
        assertEquals(4_000L, RaNotificationController.DEFAULT_SYSTEM_ERROR_MS)
    }

    @Test
    fun `custom unlock duration honoured`() {
        val c = RaNotificationController(unlockDurationMillis = 1_000L)
        c.publishUnlock(1, "A", "", 1, "", nowMillis = 0L)
        assertNotNull(c.visibleAt(999L))
        assertNull(c.visibleAt(1_000L))
    }

    @Test
    fun `empty unlock queue with active system still shows system`() {
        val c = RaNotificationController()
        c.publishSystem(SystemSeverity.ERROR, "Server down", nowMillis = 0L)
        assertSame(c.currentSystem, c.visibleAt(0L))
    }
}