package com.github.alondero.nestlin.cpu.opcode

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.shiftRight
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt

/**
 * Memory shift/rotate — ASL, LSR, ROL, ROR (16 opcodes on memory).
 *
 * Read-Modify-Write: read byte at addressing-mode location, transform,
 * write back, set C and Z/N flags. The accumulator variants (0x0A/0x2A/
 * 0x4A/0x6A) are in Opcode.kt as [AccShiftRotate].
 * Original Opcodes.kt:650-705, 151-166.
 *
 * [kind] selects shift vs rotate; [direction] is meaningless for ASL/ROL
 * (always leftward) but encoded anyway for symmetry.
 */
class MemoryShiftRotate(
    override val addressing: Addressing,
    val kind: Kind,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles), RmwOpcode {

    override fun transformedValue(cpu: Cpu, original: Byte): Byte = when (kind) {
        Kind.ASL -> ((original.toUnsignedInt() shl 1) and 0xFF).toSignedByte()
        Kind.LSR -> original.shiftRight()
        Kind.ROL -> ((original.toUnsignedInt() shl 1) or if (cpu.processorStatus.carry) 1 else 0).toSignedByte()
        Kind.ROR -> ((original.toUnsignedInt() shr 1) or if (cpu.processorStatus.carry) 0x80 else 0).toSignedByte()
    }

    override fun commitResult(cpu: Cpu, original: Byte, result: Byte) {
        cpu.processorStatus.carry = when (kind) {
            Kind.ASL, Kind.ROL -> original.isBitSet(7)
            Kind.LSR, Kind.ROR -> original.isBitSet(0)
        }
        cpu.processorStatus.resolveZeroAndNegativeFlags(result)
    }

    enum class Kind { ASL, LSR, ROL, ROR }

    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val original = cpu.memory[addr]

        val result = transformedValue(cpu, original)
        cpu.memory[addr] = result
        commitResult(cpu, original, result)
        // Issue #17 / #172: +1 cycle on page cross for abs,X (only indexed
        // RMW variant — zp,X is zero-page, so no page can be crossed).
        cpu.workCyclesLeft = cycles + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * Memory INC/DEC — increment or decrement a memory byte (8 opcodes).
 *
 * Read-Modify-Write: read byte at addressing-mode location, add/subtract 1
 * with 8-bit wrap, write back, set Z/N flags.
 * Original Opcodes.kt:707-717, 169-176.
 */
class MemoryIncDec(
    override val addressing: Addressing,
    val delta: Int,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles), RmwOpcode {
    override fun transformedValue(@Suppress("UNUSED_PARAMETER") cpu: Cpu, original: Byte): Byte =
        ((original.toUnsignedInt() + delta) and 0xFF).toSignedByte()

    override fun commitResult(cpu: Cpu, @Suppress("UNUSED_PARAMETER") original: Byte, result: Byte) {
        cpu.processorStatus.resolveZeroAndNegativeFlags(result)
    }

    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val original = cpu.memory[addr].toUnsignedInt()
        val result = transformedValue(cpu, original.toSignedByte())
        cpu.memory[addr] = result
        commitResult(cpu, original.toSignedByte(), result)
        // INC/DEC abs,X is the only indexed variant — zp,X is zero-page,
        // so the page-cross flag only fires for abs,X.
        cpu.workCyclesLeft = cycles + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}
