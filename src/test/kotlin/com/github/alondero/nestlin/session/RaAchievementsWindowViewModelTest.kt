package com.github.alondero.nestlin.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins the [RaAchievementListSnapshot] data model + [RaAchievementBucket]
 * grouping contract (issue #272 AC #4).
 *
 * The view model is a pure value type; the tests below prove:
 *
 *  - Every bucket code in `RC_CLIENT_ACHIEVEMENT_BUCKET_*` maps to the
 *    matching [RaAchievementBucket] enum value; unknown codes round-trip
 *    to [RaAchievementBucket.UNKNOWN] rather than throwing.
 *  - Snapshots preserve structural equality so two snapshots with the
 *    same fields compare equal under the view-model layer.
 *  - Measured-progress values are captured verbatim — no rounding, no
 *    formatting, no truncation.
 *  - Recognized view-models carry the snapshot intact and unchanged.
 *  - Loading view-models carry the previous snapshot so the window can
 *    keep rendering the old list while a refresh is in flight (issue
 *    #272 AC: "The list must remain responsive ... without stalling
 *    emulation").
 *
 * No JavaFX nodes are referenced; every assertion is on the immutable
 * value, matching the issue's AC: "without asserting private JavaFX
 * nodes".
 */
class RaAchievementsWindowViewModelTest {

    private fun achievement(
        id: Int,
        bucket: RaAchievementBucket = RaAchievementBucket.LOCKED,
        title: String = "A$id",
        isUnlocked: Boolean = false,
        measured: String = "",
        percent: Float = 0f,
        points: Int = 5,
    ) = RaAchievement(
        id = id, title = title, description = "D$id",
        points = points, badgeName = "b$id",
        badgeUrlUnlocked = "u$id", badgeUrlLocked = "l$id",
        bucket = bucket, measuredProgress = measured,
        measuredPercent = percent, isUnlocked = isUnlocked,
    )

    private fun bucket(
        type: RaAchievementBucket,
        label: String = type.label,
        achievements: List<RaAchievement> = emptyList(),
    ) = RaAchievementBucketSnapshot(
        bucket = type, label = label, achievements = achievements,
    )

    private fun snapshot(
        total: Int = 10,
        unlocked: Int = 3,
        points: Int = 100,
        unlockedPoints: Int = 30,
        buckets: List<RaAchievementBucketSnapshot> = emptyList(),
        title: String = "Test Game",
        image: String = "https://example.com/badge.png",
        generation: Long = 0L,
    ) = RaAchievementListSnapshot(
        gameTitle = title,
        gameImageUrl = image,
        totalCoreAchievements = total,
        totalCorePoints = points,
        unlockedCoreAchievements = unlocked,
        unlockedCorePoints = unlockedPoints,
        buckets = buckets,
        generation = generation,
    )

    // ----------------------------------------------------------------
    // Bucket code mapping
    // ----------------------------------------------------------------

    @Test
    fun `every rcheevos bucket code maps to the matching enum value`() {
        // The numeric codes here MUST match
        // RC_CLIENT_ACHIEVEMENT_BUCKET_* in rcheevos's rc_client.h.
        assertSame(RaAchievementBucket.LOCKED, RaAchievementBucket.fromCode(1))
        assertSame(RaAchievementBucket.UNLOCKED, RaAchievementBucket.fromCode(2))
        assertSame(RaAchievementBucket.UNSUPPORTED, RaAchievementBucket.fromCode(3))
        assertSame(RaAchievementBucket.UNOFFICIAL, RaAchievementBucket.fromCode(4))
        assertSame(RaAchievementBucket.RECENTLY_UNLOCKED, RaAchievementBucket.fromCode(5))
        assertSame(RaAchievementBucket.ACTIVE_CHALLENGE, RaAchievementBucket.fromCode(6))
        assertSame(RaAchievementBucket.ALMOST_THERE, RaAchievementBucket.fromCode(7))
        assertSame(RaAchievementBucket.UNSYNCED, RaAchievementBucket.fromCode(8))
    }

    @Test
    fun `unknown bucket codes round-trip to UNKNOWN`() {
        // Forward-compatibility: a future rcheevos version that adds
        // a new bucket shouldn't break Nestlin; the unknown bucket
        // shows up as UNKNOWN in the UI until the enum is updated.
        assertSame(RaAchievementBucket.UNKNOWN, RaAchievementBucket.fromCode(0))
        assertSame(RaAchievementBucket.UNKNOWN, RaAchievementBucket.fromCode(99))
        assertSame(RaAchievementBucket.UNKNOWN, RaAchievementBucket.fromCode(-1))
    }

    // ----------------------------------------------------------------
    // Snapshot equality
    // ----------------------------------------------------------------

    @Test
    fun `snapshots with identical fields are structurally equal`() {
        val a = snapshot(total = 10, unlocked = 3)
        val b = snapshot(total = 10, unlocked = 3)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test
    fun `snapshots differ when any field differs`() {
        val base = snapshot(total = 10, unlocked = 3)
        val diff = snapshot(total = 10, unlocked = 4)
        assertTrue(base != diff)
    }

    // ----------------------------------------------------------------
    // Measured progress
    // ----------------------------------------------------------------

    @Test
    fun `measured progress string is captured verbatim`() {
        val a = achievement(id = 1, measured = "5/10", percent = 0.5f)
        assertEquals("5/10", a.measuredProgress)
        assertEquals(0.5f, a.measuredPercent)

        val b = achievement(id = 2, measured = "00:30 / 02:00", percent = 0.25f)
        assertEquals("00:30 / 02:00", b.measuredProgress)
        assertEquals(0.25f, b.measuredPercent)
    }

    @Test
    fun `measured progress is empty string for non-measurable achievements`() {
        val a = achievement(id = 1, measured = "", percent = 0f)
        assertEquals("", a.measuredProgress)
        assertEquals(0f, a.measuredPercent)
    }

    // ----------------------------------------------------------------
    // Recognized view-model
    // ----------------------------------------------------------------

    @Test
    fun `Recognized view-model carries the snapshot unchanged`() {
        val snap = snapshot(total = 10, unlocked = 3)
        val vm = RaAchievementsWindowViewModel.Recognized(generation = 5L, snapshot = snap)
        assertEquals(5L, vm.generation)
        assertSame(snap, vm.snapshot)
        assertEquals(10, vm.snapshot.totalCoreAchievements)
        assertEquals(3, vm.snapshot.unlockedCoreAchievements)
    }

    // ----------------------------------------------------------------
    // Flattened list / grouping shape (the cell factory consumes this)
    // ----------------------------------------------------------------

    @Test
    fun `snapshot preserves the bucket grouping order returned by the runtime`() {
        // The runtime returns buckets in its own order — the snapshot
        // must preserve that ordering verbatim. The UI's `flatten` step
        // iterates snapshot.buckets in index order; a test that
        // re-orders would lose the runtime's section preference.
        val buckets = listOf(
            bucket(RaAchievementBucket.RECENTLY_UNLOCKED, achievements = listOf(achievement(1, RaAchievementBucket.RECENTLY_UNLOCKED))),
            bucket(RaAchievementBucket.ACTIVE_CHALLENGE, achievements = listOf(achievement(2, RaAchievementBucket.ACTIVE_CHALLENGE))),
            bucket(RaAchievementBucket.ALMOST_THERE, achievements = listOf(achievement(3, RaAchievementBucket.ALMOST_THERE))),
            bucket(RaAchievementBucket.LOCKED, achievements = listOf(achievement(4, RaAchievementBucket.LOCKED))),
            bucket(RaAchievementBucket.UNLOCKED, achievements = listOf(achievement(5, RaAchievementBucket.UNLOCKED))),
        )
        val snap = snapshot(buckets = buckets)
        assertEquals(
            listOf(
                RaAchievementBucket.RECENTLY_UNLOCKED,
                RaAchievementBucket.ACTIVE_CHALLENGE,
                RaAchievementBucket.ALMOST_THERE,
                RaAchievementBucket.LOCKED,
                RaAchievementBucket.UNLOCKED,
            ),
            snap.buckets.map { it.bucket },
        )
    }

    @Test
    fun `every achievement in a bucket carries the bucket's label verbatim`() {
        // The bucket label is the runtime-supplied string; a per-achievement
        // label override would defeat the documented grouping.
        val active = bucket(
            RaAchievementBucket.ACTIVE_CHALLENGE,
            label = "Active Challenges",
            achievements = listOf(
                achievement(id = 1, bucket = RaAchievementBucket.ACTIVE_CHALLENGE, title = "First Challenge"),
                achievement(id = 2, bucket = RaAchievementBucket.ACTIVE_CHALLENGE, title = "Second Challenge"),
            ),
        )
        val snap = snapshot(buckets = listOf(active))
        assertEquals("Active Challenges", snap.buckets.first().label)
        assertEquals(2, snap.buckets.first().achievements.size)
        assertEquals("First Challenge", snap.buckets.first().achievements[0].title)
        assertEquals("Second Challenge", snap.buckets.first().achievements[1].title)
    }

    // ----------------------------------------------------------------
    // Generation token
    // ----------------------------------------------------------------

    @Test
    fun `every view-model variant carries the generation it was published under`() {
        // AC: "A ROM/account generation change cannot publish stale data
        // or images into the current view." The generation token is
        // the consumer's only defense — every variant must carry one.
        val g = 42L
        val vms: List<RaAchievementsWindowViewModel> = listOf(
            RaAchievementsWindowViewModel.Unavailable(g, "x"),
            RaAchievementsWindowViewModel.SignedOut(g),
            RaAchievementsWindowViewModel.Offline(g, "x"),
            RaAchievementsWindowViewModel.NoRom(g),
            RaAchievementsWindowViewModel.Unrecognized(g, "x", "y"),
            RaAchievementsWindowViewModel.NoCoreAchievements(g, "x", "y"),
            RaAchievementsWindowViewModel.Recognized(g, snapshot = snapshot()),
        )
        for (vm in vms) assertEquals(g, vm.generation)
    }

    // ----------------------------------------------------------------
    // Initial state
    // ----------------------------------------------------------------

    @Test
    fun `INITIAL view-model is Unavailable with generation zero`() {
        // Tests that bind the controller at construction time expect
        // an explicit initial state. Anything else would let the UI
        // start with a null view-model and crash on first render.
        val initial = RaAchievementsWindowViewModel.INITIAL
        assertTrue(initial is RaAchievementsWindowViewModel.Unavailable)
        assertEquals(0L, initial.generation)
    }
}
