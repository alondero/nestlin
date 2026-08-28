package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.Memory.CpuBusAccess
import com.github.alondero.nestlin.Memory.CpuBusOperation.READ
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.testutil.FakeInterruptController
import com.github.alondero.nestlin.testutil.spinLoopRom
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Power / soft reset as a tick-by-tick bus sequence (follow-up from PR #300).
 *
 * On real 6502 hardware the RESET line does not teleport the PC to the reset
 * vector — releasing RESET runs the same seven-cycle interrupt entry sequence
 * with the stack writes turned into reads:
 *
 * ```
 *   #  address    R/W description
 *   1  PC         R   fetch opcode (discarded; PC is meaningless at reset)
 *   2  PC         R   read next instruction byte (discarded)
 *   3  $0100+S    R   read from stack (discarded)         -.
 *   4  $0100+S-1  R   read from stack (discarded)          > S decrements 3 times
 *   5  $0100+S-2  R   read from stack (discarded)         -'
 *   6  $FFFC      R   fetch low byte of reset vector
 *   7  $FFFD      R   fetch high byte of reset vector; S -= 3, I flag set
 * ```
 *
 * The sequence is resumable, save-state-serialised CPU micro-state exactly like
 * [MicrocodedInstruction] / [MicrocodedInterrupt]: one real bus operation per
 * tick, observable through `memory.cpuBusObserver`, and the CPU dispatches no
 * instruction or interrupt until the vector has been taken.
 *
 * Power-cycle vs soft-reset (RESET button) semantics:
 *  - Power-cycle: RAM wiped, A/X/Y zeroed, S starts at $00 (the sequence's three
 *    decrements land it at the documented $FD), all status flags cleared except I.
 *  - Soft reset: RAM, A, X, Y and SP preserved (S still decrements by 3 through
 *    the sequence); the I flag is forced set by the vector fetch.
 */
class CpuResetSequenceTest {

    @Test
    fun `power reset performs the seven-cycle bus sequence and lands on the reset vector`() {
        val fixture = fixture()
        fixture.cpu.reset()

        repeat(7) { fixture.cpu.tick() }

        // PC=$0000 after the power-on register wipe, so cycle 1 reads $0000
        // (the opcode byte) and cycle 2 reads $0001 (PC incremented by the
        // 6502 during cycle 1). S=$00 so the stack reads descend
        // $0100 -> $01FF -> $01FE.
        assertThat(
            fixture.trace,
            equalTo(
                listOf(
                    access(0x0000, 0xFF),
                    access(0x0001, 0xFF),
                    access(0x0100, 0xFF),
                    access(0x01FF, 0xFF),
                    access(0x01FE, 0xFF),
                    access(0xFFFC, 0x00),
                    access(0xFFFD, 0xC0),
                )
            )
        )
        assertThat(fixture.cpu.registers.programCounter, equalTo(0xC000.toSignedShort()))
        // The three sequence decrements produce the documented power-up SP.
        assertThat(fixture.cpu.registers.stackPointer, equalTo(0xFD.toSignedByte()))
        // The vector fetch is the interrupt-disable point, same as IRQ/NMI entry.
        assertThat(fixture.cpu.processorStatus.interruptDisable, equalTo(true))
        assertThat(fixture.cpu.executionInFlight, equalTo(false))
        assertThat(fixture.cpu.workCyclesLeft, equalTo(0))
    }

    @Test
    fun `reset sequence leaves cycleCount at zero so golden-log alignment is preserved`() {
        val fixture = fixture()
        fixture.cpu.reset()

        repeat(7) { fixture.cpu.tick() }

        // nestest.log's first instruction is logged at CYC:0; the golden-log
        // contract counts cycles from the completed reset sequence, not from
        // the reset() call.
        assertThat(fixture.cpu.cycleCount, equalTo(0))
    }

    @Test
    fun `soft reset decrements the stack pointer through three stack reads`() {
        val fixture = fixture()
        fixture.cpu.reset()
        fixture.cpu.finishExecution()
        fixture.cpu.registers.stackPointer = 0x10.toSignedByte()
        fixture.cpu.registers.programCounter = 0xC000.toSignedShort()
        fixture.trace.clear()

        fixture.cpu.softReset()
        repeat(7) { fixture.cpu.tick() }

        // The two discarded fetches read the opcode at the parked PC ($C000:
        // the JMP spin loop) and the operand byte at $C001. The stack reads
        // descend from S=$10.
        assertThat(
            fixture.trace,
            equalTo(
                listOf(
                    access(0xC000, 0x4C),
                    access(0xC001, 0x00),
                    access(0x0110, 0xFF),
                    access(0x010F, 0xFF),
                    access(0x010E, 0xFF),
                    access(0xFFFC, 0x00),
                    access(0xFFFD, 0xC0),
                )
            )
        )
        assertThat(fixture.cpu.registers.stackPointer, equalTo(0x0D.toSignedByte()))
        assertThat(fixture.cpu.registers.programCounter, equalTo(0xC000.toSignedShort()))
    }

    @Test
    fun `soft reset preserves RAM and CPU registers except the interrupt disable flag`() {
        val fixture = fixture()
        fixture.cpu.reset()
        fixture.cpu.finishExecution()
        fixture.cpu.registers.accumulator = 0x42.toSignedByte()
        fixture.cpu.registers.indexX = 0x13.toSignedByte()
        fixture.cpu.registers.indexY = 0x77.toSignedByte()
        fixture.cpu.processorStatus.carry = true
        fixture.cpu.processorStatus.interruptDisable = false
        fixture.memory[0x0123] = 0x7E.toSignedByte()
        fixture.memory[0x0300] = 0x99.toSignedByte()

        fixture.cpu.softReset()
        fixture.cpu.finishExecution()

        assertThat(fixture.cpu.registers.accumulator, equalTo(0x42.toSignedByte()))
        assertThat(fixture.cpu.registers.indexX, equalTo(0x13.toSignedByte()))
        assertThat(fixture.cpu.registers.indexY, equalTo(0x77.toSignedByte()))
        assertThat(fixture.cpu.processorStatus.carry, equalTo(true))
        assertThat(fixture.cpu.processorStatus.interruptDisable, equalTo(true))
        assertThat(fixture.memory[0x0123], equalTo(0x7E.toSignedByte()))
        assertThat(fixture.memory[0x0300], equalTo(0x99.toSignedByte()))
    }

    @Test
    fun `power reset still wipes internal RAM to the post-power-up pattern`() {
        val fixture = fixture()
        fixture.cpu.reset()
        fixture.cpu.finishExecution()
        fixture.memory[0x0123] = 0x7E.toSignedByte()

        fixture.cpu.reset()
        fixture.cpu.finishExecution()

        assertThat(fixture.memory[0x0123], equalTo(0xFF.toSignedByte()))
    }

    @Test
    fun `a pending NMI cannot hijack the reset sequence`() {
        val fakeController = FakeInterruptController()
        val fixture = fixture(controller = fakeController)
        fakeController.armNmi()
        fixture.cpu.reset()

        repeat(7) { fixture.cpu.tick() }

        // The vector fetch must complete before any interrupt dispatch — the
        // controller is never polled mid-sequence, so the NMI stays pending
        // (not even armed) until the first post-reset instruction boundary.
        assertThat(fixture.cpu.nmiCount, equalTo(0))
        assertThat(fixture.cpu.registers.programCounter, equalTo(0xC000.toSignedShort()))

        // The first post-reset instruction runs (the JMP spin loop) and the
        // armed NMI dispatches at the following boundary, waking the park.
        fixture.cpu.executeNext()
        assertThat(fixture.cpu.nmiCount, equalTo(0))
        fixture.cpu.executeNext()
        assertThat(fixture.cpu.nmiCount, equalTo(1))
    }

    @Test
    fun `reset aborts an in-flight OAM DMA before running the sequence`() {
        val fixture = fixture()
        fixture.cpu.reset()
        fixture.cpu.finishExecution()
        // $4014 write starts a 513+ cycle OAM DMA from page $03.
        fixture.memory[0x4014] = 0x03.toSignedByte()
        repeat(10) { fixture.cpu.tick() }
        fixture.trace.clear()

        fixture.cpu.softReset()
        repeat(7) { fixture.cpu.tick() }

        // Only the seven reset reads appear — no residual DMA source reads or
        // $2004 writes may interleave with the sequence. S is $FD after the
        // power-on sequence, so the stack reads descend from there. Cycle 2
        // reads $C001 (the 0x00 operand byte of the JMP spin loop).
        assertThat(
            fixture.trace,
            equalTo(
                listOf(
                    access(0xC000, 0x4C),
                    access(0xC001, 0x00),
                    access(0x01FD, 0xFF),
                    access(0x01FC, 0xFF),
                    access(0x01FB, 0xFF),
                    access(0xFFFC, 0x00),
                    access(0xFFFD, 0xC0),
                )
            )
        )
        assertThat(fixture.cpu.executionInFlight, equalTo(false))
    }

    @Test
    fun `reset wakes a parked idle CPU`() {
        val fixture = fixture()
        fixture.cpu.reset()
        fixture.cpu.finishExecution()
        // Park the CPU as a KIL / spin-loop park would.
        fixture.cpu.idle = true

        fixture.cpu.softReset()
        fixture.cpu.finishExecution()

        assertThat(fixture.cpu.idle, equalTo(false))
        assertThat(fixture.cpu.registers.programCounter, equalTo(0xC000.toSignedShort()))
    }

    @Test
    fun `mid-sequence save restores the remaining reset bus cycles`() {
        val fixture = fixture()
        fixture.cpu.reset()
        fixture.cpu.tick()
        fixture.cpu.tick() // two of seven sequence phases done
        val state = ByteArrayOutputStream().also { buffer ->
            DataOutputStream(buffer).use(fixture.cpu::saveState)
        }.toByteArray()

        val restored = fixture()
        // The CPU save block doesn't carry RAM or the cartridge wiring (both
        // live in other SaveState sub-blocks), so give the restored fixture
        // the same post-power-cycle RAM pattern and mapper before restoring
        // the mid-sequence latches.
        restored.memory.clear()
        restored.memory.readCartridge(restored.cpu.currentGame!!)
        DataInputStream(ByteArrayInputStream(state)).use(restored.cpu::loadState)
        restored.trace.clear()
        restored.cpu.finishExecution()

        // Phases 3-7 resume exactly where the save left off.
        assertThat(
            restored.trace,
            equalTo(
                listOf(
                    access(0x0100, 0xFF),
                    access(0x01FF, 0xFF),
                    access(0x01FE, 0xFF),
                    access(0xFFFC, 0x00),
                    access(0xFFFD, 0xC0),
                )
            )
        )
        assertThat(restored.cpu.registers.programCounter, equalTo(0xC000.toSignedShort()))
        assertThat(restored.cpu.registers.stackPointer, equalTo(0xFD.toSignedByte()))
    }

    @Test
    fun `a save loaded with phase 7 (save taken on the completing tick) does not crash on the next tick`() {
        // Regression for the load-time logic bomb: an older draft of
        // MicrocodedReset.load() set `isComplete = (phase == 7)` which made
        // the very next tick call step() and throw
        // "completed CPU reset sequence stepped again".
        //
        // A legitimate production save can never carry `phase == 7` because
        // the completing tick clears `activeReset` on the same cycle, but a
        // hand-crafted save (or a future regression) might. The dispatch loop
        // must tolerate the state and finish the sequence cleanly.
        val fixture = fixture()
        fixture.cpu.reset()
        repeat(7) { fixture.cpu.tick() } // sequence completes, activeReset=false
        assertThat(fixture.cpu.executionInFlight, equalTo(false))

        // Hand-craft a save where phase is 7 but the activeReset latch is
        // true, simulating a save taken on the very tick the sequence
        // completed. We can't reach this through public Cpu.reset() + tick(),
        // so write the latches directly via reflection-equivalent paths.
        val fixture2 = fixture()
        fixture2.cpu.reset()
        repeat(6) { fixture2.cpu.tick() } // six phases done; phase=7, isComplete=false
        fixture2.trace.clear()

        // Force the "save mid-completion with phase 7" shape and restore it.
        val bytes = serialisePhase7Completion(fixture2.cpu)
        val restored = fixture()
        restored.memory.clear()
        restored.memory.readCartridge(restored.cpu.currentGame!!)
        DataInputStream(ByteArrayInputStream(bytes)).use(restored.cpu::loadState)

        // The next tick must NOT throw — it should drive the completion
        // bookkeeping (clear activeReset, zero cycleCount, activate test-ROM
        // automation) without calling resetState.step() again.
        restored.cpu.tick()

        assertThat(restored.cpu.executionInFlight, equalTo(false))
        assertThat(restored.cpu.registers.programCounter, equalTo(0xC000.toSignedShort()))
    }

    /**
     * Serialise a CPU save state with the reset sequencer at `phase == 7` and
     * `activeReset == true` — the precise shape a save would have if taken on
     * the very tick the sequence completed. The completing branch in Cpu.tick
     * clears activeReset on the same tick, so production saves can never carry
     * this shape; the test simulates it by writing the latches directly.
     *
     * The v10 CPU block layout (issue #301) ends with the optional reset
     * sequencer payload: a boolean flag and, when true, a 5-byte latch
     * (startPc short, initialSp byte, phase byte, vectorLow byte). We bypass
     * saveState() and emit exactly the bytes a save on the completing tick
     * would carry.
     */
    private fun serialisePhase7Completion(cpu: com.github.alondero.nestlin.cpu.Cpu): ByteArray {
        val buf = ByteArrayOutputStream()
        DataOutputStream(buf).use { dos ->
            // The 18-byte prefix the v10 CPU block writes regardless of state.
            dos.writeByte(cpu.registers.stackPointer.toInt())
            dos.writeByte(cpu.registers.accumulator.toInt())
            dos.writeByte(cpu.registers.indexX.toInt())
            dos.writeByte(cpu.registers.indexY.toInt())
            dos.writeShort(cpu.registers.programCounter.toInt())
            dos.writeByte(cpu.processorStatus.asByte().toInt())
            dos.writeBoolean(cpu.processorStatus.breakCommand)
            dos.writeInt(cpu.workCyclesLeft)
            dos.writeBoolean(cpu.pageBoundaryFlag)
            dos.writeBoolean(cpu.idle)
            dos.writeInt(0) // reserved
            // v10 fields the >= 10 branch consumes.
            dos.writeInt(cpu.cycleCount)
            dos.writeBoolean(false) // activeInstruction
            dos.writeBoolean(false) // activeInterrupt
            dos.writeInt(0)          // genericStallCycles
            dos.writeBoolean(false) // oamDmaActive
            // Reset sequencer payload: activeReset=true with phase == 7.
            dos.writeBoolean(true)
            dos.writeShort(0xC000)   // startPc
            dos.writeByte(0)          // initialSp
            dos.writeByte(7)          // phase == 7
            dos.writeByte(0x00)       // vectorLow
        }
        return buf.toByteArray()
    }

    // ---------------------------------------------------------------------

    private fun fixture(controller: InterruptController = FakeInterruptController()): Fixture {
        val (memory, _) = Memory.createWithApu()
        val cpu = Cpu(memory, controller)
        cpu.currentGame = GamePak(spinLoopRom(), "reset-sequence-test")
        val trace = mutableListOf<CpuBusAccess>()
        memory.cpuBusObserver = { trace.add(it) }
        return Fixture(cpu, memory, trace)
    }

    /** One expected READ bus access with the byte the bus returned. */
    private fun access(address: Int, value: Int) =
        CpuBusAccess(READ, address, value.toSignedByte())

    private class Fixture(
        val cpu: Cpu,
        val memory: Memory,
        val trace: MutableList<CpuBusAccess>,
    )
}
