package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Reusable seven-cycle RESET entry sequencer.
 *
 * Releasing the 6502's RESET line runs the same interrupt entry sequence as
 * IRQ/NMI with the three stack *writes* turned into *reads* (the write-enable
 * is gated, the stack-pointer arithmetic is not — S still decrements three
 * times, which is what produces the documented power-up value of $FD from the
 * power-on S of $00):
 *
 * ```
 *   #  address   R/W description
 *   1  PC        R   fetch opcode (discarded; PC is meaningless at reset)
 *   2  $PC+1     R   read next instruction byte (discarded; PC incremented
 *                       by the 6502 during the previous cycle)
 *   3  $0100+S   R   read from stack (discarded)
 *   4  $0100+S-1 R   read from stack (discarded)
 *   5  $0100+S-2 R   read from stack (discarded)
 *   6  $FFFC     R   fetch low byte of reset vector
 *   7  $FFFD     R   fetch high byte of reset vector; S -= 3, I flag set
 * ```
 *
 * The object is allocated once by [Cpu] and reset for each reset event, exactly
 * like [MicrocodedInstruction] / [MicrocodedInterrupt]. Every call to [step]
 * performs one real CPU-bus operation; the vector bytes come through the live
 * bus so mappers and the open-bus data-bus latch observe them. The PC (and the
 * final stack pointer) only take effect on the last cycle, so no instruction or
 * interrupt dispatch can happen mid-sequence.
 */
internal class MicrocodedReset(private val cpu: Cpu) {
    private var startPc = 0
    private var initialSp = 0
    private var phase = 0
    private var vectorLow = 0
    var isComplete = false
        private set

    fun begin() {
        startPc = cpu.registers.programCounter.toUnsignedInt()
        initialSp = cpu.registers.stackPointer.toUnsignedInt()
        phase = 0
        vectorLow = 0
        isComplete = false
    }

    fun step() {
        check(!isComplete) { "completed CPU reset sequence stepped again" }
        phase++
        when (phase) {
            1 -> cpu.memory[startPc]
            2 -> cpu.memory[(startPc + 1) and 0xFFFF]
            3 -> {
                cpu.memory[0x100 or initialSp]
                // S decrements on each push/read cycle. Write back so a debugger
                // or memory inspector observing SP mid-sequence sees the
                // hardware value, not the still-pre-decrement SP.
                cpu.registers.stackPointer = ((initialSp - 1) and 0xFF).toSignedByte()
            }
            4 -> {
                cpu.memory[0x100 or ((initialSp - 1) and 0xFF)]
                cpu.registers.stackPointer = ((initialSp - 2) and 0xFF).toSignedByte()
            }
            5 -> {
                cpu.memory[0x100 or ((initialSp - 2) and 0xFF)]
                cpu.registers.stackPointer = ((initialSp - 3) and 0xFF).toSignedByte()
            }
            6 -> vectorLow = cpu.memory[0xFFFC].toUnsignedInt()
            7 -> completeNow()
        }
    }

    /**
     * Perform the phase-7 completion writeback (PC from `$FFFC`/`$FFFD`, I
     * flag forced, sequence marked complete). Called either by [step] on
     * the seventh cycle of a normal run or by the dispatch loop when a
     * save was restored with `phase == 7` and `isComplete == true` (a save
     * taken on the completing tick — unreachable in production, possible
     * in tests or future regressions).
     */
    fun completeNow() {
        val vectorHigh = cpu.memory[0xFFFD].toUnsignedInt()
        cpu.registers.programCounter = (vectorLow or (vectorHigh shl 8)).toSignedShort()
        cpu.processorStatus.interruptDisable = true
        // SP writeback: matches the hardware value after the three stack
        // decrements. If the sequence reached this point through step()
        // (cycles 3/4/5) the SP is already at its final value; if it was
        // restored from a save with phase == 7, we need to derive it from
        // the latched initialSp.
        cpu.registers.stackPointer = ((initialSp - 3) and 0xFF).toSignedByte()
        isComplete = true
    }

    fun save(out: DataOutput) {
        out.writeShort(startPc)
        out.writeByte(initialSp)
        out.writeByte(phase)
        out.writeByte(vectorLow)
    }

    fun load(input: DataInput) {
        startPc = input.readUnsignedShort()
        initialSp = input.readUnsignedByte()
        phase = input.readUnsignedByte()
        vectorLow = input.readUnsignedByte()
        // `phase == 7` is the completion phase; restoring with it set means
        // the sequence has already done all the bus work and just needs the
        // PC/SP/I-flag writeback. The dispatch loop in `Cpu.tick()` skips
        // `step()` when `isComplete` is already true, so this state is safe
        // to restore (production saves can never carry `phase == 7` because
        // the completing tick clears `activeReset` on the same cycle, but
        // hand-crafted or future-regression saves may).
        isComplete = phase == 7
    }
}
