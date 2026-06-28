package com.github.alondero.nestlin.cpu.opcode

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt

/**
 * LAX — Load A and X combined. 6 opcodes.
 *
 * **Preserved quirk:** the original `lax` helper (Opcodes.kt:770-778)
 * hardcodes `workCyclesLeft = 2` for ALL six addressing modes. Real 6502
 * cycles are 6, 3, 4, 5, 4, 4. Preserving here; out of scope for #192.
 * Original Opcodes.kt:409-414.
 */
class Lax(
    val addressing: Addressing,
    override val mnemonic: String,
) : Opcode(cycles = 2) {
    override fun evaluate(cpu: Cpu) {
        val value = addressing.value(cpu)
        cpu.registers.accumulator = value
        cpu.registers.indexX = value
        cpu.processorStatus.resolveZeroAndNegativeFlags(value)
        cpu.workCyclesLeft = 2
    }
}

/**
 * SAX — Store A AND X. 4 opcodes.
 *
 * **Preserved quirk:** the original `sax` helper (Opcodes.kt:780-785)
 * hardcodes `workCyclesLeft = 4` for ALL four addressing modes. Real 6502
 * cycles: zp=3, abs=4, (ind,X)=6, zp,Y=4. Preserved.
 * Original Opcodes.kt:417-420.
 */
class Sax(
    val addressing: Addressing,
    override val mnemonic: String,
) : Opcode(cycles = 4) {
    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        cpu.memory[addr] =
            (cpu.registers.accumulator.toUnsignedInt() and
             cpu.registers.indexX.toUnsignedInt()).toSignedByte()
        cpu.workCyclesLeft = 4
    }
}

/**
 * AHX — Store A AND X AND H. 2 opcodes.
 *
 * **Preserved quirk:** the original `ahx` helper (Opcodes.kt:787-793)
 * uses a high-byte mask of `0x07`, which looks like a typo for the real
 * 6502 mask (typically `0xFF` or the address high byte + 1). Preserved
 * here; out of scope for #192.
 * Original Opcodes.kt:423-424.
 */
class Ahx(
    val addressing: Addressing,
    override val mnemonic: String,
) : Opcode(cycles = 4) {
    override fun evaluate(cpu: Cpu) {
        val value = (cpu.registers.accumulator.toUnsignedInt() and
                     cpu.registers.indexX.toUnsignedInt() and 0x07).toSignedByte()
        cpu.memory[addressing.address(cpu)] = value
        cpu.workCyclesLeft = 4
    }
}

/**
 * XAA / ANE — A = A AND X AND immediate operand. 2 cycles.
 *
 * **Preserved quirk:** 0x9B is canonical 6502 TAS, but is mapped to XAA
 * in this codebase (Opcodes.kt:427-428). 0xAB is canonical XAA.
 * Original Opcodes.kt:795-800.
 */
class Xaa : Opcode(cycles = 2) {
    override val mnemonic = "XAA"
    override fun evaluate(cpu: Cpu) {
        cpu.registers.accumulator =
            (cpu.registers.accumulator.toUnsignedInt() and
             cpu.registers.indexX.toUnsignedInt()).toSignedByte()
        cpu.workCyclesLeft = 2
    }
}

/**
 * LAS — Load A, X, and S from memory. 4 cycles.
 * Original Opcodes.kt:431, 835-844.
 */
class Las(
    val addressing: Addressing,
    override val mnemonic: String,
) : Opcode(cycles = 4) {
    override fun evaluate(cpu: Cpu) {
        val value = addressing.value(cpu)
        cpu.registers.accumulator = value
        cpu.registers.indexX = value
        cpu.registers.stackPointer = value
        cpu.processorStatus.resolveZeroAndNegativeFlags(value)
        cpu.workCyclesLeft = 4
    }
}

/**
 * TAS / SHX / SHY — Unofficial store ops. 4 cycles each.
 *
 * **Preserved quirk:** the original `tas()`, `shx()`, `shy()` factory
 * methods (Opcodes.kt:802-826) exist but are NOT registered in the
 * dispatch table. The classes are kept here so a future follow-up issue
 * can register them without re-deriving the bit math.
 */
class Tas(
    val addressing: Addressing,
    override val mnemonic: String,
) : Opcode(cycles = 4) {
    override fun evaluate(cpu: Cpu) {
        cpu.registers.stackPointer = cpu.registers.accumulator
        // Original helper ignored the address value; we replicate that.
        @Suppress("UNUSED_VARIABLE") val addr = addressing.address(cpu)
        cpu.workCyclesLeft = 4
    }
}

class Shx(
    val addressing: Addressing,
    override val mnemonic: String,
) : Opcode(cycles = 5) {
    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val highByte = ((addr shr 8) + 1) and 0xFF
        cpu.memory[addr] =
            (cpu.registers.indexX.toUnsignedInt() and highByte).toSignedByte()
        cpu.workCyclesLeft = 5
    }
}

class Shy(
    val addressing: Addressing,
    override val mnemonic: String,
) : Opcode(cycles = 5) {
    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val highByte = ((addr shr 8) + 1) and 0xFF
        cpu.memory[addr] =
            (cpu.registers.indexY.toUnsignedInt() and highByte).toSignedByte()
        cpu.workCyclesLeft = 5
    }
}