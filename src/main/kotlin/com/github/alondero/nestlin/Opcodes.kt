package com.github.alondero.nestlin

import java.util.*

class Opcodes {
    val map = HashMap<Int, Opcode>()

    init {
        map[0x00] = Opcode() {
            it.processorStatus.breakCommand = true
            it.registers.programCounter++
            it.registers.programCounter.toUnsignedInt().apply {
                it.push((this shr 8).toSignedByte())
                it.push((this and 0xFF).toSignedByte())
            }
            it.push(it.processorStatus.asByte()) // TODO: Correctly set this
            it.registers.programCounter = it.memory[0xFFFE, 0xFFFF]
            it.processorStatus.interruptDisable = true

            //  Takes 7 cycles
        }
        map[0x20] = Opcode() {
            val next = it.memory[it.registers.programCounter++.toUnsignedInt(), it.registers.programCounter++.toUnsignedInt()]

            it.registers.programCounter--.toUnsignedInt().apply {
                it.push((this shr 8).toSignedByte())
                it.push((this and 0xFF).toSignedByte())
                it.registers.programCounter = next
            }
            //  TODO: Takes 6 cycles
        }
        map[0x78] = Opcode() {
            it.processorStatus.interruptDisable = true
            //  TODO: Takes 2 cycles
        }
        map[0xd8] = Opcode() {
            it.processorStatus.decimalMode = false
            //  TODO: Takes 2 cycles
        }
        map[0x4c] = Opcode() {
            val temp = it.registers.programCounter
            it.registers.programCounter = it.memory[it.registers.programCounter++.toUnsignedInt(), it.registers.programCounter++.toUnsignedInt()]

            if (it.registers.programCounter == (temp-1).toSignedShort()) {
                //  TODO: Set idle
            }
            //  TODO: Takes 3 cycles
        }
        map[0xa2] = Opcode() {
            it.registers.indexX = it.memory[it.registers.programCounter++.toUnsignedInt()].apply {
                it.processorStatus.setFlags(this)
            }

            //  TODO: Takes 2 cycles
        }
        map[0x86] = Opcode() {
            it.memory[it.memory[it.registers.programCounter++.toUnsignedInt()].toUnsignedInt()] = it.registers.indexX
            // TODO: Takes 3 cycles
        }

        map[0xea] = Opcode() {
            // TODO: Takes 2 cycles
        }
    }

    operator fun get(code: Int): Opcode? = map[code]
}

class Opcode(val op: (Cpu) -> Unit)

class UnhandledOpcodeException(opcodeVal: Int) : Throwable("Opcode ${Integer.toHexString(opcodeVal)} not implemented")