package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.testutil.FakeInterruptController
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Regression bar for issue #25 — `workCyclesLeft` mutation is inconsistent
 * (`+= 2` mixed with `= 6`). The scheduler in [Cpu.tick] dispatches an
 * instruction only when `workCyclesLeft <= 0`, so the convention *currently*
 * produces the same observable behaviour either way. The contract these tests
 * pin down is the one the refactor standardises on: every opcode sets
 * `workCyclesLeft` to the **absolute** cycle count for that opcode.
 *
 * `tick()` decrements `workCyclesLeft` after the opcode runs, so immediately
 * after a single `tick()` against an N-cycle instruction the field is `N - 1`,
 * not `N`. The expected values below are written against that post-decrement
 * value to keep the tests hermetic to the scheduler.
 */
class WorkCyclesLeftConsistencyTest {

    // Factory (issue #22): wire Memory + Apu so cpu.memory.apu is non-null when
    // the IRQ-check path reads it on every tick.
    private fun freshCpu() = Cpu(Memory.createWithApu().first).apply { reset() }

    @Test
    fun nopImpliedSetsTwoCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0xEA.toSignedByte() // NOP implied

        cpu.tick()

        // 2-cycle instruction, post-tick decrement leaves 1.
        assertThat(cpu.workCyclesLeft, equalTo(1))
    }

    @Test
    fun brkSetsSevenCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x00.toSignedByte() // BRK
        // BRK reads a 16-bit "return" address from PC; supply one so the
        // bus reads don't bounce off unmapped memory.
        cpu.memory[0x0001] = 0x00.toSignedByte()
        cpu.memory[0x0002] = 0x80.toSignedByte()
        // Reset vector -> $0000 so the loaded PC is sane if the test reuses
        // the CPU; not strictly required for one tick.
        cpu.memory[0xFFFE] = 0x00.toSignedByte()
        cpu.memory[0xFFFF] = 0x00.toSignedByte()

        cpu.tick()

        // 7-cycle instruction, post-tick decrement leaves 6.
        assertThat(cpu.workCyclesLeft, equalTo(6))
    }

    @Test
    fun ldaImmediateSetsTwoCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0xA9.toSignedByte() // LDA immediate
        cpu.memory[0x0001] = 0x42.toSignedByte()

        cpu.tick()

        // 2-cycle instruction.
        assertThat(cpu.workCyclesLeft, equalTo(1))
    }

    @Test
    fun jsrSetsSixCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x20.toSignedByte() // JSR absolute
        cpu.memory[0x0001] = 0x00.toSignedByte() // low byte of target
        cpu.memory[0x0002] = 0x80.toSignedByte() // high byte of target

        cpu.tick()

        // 6-cycle instruction, post-tick decrement leaves 5.
        assertThat(cpu.workCyclesLeft, equalTo(5))
    }

    @Test
    fun rtsSetsSixCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x60.toSignedByte() // RTS
        // RTS pops a 16-bit return address off the stack. Pre-populate the
        // stack so pop() reads don't bounce off unmapped memory.
        cpu.registers.stackPointer = 0xFE.toSignedByte()
        cpu.memory[0x01FF] = 0x00.toSignedByte()
        cpu.memory[0x0100] = 0x80.toSignedByte()

        cpu.tick()

        // 6-cycle instruction, post-tick decrement leaves 5.
        assertThat(cpu.workCyclesLeft, equalTo(5))
    }

    @Test
    fun clcImpliedSetsTwoCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x18.toSignedByte() // CLC implied

        cpu.tick()

        // 2-cycle instruction.
        assertThat(cpu.workCyclesLeft, equalTo(1))
    }

    @Test
    fun nmiDispatchSetsSevenCycles() {
        // Issue #190: drive the NMI via a FakeInterruptController instead of
        // poking `cpu.memory.ppuAddressedMemory.nmiOccurred = true`. The
        // Fake honours the same 1-instruction-latency contract as the
        // production controller, so the timing assertions below are unchanged.
        val fakeController = FakeInterruptController()
        val cpu = Cpu(Memory.createWithApu().first, fakeController).apply { reset() }
        // NMI vector -> $0000 (a NOP, so we don't read garbage).
        cpu.memory[0xFFFA] = 0x00.toSignedByte()
        cpu.memory[0xFFFB] = 0x00.toSignedByte()
        // The CPU is parked in a NOP at $0000.
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0xEA.toSignedByte()

        // Arm NMI BEFORE any tick so the next instruction boundary is the
        // arm boundary. The Cpu is NOT idle (it's about to execute a NOP), so
        // the controller honours the 1-instruction latency:
        //   tick 1: ready, NMI not armed yet -> arms (returns null), then runs NOP
        //           (workCyclesLeft becomes 2-1=1 after the post-opcode decrement)
        //   tick 2: not ready (workCyclesLeft=1), just decrements to 0
        //   tick 3: ready, NMI is now armed -> services it (workCyclesLeft=7, then 6)
        // So 3 ticks are needed before the post-decrement 6 is visible.
        fakeController.armNmi()
        repeat(3) { cpu.tick() }

        // 7-cycle NMI service, post-tick decrement leaves 6.
        assertThat(cpu.workCyclesLeft, equalTo(6))
    }

    @Test
    fun bccBranchNotTakenSetsTwoCycles() {
        val cpu = freshCpu()
        // BCC $0010 with carry SET -> branch not taken (2 cycles).
        // BCC = branch on carry CLEAR, so carry=set means no branch.
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x90.toSignedByte() // BCC relative
        cpu.memory[0x0001] = 0x0E.toSignedByte() // offset +14 -> $0010
        cpu.processorStatus.carry = true

        cpu.tick()

        // 2-cycle branch (not taken), post-tick decrement leaves 1.
        assertThat(cpu.workCyclesLeft, equalTo(1))
        // PC advanced past the 2-byte instruction (relative offset NOT applied).
        assertThat(cpu.registers.programCounter, equalTo(0x0002.toSignedShort()))
    }

    @Test
    fun bccBranchTakenSetsThreeCycles() {
        val cpu = freshCpu()
        // BCC $0010 with carry CLEAR -> branch taken (3 cycles, no page cross).
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x90.toSignedByte() // BCC relative
        cpu.memory[0x0001] = 0x0E.toSignedByte() // offset +14 -> $0010
        cpu.processorStatus.carry = false

        cpu.tick()

        // 3-cycle branch (taken, no page cross), post-tick decrement leaves 2.
        assertThat(cpu.workCyclesLeft, equalTo(2))
        // PC followed the relative offset.
        assertThat(cpu.registers.programCounter, equalTo(0x0010.toSignedShort()))
    }

    @Test
    fun bccBranchTakenAcrossPageBoundarySetsFourCycles() {
        // Regression for issue #176 — taken relative branch across a page
        // boundary adds +1 cycle (4 cycles vs 3). BCC at PC=$10FD, offset $01
        // -> target $1100 crosses $10xx -> $11xx. carry CLEAR makes BCC take
        // the branch.
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x10FD.toSignedShort()
        cpu.memory[0x10FD] = 0x90.toSignedByte() // BCC relative
        cpu.memory[0x10FE] = 0x01.toSignedByte() // offset +1 -> $1100
        cpu.processorStatus.carry = false

        cpu.tick()

        // 4-cycle branch (taken, page-crossed), post-tick decrement leaves 3.
        assertThat(cpu.workCyclesLeft, equalTo(3))
        // PC followed the relative offset across the page.
        assertThat(cpu.registers.programCounter, equalTo(0x1100.toSignedShort()))
        // pageBoundaryFlag is set so saveState can persist the cross.
        assertThat(cpu.pageBoundaryFlag, equalTo(true))
    }

    // ----- Issue #175: helpers stamp per-addressing-mode cycle counts -----
    // The tests below exercise one representative addressing mode per refactored
    // helper and assert the real-6502 base cycle count. They are the regression
    // bar for the Opcodes.kt cycle-thread refactor: any future helper that
    // hardcodes a single value (or drops the `cycles: Int` parameter) will fail
    // here because the per-mode count is no longer constant.
    //
    // Out of scope for #175: page-boundary +1 cycles (issue #172), accumulator
    // variants of shift/rotate (0x0A/0x2A/0x4A/0x6A, already 2 cycles), and the
    // complex load/store family (LAX/SAX/AHX/etc). The tests intentionally stay
    // inside zero-page or non-page-crossing addressing so a future page-boundary
    // fix doesn't break them.

    @Test
    fun adcZeroPageSetsThreeCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x65.toSignedByte() // ADC $zp
        cpu.memory[0x0001] = 0x42.toSignedByte() // zp address
        cpu.memory[0x0042] = 0x10.toSignedByte() // operand
        cpu.registers.accumulator = 0x01.toSignedByte()

        cpu.tick()

        // 3-cycle instruction, post-tick decrement leaves 2.
        assertThat(cpu.workCyclesLeft, equalTo(2))
    }

    @Test
    fun adcAbsoluteSetsFourCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x6D.toSignedByte() // ADC $abs
        cpu.memory[0x0001] = 0x00.toSignedByte() // low byte
        cpu.memory[0x0002] = 0x02.toSignedByte() // high byte -> $0200 (RAM)
        cpu.memory[0x0200] = 0x10.toSignedByte() // operand
        cpu.registers.accumulator = 0x01.toSignedByte()

        cpu.tick()

        // 4-cycle instruction, post-tick decrement leaves 3.
        assertThat(cpu.workCyclesLeft, equalTo(3))
    }

    @Test
    fun sbcZeroPageSetsThreeCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0xE5.toSignedByte() // SBC $zp
        cpu.memory[0x0001] = 0x42.toSignedByte()
        cpu.memory[0x0042] = 0x10.toSignedByte()
        cpu.registers.accumulator = 0x20.toSignedByte()

        cpu.tick()

        // 3-cycle instruction, post-tick decrement leaves 2.
        assertThat(cpu.workCyclesLeft, equalTo(2))
    }

    @Test
    fun ldaZeroPageSetsThreeCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0xA5.toSignedByte() // LDA $zp
        cpu.memory[0x0001] = 0x42.toSignedByte()
        cpu.memory[0x0042] = 0x55.toSignedByte()

        cpu.tick()

        // 3-cycle instruction, post-tick decrement leaves 2.
        assertThat(cpu.workCyclesLeft, equalTo(2))
        // The load actually happened (proves the parametrised path ran).
        assertThat(cpu.registers.accumulator, equalTo(0x55.toSignedByte()))
    }

    @Test
    fun ldaAbsoluteSetsFourCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0xAD.toSignedByte() // LDA $abs
        cpu.memory[0x0001] = 0x00.toSignedByte()
        cpu.memory[0x0002] = 0x02.toSignedByte() // -> $0200 (RAM)
        cpu.memory[0x0200] = 0xAA.toSignedByte()

        cpu.tick()

        // 4-cycle instruction, post-tick decrement leaves 3.
        assertThat(cpu.workCyclesLeft, equalTo(3))
    }

    @Test
    fun ldaIndirectXSetsSixCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0xA1.toSignedByte() // LDA ($zp,X)
        cpu.memory[0x0001] = 0x20.toSignedByte() // zp base
        cpu.registers.indexX = 0x04.toSignedByte()
        // Pointer at $0024: low=$00, high=$02 -> target $0200 (RAM).
        cpu.memory[0x0024] = 0x00.toSignedByte()
        cpu.memory[0x0025] = 0x02.toSignedByte()
        cpu.memory[0x0200] = 0x77.toSignedByte()

        cpu.tick()

        // 6-cycle instruction, post-tick decrement leaves 5.
        // (Old hardcoded value was 2 -> would have been 1; the +4 delta is the
        //  regression bar for the `load` helper.)
        assertThat(cpu.workCyclesLeft, equalTo(5))
        assertThat(cpu.registers.accumulator, equalTo(0x77.toSignedByte()))
    }

    @Test
    fun staZeroPageSetsThreeCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x85.toSignedByte() // STA $zp
        cpu.memory[0x0001] = 0x42.toSignedByte()
        cpu.registers.accumulator = 0xAB.toSignedByte()

        cpu.tick()

        // 3-cycle instruction, post-tick decrement leaves 2.
        assertThat(cpu.workCyclesLeft, equalTo(2))
        // The store actually happened.
        assertThat(cpu.memory[0x0042], equalTo(0xAB.toSignedByte()))
    }

    @Test
    fun staIndirectYSetsSixCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x91.toSignedByte() // STA ($zp),Y
        cpu.memory[0x0001] = 0x20.toSignedByte() // zp base
        // Pointer at $0020: low=$00, high=$02 -> target $0200 (RAM).
        cpu.memory[0x0020] = 0x00.toSignedByte()
        cpu.memory[0x0021] = 0x02.toSignedByte()
        cpu.registers.indexY = 0x04.toSignedByte()
        cpu.registers.accumulator = 0xCD.toSignedByte()

        cpu.tick()

        // 6-cycle instruction, post-tick decrement leaves 5.
        // (Old hardcoded value was 4 -> would have been 3; +2 delta.)
        assertThat(cpu.workCyclesLeft, equalTo(5))
        // STA wrote to $0204 ($0200 + Y=4).
        assertThat(cpu.memory[0x0204], equalTo(0xCD.toSignedByte()))
    }

    @Test
    fun andZeroPageSetsThreeCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x25.toSignedByte() // AND $zp
        cpu.memory[0x0001] = 0x42.toSignedByte()
        cpu.memory[0x0042] = 0x0F.toSignedByte()
        cpu.registers.accumulator = 0xFF.toSignedByte()

        cpu.tick()

        // 3-cycle instruction, post-tick decrement leaves 2.
        assertThat(cpu.workCyclesLeft, equalTo(2))
        // A AND 0x0F = 0x0F.
        assertThat(cpu.registers.accumulator, equalTo(0x0F.toSignedByte()))
    }

    @Test
    fun eorImmediateSetsTwoCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x49.toSignedByte() // EOR #imm
        cpu.memory[0x0001] = 0xFF.toSignedByte()
        cpu.registers.accumulator = 0x0F.toSignedByte()

        cpu.tick()

        // 2-cycle instruction, post-tick decrement leaves 1.
        assertThat(cpu.workCyclesLeft, equalTo(1))
        assertThat(cpu.registers.accumulator, equalTo(0xF0.toSignedByte()))
    }

    @Test
    fun cmpZeroPageSetsThreeCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0xC5.toSignedByte() // CMP $zp
        cpu.memory[0x0001] = 0x42.toSignedByte()
        cpu.memory[0x0042] = 0x10.toSignedByte()
        cpu.registers.accumulator = 0x10.toSignedByte()

        cpu.tick()

        // 3-cycle instruction, post-tick decrement leaves 2.
        assertThat(cpu.workCyclesLeft, equalTo(2))
        // Equal -> zero flag set, carry set.
        assertThat(cpu.processorStatus.zero, equalTo(true))
        assertThat(cpu.processorStatus.carry, equalTo(true))
    }

    @Test
    fun cpyImmediateSetsTwoCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0xC0.toSignedByte() // CPY #imm
        cpu.memory[0x0001] = 0x05.toSignedByte()
        cpu.registers.indexY = 0x10.toSignedByte()

        cpu.tick()

        // 2-cycle instruction, post-tick decrement leaves 1.
        assertThat(cpu.workCyclesLeft, equalTo(1))
    }

    @Test
    fun aslZeroPageSetsFiveCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x06.toSignedByte() // ASL $zp
        cpu.memory[0x0001] = 0x42.toSignedByte()
        cpu.memory[0x0042] = 0x40.toSignedByte()

        cpu.tick()

        // 5-cycle instruction, post-tick decrement leaves 4.
        // (Old hardcoded value was 2 -> would have been 1; +3 delta.)
        assertThat(cpu.workCyclesLeft, equalTo(4))
        // 0x40 << 1 = 0x80, carry cleared (bit 7 of original was 0).
        assertThat(cpu.memory[0x0042], equalTo(0x80.toSignedByte()))
        assertThat(cpu.processorStatus.carry, equalTo(false))
    }

    @Test
    fun lsrAbsoluteSetsSixCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x4E.toSignedByte() // LSR $abs
        cpu.memory[0x0001] = 0x00.toSignedByte()
        cpu.memory[0x0002] = 0x02.toSignedByte() // -> $0200 (RAM)
        cpu.memory[0x0200] = 0x01.toSignedByte()

        cpu.tick()

        // 6-cycle instruction, post-tick decrement leaves 5.
        // (Old hardcoded value was 2 -> would have been 1; +4 delta.)
        assertThat(cpu.workCyclesLeft, equalTo(5))
        // 0x01 >> 1 = 0x00, carry set (bit 0 of original was 1).
        assertThat(cpu.memory[0x0200], equalTo(0x00.toSignedByte()))
        assertThat(cpu.processorStatus.carry, equalTo(true))
    }

    @Test
    fun rolZeroPageXSetsSixCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x36.toSignedByte() // ROL $zp,X
        cpu.memory[0x0001] = 0x40.toSignedByte() // zp base
        cpu.registers.indexX = 0x02.toSignedByte()
        cpu.memory[0x0042] = 0x80.toSignedByte()
        cpu.processorStatus.carry = true

        cpu.tick()

        // 6-cycle instruction, post-tick decrement leaves 5.
        // (Old hardcoded value was 2 -> would have been 1; +4 delta.)
        assertThat(cpu.workCyclesLeft, equalTo(5))
        // (0x80 << 1) | 1 = 0x101, low byte 0x01, carry = 1 (bit 7 of original).
        assertThat(cpu.memory[0x0042], equalTo(0x01.toSignedByte()))
        assertThat(cpu.processorStatus.carry, equalTo(true))
    }

    @Test
    fun rorAbsoluteXSetsSevenCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x7E.toSignedByte() // ROR $abs,X
        cpu.memory[0x0001] = 0x00.toSignedByte()
        cpu.memory[0x0002] = 0x02.toSignedByte() // -> $0200 base (RAM)
        cpu.registers.indexX = 0x04.toSignedByte()
        cpu.memory[0x0204] = 0x01.toSignedByte()
        cpu.processorStatus.carry = true

        cpu.tick()

        // 7-cycle instruction, post-tick decrement leaves 6.
        // (Old hardcoded value was 2 -> would have been 1; +5 delta.)
        assertThat(cpu.workCyclesLeft, equalTo(6))
        // (0x01 >> 1) | 0x80 = 0x80, carry = 1 (bit 0 of original).
        assertThat(cpu.memory[0x0204], equalTo(0x80.toSignedByte()))
        assertThat(cpu.processorStatus.carry, equalTo(true))
    }

    @Test
    fun incZeroPageSetsFiveCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0xE6.toSignedByte() // INC $zp
        cpu.memory[0x0001] = 0x42.toSignedByte()
        cpu.memory[0x0042] = 0x0F.toSignedByte()

        cpu.tick()

        // 5-cycle instruction, post-tick decrement leaves 4.
        // (Old hardcoded value was 6 -> would have been 5; -1 delta is also a
        //  regression signal that the parametrised path is wired.)
        assertThat(cpu.workCyclesLeft, equalTo(4))
        assertThat(cpu.memory[0x0042], equalTo(0x10.toSignedByte()))
    }

    @Test
    fun decAbsoluteXSetsSevenCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0xDE.toSignedByte() // DEC $abs,X
        cpu.memory[0x0001] = 0x00.toSignedByte()
        cpu.memory[0x0002] = 0x02.toSignedByte() // -> $0200 base (RAM)
        cpu.registers.indexX = 0x04.toSignedByte()
        cpu.memory[0x0204] = 0x10.toSignedByte()

        cpu.tick()

        // 7-cycle instruction, post-tick decrement leaves 6.
        // (Old hardcoded `unary = 6` -> would have been 5; +1 cycle delta
        //  is the regression bar proving DEC $abs,X is no longer under-
        //  counted by the parametrised path.)
        assertThat(cpu.workCyclesLeft, equalTo(6))
        assertThat(cpu.memory[0x0204], equalTo(0x0F.toSignedByte()))
    }

    @Test
    fun bitAbsoluteSetsFourCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0x2C.toSignedByte() // BIT $abs
        cpu.memory[0x0001] = 0x00.toSignedByte()
        cpu.memory[0x0002] = 0x02.toSignedByte() // -> $0200 (RAM)
        cpu.memory[0x0200] = 0xC0.toSignedByte() // bits 6+7 set -> V+N
        cpu.registers.accumulator = 0x00.toSignedByte()

        cpu.tick()

        // 4-cycle instruction, post-tick decrement leaves 3.
        // (Old hardcoded value was 3 -> would have been 2; +1 delta.)
        assertThat(cpu.workCyclesLeft, equalTo(3))
        // A=0 AND M=0xC0 = 0 -> zero set, V and N mirror M's bits 6 and 7.
        assertThat(cpu.processorStatus.zero, equalTo(true))
        assertThat(cpu.processorStatus.overflow, equalTo(true))
        assertThat(cpu.processorStatus.negative, equalTo(true))
    }
}
