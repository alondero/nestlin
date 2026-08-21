package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.cpu.opcode.Branch
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Regression bar for issue #11 — `pageBoundaryFlag` is set but never consumed.
 *
 * **Historical context (issue #176).** The original fix for the page-cross
 * +1 cycle accounting on a taken relative branch set the flag and then
 * ALSO assigned `workCyclesLeft = 4` inline. The commit message at the
 * time noted: *"pageBoundaryFlag is write-only and effectively dead — a
 * separate cleanup PR could remove the now-dead field with a SaveState
 * VERSION bump."* Issue #11 is that cleanup PR.
 *
 * **What this test pins down.** The Branch opcode must use the SAME
 * flag-driven cycle pattern as every other opcode family (Arithmetic,
 * LoadStore, ReadModifyWrite, Logic, UnofficialNop, UnofficialLoadStore,
 * UnofficialCombined): the base cycle count is the value, and the
 * `pageBoundaryFlag` is the signal that +1 should be added. Setting the
 * flag must affect the cycle count, whether the flag was set by the
 * Branch's own page-cross check or by some other mechanism.
 *
 * **Why a NEW test file.** `WorkCyclesLeftConsistencyTest.bccBranchTakenAcrossPageBoundarySetsFourCycles`
 * already pins the OBSERVABLE behaviour (4 cycles + flag set) for a
 * page-crossing branch. That test passes with both the old "inline
 * workCyclesLeft = 4" implementation AND the refactored "flag-driven"
 * implementation. It cannot tell the two apart. This file's tests CAN —
 * they verify the flag is dead-READ by the cycle calculation, not just
 * coincidentally set.
 */
class Issue11PageBoundaryFlagConsumedTest {

    // Factory (issue #22): wire Memory + Apu so cpu.memory.apu is non-null when
    // the IRQ-check path reads it on every tick.
    private fun freshCpu() = Cpu(Memory.createWithApu().first).apply { reset() }

    /**
     * The bug-exposing test.
     *
     * Setup: BCC at PC=$10FD with offset $00 → target $10FF (NO page-cross).
     * The condition (carry clear) makes the branch taken. The branch's own
     * page-cross check returns false, so the Branch never sets the flag
     * itself.
     *
     * We pre-set the flag to `true` (simulating that some upstream code
     * flagged a page-cross). The two implementations behave differently:
     *
     *   - Current ("inline workCyclesLeft = 4"): the page-cross check
     *     doesn't fire, so the inline override is skipped. Cycle = 3.
     *     **The pre-set flag is silently ignored. THIS IS THE BUG.**
     *
     *   - Refactored ("flag-driven cycle"): the cycle is computed as
     *     `3 + if (pageBoundaryFlag) 1 else 0`, so the pre-set flag
     *     DOES add the cycle. Cycle = 4.
     *
     * This test FAILS on the current code and PASSES after the refactor.
     */
    @Test
    fun branchConsumesPageBoundaryFlagWhenSetExternally() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x10FD.toSignedShort()
        cpu.memory[0x10FD] = 0x90.toSignedByte() // BCC relative
        cpu.memory[0x10FE] = 0x00.toSignedByte() // offset 0 -> $10FF (no page-cross)
        cpu.processorStatus.carry = false        // branch taken

        // Pre-set the flag (the bug: this used to be silently ignored).
        // We use the Branch class directly so the Cpu.tick() flag-reset
        // doesn't undo our setup.
        cpu.pageBoundaryFlag = true
        val branch = Branch({ !it.processorStatus.carry }, "BCC")
        branch.evaluate(cpu)

        // Refactored: 3 base cycles + 1 (flag consumed) = 4.
        // Current: 3 (flag ignored — the inline override only fires on page-cross).
        assertThat(cpu.workCyclesLeft, equalTo(4))
        assertThat(cpu.pageBoundaryFlag, equalTo(true))
    }

    /**
     * Control: the same branch WITHOUT the pre-set flag must still take 3
     * cycles (no page-cross, no flag, no bonus). This guards against a
     * sloppy refactor that adds +1 unconditionally.
     */
    @Test
    fun branchNotPageCrossedNoFlagKeepsThreeCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x10FD.toSignedShort()
        cpu.memory[0x10FD] = 0x90.toSignedByte() // BCC relative
        cpu.memory[0x10FE] = 0x00.toSignedByte() // offset 0 -> $10FF (no page-cross)
        cpu.processorStatus.carry = false        // branch taken

        // Flag is NOT pre-set (default false from freshCpu's reset).
        val branch = Branch({ !it.processorStatus.carry }, "BCC")
        branch.evaluate(cpu)

        assertThat(cpu.workCyclesLeft, equalTo(3))
        assertThat(cpu.pageBoundaryFlag, equalTo(false))
    }

    /**
     * The OBSERVABLE behaviour pinned by #176, restated here under the
     * new flag-driven implementation: branch crosses a page, the flag is
     * set INTERNALLY by the Branch's own page-cross check, cycle = 4.
     *
     * Runs via `cpu.tick()` (not direct evaluate) so the post-tick
     * decrement is in scope — same observable behaviour as the #176
     * regression test, and therefore the same regression bar.
     */
    @Test
    fun branchPageCrossedInternallySetsFourCyclesAndFlag() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x10FD.toSignedShort()
        cpu.memory[0x10FD] = 0x90.toSignedByte() // BCC relative
        cpu.memory[0x10FE] = 0x01.toSignedByte() // offset +1 -> $1100 (crosses page)
        cpu.processorStatus.carry = false        // branch taken

        cpu.tick()

        // 4-cycle branch (taken, page-crossed), post-tick decrement leaves 3.
        assertThat(cpu.workCyclesLeft, equalTo(3))
        assertThat(cpu.registers.programCounter, equalTo(0x1100.toSignedShort()))
        assertThat(cpu.pageBoundaryFlag, equalTo(true))
    }

    /**
     * Branch taken on the SAME page: the flag must NOT be set, cycle = 3.
     * This is the regression bar for HAS NOT regressing the per-mode
     * baseline cycle count.
     */
    @Test
    fun branchTakenSamePageNoFlagThreeCycles() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x10FD.toSignedShort()
        cpu.memory[0x10FD] = 0x90.toSignedByte() // BCC relative
        cpu.memory[0x10FE] = 0x00.toSignedByte() // offset 0 -> $10FF (same page)
        cpu.processorStatus.carry = false        // branch taken

        cpu.tick()

        // 3-cycle branch (taken, same page), post-tick decrement leaves 2.
        assertThat(cpu.workCyclesLeft, equalTo(2))
        assertThat(cpu.registers.programCounter, equalTo(0x10FF.toSignedShort()))
        assertThat(cpu.pageBoundaryFlag, equalTo(false))
    }

    /**
     * Branch NOT taken: cycle = 2, flag is never set (page-cross check
     * doesn't run because the offset byte is consumed without applying).
     */
    @Test
    fun branchNotTakenTwoCyclesNoFlagChange() {
        val cpu = freshCpu()
        cpu.registers.programCounter = 0x10FD.toSignedShort()
        cpu.memory[0x10FD] = 0x90.toSignedByte() // BCC relative
        cpu.memory[0x10FE] = 0x01.toSignedByte() // offset (ignored)
        cpu.processorStatus.carry = true         // condition FALSE → not taken

        cpu.tick()

        // 2-cycle branch (not taken), post-tick decrement leaves 1.
        assertThat(cpu.workCyclesLeft, equalTo(1))
        // PC advanced past the 2-byte instruction.
        assertThat(cpu.registers.programCounter, equalTo(0x10FF.toSignedShort()))
        // Flag is unchanged from reset (false).
        assertThat(cpu.pageBoundaryFlag, equalTo(false))
    }
}
