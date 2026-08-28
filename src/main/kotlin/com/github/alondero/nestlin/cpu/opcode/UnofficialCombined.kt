package com.github.alondero.nestlin.cpu.opcode

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt

/**
 * Combined Read-Modify-Write + A-op unofficial opcodes.
 *
 * Each combines a memory RMW (shift/rotate/inc/dec) with an accumulator
 * operation (AND/ORA/EOR/SBC/ADC). The constructor carries the real cycle
 * count for the selected addressing mode, just like the official families.
 */

/**
 * DCP — DEC then CMP. Cycle count follows the addressing mode.
 * Original Opcodes.kt:846-857.
 */
class Dcp(
    override val addressing: Addressing,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles), RmwOpcode {
    override fun transformedValue(@Suppress("UNUSED_PARAMETER") cpu: Cpu, original: Byte): Byte =
        ((original.toUnsignedInt() - 1) and 0xFF).toSignedByte()

    override fun commitResult(cpu: Cpu, @Suppress("UNUSED_PARAMETER") original: Byte, result: Byte) {
        val comparison = cpu.registers.accumulator.toUnsignedInt() - result.toUnsignedInt()
        cpu.processorStatus.carry = comparison >= 0
        cpu.processorStatus.zero = comparison == 0
        cpu.processorStatus.negative = comparison.toSignedByte().isBitSet(7)
    }

    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val original = cpu.memory[addr]
        val result = transformedValue(cpu, original)
        cpu.memory[addr] = result
        commitResult(cpu, original, result)
        // Issue #17 / #172: +1 cycle on page cross for abs,X / ($zp),Y.
        // zp / zp,X / abs / abs,X / zp,Y / (ind,X) — only the indexed
        // forms can cross a page (zp can't).
        cpu.workCyclesLeft = cycles + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * ISC — INC then SBC. Cycle count follows the addressing mode.
 *
 * **Issue #207 quirk fix.** In the original dispatcher, `0xE3` and
 * `0xF3` were both assigned to ISC (0xE3 had a DCP put-then-ISC-overwrite
 * pattern at Opcodes.kt:441, 442, 450, 451). Canonical 6502 says 0xE3 is
 * DCP, 0xF3 is ISC. Now `0xE3` dispatches to [Dcp] and only `0xF3`
 * dispatches here.
 * Original Opcodes.kt:859-874.
 */
class Isc(
    override val addressing: Addressing,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles), RmwOpcode {
    override fun transformedValue(@Suppress("UNUSED_PARAMETER") cpu: Cpu, original: Byte): Byte =
        ((original.toUnsignedInt() + 1) and 0xFF).toSignedByte()

    override fun commitResult(cpu: Cpu, @Suppress("UNUSED_PARAMETER") original: Byte, result: Byte) {
        val currentAccumulator = cpu.registers.accumulator
        var value = currentAccumulator.toUnsignedInt() - result.toUnsignedInt()
        if (!cpu.processorStatus.carry) value--
        cpu.registers.accumulator = (value and 0xFF).toSignedByte()
        cpu.processorStatus.carry = (value shr 8) == 0
        cpu.processorStatus.overflow =
            ((currentAccumulator.toUnsignedInt() xor result.toUnsignedInt()) and 0x80 == 0x80) &&
            ((currentAccumulator.toUnsignedInt() xor cpu.registers.accumulator.toUnsignedInt()) and 0x80 == 0x80)
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
    }

    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val original = cpu.memory[addr]
        val result = transformedValue(cpu, original)
        cpu.memory[addr] = result
        commitResult(cpu, original, result)
        // Issue #17 / #172: +1 cycle on page cross for abs,X / ($zp),Y.
        cpu.workCyclesLeft = cycles + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * RLA — ROL then AND. Cycle count follows the addressing mode.
 * Original Opcodes.kt:876-888.
 */
class Rla(
    override val addressing: Addressing,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles), RmwOpcode {
    override fun transformedValue(cpu: Cpu, original: Byte): Byte =
        ((original.toUnsignedInt() shl 1) or if (cpu.processorStatus.carry) 1 else 0).toSignedByte()

    override fun commitResult(cpu: Cpu, original: Byte, result: Byte) {
        cpu.processorStatus.carry = original.isBitSet(7)
        cpu.registers.accumulator = (cpu.registers.accumulator.toUnsignedInt() and result.toUnsignedInt()).toSignedByte()
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
    }

    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val original = cpu.memory[addr]
        val rotated = transformedValue(cpu, original)
        cpu.memory[addr] = rotated
        commitResult(cpu, original, rotated)
        // Issue #17 / #172: +1 cycle on page cross for abs,X / abs,Y /
        // ($zp),Y.
        cpu.workCyclesLeft = cycles + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * RRA — ROR then ADC. Cycle count follows the addressing mode.
 * Original Opcodes.kt:890-908.
 */
class Rra(
    override val addressing: Addressing,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles), RmwOpcode {
    override fun transformedValue(cpu: Cpu, original: Byte): Byte =
        ((original.toUnsignedInt() shr 1) or if (cpu.processorStatus.carry) 0x80 else 0).toSignedByte()

    override fun commitResult(cpu: Cpu, original: Byte, result: Byte) {
        cpu.processorStatus.carry = original.isBitSet(0)
        val currentAccumulator = cpu.registers.accumulator
        var value = currentAccumulator.toUnsignedInt() + result.toUnsignedInt()
        if (cpu.processorStatus.carry) value++
        cpu.registers.accumulator = value.toSignedByte()
        cpu.processorStatus.carry = (value shr 8) != 0
        cpu.processorStatus.overflow =
            ((currentAccumulator.toUnsignedInt() xor result.toUnsignedInt()) and 0x80 == 0) &&
            ((currentAccumulator.toUnsignedInt() xor cpu.registers.accumulator.toUnsignedInt()) and 0x80 != 0)
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
    }

    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val original = cpu.memory[addr]
        val rotated = transformedValue(cpu, original)
        cpu.memory[addr] = rotated
        commitResult(cpu, original, rotated)
        // Issue #17 / #172: +1 cycle on page cross for abs,X / abs,Y /
        // ($zp),Y.
        cpu.workCyclesLeft = cycles + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * SLO — ASL then ORA. Cycle count follows the addressing mode.
 * Original Opcodes.kt:910-921.
 */
class Slo(
    override val addressing: Addressing,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles), RmwOpcode {
    override fun transformedValue(@Suppress("UNUSED_PARAMETER") cpu: Cpu, original: Byte): Byte =
        ((original.toUnsignedInt() shl 1) and 0xFF).toSignedByte()

    override fun commitResult(cpu: Cpu, original: Byte, result: Byte) {
        cpu.processorStatus.carry = original.isBitSet(7)
        cpu.registers.accumulator = (cpu.registers.accumulator.toUnsignedInt() or result.toUnsignedInt()).toSignedByte()
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
    }

    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val original = cpu.memory[addr]
        val shifted = transformedValue(cpu, original)
        cpu.memory[addr] = shifted
        commitResult(cpu, original, shifted)
        // Issue #17 / #172: +1 cycle on page cross for abs,X / abs,Y /
        // ($zp),Y.
        cpu.workCyclesLeft = cycles + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * SRE — LSR then EOR. Cycle count follows the addressing mode.
 * Original Opcodes.kt:923-934.
 */
class Sre(
    override val addressing: Addressing,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles), RmwOpcode {
    override fun transformedValue(@Suppress("UNUSED_PARAMETER") cpu: Cpu, original: Byte): Byte =
        (original.toUnsignedInt() shr 1).toSignedByte()

    override fun commitResult(cpu: Cpu, original: Byte, result: Byte) {
        cpu.processorStatus.carry = original.isBitSet(0)
        cpu.registers.accumulator = (cpu.registers.accumulator.toUnsignedInt() xor result.toUnsignedInt()).toSignedByte()
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
    }

    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val original = cpu.memory[addr]
        val shifted = transformedValue(cpu, original)
        cpu.memory[addr] = shifted
        commitResult(cpu, original, shifted)
        // Issue #17 / #172: +1 cycle on page cross for abs,X / abs,Y /
        // ($zp),Y.
        cpu.workCyclesLeft = cycles + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * ALR — A AND immediate, then LSR A. 2 cycles.
 * Original Opcodes.kt:936-944.
 */
class Alr : Opcode(cycles = 2), ReadOpcode {
    override val addressing: Addressing = Immediate
    override fun applyRead(cpu: Cpu, value: Byte) {
        cpu.registers.accumulator = (cpu.registers.accumulator.toUnsignedInt() and value.toUnsignedInt()).toSignedByte()
        cpu.processorStatus.carry = cpu.registers.accumulator.isBitSet(7)
        cpu.registers.accumulator = (cpu.registers.accumulator.toUnsignedInt() shr 1).toSignedByte()
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
    }
    override val mnemonic = "ALR"
    override fun evaluate(cpu: Cpu) {
        applyRead(cpu, cpu.readByteAtPC())
        cpu.workCyclesLeft = 2
    }
}

/**
 * ARR — A AND immediate, then ROR A. 2 cycles.
 * Original Opcodes.kt:946-956.
 */
class Arr : Opcode(cycles = 2), ReadOpcode {
    override val addressing: Addressing = Immediate
    override fun applyRead(cpu: Cpu, value: Byte) {
        val oldCarry = cpu.processorStatus.carry
        cpu.registers.accumulator = (cpu.registers.accumulator.toUnsignedInt() and value.toUnsignedInt()).toSignedByte()
        cpu.processorStatus.carry = cpu.registers.accumulator.isBitSet(0)
        cpu.registers.accumulator = (cpu.registers.accumulator.toUnsignedInt() shr 1).toSignedByte()
        if (oldCarry) cpu.registers.accumulator = (cpu.registers.accumulator.toUnsignedInt() or 0x80).toSignedByte()
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
    }
    override val mnemonic = "ARR"
    override fun evaluate(cpu: Cpu) {
        applyRead(cpu, cpu.readByteAtPC())
        cpu.workCyclesLeft = 2
    }
}
