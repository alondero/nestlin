package com.github.alondero.nestlin.cpu.opcode

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt

/**
 * LAX — Load A and X combined. 6 opcodes.
 *
 * Per-mode cycle counts (issue #207, real 6502): (ind,X)=6, zp=3, abs=4,
 * (ind),Y=5, zp,Y=4, abs,Y=4. (The original `lax` helper hardcoded 2 for
 * all six; that quirk is now fixed and the per-mode count is threaded
 * through via [cycles].)
 * Original Opcodes.kt:409-414, 770-778.
 */
class Lax(
    val addressing: Addressing,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles) {
    override fun evaluate(cpu: Cpu) {
        val value = addressing.value(cpu)
        cpu.registers.accumulator = value
        cpu.registers.indexX = value
        cpu.processorStatus.resolveZeroAndNegativeFlags(value)
        // Issue #17 / #172: +1 cycle on page cross for abs,Y / ($zp),Y.
        cpu.workCyclesLeft = cycles + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * SAX — Store A AND X. 4 opcodes.
 *
 * Per-mode cycle counts (issue #207, real 6502): (ind,X)=6, zp=3, abs=4,
 * zp,Y=4. (The original `sax` helper hardcoded 4 for all four; that
 * quirk is now fixed.)
 * Original Opcodes.kt:417-420, 780-785.
 */
class Sax(
    val addressing: Addressing,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles) {
    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        cpu.memory[addr] =
            (cpu.registers.accumulator.toUnsignedInt() and
             cpu.registers.indexX.toUnsignedInt()).toSignedByte()
        // SAX is a store — no page-cross +1 cycle (real 6502 behaviour).
        // zp / abs / zp,Y / (ind,X) all keep their base cycle count.
        cpu.workCyclesLeft = cycles
    }
}

/**
 * AHX — Store A AND X. 2 opcodes.
 *
 * **Issue #207 quirk fixed.** The original `ahx` helper (Opcodes.kt:787-793)
 * used a high-byte mask of `0x07` — clearly a typo for the real 6502
 * mask (typically `0xFF`, since `(A AND X)` is already 8-bit). Now
 * stores `A AND X` directly with no spurious mask.
 * Original Opcodes.kt:423-424.
 */
class Ahx(
    val addressing: Addressing,
    override val mnemonic: String,
) : Opcode(cycles = 4) {
    override fun evaluate(cpu: Cpu) {
        val value = (cpu.registers.accumulator.toUnsignedInt() and
                     cpu.registers.indexX.toUnsignedInt()).toSignedByte()
        cpu.memory[addressing.address(cpu)] = value
        // AHX is a store — no page-cross +1 cycle.
        cpu.workCyclesLeft = 4
    }
}

/**
 * XAA / ANE — A = A AND X AND immediate operand. 2 cycles.
 *
 * **Issue #207 quirk fix.** Previously 0x9B was also mapped to XAA, but
 * canonical 6502 says 0x9B is TAS abs,Y. Now 0x9B dispatches to [Tas];
 * 0xAB remains XAA.
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
        // LAS abs,Y — +1 cycle on page cross.
        cpu.workCyclesLeft = 4 + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * TAS / SHX / SHY — Unofficial store ops. 5 cycles each.
 *
 * **Issue #207 quirk fix.** The original `tas()`, `shx()`, `shy()`
 * factory methods (Opcodes.kt:802-826) exist but were NOT registered in
 * the dispatch table. Now registered:
 *  - 0x9B = TAS abs,Y
 *  - 0x9C = SHY abs,X
 *  - 0x9E = SHX abs,Y
 *
 * All three are store-like and do NOT add a cycle on page cross
 * (real 6502 behaviour). The base cycle count already reflects the
 * indexed addressing mode's cost.
 */
class Tas(
    val addressing: Addressing,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles) {
    override fun evaluate(cpu: Cpu) {
        cpu.registers.stackPointer = cpu.registers.accumulator
        // Original helper ignored the address value; we replicate that.
        @Suppress("UNUSED_VARIABLE") val addr = addressing.address(cpu)
        // TAS is a store-side effect on SP — no page-cross +1.
        cpu.workCyclesLeft = cycles
    }
}

class Shx(
    val addressing: Addressing,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles) {
    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val highByte = ((addr shr 8) + 1) and 0xFF
        cpu.memory[addr] =
            (cpu.registers.indexX.toUnsignedInt() and highByte).toSignedByte()
        // SHX is a store — no page-cross +1.
        cpu.workCyclesLeft = cycles
    }
}

class Shy(
    val addressing: Addressing,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles) {
    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val highByte = ((addr shr 8) + 1) and 0xFF
        cpu.memory[addr] =
            (cpu.registers.indexY.toUnsignedInt() and highByte).toSignedByte()
        // SHY is a store — no page-cross +1.
        cpu.workCyclesLeft = cycles
    }
}