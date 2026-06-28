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
    val addressing: Addressing,
    val op: (Int, Int) -> Int,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles) {
    override fun evaluate(cpu: Cpu) {
        val acc = cpu.registers.accumulator.toUnsignedInt()
        val mem = addressing.value(cpu).toUnsignedInt()
        cpu.registers.accumulator = op(acc, mem).toSignedByte().apply {
            cpu.processorStatus.resolveZeroAndNegativeFlags(this)
        }
        cpu.workCyclesLeft = cycles
    }
}