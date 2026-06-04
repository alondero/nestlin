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
        assertFalse(fc.tick(8312).quarterFrame)
        // ...and the quarter-frame clock fires exactly at it.
        assertTrue(fc.tick(8313).quarterFrame)
    }

    @Test
    fun `noise period table is region-specific`() {
        // Period index 4: NTSC = 64 cycles, PAL = 60 cycles.
        val ntsc = NoiseChannel().apply { region = Region.NTSC; isEnabled = true }
        ntsc.write400E(0x04.toByte())
        ntsc.write400F(0x00.toByte())
        assertEquals(64, ntsc.timerCounter)

        val pal = NoiseChannel().apply { region = Region.PAL; isEnabled = true }
        pal.write400E(0x04.toByte())
        pal.write400F(0x00.toByte())
        assertEquals(60, pal.timerCounter)
    }

    @Test
    fun `setting Apu region cascades to the frame counter`() {
        val apu = Apu(Memory())
        apu.region = Region.PAL
        assertEquals(33254, apu.frameCounterMaxCycles())  // 4-step default, PAL
        // Audio output sample rate is fixed at 44.1 kHz regardless of region.
        assertEquals(44100.0, apu.outputSampleRateHz(), 0.0001)
    }
}
