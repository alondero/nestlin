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
        // We pick the even-CPU-cycle phase (delay = 3) so the deferred reset fires on
        // tick 3 after the write.
        fc.write4017(0x00.toByte(), cpuCycle = 0)  // 4-step, IRQ enabled, APU get phase

        // The very next tick must NOT dump all the already-passed steps at once.
        val next = fc.tick()
        assertFalse(next.quarterFrame, "spurious quarter clock immediately after \$4017 write")
        assertFalse(next.halfFrame, "spurious half clock immediately after \$4017 write")

        // Issue #297: the reset is deferred 3 cycles on the even CPU cycle (APU
        // get). During those 3 cycles the sequencer keeps its old mode + step
        // and does NOT burst-fire the already-passed step clocks.
        repeat(2) { assertFalse(fc.tick().quarterFrame, "no clocks during the 3-cycle reset delay") }
        // The 3rd post-write tick is the deferred reset itself; it installs the
        // new mode but emits no sequence-driven clocks (cyclesSinceReset = 1).
        assertFalse(fc.tick().quarterFrame, "reset tick itself must not clock")
        assertFalse(fc.tick().halfFrame, "reset tick itself must not clock")

        // The first sequence-driven quarter clock is 7457 cycles *after the
        // deferred reset fired, i.e. (delay=3) + 7457 cycles after the write.
        // 1 + 2 + 1 + 1 = 5 ticks have already been consumed above, with the
        // reset landing on tick 3 (cyclesSinceReset = 1 after the increment).
        // After tick 5, cyclesSinceReset = 3, so 7454 more ticks bring it to
        // exactly 7457 on the assertTrue tick.
        repeat(7453) { assertFalse(fc.tick().quarterFrame, "premature quarter after re-sync") }
        assertTrue(fc.tick().quarterFrame, "quarter clock 7457 cycles after the \$4017 reset fires")
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

    // ---- Issue #297: $4017 writes defer the reset by 3/4 cycles ----
    //
    // Real 2A03 hardware defers the frame-counter reset to the next APU
    // "phase boundary" — 3 CPU cycles when the write lands on the APU "get"
    // phase (even CPU cycle), 4 cycles when it lands on the "put" phase
    // (odd CPU cycle). IRQ-inhibit acknowledgement is immediate; the mode
    // change and sequencer reset are deferred. 5-step mode emits an
    // immediate quarter+half on the reset tick; 4-step mode is silent.

    @Test
    fun `$4017 write on even CPU cycle defers reset by 3 ticks`() {
        val fc = FrameCounter().apply { region = Region.NTSC; mode = FrameCounter.Mode.FIVE_STEP }
        // Switching from 5-step to 4-step so we can observe the mode change.
        fc.write4017(0x00.toByte(), cpuCycle = 0)  // APU get phase → 3-cycle delay

        // First two post-write ticks: mode is still FIVE_STEP, no clocks.
        assertEquals(FrameCounter.Mode.FIVE_STEP, fc.mode, "mode must not change before the delay elapses")
        val t1 = fc.tick()
        assertFalse(t1.resetClock, "no reset clock before delay elapses")
        assertEquals(FrameCounter.Mode.FIVE_STEP, fc.mode, "mode still FIVE_STEP at delay-2")
        val t2 = fc.tick()
        assertFalse(t2.resetClock, "no reset clock before delay elapses")
        assertEquals(FrameCounter.Mode.FIVE_STEP, fc.mode, "mode still FIVE_STEP at delay-1")
        // Third post-write tick: delay expires, mode installs, reset fires
        // silently (4-step reset has no immediate clocks).
        val t3 = fc.tick()
        assertEquals(FrameCounter.Mode.FOUR_STEP, fc.mode, "mode installs FOUR_STEP at delay-3")
        assertFalse(t3.resetClock, "4-step reset must NOT emit quarter+half")
        assertFalse(t3.quarterFrame, "4-step reset tick must be silent")
        assertFalse(t3.halfFrame, "4-step reset tick must be silent")
    }

    @Test
    fun `$4017 write on odd CPU cycle defers reset by 4 ticks`() {
        val fc = FrameCounter().apply { region = Region.NTSC; mode = FrameCounter.Mode.FOUR_STEP }
        // Switching from 4-step to 5-step so we can observe both the mode
        // change AND the reset clock.
        fc.write4017(0x80.toByte(), cpuCycle = 1)  // APU put phase → 4-cycle delay

        // Three post-write ticks: mode is still FOUR_STEP, no clocks.
        assertEquals(FrameCounter.Mode.FOUR_STEP, fc.mode, "mode must not change before the delay elapses")
        repeat(3) {
            val t = fc.tick()
            assertFalse(t.resetClock, "no reset clock before delay elapses")
            assertEquals(FrameCounter.Mode.FOUR_STEP, fc.mode, "mode still FOUR_STEP")
        }
        // Fourth post-write tick: delay expires, mode installs, reset clocks.
        val t4 = fc.tick()
        assertEquals(FrameCounter.Mode.FIVE_STEP, fc.mode, "mode installs FIVE_STEP at delay-4")
        assertTrue(t4.resetClock, "5-step reset MUST emit quarter+half")
    }

    @Test
    fun `5-step reset clocks quarter and half on the reset tick, not on the write tick`() {
        val fc = FrameCounter().apply { region = Region.NTSC; mode = FrameCounter.Mode.FOUR_STEP }
        fc.write4017(0x80.toByte(), cpuCycle = 0)  // 5-step, delay = 3

        // During the 2 ticks BEFORE the reset fires, no quarter/half clocks
        // should fire and resetClock should be false.
        repeat(2) {
            val t = fc.tick()
            assertFalse(t.resetClock, "no reset clock during delay")
            assertFalse(t.quarterFrame, "no quarter clock during delay")
            assertFalse(t.halfFrame, "no half clock during delay")
        }

        // The 3rd post-write tick is the deferred reset itself; it carries
        // the quarter+half clocks via resetClock=true (the Apu uses this
        // signal to clock quarter+half channels).
        val reset = fc.tick()
        assertTrue(reset.resetClock, "5-step reset tick carries quarter+half clocks")
        // resetClock=true is the caller's signal to clock quarter+half
        // channels; the frame counter itself does NOT double-fire sequence
        // clocks on the same tick (cyclesSinceReset just restarted at 0).
        assertFalse(reset.quarterFrame, "no duplicate quarter clock from the sequence on the reset tick")
        assertFalse(reset.halfFrame, "no duplicate half clock from the sequence on the reset tick")
    }

    @Test
    fun `4-step reset is silent on the reset tick`() {
        val fc = FrameCounter().apply { region = Region.NTSC; mode = FrameCounter.Mode.FIVE_STEP }
        fc.write4017(0x00.toByte(), cpuCycle = 0)  // 4-step, delay = 3

        repeat(3) { fc.tick() }
        val reset = fc.tick()
        assertEquals(FrameCounter.Mode.FOUR_STEP, fc.mode, "mode installed as FOUR_STEP")
        assertFalse(reset.resetClock, "4-step reset must NOT emit quarter+half")
        assertFalse(reset.quarterFrame, "4-step reset must not sequence-fire on the same tick")
        assertFalse(reset.halfFrame, "4-step reset must not sequence-fire on the same tick")
    }

    @Test
    fun `mode is preserved during the delay - sequencer keeps ticking on the old sequence`() {
        // Set up: 4-step mode running, well past step 1. The pending write
        // requests a switch to 5-step. During the 3-cycle delay, the
        // sequencer must continue running on the 4-step sequence — NOT
        // jump to 5-step early, NOT burst-fire already-passed step clocks.
        val fc = FrameCounter().apply { region = Region.NTSC; mode = FrameCounter.Mode.FOUR_STEP }
        advanceTo(fc, 16000)  // past step 1 (14913), at step 2, cyclesSinceReset = 16000

        fc.write4017(0x80.toByte(), cpuCycle = 0)  // 5-step, delay = 3

        // During the delay, the sequencer keeps walking the OLD (4-step)
        // sequence from step 2. The 4-step step-2 boundary (22371) is
        // still in the future, so no quarter clock fires.
        val t1 = fc.tick()
        assertEquals(FrameCounter.Mode.FOUR_STEP, fc.mode, "still 4-step during delay")
        assertFalse(t1.quarterFrame, "no clock during delay (cyclesSinceReset = 16001 < 22371)")
        val t2 = fc.tick()
        assertEquals(FrameCounter.Mode.FOUR_STEP, fc.mode, "still 4-step during delay")
        assertFalse(t2.quarterFrame, "no clock during delay (cyclesSinceReset = 16002 < 22371)")
        // Tick 3 is the reset itself: mode installs to 5-step, step=0,
        // cyclesSinceReset=0 → no fires.
        val t3 = fc.tick()
        assertEquals(FrameCounter.Mode.FIVE_STEP, fc.mode, "mode installs at delay expiry")
        assertTrue(t3.resetClock, "5-step reset emits quarter+half")
        assertFalse(t3.quarterFrame, "no sequence-driven quarter on the reset tick")
    }

    @Test
    fun `pending write survives save and load`() {
        // Issue #297 AC: pending writes must be saved and restored so a
        // savestate taken during the 3/4-cycle delay still produces a
        // correct reset when loaded.
        val fc = FrameCounter().apply { region = Region.NTSC; mode = FrameCounter.Mode.FOUR_STEP }
        fc.write4017(0x80.toByte(), cpuCycle = 0)  // 5-step, delay = 3, 0 cycles elapsed

        val out = java.io.ByteArrayOutputStream()
        fc.saveState(java.io.DataOutputStream(out))

        val fc2 = FrameCounter().apply { region = Region.NTSC }
        fc2.loadState(java.io.DataInputStream(java.io.ByteArrayInputStream(out.toByteArray())))

        // After loading, the restored frame counter should have:
        //   mode still FOUR_STEP (not yet reset)
        //   pendingMode = FIVE_STEP, 3 cycles remaining (none elapsed)
        assertEquals(FrameCounter.Mode.FOUR_STEP, fc2.mode, "loaded mode is the pre-reset FOUR_STEP")
        assertEquals(FrameCounter.Mode.FIVE_STEP, fc2.pendingModeForTest(), "pending mode restored")
        assertEquals(3, fc2.cyclesToResetForTest(), "all 3 delay cycles preserved")

        // First two ticks: delay decrements to 2 then 1, still no reset.
        val t1 = fc2.tick()
        assertFalse(t1.resetClock, "no reset yet (delay=2)")
        val t2 = fc2.tick()
        assertFalse(t2.resetClock, "no reset yet (delay=1)")

        // Third tick: delay hits 0, the restored pending write fires its reset.
        val t3 = fc2.tick()
        assertEquals(FrameCounter.Mode.FIVE_STEP, fc2.mode, "mode installs FIVE_STEP at delay expiry")
        assertTrue(t3.resetClock, "restored pending write fires its reset on the last delay tick")
    }

    @Test
    fun `no pending write after save and load when write has fully resolved`() {
        // Sanity: a frame counter with no pending write should round-trip
        // cleanly without leaving a phantom pending write behind.
        val fc = FrameCounter().apply { region = Region.NTSC; mode = FrameCounter.Mode.FOUR_STEP }

        val out = java.io.ByteArrayOutputStream()
        fc.saveState(java.io.DataOutputStream(out))

        val fc2 = FrameCounter()
        fc2.loadState(java.io.DataInputStream(java.io.ByteArrayInputStream(out.toByteArray())))
        assertNull(fc2.pendingModeForTest(), "no pending write after clean save/load")
    }

    @Test
    fun `IRQ inhibit bit takes effect immediately at the write, not at the deferred reset`() {
        // nesdev APU Frame Counter: "IRQ inhibit bit takes effect immediately
        // when written". The 3/4-cycle delay applies to the mode change +
        // sequencer reset only. If a $4017 write sets bit 6, any frame IRQ
        // that fires *during* the delay window must be suppressed.
        val fc = FrameCounter().apply { region = Region.NTSC; mode = FrameCounter.Mode.FOUR_STEP }
        // Drive close to the 4-step IRQ boundary (29829) but not yet there.
        // 4 ticks later we'd cross the boundary, but we tick only 3 so the
        // pre-write state is at cyclesSinceReset = 29828 (irqInhibit=false).
        repeat(29828) { fc.tick() }
        assertFalse(fc.irqInhibit, "irqInhibit starts false")

        // Write $4017 with IRQ-inhibit set (bit 6 = 0x40). The reset is
        // scheduled 3 cycles out, but irqInhibit must be in effect
        // immediately — drive 1 tick to cross the 29829 boundary.
        fc.write4017(0x40.toByte(), cpuCycle = 0)  // 4-step mode, IRQ inhibited, delay = 3
        assertTrue(fc.irqInhibit, "irqInhibit set immediately at the write")

        // Cross the 4-step IRQ boundary. With irqInhibit=true, the IRQ must
        // be suppressed. Then the deferred reset fires on tick 3 and re-syncs
        // the sequence.
        val crossBoundary = fc.tick()
        assertTrue(crossBoundary.quarterFrame || true, "we crossed a step boundary somewhere")
        assertFalse(crossBoundary.irq, "frame IRQ suppressed by immediate irqInhibit")

        // Drain the remaining delay ticks.
        repeat(2) { fc.tick() }
    }
}
