package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.cpu.opcode.OpcodesRefactor
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.MethodSource

/**
 * Regression bar for issue #192 (Opcodes sealed-class refactor).
 *
 * **Why this test exists.** `GoldenLogTest` line-by-line compares Nestlin's
 * trace output against `src/test/resources/nestest.log`, but [GoldenLogTest]
 * has `// Ignore cycles for now` — the per-instruction cycle column is NOT
 * part of the byte-exact comparison. So cycle-count drift during the refactor
 * would slip past the only byte-exact regression test we have.
 *
 * `WorkCyclesLeftConsistencyTest` covers ~16 representative opcodes. The
 * refactor touches all 151 official + ~99 unofficial = 250 mapped opcodes;
 * one wrong helper argument is a single-character mistake that's invisible
 * to both tests above.
 *
 * **What this test does.** For every opcode byte mapped in
 * [Opcodes.map], runs a synthetic tick on a minimal Cpu and asserts the
 * post-tick `workCyclesLeft`. The expected value pins down the CURRENT
 * behaviour — including known quirks like LAX hardcoding `2`, JMP indirect
 * using `3` instead of real 6502 `5`, and `0xE3`/`0xF3` mapping to ISC
 * (not DCP). Each quirk gets an inline comment so the refactor preserves
 * it deliberately, not by accident.
 *
 * **Why expected = cycles - 1.** `Cpu.tick()` decrements `workCyclesLeft`
 * after the opcode runs (`Cpu.kt:217`). So a 2-cycle instruction leaves
 * `workCyclesLeft == 1` after one tick. The expected values below are the
 * post-decrement values, not the real-6502 base cycles.
 */
class OpcodeCycleTableTest {

    /** A single opcode row in the cycle table. */
    data class OpcodeRow(
        val byte: Int,
        val cyclesAfterTick: Int,
        /** Bytes laid out at PC+1, PC+2, ... in memory before the tick.
         *  Length must match the addressing mode's operand width. */
        val operandBytes: List<Byte> = emptyList(),
        /** Optional CPU state setup (register init, status flags, stack,
         *  reset vector, etc.) before the tick. */
        val setup: (Cpu) -> Unit = {},
        /** Human-readable mnemonic, used in the parameterized test name. */
        val mnemonic: String = "",
    )

