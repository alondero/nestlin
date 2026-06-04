package com.github.alondero.nestlin

import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ppu.Ppu
import com.github.alondero.nestlin.ui.FrameListener
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Core PAL vs NTSC timing: the PPU:CPU dot ratio, per-frame scanline geometry, and
 * how the effective region is resolved from override + ROM detection.
 */
class RegionTimingTest {

    @Test
    fun `region profiles carry the canonical NTSC and PAL constants`() {
        assertEquals(262, Region.NTSC.totalScanlines)
        assertEquals(261, Region.NTSC.preRenderScanline)
        assertEquals(30, Region.NTSC.ppuDotsPerCpuTimes10)  // 3.0

        assertEquals(312, Region.PAL.totalScanlines)
        assertEquals(311, Region.PAL.preRenderScanline)
        assertEquals(32, Region.PAL.ppuDotsPerCpuTimes10)   // 3.2
    }

    @Test
    fun `NTSC steps exactly 3 PPU dots per CPU cycle`() {
        val nestlin = Nestlin().apply {
            config.regionOverride = Region.NTSC
            applyRegion()
        }
        repeat(5) { nestlin.stepCpuCycle() }
        assertEquals(15L, nestlin.ppu.ticksElapsed)  // 5 × 3
    }

    @Test
    fun `PAL averages 3_2 PPU dots per CPU cycle as 3,3,3,3,4`() {
        val nestlin = Nestlin().apply {
            config.regionOverride = Region.PAL
            applyRegion()
        }
        repeat(5) { nestlin.stepCpuCycle() }
        assertEquals(16L, nestlin.ppu.ticksElapsed)  // 3+3+3+3+4 = 16 over 5 cycles
    }

    @Test
    fun `PAL frame has exactly 50 more scanlines worth of dots than NTSC`() {
        val ntscDots = dotsPerFrame(Region.NTSC)
        val palDots = dotsPerFrame(Region.PAL)

        val dotsPerScanline = ntscDots / Region.NTSC.totalScanlines
        // Geometry is consistent: each frame is exactly totalScanlines × dotsPerScanline.
        assertEquals(Region.NTSC.totalScanlines.toLong() * dotsPerScanline, ntscDots)
        assertEquals(Region.PAL.totalScanlines.toLong() * dotsPerScanline, palDots)
        assertEquals((312 - 262).toLong() * dotsPerScanline, palDots - ntscDots)
    }

    @Test
    fun `manual override beats ROM auto-detection`() {
        val nestlin = Nestlin()
        // No ROM loaded → detection would give NTSC; force PAL via override.
        nestlin.config.regionOverride = Region.PAL
        nestlin.applyRegion()
        assertEquals(Region.PAL, nestlin.currentRegion())
        assertEquals(Region.PAL.refreshRateHz, nestlin.config.targetFps, 0.0001)

        // Clearing the override falls back to detection (NTSC default here).
        nestlin.config.regionOverride = null
        nestlin.applyRegion()
        assertEquals(Region.NTSC, nestlin.currentRegion())
    }

    /** Drive a region-configured PPU until one frame completes; return dots ticked. */
    private fun dotsPerFrame(region: Region): Long {
        val ppu = Ppu(Memory()).apply { this.region = region }
        var frameSeen = false
        ppu.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) { frameSeen = true }
        })
        val start = ppu.ticksElapsed
        while (!frameSeen) ppu.tick()
        return ppu.ticksElapsed - start
    }
}
