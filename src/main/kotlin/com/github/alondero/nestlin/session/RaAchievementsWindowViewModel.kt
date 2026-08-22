package com.github.alondero.nestlin.session

/**
 * Immutable view-model state for the RetroAchievements loaded-game
 * achievements window (issue #272).
 *
 * The controller publishes a fresh [RaAchievementsWindowViewModel] on
 * every relevant transition (ROM load, sign-in/out, unlock, measured
 * progress, generation bump). The UI binds to the latest view-model
 * and renders the variant that matches; tests assert on the variant
 * directly without reaching into private JavaFX node state (issue #272
 * AC: "View-model tests cover every state ... without asserting
 * private JavaFX nodes").
 *
 * Each variant carries a [generation] token matching the controller's
 * current generation. The UI's render path compares it against the
 * controller's current generation before mutating scene-graph nodes,
 * mirroring the pattern [RaBootPlacard] uses for the placard.
 *
 * The variants are intentionally NOT a single `data class` with nullable
 * fields — a sealed hierarchy makes the UI's `when` exhaustive (no
 * silent "show nothing" fall-through), and it pins the documented
 * state set as the source of truth. Tests assert against the variant
 * directly so the issue's AC ("tests cover every state") is a
 * structural property of the type, not a runtime check.
 */
sealed interface RaAchievementsWindowViewModel {
    /** Generation this view-model was published under. */
    val generation: Long

    /**
     * No achievements window content can be shown — the native
     * library isn't loaded or the service is otherwise unavailable.
     * Distinct from [SignedOut] (the user can still sign in here) and
     * [Offline] (a transient network problem).
     */
    data class Unavailable(
        override val generation: Long,
        /** Hint to the UI explaining what's missing — never sensitive. */
        val reason: String,
    ) : RaAchievementsWindowViewModel

    /**
     * The user is not signed in to RetroAchievements. The window shows
     * a "sign in to view achievements" placeholder with a path to the
     * sign-in action. Distinct from [Unavailable] (the service can
     * produce a session here) and [Offline] (a transient retry path).
     */
    data class SignedOut(override val generation: Long) : RaAchievementsWindowViewModel

    /**
     * The user is signed in but the service can't reach RetroAchievements
     * right now. The window shows a "temporarily offline — retrying"
     * placeholder. Not a permanent state — the controller re-publishes
     * with fresh data when the bridge reports back online.
     */
    data class Offline(override val generation: Long, val cause: String) : RaAchievementsWindowViewModel

    /**
     * No ROM is currently loaded. The window shows a "load a game to
     * view its achievements" placeholder.
     */
    data class NoRom(override val generation: Long) : RaAchievementsWindowViewModel

    /**
     * A ROM is loaded but it isn't recognized by the RetroAchievements
     * database (ROM hack / translation / alternate dump). The window
     * shows a subtle "this ROM isn't recognized" placeholder — no nag.
     */
    data class Unrecognized(
        override val generation: Long,
        val displayName: String,
        val virtualFilename: String,
    ) : RaAchievementsWindowViewModel

    /**
     * The ROM is recognized but the core achievement set is empty.
     * Distinct from [Unrecognized] (the game IS in the database, it
     * just has no core achievements). The window shows an explicit
     * "no core achievements" message.
     */
    data class NoCoreAchievements(
        override val generation: Long,
        val gameTitle: String,
        val gameImageUrl: String,
    ) : RaAchievementsWindowViewModel

    /**
     * The game is recognized with at least one core achievement. The
     * window renders the [snapshot]'s header (badge + title + counts +
     * progress bar) and the bucket-grouped achievement list.
     *
     * This is the only state with non-trivial payload — the snapshot
     * already carries the per-bucket + per-achievement detail the UI
     * binds to. No additional fields are needed.
     */
    data class Recognized(
        override val generation: Long,
        val snapshot: RaAchievementListSnapshot,
    ) : RaAchievementsWindowViewModel

    companion object {
        /**
         * Initial view-model — [Unavailable] with a default reason.
         * The first listener fire replaces this with whatever the
         * controller's initial state implies.
         */
        val INITIAL: RaAchievementsWindowViewModel = Unavailable(generation = 0L, reason = "RetroAchievements is starting…")
    }
}
