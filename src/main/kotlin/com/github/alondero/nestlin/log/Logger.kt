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
        opcodeLog[0x78] = {"${nValue()} ${nValue()}  SEI"}
        opcodeLog[0xd8] = {"${nValue()} ${nValue()}  CLD"}
        opcodeLog[0xf8] = {"${nValue()} ${nValue()}  SED"}
        opcodeLog[0x4c] = {"${it.byte1} ${it.byte2}  JMP $${it.byte2}${it.byte1}"}
        opcodeLog[0xa2] = {"${it.byte1} ${nValue()}  LDX #$${it.byte1}"}
        opcodeLog[0x86] = {"${it.byte1} ${nValue()}  STX $${it.byte1} = ${format(it.cpu.registers.indexX)}"}
        opcodeLog[0xea] = {"${nValue()} ${nValue()}  NOP"}
        opcodeLog[0x38] = {"${nValue()} ${nValue()}  SEC"}
        opcodeLog[0xb0] = {"${it.byte1} ${nValue()}  BCS $${it.progc}"}
        opcodeLog[0x18] = {"${nValue()} ${nValue()}  CLC"}
        opcodeLog[0x90] = {"${it.byte1} ${nValue()}  BCC $${it.progc}"}
        opcodeLog[0xa9] = {"${it.byte1} ${nValue()}  LDA #$${it.byte1}"}
        opcodeLog[0xf0] = {"${it.byte1} ${nValue()}  BEQ $${it.progc}"}
        opcodeLog[0xd0] = {"${it.byte1} ${nValue()}  BNE $${it.progc}"}
        opcodeLog[0x85] = {"${it.byte1} ${nValue()}  STA $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x24] = {"${it.byte1} ${nValue()}  BIT $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x70] = {"${it.byte1} ${nValue()}  BVS $${it.progc}"}
        opcodeLog[0x50] = {"${it.byte1} ${nValue()}  BVC $${it.progc}"}
        opcodeLog[0x10] = {"${it.byte1} ${nValue()}  BPL $${it.progc}"}
        opcodeLog[0x30] = {"${it.byte1} ${nValue()}  BMI $${it.progc}"}
        opcodeLog[0x60] = {"${nValue()} ${nValue()}  RTS"}
        opcodeLog[0x08] = {"${nValue()} ${nValue()}  PHP"}
        opcodeLog[0x48] = {"${nValue()} ${nValue()}  PHA"}
        opcodeLog[0x68] = {"${nValue()} ${nValue()}  PLA"}
        opcodeLog[0x28] = {"${nValue()} ${nValue()}  PLP"}
        opcodeLog[0x29] = {"${it.byte1} ${nValue()}  AND #$${it.byte1}"}
        opcodeLog[0x09] = {"${it.byte1} ${nValue()}  ORA #$${it.byte1}"}
        opcodeLog[0x49] = {"${it.byte1} ${nValue()}  EOR #$${it.byte1}"}
        opcodeLog[0xc9] = {"${it.byte1} ${nValue()}  CMP #$${it.byte1}"}
        opcodeLog[0xb8] = {"${nValue()} ${nValue()}  CLV"}
        opcodeLog[0x69] = {"${it.byte1} ${nValue()}  ADC #$${it.byte1}"}
    }

    fun cpuTick(initialPC: Short, opcodeVal: Int, cpu: Cpu) {
        val arguments = Arguments(
                format(cpu.memory[initialPC.inc().toUnsignedInt()]),
                format(cpu.memory[initialPC.inc().inc().toUnsignedInt()]),
                format((initialPC.inc() + cpu.memory[initialPC.inc().toUnsignedInt()].inc()).toShort()),
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
    private fun format(short: Short): String = "%04X".format(short.toUnsignedInt())
    private fun nValue() = "  "

    private data class Arguments(
            val byte1: String,
            val byte2: String,
            val progc: String,
            val cpu: Cpu
    )
}

