package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toSignedByte
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * PPUMASK per-layer enable bits (bit 3 = background, bit 4 = sprites) must be
 * honored independently by the pixel pipeline.
 *
 * The decisive, easily-observed consequence is sprite-0 hit: the hit requires
 * BOTH background and sprite rendering to be enabled (nesdev PPU: Sprite 0 hits
 * "will not be triggered ... if background or sprite rendering is disabled").
 *
 * Before the fix, the whole pipeline was gated on `showBackground || showSprites`,
 * so with only one layer enabled the other still rendered and sprite 0 could hit
 * spuriously.
 */
class PpuLayerEnableTest {

    /**
     * Builds a PPU whose background tile 1 is fully opaque (pixel value 1) across
     * the whole nametable, with sprite 0 (also opaque tile 1) placed at (100,100)
     * so it overlaps opaque background — the classic sprite-0-hit condition.
     */
    private fun setupOverlap(): Pair<Memory, Ppu> {
        val memory = Memory()
        val ppu = Ppu(memory)

        // Pattern table 0, tile 1 = solid pixel-value-1 (low plane 0xFF, high 0x00).
        val chr = ByteArray(0x2000)
        for (row in 0 until 8) chr[0x10 + row] = 0xFF.toSignedByte()
        memory.ppuAddressedMemory.ppuInternalMemory.loadChrRom(chr)

        // Fill nametable 0 with tile index 1 so every background pixel is opaque.
        for (i in 0 until 0x3C0) memory.ppuAddressedMemory.ppuInternalMemory[0x2000 + i] = 1.toSignedByte()

        // Palette entries (colours are irrelevant to the hit, which keys off pixel value).
        memory.ppuAddressedMemory.ppuInternalMemory[0x3F01] = 0x30.toSignedByte()
        memory.ppuAddressedMemory.ppuInternalMemory[0x3F11] = 0x16.toSignedByte()

        // Sprite 0: Y=99 renders on scanline 100, tile 1, attr 0, X=100.
        with(memory.ppuAddressedMemory.objectAttributeMemory) {
            this[0] = 99.toSignedByte()
            this[1] = 1.toSignedByte()
            this[2] = 0.toSignedByte()
            this[3] = 100.toSignedByte()
        }
        return memory to ppu
    }

    /** Runs the PPU across the sprite's scanline and reports whether sprite-0 hit ever set. */
    private fun sprite0Hits(maskRegister: Int): Boolean {
        val (memory, ppu) = setupOverlap()
        memory.ppuAddressedMemory.mask.register = maskRegister.toSignedByte()
        var guard = 300_000
        while (guard-- > 0 && ppu.currentScanline < 120) {
            ppu.tick()
            if (memory.ppuAddressedMemory.status.register.isBitSet(6)) return true
        }
        return false
    }

    private val BACKGROUND = 0b0000_1000
    private val SPRITES = 0b0001_0000

    @Test
    fun `sprite-0 hit fires when both background and sprite rendering are enabled`() {
        assertTrue(sprite0Hits(BACKGROUND or SPRITES), "control: hit must occur with both layers on")
    }

    @Test
    fun `sprite-0 hit does NOT fire when sprite rendering is disabled`() {
        assertFalse(sprite0Hits(BACKGROUND), "no sprite-0 hit with sprites disabled (bit 4 clear)")
    }

    @Test
    fun `sprite-0 hit does NOT fire when background rendering is disabled`() {
        assertFalse(sprite0Hits(SPRITES), "no sprite-0 hit with background disabled (bit 3 clear)")
    }
}
