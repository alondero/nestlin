package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.EmulatorConfig
import com.github.alondero.nestlin.Nestlin

/**
 * The emulation loop, lifted out of [Nestlin] as part of issue #189.
 *
 * Owns:
 *
 *  - the `while (running)` loop body and its start/stop latch,
 *  - wall-clock throttle ([syncToWallClock]),
 *  - the [RewindStateMachine] dispatch (one `tick()` per iteration).
 *
 * Delegates:
 *
 *  - `stepCpuCycle()` back to [Nestlin] — Nestlin still owns the CPU/PPU/APU triple and the
 *    PPU "dot credit" accumulator. Keeping the cycle advance in Nestlin means the deterministic
 *    `movie.FrameStepper.runOneFrame` seam and unit tests can drive `stepCpuCycle()` without
 *    instantiating a [RunLoop].
 *
 * The [Clock] parameter is the seam that makes the throttle unit-testable: production uses
 * [SystemClock], tests inject a `FakeClock` (planned in a follow-up test file — issue #189
 * leaves the FakeClock implementation for the test phase).
 *
 * Threading: [start] blocks the calling thread until [stop] is invoked (sets [running] false).
 * The JavaFX app spins up a worker thread and calls [start]; the JavaFX thread calls [stop]
 * from any UI handler. The `@Volatile` on [running] makes the cross-thread visibility safe
 * (issue #12).
 */
class RunLoop(
    private val nestlin: Nestlin,
    val config: EmulatorConfig,
    private val rewindProvider: () -> RewindStateMachine,
    private val clock: Clock = SystemClock,
) {

    /** Cached rewind machine — resolved on first [start] so init-order can't cause a read-before-init. */
    private val rewind: RewindStateMachine by lazy { rewindProvider() }

    // @Volatile: start() runs on the emulation thread and reads this in the while-loop; stop()
    // is called from the UI thread. Without the volatile barrier the JMM permits the emulation
    // thread to cache the read and the loop may never observe the stop (issue #12).
    @Volatile
    private var running = false

    private var nextSyncDeadlineNanos: Long = 0
    private var ticksSinceLastSync: Int = 0

    /** True between [start] and [stop]. Read on the UI thread to drive UI affordances. */
    val isRunning: Boolean get() = running

    fun start() {
        running = true
        nextSyncDeadlineNanos = clock.nowNanos()
        ticksSinceLastSync = 0

        try {
            while (running) {
                if (config.paused) {
                    clock.sleepNanos(PAUSE_TICK_NANOS)
                    // Reset throttle baseline so the first frame after resume isn't sped up.
                    // Without this, syncToWallClock would treat the pause duration as drift to "catch up".
                    nextSyncDeadlineNanos = clock.nowNanos()
                    ticksSinceLastSync = 0
                    continue
                }
                when (rewind.tick()) {
                    RewindStateMachine.TickAction.STEP_REWIND -> {
                        // Reset the throttle baseline on every scrub tick. The scrub rewound
                        // wall-clock time, which the throttle would otherwise treat as drift
                        // to "catch up" by sprinting frames. Resetting every step (rather than
                        // only on transition out of SCRUBBING) keeps the deadline fresh while
                        // the machine is scrubbing — by the time we hit STEP_NORMAL again,
                        // the baseline is already fresh.
                        ticksSinceLastSync = 0
                        nextSyncDeadlineNanos = clock.nowNanos()
                    }
                    RewindStateMachine.TickAction.STEP_NORMAL -> {
                        nestlin.stepCpuCycle()
                        ticksSinceLastSync++
                        if (ticksSinceLastSync >= TICKS_PER_SYNC_CHUNK) {
                            ticksSinceLastSync = 0
                            syncToWallClock()
                        }
                    }
                    RewindStateMachine.TickAction.NOOP -> { /* nothing */ }
                }
            }
        } finally {
            // TODO: Development-only feature - Remove undocumented opcode dumping once emulator stability is proven
            // Always dump undocumented opcodes, even if emulation crashes
            nestlin.cpu.dumpUndocumentedOpcodes()
        }
    }

    fun stop() {
        running = false
        // Defensive: if we're stopped mid-rewind, make sure audio isn't left muted for the
        // next start(). outputMuted is volatile so this is safe across threads.
        nestlin.apu.outputMuted = false
    }

    // Sleeping the emulation thread for ~16 ms per frame starves the APU consumer;
    // syncing every ~1 ms keeps Apu.audioBuffer continuously fed without changing the
    // total throttle time.
    private fun syncToWallClock() {
        if (!config.speedThrottlingEnabled) return

        val region = nestlin.regionConfig.region
        val nanosPerTick = nestlin.regionConfig.targetFrameTimeNanos / region.cpuCyclesPerFrame
        nextSyncDeadlineNanos += TICKS_PER_SYNC_CHUNK * nanosPerTick

        val now = clock.nowNanos()
        val ahead = nextSyncDeadlineNanos - now

        if (ahead > MIN_SLEEP_NANOS) {
            clock.sleepNanos(ahead)
        } else if (-ahead > MAX_DRIFT_NANOS) {
            // Emulation is running slower than wall clock and falling behind. Reset
            // the deadline so we don't try to "catch up" by skipping all future sleeps
            // (which would defeat throttling on hosts where the emu can briefly outpace
            // its target during transient stalls).
            nextSyncDeadlineNanos = now
        }
    }

    companion object {
        // Sync to wall clock every ~1 ms of emulated time (1789 ticks at 1.79 MHz).
        // Small enough that APU production gaps are barely perceptible to the audio
        // thread, large enough that sync overhead stays negligible.
        private const val TICKS_PER_SYNC_CHUNK = 1789
        // Skip sub-0.1 ms sleeps; JVM/OS timer resolution can't honor them reliably.
        private const val MIN_SLEEP_NANOS = 100_000L
        // If wall-clock is more than 100 ms ahead of emulation, give up trying to catch up.
        private const val MAX_DRIFT_NANOS = 100_000_000L
        // When paused, sleep this long between loop iterations. Long enough to be cheap,
        // short enough that resume latency is imperceptible.
        private const val PAUSE_TICK_NANOS = 10_000_000L
    }
}
