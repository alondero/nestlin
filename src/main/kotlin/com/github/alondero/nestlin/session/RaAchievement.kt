package com.github.alondero.nestlin.session

/**
 * Per-achievement snapshot for the RetroAchievements achievements window
 * (issue #272). Distinct from the game-level [RaGameSummary] — that one
 * holds the title + badge for the whole set; this one holds title,
 * description, points, badge URL, measured progress, and the runtime
 * bucket assignment for a single achievement.
 *
 * Every field is a copy-safe value:
 *  - Strings are Kotlin `String` copies of the native C strings the
 *    façade returned. The JNA side copied them out of rcheevos-owned
 *    memory before the call returned, so no native pointer is held by
 *    this struct.
 *  - `badgeName`, `badgeUrlUnlocked`, and `badgeUrlLocked` are the
 *    official `https://retroachievements.org/Images/<name>.png` URLs
 *    the image cache dedupes on.
 *  - `measuredProgress` is rcheevos's human-readable progress string
 *    (e.g. "5/10", "00:30 / 02:00", or `""` when the achievement has
 *    no measurable condition). `measuredPercent` is the float 0..1 the
 *    runtime uses for the "almost there" bucket assignment.
 *  - `bucket` is rcheevos's authoritative progress grouping
 *    (see [RaAchievementBucket]) — the window uses it to section the
 *    list without inventing its own categories.
 */
data class RaAchievement(
    /** RA achievement ID; matches the `RC_CLIENT_ACHIEVEMENT_*` event payload's `id`. */
    val id: Int,
    /** User-facing title (rcheevos's `achievement->title`). */
    val title: String,
    /** User-facing description. */
    val description: String,
    /** Core point value (0 for unofficial). */
    val points: Int,
    /** Official badge filename (e.g. "12345"); empty when rcheevos hasn't assigned one. */
    val badgeName: String,
    /** Unlocked-state badge URL. Empty when rcheevos hasn't resolved the URL yet. */
    val badgeUrlUnlocked: String,
    /** Locked-state badge URL. Empty when the set didn't ship one. */
    val badgeUrlLocked: String,
    /**
     * Runtime-assigned progress bucket (see [RaAchievementBucket]). The
     * window sections the list by this value; tests assert that the
     * bucket assignment matches the runtime's grouping rather than a
     * locally-recomputed category.
     */
    val bucket: RaAchievementBucket,
    /**
     * rcheevos's formatted progress text (e.g. "5/10", "00:30 / 02:00").
     * Empty when the achievement has no measurable condition or the
     * runtime hasn't measured it yet. The window shows this verbatim in
     * the achievement row's progress slot.
     */
    val measuredProgress: String,
    /**
     * rcheevos's progress fraction 0..1. Drives the "almost there" bucket
     * (>= some threshold) and the progress bar on each row. 0 when the
     * achievement has no measurable condition.
     */
    val measuredPercent: Float,
    /** True when the runtime marks this achievement as unlocked. */
    val isUnlocked: Boolean,
)

/**
 * Runtime progress grouping that rcheevos exposes via the achievement
 * list bucket enum. The window uses it as-is — no locally-invented
 * alternative categorisation (issue #272 AC #4). The labels are the
 * official English strings rcheevos surfaces; the window overrides
 * only the presentation, not the grouping itself.
 *
 * `ordinal` order matches `RC_CLIENT_ACHIEVEMENT_BUCKET_*` so the JNA
 * side can map directly. The label strings here are defaults; the
 * runtime's bucket label (e.g. "Active Challenges" vs "Active
 * Challenge") is what rcheevos actually reports and the snapshot
 * captures verbatim.
 */
enum class RaAchievementBucket(val label: String) {
    UNKNOWN("Unknown"),
    LOCKED("Locked"),
    UNLOCKED("Unlocked"),
    UNSUPPORTED("Unsupported"),
    UNOFFICIAL("Unofficial"),
    RECENTLY_UNLOCKED("Recently Unlocked"),
    ACTIVE_CHALLENGE("Active Challenges"),
    ALMOST_THERE("Almost There"),
    UNSYNCED("Unsynced");

    companion object {
        /**
         * Map rcheevos's `RC_CLIENT_ACHIEVEMENT_BUCKET_*` int to our enum.
         * Unknown codes round-trip to [UNKNOWN] rather than throwing —
         * a future rcheevos version that adds a new bucket is rendered as
         * "Unknown" until the enum is updated. This is the only place
         * the conversion lives; everything else consumes the enum.
         */
        fun fromCode(code: Int): RaAchievementBucket = when (code) {
            1 -> LOCKED
            2 -> UNLOCKED
            3 -> UNSUPPORTED
            4 -> UNOFFICIAL
            5 -> RECENTLY_UNLOCKED
            6 -> ACTIVE_CHALLENGE
            7 -> ALMOST_THERE
            8 -> UNSYNCED
            else -> UNKNOWN
        }
    }
}

/**
 * A single bucket within an [RaAchievementListSnapshot]. The bucket
 * carries the runtime-supplied label (so future rcheevos label changes
 * propagate without a Kotlin recompile) and the achievements assigned
 * to that bucket. Achievements within a bucket are presented in the
 * order rcheevos returned them — typically by ID.
 */
data class RaAchievementBucketSnapshot(
    val bucket: RaAchievementBucket,
    /** Runtime-supplied label (defaults to [RaAchievementBucket.label] when the runtime doesn't override). */
    val label: String,
    val achievements: List<RaAchievement>,
)

/**
 * Frozen snapshot of the currently-loaded game's achievement list, in
 * the runtime's official progress grouping. Returned by
 * [RetroAchievementsService.achievementListSnapshot]; consumed by the
 * achievements window view model.
 *
 * All fields are copied out of the native runtime at snapshot time —
 * no native pointer is retained past the call. The snapshot is
 * defensive-copied by [copy] so a UI consumer can mutate freely without
 * poisoning other listeners.
 *
 * The summary fields (gameTitle, unlocked/total, etc.) are duplicated
 * from the [RaGameSummary] the boot placard binds to — the
 * achievements window doesn't require a separate round-trip to the
 * façade for those, and bundling them with the list keeps the
 * view-model layer to a single immutable value per refresh.
 */
data class RaAchievementListSnapshot(
    /** Game title as rcheevos reports it. */
    val gameTitle: String,
    /** Game-level badge image URL. */
    val gameImageUrl: String,
    /** Total core achievements (== buckets[].count where bucket != UNOFFICIAL/UNSUPPORTED). */
    val totalCoreAchievements: Int,
    /** Total core points. */
    val totalCorePoints: Int,
    /** Achievements unlocked by the signed-in user (softcore). */
    val unlockedCoreAchievements: Int,
    /** Core points earned by the signed-in user. */
    val unlockedCorePoints: Int,
    /** All achievement buckets in display order. */
    val buckets: List<RaAchievementBucketSnapshot>,
    /**
     * Snapshot generation. Mirrors the [RaAchievementsController]
     * generation so the view model can assert "this snapshot belongs
     * to the generation the controller is currently serving" — a
     * late snapshot from a previous game cannot accidentally bind to
     * the new game's window.
     */
    val generation: Long,
)
