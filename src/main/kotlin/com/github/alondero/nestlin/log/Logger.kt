package com.github.alondero.nestlin.log

import com.github.alondero.nestlin.Cpu
import com.github.alondero.nestlin.toHexString
import com.github.alondero.nestlin.toUnsignedInt
import java.util.*

class Logger {

    private var opcodeLog = HashMap<Int, (Arguments) -> String>()

    init {
        opcodeLog[0x00] = {"BRK"}
        opcodeLog[0x20] = {"${it.byte1} ${it.byte2}  JSR $${it.byte2}${it.byte1}"}
        opcodeLog[0x78] = {"SEI"}
        opcodeLog[0xd8] = {"CLD"}
        opcodeLog[0x4c] = {"${it.byte1} ${it.byte2}  JMP $${it.byte2}${it.byte1}"}
        opcodeLog[0xa2] = {"${it.byte1} ${nValue()}  LDX #$${it.byte1}"}
        opcodeLog[0x86] = {"${it.byte1} ${nValue()}  STX $${it.byte1} = ${format(it.cpu.registers.indexX)}"}
        opcodeLog[0xea] = {"${nValue()} ${nValue()}  NOP"}
        opcodeLog[0x38] = {"${nValue()} ${nValue()}  SEC"}
    }

    fun cpuTick(initialPC: Short, opcodeVal: Int, cpu: Cpu) {
        val arguments = Arguments(
                format(cpu.memory[initialPC.toUnsignedInt() + 1]),
                format(cpu.memory[initialPC.toUnsignedInt() + 2]),
                cpu)

        println("${initialPC.toHexString()}  ${opcodeVal.toHexString()} ${"%-39s".format(opcodeLog[opcodeVal]!!(arguments))} " +
                "A:${format(cpu.registers.accumulator)} " +
                "X:${format(cpu.registers.indexX)} " +
                "Y:${format(cpu.registers.indexY)} " +
                "P:${format(cpu.processorStatus.asByte())} " +
                "SP:${format(cpu.registers.stackPointer)} " +
                "CYC:${"%1$3s".format(cpu.cycles.toString())}")
    }

    private fun format(byte: Byte): String = "%02X".format(byte.toUnsignedInt())
    private fun nValue() = "  "

    private data class Arguments(
            val byte1: String,
            val byte2: String,
            val cpu: Cpu
    )
}

