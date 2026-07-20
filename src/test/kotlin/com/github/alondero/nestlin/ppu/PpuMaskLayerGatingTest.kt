package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.ui.FrameListener
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * PPUMASK bits 3 (show background) and 4 (show sprites) gate their layers
 * INDIVIDUALLY on hardware. Nestlin treated "either bit set" as "render both
 * layers": a game running sprites-only (bit 4) still had its background tiles
 * scanned out, and a game running background-only (bit 3) still had sprites
 * composited on top.
 */
class PpuMaskLayerGatingTest {

    private val backdropIndex = 0x21   // blue
    private val bgColorIndex = 0x16    // red
    private val spriteColorIndex = 0x2A // green

    /** 8KB CHR: tile 0 solid pixel-value 3, tile 1 solid pixel-value 3. */
    private fun solidChr(): ByteArray {
        val chr = ByteArray(0x2000)
        for (tile in 0..1) {
            for (row in 0..7) {
                chr[tile * 16 + row] = 0xFF.toByte()      // low plane
                chr[tile * 16 + 8 + row] = 0xFF.toByte()  // high plane
            }
        }
        return chr
    }

    private fun setUpPpu(): Pair<Memory, Ppu> {
        val memory = Memory()
        val ppu = Ppu(memory)
        val mem = memory.ppuAddressedMemory.ppuInternalMemory
        mem.loadChrRom(solidChr())
        // Fill the first nametable with tile 0 (attributes stay 0 → bg palette 0).
        for (i in 0 until 960) mem[0x2000 + i] = 0
        mem[0x3F00] = backdropIndex.toByte()
        mem[0x3F03] = bgColorIndex.toByte()      // bg palette 0, pixel value 3
        mem[0x3F13] = spriteColorIndex.toByte()  // sprite palette 0, pixel value 3
        // OAM is zeroed at construction: 64 sprites at y=0, tile 0, x=0 —
        // they cover scanlines 1-8 at columns ~1-8.
        return memory to ppu
    }

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
    fun `sprites-only mode does not render background tiles`() {
        val (memory, ppu) = setUpPpu()
        // Show sprites + sprites-in-leftmost-8px; background OFF.
        memory.ppuAddressedMemory.mask.register = 0b0001_0100.toByte()

        val frame = runOneFrame(ppu)

        // Mid-screen, far from any sprite: must be the backdrop, not the bg tile colour.
        assertThat("pixel (128,120)", frame.scanlines[120][128], equalTo(NesPalette.getRgb(backdropIndex)))
    }

    @Test
    fun `background renders the rightmost pixel column`() {
        val (memory, ppu) = setUpPpu()
        memory.ppuAddressedMemory.mask.register = 0b0000_1010.toByte()

        val frame = runOneFrame(ppu)

        val expected = NesPalette.getRgb(bgColorIndex)
        for (y in intArrayOf(0, 60, 120, 200, 239)) {
            assertThat("pixel (255,$y)", frame.scanlines[y][255], equalTo(expected))
        }
    }

    @Test
    fun `background-only mode does not render sprites`() {
        val (memory, ppu) = setUpPpu()
        // Put sprite 0 mid-screen (outside the left-8px clip zone): y=49 → visible on
        // scanlines 50-57, x=100, solid tile 0, priority in-front.
        val oam = memory.ppuAddressedMemory.objectAttributeMemory
        oam[0] = 49.toByte()  // y
        oam[1] = 0            // tile
        oam[2] = 0            // attributes: palette 0, in front
        oam[3] = 100.toByte() // x
        // Show background + bg-in-leftmost-8px; sprites OFF.
        memory.ppuAddressedMemory.mask.register = 0b0000_1010.toByte()

        val frame = runOneFrame(ppu)

        // (103,53) is inside the sprite's 8x8 box. With sprites disabled the
        // background tile colour must win.
        assertThat("pixel (103,53)", frame.scanlines[53][103], equalTo(NesPalette.getRgb(bgColorIndex)))
    }
}
