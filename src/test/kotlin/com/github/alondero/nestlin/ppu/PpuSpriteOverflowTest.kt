package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * PPUSTATUS bit 5 (sprite overflow) must be set during sprite evaluation when a
 * 9th sprite falls on a scanline, and cleared at the pre-render line. Nestlin
 * never set it — games that use it for raster timing saw a permanently-clear flag.
 *
 * (The hardware's buggy diagonal-OAM-scan false positives/negatives are NOT
 * modelled — only the documented "more than 8 real sprites" case.)
 */
class PpuSpriteOverflowTest {

    private fun tickToScanline(ppu: Ppu, target: Int) {
        var guard = 400_000
        while (ppu.currentScanline < target && guard-- > 0) ppu.tick()
    }

    @Test
    fun `ninth sprite on a scanline sets the overflow flag`() {
        val memory = Memory()
        val ppu = Ppu(memory)
        // Zero-init OAM = 64 sprites at y=0 → scanlines 1-8 each have 64 candidates.
        memory.ppuAddressedMemory.mask.register = 0b0000_1000.toByte() // rendering on (bg)

        tickToScanline(ppu, 100)

        assertThat(memory.ppuAddressedMemory.status.spriteOverflow(), equalTo(true))
    }

    @Test
    fun `eight or fewer sprites never set the overflow flag`() {
        val memory = Memory()
        val ppu = Ppu(memory)
        val oam = memory.ppuAddressedMemory.objectAttributeMemory
        // Move sprites 8..63 off-screen (y=0xF0 → never matches a visible scanline),
        // leaving exactly 8 sprites (0..7) on scanlines 1-8.
        for (i in 8 until 64) oam[i * 4] = 0xF0.toByte()
        memory.ppuAddressedMemory.mask.register = 0b0000_1000.toByte()

        tickToScanline(ppu, 100)

        assertThat(memory.ppuAddressedMemory.status.spriteOverflow(), equalTo(false))
    }

    @Test
    fun `overflow flag is cleared at the pre-render line`() {
        val memory = Memory()
        val ppu = Ppu(memory)
        memory.ppuAddressedMemory.mask.register = 0b0000_1000.toByte()

        tickToScanline(ppu, 100)
        assertThat(memory.ppuAddressedMemory.status.spriteOverflow(), equalTo(true))

        // Run through the pre-render line to the START of the next frame's scanline 0.
        // The flag must have been cleared at pre-render; stop before scanline 0's
        // cycle-64 sprite eval (which prepares crowded scanline 1) re-sets it.
        var guard = 400_000
        do {
            ppu.tick()
            if (guard-- <= 0) error("never reached start of next frame")
        } while (!(ppu.currentScanline == 0 && ppu.currentCycle in 1..40))
        assertThat(memory.ppuAddressedMemory.status.spriteOverflow(), equalTo(false))
    }
}
