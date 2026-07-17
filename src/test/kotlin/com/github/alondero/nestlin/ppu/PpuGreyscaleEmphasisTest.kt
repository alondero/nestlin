package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.ui.FrameListener
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * PPUMASK bit 0 (greyscale) and bits 5-7 (colour emphasis) were decoded by [Mask]
 * but never applied to output pixels. Greyscale masks the palette index to the
 * grey column ($00/$10/$20/$30); each emphasis bit attenuates the OTHER two
 * colour channels (the standard emulator approximation of the PPU's analog
 * de-emphasis).
 *
 * Both tests drive the forced-blank backdrop path (rendering disabled) because it
 * is the simplest pixel-producing path and shares the same colour-resolution code
 * as the rendering path.
 */
class PpuGreyscaleEmphasisTest {

    private val backdropIndex = 0x21 // a saturated blue — distinct from its grey column ($20)

    private fun runOneFrame(ppu: Ppu): Frame {
        var captured: Frame? = null
        ppu.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) { captured = frame }
        })
        var guard = 400_000
        while (captured == null && guard-- > 0) ppu.tick()
        return captured ?: error("PPU did not complete a frame")
    }

    @Test
    fun `greyscale bit masks the palette index to the grey column`() {
        val memory = Memory()
        val ppu = Ppu(memory)
        memory.ppuAddressedMemory.ppuInternalMemory[0x3F00] = backdropIndex.toByte()
        memory.ppuAddressedMemory.mask.register = 0b0000_0001.toByte() // greyscale, rendering off

        val frame = runOneFrame(ppu)

        // $21 & $30 = $20 — the greyscale column entry for that row.
        assertThat(frame.scanlines[120][128], equalTo(NesPalette.getRgb(0x20)))
    }

    @Test
    fun `red emphasis attenuates green and blue channels`() {
        val memory = Memory()
        val ppu = Ppu(memory)
        memory.ppuAddressedMemory.ppuInternalMemory[0x3F00] = backdropIndex.toByte()
        memory.ppuAddressedMemory.mask.register = 0b0010_0000.toByte() // red emphasis, rendering off

        val frame = runOneFrame(ppu)
        val pixel = frame.scanlines[120][128]
        val base = NesPalette.getRgb(backdropIndex)

        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val baseR = (base shr 16) and 0xFF
        val baseG = (base shr 8) and 0xFF
        val baseB = base and 0xFF

        assertThat("red channel unchanged", r, equalTo(baseR))
        assertThat("green attenuated", g < baseG, equalTo(true))
        assertThat("blue attenuated", b < baseB, equalTo(true))
    }

    @Test
    fun `no greyscale or emphasis leaves the palette colour untouched`() {
        val memory = Memory()
        val ppu = Ppu(memory)
        memory.ppuAddressedMemory.ppuInternalMemory[0x3F00] = backdropIndex.toByte()
        memory.ppuAddressedMemory.mask.register = 0

        val frame = runOneFrame(ppu)

        assertThat(frame.scanlines[120][128], equalTo(NesPalette.getRgb(backdropIndex)))
    }
}
