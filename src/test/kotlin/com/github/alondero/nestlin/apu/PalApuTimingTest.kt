package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.Apu
import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.Region
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * PAL-specific APU timing: the frame-counter sequence wraps later, and the
 * noise/DMC period tables differ from NTSC. NTSC paths are asserted too so the
 * defaults stay bit-identical to the pre-region code.
 *
 * Noise timing tests are observable through [Apu.tick] rather than through the
 * internal [NoiseChannel.timerCounter] representation. The issue-#295 fix
 * switched the channel to counting in APU cycles (so [NoiseChannel.timerCounter]
 * holds `period / 2`), which means an assertion like
 * `assertEquals(64, ntsc.timerCounter)` would now be testing the representation
 * rather than the externally observable spacing — exactly the trap the issue
 * calls out. These tests instead drive `Apu.tick` one CPU cycle at a time and
 * check that the deltas between LFSR shifts equal the table entry.
 */
class PalApuTimingTest {

    @Test
    fun `frame counter wrap point is region-specific`() {
        val fc = FrameCounter()
        fc.mode = FrameCounter.Mode.FOUR_STEP

        fc.region = Region.NTSC
        assertEquals(29830, fc.maxCycles())
        fc.region = Region.PAL
        assertEquals(33254, fc.maxCycles())
    }

    @Test
    fun `PAL four-step quarter frame fires at the PAL first-step boundary`() {
        val fc = FrameCounter().apply { region = Region.PAL; mode = FrameCounter.Mode.FOUR_STEP }

        // Nothing should fire before the PAL step-0 boundary (8313)...
        repeat(8312) { assertFalse(fc.tick().quarterFrame) }
        // ...and the quarter-frame clock fires exactly at it (the 8313th cycle).
        assertTrue(fc.tick().quarterFrame)
    }

    @Test
    fun `NTSC noise LFSR shifts at 4 CPU cycles apart for period index 0`() {
        assertShiftSpacing(region = Region.NTSC, periodIndex = 0, expectedSpacing = 4)
    }

    @Test
    fun `NTSC noise LFSR shifts at 64 CPU cycles apart for period index 4`() {
        assertShiftSpacing(region = Region.NTSC, periodIndex = 4, expectedSpacing = 64)
    }

    @Test
    fun `NTSC noise LFSR shifts at 4068 CPU cycles apart for period index 15`() {
        assertShiftSpacing(region = Region.NTSC, periodIndex = 15, expectedSpacing = 4068)
    }

    @Test
    fun `PAL noise LFSR shifts at 14 CPU cycles apart for period index 2`() {
        assertShiftSpacing(region = Region.PAL, periodIndex = 2, expectedSpacing = 14)
    }

    @Test
    fun `PAL noise LFSR shifts at 3778 CPU cycles apart for period index 15`() {
        assertShiftSpacing(region = Region.PAL, periodIndex = 15, expectedSpacing = 3778)
    }

    @Test
    fun `setting Apu region cascades to the frame counter`() {
        // Factory (issue #22): Apu needs Memory as DmaPort for DMC; the factory
        // builds both and wires memory.apu non-null.
        val (_, apu) = Memory.createWithApu()
        apu.region = Region.PAL
        assertEquals(33254, apu.frameCounterMaxCycles())  // 4-step default, PAL
        // Audio output sample rate is fixed at 44.1 kHz regardless of region.
        assertEquals(44100.0, apu.outputSampleRateHz(), 0.0001)
    }

    /**
     * Drive [apu] for up to [maxCycles] CPU cycles and record the local tick
     * count at each point where `noise.shiftRegister` changes. Every shift
     * produces a new register value (the LFSR is a permutation of the
     * non-zero 15-bit states, so consecutive states always differ), so a
     * `shiftRegister` change is exactly a shift event.
     *
     * Asserts that all consecutive deltas equal [expectedSpacing]. Skips the
     * first delta because the phase of the first shift after [write400F] is
     * determined by the cpuCycleCounter parity at the moment the write lands;
     * the issue calls out that the FIRST reload after a period switch can
     * carry a ±1 cycle offset, but no permanent off-by-one is introduced.
     */
    private fun assertShiftSpacing(
        region: Region,
        periodIndex: Int,
        expectedSpacing: Int,
        shiftsToCapture: Int = 5,
        maxCycles: Int = expectedSpacing * (shiftsToCapture + 1) + 32
    ) {
        val (memory, apu) = Memory.createWithApu()
        apu.region = region

        // Configure the noise channel directly. Volume/envelope don't affect
        // LFSR spacing, so 0x0F is just a "channel is configured" placeholder
        // (envelope period nibble = 15, loop/halt and constant-volume bits
        // clear). Mode 0 (15-bit LFSR) is the default — periods are identical
        // in mode 1, only the feedback taps differ.
        memory[0x400C] = 0x0F.toByte()             // envelope period = 15, no loop/halt
        memory[0x400E] = periodIndex.toByte()      // mode 0, period index
        memory[0x400F] = 0x00.toByte()              // length load (resets timer + envelope)
        memory[0x4015] = 0x08.toByte()              // enable noise

        // Drive ticks one CPU cycle at a time. We track the LOCAL tick count
        // rather than apu.cpuCycles() so that the per-frame wrap at the
        // frame-counter max (29830 NTSC / 33254 PAL) can't perturb deltas.
        val transitions = mutableListOf<Int>()
        var prevRegister = apu.noise.shiftRegister
        var cycles = 0
        while (transitions.size < shiftsToCapture && cycles < maxCycles) {
            apu.tick()
            cycles++
            if (apu.noise.shiftRegister != prevRegister) {
                transitions.add(cycles)
                prevRegister = apu.noise.shiftRegister
            }
        }

        assertTrue(
            transitions.size >= shiftsToCapture,
            "captured only ${transitions.size} of $shiftsToCapture expected shifts within $maxCycles cycles " +
                "(region=$region, periodIndex=$periodIndex)"
        )

        // Steady-state spacing: every consecutive delta must equal the table
        // entry. The first delta is skipped because the phase of the very
        // first shift after write400F depends on the cpuCycleCounter parity
        // at the moment the write landed; per issue #295, hardware has the
        // same ±1 cycle ambiguity on a period change.
        for (i in 2 until transitions.size) {
            val delta = transitions[i] - transitions[i - 1]
            assertEquals(
                expectedSpacing,
                delta,
                "shift #${i + 1} arrived $delta CPU cycles after shift #$i " +
                    "(expected $expectedSpacing). All shifts: $transitions " +
                    "(region=$region, periodIndex=$periodIndex)"
            )
        }
    }
}
