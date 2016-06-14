package com.github.alondero.nestlin.log

import com.github.alondero.nestlin.Cpu
import com.github.alondero.nestlin.UnhandledOpcodeException
import com.github.alondero.nestlin.toHexString
import com.github.alondero.nestlin.toUnsignedInt
import java.util.*

class Logger {

    private var opcodeLog = HashMap<Int, (Arguments) -> String>()

    init {
        opcodeLog[0x00] = {"${nValue()} ${nValue()}  BRK"}
        opcodeLog[0x01] = { indirectXOp(it, "ORA")}
        opcodeLog[0x05] = {"${it.byte1} ${nValue()}  ORA $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x06] = {"${it.byte1} ${nValue()}  ASL $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x08] = {"${nValue()} ${nValue()}  PHP"}
        opcodeLog[0x09] = {"${it.byte1} ${nValue()}  ORA #$${it.byte1}"}
        opcodeLog[0x0a] = {"${nValue()} ${nValue()}  ASL A"}
        opcodeLog[0x0d] = {"${it.byte1} ${it.byte2}  ORA $${it.byte2}${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x0e] = {"${it.byte1} ${it.byte2}  ASL $${it.byte2}${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x10] = {"${it.byte1} ${nValue()}  BPL $${it.progc}"}
        opcodeLog[0x11] = { indirectYOp(it, "ORA")}
        opcodeLog[0x18] = {"${nValue()} ${nValue()}  CLC"}
        opcodeLog[0x20] = {"${it.byte1} ${it.byte2}  JSR $${it.byte2}${it.byte1}"}
        opcodeLog[0x21] = { indirectXOp(it, "AND")}
        opcodeLog[0x24] = {"${it.byte1} ${nValue()}  BIT $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x25] = {"${it.byte1} ${nValue()}  AND $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x26] = {"${it.byte1} ${nValue()}  ROL $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x28] = {"${nValue()} ${nValue()}  PLP"}
        opcodeLog[0x29] = {"${it.byte1} ${nValue()}  AND #$${it.byte1}"}
        opcodeLog[0x2a] = {"${nValue()} ${nValue()}  ROL A"}
        opcodeLog[0x2c] = {"${it.byte1} ${it.byte2}  BIT $${it.byte2}${it.byte1} = ${format(it.cpu.registers.indexY)}"}
        opcodeLog[0x2d] = {"${it.byte1} ${it.byte2}  AND $${it.byte2}${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x2e] = {"${it.byte1} ${it.byte2}  ROL $${it.byte2}${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x30] = {"${it.byte1} ${nValue()}  BMI $${it.progc}"}
        opcodeLog[0x31] = { indirectYOp(it, "AND")}
        opcodeLog[0x38] = {"${nValue()} ${nValue()}  SEC"}
        opcodeLog[0x40] = {"${nValue()} ${nValue()}  RTI"}
        opcodeLog[0x41] = { indirectXOp(it, "EOR")}
        opcodeLog[0x45] = {"${it.byte1} ${nValue()}  EOR $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x46] = {"${it.byte1} ${nValue()}  LSR $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x48] = {"${nValue()} ${nValue()}  PHA"}
        opcodeLog[0x49] = {"${it.byte1} ${nValue()}  EOR #$${it.byte1}"}
        opcodeLog[0x4a] = {"${nValue()} ${nValue()}  LSR A"}
        opcodeLog[0x4c] = {"${it.byte1} ${it.byte2}  JMP $${it.byte2}${it.byte1}"}
        opcodeLog[0x4d] = {"${it.byte1} ${it.byte2}  EOR $${it.byte2}${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x4e] = {"${it.byte1} ${it.byte2}  LSR $${it.byte2}${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x50] = {"${it.byte1} ${nValue()}  BVC $${it.progc}"}
        opcodeLog[0x51] = { indirectYOp(it, "EOR")}
        opcodeLog[0x60] = {"${nValue()} ${nValue()}  RTS"}
        opcodeLog[0x61] = { indirectXOp(it, "ADC")}
        opcodeLog[0x65] = {"${it.byte1} ${nValue()}  ADC $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x66] = {"${it.byte1} ${nValue()}  ROR $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x6a] = {"${nValue()} ${nValue()}  ROR A"}
        opcodeLog[0x6c] = {"${it.byte1} ${it.byte2}  JMP ($${it.byte2}${it.byte1}) = XXXX"} // TODO: Fix this without replicating logic here
        opcodeLog[0x6d] = {"${it.byte1} ${it.byte2}  ADC $${it.byte2}${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x6e] = {"${it.byte1} ${it.byte2}  ROR $${it.byte2}${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x68] = {"${nValue()} ${nValue()}  PLA"}
        opcodeLog[0x69] = {"${it.byte1} ${nValue()}  ADC #$${it.byte1}"}
        opcodeLog[0x70] = {"${it.byte1} ${nValue()}  BVS $${it.progc}"}
        opcodeLog[0x71] = { indirectYOp(it, "ADC")}
        opcodeLog[0x78] = {"${nValue()} ${nValue()}  SEI"}
        opcodeLog[0x81] = { indirectXOp(it, "STA")}
        opcodeLog[0x84] = {"${it.byte1} ${nValue()}  STY $${it.byte1} = ${format(it.cpu.registers.indexY)}"}
        opcodeLog[0x85] = {"${it.byte1} ${nValue()}  STA $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x86] = {"${it.byte1} ${nValue()}  STX $${it.byte1} = ${format(it.cpu.registers.indexX)}"}
        opcodeLog[0x88] = {"${nValue()} ${nValue()}  DEY"}
        opcodeLog[0x8a] = {"${nValue()} ${nValue()}  TXA"}
        opcodeLog[0x8c] = {"${it.byte1} ${it.byte2}  STY $${it.byte2}${it.byte1} = ${format(it.cpu.registers.indexY)}"}
        opcodeLog[0x8d] = {"${it.byte1} ${it.byte2}  STA $${it.byte2}${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0x8e] = {"${it.byte1} ${it.byte2}  STX $${it.byte2}${it.byte1} = ${format(it.cpu.registers.indexX)}"}
        opcodeLog[0x90] = {"${it.byte1} ${nValue()}  BCC $${it.progc}"}
        opcodeLog[0x91] = { indirectYOp(it, "STA")}
        opcodeLog[0x98] = {"${nValue()} ${nValue()}  TYA"}
        opcodeLog[0x9a] = {"${nValue()} ${nValue()}  TXS"}
        opcodeLog[0xa0] = {"${it.byte1} ${nValue()}  LDY #$${it.byte1}"}
        opcodeLog[0xa1] = { indirectXOp(it, "LDA")}
        opcodeLog[0xa2] = {"${it.byte1} ${nValue()}  LDX #$${it.byte1}"}
        opcodeLog[0xa4] = {"${it.byte1} ${nValue()}  LDY $${it.byte1} = ${format(it.cpu.registers.indexY)}"}
        opcodeLog[0xa5] = {"${it.byte1} ${nValue()}  LDA $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0xa6] = {"${it.byte1} ${nValue()}  LDX $${it.byte1} = ${format(it.cpu.registers.indexX)}"}
        opcodeLog[0xa8] = {"${nValue()} ${nValue()}  TAY"}
        opcodeLog[0xa9] = {"${it.byte1} ${nValue()}  LDA #$${it.byte1}"}
        opcodeLog[0xaa] = {"${nValue()} ${nValue()}  TAX"}
        opcodeLog[0xac] = {"${it.byte1} ${it.byte2}  LDY $${it.byte2}${it.byte1} = ${format(it.cpu.registers.indexY)}"}
        opcodeLog[0xad] = {"${it.byte1} ${it.byte2}  LDA $${it.byte2}${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0xae] = {"${it.byte1} ${it.byte2}  LDX $${it.byte2}${it.byte1} = ${format(it.cpu.registers.indexX)}"}
        opcodeLog[0xb0] = {"${it.byte1} ${nValue()}  BCS $${it.progc}"}
        opcodeLog[0xb1] = { indirectYOp(it, "LDA")}
        opcodeLog[0xb8] = {"${nValue()} ${nValue()}  CLV"}
        opcodeLog[0xb9] = { absoluteYOp(it, "LDA")}
        opcodeLog[0xba] = {"${nValue()} ${nValue()}  TSX"}
        opcodeLog[0xc0] = {"${it.byte1} ${nValue()}  CPY #$${it.byte1}"}
        opcodeLog[0xc1] = { indirectXOp(it, "CMP")}
        opcodeLog[0xc4] = {"${it.byte1} ${nValue()}  CPY $${it.byte1} = ${format(it.cpu.registers.indexY)}"}
        opcodeLog[0xc5] = {"${it.byte1} ${nValue()}  CMP $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0xc6] = {"${it.byte1} ${nValue()}  DEC $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0xc8] = {"${nValue()} ${nValue()}  INY"}
        opcodeLog[0xc9] = {"${it.byte1} ${nValue()}  CMP #$${it.byte1}"}
        opcodeLog[0xca] = {"${nValue()} ${nValue()}  DEX"}
        opcodeLog[0xcc] = {"${it.byte1} ${it.byte2}  CPY $${it.byte2}${it.byte1} = ${format(it.cpu.registers.indexY)}"}
        opcodeLog[0xcd] = {"${it.byte1} ${it.byte2}  CMP $${it.byte2}${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0xce] = {"${it.byte1} ${it.byte2}  DEC $${it.byte2}${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0xd0] = {"${it.byte1} ${nValue()}  BNE $${it.progc}"}
        opcodeLog[0xd1] = { indirectYOp(it, "CMP")}
        opcodeLog[0xd8] = {"${nValue()} ${nValue()}  CLD"}
        opcodeLog[0xe0] = {"${it.byte1} ${nValue()}  CPX #$${it.byte1}"}
        opcodeLog[0xe1] = { indirectXOp(it, "SBC")}
        opcodeLog[0xe4] = {"${it.byte1} ${nValue()}  CPX $${it.byte1} = ${format(it.cpu.registers.indexX)}"}
        opcodeLog[0xe5] = {"${it.byte1} ${nValue()}  SBC $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0xe6] = {"${it.byte1} ${nValue()}  INC $${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0xe8] = {"${nValue()} ${nValue()}  INX"}
        opcodeLog[0xe9] = {"${it.byte1} ${nValue()}  SBC #$${it.byte1}"}
        opcodeLog[0xea] = {"${nValue()} ${nValue()}  NOP"}
        opcodeLog[0xec] = {"${it.byte1} ${it.byte2}  CPX $${it.byte2}${it.byte1} = ${format(it.cpu.registers.indexX)}"}
        opcodeLog[0xed] = {"${it.byte1} ${it.byte2}  SBC $${it.byte2}${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0xee] = {"${it.byte1} ${it.byte2}  INC $${it.byte2}${it.byte1} = ${format(it.cpu.registers.accumulator)}"}
        opcodeLog[0xf0] = {"${it.byte1} ${nValue()}  BEQ $${it.progc}"}
        opcodeLog[0xf1] = { indirectYOp(it, "SBC")}
        opcodeLog[0xf8] = {"${nValue()} ${nValue()}  SED"}
    }

    //  TODO: This is stupid... Refactor so that don't need to replicate logic here
    private fun indirectXOp(args: Arguments, op: String): String {
        return args.let {
            val lookupAddr = it.cpu.memory[(it.cpu.memory[it.cpu.registers.programCounter.toUnsignedInt()] + it.cpu.registers.indexX) and 0xFF, ((it.cpu.memory[it.cpu.registers.programCounter.toUnsignedInt()] + it.cpu.registers.indexX) and 0xFF) + 1]
            "${it.byte1} ${nValue()}  $op (${it.byte1},X) @ ${it.byte1} = ${lookupAddr.toHexString()} = ${it.cpu.memory[lookupAddr.toUnsignedInt()].toHexString()}"
        }
    }

    private fun indirectYOp(args: Arguments, op: String): String {
        return args.let {
            val mem = it.cpu.memory[it.cpu.registers.programCounter.toUnsignedInt()].toUnsignedInt()
            val addr = it.cpu.memory[mem].toUnsignedInt() or (it.cpu.memory[(mem+1) and 0xFF].toUnsignedInt() shl 8)
            val shiftedAddr = (addr + it.cpu.registers.indexY.toUnsignedInt()) and 0xFFFF
            "${it.byte1} ${nValue()}  $op ($${it.byte1}),Y = ${"%04X".format(addr)} @ ${"%04X".format(shiftedAddr)} = ${it.cpu.memory[shiftedAddr].toHexString()}"
        }
    }

    private fun absoluteYOp(args: Arguments, op: String): String {
        return args.let {
            val addr = it.cpu.memory[it.cpu.registers.programCounter.toUnsignedInt(), it.cpu.registers.programCounter.toUnsignedInt()+1]
            val shiftedAddr = addr.toUnsignedInt() + it.cpu.registers.indexY.toUnsignedInt()
            val mem = it.cpu.memory[shiftedAddr and 0xFFFF]
            "${it.byte1} ${it.byte2}  $op $${"%04X".format(addr)},Y @ ${"%04X".format(shiftedAddr)} = ${mem.toHexString()}"
        }
    }

    fun cpuTick(initialPC: Short, opcodeVal: Int, cpu: Cpu) {
        val arguments = Arguments(
                format(cpu.memory[initialPC.inc().toUnsignedInt()]),
                format(cpu.memory[initialPC.inc().inc().toUnsignedInt()]),
                format((initialPC.inc() + cpu.memory[initialPC.inc().toUnsignedInt()].inc()).toShort()),
                cpu)

        if (!opcodeLog.containsKey(opcodeVal)) {
            throw UnhandledOpcodeException(opcodeVal)
        }

        println("${initialPC.toHexString()}  ${opcodeVal.toHexString()} ${"%-38s".format(opcodeLog[opcodeVal]!!(arguments))} " +
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

