package com.github.alondero.nestlin.session

import java.util.ArrayDeque

/**
 * Reusable queued notification controller for RetroAchievements UI feedback (issue #270).
 *
 * Replaces the issue-#129 [com.github.alondero.nestlin.ui.ToastController] "single-toast-at-a-time,
 * last-message-wins" model with the two-slot shape RetroAchievements needs:
 *
 *   - **Unlocks are FIFO queued.** Multiple `ACHIEVEMENT_TRIGGERED` events that fire in the same
 *     emulation frame (rare but possible — a save-state load with several triggers) show one
 *     after the other, each for ~5 s, in trigger order.
 *   - **System messages are single-slot.** A `DISCONNECTED` / `SERVER_ERROR` / `LEADERBOARD_SUBMITTED`
 *     message replaces the current system message; it does NOT queue behind unlocks and it does
 *     NOT displace a currently-displayed unlock.
 *   - **System messages do not displace unlocks.** When the system slot is empty and an unlock
 *     is showing, the unlock remains on screen. When the system slot is non-empty, the system
 *     message wins; the unlock continues to tick down its display window behind the scenes and
 *     becomes visible again once the system message expires.
 *
 * ## Why two slots (not one FIFO queue)
 *
 * The acceptance criterion is "System messages can only replace other system messages". A single
 * FIFO queue would either (a) let a server-error message displace an unlock mid-display, breaking
 * the "5 s persist" AC, or (b) let a server-error message wait behind a long backlog of unlocks,
 * making time-sensitive diagnostics (disconnect, retry exhaustion) silently delayed. Two slots
 * solve both: a one-line priority check in [visibleAt] makes the policy mechanical.
 *
 * ## FIFO + per-unlock 5 s window
 *
 * Each [UnlockNotification] carries its own `displayUntilMillis = publishTimeMillis + 5_000`.
 * The window starts when the unlock is **published**, NOT when it becomes the head of the queue.
 * If three unlocks fire at T=0, the first expires at T=5_000, the second at T=5_001 (one tick
 * later), and the third at T=5_002. The visible-at logic pops an unlock only when its own
 * window has elapsed, so each unlock gets its own full 5 s on screen — no "rounding loss" when
 * several fire at once.
 *
 * ## Side-effect-free / pure data
 *
 * The controller is a pure-data state machine: no JavaFX, no AWT, no scene-graph types. The
 * JavaFX render loop in [com.github.alondero.nestlin.ui.NestlinApplication] calls [visibleAt]
 * every frame and reflects the result onto a `javafx.scene.control.Label`. Tests can drive the
 * controller with a `FakeClock` style `nowMillis` parameter and assert on the exact
 * (notification, instant) sequence without ever booting JavaFX — that's the
 * "Controller tested without JavaFX" AC.
 *
 * ## ROM lifecycle
 *
 * [markRomChange] clears the unlock queue AND the system slot. Old unlocks are meaningless
 * once a new ROM boots; the system slot is cleared too because the offline/sync indicator
 * concerns the connection, but the only honest UX is to clear everything — once a new ROM is
 * on screen, showing "Reconnected — syncing pending unlocks" for the previous game would be
 * misleading. The system slot will repopulate naturally on the next disconnect/reconnect event.
 */
