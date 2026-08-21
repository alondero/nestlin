package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.ui.FrameListener
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Regression tests for GitHub issue #226 — PPU dot-accounting accuracy.
 *
 * Two related bugs were folded into a single fix because they're both about the
 * PPU's per-frame dot count:
 *
 *  1. **Off-by-one scanline tick.** `Ppu.tick` historically spent 342 ticks per
 *     scanline (341 visible dots + one extra "boundary tick" that only called
 *     `endLine()`). The boundary tick shouldn't be a dot — it has no address
 *     to process, no render-side effect, and it shifts the wall-clock per-frame
 *     by ~0.3%. The fix makes `ticksElapsed` count only real dots (cycles 0..340).
 *
 *  2. **No odd-frame cycle skip.** Real hardware shortens the odd frame by one
 *     dot when rendering is enabled: the post-pre-render idle dot is absorbed.
 *     NTSC therefore alternates 89342/89341 dots per frame, not 89342/89342.
 *
 * Both bugs were "internally consistent" (games boot, mid-scanline splits land
 * because cycles 0..340 are processed correctly), which is why they survived
 * the GoldenLog CPU cycle comparison. The regression bar for THIS fix is
 * per-frame dot count, not per-instruction timing.
 */
class Issue226PpuDotsPerScanlineTest {

    /** Standard NTSC frame length when no odd-frame skip happens (rendering disabled). */
    private val ntscEvenFrameDots = 262L * 341L          // 89342

    /** NTSC odd frame with rendering on — one dot shorter. */
    private val ntscOddFrameDots = ntscEvenFrameDots - 1 // 89341

    /** Standard PAL frame length when no odd-frame skip happens. */
    private val palEvenFrameDots = 312L * 341L           // 106392

    /** PAL odd frame with rendering on — one dot shorter. */
    private val palOddFrameDots = palEvenFrameDots - 1   // 106391

    private fun newPpu(region: Region = Region.NTSC): Ppu =
        Ppu(Memory()).apply { this.region = region }

