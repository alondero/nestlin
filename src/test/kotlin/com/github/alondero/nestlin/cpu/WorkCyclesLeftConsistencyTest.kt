package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.Memory
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

    private fun freshCpu() = Cpu(Memory()).apply { reset() }

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
        val cpu = freshCpu()
        // NMI vector -> $0000 (a NOP, so we don't read garbage).
        cpu.memory[0xFFFA] = 0x00.toSignedByte()
        cpu.memory[0xFFFB] = 0x00.toSignedByte()
        // Enable NMI generation (PPUCTRL bit 7) and mark vblank as occurred,
        // matching what the PPU would do at scanline 241.
        cpu.memory[0x2000] = 0x80.toSignedByte()
        cpu.memory.ppuAddressedMemory.nmiOccurred = true
        // The CPU is parked in a NOP at $0000.
        cpu.registers.programCounter = 0x0000.toSignedShort()
        cpu.memory[0x0000] = 0xEA.toSignedByte()

        // Scheduler timing for NMI dispatch (see Cpu.checkAndHandleNmi):
        //   tick 1: ready, NMI not armed yet -> arms (returns false), then runs NOP
        //           (workCyclesLeft becomes 2-1=1 after the post-opcode decrement)
        //   tick 2: not ready (workCyclesLeft=1), just decrements to 0
        //   tick 3: ready, NMI is now armed -> services it (workCyclesLeft=7, then 6)
        // So 3 ticks are needed before the post-decrement 6 is visible.
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
}
