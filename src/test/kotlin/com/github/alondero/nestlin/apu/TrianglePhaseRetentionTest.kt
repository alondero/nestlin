package com.github.alondero.nestlin.apu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Regression coverage for GitHub issue #296:
 *
 *   "Triangle DAC incorrectly snaps to zero when counters halt instead of
 *    retaining its phase level."
 *
 * The real NES triangle channel holds its 4-bit DAC output at whatever value
 * the 32-step sequencer was at when the length counter / linear counter hit
 * zero. The sequencer stops advancing (because clockTimer gates on both
 * counters being nonzero), but the DAC continues to emit that frozen level.
 *
 * The pre-fix TriangleChannel.output() forced `return 0` whenever the length
 * counter or linear counter hit zero — which produces an audible click/pop
 * every time a triangle note ends, and momentarily corrupts the TND mixer
 * contribution for the other channels.
 *
 * These tests pin the four conditions that previously snapped to zero and
 * assert they now retain triangleSequence[sequenceStep]:
 *
 *  - length counter at zero, linear counter nonzero
 *  - linear counter at zero, length counter nonzero
 *  - both counters at zero
 *  - sequenceStep at three representative positions (peak / mid-ramp /
 *    trough), to prove the retained value is the natural triangle sequence
 *    value at that step and not a constant.
 *
 * Setup uses only the public TriangleChannel surface — write400B to load the
 * length counter (lengthTable[31] = 30), then clockLength() to drive it
 * down to zero. linearCounter and sequenceStep are public `var`s so they're
 * set directly.
 */
class TrianglePhaseRetentionTest {

    /**
     * Triangle sequence table duplicated here so the test is self-contained
     * and readable. Must stay in lockstep with TriangleChannel.triangleSequence.
     */
    private val triangleSequence = intArrayOf(
        15, 14, 13, 12, 11, 10,  9,  8,  7,  6,  5,  4,  3,  2,  1,  0,
         0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15
    )

    /**
     * Returns a TriangleChannel set up at the given sequence step with the
     * length counter clocked all the way down to zero. linearCounter is
     * set directly (it's a public var).
     */
    private fun newChannelWithLengthCounterAtZero(
        step: Int,
        linearValue: Int
    ): TriangleChannel {
        val ch = TriangleChannel()
        ch.isEnabled = true
        // write400B loads lengthTable[(value shr 3) and 0x1F] into the
        // length counter. value = 0xF8 → index 31 → 30. Clock it down 30
        // times to reach zero — this is exactly how hardware reaches
        // "length counter at zero" in the wild.
        ch.write400B(0xF8.toByte())
        repeat(30) { ch.clockLength() }
        // Sanity: the length counter must really be at zero before we run
        // the assertion under test. If this fails the test is broken, not
        // the channel.
        assertEquals(0, ch.getLengthCounterValue(), "test setup: length counter")
        ch.timerPeriod = 1
        ch.timerCounter = 1
        ch.linearCounter = linearValue
        ch.linearCounterReloadFlag = false
        ch.sequenceStep = step
        return ch
    }

    /**
     * Returns a TriangleChannel set up at the given sequence step with the
     * length counter loaded to a non-zero value (30) and the linear counter
     * at zero. Linear counter is a public var so we set it directly.
     */
    private fun newChannelWithLinearCounterAtZero(
        step: Int
    ): TriangleChannel {
        val ch = TriangleChannel()
        ch.isEnabled = true
        ch.write400B(0xF8.toByte())
        assertEquals(30, ch.getLengthCounterValue(), "test setup: length counter")
        ch.timerPeriod = 1
        ch.timerCounter = 1
        ch.linearCounter = 0
        ch.linearCounterReloadFlag = false
        ch.sequenceStep = step
        return ch
    }

    @Test
    fun `output retains phase when length counter is zero and linear counter is nonzero`() {
        // sequenceStep = 5 → triangleSequence[5] = 10 (mid-ramp descending).
        // Pre-fix this would return 0 (lengthCounter.value == 0 gate).
        val ch = newChannelWithLengthCounterAtZero(step = 5, linearValue = 1)
        assertEquals(triangleSequence[5], ch.output())
    }

    @Test
    fun `output retains phase when linear counter is zero and length counter is nonzero`() {
        // sequenceStep = 12 → triangleSequence[12] = 3 (lower ramp).
        // Pre-fix this would return 0 (linearCounter == 0 gate).
        val ch = newChannelWithLinearCounterAtZero(step = 12)
        assertEquals(triangleSequence[12], ch.output())
    }

    @Test
    fun `output retains phase when both length and linear counter are zero`() {
        // sequenceStep = 28 → triangleSequence[28] = 12 (upper ramp ascending).
        // Pre-fix this would return 0 (both gates fire).
        val ch = newChannelWithLengthCounterAtZero(step = 28, linearValue = 0)
        assertEquals(triangleSequence[28], ch.output())
    }

    @Test
    fun `output retains phase at sequenceStep zero (peak 15)`() {
        // First peak of the triangle wave. Tying the test down at this step
        // ensures the fix doesn't just substitute "8" or any other constant
        // for the previous "0" snap.
        val ch = newChannelWithLengthCounterAtZero(step = 0, linearValue = 0)
        assertEquals(15, ch.output())
    }

    @Test
    fun `output retains phase at sequenceStep 15 (first trough)`() {
        // First zero-crossing of the triangle wave. This is the most audible
        // snap — going from a sustained non-zero phase level to a sudden
        // zero is the click you hear at the end of every triangle note.
        val ch = newChannelWithLengthCounterAtZero(step = 15, linearValue = 0)
        assertEquals(0, ch.output())
    }

    @Test
    fun `output retains phase at sequenceStep 31 (second peak 15)`() {
        // Symmetric peak in the second half of the wave. Locks down the
        // "retained value is the natural sequence value, not a constant"
        // invariant from the other side of the ramp.
        val ch = newChannelWithLengthCounterAtZero(step = 31, linearValue = 0)
        assertEquals(15, ch.output())
    }

    @Test
    fun `disabled channel still outputs zero regardless of phase`() {
        // The isEnabled gate MUST remain — when the channel is disabled
        // (via $4015 bit 2 clear), the DAC output is genuinely zero. The fix
        // only removes the counter-zero gates, not the enable gate.
        val ch = TriangleChannel()
        ch.isEnabled = false
        ch.sequenceStep = 0 // a peak phase value, but the channel is off
        ch.timerPeriod = 1
        ch.timerCounter = 1
        assertEquals(0, ch.output())
    }
}