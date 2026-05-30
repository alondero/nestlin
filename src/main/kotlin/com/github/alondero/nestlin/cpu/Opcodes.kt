package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.*
import com.github.alondero.nestlin.cpu.addressing.*
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

        //  STA/STX/STY - Store operations
        map[0x81] = storeOp (indirectXAdr()) { it.registers.accumulator }
        map[0x84] = storeOp (zeroPagedAdr()) { it.registers.indexY }
        map[0x85] = storeOp (zeroPagedAdr()) { it.registers.accumulator }
        map[0x86] = storeOp (zeroPagedAdr()) { it.registers.indexX }
        map[0x8c] = storeOp (absoluteAdr())  { it.registers.indexY }
        map[0x8d] = storeOp (absoluteAdr())  { it.registers.accumulator }
        map[0x8e] = storeOp (absoluteAdr())  { it.registers.indexX }
        map[0x91] = storeOp (indirectYAdr()) { it.registers.accumulator }
        map[0x94] = storeOp (zeroPagedAdr {it.registers.indexX}) { it.registers.indexY }
        map[0x95] = storeOp (zeroPagedAdr {it.registers.indexX}) { it.registers.accumulator }
        map[0x96] = storeOp (zeroPagedAdr {it.registers.indexY}) { it.registers.indexX }
        map[0x99] = storeOp (absoluteAdr {it.registers.indexY}) { it.registers.accumulator }
        map[0x9d] = storeOp (absoluteAdr {it.registers.indexX}) { it.registers.accumulator }

        //  Set and Clear operations
        map[0x18] = setOp { it.processorStatus.carry = false } // CLC - Clear Carry Flag
        map[0x38] = setOp { it.processorStatus.carry = true } // SEC - Set Carry Flag
        map[0x58] = setOp { it.processorStatus.interruptDisable = false } // CLI - Clear Interrupt Disable
        map[0x78] = setOp { it.processorStatus.interruptDisable = true } // SEI - Set Interrupt Disable Status
        map[0xd8] = setOp { it.processorStatus.decimalMode = false } // CLD - Clear Decimal mode
        map[0xf8] = setOp { it.processorStatus.decimalMode = true } // SED - Set Decimal mode

        //  ADC - Add M to A with Carry
        map[0x61] = addToA (indirectX())
        map[0x65] = addToA (zeroPaged())
        map[0x69] = addToA (immediate())
        map[0x6d] = addToA (absolute())
        map[0x71] = addToA (indirectY())
        map[0x75] = addToA (zeroPaged {it.registers.indexX})
        map[0x79] = addToA (absolute {it.registers.indexY})
        map[0x7d] = addToA (absolute {it.registers.indexX})

        // SBC - Subtract M from A with Borrow
        map[0xe1] = subtractFromA (indirectX())
        map[0xe5] = subtractFromA (zeroPaged())
        map[0xe9] = subtractFromA (immediate())
        map[0xed] = subtractFromA (absolute())
        map[0xf1] = subtractFromA (indirectY())
        map[0xf5] = subtractFromA (zeroPaged{it.registers.indexX})
        map[0xf9] = subtractFromA (absolute {it.registers.indexY})
        map[0xfd] = subtractFromA (absolute {it.registers.indexX})

        //  Push operations
        map[0x08] = pushOp { it.processorStatus.asByte() } //  PHP - Push Processor Status on Stack
        map[0x48] = pushOp { it.registers.accumulator } // PHA - Push A on Stack

        //  Clear flag operations
        map[0xb8] = clearOp { it.processorStatus.overflow = false } // CLV - Clear Overflow Flag

        //  ORA/AND/EOR Operations over Accumulator
        map[0x01] = opWithA (indirectX()) { a, m -> ((a or m) and 0xff) }
        map[0x05] = opWithA (zeroPaged()) { a, m -> ((a or m) and 0xff) }
        map[0x09] = opWithA (immediate()) { a, m -> ((a or m) and 0xff) }
        map[0x0d] = opWithA (absolute())  { a, m -> ((a or m) and 0xff) }
        map[0x11] = opWithA (indirectY()) { a, m -> ((a or m) and 0xff) }
        map[0x15] = opWithA (zeroPaged {it.registers.indexX }) { a, m -> ((a or m) and 0xff) }
        map[0x19] = opWithA (absolute {it.registers.indexY}) { a, m -> ((a or m) and 0xff) }
        map[0x1d] = opWithA (absolute {it.registers.indexX}) { a, m -> ((a or m) and 0xff) }
        map[0x21] = opWithA (indirectX()) { a, m -> (a and m) }
        map[0x25] = opWithA (zeroPaged()) { a, m -> (a and m) }
        map[0x29] = opWithA (immediate()) { a, m -> (a and m) }
        map[0x2d] = opWithA (absolute())  { a, m -> (a and m) }
        map[0x31] = opWithA (indirectY()) { a, m -> (a and m) }
        map[0x35] = opWithA (zeroPaged {it.registers.indexX}) { a, m -> (a and m) }
        map[0x39] = opWithA (absolute {it.registers.indexY}) { a, m -> (a and m) }
        map[0x3d] = opWithA (absolute {it.registers.indexX}) { a, m -> (a and m) }
        map[0x41] = opWithA (indirectX()) { a, m -> ((a xor m) and 0xff) }
        map[0x45] = opWithA (zeroPaged()) { a, m -> ((a xor m) and 0xff) }
        map[0x49] = opWithA (immediate()) { a, m -> ((a xor m) and 0xff) }
        map[0x4d] = opWithA (absolute())  { a, m -> ((a xor m) and 0xff) }
        map[0x51] = opWithA (indirectY()) { a, m -> ((a xor m) and 0xff) }
        map[0x55] = opWithA (zeroPaged {it.registers.indexX})  { a, m -> ((a xor m) and 0xff) }
        map[0x59] = opWithA (absolute {it.registers.indexY})  { a, m -> ((a xor m) and 0xff) }
        map[0x5d] = opWithA (absolute {it.registers.indexX}) { a, m -> ((a xor m) and 0xff) }

        //  LDA/LDX/LDY Load operations
        map[0xa0] = load (immediate()) { registers, mem -> registers.indexY = mem }
        map[0xa1] = load (indirectX()) { registers, mem -> registers.accumulator = mem }
        map[0xa2] = load (immediate()) { registers, mem -> registers.indexX = mem }
        map[0xa4] = load (zeroPaged()) { registers, mem -> registers.indexY = mem }
        map[0xa5] = load (zeroPaged()) { registers, mem -> registers.accumulator = mem }
        map[0xa6] = load (zeroPaged()) { registers, mem -> registers.indexX = mem }
        map[0xa9] = load (immediate()) { registers, mem -> registers.accumulator = mem }
        map[0xac] = load (absolute()) { registers, mem -> registers.indexY = mem }
        map[0xad] = load (absolute()) { registers, mem -> registers.accumulator = mem }
        map[0xae] = load (absolute()) { registers, mem -> registers.indexX = mem }
        map[0xb1] = load (indirectY()) { registers, mem -> registers.accumulator = mem }
        map[0xb4] = load (zeroPaged {it.registers.indexX}) { registers, mem -> registers.indexY = mem }
        map[0xb5] = load (zeroPaged {it.registers.indexX}) { registers, mem -> registers.accumulator = mem }
        map[0xb6] = load (zeroPaged {it.registers.indexY}) { registers, mem -> registers.indexX = mem }
        map[0xb9] = load (absolute {it.registers.indexY}) { registers, mem -> registers.accumulator = mem }
        map[0xbc] = load (absolute {it.registers.indexX}) { registers, mem -> registers.indexY = mem }
        map[0xbd] = load (absolute {it.registers.indexX}) { registers, mem -> registers.accumulator = mem }
        map[0xbe] = load (absolute {it.registers.indexY}) { registers, mem -> registers.indexX = mem }

        //  Bit operations
        map[0x24] = bit (zeroPaged()) //  BIT - Test Bits in M with A
        map[0x2c] = bit (absolute())

        //  Compare operations
        map[0xc0] = compareOp (immediate()) { it.indexY } // CPY - Compare M and Y
        map[0xc1] = compareOp (indirectX()) { it.accumulator } //  CMP - Compare M and A
        map[0xc4] = compareOp (zeroPaged()) { it.indexY } // CPY - Compare M and Y
        map[0xc5] = compareOp (zeroPaged()) { it.accumulator } //  CMP - Compare M and A
        map[0xc9] = compareOp (immediate()) { it.accumulator } //  CMP - Compare M and A
        map[0xcc] = compareOp (absolute())  { it.indexY } // CPY - Compare M and Y
        map[0xcd] = compareOp (absolute())  { it.accumulator } //  CMP - Compare M and A
        map[0xd1] = compareOp (indirectY())  { it.accumulator } //  CMP - Compare M and A
        map[0xd5] = compareOp (zeroPaged{it.registers.indexX})  { it.accumulator } //  CMP - Compare M and A
        map[0xd9] = compareOp (absolute {it.registers.indexY})  { it.accumulator } //  CMP - Compare M and A
        map[0xdd] = compareOp (absolute {it.registers.indexX})  { it.accumulator } //  CMP - Compare M and A
        map[0xe0] = compareOp (immediate()) { it.indexX } // CPX - Compare M and X
        map[0xe4] = compareOp (zeroPaged()) { it.indexX } // CPX - Compare M and X
        map[0xec] = compareOp (absolute())  { it.indexX } // CPX - Compare M and X

        //  ASL/LSR/ROL/ROR Shift and Rotate operations
        map[0x06] = shiftLeft (zeroPagedAdr())
        map[0x0e] = shiftLeft (absoluteAdr())
        map[0x16] = shiftLeft (zeroPagedAdr { it.registers.indexX })
        map[0x1e] = shiftLeft (absoluteAdr { it.registers.indexX })
        map[0x26] = rotateLeft (zeroPagedAdr())
        map[0x2e] = rotateLeft (absoluteAdr())
        map[0x36] = rotateLeft (zeroPagedAdr { it.registers.indexX })
        map[0x3e] = rotateLeft (absoluteAdr { it.registers.indexX })
        map[0x46] = shiftRight (zeroPagedAdr())
        map[0x4e] = shiftRight (absoluteAdr())
        map[0x56] = shiftRight (zeroPagedAdr { it.registers.indexX })
        map[0x5e] = shiftRight (absoluteAdr { it.registers.indexX })
        map[0x66] = rotateRight (zeroPagedAdr())
        map[0x6e] = rotateRight (absoluteAdr())
        map[0x76] = rotateRight (zeroPagedAdr { it.registers.indexX })
        map[0x7e] = rotateRight (absoluteAdr { it.registers.indexX })

        //  INC/DEC Increment/Decrement
        map[0xc6] = unary (zeroPagedAdr()) { dec() }
        map[0xce] = unary (absoluteAdr())  { dec() }
        map[0xd6] = unary (zeroPagedAdr{it.registers.indexX}) { dec() }
        map[0xde] = unary (absoluteAdr{it.registers.indexX}) { dec() }
        map[0xe6] = unary (zeroPagedAdr()) { inc() }
        map[0xee] = unary (absoluteAdr())  { inc() }
        map[0xf6] = unary (zeroPagedAdr{it.registers.indexX}) { inc() }
        map[0xfe] = unary (absoluteAdr{it.registers.indexX}) { inc() }

        //  Transfer operations
        map[0x8a] = transfer ({it.indexX}) { r, x -> r.accumulator = x } // TXA - Transfer X to A
        map[0x98] = transfer ({it.indexY}) { r, y -> r.accumulator = y } // TYA - Transfer Y to A
        map[0x9a] = Opcode {
            it.apply {
                it.registers.stackPointer = it.registers.indexX
                it.workCyclesLeft += 2
            }
        } // TXS - Transfer X to Stack Pointer (doesn't set program counter)
        map[0xa8] = transfer ({it.accumulator}) { r, acc -> r.indexY = acc } //  TAY - Transfer A to Y
        map[0xaa] = transfer ({it.accumulator}) { r, acc -> r.indexX = acc } //  TAX - Transfer A to X
        map[0xba] = transfer ({it.stackPointer}) { r, sp -> r.indexX = sp } // TSX - Transfer Stack Pointer to X

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

                it.workCyclesLeft = 7
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

                workCyclesLeft = 6
            }
        }

        // JMP - Jump to location
        map[0x4c] = jump { absoluteAdr()(it).toSignedShort() }
        map[0x6c] = jump {
            //  Apparently some bug in this processor means this looks a bit odd
            val addr = absoluteAdr()(it)
            val hiByte = it.memory[(addr and 0xff00) or ((addr + 1) and 0x00ff)]
            ((hiByte.toUnsignedInt() shl 8) or it.memory[addr].toUnsignedInt()).toSignedShort()
        }

