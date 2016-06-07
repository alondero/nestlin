package com.github.alondero.nestlin

import java.util.*

class Opcodes {
    val map = HashMap<Int, Opcode>()

    init {
        map[0x00] = Opcode {
            it.apply {
                processorStatus.breakCommand = true
                registers.programCounter++
                registers.programCounter.toUnsignedInt().apply {
                    push((this shr 8).toSignedByte())
                    push((this and 0xFF).toSignedByte())
                }
                push(processorStatus.asByte())
                registers.programCounter = memory[0xFFFE, 0xFFFF]
                processorStatus.interruptDisable = true

                //  TODO: Takes 7 cycles
            }
        }
        map[0x20] = Opcode {
            it.apply {
                val next = memory[registers.programCounter++.toUnsignedInt(), registers.programCounter++.toUnsignedInt()]

                registers.programCounter--.toUnsignedInt().apply {
                    push((this shr 8).toSignedByte())
                    push((this and 0xFF).toSignedByte())
                    registers.programCounter = next
                }

                //  TODO: Takes 6 cycles
            }
        }
        map[0x78] = Opcode {
            it.processorStatus.interruptDisable = true
            //  TODO: Takes 2 cycles
        }
        map[0xd8] = Opcode {
            it.processorStatus.decimalMode = false
            //  TODO: Takes 2 cycles
        }
        map[0x4c] = Opcode {
            it.apply {
                val temp = registers.programCounter
                registers.programCounter = memory[registers.programCounter++.toUnsignedInt(), it.registers.programCounter++.toUnsignedInt()]

                if (registers.programCounter == (temp-1).toSignedShort()) {
                    // TODO: Set idle
                }

                //  TODO: Takes 3 cycles
            }
        }
        map[0xa2] = Opcode {
            it.apply {
                registers.indexX = memory[registers.programCounter++.toUnsignedInt()].apply {
                    processorStatus.setFlags(this)
                }

                //  TODO: Takes 2 cycles
            }
        }
        map[0x86] = Opcode {
            it.apply {
                memory[memory[registers.programCounter++.toUnsignedInt()].toUnsignedInt()] = registers.indexX
                // TODO: Takes 3 cycles
            }
        }

        map[0xea] = Opcode {
            // TODO: Takes 2 cycles
        }

        map[0x38] = Opcode {
            it.processorStatus.carry = true
            // TODO: Takes 2 cycles
        }
    }

    operator fun get(code: Int): Opcode? = map[code]
}

class Opcode(val op: (Cpu) -> Unit)

class UnhandledOpcodeException(opcodeVal: Int) : Throwable("Opcode ${Integer.toHexString(opcodeVal)} not implemented")