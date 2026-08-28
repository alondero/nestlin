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
 *   2  PC        R   read next instruction byte (discarded)
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
            1, 2 -> cpu.memory[startPc]
            3 -> cpu.memory[0x100 or initialSp]
            4 -> cpu.memory[0x100 or ((initialSp - 1) and 0xFF)]
            5 -> cpu.memory[0x100 or ((initialSp - 2) and 0xFF)]
            6 -> vectorLow = cpu.memory[0xFFFC].toUnsignedInt()
            7 -> {
                val vectorHigh = cpu.memory[0xFFFD].toUnsignedInt()
                cpu.registers.stackPointer = ((initialSp - 3) and 0xFF).toSignedByte()
                cpu.registers.programCounter = (vectorLow or (vectorHigh shl 8)).toSignedShort()
                cpu.processorStatus.interruptDisable = true
                isComplete = true
            }
        }
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
        // The completing tick writes `phase == 7` then clears `activeReset`,
        // so a save taken on that tick could legitimately carry phase == 7
        // (no save would follow it in production, but a rewind capture is
        // possible). Treat it as already complete so the next tick doesn't
        // silently no-op on a non-existent phase-8 arm.
        isComplete = phase == 7
    }
}
