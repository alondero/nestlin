package com.github.alondero.nestlin

import java.util.*

class Opcodes {
    val map = HashMap<Int, Opcode>()

    init {
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
        map[0x85] = storeZeroPagedOp { it.registers.accumulator } // STA - Store A in M (Zero Paged)
        map[0x86] = storeZeroPagedOp { it.registers.indexX } // STX - Store X in M (Zero Paged)
        map[0x8e] = storeOp { it.registers.indexX } // STX - Store X in M

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

        //  Load operations
        map[0xa9] = load { registers, mem -> registers.accumulator = mem } // LDA - Load A with M
        map[0xa2] = load { registers, mem -> registers.indexX = mem } // LDX - Load X with M
        map[0xa0] = load { registers, mem -> registers.indexY = mem } // LDY - Load Y with M
        map[0xae] = load ({it.memory[it.readShortAtPC().toUnsignedInt()]}) { registers, mem -> registers.indexX = mem }// LDX - Load X with M
        map[0xad] = load ({it.memory[it.readShortAtPC().toUnsignedInt()]}) { registers, mem -> registers.accumulator = mem }// LDA - Load A with M

        //  Compare operations
        map[0xc9] = compareOp { it.accumulator } //  CMP - Compare M and A
        map[0xc0] = compareOp { it.indexY } // CPY - Compare M and Y
        map[0xe0] = compareOp { it.indexX } // CPX - Compare M and X

        //  Transfer operations
        map[0xa8] = transfer ({it.accumulator}) { r, acc -> r.indexY = acc } //  TAY - Transfer A to Y
        map[0xaa] = transfer ({it.accumulator}) { r, acc -> r.indexX = acc } //  TAX - Transfer A to X
        map[0x98] = transfer ({it.indexY}) { r, y -> r.accumulator = y } // TYA - Transfer Y to A
        map[0x8a] = transfer ({it.indexX}) { r, x -> r.accumulator = x } // TXA - Transfer X to A
        map[0xba] = transfer ({it.stackPointer}) { r, sp -> r.indexX = sp } // TSX - Transfer Stack Pointer to X
        map[0x9a] = Opcode {
            it.apply {
                it.registers.stackPointer = it.registers.indexX
                //  TODO: 2 cycles
            }
        } // TXS - Transfer X to Stack Pointer (doesn't set program counter)

        //  BRK - Force Break
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

        //  JSR - Jump to Location Save Return Address
        map[0x20] = Opcode {
            it.apply {
                val next = readShortAtPC()
                registers.programCounter.dec().toUnsignedInt().apply {
                    push((this shr 8).toSignedByte())
                    push((this and 0xFF).toSignedByte())
                    registers.programCounter = next
                    registers.programCounter
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

        // NOP - No Operation
        map[0xea] = Opcode {
            // TODO: Takes 2 cycles
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
                it.registers.programCounter++
//                println("Returning to Program Counter ${it.registers.programCounter.toHexString()}")
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
                processorStatus.carry = (result shr 8) != 0
                processorStatus.overflow = ((currentAccumulator.toUnsignedInt() xor next.toUnsignedInt()) and 0x80 == 0x00) &&
                        ((currentAccumulator.toUnsignedInt() xor registers.accumulator.toUnsignedInt()) and 0x80 == 0x80)
                processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)

                // TODO: Takes 2 cycles
            }
        }

        // SBC - Subtract M from A with Borrow
        map[0xe9] = Opcode {
            it.apply {
                //  No need to test for decimal mode on NES CPU!
                val next = readByteAtPC()
                val currentAccumulator = registers.accumulator

                var result = currentAccumulator.toUnsignedInt() - next.toUnsignedInt()
                if (!processorStatus.carry) result--

                registers.accumulator = (result and 0xFF).toSignedByte()
                processorStatus.carry = (result shr 8) == 0
                processorStatus.overflow = ((currentAccumulator.toUnsignedInt() xor next.toUnsignedInt()) and 0x80 == 0x80) &&
                        ((currentAccumulator.toUnsignedInt() xor registers.accumulator.toUnsignedInt()) and 0x80 == 0x80)
                processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)

                //  TODO: Takes 6 cycles
            }
        }

        //  TODO: Refactor this so not so C&P
        //  INY - Increment Y By One
        map[0xc8] = Opcode {
            it.apply {
                registers.indexY = ((registers.indexY + 1) and 0xFF).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.indexY)
                //  TODO: Takes 2 cycles
            }
        }

