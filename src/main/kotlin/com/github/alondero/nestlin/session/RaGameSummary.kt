package com.github.alondero.nestlin.session

/**
 * Per-game achievement progress snapshot used by the boot placard
 * (issue #269).
 *
 * Distinct from [RaAccount] (which is the user-level totals — score,
 * display name, unread count) and from `ra_game_info_t` (which only
 * exposes state + a few booleans). [RaGameSummary] is what the boot
 * placard binds to AFTER identification completes: the game title
 * (for the heading), the core achievement counts (for
 * "unlocked X of Y"), the earned points (for "earned A of B points"),
 * and the badge image URL (for the placard's left-hand icon).
 *
 * All counts are zero when the user is not signed in or no game is
 * loaded. The coordinator checks sign-in state before asking the
 * service for a summary, so this type is a pure value object — no
 * lazy fields, no defaults that could mask a "no achievements" vs
 * "not signed in" distinction at the UI layer.
 */
data class RaGameSummary(
    /**
     * RA database game id (0 when unidentified). The placard doesn't surface
     * this; it's preserved for future "open on retroachievements.org" links.
     */
    val gameId: Int,
    /**
     * Game title as rcheevos reports it. Distinct from the iNES internal
     * title — the server normalizes the title against its own database.
     */
    val title: String,
    /**
     * Official NES hash as rcheevos computed it. Matches
     * [GameSessionInfo.nesHash] when both succeed; used for cache keys.
     */
    val hash: String,
    /**
     * Badge filename (e.g. "000001"). Empty when rcheevos hasn't assigned a
     * badge yet. Used to build the official badge URL.
     */
    val badgeName: String,
    /**
     * Absolute URL of the badge image. Empty when no badge is known yet.
     * The image cache dedupes fetches by this URL.
     */
    val imageUrl: String,
    /**
     * Total core achievements. Zero when the game has no core set.
     */
    val numCoreAchievements: Int,
    /**
     * Total points the core set is worth. Zero when no core set.
     */
    val pointsCore: Int,
    /**
     * Achievements the signed-in user has already unlocked (softcore).
     * Zero on first launch with a never-before-seen ROM.
     */
    val numUnlockedAchievements: Int,
    /**
     * Points the user has earned toward the core set. Zero on first launch.
     */
    val pointsUnlocked: Int,
) {
    /**
     * True iff the game has a core achievement set at all. Used by the boot
     * placard to distinguish "no achievements" from "achievements not yet
     * loaded" (AC #7).
     */
    val hasCoreAchievements: Boolean get() = numCoreAchievements > 0

    /**
     * True iff the game has been recognized by the server (gameId > 0).
     * Used by the placard to distinguish "ROM not recognized" (gameId == 0)
     * from "ROM recognized, no core achievements" (gameId > 0 && numCore == 0).
     */
    val isRecognized: Boolean get() = gameId > 0 && hash.isNotEmpty()
}
