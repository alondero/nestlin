package com.github.alondero.nestlin.session

/**
 * Abstract time source for the emulation loop.
 *
 * The `RunLoop` reads wall-clock time and sleeps against this interface instead of calling
 * `System.nanoTime()` / `Thread.sleep()` directly, so the throttle and rewind pacing can be
 * unit-tested deterministically with a [FakeClock]. The production implementation is
 * [SystemClock]; tests inject `FakeClock`.
 *
 * Mirrors the seam [com.github.alondero.nestlin.Memory.peek] provides at the read side:
 * `Memory.get` has the side effects, `Memory.peek` is side-effect-free. Here,
 * `SystemClock.nowNanos()` is the live one, `FakeClock.nowNanos()` is the deterministic one
 * the tests can drive.
 *
 * See issue #189.
 */
interface Clock {
    /** Monotonic nanoseconds, analogous to `System.nanoTime()`. */
    fun nowNanos(): Long

    /**
     * Sleep for [nanos] nanoseconds, analogous to `Thread.sleep(millis, nanos)`. Implementations
     * must tolerate [nanos] being 0 or negative (return immediately) — the throttle skips
     * sub-`MIN_SLEEP_NANOS` sleeps to dodge JVM timer-resolution variance.
     */
    fun sleepNanos(nanos: Long)
}

/** Production clock — `System.nanoTime()` for time, `Thread.sleep(ms, ns)` for sleeps. */
object SystemClock : Clock {
    override fun nowNanos(): Long = System.nanoTime()
    override fun sleepNanos(nanos: Long) {
        if (nanos <= 0) return
        try {
            Thread.sleep(nanos / 1_000_000, (nanos % 1_000_000).toInt())
        } catch (e: InterruptedException) {
            // Restore the interrupt flag so the caller (RunLoop) can observe and exit cleanly.
            // The run loop polls a `@Volatile running` flag, not the interrupt flag, but the
            // standard "preserve the interrupt" contract still applies.
            Thread.currentThread().interrupt()
        }
    }
}

/**
 * Deterministic test clock (issue #189): records every sleep, advances a virtual clock when
 * `advanceBy(nanos)` is called.
 *
 * Tests can either:
 *  - assert `recordedSleeps` to verify the throttle / rewind pacing decisions, or
 *  - call `advanceBy(nanos)` after each iteration so the next `nowNanos()` reflects the
 *    requested wall-clock progression (lets `syncToWallClock`'s drift math run with realistic
 *    intervals without actually sleeping).
 *
 * The default virtual time starts at 0; `nowNanos()` returns `now + totalAdvanced`.
 */
class FakeClock(
    var now: Long = 0L,
) : Clock {
    private var totalAdvanced: Long = 0L
    val recordedSleeps: MutableList<Long> = mutableListOf()

    override fun nowNanos(): Long = now + totalAdvanced

    override fun sleepNanos(nanos: Long) {
        if (nanos <= 0) return
        recordedSleeps += nanos
        // Tests that care about the throttle's drift math should call advanceBy(...) to keep
        // the virtual clock in step. Default behaviour is "no auto-advance" so sleeps don't
        // silently move the clock forward — that would mask sleep-then-check patterns.
    }

    /** Move the virtual clock forward by [nanos]. Call after recording a sleep to keep the
     *  throttle's deadline math realistic. */
    fun advanceBy(nanos: Long) {
        totalAdvanced += nanos
    }

    /** Drop every recorded sleep — useful between test phases that should not see each other's sleeps. */
    fun resetRecordedSleeps() {
        recordedSleeps.clear()
    }
}