// NOP - No Operation (official)
        map[0xea] = Opcode {
            it.workCyclesLeft = 2
        }

//  ROL - Rotate One Bit Left (M or A)
        map[0x2a] = Opcode {
            it.apply {
                val newAccumulator = (registers.accumulator.toUnsignedInt() shl 1) or (if (processorStatus.carry) 1 else 0)
                processorStatus.carry = (newAccumulator and 0xFF00) > 0
                registers.accumulator = (newAccumulator and 0xFF).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)

workCyclesLeft = 2
            }
        }

        //  RTS - Return from Subroutine
        map[0x60] = Opcode {
            it.apply {
                val lowByte = pop().toUnsignedInt()
                val highByte = pop().toUnsignedInt()
                it.registers.programCounter = ((lowByte and 0xff) or (highByte shl 8)).toSignedShort()
                it.registers.programCounter++
//                println("Returning to Program Counter ${it.registers.programCounter.toHexString()}")

                workCyclesLeft = 6
            }
        }

        //  PLA - Pull A from Stack
        map[0x68] = Opcode {
            it.apply {
                registers.accumulator = pop()
                processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)

                workCyclesLeft = 4
            }
        }

        //  TODO: Refactor this so not so C&P
        //  INY - Increment Y By One
        map[0xc8] = Opcode {
            it.apply {
                registers.indexY = ((registers.indexY + 1) and 0xFF).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.indexY)

                workCyclesLeft = 2
            }
        }

        //  DEY - Decrement Y By One
        map[0x88] = Opcode {
            it.apply {
                registers.indexY = ((registers.indexY - 1) and 0xFF).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.indexY)

                workCyclesLeft = 2
            }
        }

        //  INX - Increment X By One
        map[0xe8] = Opcode {
            it.apply {
                registers.indexX = ((registers.indexX + 1) and 0xFF).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.indexX)

                workCyclesLeft = 2
            }
        }

        //  DEX - Decrement X By One
        map[0xca] = Opcode {
            it.apply {
                registers.indexX = ((registers.indexX - 1) and 0xFF).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.indexX)

                workCyclesLeft = 2
            }
        }

        //  PLP - Pull ProcessorStatus from Stack
        map[0x28] = Opcode {
            it.apply {
                processorStatus.toFlags(pop())

                workCyclesLeft = 4
            }
        }

        //  RTI - Return from Interrupt
        map[0x40] = Opcode {
            it.apply {
                processorStatus.toFlags(pop())
                registers.programCounter = (pop().toUnsignedInt() or (pop().toUnsignedInt() shl 8)).toSignedShort()

                workCyclesLeft = 6
            }
        }

        //  LSR - Shift Right One Bit (M or A)
        map[0x4a] = Opcode {
            it.apply {
                processorStatus.carry = registers.accumulator.isBitSet(0)
                registers.accumulator = (registers.accumulator.toUnsignedInt() shr 1).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)

                workCyclesLeft = 2
            }
        }

        //  ASL A
        map[0x0a] = Opcode {
            it.apply {
                processorStatus.carry = registers.accumulator.isBitSet(7)
                registers.accumulator = (registers.accumulator.toUnsignedInt() shl 1).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)

                workCyclesLeft = 2
            }
        }

        //  ROR - Rotate One Bit Right (M or A)
        map[0x6a] = Opcode {
            it.apply {
                val oldCarry = processorStatus.carry
                processorStatus.carry = registers.accumulator.isBitSet(0)
                registers.accumulator = ((registers.accumulator.toUnsignedInt() shr 1) or (if (oldCarry) 0x80 else 0)).toSignedByte()
                processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)

                workCyclesLeft = 2
            }
        }

        //  ----- UNOFFICIAL NOP FAMILY -----
        // NOP zp (3 bytes, 3 cycles) — 0x04, 0x44, 0x64
        map[0x04] = nopZp()
        map[0x44] = nopZp()
        map[0x64] = nopZp()

        // NOP abs (4 bytes, 4 cycles) — 0x0C
        map[0x0C] = nopAbs()

        // NOP abs,X (4 bytes, 4 cycles) — 0x1C, 0x3C, 0x5C, 0x7C, 0xDC, 0xFC
        map[0x1C] = nopAbsX()
        map[0x3C] = nopAbsX()
        map[0x5C] = nopAbsX()
        map[0x7C] = nopAbsX()
        map[0xDC] = nopAbsX()
        map[0xFC] = nopAbsX()

        // NOP zp,X (4 bytes, 4 cycles) — 0x14, 0x34, 0x54, 0x74, 0xD4, 0xF4
        map[0x14] = nopZpX()
        map[0x34] = nopZpX()
        map[0x54] = nopZpX()
        map[0x74] = nopZpX()
        map[0xD4] = nopZpX()
        map[0xF4] = nopZpX()

        // NOP imm (3 bytes, 2 cycles) — 0x80, 0x82, 0x89, 0xC2, 0xE2, 0xEB
        map[0x80] = nopImm()
        map[0x82] = nopImm()
        map[0x89] = nopImm()
        map[0xC2] = nopImm()
        map[0xE2] = nopImm()
        map[0xEB] = nopImm()

        // NOP (implied) (2 bytes, 2 cycles) — 0x1A, 0x3A, 0x5A, 0x7A, 0xDA, 0xFA
        map[0x1A] = nopImplied()
        map[0x3A] = nopImplied()
        map[0x5A] = nopImplied()
        map[0x7A] = nopImplied()
        map[0xDA] = nopImplied()
        map[0xFA] = nopImplied()

        //  ----- COMPLEX LOAD/STORE FAMILY (LAX/SAX/AHX/SHX/SHY) -----
        // LAX - Load A and X combined (2-5 cycles depending on addressing)
        map[0xA3] = lax(indirectX())
        map[0xA7] = lax(zeroPaged())
        map[0xAF] = lax(absolute())
        map[0xB3] = lax(indirectY())
        map[0xB7] = lax(zeroPaged { it.registers.indexY })
        map[0xBF] = lax(absolute { it.registers.indexY })

        // SAX - Store A AND X (4-6 cycles)
        map[0x83] = sax(indirectXAdr())
        map[0x87] = sax(zeroPagedAdr())
        map[0x8F] = sax(absoluteAdr())
        map[0x97] = sax(zeroPagedAdr { it.registers.indexY })

        // AHX - Store A AND X AND H (4-5 cycles)
        map[0x93] = ahx(indirectYAdr())
        map[0x9F] = ahx(absoluteAdr { it.registers.indexY })

        // XAA - ANE: A = A AND X AND operand (2 cycles, very unstable)
        map[0x9B] = xaa()
        map[0xAB] = xaa()

        // TAS/LAS - Load A and S (or A and X from memory) (4-5 cycles)
        map[0xBB] = las(absolute { it.registers.indexY })

        //  ----- DCP/ISC/RLA/RRA/SLO/SRE (ALR/ARR already covered) -----
        // DCP - DEC then CMP (6-8 cycles)
        map[0xC7] = dcp(zeroPagedAdr())
        map[0xD7] = dcp(zeroPagedAdr { it.registers.indexX })
        map[0xCF] = dcp(absoluteAdr())
        map[0xDF] = dcp(absoluteAdr { it.registers.indexX })
        map[0xDB] = dcp(zeroPagedAdr { it.registers.indexY })
        map[0xD3] = dcp(indirectXAdr())
        map[0xE3] = dcp(indirectXAdr())
        map[0xF3] = dcp(indirectYAdr())

        // ISC - INC then SBC (6-8 cycles)
        map[0xE7] = isc(zeroPagedAdr())
        map[0xF7] = isc(zeroPagedAdr { it.registers.indexX })
        map[0xEF] = isc(absoluteAdr())
        map[0xFF] = isc(absoluteAdr { it.registers.indexX })
        map[0xFB] = isc(zeroPagedAdr { it.registers.indexY })
        map[0xE3] = isc(indirectXAdr())
        map[0xF3] = isc(indirectYAdr())

        // RLA - ROL then AND (5-7 cycles)
        map[0x27] = rla(zeroPagedAdr())
        map[0x37] = rla(zeroPagedAdr { it.registers.indexX })
        map[0x2F] = rla(absoluteAdr())
        map[0x3F] = rla(absoluteAdr { it.registers.indexX })
        map[0x3B] = rla(absoluteAdr { it.registers.indexY })
        map[0x23] = rla(indirectXAdr())
        map[0x33] = rla(indirectYAdr())

        // RRA - ROR then ADC (5-7 cycles)
        map[0x67] = rra(zeroPagedAdr())
        map[0x77] = rra(zeroPagedAdr { it.registers.indexX })
        map[0x6F] = rra(absoluteAdr())
        map[0x7F] = rra(absoluteAdr { it.registers.indexX })
        map[0x7B] = rra(absoluteAdr { it.registers.indexY })
        map[0x63] = rra(indirectXAdr())
        map[0x73] = rra(indirectYAdr())

        // SLO - ASL then ORA (5-7 cycles)
        map[0x07] = slo(zeroPagedAdr())
        map[0x17] = slo(zeroPagedAdr { it.registers.indexX })
        map[0x0F] = slo(absoluteAdr())
        map[0x1F] = slo(absoluteAdr { it.registers.indexX })
        map[0x1B] = slo(absoluteAdr { it.registers.indexY })
        map[0x03] = slo(indirectXAdr())
        map[0x13] = slo(indirectYAdr())

        // SRE - LSR then EOR (5-7 cycles)
        map[0x47] = sre(zeroPagedAdr())
        map[0x57] = sre(zeroPagedAdr { it.registers.indexX })
        map[0x4F] = sre(absoluteAdr())
        map[0x5F] = sre(absoluteAdr { it.registers.indexX })
        map[0x5B] = sre(absoluteAdr { it.registers.indexY })
        map[0x43] = sre(indirectXAdr())
        map[0x53] = sre(indirectYAdr())

        // ALR - ASL then LSR (2 cycles) - already covered as 0x4B
        map[0x4B] = alr()

        // ARR - (same as 0x6A ROR with special behavior)
        map[0x6B] = arr()

        //  ----- KIL (HALT) OPCODES -----
        // KIL - Halt the CPU (no operation, locks up)
        map[0x02] = kil()
        map[0x12] = kil()
        map[0x22] = kil()
        map[0x32] = kil()
        map[0x42] = kil()
        map[0x52] = kil()
        map[0x62] = kil()
        map[0x72] = kil()
        map[0x92] = kil()
        map[0xB2] = kil()
        map[0xC3] = kil()
        map[0xD2] = kil()
        map[0xF2] = kil()

    }

    private fun branchOp(flag: (Cpu) -> Boolean) = Opcode {
        it.apply {
            if (flag(it)) {
                val previousCounter: Short = registers.programCounter.inc()

                //  Relative addressing
                registers.programCounter = (readByteAtPC() + registers.programCounter).toSignedShort()

                workCyclesLeft++
                if (hasCrossedPageBoundary(previousCounter, registers.programCounter)) pageBoundaryFlag = true
                if ((previousCounter - 2).toSignedShort() == registers.programCounter) idle = true
            } else {
                registers.programCounter++ // Increment the program counter
            }
            workCyclesLeft += 2
        }
    }

    private fun storeOp(adr: (Cpu) -> Int, value: (Cpu) -> Byte) = Opcode {
        it.apply {
            memory[adr(it)] = value(it)
            workCyclesLeft += 4
        }
    }

    private fun setOp(op: (Cpu) -> Unit) = Opcode {
        it.apply {
            op(it)
            workCyclesLeft += 2
        }
    }

    private fun pushOp(value: (Cpu) -> Byte) = Opcode {
        it.apply {
            push(value(it))
            workCyclesLeft += 3
        }
    }

    private fun clearOp(op: (Cpu) -> Unit) = Opcode {
        it.apply {
            op(it)
            workCyclesLeft += 2
        }
    }

    private fun opWithA(mem: (Cpu) -> Byte, op: (Int, Int) -> Int) = Opcode {
        it.apply {
            registers.accumulator = op(registers.accumulator.toUnsignedInt(), mem(it).toUnsignedInt()).toSignedByte().apply {
                processorStatus.resolveZeroAndNegativeFlags(this)
            }

            workCyclesLeft += 2
        }
    }

    private fun load(mem: (Cpu) -> Byte, op: (Registers, Byte) -> Unit) = Opcode {
        it.apply {
            mem(it).apply {
                op(it.registers, this)
                processorStatus.resolveZeroAndNegativeFlags(this)
            }

            workCyclesLeft += 2
        }
    }

    private fun compareOp(memFn: (Cpu) -> Byte, op: (Registers) -> Byte) = Opcode {
        it.apply {
            val register = op(it.registers)
            val memValue = memFn(it)
            val comparison: Int = register - memValue
            processorStatus.apply {
                negative = comparison.toSignedByte().isBitSet(7)
                zero = comparison == 0
                carry = register.toUnsignedInt() >= memValue.toUnsignedInt()
            }

            workCyclesLeft += 2
        }
    }

    private fun transfer(from: (Registers) -> Byte, transfer: (Registers, Byte) -> Unit) = Opcode {
        it.apply {
            from(it.registers).apply {
                processorStatus.resolveZeroAndNegativeFlags(this)
                transfer(registers, this)
            }
            workCyclesLeft += 2
        }
    }

    private fun addToA(mem: (Cpu) -> Byte) = Opcode {
        it.apply {
            //  No need to test for decimal mode on NES CPU!
            val next = mem(it)
            val currentAccumulator = registers.accumulator

            var result = currentAccumulator.toUnsignedInt() + next.toUnsignedInt()
            if (processorStatus.carry) result++

            registers.accumulator = result.toSignedByte()
            processorStatus.carry = (result shr 8) != 0
            processorStatus.overflow = ((currentAccumulator.toUnsignedInt() xor next.toUnsignedInt()) and 0x80 == 0x00) &&
                    ((currentAccumulator.toUnsignedInt() xor registers.accumulator.toUnsignedInt()) and 0x80 == 0x80)
            processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)

            workCyclesLeft += 2
        }
    }

    private fun subtractFromA(mem: (Cpu) -> Byte) = Opcode {
        it.apply {
            //  No need to test for decimal mode on NES CPU!
            val next = mem(it)
            val currentAccumulator = registers.accumulator

            var result = currentAccumulator.toUnsignedInt() - next.toUnsignedInt()
            if (!processorStatus.carry) result--

            registers.accumulator = (result and 0xFF).toSignedByte()
            processorStatus.carry = (result shr 8) == 0
            processorStatus.overflow = ((currentAccumulator.toUnsignedInt() xor next.toUnsignedInt()) and 0x80 == 0x80) &&
                    ((currentAccumulator.toUnsignedInt() xor registers.accumulator.toUnsignedInt()) and 0x80 == 0x80)
            processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)

            workCyclesLeft += 6
        }
    }

    private fun shiftRight(addrFn: (Cpu) -> Int) = Opcode {
        it.apply {
            val address = addrFn(it)
            val result = memory[address]

            processorStatus.carry = result.isBitSet(0)
            val shiftedResult = result.shiftRight()
            memory[address] = shiftedResult
            processorStatus.resolveZeroAndNegativeFlags(shiftedResult)

            workCyclesLeft += 2
        }
    }

    private fun shiftLeft(addrFn: (Cpu) -> Int) = Opcode {
        it.apply {
            val address = addrFn(it)
            val result = memory[address]

            processorStatus.carry = result.isBitSet(7)
            val shiftedResult = ((result.toUnsignedInt() shl 1) and 0xFF).toSignedByte()
            memory[address] = shiftedResult
            processorStatus.resolveZeroAndNegativeFlags(shiftedResult)

            workCyclesLeft += 2
        }
    }

    private fun rotateRight(addrFn: (Cpu) -> Int) = Opcode {
        it.apply {
            val address = addrFn(it)
            val result = memory[address]

            val oldCarry = processorStatus.carry
            processorStatus.carry = result.isBitSet(0)
            val rotatedResult = ((result.toUnsignedInt() shr 1) or (if (oldCarry) 0x80 else 0)).toSignedByte()
            memory[address] = rotatedResult
            processorStatus.resolveZeroAndNegativeFlags(rotatedResult)

            workCyclesLeft += 2
        }
    }

    private fun rotateLeft(addrFn: (Cpu) -> Int) = Opcode {
        it.apply {
            val address = addrFn(it)
            val result = memory[address]

            val rotatedResult = (result.toUnsignedInt() shl 1) or (if (processorStatus.carry) 1 else 0)
            processorStatus.carry = (rotatedResult and 0xFF00) > 0
            memory[address] = (rotatedResult and 0xFF).toSignedByte()
            processorStatus.resolveZeroAndNegativeFlags((rotatedResult and 0xFF).toSignedByte())

            workCyclesLeft += 2
        }
    }

    private fun unary(addrFn: (Cpu) -> Int, op: Int.() -> Int) = Opcode {
        it.apply {
            val address = addrFn(it)
            val result = (op((memory[address].toUnsignedInt())) and 0xFF).toSignedByte()

            memory[address] = result
            processorStatus.resolveZeroAndNegativeFlags(result)

            workCyclesLeft += 6
        }
    }

    private fun bit(mem: (Cpu) -> Byte) = Opcode {
        it.apply {
            mem(it).toUnsignedInt().apply {
                processorStatus.zero = (this and registers.accumulator.toUnsignedInt()) == 0
                processorStatus.negative = (this and 0b10000000) != 0
                processorStatus.overflow = (this and 0b01000000) != 0
            }

            workCyclesLeft += 3
        }
    }

    private fun nopZp() = Opcode {
        it.apply {
            it.memory[zeroPagedAdr()(it)]
            workCyclesLeft = 3
        }
    }

    private fun nopAbs() = Opcode {
        it.apply {
            it.memory[absoluteAdr()(it)]
            workCyclesLeft = 4
        }
    }

    private fun nopAbsX() = Opcode {
        it.apply {
            it.memory[absoluteAdr { it.registers.indexX }(it)]
            workCyclesLeft = 4
        }
    }

    private fun nopZpX() = Opcode {
        it.apply {
            it.memory[zeroPagedAdr { it.registers.indexX }(it)]
            workCyclesLeft = 4
        }
    }

    private fun nopImm() = Opcode {
        it.apply {
            it.readByteAtPC()
            workCyclesLeft = 2
        }
    }

    private fun nopImplied() = Opcode {
        it.workCyclesLeft = 2
    }

    private fun lax(mem: (Cpu) -> Byte) = Opcode {
        it.apply {
            val value = mem(it)
            registers.accumulator = value
            registers.indexX = value
            processorStatus.resolveZeroAndNegativeFlags(value)
            workCyclesLeft += 2
        }
    }

    private fun sax(addrFn: (Cpu) -> Int) = Opcode {
        it.apply {
            memory[addrFn(it)] = (registers.accumulator.toUnsignedInt() and registers.indexX.toUnsignedInt()).toSignedByte()
            workCyclesLeft += 4
        }
    }

    private fun ahx(addrFn: (Cpu) -> Int) = Opcode {
        it.apply {
            val value = (registers.accumulator.toUnsignedInt() and registers.indexX.toUnsignedInt() and 0x07).toSignedByte()
            memory[addrFn(it)] = value
            workCyclesLeft += 4
        }
    }

    private fun xaa() = Opcode {
        it.apply {
            registers.accumulator = (registers.accumulator.toUnsignedInt() and registers.indexX.toUnsignedInt()).toSignedByte()
            workCyclesLeft = 2
        }
    }

    private fun tas(addrFn: (Cpu) -> Int) = Opcode {
        it.apply {
            registers.stackPointer = registers.accumulator
            memory[addrFn(it)]
            workCyclesLeft = 4
        }
    }

    private fun shx(addrFn: (Cpu) -> Int) = Opcode {
        it.apply {
            val address = addrFn(it)
            val highByte = ((address shr 8) + 1) and 0xFF
            memory[address] = (registers.indexX.toUnsignedInt() and highByte).toSignedByte()
            workCyclesLeft = 5
        }
    }

    private fun shy(addrFn: (Cpu) -> Int) = Opcode {
        it.apply {
            val address = addrFn(it)
            val highByte = ((address shr 8) + 1) and 0xFF
            memory[address] = (registers.indexY.toUnsignedInt() and highByte).toSignedByte()
            workCyclesLeft = 5
        }
    }

    private fun kil() = Opcode {
        it.apply {
            // Halt - loop forever (treat as NOP that consumes 2 cycles but doesn't advance PC)
            workCyclesLeft = 2
        }
    }

    private fun las(mem: (Cpu) -> Byte) = Opcode {
        it.apply {
            val value = mem(it)
            registers.accumulator = value
            registers.indexX = value
            registers.stackPointer = value
            processorStatus.resolveZeroAndNegativeFlags(value)
            workCyclesLeft += 4
        }
    }

    private fun dcp(addrFn: (Cpu) -> Int) = Opcode {
        it.apply {
            val address = addrFn(it)
            val result = ((memory[address].toUnsignedInt() - 1) and 0xFF).toSignedByte()
            memory[address] = result
            val comparison = registers.accumulator.toUnsignedInt() - result.toUnsignedInt()
            processorStatus.carry = comparison >= 0
            processorStatus.zero = comparison == 0
            processorStatus.negative = comparison.toSignedByte().isBitSet(7)
            workCyclesLeft += 6
        }
    }

    private fun isc(addrFn: (Cpu) -> Int) = Opcode {
        it.apply {
            val address = addrFn(it)
            val result = ((memory[address].toUnsignedInt() + 1) and 0xFF).toSignedByte()
            memory[address] = result
            val currentAccumulator = registers.accumulator
            var res = currentAccumulator.toUnsignedInt() - result.toUnsignedInt()
            if (!processorStatus.carry) res--
            registers.accumulator = (res and 0xFF).toSignedByte()
            processorStatus.carry = (res shr 8) == 0
            processorStatus.overflow = ((currentAccumulator.toUnsignedInt() xor result.toUnsignedInt()) and 0x80 == 0x80) &&
                    ((currentAccumulator.toUnsignedInt() xor registers.accumulator.toUnsignedInt()) and 0x80 == 0x80)
            processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)
            workCyclesLeft += 6
        }
    }

    private fun rla(addrFn: (Cpu) -> Int) = Opcode {
        it.apply {
            val address = addrFn(it)
            val result = memory[address]
            val newCarry = result.isBitSet(7)
            val rotated = ((result.toUnsignedInt() shl 1) or (if (processorStatus.carry) 1 else 0)).toSignedByte()
            memory[address] = rotated
            processorStatus.carry = newCarry
            registers.accumulator = (registers.accumulator.toUnsignedInt() and rotated.toUnsignedInt()).toSignedByte()
            processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)
            workCyclesLeft += 5
        }
    }

    private fun rra(addrFn: (Cpu) -> Int) = Opcode {
        it.apply {
            val address = addrFn(it)
            val result = memory[address]
            val oldCarry = processorStatus.carry
            processorStatus.carry = result.isBitSet(0)
            val rotated = ((result.toUnsignedInt() shr 1) or (if (oldCarry) 0x80 else 0)).toSignedByte()
            memory[address] = rotated
            val currentAccumulator = registers.accumulator
            var res = currentAccumulator.toUnsignedInt() + rotated.toUnsignedInt()
            if (processorStatus.carry) res++
            registers.accumulator = res.toSignedByte()
            processorStatus.carry = (res shr 8) != 0
            processorStatus.overflow = ((currentAccumulator.toUnsignedInt() xor rotated.toUnsignedInt()) and 0x80 == 0x00) &&
                    ((currentAccumulator.toUnsignedInt() xor registers.accumulator.toUnsignedInt()) and 0x80 == 0x80)
            processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)
            workCyclesLeft += 5
        }
    }

    private fun slo(addrFn: (Cpu) -> Int) = Opcode {
        it.apply {
            val address = addrFn(it)
            val result = memory[address]
            processorStatus.carry = result.isBitSet(7)
            val shifted = ((result.toUnsignedInt() shl 1) and 0xFF).toSignedByte()
            memory[address] = shifted
            registers.accumulator = (registers.accumulator.toUnsignedInt() or shifted.toUnsignedInt()).toSignedByte()
            processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)
            workCyclesLeft += 5
        }
    }

    private fun sre(addrFn: (Cpu) -> Int) = Opcode {
        it.apply {
            val address = addrFn(it)
            val result = memory[address]
            processorStatus.carry = result.isBitSet(0)
            val shifted = (result.toUnsignedInt() shr 1).toSignedByte()
            memory[address] = shifted
            registers.accumulator = (registers.accumulator.toUnsignedInt() xor shifted.toUnsignedInt()).toSignedByte()
            processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)
            workCyclesLeft += 5
        }
    }

    private fun alr() = Opcode {
        it.apply {
            registers.accumulator = (registers.accumulator.toUnsignedInt() and it.readByteAtPC().toUnsignedInt()).toSignedByte()
            processorStatus.carry = registers.accumulator.isBitSet(7)
            registers.accumulator = (registers.accumulator.toUnsignedInt() shr 1).toSignedByte()
            processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)
            workCyclesLeft = 2
        }
    }

    private fun arr() = Opcode {
        it.apply {
            registers.accumulator = (registers.accumulator.toUnsignedInt() and it.readByteAtPC().toUnsignedInt()).toSignedByte()
            val oldCarry = processorStatus.carry
            processorStatus.carry = registers.accumulator.isBitSet(0)
            registers.accumulator = (registers.accumulator.toUnsignedInt() shr 1).toSignedByte()
            if (oldCarry) registers.accumulator = (registers.accumulator.toUnsignedInt() or 0x80).toSignedByte()
            processorStatus.resolveZeroAndNegativeFlags(registers.accumulator)
            workCyclesLeft = 2
        }
    }

    private fun jump(mem: (Cpu) -> Short) = Opcode {
        it.apply {
            val temp = registers.programCounter
            registers.programCounter = mem(it)

            if (registers.programCounter == (temp - 1).toSignedShort()) {
                // TODO: Set idle
            }

            workCyclesLeft += 3
        }
    }

    operator fun get(code: Int): Opcode? = map[code]
}

class Opcode(val op: (Cpu) -> Unit)

class UnhandledOpcodeException(opcodeVal: Int) : Throwable("Opcode ${"%02X".format(opcodeVal)} not implemented")