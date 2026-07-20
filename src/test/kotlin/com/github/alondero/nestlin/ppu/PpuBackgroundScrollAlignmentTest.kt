package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.ui.FrameListener
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Guard against the scroll-0 background being shifted by whole tiles.
 *
 * Regression for the issue-#227 loopy fix: moving the horizontal v←t copy from
 * cycle 0 to NES dot 257 (Nestlin cycle 256) left [Ppu.preloadFirstTwoTiles]
 * starting from a coarse-X that the end-of-line 320–335 fetches had already
 * advanced. The first visible columns then came from tile N+1 or N+2 instead
 * of tile N — classic "everything is shifted left by 8/16 pixels" (SMB3 status
 * bar reading "ORLD 1" instead of "WORLD 1").
 */
class PpuBackgroundScrollAlignmentTest {

    private val backdropIndex = 0x0F // black
    private val litIndex = 0x16      // red — any non-backdrop palette entry

    private fun runOneFrame(ppu: Ppu): Frame {
        var captured: Frame? = null
        ppu.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                captured = frame
            }
        })
        var guard = 400_000
        while (captured == null && guard-- > 0) ppu.tick()
        return captured ?: error("PPU did not complete a frame")
    }

    /**
     * Column pattern at scroll 0:
     *   col 0 (px 0-7)   = lit
     *   col 1 (px 8-15)  = dark
     *   col 2+ (px 16+)  = lit
     *
     * A 1-tile left shift shows dark at px 0; a 2-tile left shift shows lit at
     * px 0 and lit at px 8 (dark band pushed off the left edge).
     */
    private fun setUpColumnPattern(): Ppu {
        val memory = Memory()
        val ppu = Ppu(memory)
        val mem = memory.ppuAddressedMemory.ppuInternalMemory

        val chr = ByteArray(0x2000)
        // Tile 1: solid low-plane (pixel value 1). Tile 2 empty. Tile 3 solid.
        for (row in 0..7) chr[1 * 16 + row] = 0xFF.toByte()
        for (row in 0..7) chr[3 * 16 + row] = 0xFF.toByte()
        mem.loadChrRom(chr)

        for (row in 0 until 30) {
            val base = 0x2000 + row * 32
            mem[base + 0] = 1
            mem[base + 1] = 2
            for (col in 2 until 32) mem[base + col] = 3
        }

        mem[0x3F00] = backdropIndex.toByte()
        mem[0x3F01] = litIndex.toByte() // bg palette 0, pixel value 1
        // Show background + leftmost 8px. Scroll left at power-on 0.
        memory.ppuAddressedMemory.mask.register = 0b0000_1010.toByte()
        return ppu
    }

    @Test
    fun `scroll 0 leftmost tile columns are not shifted`() {
        val ppu = setUpColumnPattern()
        val frame = runOneFrame(ppu)

        val lit = NesPalette.getRgb(litIndex)
        val dark = NesPalette.getRgb(backdropIndex)
        // y=0 is a trap: construction starts at scanline 0 before any end-of-line
        // 320–335 advance, so the first line can look correct while every later
        // line is shifted. Sample mid-frame.
        val y = 100

        assertThat("px 0 (col0) must be lit at scroll 0", frame.scanlines[y][0], equalTo(lit))
        assertThat("px 4 (col0) must be lit at scroll 0", frame.scanlines[y][4], equalTo(lit))
        assertThat("px 8 (col1) must be dark at scroll 0", frame.scanlines[y][8], equalTo(dark))
        assertThat("px 12 (col1) must be dark at scroll 0", frame.scanlines[y][12], equalTo(dark))
        assertThat("px 16 (col2) must be lit at scroll 0", frame.scanlines[y][16], equalTo(lit))
        assertThat("px 20 (col2) must be lit at scroll 0", frame.scanlines[y][20], equalTo(lit))
    }

    @Test
    fun `scroll 0 rightmost columns still render from the first nametable`() {
        // Columns 30-31 of NT0 lit, everything else dark. A left shift of the
        // content (the issue-#227 regression) advances coarseX past NT0 col 31
        // into NT1 — the next nametable — so the right edge reads NT1's empty
        // tiles instead of NT0's lit ones.
        //
        // Vertical mirroring is required so $2400 maps to NT1's own backing
        // array: with the default horizontal mirroring, $2000 and $2400 are
        // both NT0, so the NT1 wipe below would clobber the NT0 setup.
        val memory = Memory()
        val ppu = Ppu(memory)
        val mem = memory.ppuAddressedMemory.ppuInternalMemory
        mem.mirroring = PpuInternalMemory.Mirroring.VERTICAL

        val chr = ByteArray(0x2000)
        for (row in 0..7) chr[1 * 16 + row] = 0xFF.toByte()
        mem.loadChrRom(chr)

        for (row in 0 until 30) {
            val base = 0x2000 + row * 32
            for (col in 0 until 32) mem[base + col] = 0
            mem[base + 30] = 1
            mem[base + 31] = 1
        }
        // NT1 is empty by construction (ByteArray defaults to 0), but make it
        // explicit so the test no longer silently depends on $2400's mapping.
        for (addr in 0x2400 until 0x2400 + 960) mem[addr] = 0

        mem[0x3F00] = backdropIndex.toByte()
        mem[0x3F01] = litIndex.toByte()
        memory.ppuAddressedMemory.mask.register = 0b0000_1010.toByte()

        val frame = runOneFrame(ppu)
        val lit = NesPalette.getRgb(litIndex)
        val dark = NesPalette.getRgb(backdropIndex)
        val y = 100
        val row = frame.scanlines[y]
        val litXs = (0..255).filter { row[it] == lit }
        val diag = "litXs=${litXs.take(5)}..${litXs.takeLast(5)} count=${litXs.size}"

        assertThat("px 239 (col29) dark ($diag)", row[239], equalTo(dark))
        assertThat("px 240 (col30) lit ($diag)", row[240], equalTo(lit))
        assertThat("px 248 (col31) lit ($diag)", row[248], equalTo(lit))
        assertThat("px 255 (col31) lit ($diag)", row[255], equalTo(lit))
    }
}
