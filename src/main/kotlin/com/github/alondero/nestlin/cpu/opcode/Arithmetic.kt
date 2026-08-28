package com.github.alondero.nestlin.cpu.opcode

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt

/**
 * ADC family — Add Memory to Accumulator with Carry.
 *
 * 8 opcodes (imm/zp/abs/(ind,X)/(ind),Y/zp,X/abs,X/abs,Y).
 * Sets C, V, Z, N flags based on the result.
 * Original Opcodes.kt:612-629.
 */
class Adc(
    override val addressing: Addressing,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles), ReadOpcode {
    override fun applyRead(cpu: Cpu, value: Byte) {
        val currentAccumulator = cpu.registers.accumulator
        var result = currentAccumulator.toUnsignedInt() + value.toUnsignedInt()
        if (cpu.processorStatus.carry) result++
        cpu.registers.accumulator = result.toSignedByte()
        cpu.processorStatus.carry = (result shr 8) != 0
        cpu.processorStatus.overflow =
            ((currentAccumulator.toUnsignedInt() xor value.toUnsignedInt()) and 0x80 == 0x00) &&
            ((currentAccumulator.toUnsignedInt() xor cpu.registers.accumulator.toUnsignedInt()) and 0x80 == 0x80)
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
    }

    override fun evaluate(cpu: Cpu) {
        // NES CPU doesn't test decimal mode (no decimal ADC on NES).
        applyRead(cpu, addressing.value(cpu))

        // Issue #17 / #172: +1 cycle on page cross for abs,X / abs,Y /
        // ($zp),Y (ADC uses indexed variants).
        cpu.workCyclesLeft = cycles + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * SBC family — Subtract Memory from Accumulator with Borrow (Carry).
 *
 * 8 opcodes. Sets C, V, Z, N flags.
 * Original Opcodes.kt:631-648.
 */
class Sbc(
    override val addressing: Addressing,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles), ReadOpcode {
    override fun applyRead(cpu: Cpu, value: Byte) {
        val currentAccumulator = cpu.registers.accumulator
        var result = currentAccumulator.toUnsignedInt() - value.toUnsignedInt()
        if (!cpu.processorStatus.carry) result--
        cpu.registers.accumulator = (result and 0xFF).toSignedByte()
        cpu.processorStatus.carry = (result shr 8) == 0
        cpu.processorStatus.overflow =
            ((currentAccumulator.toUnsignedInt() xor value.toUnsignedInt()) and 0x80 == 0x80) &&
            ((currentAccumulator.toUnsignedInt() xor cpu.registers.accumulator.toUnsignedInt()) and 0x80 == 0x80)
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
    }

    override fun evaluate(cpu: Cpu) {
        // NES CPU doesn't test decimal mode.
        applyRead(cpu, addressing.value(cpu))

        // Issue #17 / #172: +1 cycle on page cross for abs,X / abs,Y /
        // ($zp),Y (SBC uses indexed variants).
        cpu.workCyclesLeft = cycles + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * Compare family — CMP/CPX/CPY. 14 opcodes.
 *
 * Compares a register with the memory operand by subtraction. Sets C, Z,
 * N flags; no other side effects.
 * Original Opcodes.kt:587-600.
 */
class Compare(
    override val addressing: Addressing,
    val register: (Cpu) -> Byte,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles), ReadOpcode {
    override fun applyRead(cpu: Cpu, value: Byte) {
        val reg = register(cpu).toUnsignedInt()
        val operand = value.toUnsignedInt()
        val result = reg - operand
        cpu.processorStatus.negative = result.toSignedByte().isBitSet(7)
        cpu.processorStatus.zero = result == 0
        cpu.processorStatus.carry = reg >= operand
    }

    override fun evaluate(cpu: Cpu) {
        applyRead(cpu, addressing.value(cpu))
        // Issue #17 / #172: +1 cycle on page cross for abs,X / abs,Y /
        // ($zp),Y (CMP uses indexed variants; CPX/CPY use zp/abs only).
        cpu.workCyclesLeft = cycles + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * BIT family — Test Bits in Memory with Accumulator.
 *
 * 2 opcodes (zp, abs). Sets Z = (A AND M) == 0; copies M's bit 6 to V,
 * M's bit 7 to N. Original Opcodes.kt:719-729.
 */
class Bit(
    override val addressing: Addressing,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles), ReadOpcode {
    override fun applyRead(cpu: Cpu, value: Byte) {
        val mem = value.toUnsignedInt()
        cpu.processorStatus.apply {
            zero = (mem and cpu.registers.accumulator.toUnsignedInt()) == 0
            negative = (mem and 0b10000000) != 0
            overflow = (mem and 0b01000000) != 0
        }
    }

    override fun evaluate(cpu: Cpu) {
        applyRead(cpu, addressing.value(cpu))
        // BIT uses zp / abs only — no indexed variants, so no page-cross
        // +1 to apply.
        cpu.workCyclesLeft = cycles
    }
}
