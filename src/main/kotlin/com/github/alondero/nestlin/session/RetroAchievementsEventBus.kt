package com.github.alondero.nestlin.session

import java.util.concurrent.CopyOnWriteArrayList

/**
 * Lightweight event bus for runtime-side RetroAchievements achievement
 * events (issue #288). Routes native `ACHIEVEMENT_TRIGGERED`,
 * `ACHIEVEMENT_CHALLENGE_*`, and `ACHIEVEMENT_PROGRESS_*` events from
 * the façade's event queue to in-process listeners (currently: the
 * loaded-game achievements window).
 *
 * Mirrors the listener surface from [RaSignInManager] and
 * [RaBootPlacardController]: `CopyOnWriteArrayList` + throw-isolated
 * publish. The bus itself is stateless across publishes — generation
 * guards live one layer up. Listeners re-query the service for a
 * fresh snapshot via [RaAchievementsController.refresh], which checks
 * the controller's `currentGeneration` before publishing, so a late
 * event from the previous game's runtime cannot overwrite the current
 * game's view-model.
 *
 * ## Threading
 *
 * [publish] is called synchronously on the emulation thread (the
 * façade's `evaluateFrame` → `drainEvents` → `handleEvent` path). UI
 * listeners that mutate scene-graph nodes MUST re-post to the JavaFX
 * Application Thread via [javafx.application.Platform.runLater] before
 * touching scene-graph state — matches the boot-placard's
 * recommendation.
 *
 * ## Throw isolation
 *
 * A listener that throws is caught and logged with a one-line stderr
 * message. A UI-side bug must not propagate into the emulation
 * thread's per-frame hot path; the same pattern is used by
 * [RaBootPlacardController.publish], [RaSignInManager.updateState],
 * and [RaAchievementsController.publish].
 *
 * ## Why not just call `refresh()` directly
 *
 * The naïve fix is to register a single callback that calls
 * `achievementsController.refresh()` on every unlock. The bus pattern
 * is preferred because (a) tests can drive the bus without standing
 * up the achievements controller, (b) future surfaces (a leaderboard
 * tracker, a recently-unlocked toast) can subscribe without coupling
 * to the window, and (c) the listener registration matches the
 * project's three existing event surfaces so the next contributor
 * can find the pattern in seconds.
 */
class RetroAchievementsEventBus {

    private val listeners: CopyOnWriteArrayList<(RaAchievementEvent) -> Unit> = CopyOnWriteArrayList()

    /**
     * Add a listener that fires on every [publish]. The listener runs
     * synchronously on the calling thread; UI listeners that mutate
     * scene-graph nodes MUST re-post to the JavaFX thread.
     *
     * Returns an opaque token; pass to [removeListener] to unsubscribe.
     */
    fun addListener(listener: (RaAchievementEvent) -> Unit): ListenerToken {
        listeners += listener
        return ListenerToken(listener)
    }

    /** Idempotent. */
    fun removeListener(token: ListenerToken) {
        listeners.remove(token.listener)
    }

    /**
     * Publish [event] to every listener. A listener that throws is
     * caught and logged — a UI-side bug must not propagate into the
     * emulation thread's per-frame hot path. Listeners fire in
     * registration order (CopyOnWriteArrayList preserves insertion
     * order).
     */
    fun publish(event: RaAchievementEvent) {
        for (l in listeners) {
            try {
                l(event)
            } catch (e: Exception) {
                System.err.println("[RA] Achievement event listener threw: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Snapshot of the current listener count. Test-only. */
    val listenerCount: Int get() = listeners.size

    /** Opaque token for [addListener] / [removeListener]. */
    data class ListenerToken internal constructor(internal val listener: (RaAchievementEvent) -> Unit)
}

/**
 * A native RetroAchievements runtime achievement event (issue #288).
 *
 * Each variant carries the achievement ID the event refers to. The
 * payload is intentionally minimal: the consumer (the loaded-game
 * achievements window) re-queries the service for a fresh snapshot
 * via [RaAchievementsController.refresh] — the event's job is to
 * signal "the runtime state has changed; refresh", not to carry the
 * new state inline.
 *
 * The variants map 1:1 to the rcheevos `RC_CLIENT_EVENT_ACHIEVEMENT_*`
 * events that affect the achievements list:
 *
 *   - [AchievementTriggered] — `RC_CLIENT_EVENT_ACHIEVEMENT_TRIGGERED`.
 *     The achievement's `isUnlocked` flag and the unlocked/total counts
 *     change on this event; the bucket assignment of every other
 *     achievement may also change (a "Recently Unlocked" entry appears).
 *   - [AchievementChallengeShow] / [AchievementChallengeHide] — challenge
 *     indicator shown / hidden on the runtime's HUD. Affects the
 *     bucket assignment (an achievement moves into / out of
 *     "Active Challenges").
 *   - [AchievementProgressShow] / [AchievementProgressHide] —
 *     progress tracker shown / hidden on the runtime's HUD.
 *   - [AchievementProgressUpdate] — measured progress text + percent
 *     updated; the achievement stays locked but its measured-progress
 *     column changes.
 */
sealed interface RaAchievementEvent {
    /** RA achievement ID the event refers to. */
    val achievementId: Int

    /**
     * Achievement unlocked. Emitted by the runtime the moment a trigger
     * condition is satisfied and the unlock has been queued for server
     * submission.
     */
    data class AchievementTriggered(
        override val achievementId: Int,
    ) : RaAchievementEvent

    /**
     * Challenge indicator for the achievement is now visible on the
     * runtime's HUD. The achievement's bucket assignment typically
     * changes to (or back from) `ACTIVE_CHALLENGE`.
     */
    data class AchievementChallengeShow(
        override val achievementId: Int,
    ) : RaAchievementEvent

    /** Challenge indicator hidden. */
    data class AchievementChallengeHide(
        override val achievementId: Int,
    ) : RaAchievementEvent

    /** Progress tracker for the achievement is now visible on the HUD. */
    data class AchievementProgressShow(
        override val achievementId: Int,
    ) : RaAchievementEvent

    /** Progress tracker hidden. */
    data class AchievementProgressHide(
        override val achievementId: Int,
    ) : RaAchievementEvent

    /**
     * Measured progress for the achievement updated. The achievement
     * stays locked; only its progress text + percent changes. Bucket
     * assignment may shift between `ACTIVE_CHALLENGE` and `ALMOST_THERE`.
     */
    data class AchievementProgressUpdate(
        override val achievementId: Int,
    ) : RaAchievementEvent
}