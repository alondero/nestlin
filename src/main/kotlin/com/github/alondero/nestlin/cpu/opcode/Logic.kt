package com.github.alondero.nestlin.cpu.opcode

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt

/**
 * Logic family — AND, ORA, EOR (24 opcodes total).
 *
 * Combines accumulator with memory operand via a bitwise op and stores
 * the result back into A. Sets Z and N flags.
 * Original Opcodes.kt:566-574, 84-107.
 */
class Logic(
    override val addressing: Addressing,
    val op: (Int, Int) -> Int,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles), ReadOpcode {
    override fun applyRead(cpu: Cpu, value: Byte) {
        val result = op(cpu.registers.accumulator.toUnsignedInt(), value.toUnsignedInt()).toSignedByte()
        cpu.registers.accumulator = result
        cpu.processorStatus.resolveZeroAndNegativeFlags(result)
    }

    override fun evaluate(cpu: Cpu) {
        applyRead(cpu, addressing.value(cpu))
        // Issue #17 / #172: +1 cycle on page cross for abs,X / abs,Y /
        // ($zp),Y. AND / ORA / EOR use all three indexed variants.
        cpu.workCyclesLeft = cycles + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}