        //  DEY - Decrement Y By One
        map[0x88] = Opcode {
            it.apply {
                registers.indexY = ((registers.indexY - 1) and 0xFF).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.indexY)
                //  TODO: Takes 2 cycles
            }
        }

        //  INX - Increment X By One
        map[0xe8] = Opcode {
            it.apply {
                registers.indexX = ((registers.indexX + 1) and 0xFF).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.indexX)
                //  TODO: Takes 2 cycles
            }
        }

        //  DEX - Decrement X By One
        map[0xca] = Opcode {
            it.apply {
                registers.indexX = ((registers.indexX - 1) and 0xFF).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.indexX)
                //  TODO: Takes 2 cycles
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

        //  RTI - Return from Interrupt
        map[0x40] = Opcode {
            it.apply {
                processorStatus.toFlags(pop())
                registers.programCounter = (pop().toUnsignedInt() or (pop().toUnsignedInt() shl 8)).toSignedShort()
                //  TODO: Takes 6 cycles
            }
        }

        //  LSR - Shift Right One Bit (M or A)
        map[0x4a] = Opcode {
            it.apply {
                processorStatus.carry = registers.accumulator.isBitSet(0)
                registers.accumulator = (registers.accumulator.toUnsignedInt() shr 1).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)
                //  TODO: Takes 2 cycles
            }
        }

        //  ASL - Shift Left One Bit (M or A)
        map[0x0a] = Opcode {
            it.apply {
                processorStatus.carry = registers.accumulator.isBitSet(7)
                registers.accumulator = (registers.accumulator.toUnsignedInt() shl 1).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)
                //  TODO: Takes 2 cycles
            }
        }

        //  ROR - Rotate One Bit Right (M or A)
        map[0x6a] = Opcode {
            it.apply {
                val oldCarry = processorStatus.carry
                processorStatus.carry = registers.accumulator.isBitSet(0)
                registers.accumulator = ((registers.accumulator.toUnsignedInt() shr 1) or (if (oldCarry) 0x80 else 0)).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)
                //  TODO: Takes 2 cycles
            }
        }

        //  ROL - Rotate One Bit Left (M or A)
        map[0x2a] = Opcode {
            it.apply {
                val newAccumulator = (registers.accumulator.toUnsignedInt() shl 1) or (if (processorStatus.carry) 1 else 0)
                processorStatus.carry = (newAccumulator and 0xFF00) > 0
                registers.accumulator = (newAccumulator and 0xFF).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)
                //  TODO: Takes 2 cycles
            }
        }

    }

    private fun branchOp(flag: (Cpu) -> Boolean) = Opcode {
        it.apply {
            branch(flag(it))
            //  TODO: Takes 2 cycles
        }
    }

    private fun storeZeroPagedOp(value: (Cpu) -> Byte) = Opcode {
        it.apply {
            memory[readByteAtPC().toUnsignedInt()] = value(it)
            // TODO: Takes 3 cycles
        }
    }

    private fun storeOp(value: (Cpu) -> Byte) = Opcode {
        it.apply {
            memory[readShortAtPC().toUnsignedInt()] = value(it)
            // TODO: Takes 4 cycles
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

    private fun load(mem: (Cpu) -> Byte = {it.readByteAtPC()}, op: (Registers, Byte) -> Unit) = Opcode {
        it.apply {
            mem(it).apply {
                op(it.registers, this)
                processorStatus.resolveZeroAndNegativeFlags(this)
            }

            //  TODO: Takes 2 cycles
        }
    }

    private fun compareOp(op: (Registers) -> Byte) = Opcode {
        it.apply {
            val register = op(it.registers)
            val mem = readByteAtPC()
            val comparison: Int = register - mem
            processorStatus.apply {
                negative = comparison.toSignedByte().isBitSet(7)
                zero = comparison == 0
                carry = register.toUnsignedInt() >= mem.toUnsignedInt()
            }

            //  TODO: Takes 2 cycles
        }
    }

    private fun transfer(from: (Registers) -> Byte, transfer: (Registers, Byte) -> Unit) = Opcode {
        it.apply {
            from(it.registers).apply {
                processorStatus.resolveZeroAndNegativeFlags(this)
                transfer(registers, this)
            }
            // TODO: Takes 2 cycles
        }
    }

    operator fun get(code: Int): Opcode? = map[code]
}

class Opcode(val op: (Cpu) -> Unit)

class UnhandledOpcodeException(opcodeVal: Int) : Throwable("Opcode ${"%02X".format(opcodeVal)} not implemented")