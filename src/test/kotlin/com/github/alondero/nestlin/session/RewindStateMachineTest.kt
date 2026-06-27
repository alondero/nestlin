package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.rewind.RewindBuffer
import com.github.alondero.nestlin.testutil.failTest
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * State-transition tests for [RewindStateMachine] (issue #189).
 *
 * The machine's contract is a pure transition table: given (rewindActive, gameLoaded, enabled),
 * what action should the RunLoop take? These tests verify each transition without instantiating
 * Nestlin — every side effect is a lambda, so we can substitute recording fakes.
 *
 * Why the state machine has three states and not two: the boundary between SCRUBBING and IDLE
 * has a one-shot "reset the throttle baseline" side effect. Encoding the exit as its own state
 * (WAS_REWINDING) makes the transition a property of the machine rather than a flag the RunLoop
 * has to remember.
 */
class RewindStateMachineTest {

    /** A test harness wiring a real [RewindBuffer] to recording fakes for every side effect. */
    private class Harness(
        initialBuffer: List<ByteArray> = emptyList(),
        val gameLoaded: Boolean = true,
        val enabled: Boolean = true,
    ) {
        val buffer = RewindBuffer(16).also { initialBuffer.forEach { b -> it.capture(b) } }
        var saveCount = 0
        var loadCount = 0
        var renderCount = 0
        var paceCount = 0
        var parkCount = 0
        var muted: Boolean? = null

        val machine = RewindStateMachine(
            buffer = buffer,
            saveState = { saveCount++; byteArrayOf(saveCount.toByte()) },
            loadState = { loadCount++ },
            renderOneFrame = { renderCount++ },
            setMuted = { muted = it },
            paceFrame = { paceCount++ },
            park = { parkCount++ },
            isGameLoaded = { gameLoaded },
            isEnabled = { enabled },
        )
    }

    @Test
    fun `IDLE stays IDLE when no rewind requested`() {
        val h = Harness()
        assertThat(h.machine.tick(), equalTo(RewindStateMachine.TickAction.STEP_NORMAL))
        assertThat(h.machine.state, equalTo(RewindStateMachine.State.IDLE))
        assertFalse(h.machine.isRewinding())
        assertThat(h.saveCount, equalTo(0))
        assertThat(h.loadCount, equalTo(0))
    }

    @Test
    fun `IDLE + rewindActive transitions to SCRUBBING and mutes audio`() {
        val h = Harness()
        h.machine.setRewindActive(true)
        assertThat(h.machine.tick(), equalTo(RewindStateMachine.TickAction.STEP_REWIND))
        assertThat(h.machine.state, equalTo(RewindStateMachine.State.SCRUBBING))
        assertTrue(h.machine.isRewinding())
        assertThat(h.muted, equalTo(true))
    }

    @Test
    fun `SCRUBBING unloads, renders, and paces each tick`() {
        val h = Harness(initialBuffer = listOf(byteArrayOf(1), byteArrayOf(2), byteArrayOf(3)))
        h.machine.setRewindActive(true)
        // First tick enters SCRUBBING AND steps (the IDLE→SCRUBBING branch recurses into tick()
        // so the user sees immediate scrub on Backspace press, not one frame of normal play).
        h.machine.tick()
        assertThat(h.machine.state, equalTo(RewindStateMachine.State.SCRUBBING))
        assertThat(h.loadCount, equalTo(1))
        assertThat(h.renderCount, equalTo(1))
        assertThat(h.paceCount, equalTo(1))
        // Subsequent ticks walk back another batch.
        h.machine.tick()
        assertThat(h.loadCount, equalTo(2))
        assertThat(h.paceCount, equalTo(2))
    }

    @Test
    fun `SCRUBBING parks when buffer is empty instead of loading`() {
        val h = Harness(initialBuffer = emptyList())
        h.machine.setRewindActive(true)
        // First tick enters + tries to step on empty buffer → park (no load/render).
        h.machine.tick()
        assertThat(h.machine.state, equalTo(RewindStateMachine.State.SCRUBBING))
        assertThat(h.loadCount, equalTo(0))
        assertThat(h.renderCount, equalTo(0))
        assertThat(h.parkCount, equalTo(1))
        // Subsequent ticks on an empty buffer keep parking (no growth into a load).
        h.machine.tick()
        assertThat(h.parkCount, equalTo(2))
    }

    @Test
    fun `SCRUBBING + rewind released transitions through WAS_REWINDING then IDLE`() {
        val h = Harness(initialBuffer = listOf(byteArrayOf(1), byteArrayOf(2)))
        h.machine.setRewindActive(true)
        h.machine.tick()  // enter → SCRUBBING
        h.machine.setRewindActive(false)
        // First tick after release: WAS_REWINDING, unmutes, returns STEP_NORMAL.
        assertThat(h.machine.tick(), equalTo(RewindStateMachine.TickAction.STEP_NORMAL))
        assertThat(h.machine.state, equalTo(RewindStateMachine.State.WAS_REWINDING))
        assertThat(h.muted, equalTo(false))
        assertFalse(h.machine.isRewinding())
        // Second tick: IDLE, no unmute again.
        h.muted = null
        assertThat(h.machine.tick(), equalTo(RewindStateMachine.TickAction.STEP_NORMAL))
        assertThat(h.machine.state, equalTo(RewindStateMachine.State.IDLE))
        assertThat(h.muted, equalTo(null))
    }

    @Test
    fun `scrubbing a no-game session falls through to IDLE (no scrub)`() {
        val h = Harness(gameLoaded = false)
        h.machine.setRewindActive(true)
        assertThat(h.machine.tick(), equalTo(RewindStateMachine.TickAction.STEP_NORMAL))
        assertThat(h.machine.state, equalTo(RewindStateMachine.State.IDLE))
        assertThat(h.muted, equalTo(null))
    }

    @Test
    fun `scrubbing a disabled session falls through to IDLE (no scrub)`() {
        val h = Harness(enabled = false)
        h.machine.setRewindActive(true)
        assertThat(h.machine.tick(), equalTo(RewindStateMachine.TickAction.STEP_NORMAL))
        assertThat(h.machine.state, equalTo(RewindStateMachine.State.IDLE))
    }

    @Test
    fun `captureFrame no-ops when disabled`() {
        val h = Harness(enabled = false)
        h.machine.captureFrame { failTest("should not be called") }
        assertThat(h.saveCount, equalTo(0))
        assertThat(h.buffer.size, equalTo(0))
    }

    @Test
    fun `captureFrame no-ops when no game loaded`() {
        val h = Harness(gameLoaded = false)
        h.machine.captureFrame { failTest("should not be called") }
        assertThat(h.saveCount, equalTo(0))
        assertThat(h.buffer.size, equalTo(0))
    }

    @Test
    fun `captureFrame appends to the buffer on every successful frame`() {
        val h = Harness()
        h.machine.captureFrame { failTest("should not be called") }
        h.machine.captureFrame { failTest("should not be called") }
        h.machine.captureFrame { failTest("should not be called") }
        assertThat(h.saveCount, equalTo(3))
        assertThat(h.buffer.size, equalTo(3))
    }

    @Test
    fun `captureFrame failure routes to onCaptureFailure and does not crash`() {
        val h = Harness()
        // Force saveState to throw by replacing the lambda. We rebuild the machine for that.
        val machine = RewindStateMachine(
            buffer = h.buffer,
            saveState = { throw IllegalStateException("simulated mapper save failure") },
            loadState = { h.loadCount++ },
            renderOneFrame = { h.renderCount++ },
            setMuted = { h.muted = it },
            paceFrame = { h.paceCount++ },
            park = { h.parkCount++ },
            isGameLoaded = { h.gameLoaded },
            isEnabled = { h.enabled },
        )
        var seen: Throwable? = null
        machine.captureFrame { seen = it }
        assertThat(seen?.message, equalTo("simulated mapper save failure"))
        assertThat(h.buffer.size, equalTo(0))  // nothing captured
    }

    @Test
    fun `clearBuffer drops every snapshot`() {
        val h = Harness()
        repeat(5) { h.machine.captureFrame { failTest("should not be called") } }
        assertThat(h.buffer.size, equalTo(5))
        h.machine.clearBuffer()
        assertThat(h.buffer.size, equalTo(0))
    }
}
