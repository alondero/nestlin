package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.toSignedByte
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression coverage for GitHub issue #230, item 1:
 *
 *   "Triangle 'ultrasonic' returns a constant mid-level — `TriangleChannel.output`
 *    returns `7` when `timerPeriod < 2`. A defensible anti-pop hack, but
 *    hardware keeps the sequencer running at ultrasonic frequency."
 *
 * The hardware triangle channel keeps the 32-step sequencer advancing at
 * whatever rate the timer dictates, including ultrasonic rates the DAC cannot
 * reproduce. The pre-fix shortcut (`return 7`) pinned the output to the
 * constant 7 — 0-indexed value of the table at sequenceStep=8 — which is a
 * single point on the wave and not what the sequencer is actually doing.
 *
 * These tests pin three points on the triangle sequence at `timerPeriod < 2`
 * to prove the sequencer value, not a constant hack, drives the output:
 *
 *  - sequenceStep = 0  → 15  (peak, top of the ramp)
 *  - sequenceStep = 15 → 0   (first zero-crossing going down)
 *  - sequenceStep = 31 → 15  (peak, second half)
 *
 * Only the first and second fail under the old code; the third is a
 * tie-breaking check that the 7 returned at step 8 is not the old hack
 * (step 31 always returns 15, regardless).
 */
class TriangleUltrasonicTest {

    private fun newChannelAtStep(step: Int): TriangleChannel {
        // Drive the channel into the ultrasonic regime with a non-zero length
        // counter and non-zero linear counter, so the output gate clears all
        // pre-output() short-circuits except the one under test.
        val ch = TriangleChannel()
        ch.isEnabled = true
        // write400B loads the length counter (lengthTable[31] = 30) only if
        // the channel is enabled at write time. Setting isEnabled first and
        // then writing makes the length counter non-zero.
        ch.write400B(0xF8.toByte())
        // write400B clobbers timerPeriod (timer-high bits of the value it
        // carries). Override with the ultrasonic regime explicitly.
        ch.timerPeriod = 1
        ch.timerCounter = 1
        ch.linearCounter = 1
        ch.linearCounterReloadFlag = false
        ch.sequenceStep = step
        return ch
    }

    @Test
    fun `triangle ultrasonic at sequenceStep 0 emits peak 15 not constant 7`() {
        val ch = newChannelAtStep(step = 0)
        // 32-step triangle sequence, index 0 is the peak value 15. Pre-fix
        // this would return 7 (the constant mid-level hack); post-fix the
        // sequencer value drives the output.
        assertEquals(15, ch.output())
    }

    @Test
    fun `triangle ultrasonic at sequenceStep 15 emits zero-cross 0 not constant 7`() {
        val ch = newChannelAtStep(step = 15)
        // Step 15 is the midpoint of the descending half — hardware output is
        // 0. The constant-7 hack masks this as 7, which is a clearly audible
        // tonal error.
        assertEquals(0, ch.output())
    }

    @Test
    fun `triangle ultrasonic at sequenceStep 31 emits peak 15 not constant 7`() {
        val ch = newChannelAtStep(step = 31)
        // Step 31 is the symmetric peak in the second half. A pre-fix return
        // of 7 fails this; a post-fix return of 15 passes.
        assertEquals(15, ch.output())
    }

    @Test
    fun `triangle ultrasonic at sequenceStep 8 still emits natural 7`() {
        // Tie-breaking assertion: the new behaviour must also be the natural
        // triangle-sequence value at every step. Sequence index 8 is the only
        // index that maps to 7, so this locks down the "we didn't just
        // replace one constant hack with another" invariant.
        val ch = newChannelAtStep(step = 8)
        assertEquals(7, ch.output())
    }
}
