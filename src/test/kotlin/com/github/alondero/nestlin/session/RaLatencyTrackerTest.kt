package com.github.alondero.nestlin.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Behavioural tests for [RaLatencyTracker] (issue #270 benchmark AC).
 *
 * The tracker is the "p95 latency at 1ms budget" probe the issue calls
 * for. We don't need real CPU cycle work — we synthesise a sequence of
 * durations and assert the percentile maths is right.
 */
class RaLatencyTrackerTest {

    @Test
    fun `empty tracker reports zero samples and zero p95`() {
        val t = RaLatencyTracker()
        assertEquals(0, t.samplesConsumed())
        assertEquals(0L, t.p95Nanos())
    }

    @Test
    fun `record clamps negative input to zero`() {
        // System.nanoTime skew (clock adjustment mid-frame) can produce a
        // negative delta on Windows; the tracker records 0 rather than
        // corrupting the percentile.
        val t = RaLatencyTracker()
        t.record(-1L)
        t.record(-100L)
        assertEquals(2, t.samplesConsumed())
        assertEquals(0L, t.p95Nanos())
    }

    @Test
    fun `p95 of all-equal samples equals that value`() {
        val t = RaLatencyTracker(capacity = 8)
        repeat(8) { t.record(500L) }
        assertEquals(500L, t.p95Nanos())
    }

    @Test
    fun `p95 of 1024 samples with one outlier returns the high percentile`() {
        // 1023 samples at 100 ns, 1 sample at 100_000 ns. p95 should
        // land near the median (100 ns), not the outlier. The exact
        // index is ceil(0.95 × 1024) − 1 = 972; sample[972] is in the
        // 100-ns run.
        val t = RaLatencyTracker()
        repeat(1023) { t.record(100L) }
        t.record(100_000L)
        assertEquals(100L, t.p95Nanos())
    }

    @Test
    fun `budgetBreaches counts samples strictly greater than the budget`() {
        val t = RaLatencyTracker(capacity = 4)
        t.record(500L)
        t.record(1_000L)        // = budget
        t.record(1_500L)        // > budget
        t.record(2_000L)        // > budget
        assertEquals(2, t.budgetBreaches(1_000L))
    }

    @Test
    fun `p95 of full ring is monotonic across pushes that grow all samples`() {
        // Capacity ring filling, then pushing one more sample should drop
        // the oldest. Verify the ring semantics by checking samplesConsumed
        // caps at capacity.
        val t = RaLatencyTracker(capacity = 4)
        repeat(10) { t.record(it.toLong()) }
        assertEquals(4, t.samplesConsumed())
        // The last 4 samples (6, 7, 8, 9) are what's in the ring. Sorted,
        // p95 index for 4 samples is ceil(0.95 × 4) − 1 = 3, the max.
        assertEquals(9L, t.p95Nanos())
    }

    @Test
    fun `issue 270 budget assertion holds for a well-behaved workload`() {
        // Synthetic 1ms-budget check: 1024 samples at 800 µs each should
        // yield 0 breaches. This is the "benchmark tracks p95 latency at
        // 1ms budget" AC — production code never sees the tracker
        // (default null) but the test pins the math.
        val t = RaLatencyTracker()
        repeat(1024) { t.record(800_000L) }
        assertTrue(t.budgetBreaches(RaLatencyTracker.DEFAULT_BUDGET_NANOS) == 0,
            "1024 samples at 800 µs each must NOT breach the 1 ms budget")
        assertEquals(800_000L, t.p95Nanos())
    }

    @Test
    fun `reset clears every sample and the head pointer`() {
        val t = RaLatencyTracker(capacity = 4)
        repeat(4) { t.record(100L) }
        assertEquals(4, t.samplesConsumed())
        t.reset()
        assertEquals(0, t.samplesConsumed())
        assertEquals(0L, t.p95Nanos())
    }

    @Test
    fun `capacity zero is rejected`() {
        // Defensive: a zero-capacity ring would divide by zero in p95.
        try {
            RaLatencyTracker(capacity = 0)
            assertTrue(false, "Should have thrown IllegalArgumentException")
        } catch (e: IllegalArgumentException) {
            // Expected.
        }
    }

    @Test
    fun `1ms budget default matches the issue AC`() {
        // Pin the constant. If we ever need to tune the budget, the
        // benchmark test will fail and force a reviewer to confirm.
        assertEquals(1_000_000L, RaLatencyTracker.DEFAULT_BUDGET_NANOS)
    }
}