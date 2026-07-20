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
 * The hardware's cycle-by-cycle diagonal-OAM scan can report false positives and
 * negatives. That quirk is intentionally out of scope here because it requires a
 * per-dot evaluation state machine; these tests pin the documented >8-sprite case.
 */
class PpuSpriteOverflowTest {

    private fun tickToScanline(ppu: Ppu, target: Int) {
        var guard = 400_000
        while (ppu.currentScanline < target && guard-- > 0) ppu.tick()
    }

    private fun tickUntilOverflow(ppu: Ppu, memory: Memory) {
        var guard = 400_000
        while (!memory.ppuAddressedMemory.status.spriteOverflow() && guard-- > 0) ppu.tick()
        if (!memory.ppuAddressedMemory.status.spriteOverflow()) error("sprite overflow flag was never set")
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
    fun `8x16 sprites use their full height for overflow evaluation`() {
        val memory = Memory()
        val ppu = Ppu(memory)
        val oam = memory.ppuAddressedMemory.objectAttributeMemory
        for (i in 9 until 64) oam[i * 4] = 0xF0.toByte()
        memory.ppuAddressedMemory.controller.register = 0b0010_0000.toByte()

        // Keep rendering off through the rows shared by 8x8 and 8x16 sprites.
        // Evaluation on scanline 8 prepares target scanline 9, which is only in
        // range when PPUCTRL selects 8x16 sprites.
        tickToScanline(ppu, 8)
        memory.ppuAddressedMemory.mask.register = 0b0000_1000.toByte()
        while (ppu.currentScanline == 8 && ppu.currentCycle <= 64) ppu.tick()

        assertThat(memory.ppuAddressedMemory.status.spriteOverflow(), equalTo(true))
    }

    @Test
    fun `reading PPUSTATUS returns and preserves the overflow flag`() {
        val memory = Memory()
        val ppu = Ppu(memory)
        val oam = memory.ppuAddressedMemory.objectAttributeMemory
        for (i in 9 until 64) oam[i * 4] = 0xF0.toByte()
        memory.ppuAddressedMemory.mask.register = 0b0000_1000.toByte()

        tickUntilOverflow(ppu, memory)
        val statusRead = memory[0x2002].toInt() and 0xFF

        assertThat(statusRead and 0x20, equalTo(0x20))
        assertThat(memory.ppuAddressedMemory.status.spriteOverflow(), equalTo(true))
    }

    @Test
    fun `overflow remains latched when later scanlines have no ninth sprite`() {
        val memory = Memory()
        val ppu = Ppu(memory)
        val oam = memory.ppuAddressedMemory.objectAttributeMemory
        for (i in 9 until 64) oam[i * 4] = 0xF0.toByte()
        memory.ppuAddressedMemory.mask.register = 0b0000_1000.toByte()

        tickUntilOverflow(ppu, memory)
        for (i in 0 until 9) oam[i * 4] = 0xF0.toByte()
        tickToScanline(ppu, 100)

        assertThat(memory.ppuAddressedMemory.status.spriteOverflow(), equalTo(true))
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
