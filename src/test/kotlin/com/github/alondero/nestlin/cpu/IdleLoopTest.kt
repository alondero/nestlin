package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.testutil.FakeInterruptController
import com.github.alondero.nestlin.testutil.testRom
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Regression tests for issue #15 — the `idle` flag is set when the CPU enters a
 * branch-to-self / JMP-to-self spin loop, but `tick()` must actually honour it.
 *
 * The flag is a *park*, not a *halt*: while idle the CPU stops re-executing the
 * spin instruction (saving host cycles), but it must still poll for NMI/IRQ every
 * tick so an interrupt can break the loop — which is exactly how real games sit in
 * `JMP *` waiting for the vblank NMI.
 *
 * Issue #190: the NMI-driving test (`nmiBreaksOutOfTheIdleParkAndClearsTheFlag`)
 * now uses a `FakeInterruptController` and calls `fake.armNmi()` directly. The
 * previous shape (`cpu.memory.ppuAddressedMemory.nmiOccurred = true`) reached
 * three layers deep into PPU state to drive a CPU scenario — the fake replaces
 * that leaky seam.
 */
class IdleLoopTest {

    @Test
    fun branchToSelfStopsReExecutingTheInstruction() {
        // Factory (issue #22): wire Memory + Apu so cpu.memory.apu is non-null when
        // the IRQ-check path reads it on every tick.
        val cpu = Cpu(Memory.createWithApu().first).apply {
            reset()
            registers.programCounter = 0x0000.toSignedShort()
            // BEQ -2  (branches to its own opcode when the zero flag is set)
            memory[0x0000] = 0xF0.toSignedByte()
            memory[0x0001] = 0xFE.toSignedByte()
            processorStatus.zero = true
        }

        // Run long enough for the branch to execute once and enter the idle park.
        repeat(4) { cpu.tick() }
        assertThat(cpu.idle, equalTo(true))

        val executedBeforeParking = cpu.getInstructionCount()

        // While parked, no further instructions should be fetched/executed no
        // matter how many cycles tick over.
        repeat(1000) { cpu.tick() }

        assertThat(cpu.getInstructionCount(), equalTo(executedBeforeParking))
        assertThat(cpu.registers.programCounter, equalTo(0x0000.toSignedShort()))
    }

    @Test
    fun jumpToSelfSetsIdle() {
        val cpu = Cpu(Memory.createWithApu().first).apply {
            reset()
            registers.programCounter = 0x0200.toSignedShort()
            // JMP $0200  (jumps to its own opcode — an unconditional spin loop)
            memory[0x0200] = 0x4C.toSignedByte()
            memory[0x0201] = 0x00.toSignedByte()
            memory[0x0202] = 0x02.toSignedByte()
        }

        cpu.tick()

        assertThat(cpu.idle, equalTo(true))
        assertThat(cpu.registers.programCounter, equalTo(0x0200.toSignedShort()))
    }

    @Test
    fun nmiBreaksOutOfTheIdleParkAndClearsTheFlag() {
        // Issue #190: drive the NMI via the FakeInterruptController seam.
        // No PPU poke needed — the fake's armNmi() is the producer side of the
        // controller interface, and the Cpu dispatches via pendingInterrupt(idle).
        val fakeController = FakeInterruptController()
        val cpu = Cpu(Memory.createWithApu().first, fakeController).apply {
            currentGame = GamePak(spinLoopRom())
            reset()
        }

        // Reset vector points at the JMP-to-self spin loop at $C000.
        repeat(6) { cpu.tick() }
        assertThat(cpu.idle, equalTo(true))

        // Arm an NMI edge — the production wiring would set PPU vblank + PPUCTRL
        // bit 7; the fake just trusts armNmi() and dispatches on the next tick.
        // Idle=true skips the 1-instruction latency (no in-flight instruction
        // to finish), so this single tick both arms AND dispatches.
        fakeController.armNmi()

        cpu.tick()

        // The interrupt must wake the CPU: idle cleared and PC redirected to the
        // NMI vector ($C010 in this ROM).
        assertThat(cpu.idle, equalTo(false))
        assertThat(cpu.registers.programCounter, equalTo(0xC010.toSignedShort()))
    }

    /**
     * A minimal 16KB NROM (mapper 0) image:
     *  - $C000: JMP $C000  (spin loop)
     *  - $C010: RTI        (NMI handler)
     *  - reset vector -> $C000, NMI vector -> $C010
     */
    private fun spinLoopRom(): ByteArray = testRom {
        prgKb = 16
        chrKb = 0
        prg[0x0000] = 0x4C.toByte() // JMP $C000
        prg[0x0001] = 0x00.toByte()
        prg[0x0002] = 0xC0.toByte()
        prg[0x0010] = 0x40.toByte() // RTI
        prg[0x3FFA] = 0x10.toByte() // NMI vector low  -> $C010
        prg[0x3FFB] = 0xC0.toByte() // NMI vector high
        prg[0x3FFC] = 0x00.toByte() // reset vector low -> $C000
        prg[0x3FFD] = 0xC0.toByte() // reset vector high
    }
}