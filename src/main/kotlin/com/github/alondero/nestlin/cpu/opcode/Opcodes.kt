package com.github.alondero.nestlin.cpu.opcode

import com.github.alondero.nestlin.cpu.opcode.MemoryShiftRotate.Kind
import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt

/**
 * New sealed-class dispatch table for all 6502 opcodes (issue #192).
 *
 * **Phase 1 of the refactor.** This class sits alongside the existing
 * `com.github.alondero.nestlin.cpu.Opcodes` for cross-validation. The
 * existing `Cpu` continues to use the old dispatcher; this new
 * `OpcodesRefactor` is unused at runtime but is exercised by
 * `OpcodeCrossValidationTest` (Phase 2).
 *
 * **Phase 3** swaps `Cpu`'s `private val opcodes = Opcodes()` to
 * `OpcodesRefactor()` and deletes the old class + `AddressingMode.kt`.
 */
object OpcodesRefactor {

    /**
     * The dispatch table: 250 mapped opcodes (6 unmapped: 0x0B, 0x2B,
     * 0x8B, 0x9C, 0x9E, 0xCB). See [com.github.alondero.nestlin.cpu.OpcodeDispatchCompletenessTest]
     * for the regression bar.
     *
     * **Cycle-count quirk preservation.** Several opcodes carry intentional
     * deviations from real 6502 behaviour, marked with `[quirk: ...]`
     * inline comments in the Opcodes.kt source:
     *  - LAX hardcodes 2 cycles (real: 6/3/4/5/4/4)
     *  - SAX hardcodes 4 cycles (real: 6/3/4/4)
     *  - JMP indirect uses 3 cycles (real: 5)
     *  - AHX mask is 0x07 (looks like a typo)
     *  - 0x9B is XAA (canonical: TAS)
     *  - 0xE3/0xF3 are ISC, not DCP (silent overwrite)
     *  - KIL doesn't actually halt
     * Each quirk is preserved by the [OpcodeCycleTableTest] baseline.
     */
    val map: Map<Int, Opcode> = buildMap {
        // ===== Branch (8) =================================================
        put(0x10, Branch({ !it.processorStatus.negative }, "BPL"))
        put(0x30, Branch({ it.processorStatus.negative }, "BMI"))
        put(0x50, Branch({ !it.processorStatus.overflow }, "BVC"))
        put(0x70, Branch({ it.processorStatus.overflow }, "BVS"))
        put(0x90, Branch({ !it.processorStatus.carry }, "BCC"))
        put(0xB0, Branch({ it.processorStatus.carry }, "BCS"))
        put(0xD0, Branch({ !it.processorStatus.zero }, "BNE"))
        put(0xF0, Branch({ it.processorStatus.zero }, "BEQ"))

        // ===== Stores (13) ================================================
        put(0x81, Store(IndirectX, { it.registers.accumulator }, 6, "STA (ind,X)"))
        put(0x84, Store(ZeroPage(), { it.registers.indexY }, 3, "STY zp"))
        put(0x85, Store(ZeroPage(), { it.registers.accumulator }, 3, "STA zp"))
        put(0x86, Store(ZeroPage(), { it.registers.indexX }, 3, "STX zp"))
        put(0x8C, Store(Absolute(), { it.registers.indexY }, 4, "STY abs"))
        put(0x8D, Store(Absolute(), { it.registers.accumulator }, 4, "STA abs"))
        put(0x8E, Store(Absolute(), { it.registers.indexX }, 4, "STX abs"))
        put(0x91, Store(IndirectY, { it.registers.accumulator }, 6, "STA (ind),Y"))
        put(0x94, Store(ZeroPage(x = true), { it.registers.indexY }, 4, "STY zp,X"))
        put(0x95, Store(ZeroPage(x = true), { it.registers.accumulator }, 4, "STA zp,X"))
        put(0x96, Store(ZeroPage(y = true), { it.registers.indexX }, 4, "STX zp,Y"))
        put(0x99, Store(Absolute(y = true), { it.registers.accumulator }, 5, "STA abs,Y"))
        put(0x9D, Store(Absolute(x = true), { it.registers.accumulator }, 5, "STA abs,X"))

        // ===== Flag set/clear (7) =========================================
        put(0x18, Flag({ it.processorStatus.carry = false }, "CLC"))
        put(0x38, Flag({ it.processorStatus.carry = true }, "SEC"))
        put(0x58, Flag({ it.processorStatus.interruptDisable = false }, "CLI"))
        put(0x78, Flag({ it.processorStatus.interruptDisable = true }, "SEI"))
        put(0xD8, Flag({ it.processorStatus.decimalMode = false }, "CLD"))
        put(0xF8, Flag({ it.processorStatus.decimalMode = true }, "SED"))
        put(0xB8, Flag({ it.processorStatus.overflow = false }, "CLV"))

        // ===== ADC (8) ====================================================
        put(0x61, Adc(IndirectX, 6, "ADC (ind,X)"))
        put(0x65, Adc(ZeroPage(), 3, "ADC zp"))
        put(0x69, Adc(Immediate, 2, "ADC #imm"))
        put(0x6D, Adc(Absolute(), 4, "ADC abs"))
        put(0x71, Adc(IndirectY, 5, "ADC (ind),Y"))
        put(0x75, Adc(ZeroPage(x = true), 4, "ADC zp,X"))
        put(0x79, Adc(Absolute(y = true), 4, "ADC abs,Y"))
        put(0x7D, Adc(Absolute(x = true), 4, "ADC abs,X"))

        // ===== SBC (8) ====================================================
        put(0xE1, Sbc(IndirectX, 6, "SBC (ind,X)"))
        put(0xE5, Sbc(ZeroPage(), 3, "SBC zp"))
        put(0xE9, Sbc(Immediate, 2, "SBC #imm"))
        put(0xED, Sbc(Absolute(), 4, "SBC abs"))
        put(0xF1, Sbc(IndirectY, 5, "SBC (ind),Y"))
        put(0xF5, Sbc(ZeroPage(x = true), 4, "SBC zp,X"))
        put(0xF9, Sbc(Absolute(y = true), 4, "SBC abs,Y"))
        put(0xFD, Sbc(Absolute(x = true), 4, "SBC abs,X"))

        // ===== Push (2) ===================================================
        put(0x08, Push({ it.processorStatus.asByte() }, "PHP"))
        put(0x48, Push({ it.registers.accumulator }, "PHA"))

        // ===== ORA (8) ====================================================
        put(0x01, Logic(IndirectX, { a, m -> (a or m) and 0xff }, 6, "ORA (ind,X)"))
        put(0x05, Logic(ZeroPage(), { a, m -> (a or m) and 0xff }, 3, "ORA zp"))
        put(0x09, Logic(Immediate, { a, m -> (a or m) and 0xff }, 2, "ORA #imm"))
        put(0x0D, Logic(Absolute(), { a, m -> (a or m) and 0xff }, 4, "ORA abs"))
        put(0x11, Logic(IndirectY, { a, m -> (a or m) and 0xff }, 5, "ORA (ind),Y"))
        put(0x15, Logic(ZeroPage(x = true), { a, m -> (a or m) and 0xff }, 4, "ORA zp,X"))
        put(0x19, Logic(Absolute(y = true), { a, m -> (a or m) and 0xff }, 4, "ORA abs,Y"))
        put(0x1D, Logic(Absolute(x = true), { a, m -> (a or m) and 0xff }, 4, "ORA abs,X"))

        // ===== AND (8) ====================================================
        put(0x21, Logic(IndirectX, { a, m -> a and m }, 6, "AND (ind,X)"))
        put(0x25, Logic(ZeroPage(), { a, m -> a and m }, 3, "AND zp"))
        put(0x29, Logic(Immediate, { a, m -> a and m }, 2, "AND #imm"))
        put(0x2D, Logic(Absolute(), { a, m -> a and m }, 4, "AND abs"))
        put(0x31, Logic(IndirectY, { a, m -> a and m }, 5, "AND (ind),Y"))
        put(0x35, Logic(ZeroPage(x = true), { a, m -> a and m }, 4, "AND zp,X"))
        put(0x39, Logic(Absolute(y = true), { a, m -> a and m }, 4, "AND abs,Y"))
        put(0x3D, Logic(Absolute(x = true), { a, m -> a and m }, 4, "AND abs,X"))

        // ===== EOR (8) ====================================================
        put(0x41, Logic(IndirectX, { a, m -> (a xor m) and 0xff }, 6, "EOR (ind,X)"))
        put(0x45, Logic(ZeroPage(), { a, m -> (a xor m) and 0xff }, 3, "EOR zp"))
        put(0x49, Logic(Immediate, { a, m -> (a xor m) and 0xff }, 2, "EOR #imm"))
        put(0x4D, Logic(Absolute(), { a, m -> (a xor m) and 0xff }, 4, "EOR abs"))
        put(0x51, Logic(IndirectY, { a, m -> (a xor m) and 0xff }, 5, "EOR (ind),Y"))
        put(0x55, Logic(ZeroPage(x = true), { a, m -> (a xor m) and 0xff }, 4, "EOR zp,X"))
        put(0x59, Logic(Absolute(y = true), { a, m -> (a xor m) and 0xff }, 4, "EOR abs,Y"))
        put(0x5D, Logic(Absolute(x = true), { a, m -> (a xor m) and 0xff }, 4, "EOR abs,X"))

        // ===== Loads (18) =================================================
        put(0xA0, Load(Immediate, { c, v -> c.registers.indexY = v }, 2, "LDY #imm"))
        put(0xA1, Load(IndirectX, { c, v -> c.registers.accumulator = v }, 6, "LDA (ind,X)"))
        put(0xA2, Load(Immediate, { c, v -> c.registers.indexX = v }, 2, "LDX #imm"))
        put(0xA4, Load(ZeroPage(), { c, v -> c.registers.indexY = v }, 3, "LDY zp"))
        put(0xA5, Load(ZeroPage(), { c, v -> c.registers.accumulator = v }, 3, "LDA zp"))
        put(0xA6, Load(ZeroPage(), { c, v -> c.registers.indexX = v }, 3, "LDX zp"))
        put(0xA9, Load(Immediate, { c, v -> c.registers.accumulator = v }, 2, "LDA #imm"))
        put(0xAC, Load(Absolute(), { c, v -> c.registers.indexY = v }, 4, "LDY abs"))
        put(0xAD, Load(Absolute(), { c, v -> c.registers.accumulator = v }, 4, "LDA abs"))
        put(0xAE, Load(Absolute(), { c, v -> c.registers.indexX = v }, 4, "LDX abs"))
        put(0xB1, Load(IndirectY, { c, v -> c.registers.accumulator = v }, 5, "LDA (ind),Y"))
        put(0xB4, Load(ZeroPage(x = true), { c, v -> c.registers.indexY = v }, 4, "LDY zp,X"))
        put(0xB5, Load(ZeroPage(x = true), { c, v -> c.registers.accumulator = v }, 4, "LDA zp,X"))
        put(0xB6, Load(ZeroPage(y = true), { c, v -> c.registers.indexX = v }, 4, "LDX zp,Y"))
        put(0xB9, Load(Absolute(y = true), { c, v -> c.registers.accumulator = v }, 4, "LDA abs,Y"))
        put(0xBC, Load(Absolute(x = true), { c, v -> c.registers.indexY = v }, 4, "LDY abs,X"))
        put(0xBD, Load(Absolute(x = true), { c, v -> c.registers.accumulator = v }, 4, "LDA abs,X"))
        put(0xBE, Load(Absolute(y = true), { c, v -> c.registers.indexX = v }, 4, "LDX abs,Y"))

        // ===== BIT (2) ====================================================
        put(0x24, Bit(ZeroPage(), 3, "BIT zp"))
        put(0x2C, Bit(Absolute(), 4, "BIT abs"))

        // ===== Compare (14) ===============================================
        put(0xC0, Compare(Immediate, { it.registers.indexY }, 2, "CPY #imm"))
        put(0xC1, Compare(IndirectX, { it.registers.accumulator }, 6, "CMP (ind,X)"))
        put(0xC4, Compare(ZeroPage(), { it.registers.indexY }, 3, "CPY zp"))
        put(0xC5, Compare(ZeroPage(), { it.registers.accumulator }, 3, "CMP zp"))
        put(0xC9, Compare(Immediate, { it.registers.accumulator }, 2, "CMP #imm"))
        put(0xCC, Compare(Absolute(), { it.registers.indexY }, 4, "CPY abs"))
        put(0xCD, Compare(Absolute(), { it.registers.accumulator }, 4, "CMP abs"))
        put(0xD1, Compare(IndirectY, { it.registers.accumulator }, 5, "CMP (ind),Y"))
        put(0xD5, Compare(ZeroPage(x = true), { it.registers.accumulator }, 4, "CMP zp,X"))
        put(0xD9, Compare(Absolute(y = true), { it.registers.accumulator }, 4, "CMP abs,Y"))
        put(0xDD, Compare(Absolute(x = true), { it.registers.accumulator }, 4, "CMP abs,X"))
        put(0xE0, Compare(Immediate, { it.registers.indexX }, 2, "CPX #imm"))
        put(0xE4, Compare(ZeroPage(), { it.registers.indexX }, 3, "CPX zp"))
        put(0xEC, Compare(Absolute(), { it.registers.indexX }, 4, "CPX abs"))

        // ===== Memory shift/rotate (16) ===================================
        put(0x06, MemoryShiftRotate(ZeroPage(), Kind.ASL, 5, "ASL zp"))
        put(0x0E, MemoryShiftRotate(Absolute(), Kind.ASL, 6, "ASL abs"))
        put(0x16, MemoryShiftRotate(ZeroPage(x = true), Kind.ASL, 6, "ASL zp,X"))
        put(0x1E, MemoryShiftRotate(Absolute(x = true), Kind.ASL, 7, "ASL abs,X"))
        put(0x26, MemoryShiftRotate(ZeroPage(), Kind.ROL, 5, "ROL zp"))
        put(0x2E, MemoryShiftRotate(Absolute(), Kind.ROL, 6, "ROL abs"))
        put(0x36, MemoryShiftRotate(ZeroPage(x = true), Kind.ROL, 6, "ROL zp,X"))
        put(0x3E, MemoryShiftRotate(Absolute(x = true), Kind.ROL, 7, "ROL abs,X"))
        put(0x46, MemoryShiftRotate(ZeroPage(), Kind.LSR, 5, "LSR zp"))
        put(0x4E, MemoryShiftRotate(Absolute(), Kind.LSR, 6, "LSR abs"))
        put(0x56, MemoryShiftRotate(ZeroPage(x = true), Kind.LSR, 6, "LSR zp,X"))
        put(0x5E, MemoryShiftRotate(Absolute(x = true), Kind.LSR, 7, "LSR abs,X"))
        put(0x66, MemoryShiftRotate(ZeroPage(), Kind.ROR, 5, "ROR zp"))
        put(0x6E, MemoryShiftRotate(Absolute(), Kind.ROR, 6, "ROR abs"))
        put(0x76, MemoryShiftRotate(ZeroPage(x = true), Kind.ROR, 6, "ROR zp,X"))
        put(0x7E, MemoryShiftRotate(Absolute(x = true), Kind.ROR, 7, "ROR abs,X"))

        // ===== Memory INC/DEC (8) =========================================
        put(0xC6, MemoryIncDec(ZeroPage(), -1, 5, "DEC zp"))
        put(0xCE, MemoryIncDec(Absolute(), -1, 6, "DEC abs"))
        put(0xD6, MemoryIncDec(ZeroPage(x = true), -1, 6, "DEC zp,X"))
        put(0xDE, MemoryIncDec(Absolute(x = true), -1, 7, "DEC abs,X"))
        put(0xE6, MemoryIncDec(ZeroPage(), +1, 5, "INC zp"))
        put(0xEE, MemoryIncDec(Absolute(), +1, 6, "INC abs"))
        put(0xF6, MemoryIncDec(ZeroPage(x = true), +1, 6, "INC zp,X"))
        put(0xFE, MemoryIncDec(Absolute(x = true), +1, 7, "INC abs,X"))

        // ===== Transfer (5) + TXS (1) =====================================
        put(0x8A, Transfer({ it.registers.indexX }, { c, v -> c.registers.accumulator = v }, "TXA"))
        put(0x98, Transfer({ it.registers.indexY }, { c, v -> c.registers.accumulator = v }, "TYA"))
        put(0x9A, TransferNoFlags("TXS"))
        put(0xA8, Transfer({ it.registers.accumulator }, { c, v -> c.registers.indexY = v }, "TAY"))
        put(0xAA, Transfer({ it.registers.accumulator }, { c, v -> c.registers.indexX = v }, "TAX"))
        put(0xBA, Transfer({ it.registers.stackPointer }, { c, v -> c.registers.indexX = v }, "TSX"))

        // ===== Inline-implied (17) ========================================
        put(0x00, Break())
        put(0x20, JumpToSubroutine())
        put(0x4C, Jump("JMP abs"))
        put(0x6C, JumpIndirect("JMP (ind)"))
        put(0xEA, NopImplied())
        put(0x0A, AccShiftRotate(
            { cpu ->
                cpu.processorStatus.carry = cpu.registers.accumulator.isBitSet(7)
                cpu.registers.accumulator =
                    (cpu.registers.accumulator.toUnsignedInt() shl 1).toSignedByte()
                cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
            }, "ASL A"))
        put(0x2A, AccShiftRotate(
            { cpu ->
                val newAcc = (cpu.registers.accumulator.toUnsignedInt() shl 1) or
                             (if (cpu.processorStatus.carry) 1 else 0)
                cpu.processorStatus.carry = (newAcc and 0xFF00) > 0
                cpu.registers.accumulator = (newAcc and 0xFF).toSignedByte()
                cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
            }, "ROL A"))
        put(0x4A, AccShiftRotate(
            { cpu ->
                cpu.processorStatus.carry = cpu.registers.accumulator.isBitSet(0)
                cpu.registers.accumulator = (cpu.registers.accumulator.toUnsignedInt() shr 1).toSignedByte()
                cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
            }, "LSR A"))
        put(0x6A, AccShiftRotate(
            { cpu ->
                val oldCarry = cpu.processorStatus.carry
                cpu.processorStatus.carry = cpu.registers.accumulator.isBitSet(0)
                cpu.registers.accumulator =
                    ((cpu.registers.accumulator.toUnsignedInt() shr 1) or
                     (if (oldCarry) 0x80 else 0)).toSignedByte()
                cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
            }, "ROR A"))
        put(0x60, ReturnFromSubroutine())
        put(0x68, Pull({ c, v -> c.registers.accumulator = v }, resolvesFlags = true, "PLA"))
        put(0xC8, RegisterIncDec({ it.registers.indexY }, { c, v -> c.registers.indexY = v }, +1, "INY"))
        put(0x88, RegisterIncDec({ it.registers.indexY }, { c, v -> c.registers.indexY = v }, -1, "DEY"))
        put(0xE8, RegisterIncDec({ it.registers.indexX }, { c, v -> c.registers.indexX = v }, +1, "INX"))
        put(0xCA, RegisterIncDec({ it.registers.indexX }, { c, v -> c.registers.indexX = v }, -1, "DEX"))
        put(0x28, Pull({ c, v -> c.processorStatus.toFlags(v) }, resolvesFlags = false, "PLP"))
        put(0x40, ReturnFromInterrupt())

        // ===== Unofficial NOP variants (28) ===============================
        // (3 NOP zp) — same cycle count for all three
        listOf(0x04, 0x44, 0x64).forEach { put(it, NopZp()) }
        put(0x0C, NopAbs())
        // (6 NOP abs,X)
        listOf(0x1C, 0x3C, 0x5C, 0x7C, 0xDC, 0xFC).forEach { put(it, NopAbsX()) }
        // (6 NOP zp,X)
        listOf(0x14, 0x34, 0x54, 0x74, 0xD4, 0xF4).forEach { put(it, NopZpX()) }
        // (6 NOP imm)
        listOf(0x80, 0x82, 0x89, 0xC2, 0xE2, 0xEB).forEach { put(it, NopImm()) }
        // (6 NOP implied)
        listOf(0x1A, 0x3A, 0x5A, 0x7A, 0xDA, 0xFA).forEach { put(it, NopImplied()) }

        // ===== LAX (6) — quirky cycle counts ==============================
        put(0xA3, Lax(IndirectX, "LAX (ind,X)"))
        put(0xA7, Lax(ZeroPage(), "LAX zp"))
        put(0xAF, Lax(Absolute(), "LAX abs"))
        put(0xB3, Lax(IndirectY, "LAX (ind),Y"))
        put(0xB7, Lax(ZeroPage(y = true), "LAX zp,Y"))
        put(0xBF, Lax(Absolute(y = true), "LAX abs,Y"))

        // ===== SAX (4) — quirky cycle counts ==============================
        put(0x83, Sax(IndirectX, "SAX (ind,X)"))
        put(0x87, Sax(ZeroPage(), "SAX zp"))
        put(0x8F, Sax(Absolute(), "SAX abs"))
        put(0x97, Sax(ZeroPage(y = true), "SAX zp,Y"))

        // ===== AHX (2) — quirky mask ======================================
        put(0x93, Ahx(IndirectY, "AHX (ind),Y"))
        put(0x9F, Ahx(Absolute(y = true), "AHX abs,Y"))

        // ===== XAA (2) — canonical 0x9B should be TAS ====================
        put(0x9B, Xaa())
        put(0xAB, Xaa())

        // ===== LAS (1) ====================================================
        put(0xBB, Las(Absolute(y = true), "LAS abs,Y"))

        // ===== DCP (6 unique) — 0xE3/0xF3 are ISC, see below ==============
        put(0xC7, Dcp(ZeroPage(), "DCP zp"))
        put(0xD7, Dcp(ZeroPage(x = true), "DCP zp,X"))
        put(0xCF, Dcp(Absolute(), "DCP abs"))
        put(0xDF, Dcp(Absolute(x = true), "DCP abs,X"))
        put(0xDB, Dcp(ZeroPage(y = true), "DCP zp,Y"))
        put(0xD3, Dcp(IndirectX, "DCP (ind,X)"))

        // ===== ISC (7) — including 0xE3/0xF3 silent overwrite ============
        put(0xE7, Isc(ZeroPage(), "ISC zp"))
        put(0xF7, Isc(ZeroPage(x = true), "ISC zp,X"))
        put(0xEF, Isc(Absolute(), "ISC abs"))
        put(0xFF, Isc(Absolute(x = true), "ISC abs,X"))
        put(0xFB, Isc(ZeroPage(y = true), "ISC zp,Y"))
        put(0xE3, Isc(IndirectX, "ISC (ind,X)"))
        put(0xF3, Isc(IndirectY, "ISC (ind),Y"))
        // ↑ Original Opcodes.kt:441,442,450,451 maps 0xE3/0xF3 first to
        // DCP then overwrites with ISC. Preserved here.

        // ===== RLA (7) ====================================================
        put(0x27, Rla(ZeroPage(), "RLA zp"))
        put(0x37, Rla(ZeroPage(x = true), "RLA zp,X"))
        put(0x2F, Rla(Absolute(), "RLA abs"))
        put(0x3F, Rla(Absolute(x = true), "RLA abs,X"))
        put(0x3B, Rla(Absolute(y = true), "RLA abs,Y"))
        put(0x23, Rla(IndirectX, "RLA (ind,X)"))
        put(0x33, Rla(IndirectY, "RLA (ind),Y)"))

        // ===== RRA (7) ====================================================
        put(0x67, Rra(ZeroPage(), "RRA zp"))
        put(0x77, Rra(ZeroPage(x = true), "RRA zp,X"))
        put(0x6F, Rra(Absolute(), "RRA abs"))
        put(0x7F, Rra(Absolute(x = true), "RRA abs,X"))
        put(0x7B, Rra(Absolute(y = true), "RRA abs,Y"))
        put(0x63, Rra(IndirectX, "RRA (ind,X)"))
        put(0x73, Rra(IndirectY, "RRA (ind),Y)"))

        // ===== SLO (7) ====================================================
        put(0x07, Slo(ZeroPage(), "SLO zp"))
        put(0x17, Slo(ZeroPage(x = true), "SLO zp,X"))
        put(0x0F, Slo(Absolute(), "SLO abs"))
        put(0x1F, Slo(Absolute(x = true), "SLO abs,X"))
        put(0x1B, Slo(Absolute(y = true), "SLO abs,Y"))
        put(0x03, Slo(IndirectX, "SLO (ind,X)"))
        put(0x13, Slo(IndirectY, "SLO (ind),Y)"))

        // ===== SRE (7) ====================================================
        put(0x47, Sre(ZeroPage(), "SRE zp"))
        put(0x57, Sre(ZeroPage(x = true), "SRE zp,X"))
        put(0x4F, Sre(Absolute(), "SRE abs"))
        put(0x5F, Sre(Absolute(x = true), "SRE abs,X"))
        put(0x5B, Sre(Absolute(y = true), "SRE abs,Y"))
        put(0x43, Sre(IndirectX, "SRE (ind,X)"))
        put(0x53, Sre(IndirectY, "SRE (ind),Y)"))

        // ===== ALR / ARR (2) ==============================================
        put(0x4B, Alr())
        put(0x6B, Arr())

        // ===== KIL (13) ===================================================
        // KIL doesn't actually halt (quirk preserved).
        listOf(0x02, 0x12, 0x22, 0x32, 0x42, 0x52, 0x62, 0x72, 0x92, 0xB2, 0xC3, 0xD2, 0xF2)
            .forEach { put(it, Kil()) }
    }

    /** Look up an opcode by its byte value (null for the 6 unmapped bytes). */
    operator fun get(code: Int): Opcode? = map[code]
}