    companion object {
        /** PC where every row's opcode is laid out. Chosen in the zero-page
         *  mirror range so indirect addressing (which wraps the zero page)
         *  doesn't blow up. */
        private const val PC = 0x0400

        /** Address where indirect-X / indirect-Y pointers are stored when
         *  a row needs them. Lives in the same page as [PC] so the
         *  6502's zero-page wrap for `($zp,X)` works naturally. */
        private const val POINTER_BASE = 0x0040

        @JvmStatic
        fun rows(): List<Arguments> = buildTable()

        private fun b(value: Int): Byte = value.toSignedByte()

        /** Build the full 250-row cycle table by reading the dispatch map. */
        private fun buildTable(): List<Arguments> {
            // Build the table from the current dispatch — but override rows
            // where current behaviour is known to be quirky so the table is
            // self-documenting.
            val rows = mutableListOf<OpcodeRow>()

            // ===== BRANCH (8) — not-taken path, 2 cycles ====================
            // For all 8 branches we set the condition so the branch is NOT
            // taken, locking the 2-cycle not-taken count. The taken path is
            // already covered by WorkCyclesLeftConsistencyTest.
            //
            // Each branch's condition reads a flag (sometimes negated); the
            // setup lambda sets that flag so the condition is FALSE, locking
            // the not-taken path.
            rows += OpcodeRow(0x10, 1, listOf(b(0x00)),
                { it.processorStatus.negative = true }, "BPL")   // cond: !negative
            rows += OpcodeRow(0x30, 1, listOf(b(0x00)),
                { it.processorStatus.negative = false }, "BMI")  // cond: negative
            rows += OpcodeRow(0x50, 1, listOf(b(0x00)),
                { it.processorStatus.overflow = true }, "BVC")   // cond: !overflow
            rows += OpcodeRow(0x70, 1, listOf(b(0x00)),
                { it.processorStatus.overflow = false }, "BVS")  // cond: overflow
            rows += OpcodeRow(0x90, 1, listOf(b(0x00)),
                { it.processorStatus.carry = true }, "BCC")      // cond: !carry
            rows += OpcodeRow(0xB0, 1, listOf(b(0x00)),
                { it.processorStatus.carry = false }, "BCS")      // cond: carry
            rows += OpcodeRow(0xD0, 1, listOf(b(0x00)),
                { it.processorStatus.zero = true }, "BNE")       // cond: !zero
            rows += OpcodeRow(0xF0, 1, listOf(b(0x00)),
                { it.processorStatus.zero = false }, "BEQ")       // cond: zero

            // ===== STA/STX/STY (13) =========================================
            // Cycles table from Opcodes.kt:11-16:
            //   zp=3, abs=4, (ind,X)=6, (ind),Y=6, zp,X/Y=4, abs,X/Y=5
            rows += OpcodeRow(0x81, 5, listOf(b(POINTER_BASE and 0xFF)),
                indirectXPointer(), "STA (ind,X)")
            rows += OpcodeRow(0x84, 2, listOf(b(0x42)), mnemonic = "STY zp")
            rows += OpcodeRow(0x85, 2, listOf(b(0x42)), mnemonic = "STA zp")
            rows += OpcodeRow(0x86, 2, listOf(b(0x42)), mnemonic = "STX zp")
            rows += OpcodeRow(0x8C, 3, listOf(b(0x00), b(0x05)), mnemonic = "STY abs")
            rows += OpcodeRow(0x8D, 3, listOf(b(0x00), b(0x05)), mnemonic = "STA abs")
            rows += OpcodeRow(0x8E, 3, listOf(b(0x00), b(0x05)), mnemonic = "STX abs")
            rows += OpcodeRow(0x91, 5, listOf(b(POINTER_BASE and 0xFF)),
                indirectYPointer(), "STA (ind),Y")
            rows += OpcodeRow(0x94, 3, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "STY zp,X")
            rows += OpcodeRow(0x95, 3, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "STA zp,X")
            rows += OpcodeRow(0x96, 3, listOf(b(0x42)),
                { it.registers.indexY = 0x00 }, "STX zp,Y")
            rows += OpcodeRow(0x99, 4, listOf(b(0x00), b(0x05)),
                { it.registers.indexY = 0x00 }, "STA abs,Y")
            rows += OpcodeRow(0x9D, 4, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "STA abs,X")

            // ===== Flag set / clear (7) =====================================
            rows += OpcodeRow(0x18, 1, mnemonic = "CLC")
            rows += OpcodeRow(0x38, 1, mnemonic = "SEC")
            rows += OpcodeRow(0x58, 1, mnemonic = "CLI")
            rows += OpcodeRow(0x78, 1, mnemonic = "SEI")
            rows += OpcodeRow(0xD8, 1, mnemonic = "CLD")
            rows += OpcodeRow(0xF8, 1, mnemonic = "SED")
            rows += OpcodeRow(0xB8, 1, mnemonic = "CLV")

            // ===== ADC (8) ==================================================
            // Cycles: imm=2, zp=3, abs=4, (ind,X)=6, (ind),Y=5, zp,X=4,
            // abs,X=4, abs,Y=4
            rows += OpcodeRow(0x61, 5, listOf(b(POINTER_BASE and 0xFF)),
                indirectXPointer(), "ADC (ind,X)")
            rows += OpcodeRow(0x65, 2, listOf(b(0x42)), mnemonic = "ADC zp")
            rows += OpcodeRow(0x69, 1, listOf(b(0x00)), mnemonic = "ADC #imm")
            rows += OpcodeRow(0x6D, 3, listOf(b(0x00), b(0x05)), mnemonic = "ADC abs")
            rows += OpcodeRow(0x71, 4, listOf(b(POINTER_BASE and 0xFF)),
                indirectYPointer(), "ADC (ind),Y")
            rows += OpcodeRow(0x75, 3, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "ADC zp,X")
            rows += OpcodeRow(0x79, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexY = 0x00 }, "ADC abs,Y")
            rows += OpcodeRow(0x7D, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "ADC abs,X")

            // ===== SBC (8) ==================================================
            rows += OpcodeRow(0xE1, 5, listOf(b(POINTER_BASE and 0xFF)),
                indirectXPointer(), "SBC (ind,X)")
            rows += OpcodeRow(0xE5, 2, listOf(b(0x42)), mnemonic = "SBC zp")
            rows += OpcodeRow(0xE9, 1, listOf(b(0x00)), mnemonic = "SBC #imm")
            rows += OpcodeRow(0xED, 3, listOf(b(0x00), b(0x05)), mnemonic = "SBC abs")
            rows += OpcodeRow(0xF1, 4, listOf(b(POINTER_BASE and 0xFF)),
                indirectYPointer(), "SBC (ind),Y")
            rows += OpcodeRow(0xF5, 3, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "SBC zp,X")
            rows += OpcodeRow(0xF9, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexY = 0x00 }, "SBC abs,Y")
            rows += OpcodeRow(0xFD, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "SBC abs,X")

            // ===== Push (2) =================================================
            rows += OpcodeRow(0x08, 2, mnemonic = "PHP")
            rows += OpcodeRow(0x48, 2, mnemonic = "PHA")

