package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.Region
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Behavioural tests for [RunLoop] (issue #189).
 *
 * These are end-to-end against a real [Nestlin] (no ROM — the loop ticks cycles on bare hardware
 * just fine; it doesn't need a mapper). The tests use a [FakeClock] so the throttle is
 * deterministic and we can assert on recorded sleeps without actually sleeping.
 *
 * What we're verifying:
 *  - `start()` ticks the emulator until `stop()` is called from another thread.
 *  - With throttling on, the loop records throttle sleeps via the injected clock (no real
 *    Thread.sleep occurs — the FakeClock records them).
 *  - The `running` field is observable across threads (issue #12's @Volatile guarantee survives
 *    the move from Nestlin to RunLoop).
 */
class RunLoopTest {

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `stop called from another thread terminates start`() {
        val nes = Nestlin()
        nes.config.speedThrottlingEnabled = false  // no throttle sleeps — fastest test
        val rewind = buildSilentRewind(nes)
        val clock = FakeClock()
        val loop = RunLoop(nes, nes.config, { rewind }, clock)

        val thread = Thread({ loop.start() }, "runloop-test")
        thread.isDaemon = true
        thread.start()
        Thread.sleep(50)              // let the loop spin
        loop.stop()
        thread.join(2_000)

        assertTrue(!thread.isAlive, "RunLoop.start() did not terminate within 2s of stop()")
        assertTrue(nes.ppu.ticksElapsed > 0, "Loop should have ticked PPU at least once")
        assertTrue(!nes.apu.outputMuted, "Stop should defensively unmute audio")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `throttle records sleeps via injected clock without touching real Thread sleep`() {
        val nes = Nestlin()
        nes.config.speedThrottlingEnabled = true
        val rewind = buildSilentRewind(nes)
        val clock = FakeClock(now = 1_000_000_000L)  // arbitrary non-zero baseline
        val loop = RunLoop(nes, nes.config, { rewind }, clock)

        val thread = Thread({ loop.start() }, "runloop-throttle-test")
        thread.isDaemon = true
        thread.start()

        // Wait long enough for at least one sync chunk (~1 ms of emulated time) — we just want
        // the throttle to *have run* at least once. The exact sleep count is non-deterministic
        // because the throttle sleeps based on `ahead` vs the fake clock, but at least one
        // recorded sleep is the assertion.
        Thread.sleep(100)
        loop.stop()
        thread.join(2_000)

        assertTrue(clock.recordedSleeps.isNotEmpty(),
            "With throttling on, the loop should have recorded at least one sleep")
        // Every recorded sleep should be > MIN_SLEEP_NANOS (100 us) — the throttle skips sub-0.1ms
        // sleeps to dodge JVM timer resolution variance. If a sub-100us sleep leaked through it
        // would mean the throttle's MIN_SLEEP_NANOS guard broke.
        assertTrue(clock.recordedSleeps.all { it >= 100_000L },
            "Throttle sleeps should respect MIN_SLEEP_NANOS, got ${clock.recordedSleeps}")
    }

    @Test
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    fun `paused loop sleeps the pause tick each iteration and advances no cycles`() {
        val nes = Nestlin()
        nes.config.speedThrottlingEnabled = false
        nes.config.paused = true
        val rewind = buildSilentRewind(nes)
        val clock = FakeClock()
        val loop = RunLoop(nes, nes.config, { rewind }, clock)

        val startTicks = nes.ppu.ticksElapsed
        val thread = Thread({ loop.start() }, "runloop-paused-test")
        thread.isDaemon = true
        thread.start()
        Thread.sleep(50)
        loop.stop()
        thread.join(2_000)

        // While paused the loop only sleeps; no CPU/PPU/APU cycles are stepped.
        assertTrue(nes.ppu.ticksElapsed == startTicks,
            "Paused loop should not advance PPU (started at $startTicks, ended at ${nes.ppu.ticksElapsed})")
        // The pause path uses a fixed 10ms tick sleep — verify that's what we recorded.
        assertTrue(clock.recordedSleeps.all { it == 10_000_000L },
            "Pause path should record 10ms sleeps, got ${clock.recordedSleeps}")
    }

    @Test
    fun `region-derived throttle nanos per tick matches region cpuCyclesPerFrame math`() {
        // Pure-math sanity check the throttle uses: nanosPerTick = targetFrameTimeNanos /
        // cpuCyclesPerFrame. We don't need to drive the loop here — just verify the
        // RegionConfig math yields the numbers the throttle depends on.
        val region = Region.NTSC
        val rc = RegionConfig(region)
        val nanosPerTick = rc.targetFrameTimeNanos / region.cpuCyclesPerFrame
        // NTSC: ~60.0988 Hz * ~29830 cycles/frame ≈ 558 ns/cycle
        assertTrue(nanosPerTick in 500L..600L,
            "Expected ~558 ns/tick for NTSC, got $nanosPerTick")
        // PAL: ~50.007 Hz * ~33248 cycles/frame ≈ 601 ns/cycle
        val palRegion = Region.PAL
        val palNanosPerTick = RegionConfig(palRegion).targetFrameTimeNanos / palRegion.cpuCyclesPerFrame
        assertTrue(palNanosPerTick in 550L..650L,
            "Expected ~601 ns/tick for PAL, got $palNanosPerTick")
    }

    /**
     * Build a [RewindStateMachine] wired against a real Nestlin but with rewind disabled —
     * the loop's rewind path is tested separately in [RewindStateMachineTest]. Here we want
     * the run loop to take the STEP_NORMAL branch every tick.
     */
    private fun buildSilentRewind(nes: Nestlin): RewindStateMachine {
        nes.config.rewindEnabled = false  // every captureFrame short-circuits
        return RewindStateMachine(
            buffer = nes.rewindBuffer,
            saveState = { ByteArray(0) },
            loadState = { },
            renderOneFrame = { },
            setMuted = { nes.apu.outputMuted = it },
            paceFrame = { },
            park = { },
            isGameLoaded = { true },
            isEnabled = { false },  // state machine tests already cover the enabled branch
        )
    }

    @Suppress("unused")
    private val unusedCounter = AtomicLong(0)
}