    /** Count complete frames until [target] frames have ended, returning total ticksElapsed. */
    private fun runFrames(ppu: Ppu, target: Int): Long {
        var completed = 0
        ppu.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) { completed++ }
        })
        val start = ppu.ticksElapsed
        var guard = 1_000_000
        while (completed < target && guard-- > 0) ppu.tick()
        check(completed == target) { "PPU did not reach $target frames (completed=$completed)" }
        return ppu.ticksElapsed - start
    }

    // ---- 1. 341 dots per scanline (the off-by-one bug) ---------------------------

    @Test
    fun `NTSC frame with rendering disabled is exactly 89342 dots`() {
        // Rendering off → no odd-frame skip → every frame has the same length.
        // The off-by-one bug would report 262 × 342 = 89604 dots instead.
        val ppu = newPpu(Region.NTSC)
        ppu.memory.ppuAddressedMemory.mask.register = 0
        assertThat(runFrames(ppu, 1), equalTo(ntscEvenFrameDots))
    }

    @Test
    fun `NTSC even frame with rendering enabled is 89342 dots`() {
        // Frame 0 is even → no odd-frame skip. With the bug, this would report 89604.
        val ppu = newPpu(Region.NTSC)
        ppu.memory.ppuAddressedMemory.mask.register = 0b0001_1000.toByte()
        assertThat(runFrames(ppu, 1), equalTo(ntscEvenFrameDots))
    }

    @Test
    fun `PAL frame with rendering disabled is exactly 106392 dots`() {
        val ppu = newPpu(Region.PAL)
        ppu.memory.ppuAddressedMemory.mask.register = 0
        assertThat(runFrames(ppu, 1), equalTo(palEvenFrameDots))
    }

    // ---- 2. Odd-frame cycle skip ------------------------------------------------

    @Test
    fun `NTSC odd frame with rendering enabled is 89341 dots one dot shorter`() {
        // Frame 1 (the second frame ever run) is odd; with rendering on it skips
        // one dot at the post-pre-render transition. Without the odd-frame skip
        // logic, this would also report 89342.
        val ppu = newPpu(Region.NTSC)
        ppu.memory.ppuAddressedMemory.mask.register = 0b0001_1000.toByte()

        // Advance through frame 0 (89342 dots) and into frame 1.
        assertThat(runFrames(ppu, 1), equalTo(ntscEvenFrameDots))
        val startOfFrame1 = ppu.ticksElapsed
        runFrames(ppu, 2)  // complete frame 1 and frame 2
        val frame1Ticks = ppu.ticksElapsed - startOfFrame1 - ntscEvenFrameDots  // subtract frame 2's 89342
        assertThat("odd frame is one dot shorter", frame1Ticks, equalTo(ntscOddFrameDots))
    }

    @Test
    fun `NTSC odd frame with rendering disabled is NOT shortened`() {
        // The skip is gated on PPUMASK bit 3/4 (background OR sprites visible).
        // With rendering off, the PPU never enters the rendering pipeline, so the
        // odd frame stays at 89342 dots like every other frame.
        val ppu = newPpu(Region.NTSC)
        ppu.memory.ppuAddressedMemory.mask.register = 0

        assertThat(runFrames(ppu, 1), equalTo(ntscEvenFrameDots))
        val startOfFrame1 = ppu.ticksElapsed
        runFrames(ppu, 2)
        val frame1Ticks = ppu.ticksElapsed - startOfFrame1 - ntscEvenFrameDots
        assertThat("odd frame WITHOUT rendering is NOT shortened", frame1Ticks, equalTo(ntscEvenFrameDots))
    }

    @Test
    fun `two frames with rendering enabled total 178683 dots`() {
        // Cumulative check: 89342 (frame 0) + 89341 (frame 1) = 178683.
        // The off-by-one bug would report 179208 (262×342 × 2) instead.
        val ppu = newPpu(Region.NTSC)
        ppu.memory.ppuAddressedMemory.mask.register = 0b0001_1000.toByte()
        assertThat(runFrames(ppu, 2), equalTo(89342L + 89341L))
    }

    @Test
    fun `PAL odd frame with rendering enabled is 106391 dots`() {
        // PAL has the same odd-frame skip mechanic; verify the per-frame length
        // and that the skip is independent of the 3.2× CPU ratio.
        val ppu = newPpu(Region.PAL)
        ppu.memory.ppuAddressedMemory.mask.register = 0b0001_1000.toByte()

        assertThat(runFrames(ppu, 1), equalTo(palEvenFrameDots))
        val startOfFrame1 = ppu.ticksElapsed
        runFrames(ppu, 2)
        val frame1Ticks = ppu.ticksElapsed - startOfFrame1 - palEvenFrameDots
        assertThat("PAL odd frame is one dot shorter", frame1Ticks, equalTo(palOddFrameDots))
    }

    @Test
    fun `odd frame skip uses background OR sprites visible bit`() {
        // The skip fires if EITHER background (mask bit 3) or sprites (mask bit 4)
        // is enabled. Test both single-bit paths and the no-rendering control.
        val cases = listOf(
            "bg only"      to 0b0000_1000.toByte(),
            "sprites only" to 0b0001_0000.toByte(),
            "bg+sprites"   to 0b0001_1000.toByte(),
        )
        for ((label, mask) in cases) {
            val ppu = newPpu(Region.NTSC).apply { memory.ppuAddressedMemory.mask.register = mask }
            runFrames(ppu, 1)  // frame 0 = 89342
            val startOfFrame1 = ppu.ticksElapsed
            runFrames(ppu, 2)  // frame 1 + frame 2
            val frame1Ticks = ppu.ticksElapsed - startOfFrame1 - ntscEvenFrameDots
            assertThat("odd frame with $label rendering", frame1Ticks, equalTo(ntscOddFrameDots))
        }
    }
}