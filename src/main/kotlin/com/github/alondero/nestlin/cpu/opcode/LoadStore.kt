package com.github.alondero.nestlin.cpu.opcode

import com.github.alondero.nestlin.cpu.Cpu
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt

/**
 * Load family — LDA, LDX, LDY. 18 opcodes.
 *
 * Reads the operand at the addressing-mode location and writes it into a
 * register. Sets Z and N flags based on the byte loaded.
 * Original Opcodes.kt:576-585, 110-127.
 */
class Load(
    val addressing: Addressing,
    /** Selector for which register receives the loaded value. */
    val setter: (Cpu, Byte) -> Unit,
    /** Real-6502 cycle count for this addressing mode. */
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles) {
    override fun evaluate(cpu: Cpu) {
        val value = addressing.value(cpu)
        setter(cpu, value)
        cpu.processorStatus.resolveZeroAndNegativeFlags(value)
        // Issue #17 / #172: +1 cycle on page cross for abs,X / abs,Y /
        // ($zp),Y. The Addressing class set `cpu.pageBoundaryFlag` during
        // its address() call when the indexed address crossed a page.
        cpu.workCyclesLeft = cycles + (if (cpu.pageBoundaryFlag) 1 else 0)
    }
}

/**
 * Store family — STA, STX, STY. 13 opcodes.
 *
 * Writes a register's value to the addressing-mode location. No flags
 * affected. Original Opcodes.kt:538-543, 34-46.
 */
class Store(
    val addressing: Addressing,
    /** Selector for which register is stored. */
    val source: (Cpu) -> Byte,
    cycles: Int,
    override val mnemonic: String,
) : Opcode(cycles) {
    override fun evaluate(cpu: Cpu) {
        val addr = addressing.address(cpu)
        cpu.memory[addr] = source(cpu)
        // Stores (STA/STX/STY) do NOT add a cycle on page cross — only
        // read operations do. Real 6502 STA abs,X is always 5 cycles
        // regardless of whether the indexed address crosses a page
        // boundary. See issue #17 / #172.
        cpu.workCyclesLeft = cycles
    }
}