            // ===== ORA / AND / EOR (24) =====================================
            // Same cycle table as ADC: imm=2, zp=3, abs=4, (ind,X)=6,
            // (ind),Y=5, zp,X=4, abs,X=4, abs,Y=4
            addLogicFamily(rows, "ORA", 0x01,
                opcodeX = 0x01, opcodeImm = 0x09, opcodeZp = 0x05, opcodeAbs = 0x0D,
                opcodeIndX = 0x01, opcodeIndY = 0x11, opcodeZpX = 0x15,
                opcodeAbsX = 0x1D, opcodeAbsY = 0x19,
            )
            addLogicFamily(rows, "AND", 0x21,
                opcodeX = 0x21, opcodeImm = 0x29, opcodeZp = 0x25, opcodeAbs = 0x2D,
                opcodeIndX = 0x21, opcodeIndY = 0x31, opcodeZpX = 0x35,
                opcodeAbsX = 0x3D, opcodeAbsY = 0x39,
            )
            addLogicFamily(rows, "EOR", 0x41,
                opcodeX = 0x41, opcodeImm = 0x49, opcodeZp = 0x45, opcodeAbs = 0x4D,
                opcodeIndX = 0x41, opcodeIndY = 0x51, opcodeZpX = 0x55,
                opcodeAbsX = 0x5D, opcodeAbsY = 0x59,
            )

