package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.Region
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Frame-counter (frame sequencer) regressions.
 *
 * Two hardware-accuracy bugs are pinned here:
 *
 *  1. A `$4017` write restarts the sequencer's *own* cycle reference. Before the
 *     fix, `tick()` compared against an absolute CPU-cycle counter that was never
 *     reset on the write, so a mid-frame `$4017` write (the standard per-frame
 *     re-sync idiom) made the next tick fire every already-passed step at once —
 *     three quarter-frame and two half-frame clocks back-to-back.
 *
 *  2. In 5-step mode the 4th step (index 3, cycle 29829 on NTSC) is an *empty*
 *     slot; only step 4 (37281) clocks quarter+half. The old code reused the
 *     4-step branch and wrongly clocked quarter+half at step 3.
 */
class FrameCounterTest {

    private fun advanceTo(fc: FrameCounter, cycles: Int): FrameCounter.Result {
        var last = FrameCounter.Result(false, false, false)
        repeat(cycles) { last = fc.tick() }
        return last
    }

    // ---- Bug 1: $4017 write restarts the sequencer reference clock ----

    @Test
    fun `quarter frame fires 7457 cycles after reset, not at an absolute boundary`() {
        val fc = FrameCounter().apply { region = Region.NTSC; mode = FrameCounter.Mode.FOUR_STEP }
        // 7456 ticks: no quarter yet.
        repeat(7456) { assertFalse(fc.tick().quarterFrame, "premature quarter clock") }
        // 7457th tick: the step-0 quarter clock.
        assertTrue(fc.tick().quarterFrame, "quarter clock at cycle 7457")
    }

    @Test
    fun `a $4017 write mid-frame restarts the sequence and does NOT burst-fire`() {
        val fc = FrameCounter().apply { region = Region.NTSC; mode = FrameCounter.Mode.FOUR_STEP }
        // Run well past three step boundaries (past 22371).
        advanceTo(fc, 25000)
        // Re-sync the frame counter, exactly as a game's NMI handler does every frame.
        fc.write4017(0x00.toByte())  // 4-step, IRQ enabled

        // The very next tick must NOT dump all the already-passed steps at once.
        val next = fc.tick()
        assertFalse(next.quarterFrame, "spurious quarter clock immediately after \$4017 write")
        assertFalse(next.halfFrame, "spurious half clock immediately after \$4017 write")

        // The first real quarter clock is 7457 cycles *after the write* (we already
        // spent 1 tick above, so 7456 more).
        repeat(7455) { assertFalse(fc.tick().quarterFrame, "premature quarter after re-sync") }
        assertTrue(fc.tick().quarterFrame, "quarter clock 7457 cycles after \$4017 write")
    }

    // ---- Bug 2: 5-step mode's empty 4th step ----

    @Test
    fun `four-step mode clocks quarter and half at the final step with IRQ`() {
        val fc = FrameCounter().apply { region = Region.NTSC; mode = FrameCounter.Mode.FOUR_STEP }
        val r = advanceTo(fc, 29829)  // step 3 boundary
        assertTrue(r.quarterFrame, "4-step step-3 quarter")
        assertTrue(r.halfFrame, "4-step step-3 half")
        assertTrue(r.irq, "4-step step-3 frame IRQ")
    }

    @Test
    fun `five-step mode does not clock anything at the empty fourth step`() {
        val fc = FrameCounter().apply { region = Region.NTSC; mode = FrameCounter.Mode.FIVE_STEP }
        val r = advanceTo(fc, 29829)  // the empty slot in 5-step mode
        assertFalse(r.quarterFrame, "5-step step-3 must be silent")
        assertFalse(r.halfFrame, "5-step step-3 must be silent")
        assertFalse(r.irq, "5-step never raises the frame IRQ")
    }

    @Test
    fun `five-step mode clocks quarter and half at the fifth step`() {
        val fc = FrameCounter().apply { region = Region.NTSC; mode = FrameCounter.Mode.FIVE_STEP }
        val r = advanceTo(fc, 37281)  // step 4 boundary
        assertTrue(r.quarterFrame, "5-step step-4 quarter")
        assertTrue(r.halfFrame, "5-step step-4 half")
        assertFalse(r.irq, "5-step never raises the frame IRQ")
    }

    @Test
    fun `five-step mode clocks the linear counter four times per sequence, not five`() {
        val fc = FrameCounter().apply { region = Region.NTSC; mode = FrameCounter.Mode.FIVE_STEP }
        var quarters = 0
        // One full 5-step sequence (wraps at 37282).
        repeat(37282) { if (fc.tick().quarterFrame) quarters++ }
        assertEquals(4, quarters, "5-step mode must produce exactly 4 quarter clocks per sequence")
    }
}
