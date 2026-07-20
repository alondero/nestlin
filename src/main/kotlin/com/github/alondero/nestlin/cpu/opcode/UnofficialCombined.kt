package com.github.alondero.nestlin.cpu.opcode

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt

/**
 * Combined Read-Modify-Write + A-op unofficial opcodes.
 *
 * Each combines a memory RMW (shift/rotate/inc/dec) with an accumulator
 * operation (AND/ORA/EOR/SBC/ADC). The original `dcp`/`isc`/... helpers
 * hardcoded a single cycle count across all addressing modes; that quirk
 * is preserved here.
 */

/**
 * DCP — DEC then CMP. 6/6 cycles for all addressing modes (preserved).
 * Original Opcodes.kt:846-857.
 */
class Dcp(
    val addressing: Addressing,
    override val mnemonic: String,
) : Opcode(cycles = 6) {
    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val result = ((cpu.memory[addr].toUnsignedInt() - 1) and 0xFF).toSignedByte()
        cpu.memory[addr] = result
        val comparison = cpu.registers.accumulator.toUnsignedInt() - result.toUnsignedInt()
        cpu.processorStatus.carry = comparison >= 0
        cpu.processorStatus.zero = comparison == 0
        cpu.processorStatus.negative = comparison.toSignedByte().isBitSet(7)
        // Issue #17 / #172: +1 cycle on page cross for abs,X / ($zp),Y.
        // zp / zp,X / abs / abs,X / zp,Y / (ind,X) — only the indexed
        // forms can cross a page (zp can't).
        cpu.workCyclesLeft = 6 + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * ISC — INC then SBC. 6/6 cycles for all addressing modes (preserved).
 *
 * **Issue #207 quirk fix.** In the original dispatcher, `0xE3` and
 * `0xF3` were both assigned to ISC (0xE3 had a DCP put-then-ISC-overwrite
 * pattern at Opcodes.kt:441, 442, 450, 451). Canonical 6502 says 0xE3 is
 * DCP, 0xF3 is ISC. Now `0xE3` dispatches to [Dcp] and only `0xF3`
 * dispatches here.
 * Original Opcodes.kt:859-874.
 */
class Isc(
    val addressing: Addressing,
    override val mnemonic: String,
) : Opcode(cycles = 6) {
    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val result = ((cpu.memory[addr].toUnsignedInt() + 1) and 0xFF).toSignedByte()
        cpu.memory[addr] = result
        val currentAccumulator = cpu.registers.accumulator
        var res = currentAccumulator.toUnsignedInt() - result.toUnsignedInt()
        if (!cpu.processorStatus.carry) res--
        cpu.registers.accumulator = (res and 0xFF).toSignedByte()
        cpu.processorStatus.carry = (res shr 8) == 0
        cpu.processorStatus.overflow =
            ((currentAccumulator.toUnsignedInt() xor result.toUnsignedInt()) and 0x80 == 0x80) &&
            ((currentAccumulator.toUnsignedInt() xor cpu.registers.accumulator.toUnsignedInt()) and 0x80 == 0x80)
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
        // Issue #17 / #172: +1 cycle on page cross for abs,X / ($zp),Y.
        cpu.workCyclesLeft = 6 + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * RLA — ROL then AND. 5/5 cycles for all addressing modes (preserved).
 * Original Opcodes.kt:876-888.
 */
class Rla(
    val addressing: Addressing,
    override val mnemonic: String,
) : Opcode(cycles = 5) {
    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val original = cpu.memory[addr]
        val newCarry = original.isBitSet(7)
        val rotated = ((original.toUnsignedInt() shl 1) or
                       (if (cpu.processorStatus.carry) 1 else 0)).toSignedByte()
        cpu.memory[addr] = rotated
        cpu.processorStatus.carry = newCarry
        cpu.registers.accumulator =
            (cpu.registers.accumulator.toUnsignedInt() and rotated.toUnsignedInt()).toSignedByte()
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
        // Issue #17 / #172: +1 cycle on page cross for abs,X / abs,Y /
        // ($zp),Y.
        cpu.workCyclesLeft = 5 + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * RRA — ROR then ADC. 5/5 cycles for all addressing modes (preserved).
 * Original Opcodes.kt:890-908.
 */
class Rra(
    val addressing: Addressing,
    override val mnemonic: String,
) : Opcode(cycles = 5) {
    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val original = cpu.memory[addr]
        val oldCarry = cpu.processorStatus.carry
        cpu.processorStatus.carry = original.isBitSet(0)
        val rotated = ((original.toUnsignedInt() shr 1) or
                       (if (oldCarry) 0x80 else 0)).toSignedByte()
        cpu.memory[addr] = rotated
        val currentAccumulator = cpu.registers.accumulator
        var res = currentAccumulator.toUnsignedInt() + rotated.toUnsignedInt()
        if (cpu.processorStatus.carry) res++
        cpu.registers.accumulator = res.toSignedByte()
        cpu.processorStatus.carry = (res shr 8) != 0
        cpu.processorStatus.overflow =
            ((currentAccumulator.toUnsignedInt() xor rotated.toUnsignedInt()) and 0x80 == 0x00) &&
            ((currentAccumulator.toUnsignedInt() xor cpu.registers.accumulator.toUnsignedInt()) and 0x80 == 0x80)
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
        // Issue #17 / #172: +1 cycle on page cross for abs,X / abs,Y /
        // ($zp),Y.
        cpu.workCyclesLeft = 5 + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * SLO — ASL then ORA. 5/5 cycles for all addressing modes (preserved).
 * Original Opcodes.kt:910-921.
 */
class Slo(
    val addressing: Addressing,
    override val mnemonic: String,
) : Opcode(cycles = 5) {
    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val original = cpu.memory[addr]
        cpu.processorStatus.carry = original.isBitSet(7)
        val shifted = ((original.toUnsignedInt() shl 1) and 0xFF).toSignedByte()
        cpu.memory[addr] = shifted
        cpu.registers.accumulator =
            (cpu.registers.accumulator.toUnsignedInt() or shifted.toUnsignedInt()).toSignedByte()
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
        // Issue #17 / #172: +1 cycle on page cross for abs,X / abs,Y /
        // ($zp),Y.
        cpu.workCyclesLeft = 5 + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * SRE — LSR then EOR. 5/5 cycles for all addressing modes (preserved).
 * Original Opcodes.kt:923-934.
 */
class Sre(
    val addressing: Addressing,
    override val mnemonic: String,
) : Opcode(cycles = 5) {
    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        val original = cpu.memory[addr]
        cpu.processorStatus.carry = original.isBitSet(0)
        val shifted = (original.toUnsignedInt() shr 1).toSignedByte()
        cpu.memory[addr] = shifted
        cpu.registers.accumulator =
            (cpu.registers.accumulator.toUnsignedInt() xor shifted.toUnsignedInt()).toSignedByte()
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
        // Issue #17 / #172: +1 cycle on page cross for abs,X / abs,Y /
        // ($zp),Y.
        cpu.workCyclesLeft = 5 + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * ALR — A AND immediate, then LSR A. 2 cycles.
 * Original Opcodes.kt:936-944.
 */
class Alr : Opcode(cycles = 2) {
    override val mnemonic = "ALR"
    override fun evaluate(cpu: Cpu) {
        cpu.registers.accumulator =
            (cpu.registers.accumulator.toUnsignedInt() and
             cpu.readByteAtPC().toUnsignedInt()).toSignedByte()
        cpu.processorStatus.carry = cpu.registers.accumulator.isBitSet(7)
        cpu.registers.accumulator =
            (cpu.registers.accumulator.toUnsignedInt() shr 1).toSignedByte()
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
        cpu.workCyclesLeft = 2
    }
}

/**
 * ARR — A AND immediate, then ROR A. 2 cycles.
 * Original Opcodes.kt:946-956.
 */
class Arr : Opcode(cycles = 2) {
    override val mnemonic = "ARR"
    override fun evaluate(cpu: Cpu) {
        cpu.registers.accumulator =
            (cpu.registers.accumulator.toUnsignedInt() and
             cpu.readByteAtPC().toUnsignedInt()).toSignedByte()
        val oldCarry = cpu.processorStatus.carry
        cpu.processorStatus.carry = cpu.registers.accumulator.isBitSet(0)
        cpu.registers.accumulator =
            (cpu.registers.accumulator.toUnsignedInt() shr 1).toSignedByte()
        if (oldCarry) {
            cpu.registers.accumulator =
                (cpu.registers.accumulator.toUnsignedInt() or 0x80).toSignedByte()
        }
        cpu.processorStatus.resolveZeroAndNegativeFlags(cpu.registers.accumulator)
        cpu.workCyclesLeft = 2
    }
}