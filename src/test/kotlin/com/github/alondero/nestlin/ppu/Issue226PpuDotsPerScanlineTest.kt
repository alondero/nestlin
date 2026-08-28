package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.Region
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/** Regression coverage for issues #226 and #291: observable PPU scheduler timing. */
class Issue226PpuDotsPerScanlineTest {

    private val ntscFrameClocks = 262L * 341L
    private val ntscOddFrameClocks = ntscFrameClocks - 1L
    private val palFrameClocks = 312L * 341L

    private fun newPpu(region: Region, mask: Int = 0): Ppu {
        val memory = Memory()
        memory.ppuAddressedMemory.mask.register = mask.toByte()
        return Ppu(memory).apply { this.region = region }
    }

    /**
     * Count calls at the scheduler seam, independently of [Ppu.ticksElapsed].
     * This catches boundary calls that update no diagnostic counter.
     */
    private fun frameCallCounts(ppu: Ppu, frameCount: Int): List<Long> {
        val counts = mutableListOf<Long>()
        var calls = 0L
        var previousBoundary = 0L
        ppu.addFrameCompletionListener {
            counts += calls - previousBoundary
            previousBoundary = calls
        }

        val ticksBefore = ppu.ticksElapsed
        var guard = 1_000_000
        while (counts.size < frameCount && guard-- > 0) {
            calls++
            ppu.tick()
        }

        check(counts.size == frameCount) { "PPU completed ${counts.size} of $frameCount frames" }
        assertThat("ticksElapsed must equal actual tick invocations", ppu.ticksElapsed - ticksBefore, equalTo(calls))
        return counts
    }

    @Test
    fun `rendering-disabled frames use exactly 341 scheduler calls per scanline`() {
        assertThat(frameCallCounts(newPpu(Region.NTSC), 2), equalTo(listOf(ntscFrameClocks, ntscFrameClocks)))
        assertThat(frameCallCounts(newPpu(Region.PAL), 2), equalTo(listOf(palFrameClocks, palFrameClocks)))
    }

    @Test
    fun `rendered NTSC frames alternate full and shortened scheduler lengths`() {
        val ppu = newPpu(Region.NTSC, mask = 0x18)

        assertThat(
            frameCallCounts(ppu, 4),
            equalTo(listOf(ntscFrameClocks, ntscOddFrameClocks, ntscFrameClocks, ntscOddFrameClocks)),
        )
    }

    @Test
    fun `rendered PAL frames are never shortened`() {
        val ppu = newPpu(Region.PAL, mask = 0x18)

        assertThat(frameCallCounts(ppu, 3), equalTo(List(3) { palFrameClocks }))
    }

    @Test
    fun `NTSC shortening applies when either rendering layer is enabled`() {
        for (mask in listOf(0x08, 0x10, 0x18)) {
            assertThat(
                "PPUMASK ${mask.toString(16)}",
                frameCallCounts(newPpu(Region.NTSC, mask), 2),
                equalTo(listOf(ntscFrameClocks, ntscOddFrameClocks)),
            )
        }
    }

    @Test
    fun `shortened NTSC frame skips final pre-render dot`() {
        val ppu = newPpu(Region.NTSC, mask = 0x18)
        frameCallCounts(ppu, 1)

        var guard = ntscFrameClocks.toInt()
        while (!(ppu.currentScanline == Region.NTSC.preRenderScanline && ppu.currentCycle == 338) && guard-- > 0) {
            ppu.tick()
        }
        check(guard > 0) { "PPU did not reach the odd frame's pre-render skip point" }

        ppu.tick()
        assertThat(ppu.currentScanline to ppu.currentCycle, equalTo(Region.NTSC.preRenderScanline to 339))

        ppu.tick()
        assertThat("dot 340 is skipped", ppu.currentScanline to ppu.currentCycle, equalTo(0 to 0))
    }
}
