package com.github.alondero.nestlin.session

/**
 * Allocation-free p95 latency tracker for [RetroAchievementsService.evaluateFrame] (issue #270).
 *
 * The benchmark requirement is "p95 latency at a 1 ms budget". The tracker holds the
 * most-recent 1024 frame durations (nanos) in a fixed ring; [record] is O(1) and
 * allocates nothing; [p95Nanos] sorts a copy of the ring and returns the 970th-of-1024
 * sample (the standard `ceil(0.95 × N) − 1` index used by perf tooling).
 *
 * ## Why a ring (not an EMA / ReservoirSample)
 *
 * The issue requires a 1 ms **p95** budget — a single threshold that 95 % of frames
 * must stay under. EMAs and reservoir samplers give running estimates with bounded
 * memory, but they smooth over the long tail. The goal is to detect a 5-frame spike
 * (e.g. a complex `MeasuredIf` condition or a GC pause) — a smoothed metric hides it.
 * A fixed ring of 1024 samples is still 8 KB (1024 × 8 bytes) — well under the
 * threshold where the tracker itself becomes a GC pressure source.
 *
 * ## Why p95 (not p99 / max)
 *
 * A 1 ms budget is the upper bound at p95 — p99 catches rarer spikes (good for
 * debugging) but in normal play 5 % of frames will exceed any reasonable threshold
 * during ROM swaps / achievement set downloads. p95 is the industry-standard
 * "frame-budget" metric that maps cleanly to "the user notices a hiccup".
 *
 * ## Threading
 *
 * [record] runs on the emulation thread (the run loop's per-frame call site).
 * [p95Nanos] / [budgetBreaches] run from the test thread (or a diagnostic probe).
 * The ring is `@Volatile` in the sense that LongArray reads / writes are atomic per
 * the JVM spec for individual cells, but the index+value pair is a 2-step
 * compound write — call sites that read [p95Nanos] and then [samplesConsumed]
 * may see a torn read if [record] is mid-write. Tests are single-threaded by
 * design; production only reads p95Nanos from the diagnostic path, never on
 * the per-frame critical path.
 */
class RaLatencyTracker(
    /** Capacity of the ring. 1024 ≈ 17 s at 60 Hz; choose higher for longer windows. */
    private val capacity: Int = DEFAULT_CAPACITY,
) {

    init {
        require(capacity > 0) { "capacity must be positive, got $capacity" }
    }

    private val samples: LongArray = LongArray(capacity)
    private var head: Int = 0       // index of the next write slot
    private var populated: Int = 0  // number of populated slots, capped at `capacity`

    /**
     * Record a single frame's duration in nanoseconds. Negative inputs are
     * clamped to 0 — a System.nanoTime skew (clock adjustment mid-frame) can
     * produce a negative delta on Windows; the tracker records 0 rather than
     * corrupting the percentile calculation.
     */
    fun record(durationNanos: Long) {
        val clamped = if (durationNanos < 0) 0L else durationNanos
        samples[head] = clamped
        head = (head + 1) % capacity
        if (populated < capacity) populated++
    }

    /** Number of samples currently in the ring (0..capacity). */
    fun samplesConsumed(): Int = populated

    /**
     * The 95th-percentile duration in nanoseconds. Returns 0 when the ring is
     * empty. The implementation sorts a copy of the ring — O(N log N) — and
     * returns the `ceil(0.95 × N) − 1` indexed element. For N ≤ 20 the result
     * is the max (the math works out: ceil(19) − 1 = 18, last index).
     *
     * Allocation: one `LongArray(capacity)` per call. Call from tests / the
     * diagnostic probe, not the per-frame path.
     */
    fun p95Nanos(): Long {
        if (populated == 0) return 0L
        val copy = LongArray(populated)
        // System.arraycopy is faster than a for-loop for primitive arrays.
        if (populated < capacity) {
            System.arraycopy(samples, 0, copy, 0, populated)
        } else {
            // Ring is full — copy in ring order: [head..capacity-1] then [0..head-1].
            System.arraycopy(samples, head, copy, 0, capacity - head)
            System.arraycopy(samples, 0, copy, capacity - head, head)
        }
        java.util.Arrays.sort(copy)
        // ceil(0.95 × N) − 1 == ((N * 95) + 99) / 100 − 1, integer math.
        val idx = ((populated.toLong() * 95L + 99L) / 100L).toInt() - 1
        val safeIdx = idx.coerceIn(0, populated - 1)
        return copy[safeIdx]
    }

    /**
     * Number of samples in the ring that exceed [budgetNanos]. Used by the
     * benchmark assertion in the test (`budgetBreaches(1_000_000)` should be
     * 0 for a well-behaved workload). The 1 ms budget = 1_000_000 ns.
     *
     * O(N) — walks the populated slots. Same allocation profile as [p95Nanos].
     */
    fun budgetBreaches(budgetNanos: Long): Int {
        var breaches = 0
        for (i in 0 until populated) {
            if (samples[i] > budgetNanos) breaches++
        }
        return breaches
    }

    /** Drop every sample. Useful between test phases. */
    fun reset() {
        java.util.Arrays.fill(samples, 0L)
        head = 0
        populated = 0
    }

    companion object {
        /**
         * Default ring capacity: 1024 samples ≈ 17 s at 60 Hz. Power of two so
         * the modulo in [record] compiles to a bitmask on HotSpot.
         */
        const val DEFAULT_CAPACITY = 1024

        /** Default p95 budget from issue #270 AC: 1 ms per emulated frame. */
        const val DEFAULT_BUDGET_NANOS = 1_000_000L
    }
}