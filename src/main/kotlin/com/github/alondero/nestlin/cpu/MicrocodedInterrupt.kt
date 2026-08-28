package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/** Reusable seven-cycle IRQ/NMI entry sequencer. */
internal class MicrocodedInterrupt(private val cpu: Cpu) {
    private var kind = InterruptKind.NMI
    private var startPc = 0
    private var initialSp = 0
    private var phase = 0
    private var vectorLow = 0
    var isComplete = false
        private set

    fun begin(kind: InterruptKind) {
        this.kind = kind
        startPc = cpu.registers.programCounter.toUnsignedInt()
        initialSp = cpu.registers.stackPointer.toUnsignedInt()
        phase = 0
        vectorLow = 0
        isComplete = false
    }

    fun step() {
        phase++
        val vector = if (kind == InterruptKind.NMI) 0xFFFA else 0xFFFE
        when (phase) {
            1, 2 -> cpu.memory[startPc]
            3 -> cpu.memory[0x100 or initialSp] = (startPc shr 8).toSignedByte()
            4 -> cpu.memory[0x100 or ((initialSp - 1) and 0xFF)] = startPc.toSignedByte()
            5 -> {
                val status = cpu.processorStatus.asByte().toUnsignedInt() and 0xEF
                cpu.memory[0x100 or ((initialSp - 2) and 0xFF)] = status.toSignedByte()
            }
            6 -> vectorLow = cpu.memory[vector].toUnsignedInt()
            7 -> {
                val vectorHigh = cpu.memory[vector + 1].toUnsignedInt()
                cpu.registers.stackPointer = ((initialSp - 3) and 0xFF).toSignedByte()
                cpu.registers.programCounter = (vectorLow or (vectorHigh shl 8)).toSignedShort()
                cpu.processorStatus.interruptDisable = true
                when (kind) {
                    InterruptKind.NMI -> cpu.incrementNmiCount()
                    InterruptKind.IRQ -> cpu.incrementIrqCount()
                }
                isComplete = true
            }
        }
    }

    fun save(out: DataOutput) {
        out.writeByte(kind.ordinal)
        out.writeShort(startPc)
        out.writeByte(initialSp)
        out.writeByte(phase)
        out.writeByte(vectorLow)
    }

    fun load(input: DataInput) {
        kind = InterruptKind.entries[input.readUnsignedByte()]
        startPc = input.readUnsignedShort()
        initialSp = input.readUnsignedByte()
        phase = input.readUnsignedByte()
        vectorLow = input.readUnsignedByte()
        isComplete = false
    }
}
