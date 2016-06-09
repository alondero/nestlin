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
                val next = readShortAtPC()
                registers.programCounter--.toUnsignedInt().apply {
                    push((this shr 8).toSignedByte())
                    push((this and 0xFF).toSignedByte())
                    registers.programCounter = next
                }

                //  TODO: Takes 6 cycles
            }
        }

        // JMP - Jump to location
        map[0x4c] = Opcode {
            it.apply {
                val temp = registers.programCounter
                registers.programCounter = readShortAtPC()

                if (registers.programCounter == (temp-1).toSignedShort()) {
                    // TODO: Set idle
                }

                //  TODO: Takes 3 cycles
            }
        }

        // LDX - Load X with M
        map[0xa2] = Opcode {
            it.apply {
                registers.indexX = readByteAtPC().apply {
                    processorStatus.resolveZeroAndNegativeFlags(this)
                }

                //  TODO: Takes 2 cycles
            }
        }

        // NOP - No Operation
        map[0xea] = Opcode {
            // TODO: Takes 2 cycles
        }

        // LDA - Load A with M
        map[0xa9] = Opcode {
            it.apply {
                registers.accumulator = readByteAtPC().apply {
                    processorStatus.resolveZeroAndNegativeFlags(this)
                }

                //  TODO: Takes 2 cycles
            }
        }

        //  BIT - Test Bits in M with A
        map[0x24] = Opcode {
            it.apply {
                memory[readByteAtPC().toUnsignedInt()].apply {
                    val data = this.toUnsignedInt()
                    processorStatus.zero = (data and registers.accumulator.toUnsignedInt()) == 0
                    processorStatus.negative = (data and 0b10000000) != 0
                    processorStatus.overflow = (data and 0b01000000) != 0
                }
            }
            //  TODO: Takes 3 cycles
        }

        //  RTS - Return from Subroutine
        map[0x60] = Opcode {
            it.apply {
                val lowByte = pop().toUnsignedInt()
                val highByte = pop().toUnsignedInt()
                it.registers.programCounter = ((lowByte and 0xff) or (highByte shl 8)).toSignedShort()
            }
            //  TODO: Takes 6 cycles
        }

        //  PLA - Pull A from Stack
        map[0x68] = Opcode {
            it.apply {
                registers.accumulator = pop()
                processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)
                //  TODO: Takes 4 cycles
            }
        }

        //  ADC - Add M to A with Carry
        map[0x69] = Opcode {
            it.apply {
                //  No need to test for decimal mode on NES CPU!
                val next = readByteAtPC()
                val currentAccumulator = registers.accumulator

                var result = currentAccumulator.toUnsignedInt() + next.toUnsignedInt()
                if (processorStatus.carry) result++

                registers.accumulator = result.toSignedByte()
                processorStatus.carry = result > 0xFF
                processorStatus.overflow = ((currentAccumulator.toUnsignedInt() xor next.toUnsignedInt()) and 0x80 == 0x00) &&
                        ((currentAccumulator.toUnsignedInt() xor registers.accumulator.toUnsignedInt()) and 0x80 == 0x80)
                processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)

                // TODO: Takes 2 cycles
            }
        }

        //  PLP - Pull ProcessorStatus from Stack
        map[0x28] = Opcode {
            it.apply {
                //  TODO: Handle some interrupt?
                processorStatus.toFlags(pop())
                //  TODO: Takes 4 cycles
            }
        }

        //  CMP - Compare M and A
        map[0xc9] = Opcode {
            it.apply {
                val accumulator = registers.accumulator
                val mem = readByteAtPC()
                val comparison: Int = accumulator - mem
                processorStatus.apply {
                    negative = comparison.toSignedByte().isBitSet(7)
                    zero = comparison == 0
                    carry = accumulator.toUnsignedInt() >= mem.toUnsignedInt()
                }

                //  TODO: Takes 2 cycles
            }
        }

        //  Branch Operations
        map[0x10] = branchOp { !it.processorStatus.negative } // BPL - Branch on Result Plus
        map[0x30] = branchOp { it.processorStatus.negative } // BMI - Branch on Result Minus
        map[0x50] = branchOp { !it.processorStatus.overflow } // BVC - Branch on Overflow Clear
        map[0x70] = branchOp { it.processorStatus.overflow } // BVS - Branch on Overflow Set
        map[0x90] = branchOp { !it.processorStatus.carry } // BCC - Branch on Carry Clear
        map[0xb0] = branchOp { it.processorStatus.carry } // BCS - Branch on Carry Set
        map[0xd0] = branchOp { !it.processorStatus.zero } // BNE - Branch on Result Not Zero
        map[0xf0] = branchOp { it.processorStatus.zero } // BEQ - Branch on Result Zero

        //  Store operations
        map[0x85] = storeOp { it.registers.accumulator } // STA - Store A in M
        map[0x86] = storeOp { it.registers.indexX } // STX - Store X in M

        //  Set and Clear operations
        map[0x18] = setOp { it.processorStatus.carry = false } // CLC - Clear Carry Flag
        map[0x38] = setOp { it.processorStatus.carry = true } // SEC - Set Carry Flag
        map[0x78] = setOp { it.processorStatus.interruptDisable = true } // SEI - Set Interrupt Disable Status
        map[0xd8] = setOp { it.processorStatus.decimalMode = false } // CLD - Clear Decimal mode
        map[0xf8] = setOp { it.processorStatus.decimalMode = true } // SED - Set Decimal mode

        //  Push operations
        map[0x08] = pushOp { it.processorStatus.asByte() } //  PHP - Push Processor Status on Stack
        map[0x48] = pushOp { it.registers.accumulator } // PHA - Push A on Stack

        //  Clear flag operations
        map[0xb8] = clearOp { it.processorStatus.overflow = false } // CLV - Clear Overflow Flag

        //  Operations over Accumulator
        map[0x09] = opWithA { a, m -> ((a or m) and 0xff) }  //  ORA - "OR" M with A
        map[0x29] = opWithA { a, m -> (a and m) } // AND - "AND" M with A
        map[0x49] = opWithA { a, m -> ((a xor m) and 0xff) } //  EOR - "XOR" M with A
    }

    private fun branchOp(flag: (Cpu) -> Boolean) = Opcode {
        it.apply {
            branch(flag(it))
            //  TODO: Takes 2 cycles
        }
    }

    private fun storeOp(value: (Cpu) -> Byte) = Opcode {
        it.apply {
            memory[readByteAtPC().toUnsignedInt()] = value(it)
            // TODO: Takes 3 cycles
        }
    }

    private fun setOp(op: (Cpu) -> Unit) = Opcode {
        it.apply {
            op(it)
            //  TODO: Takes 2 cycles
        }
    }

    private fun pushOp(value: (Cpu) -> Byte) = Opcode {
        it.apply {
            push(value(it))
            //  TODO: Takes 3 cycles
        }
    }

    private fun clearOp(op: (Cpu) -> Unit) = Opcode {
        it.apply {
            op(it)
            //  TODO: Takes 2 cycles
        }
    }

    private fun opWithA(op: (Int, Int) -> Int) = Opcode {
        it.apply {
            registers.accumulator = op(registers.accumulator.toUnsignedInt(), readByteAtPC().toUnsignedInt()).toSignedByte().apply {
                processorStatus.resolveZeroAndNegativeFlags(this)
            }

            //  TODO: Takes 2 cycles
        }
    }

    operator fun get(code: Int): Opcode? = map[code]
}

class Opcode(val op: (Cpu) -> Unit)

class UnhandledOpcodeException(opcodeVal: Int) : Throwable("Opcode ${Integer.toHexString(opcodeVal)} not implemented")