package com.github.alondero.nestlin.testutil

import com.github.alondero.nestlin.cpu.InterruptController
import com.github.alondero.nestlin.cpu.InterruptKind

/**
 * Test double for [InterruptController]. Issue #190 / ADR-0003 — the test
 * fixture that lets CPU interrupt tests run without a real PPU or mapper
 * (the previous `cpu.memory.ppuAddressedMemory.nmiOccurred = true` setup
 * reached three layers deep into PPU state to drive a CPU scenario).
 *
 * **How to drive a test scenario:**
 *  1. Construct the Cpu with this fake: `Cpu(memory, FakeInterruptController())`.
 *  2. Call [armNmi] / [armIrq] to assert the corresponding line.
 *  3. Call `cpu.tick()` — the fake's `pendingInterrupt` returns the kind
 *     following the same ordering rules as the production controller
 *     (NMI wins over IRQ; 1-instruction latency when not idle).
 *  4. Inspect [nmiArmed] / [acknowledgedNmiCount] / [acknowledgedIrqCount]
 *     to assert what the controller did internally.
 *
 * The fake owns its own `nmiArmed` boolean so tests can verify the
 * latency invariant (e.g. "after one tick the NMI is armed but not yet
 * dispatched") without poking at any internal CPU field.
 *
 * **Reset behaviour:** [reset] clears all state — call it between test
 * cases that share a fake across multiple CPU ticks. The Cpu's own
 * `cpu.reset()` does NOT reset the controller (the production
 * controller is owned by the emulator, not the CPU); tests that want
 * to start a scenario from a clean slate should call [reset] explicitly.
 */
class FakeInterruptController : InterruptController {

    // --- Producer-driven state (the test sets these directly) -------------
    private var nmiPending = false
    private var irqPending = false

    // --- Internal controller state (latency + ack bookkeeping) ------------
    /** True between an armed NMI and its dispatch. See [InterruptController]
     *  for the 1-instruction-latency contract. */
    var nmiArmed: Boolean = false
        private set

    var acknowledgedNmiCount: Int = 0
        private set
    var acknowledgedIrqCount: Int = 0
        private set

    /** The kind the controller last acknowledged via [acknowledge]. */
    var lastAcknowledged: InterruptKind? = null
        private set

    /** Arms an NMI edge — equivalent to the PPU setting vblank + PPUCTRL bit 7. */
    fun armNmi() { nmiPending = true }

    /** Clears an NMI edge — equivalent to a `$2002` read or pre-render scanline. */
    fun clearNmi() {
        nmiPending = false
        nmiArmed = false
    }

    /** Asserts the IRQ line — equivalent to a mapper scanline counter hitting threshold
     *  or the APU frame-counter firing. */
    fun armIrq() { irqPending = true }

    /** De-asserts the IRQ line. */
    fun clearIrq() { irqPending = false }

    /** Reset everything — call between test scenarios that share a fake. */
    fun reset() {
        nmiPending = false
        irqPending = false
        nmiArmed = false
        acknowledgedNmiCount = 0
        acknowledgedIrqCount = 0
        lastAcknowledged = null
    }

    override fun pendingInterrupt(idle: Boolean, interruptDisable: Boolean): InterruptKind? {
        if (!nmiPending) {
            // NMI latch cleared within the latency window → suppress.
            nmiArmed = false
            if (interruptDisable) return null
            return if (irqPending) InterruptKind.IRQ else null
        }
        if (!nmiArmed && !idle) {
            nmiArmed = true
            return null  // arm; dispatch on next tick
        }
        // NMI wins over any pending IRQ.
        return InterruptKind.NMI
    }

    override fun acknowledge(kind: InterruptKind) {
        lastAcknowledged = kind
        when (kind) {
            InterruptKind.NMI -> {
                nmiPending = false
                nmiArmed = false
                acknowledgedNmiCount++
            }
            InterruptKind.IRQ -> {
                irqPending = false
                acknowledgedIrqCount++
            }
        }
    }
}