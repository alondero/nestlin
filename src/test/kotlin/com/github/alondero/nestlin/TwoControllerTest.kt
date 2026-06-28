package com.github.alondero.nestlin

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Two-port hardware behaviour regression test (issue: 2-player support).
 *
 * The NES controller protocol has two ports that share the strobe line ($4016 write
 * latches BOTH shift registers) but report data on separate read addresses ($4016 for
 * port 1, $4017 for port 2). This test pins that contract at both layers:
 *
 *  1. Direct against [Controller] — proves the per-port orchestrator behaves as the
 *     Memory dispatch will rely on.
 *  2. Through [Memory] $4016/$4017 — proves the Memory dispatch routes correctly
 *     (and proves a single $4016 write strobes BOTH ports — the hardware-accurate
 *     shared strobe — which is non-obvious from the controller level alone).
 *
 * This is the canary for the InputDevice refactor (Phase 1): every later phase must
 * keep these tests green. If they break, the port abstraction has broken hardware
 * fidelity and the 2-player experience is at risk.
 *
 * The open-bus mask `0x40` is the convention [StrobeRegister.OPEN_BUS_MASK] uses;
 * it shows up as `0x40 | data_bit` on every read. We assert against the OR'd value
 * rather than the raw bit so the test mirrors what a real $4016/$4017 read returns.
 */
class TwoControllerTest {

    @Test
    fun `port 1 and port 2 report independent buttons`() {
        val port1 = Controller()
        val port2 = Controller()

        // Press A on P1 and B on P2. A is bit 0; B is bit 1. Latching a different
        // bitmap on each port is what makes them independent — the read sequence
        // for each port starts with A's bit, then B's bit, etc.
        port1.setButton(Controller.Button.A, true)
        port2.setButton(Controller.Button.B, true)

        port1.write(1); port1.write(0)
        port2.write(1); port2.write(0)

        // First read: bit 0 (A). P1 has A pressed (1); P2 does NOT (0).
        assertThat(port1.read().toInt() and 0x01, equalTo(1))
        assertThat(port2.read().toInt() and 0x01, equalTo(0))

        // Second read: bit 1 (B). P1 has no B (0); P2 has B (1).
        assertThat(port1.read().toInt() and 0x01, equalTo(0))
        assertThat(port2.read().toInt() and 0x01, equalTo(1))
    }

    @Test
    fun `port 1 reads do not advance port 2 shift register`() {
        val port1 = Controller()
        val port2 = Controller()

        port2.setButton(Controller.Button.B, true)
        port2.write(1); port2.write(0)

        // Drain P1's shift register (eight empty reads, all 0x40).
        repeat(8) { port1.read() }

        // P2's shift register is untouched — first read still returns A's bit = 0.
        assertThat(port2.read().toInt() and 0x01, equalTo(0))
        // And B itself is still in the second bit.
        assertThat(port2.read().toInt() and 0x01, equalTo(1))
    }

    @Test
    fun `peek on each port returns the same bit without shifting`() {
        val port1 = Controller()
        val port2 = Controller()

        port1.setButton(Controller.Button.START, true)
        port2.setButton(Controller.Button.SELECT, true)
        port1.write(1); port1.write(0)
        port2.write(1); port2.write(0)

        // Peek repeatedly — must keep reporting bit 0 (A, unpressed) without shifting.
        // For P1 that's 0x40 (open-bus only). For P2 same — A is bit 0 in both ports.
        assertThat(port1.peek(), equalTo(0x40.toByte()))
        assertThat(port1.peek(), equalTo(0x40.toByte()))
        assertThat(port2.peek(), equalTo(0x40.toByte()))
        assertThat(port2.peek(), equalTo(0x40.toByte()))

        // After consuming P1's first three bits, peek should show bit 3 = START (1).
        // First three reads: A=0, B=0, SELECT=0.
        port1.read(); port1.read(); port1.read()
        assertThat(port1.peek().toInt() and 0x01, equalTo(1))
        // P2's peek is still bit 0 because P1's reads didn't touch P2 at all.
        assertThat(port2.peek(), equalTo(0x40.toByte()))
    }

    @Test
    fun `Memory 0x4016 write strobes BOTH ports (hardware-accurate shared strobe)`() {
        // The 2A03 has a single strobe signal wired to both ports' shift registers.
        // One $4016 write must reload BOTH ports' latches. Pin that here so the
        // InputDevice dispatch in Phase 1 doesn't accidentally de-dup.
        val memory = Memory()
        memory.controller1.setButton(Controller.Button.A, true)
        memory.controller2.setButton(Controller.Button.B, true)

        // Single $4016 write, strobe high then low — both ports' shift registers
        // must latch the new button bitmaps.
        memory[0x4016] = 1
        memory[0x4016] = 0

        // $4016 → controller1: read B's bit (bit 1) to confirm the latched bitmap is
        // controller1's (which has only A pressed) — bit 1 = 0.
        memory[0x4016]  // consume A
        assertThat(memory[0x4016].toInt() and 0x01, equalTo(0))
        // $4017 → controller2: same single $4016 write latched controller2's bitmap
        // (which has B pressed). Read bit 1 — must be 1.
        memory[0x4017]  // consume A (not pressed on P2)
        assertThat(memory[0x4017].toInt() and 0x01, equalTo(1))
    }

    @Test
    fun `Memory 0x4016 read and 0x4017 read return independent bytes`() {
        val memory = Memory()
        // Press A on P1 and B on P2. A is bit 0; B is bit 1.
        memory.controller1.setButton(Controller.Button.A, true)
        memory.controller2.setButton(Controller.Button.B, true)

        // Strobe both ports.
        memory[0x4016] = 1
        memory[0x4016] = 0

        // $4016 → controller1: bit 0 (A) = 1.
        assertThat(memory[0x4016].toInt() and 0x01, equalTo(1))
        // $4017 → controller2: bit 0 (A, NOT pressed) = 0.
        assertThat(memory[0x4017].toInt() and 0x01, equalTo(0))

        // Second read: P1 has no B pressed (bit 1 = 0); P2 has B (bit 1 = 1).
        assertThat(memory[0x4016].toInt() and 0x01, equalTo(0))
        assertThat(memory[0x4017].toInt() and 0x01, equalTo(1))
    }

    @Test
    fun `Memory peek of 0x4016 and 0x4017 does not advance either shift register`() {
        val memory = Memory()
        memory.controller1.setButton(Controller.Button.A, true)
        memory.controller2.setButton(Controller.Button.B, true)

        memory[0x4016] = 1
        memory[0x4016] = 0

        // Peek both ports twice — must return the same byte both times.
        val p1Peek1 = memory.peek(0x4016)
        val p1Peek2 = memory.peek(0x4016)
        val p2Peek1 = memory.peek(0x4017)
        val p2Peek2 = memory.peek(0x4017)

        assertThat(p1Peek1, equalTo(p1Peek2))
        assertThat(p2Peek1, equalTo(p2Peek2))

        // The subsequent real read still returns what peek reported — proves peek
        // didn't shift.
        assertThat(memory[0x4016], equalTo(p1Peek1))
        assertThat(memory[0x4017], equalTo(p2Peek1))
    }
}
