package com.github.alondero.nestlin.session

/**
 * Runtime achievement event payload (issue #288).
 *
 * The listener registered on
 * [NativeRetroAchievementsService.achievementEventListener] receives one of
 * these for every native `RC_CLIENT_EVENT_ACHIEVEMENT_*` event the façade
 * drains on `evaluate_frame`. The single subscriber today is the
 * loaded-game achievements window's refresh path; the payload is kept
 * minimal so the same shape works for future per-achievement affordances
 * (per-achievement toast, leaderboard tracker, etc.) without growing
 * into a sealed hierarchy.
 *
 * ## Why not a sealed hierarchy
 *
 * Issue #288 was first implemented against a sealed `RaAchievementEvent`
 * with six variants (AchievementTriggered / ChallengeShow /
 * ChallengeHide / ProgressShow / ProgressHide / ProgressUpdate) and a
 * dedicated [RetroAchievementsEventBus] pub/sub surface. PR #290 review
 * correctly flagged that as over-engineered — all six variants carried
 * the same `achievementId: Int` payload that the sole subscriber
 * discarded. This data class captures the only field the listener
 * actually needs and the native service takes a single
 * `(() -> Unit)?`-shaped callback field that mirrors the existing
 * [NativeRetroAchievementsService.notificationListener] pattern.
 *
 * ## Threading
 *
 * Constructed on the emulation thread (the façade's `evaluate_frame` →
 * `drainEvents` → `handleEvent` path) and handed to the listener
 * synchronously. UI listeners that mutate scene-graph nodes MUST
 * re-post to the JavaFX Application Thread via
 * [javafx.application.Platform.runLater] before touching scene-graph
 * state — matches the boot-placard's recommendation.
 */
data class RaAchievementEvent(
    /** RA achievement ID the event refers to. */
    val achievementId: Int,
)