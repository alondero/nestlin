package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.testutil.FakeInterruptController
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Issue #9 — BRK B-flag is never pushed correctly.
 *
 * The 6502's "B" flag (bit 4 of P) doesn't exist as stored state — it only
 * manifests in the *pushed* status byte. PHP and BRK force bit 4 = 1; IRQ
 * and NMI force bit 4 = 0. This is how a handler (or kernel) can tell a
 * software BRK apart from a hardware interrupt.
 *
 * `ProcessorStatus.breakCommand` was created for exactly this — BRK sets it
 * to `true` before calling `asByte()`, but `asByte()` ignored the field
 * and hardcoded `(0 shl 4)`. Result: BRK pushed status with bit 4 = 0,
 * indistinguishable from an IRQ/NMI on the stack. The nestest golden log
 * doesn't exercise BRK lines, so this slipped through `GoldenLogTest`.
 *
 * These tests pin the correct behaviour at three levels:
 *  1. `asByte()` — direct unit test on the serializer.
 *  2. BRK — the bug under fix (bit 4 must be 1 in the pushed byte).
 *  3. IRQ — pins the inverse discriminator (bit 4 must be 0); this is the
 *     whole point of the B flag and the reason the fix must not flip it
 *     for interrupts.
 *
 * Note: PHP (0x08) shares `asByte()` and would today push bit 4 = 0
 * because PHP does not set `breakCommand` — the 6502 spec says PHP must
 * push B = 1. That is a *separate* bug; this PR is scoped to BRK per
 * issue #9. The PHP fix should follow the same pattern (set
 * `breakCommand = true` in the push lambda, reset after push).
 */
class Issue9BrkBFlagPushTest {

    private fun freshCpu() = Cpu(Memory.createWithApu().first).apply { reset() }

    // ===== asByte() — direct unit test on the serializer =================

    @Test
    fun asByteIncludesBreakCommandAtBit4() {
        val status = ProcessorStatus().apply {
            // Pick an unambiguous baseline: all "stored" flags cleared so any
            // bit in the output came from `breakCommand` and the always-set
            // bit 5 / always-clear bit 4 (pre-fix).
            carry = false
            zero = false
            interruptDisable = false
            decimalMode = false
            overflow = false
            negative = false
            breakCommand = false
        }

        // No B flag → bit 4 clear.
        val withoutB = status.asByte().toUnsignedInt() and 0x10
        assertThat(withoutB, equalTo(0))

        // B flag set → bit 4 set (the always-set bit 5 stays set).
        status.breakCommand = true
        val withB = status.asByte().toUnsignedInt() and 0x10
        assertThat(withB, equalTo(0x10))
        // Bit 5 is always 1 (the unused-but-pushed flag).
        assertThat(status.asByte().toUnsignedInt() and 0x20, equalTo(0x20))
    }

    // ===== BRK pushes status with B flag set (the bug under fix) =========

    @Test
    fun brkPushesStatusWithBFlagSet() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x00.toSignedByte() // BRK opcode
        // BRK skips a padding byte; supply one so the PC arithmetic doesn't
        // bounce off unmapped memory when reading PC+1.
        cpu.memory[0x0001] = 0xAA.toSignedByte()
        // IRQ vector — the BRK handler's PC source. Point at $0000 so the
        // next instruction is a known NOP-like state (here: another BRK).
        cpu.memory[0xFFFE] = 0x00.toSignedByte()
        cpu.memory[0xFFFF] = 0x00.toSignedByte()
        // Stack pointer starts at $FD so the three pushes land at $01FD,
        // $01FC, $01FB (PC high, PC low, status — see `Cpu.push`).
        cpu.registers.stackPointer = 0xFD.toSignedByte()
        // Pick a baseline P without bit 4 so the assertion is unambiguous.
        cpu.processorStatus.apply {
            carry = false
            zero = false
            interruptDisable = false
            decimalMode = false
            overflow = false
            negative = false
        }

        cpu.tick()

        // The pushed status byte is at $01FB (3 bytes pushed: PCh, PCl, P).
        val pushedStatus = cpu.memory[0x01FB].toUnsignedInt()
        // The fix: bit 4 (B) must be 1 — this is the BRK/IRQ discriminator.
        assertThat(pushedStatus and 0x10, equalTo(0x10))
        // And bit 5 (the always-pushed unused flag) must still be 1.
        assertThat(pushedStatus and 0x20, equalTo(0x20))
        // The I flag must be set after BRK.
        assertThat(cpu.processorStatus.interruptDisable, equalTo(true))
    }

    @Test
    fun brkResetsBreakCommandAfterPush() {
        // `breakCommand` is "magic" transient state — it should only be true
        // during the push, not linger. A follow-up PHP would otherwise push
        // a polluted byte. This pins the hygiene invariant.
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x00.toSignedByte() // BRK
        cpu.memory[0x0001] = 0x00.toSignedByte() // padding
        cpu.memory[0xFFFE] = 0x00.toSignedByte()
        cpu.memory[0xFFFF] = 0x00.toSignedByte()
        cpu.registers.stackPointer = 0xFD.toSignedByte()

        cpu.tick()

        assertThat(cpu.processorStatus.breakCommand, equalTo(false))
    }

    // ===== IRQ pushes status with B flag clear (preserves the discriminator)

    @Test
    fun irqPushesStatusWithBFlagClear() {
        // IRQ uses the same dispatchInterrupt path as NMI. If this test
        // passes, the discriminator is intact for both — that's the whole
        // point of the B flag.
        val fakeController = FakeInterruptController()
        val cpu = Cpu(Memory.createWithApu().first, fakeController).apply {
            reset()
            registers.programCounter = 0x0000.toSignedShort()
            // IRQ has no 1-instruction latency in the fake — it's dispatched
            // on the same tick it's armed, as long as I is clear. The NOPs
            // give the (un-executed) interrupted-instruction slot a sane value
            // and the IRQ vector handler a known landing pad.
            for (addr in 0x0000..0x000F) memory[addr] = 0xEA.toSignedByte()
            memory[0xFFFE] = 0x80.toSignedByte()
            memory[0xFFFF] = 0x00.toSignedByte()
            for (addr in 0x0080..0x008F) memory[addr] = 0xEA.toSignedByte()
            // I flag must be clear or IRQ is suppressed at pendingInterrupt.
            registers.stackPointer = 0xFD.toSignedByte()
            processorStatus.interruptDisable = false
        }

        // IRQ has no 1-instruction latency in the fake; armed == dispatched.
        fakeController.armIrq()
        cpu.tick()

        val pushedStatus = cpu.memory[0x01FB].toUnsignedInt()
        // IRQ pushes status with bit 4 (B) clear — that's the discriminator.
        assertThat(pushedStatus and 0x10, equalTo(0))
        // Bit 5 still set (always-pushed unused flag).
        assertThat(pushedStatus and 0x20, equalTo(0x20))
    }
}