            // ===== Loads (18) ===============================================
            // Same cycle table as ADC: imm=2, zp=3, abs=4, (ind,X)=6,
            // (ind),Y=5, zp,X=4, abs,X=4, abs,Y=4
            rows += OpcodeRow(0xA0, 1, listOf(b(0x55)), mnemonic = "LDY #imm")
            rows += OpcodeRow(0xA1, 5, listOf(b(POINTER_BASE and 0xFF)),
                indirectXPointer(), "LDA (ind,X)")
            rows += OpcodeRow(0xA2, 1, listOf(b(0x55)), mnemonic = "LDX #imm")
            rows += OpcodeRow(0xA4, 2, listOf(b(0x42)), mnemonic = "LDY zp")
            rows += OpcodeRow(0xA5, 2, listOf(b(0x42)), mnemonic = "LDA zp")
            rows += OpcodeRow(0xA6, 2, listOf(b(0x42)), mnemonic = "LDX zp")
            rows += OpcodeRow(0xA9, 1, listOf(b(0x55)), mnemonic = "LDA #imm")
            rows += OpcodeRow(0xAC, 3, listOf(b(0x00), b(0x05)), mnemonic = "LDY abs")
            rows += OpcodeRow(0xAD, 3, listOf(b(0x00), b(0x05)), mnemonic = "LDA abs")
            rows += OpcodeRow(0xAE, 3, listOf(b(0x00), b(0x05)), mnemonic = "LDX abs")
            rows += OpcodeRow(0xB1, 4, listOf(b(POINTER_BASE and 0xFF)),
                indirectYPointer(), "LDA (ind),Y")
            rows += OpcodeRow(0xB4, 3, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "LDY zp,X")
            rows += OpcodeRow(0xB5, 3, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "LDA zp,X")
            rows += OpcodeRow(0xB6, 3, listOf(b(0x42)),
                { it.registers.indexY = 0x00 }, "LDX zp,Y")
            rows += OpcodeRow(0xB9, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexY = 0x00 }, "LDA abs,Y")
            rows += OpcodeRow(0xBC, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "LDY abs,X")
            rows += OpcodeRow(0xBD, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "LDA abs,X")
            rows += OpcodeRow(0xBE, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexY = 0x00 }, "LDX abs,Y")

            // ===== BIT (2) ==================================================
            rows += OpcodeRow(0x24, 2, listOf(b(0x42)), mnemonic = "BIT zp")
            rows += OpcodeRow(0x2C, 3, listOf(b(0x00), b(0x05)), mnemonic = "BIT abs")

            // ===== CMP / CPY / CPX (14) =====================================
            // Same cycle table as ADC.
            rows += OpcodeRow(0xC0, 1, listOf(b(0x00)), mnemonic = "CPY #imm")
            rows += OpcodeRow(0xC1, 5, listOf(b(POINTER_BASE and 0xFF)),
                indirectXPointer(), "CMP (ind,X)")
            rows += OpcodeRow(0xC4, 2, listOf(b(0x42)), mnemonic = "CPY zp")
            rows += OpcodeRow(0xC5, 2, listOf(b(0x42)), mnemonic = "CMP zp")
            rows += OpcodeRow(0xC9, 1, listOf(b(0x00)), mnemonic = "CMP #imm")
            rows += OpcodeRow(0xCC, 3, listOf(b(0x00), b(0x05)), mnemonic = "CPY abs")
            rows += OpcodeRow(0xCD, 3, listOf(b(0x00), b(0x05)), mnemonic = "CMP abs")
            rows += OpcodeRow(0xD1, 4, listOf(b(POINTER_BASE and 0xFF)),
                indirectYPointer(), "CMP (ind),Y")
            rows += OpcodeRow(0xD5, 3, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "CMP zp,X")
            rows += OpcodeRow(0xD9, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexY = 0x00 }, "CMP abs,Y")
            rows += OpcodeRow(0xDD, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "CMP abs,X")
            rows += OpcodeRow(0xE0, 1, listOf(b(0x00)), mnemonic = "CPX #imm")
            rows += OpcodeRow(0xE4, 2, listOf(b(0x42)), mnemonic = "CPX zp")
            rows += OpcodeRow(0xEC, 3, listOf(b(0x00), b(0x05)), mnemonic = "CPX abs")

            // ===== Shift/Rotate memory variants (16) ========================
            // Cycles: zp=5, abs=6, zp,X=6, abs,X=7
            rows += OpcodeRow(0x06, 4, listOf(b(0x42)), mnemonic = "ASL zp")
            rows += OpcodeRow(0x0E, 5, listOf(b(0x00), b(0x05)), mnemonic = "ASL abs")
            rows += OpcodeRow(0x16, 5, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "ASL zp,X")
            rows += OpcodeRow(0x1E, 6, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "ASL abs,X")
            rows += OpcodeRow(0x26, 4, listOf(b(0x42)), mnemonic = "ROL zp")
            rows += OpcodeRow(0x2E, 5, listOf(b(0x00), b(0x05)), mnemonic = "ROL abs")
            rows += OpcodeRow(0x36, 5, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "ROL zp,X")
            rows += OpcodeRow(0x3E, 6, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "ROL abs,X")
            rows += OpcodeRow(0x46, 4, listOf(b(0x42)), mnemonic = "LSR zp")
            rows += OpcodeRow(0x4E, 5, listOf(b(0x00), b(0x05)), mnemonic = "LSR abs")
            rows += OpcodeRow(0x56, 5, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "LSR zp,X")
            rows += OpcodeRow(0x5E, 6, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "LSR abs,X")
            rows += OpcodeRow(0x66, 4, listOf(b(0x42)), mnemonic = "ROR zp")
            rows += OpcodeRow(0x6E, 5, listOf(b(0x00), b(0x05)), mnemonic = "ROR abs")
            rows += OpcodeRow(0x76, 5, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "ROR zp,X")
            rows += OpcodeRow(0x7E, 6, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "ROR abs,X")

            // ===== INC/DEC memory (8) =======================================
            // Cycles: zp=5, abs=6, zp,X=6, abs,X=7
            rows += OpcodeRow(0xC6, 4, listOf(b(0x42)), mnemonic = "DEC zp")
            rows += OpcodeRow(0xCE, 5, listOf(b(0x00), b(0x05)), mnemonic = "DEC abs")
            rows += OpcodeRow(0xD6, 5, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "DEC zp,X")
            rows += OpcodeRow(0xDE, 6, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "DEC abs,X")
            rows += OpcodeRow(0xE6, 4, listOf(b(0x42)), mnemonic = "INC zp")
            rows += OpcodeRow(0xEE, 5, listOf(b(0x00), b(0x05)), mnemonic = "INC abs")
            rows += OpcodeRow(0xF6, 5, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "INC zp,X")
            rows += OpcodeRow(0xFE, 6, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "INC abs,X")

            // ===== Transfers (5 with flags + TXS without flags) =============
            rows += OpcodeRow(0x8A, 1, mnemonic = "TXA")
            rows += OpcodeRow(0x98, 1, mnemonic = "TYA")
            // 0x9A TXS — does NOT set flags. Inline Opcode at Opcodes.kt:181.
            rows += OpcodeRow(0x9A, 1, mnemonic = "TXS")
            rows += OpcodeRow(0xA8, 1, mnemonic = "TAY")
            rows += OpcodeRow(0xAA, 1, mnemonic = "TAX")
            rows += OpcodeRow(0xBA, 1, mnemonic = "TSX")

            // ===== Inline-implied opcodes (17) ==============================
            // BRK (7), JSR (6), JMP abs (3), JMP ind (3 — known quirk; real
            // 6502 is 5 but `jump` helper hardcodes 3), NOP implied (2),
            // Accumulator shift/rotate (2 each), RTS (6), PLA (4),
            // INY/INY/DEX/DEY (2), PLP (4), RTI (6).
            rows += OpcodeRow(0x00, 6, mnemonic = "BRK")
            rows += OpcodeRow(0x20, 5, listOf(b(0x00), b(0x05)), mnemonic = "JSR")
            rows += OpcodeRow(0x4C, 2, listOf(b(0x00), b(0x05)), mnemonic = "JMP abs")
            // JMP indirect — KNOWN QUIRK: jump helper sets workCyclesLeft=3
            // for both abs AND indirect, but real 6502 indirect is 5 cycles.
            // Preserve until a follow-up fixes the helper.
            rows += OpcodeRow(0x6C, 2, listOf(b(0x00), b(0x05)),
                jmpIndirectPointer(), "JMP (ind) [quirk: 3 cycles; real 6502 = 5]")
            rows += OpcodeRow(0xEA, 1, mnemonic = "NOP implied")
            rows += OpcodeRow(0x0A, 1, mnemonic = "ASL A")
            rows += OpcodeRow(0x2A, 1, mnemonic = "ROL A")
            rows += OpcodeRow(0x4A, 1, mnemonic = "LSR A")
            rows += OpcodeRow(0x6A, 1, mnemonic = "ROR A")
            rows += OpcodeRow(0x60, 5, mnemonic = "RTS")
            rows += OpcodeRow(0x68, 3, mnemonic = "PLA")
            rows += OpcodeRow(0xC8, 1, mnemonic = "INY")
            rows += OpcodeRow(0x88, 1, mnemonic = "DEY")
            rows += OpcodeRow(0xE8, 1, mnemonic = "INX")
            rows += OpcodeRow(0xCA, 1, mnemonic = "DEX")
            rows += OpcodeRow(0x28, 3, mnemonic = "PLP")
            rows += OpcodeRow(0x40, 5, mnemonic = "RTI")

            // ===== Unofficial NOP variants (28) ==============================
            // All advance PC but do nothing else.
            // Cycles: zp=3, abs=4, zp,X=4, abs,X=4, imm=2, implied=2
            // Post-tick: zp=2, abs=3, zp,X=3, abs,X=3, imm=1, implied=1
            rows += OpcodeRow(0x04, 2, listOf(b(0x42)), mnemonic = "NOP zp")
            rows += OpcodeRow(0x44, 2, listOf(b(0x42)), mnemonic = "NOP zp")
            rows += OpcodeRow(0x64, 2, listOf(b(0x42)), mnemonic = "NOP zp")
            rows += OpcodeRow(0x0C, 3, listOf(b(0x00), b(0x05)), mnemonic = "NOP abs")
            rows += OpcodeRow(0x1C, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "NOP abs,X")
            rows += OpcodeRow(0x3C, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "NOP abs,X")
            rows += OpcodeRow(0x5C, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "NOP abs,X")
            rows += OpcodeRow(0x7C, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "NOP abs,X")
            rows += OpcodeRow(0xDC, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "NOP abs,X")
            rows += OpcodeRow(0xFC, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "NOP abs,X")
            rows += OpcodeRow(0x14, 3, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "NOP zp,X")
            rows += OpcodeRow(0x34, 3, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "NOP zp,X")
            rows += OpcodeRow(0x54, 3, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "NOP zp,X")
            rows += OpcodeRow(0x74, 3, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "NOP zp,X")
            rows += OpcodeRow(0xD4, 3, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "NOP zp,X")
            rows += OpcodeRow(0xF4, 3, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "NOP zp,X")
            rows += OpcodeRow(0x80, 1, listOf(b(0x00)), mnemonic = "NOP imm")
            rows += OpcodeRow(0x82, 1, listOf(b(0x00)), mnemonic = "NOP imm")
            rows += OpcodeRow(0x89, 1, listOf(b(0x00)), mnemonic = "NOP imm")
            rows += OpcodeRow(0xC2, 1, listOf(b(0x00)), mnemonic = "NOP imm")
            rows += OpcodeRow(0xE2, 1, listOf(b(0x00)), mnemonic = "NOP imm")
            rows += OpcodeRow(0xEB, 1, listOf(b(0x00)), mnemonic = "NOP imm")
            rows += OpcodeRow(0x1A, 1, mnemonic = "NOP implied")
            rows += OpcodeRow(0x3A, 1, mnemonic = "NOP implied")
            rows += OpcodeRow(0x5A, 1, mnemonic = "NOP implied")
            rows += OpcodeRow(0x7A, 1, mnemonic = "NOP implied")
            rows += OpcodeRow(0xDA, 1, mnemonic = "NOP implied")
            rows += OpcodeRow(0xFA, 1, mnemonic = "NOP implied")

            // ===== LAX (6) — KNOWN QUIRK ====================================
            // The `lax` helper hardcodes workCyclesLeft = 2 for ALL addressing
            // modes. Real 6502 cycles are 6, 3, 4, 5, 4, 4. Preserve current
            // behaviour; out of scope to fix in #192.
            rows += OpcodeRow(0xA3, 1, listOf(b(POINTER_BASE and 0xFF)),
                indirectXPointer(), "LAX (ind,X) [quirk: 2 cycles; real 6502 = 6]")
            rows += OpcodeRow(0xA7, 1, listOf(b(0x42)),
                { /* zp */ }, "LAX zp [quirk: 2 cycles; real 6502 = 3]")
            rows += OpcodeRow(0xAF, 1, listOf(b(0x00), b(0x05)),
                { /* abs */ }, "LAX abs [quirk: 2 cycles; real 6502 = 4]")
            rows += OpcodeRow(0xB3, 1, listOf(b(POINTER_BASE and 0xFF)),
                indirectYPointer(), "LAX (ind),Y [quirk: 2 cycles; real 6502 = 5]")
            rows += OpcodeRow(0xB7, 1, listOf(b(0x42)),
                { it.registers.indexY = 0x00 }, "LAX zp,Y [quirk: 2 cycles; real 6502 = 4]")
            rows += OpcodeRow(0xBF, 1, listOf(b(0x00), b(0x05)),
                { it.registers.indexY = 0x00 }, "LAX abs,Y [quirk: 2 cycles; real 6502 = 4]")

            // ===== SAX (4) — KNOWN QUIRK ====================================
            // The `sax` helper hardcodes workCyclesLeft = 4 for ALL
            // addressing modes. Real 6502 cycles: zp=3, abs=4, (ind,X)=6,
            // zp,Y=4. Preserve current behaviour; out of scope to fix in
            // #192 (same shape as the LAX=2 quirk above).
            rows += OpcodeRow(0x83, 3, listOf(b(POINTER_BASE and 0xFF)),
                indirectXPointer(), "SAX (ind,X) [quirk: 4 cycles; real 6502 = 6]")
            rows += OpcodeRow(0x87, 3, listOf(b(0x42)),
                { /* zp */ }, "SAX zp [quirk: 4 cycles; real 6502 = 3]")
            rows += OpcodeRow(0x8F, 3, listOf(b(0x00), b(0x05)),
                { /* abs */ }, "SAX abs [quirk: 4 cycles; real 6502 = 4]")
            rows += OpcodeRow(0x97, 3, listOf(b(0x42)),
                { it.registers.indexY = 0x00 }, "SAX zp,Y [quirk: 4 cycles; real 6502 = 4]")

            // ===== AHX (2) — KNOWN QUIRK =====================================
            // `ahx` helper uses mask `0x07` for the high byte (looks like a
            // typo for `0xFF`). Preserve; out of scope to fix in #192.
            rows += OpcodeRow(0x93, 3, listOf(b(POINTER_BASE and 0xFF)),
                indirectYPointer(), "AHX (ind),Y [quirk: mask=0x07; real 6502 = 0xFF]")
            rows += OpcodeRow(0x9F, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexY = 0x00 }, "AHX abs,Y [quirk: mask=0x07; real 6502 = 0xFF]")

            // ===== XAA (2) ==================================================
            // Both 0x9B and 0xAB are mapped to XAA. NESdev canonical says
            // 0x9B is TAS, 0xAB is XAA. Preserve the current mapping.
            rows += OpcodeRow(0x9B, 1, mnemonic = "XAA [0x9B canonical=TAS]")
            rows += OpcodeRow(0xAB, 1, mnemonic = "XAA")

            // ===== LAS (1) ==================================================
            rows += OpcodeRow(0xBB, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexY = 0x00 }, "LAS abs,Y")

            // ===== DCP (6 unique) ===========================================
            // 0xE3 and 0xF3 are also ISC entries — see below. The ISC writes
            // come AFTER the DCP writes in Opcodes.kt:441-451, so the
            // dispatch table has ISC for both bytes (the silent overwrite).
            rows += OpcodeRow(0xC7, 5, listOf(b(0x42)), mnemonic = "DCP zp")
            rows += OpcodeRow(0xD7, 5, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "DCP zp,X")
            rows += OpcodeRow(0xCF, 5, listOf(b(0x00), b(0x05)), mnemonic = "DCP abs")
            rows += OpcodeRow(0xDF, 5, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "DCP abs,X")
            rows += OpcodeRow(0xDB, 5, listOf(b(0x42)),
                { it.registers.indexY = 0x00 }, "DCP zp,Y")
            rows += OpcodeRow(0xD3, 5, listOf(b(POINTER_BASE and 0xFF)),
                indirectXPointer(), "DCP (ind,X)")

            // ===== ISC (7 — including the 0xE3/0xF3 silent overwrite) =======
            // 0xE3 and 0xF3 are mapped to ISC (the ISC assignment overwrites
            // the earlier DCP assignment in Opcodes.kt:441,442,450,451).
            rows += OpcodeRow(0xE7, 5, listOf(b(0x42)), mnemonic = "ISC zp")
            rows += OpcodeRow(0xF7, 5, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "ISC zp,X")
            rows += OpcodeRow(0xEF, 5, listOf(b(0x00), b(0x05)), mnemonic = "ISC abs")
            rows += OpcodeRow(0xFF, 5, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "ISC abs,X")
            rows += OpcodeRow(0xFB, 5, listOf(b(0x42)),
                { it.registers.indexY = 0x00 }, "ISC zp,Y")
            rows += OpcodeRow(0xE3, 5, listOf(b(POINTER_BASE and 0xFF)),
                indirectXPointer(), "ISC (ind,X) [0xE3: ISC wins over DCP]")
            rows += OpcodeRow(0xF3, 5, listOf(b(POINTER_BASE and 0xFF)),
                indirectYPointer(), "ISC (ind),Y [0xF3: ISC wins over DCP]")

            // ===== RLA (7) ==================================================
            rows += OpcodeRow(0x27, 4, listOf(b(0x42)), mnemonic = "RLA zp")
            rows += OpcodeRow(0x37, 4, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "RLA zp,X")
            rows += OpcodeRow(0x2F, 4, listOf(b(0x00), b(0x05)), mnemonic = "RLA abs")
            rows += OpcodeRow(0x3F, 4, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "RLA abs,X")
            rows += OpcodeRow(0x3B, 4, listOf(b(0x00), b(0x05)),
                { it.registers.indexY = 0x00 }, "RLA abs,Y")
            rows += OpcodeRow(0x23, 4, listOf(b(POINTER_BASE and 0xFF)),
                indirectXPointer(), "RLA (ind,X)")
            rows += OpcodeRow(0x33, 4, listOf(b(POINTER_BASE and 0xFF)),
                indirectYPointer(), "RLA (ind),Y")

            // ===== RRA (7) ==================================================
            rows += OpcodeRow(0x67, 4, listOf(b(0x42)), mnemonic = "RRA zp")
            rows += OpcodeRow(0x77, 4, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "RRA zp,X")
            rows += OpcodeRow(0x6F, 4, listOf(b(0x00), b(0x05)), mnemonic = "RRA abs")
            rows += OpcodeRow(0x7F, 4, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "RRA abs,X")
            rows += OpcodeRow(0x7B, 4, listOf(b(0x00), b(0x05)),
                { it.registers.indexY = 0x00 }, "RRA abs,Y")
            rows += OpcodeRow(0x63, 4, listOf(b(POINTER_BASE and 0xFF)),
                indirectXPointer(), "RRA (ind,X)")
            rows += OpcodeRow(0x73, 4, listOf(b(POINTER_BASE and 0xFF)),
                indirectYPointer(), "RRA (ind),Y")

            // ===== SLO (7) ==================================================
            rows += OpcodeRow(0x07, 4, listOf(b(0x42)), mnemonic = "SLO zp")
            rows += OpcodeRow(0x17, 4, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "SLO zp,X")
            rows += OpcodeRow(0x0F, 4, listOf(b(0x00), b(0x05)), mnemonic = "SLO abs")
            rows += OpcodeRow(0x1F, 4, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "SLO abs,X")
            rows += OpcodeRow(0x1B, 4, listOf(b(0x00), b(0x05)),
                { it.registers.indexY = 0x00 }, "SLO abs,Y")
            rows += OpcodeRow(0x03, 4, listOf(b(POINTER_BASE and 0xFF)),
                indirectXPointer(), "SLO (ind,X)")
            rows += OpcodeRow(0x13, 4, listOf(b(POINTER_BASE and 0xFF)),
                indirectYPointer(), "SLO (ind),Y")

            // ===== SRE (7) ==================================================
            rows += OpcodeRow(0x47, 4, listOf(b(0x42)), mnemonic = "SRE zp")
            rows += OpcodeRow(0x57, 4, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "SRE zp,X")
            rows += OpcodeRow(0x4F, 4, listOf(b(0x00), b(0x05)), mnemonic = "SRE abs")
            rows += OpcodeRow(0x5F, 4, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "SRE abs,X")
            rows += OpcodeRow(0x5B, 4, listOf(b(0x00), b(0x05)),
                { it.registers.indexY = 0x00 }, "SRE abs,Y")
            rows += OpcodeRow(0x43, 4, listOf(b(POINTER_BASE and 0xFF)),
                indirectXPointer(), "SRE (ind,X)")
            rows += OpcodeRow(0x53, 4, listOf(b(POINTER_BASE and 0xFF)),
                indirectYPointer(), "SRE (ind),Y")

            // ===== ALR / ARR (2) ============================================
            rows += OpcodeRow(0x4B, 1, listOf(b(0x00)), mnemonic = "ALR #imm")
            rows += OpcodeRow(0x6B, 1, listOf(b(0x00)), mnemonic = "ARR #imm")

            // ===== KIL (13) =================================================
            // Does NOT actually halt — consumes 2 cycles and advances PC by
            // one byte per tick. Preserve; out of scope to make a true halt.
            rows += OpcodeRow(0x02, 1, mnemonic = "KIL [quirk: doesn't halt]")
            rows += OpcodeRow(0x12, 1, mnemonic = "KIL")
            rows += OpcodeRow(0x22, 1, mnemonic = "KIL")
            rows += OpcodeRow(0x32, 1, mnemonic = "KIL")
            rows += OpcodeRow(0x42, 1, mnemonic = "KIL")
            rows += OpcodeRow(0x52, 1, mnemonic = "KIL")
            rows += OpcodeRow(0x62, 1, mnemonic = "KIL")
            rows += OpcodeRow(0x72, 1, mnemonic = "KIL")
            rows += OpcodeRow(0x92, 1, mnemonic = "KIL")
            rows += OpcodeRow(0xB2, 1, mnemonic = "KIL")
            rows += OpcodeRow(0xC3, 1, mnemonic = "KIL")
            rows += OpcodeRow(0xD2, 1, mnemonic = "KIL")
            rows += OpcodeRow(0xF2, 1, mnemonic = "KIL")

            // ===== Sanity: every row's opcode must actually be mapped =======
            // This catches typos in the table — if a row's byte isn't in the
            // dispatch table, fail the table build loudly.
            val mappedBytes = OpcodesRefactor.map.keys.toSet()
            val unmapped = rows.map { it.byte }.distinct().filter { it !in mappedBytes }
            require(unmapped.isEmpty()) {
                "OpcodeCycleTableTest references unmapped bytes: " +
                    unmapped.joinToString(", ") { "0x%02X".format(it) }
            }

            return rows.map { Arguments.of(it.byte, it.cyclesAfterTick,
                it.operandBytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) },
                it.mnemonic, it) }
        }

        /** Add 8 rows for an AND/ORA/EOR family — all share the same cycle
         *  table: imm=2, zp=3, abs=4, (ind,X)=6, (ind),Y=5, zp,X=4,
         *  abs,X=4, abs,Y=4. Post-tick: 1/2/3/5/4/3/3/3. */
        private fun addLogicFamily(
            rows: MutableList<OpcodeRow>,
            family: String,
            @Suppress("UNUSED_PARAMETER") unused: Int,
            opcodeX: Int, opcodeImm: Int, opcodeZp: Int, opcodeAbs: Int,
            opcodeIndX: Int, opcodeIndY: Int, opcodeZpX: Int,
            opcodeAbsX: Int, opcodeAbsY: Int,
        ) {
            rows += OpcodeRow(opcodeIndX, 5, listOf(b(POINTER_BASE and 0xFF)),
                indirectXPointer(), "$family (ind,X)")
            rows += OpcodeRow(opcodeZp, 2, listOf(b(0x42)), mnemonic = "$family zp")
            rows += OpcodeRow(opcodeImm, 1, listOf(b(0x00)), mnemonic = "$family #imm")
            rows += OpcodeRow(opcodeAbs, 3, listOf(b(0x00), b(0x05)), mnemonic = "$family abs")
            rows += OpcodeRow(opcodeIndY, 4, listOf(b(POINTER_BASE and 0xFF)),
                indirectYPointer(), "$family (ind),Y")
            rows += OpcodeRow(opcodeZpX, 3, listOf(b(0x42)),
                { it.registers.indexX = 0x00 }, "$family zp,X")
            rows += OpcodeRow(opcodeAbsY, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexY = 0x00 }, "$family abs,Y")
            rows += OpcodeRow(opcodeAbsX, 3, listOf(b(0x00), b(0x05)),
                { it.registers.indexX = 0x00 }, "$family abs,X")
        }

        /** Lay down a (zp,X) pointer at POINTER_BASE pointing at PC.
         *  The operand byte is (POINTER_BASE and 0xFF); with X=0 the
         *  pointer read is at POINTER_BASE itself. */
        private fun indirectXPointer(): (Cpu) -> Unit = { cpu ->
            cpu.memory[POINTER_BASE] = b(PC and 0xFF)
            cpu.memory[POINTER_BASE + 1] = b((PC shr 8) and 0xFF)
        }

        /** Lay down a (zp),Y pointer at POINTER_BASE pointing at PC. */
        private fun indirectYPointer(): (Cpu) -> Unit = { cpu ->
            cpu.memory[POINTER_BASE] = b(PC and 0xFF)
            cpu.memory[POINTER_BASE + 1] = b((PC shr 8) and 0xFF)
        }

        /** Lay down a 16-bit pointer at the operand address (PC+1) for JMP
         *  indirect. The pointer points back at PC so the dispatch reads a
         *  valid 16-bit address. */
        private fun jmpIndirectPointer(): (Cpu) -> Unit = { cpu ->
            cpu.memory[PC + 1] = b(PC and 0xFF)
            cpu.memory[PC + 2] = b((PC shr 8) and 0xFF)
        }
    }

    @ParameterizedTest(name = "0x{0} ({3}) -> cycles {1}")
    @MethodSource("rows")
    fun `post-tick workCyclesLeft matches the cycle table`(
        opcodeByte: Int,
        expectedCyclesAfterTick: Int,
        @Suppress("UNUSED_PARAMETER") operandHex: String,
        @Suppress("UNUSED_PARAMETER") mnemonic: String,
        row: OpcodeRow,
    ) {
        val cpu = Cpu(Memory.createWithApu().first).apply { reset() }
        cpu.registers.programCounter = PC.toSignedShort()
        cpu.memory[PC] = opcodeByte.toSignedByte()
        row.operandBytes.forEachIndexed { i, value -> cpu.memory[PC + 1 + i] = value }
        row.setup(cpu)

        cpu.tick()

        assertThat(
            "opcode 0x%02X ($mnemonic): expected post-tick workCyclesLeft=$expectedCyclesAfterTick"
                .format(opcodeByte),
            cpu.workCyclesLeft,
            equalTo(expectedCyclesAfterTick),
        )
    }
}