class RaNotificationController(
    /** Default unlock display window. The AC: "~5 seconds". */
    val unlockDurationMillis: Long = DEFAULT_UNLOCK_DURATION_MS,
    /** Default INFO system-message window. Errors override via [SystemSeverity]. */
    val systemInfoDurationMillis: Long = DEFAULT_SYSTEM_INFO_MS,
    /** Default ERROR system-message window. Longer than INFO so the user can read the diagnostic. */
    val systemErrorDurationMillis: Long = DEFAULT_SYSTEM_ERROR_MS,
) {

    private val unlocks: ArrayDeque<UnlockNotification> = ArrayDeque()
    private var system: SystemNotification? = null
    // Tracks when the current system message was published, so visibleAt
    // can return null for queries that pre-date the publish (a system
    // message published at T=1000 must not "backdate" into the T=0..999
    // window — the controller treats the publish time as the start of
    // the visible window, not the end of some earlier one).
    private var systemPublishedAtMillis: Long = 0L

    /**
     * True iff the controller has any visible-or-pending notification. The
     * JavaFX render pump uses this to avoid scene-graph mutations when the
     * overlay is already empty.
     */
    val hasPending: Boolean
        get() = unlocks.isNotEmpty() || system != null

    /**
     * Snapshot of the unlock backlog for diagnostics + tests. The list is a
     * copy — mutating it does not affect the controller.
     */
    val pendingUnlocks: List<UnlockNotification>
        get() = unlocks.toList()

    /** Snapshot of the system slot (or null). */
    val currentSystem: SystemNotification?
        get() = system

    /**
     * Queue an unlock notification. Does NOT take effect on [visibleAt] until
     * the previously-displayed unlock's 5 s window expires — the FIFO ordering
     * is a property of the deque, not of this method.
     */
    fun publishUnlock(
        achievementId: Int,
        title: String,
        description: String,
        points: Int,
        badgeUrl: String,
        nowMillis: Long,
    ): UnlockNotification {
        // Each unlock gets its own full 5 s window. Three unlocks fired
        // at the same instant would all have displayUntilMillis = nowMillis +
        // 5_000 and only the head would ever be visible; instead, we
        // chain them: the new entry's expiry is the previous tail's expiry
        // + duration (or nowMillis + duration when the queue is empty).
        // Result: 3 unlocks at T=0 expire at 5_000, 10_000, 15_000 — one
        // 5 s window each, in order.
        val newExpiry = if (unlocks.isEmpty()) {
            nowMillis + unlockDurationMillis
        } else {
            unlocks.peekLast().displayUntilMillis + unlockDurationMillis
        }
        val n = UnlockNotification(
            achievementId = achievementId,
            title = title,
            description = description,
            points = points,
            badgeUrl = badgeUrl,
            displayUntilMillis = newExpiry,
        )
        unlocks.addLast(n)
        return n
    }

    /**
     * Publish a system notification. **Replaces** the current system message
     * if any (the AC: "System messages can only replace other system messages").
     * Does NOT touch the unlock queue.
     */
    fun publishSystem(
        severity: SystemSeverity,
        message: String,
        nowMillis: Long,
    ): SystemNotification {
        val duration = when (severity) {
            SystemSeverity.INFO -> systemInfoDurationMillis
            SystemSeverity.ERROR -> systemErrorDurationMillis
        }
        val n = SystemNotification(
            severity = severity,
            message = message,
            displayUntilMillis = nowMillis + duration,
        )
        system = n
        systemPublishedAtMillis = nowMillis
        return n
    }

    /**
     * Return the notification that should be on screen at [nowMillis], or null
     * if nothing is visible. Priority:
     *
     *   1. The current system message — if it has not yet expired.
     *   2. The head of the unlock queue — if it has not yet expired.
     *   3. null.
     *
     * Side effect: expired unlocks are popped from the queue as part of the
     * read. This keeps [pendingUnlocks] in sync with what [visibleAt] would
     * return on a follow-up call.
     */
    fun visibleAt(nowMillis: Long): RaNotification? {
        val s = system
        if (s != null) {
            // Two conditions: nowMillis is at-or-after the system message's
            // publish time (don't backdate), AND nowMillis is before the
            // expiry. If nowMillis is past expiry, drop the slot.
            if (nowMillis < systemPublishedAtMillis) {
                // Pre-publish query — fall through to the unlock queue.
            } else if (nowMillis >= s.displayUntilMillis) {
                system = null
            } else {
                return s
            }
        }
        while (unlocks.isNotEmpty()) {
            val head = unlocks.peekFirst()!!
            if (nowMillis >= head.displayUntilMillis) {
                unlocks.pollFirst()
            } else {
                return head
            }
        }
        return null
    }

    /**
     * Drop every queued unlock AND the current system message. Called by
     * [GameSessionCoordinator] on every ROM lifecycle transition (load /
     / powerReset / unloadRom). Idempotent.
     *
     * Implementation note: the issue spec says "ROM changes clear obsolete
     * notifications" — the system slot is also cleared. Reasoning: the
     * system slot's text is about a specific game's sync state ("Reconnected
     * — syncing 3 pending unlocks"); once a new ROM is on screen, that
     * message no longer describes the user's reality. The next DISCONNECTED /
     * RECONNECTED event from the new game repopulates the slot.
     */
    fun markRomChange() {
        unlocks.clear()
        system = null
        systemPublishedAtMillis = 0L
    }

    /**
     * Drop only the system slot (e.g. after the user dismisses an offline
     * banner). Unlock queue is untouched. Not part of the issue's required
     * AC; exposed for the future "dismiss banner" affordance.
     */
    fun clearSystem() {
        system = null
        systemPublishedAtMillis = 0L
    }

    /**
     * Drop only the unlock queue. System slot is untouched. Useful for the
     * "clear all toasts" menu action without losing the offline indicator.
     */
    fun clearUnlocks() {
        unlocks.clear()
    }

    companion object {
        /** Issue #270 AC: "Unlock overlays persist ~5 seconds". */
        const val DEFAULT_UNLOCK_DURATION_MS: Long = 5_000L

        /** INFO system-message default window: 2.5 s. */
        const val DEFAULT_SYSTEM_INFO_MS: Long = 2_500L

        /** ERROR system-message default window: 4 s. Long enough to read a server code. */
        const val DEFAULT_SYSTEM_ERROR_MS: Long = 4_000L
    }
}

/**
 * A single RetroAchievements notification published by [RaNotificationController].
 *
 * All fields are immutable; the AC requires "events fully copied before callbacks return",
 * so once a [RaNotification] is in JVM heap there is no reference back to native memory.
 */
sealed interface RaNotification {
    /** Wall-clock instant (millis) at which this notification should be hidden. */
    val displayUntilMillis: Long
}

/**
 * Achievement unlock overlay payload (issue #270). The render pump reads every
 * field to draw the badge / title / description / points block on screen.
 *
 * Fields are defensive copies: the [description] / [title] / [badgeUrl] strings
 * come from `bytesToString` in [NativeRetroAchievementsService.handleEvent]
 * (already-owned JVM strings), so by the time they reach the controller there is
 * no native memory involved.
 */
data class UnlockNotification(
    val achievementId: Int,
    val title: String,
    val description: String,
    val points: Int,
    val badgeUrl: String,
    override val displayUntilMillis: Long,
) : RaNotification

/**
 * System message payload (issue #270): a server error, offline banner, leaderboard
 * confirmation, etc. Replaces the previous system message — never the unlock queue.
 *
 * [severity] drives both the render style (INFO = subtle gray pill, ERROR = red
 * bordered) and the display duration (longer for ERROR so the user can read a
 * server code).
 */
data class SystemNotification(
    val severity: SystemSeverity,
    val message: String,
    override val displayUntilMillis: Long,
) : RaNotification

/**
 * Visual severity of a [SystemNotification]. Mirrors the issue-#129
 * [com.github.alondero.nestlin.ui.ToastSeverity] enum but adds a longer ERROR
 * window because RA server errors carry a result code worth reading.
 */
enum class SystemSeverity { INFO, ERROR }