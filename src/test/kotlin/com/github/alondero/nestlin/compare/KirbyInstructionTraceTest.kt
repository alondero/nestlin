package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.ppu.Frame
import org.junit.Test
import java.nio.file.Paths
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.greaterThanOrEqualTo
import com.natpryce.hamkrest.contains

/**
 * Detailed trace of Kirby's boot sequence - decode operands correctly.
 *
 * This test validates that Kirby's boot sequence completes and NMIs start firing.
 */
class KirbyInstructionTraceTest {

    @Test
    fun traceKirbyWithCorrectDecoding() {
        val romPath = Paths.get("testroms/kirby.nes")
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        // Trace up to 500,000 instructions
        val trace = nestlin.cpu.enableInstructionTrace(500000)

        var frameCount = 0
        val ppuCtrlWrites = mutableListOf<String>()

        // Track NMI handler entries
        var nmiHandlerEntryCount = 0

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                frameCount++
                if (frameCount >= 20) nestlin.stop()
            }
        })

        nestlin.powerReset()
        nestlin.start()

        val nmiVector = (nestlin.memory[0xFFFB].toUnsignedInt() shl 8) or nestlin.memory[0xFFFA].toUnsignedInt()

        // Analyze all $2000 writes from the trace
        var lastA = 0
        trace.forEachIndexed { idx, (pc, opcode) ->
            // Check for NMI handler entry
            if (pc == nmiVector) {
                nmiHandlerEntryCount++
            }

            // Update last known A value if it was an LDA immediate or zero page
            if (opcode == 0xA9) {
                lastA = nestlin.memory[pc + 1].toUnsignedInt()
            }
            if (opcode == 0xA5) {
                lastA = nestlin.memory[nestlin.memory[pc + 1].toUnsignedInt()].toUnsignedInt()
            }

            // Check for STA to $2000 (PPUCTRL)
            if (opcode == 0x8D) {
                val low = nestlin.memory[pc + 1].toUnsignedInt()
                val high = nestlin.memory[pc + 2].toUnsignedInt()
                val addr = (high shl 8) or low
                if (addr == 0x2000) {
                    ppuCtrlWrites.add("PC=${String.format("%04X", pc)} inst=$idx: STA \$2000 = ${String.format("%02X", lastA)}")
                }
            }
        }

        // === ASSERTIONS ===

        // Assert 1: We ran long enough (at least 4 frames)
        assertThat("Should have run at least 4 frames", frameCount, greaterThanOrEqualTo(4))

        // Assert 2: PPUCTRL was ever written with bit 7 set (NMI enabled)
        // Note: we check nmiHandlerEntryCount as a more reliable proof than trace analysis heuristics
        assertThat("At least one NMI should have fired", nmiHandlerEntryCount, greaterThanOrEqualTo(1))
    }

    /**
     * Get instruction mnemonic for opcode
     */
    fun opcodeName(op: Int): String = when (op) {
        0x00 -> "BRK"
        0x01 -> "ORA"
        0x05 -> "ORA"
        0x06 -> "ASL"
        0x08 -> "PHP"
        0x09 -> "ORA"
        0x0A -> "ASL"
        0x0D -> "ORA"
        0x10 -> "BPL"
        0x11 -> "ORA"
        0x15 -> "ORA"
        0x16 -> "ASL"
        0x18 -> "CLC"
        0x19 -> "ORA"
        0x1D -> "ORA"
        0x1E -> "ASL"
        0x20 -> "JSR"
        0x21 -> "AND"
        0x24 -> "BIT"
        0x25 -> "AND"
        0x26 -> "ROL"
        0x28 -> "PLP"
        0x29 -> "AND"
        0x2A -> "ROL"
        0x2C -> "BIT"
        0x30 -> "BMI"
        0x31 -> "AND"
        0x35 -> "AND"
        0x36 -> "ROL"
        0x38 -> "SEC"
        0x39 -> "AND"
        0x3D -> "AND"
        0x3E -> "ROL"
        0x40 -> "RTI"
        0x41 -> "EOR"
        0x45 -> "EOR"
        0x46 -> "LSR"
        0x48 -> "PHA"
        0x49 -> "EOR"
        0x4A -> "LSR"
        0x4C -> "JMP"
        0x4D -> "EOR"
        0x50 -> "BVC"
        0x51 -> "EOR"
        0x55 -> "EOR"
        0x56 -> "LSR"
        0x58 -> "CLI"
        0x59 -> "EOR"
        0x5D -> "EOR"
        0x5E -> "LSR"
        0x60 -> "RTS"
        0x61 -> "ADC"
        0x65 -> "ADC"
        0x66 -> "ROR"
        0x68 -> "PLA"
        0x69 -> "ADC"
        0x6A -> "ROR"
        0x6C -> "JMP"
        0x6D -> "ADC"
        0x70 -> "BVS"
        0x71 -> "ADC"
        0x75 -> "ADC"
        0x76 -> "ROR"
        0x78 -> "SEI"
        0x79 -> "ADC"
        0x7D -> "ADC"
        0x7E -> "ADC"
        0x81 -> "STA"
        0x84 -> "STY"
        0x85 -> "STA"
        0x86 -> "STX"
        0x88 -> "DEY"
        0x8A -> "TXA"
        0x8C -> "STY"
        0x8D -> "STA"
        0x8E -> "STX"
        0x90 -> "BCC"
        0x91 -> "STA"
        0x94 -> "STY"
        0x95 -> "STA"
        0x96 -> "STX"
        0x98 -> "TYA"
        0x99 -> "STA"
        0x9A -> "TXS"
        0x9D -> "STA"
        0xA0 -> "LDY"
        0xA1 -> "LDA"
        0xA2 -> "LDX"
        0xA4 -> "LDY"
        0xA5 -> "LDA"
        0xA6 -> "LDX"
        0xA8 -> "TAY"
        0xA9 -> "LDA"
        0xAA -> "TAX"
        0xAC -> "LDY"
        0xAD -> "LDA"
        0xAE -> "LDX"
        0xB0 -> "BCS"
        0xB1 -> "LDA"
        0xB4 -> "LDY"
        0xB5 -> "LDA"
        0xB6 -> "LDX"
        0xB8 -> "CLV"
        0xB9 -> "LDA"
        0xBA -> "TSX"
        0xBC -> "LDY"
        0xBD -> "LDA"
        0xBE -> "LDX"
        0xC0 -> "CPY"
        0xC1 -> "CMP"
        0xC4 -> "CPY"
        0xC5 -> "CMP"
        0xC6 -> "DEC"
        0xC8 -> "INY"
        0xC9 -> "CMP"
        0xCA -> "DEX"
        0xCC -> "CPY"
        0xCD -> "CMP"
        0xCE -> "DEC"
        0xD0 -> "BNE"
        0xD1 -> "CMP"
        0xD5 -> "CMP"
        0xD6 -> "DEC"
        0xD8 -> "CLD"
        0xD9 -> "CMP"
        0xDD -> "CMP"
        0xDE -> "DEC"
        0xE0 -> "CPX"
        0xE1 -> "SBC"
        0xE4 -> "CPX"
        0xE5 -> "SBC"
        0xE6 -> "INC"
        0xE8 -> "INX"
        0xE9 -> "SBC"
        0xEA -> "NOP"
        0xEC -> "CPX"
        0xED -> "SBC"
        0xEE -> "INC"
        0xF0 -> "BEQ"
        0xF1 -> "SBC"
        0xF5 -> "SBC"
        0xF6 -> "INC"
        0xF8 -> "SED"
        0xF9 -> "SBC"
        0xFD -> "SBC"
        0xFE -> "INC"
        else -> "???${String.format("%02X", op)}"
    }

    /**
     * Decode instruction with operand - returns format like "LDA #$12" or "STA $1234"
     */
    fun decodeInstruction(nestlin: Nestlin, pc: Int, opcode: Int): String {
        val mem = nestlin.memory
        val name = opcodeName(opcode)

        return when (opcode) {
            // 1-byte opcodes
            0x0A, 0x2A, 0x4A, 0x6A, 0xEA, 0xAA, 0x88, 0x8A, 0x98, 0x9A, 0xE8, 0xC8, 0xCA -> name

            // Immediate: A9, A2, A0, C9, E9, E0, C0, 49
            0xA9, 0xA2, 0xA0, 0xC9, 0xE9, 0xE0, 0xC0, 0x49 -> {
                val imm = mem[pc + 1].toUnsignedInt()
                "$name #$${String.format("%02X", imm)}"
            }

            // Relative branches: 10, 30, 50, 70, 90, B0, D0, F0
            0x10, 0x30, 0x50, 0x70, 0x90, 0xB0, 0xD0, 0xF0 -> {
                val offset = mem[pc + 1].toUnsignedInt()
                val target = (pc + 2 + (if (offset > 127) offset - 256 else offset)) and 0xFFFF
                "$name $${String.format("%04X", target)}"
            }

            // Zero page: 85, 86, 84, 06, 26, 46, 66, A5, A6, A4, C5, C6, E5, E6, 25, 45, 65, 24
            0x85, 0x86, 0x84, 0x06, 0x26, 0x46, 0x66, 0xA5, 0xA6, 0xA4,
            0xC5, 0xC6, 0xE5, 0xE6, 0x25, 0x45, 0x65, 0x24 -> {
                val addr = mem[pc + 1].toUnsignedInt()
                "$name $${String.format("%02X", addr)}"
            }

            // Absolute: 8D, 8E, 8C, 0D, 2C, 4C, 6D, AC, AD, AE, CC, CD, CE, EE, 20
            0x8D, 0x8E, 0x8C, 0x0D, 0x2C, 0x4C, 0x6D, 0xAC, 0xAD, 0xAE,
            0xCC, 0xCD, 0xCE, 0xEE, 0x20 -> {

                val low = mem[pc + 1].toUnsignedInt()
                val high = mem[pc + 2].toUnsignedInt()
                val addr = (high shl 8) or low
                "$name $${String.format("%04X", addr)}"
            }

            // Absolute,X: 9D, BC, BD, BE, DD, DE, FE
            0x9D, 0xBC, 0xBD, 0xBE, 0xDD, 0xDE, 0xFE -> {
                val low = mem[pc + 1].toUnsignedInt()
                val high = mem[pc + 2].toUnsignedInt()
                val addr = (high shl 8) or low
                "$name $${String.format("%04X", addr)},X"
            }

            // Absolute,Y: 99, B9, B5, B6, 95, 96
            0x99, 0xB9, 0xB5, 0xB6, 0x95, 0x96 -> {
                val low = mem[pc + 1].toUnsignedInt()
                val high = mem[pc + 2].toUnsignedInt()
                val addr = (high shl 8) or low
                "$name $${String.format("%04X", addr)},Y"
            }

            // Indirect: 6C
            0x6C -> {
                val low = mem[pc + 1].toUnsignedInt()
                val high = mem[pc + 2].toUnsignedInt()
                val addr = (high shl 8) or low
                val targetLow = mem[addr].toUnsignedInt()
                val targetHigh = mem[if ((addr and 0xFF) == 0xFF) addr and 0xFF00 else addr + 1].toUnsignedInt()
                val target = (targetHigh shl 8) or targetLow
                "$name ($${String.format("%04X", addr)}) -> $${String.format("%04X", target)}"
            }

            // Indirect,X: 81
            0x81 -> {
                val addr = mem[pc + 1].toUnsignedInt()
                "$name ($${String.format("%02X", addr)},X)"
            }

            // Indirect,Y: 71, B1
            0x71, 0xB1 -> {
                val addr = mem[pc + 1].toUnsignedInt()
                "$name ($${String.format("%02X", addr)}),Y"
            }

            else -> name
        }
    }
}
