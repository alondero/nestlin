package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.cpu.opcode.*
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/** One resumable 6502 instruction. Every [step] performs exactly one real bus access. */
internal class MicrocodedInstruction private constructor(
    private val cpu: Cpu,
    val opcodeByte: Int,
    private val opcode: Opcode,
    private val startPc: Int,
    private val initialSp: Int,
) {
    private enum class Role { READ, STORE, RMW, IMPLIED, BRANCH, PUSH, PULL, JMP, JSR, RTS, RTI, BRK }
    private enum class Mode { IMPLIED, IMMEDIATE, ZERO_PAGE, ZERO_PAGE_INDEXED, ABSOLUTE, ABSOLUTE_INDEXED, INDIRECT_X, INDIRECT_Y }

    private val role = roleOf(opcode)
    private val mode = modeOf(opcode)
    private var phase = 1
    private var operandLow = 0
    private var operandHigh = 0
    private var pointerLow = 0
    private var pointerHigh = 0
    private var effectiveAddress = 0
    private var originalValue: Byte = 0
    private var complete = false
    private val semanticReads = linkedMapOf<Int, MutableList<Byte>>()

    val isComplete: Boolean get() = complete
    fun step() {
        check(!complete)
        phase++
        when (role) {
            Role.READ, Role.STORE, Role.RMW -> stepAddressed()
            Role.IMPLIED -> {
                read(startPc + 1)
                finishSemantics()
            }
            Role.BRANCH -> stepBranch(opcode as Branch)
            Role.PUSH -> stepPush()
            Role.PULL -> stepPull()
            Role.JMP -> stepJump()
            Role.JSR -> stepJsr()
            Role.RTS -> stepRts()
            Role.RTI -> stepRti()
            Role.BRK -> stepBrk()
        }
    }

    private fun stepAddressed() {
        when (mode) {
            Mode.IMMEDIATE -> {
                check(phase == 2)
                semanticRead(startPc + 1)
                finishSemantics()
            }
            Mode.ZERO_PAGE -> when (phase) {
                2 -> operandLow = semanticRead(startPc + 1).toUnsignedInt()
                3 -> finalAddressCycle(operandLow)
                4 -> { check(role == Role.RMW); write(effectiveAddress, originalValue) }
                5 -> { check(role == Role.RMW); finishRmw(effectiveAddress) }
            }
            Mode.ZERO_PAGE_INDEXED -> when (phase) {
                2 -> operandLow = semanticRead(startPc + 1).toUnsignedInt()
                3 -> read(operandLow)
                4 -> finalAddressCycle((operandLow + indexForMode()) and 0xFF)
                5 -> { check(role == Role.RMW); write(effectiveAddress, originalValue) }
                6 -> { check(role == Role.RMW); finishRmw(effectiveAddress) }
            }
            Mode.ABSOLUTE -> when (phase) {
                2 -> operandLow = semanticRead(startPc + 1).toUnsignedInt()
                3 -> operandHigh = semanticRead(startPc + 2).toUnsignedInt()
                4 -> finalAddressCycle(operandLow or (operandHigh shl 8))
                5 -> { check(role == Role.RMW); write(effectiveAddress, originalValue) }
                6 -> { check(role == Role.RMW); finishRmw(effectiveAddress) }
            }
            Mode.ABSOLUTE_INDEXED -> stepAbsoluteIndexed()
            Mode.INDIRECT_X -> stepIndirectX()
            Mode.INDIRECT_Y -> stepIndirectY()
            Mode.IMPLIED -> error("Addressed opcode ${opcode.mnemonic} has implied mode")
        }
    }

    private fun stepAbsoluteIndexed() {
        when (phase) {
            2 -> operandLow = semanticRead(startPc + 1).toUnsignedInt()
            3 -> {
                operandHigh = semanticRead(startPc + 2).toUnsignedInt()
                val base = operandLow or (operandHigh shl 8)
                effectiveAddress = (base + indexForMode()) and 0xFFFF
            }
            4 -> {
                val base = operandLow or (operandHigh shl 8)
                val provisional = (base and 0xFF00) or (effectiveAddress and 0xFF)
                val crossed = (base and 0xFF00) != (effectiveAddress and 0xFF00)
                when (role) {
                    Role.READ -> {
                        if (crossed) read(provisional) else {
                            semanticRead(effectiveAddress)
                            finishSemantics()
                        }
                    }
                    Role.STORE, Role.RMW -> read(provisional)
                    else -> error("Unexpected addressed role $role")
                }
            }
            5 -> when (role) {
                Role.READ -> {
                    semanticRead(effectiveAddress)
                    finishSemantics()
                }
                Role.STORE -> finishStore(effectiveAddress)
                Role.RMW -> originalValue = semanticRead(effectiveAddress)
                else -> error("Unexpected addressed role $role")
            }
            6 -> {
                check(role == Role.RMW)
                write(effectiveAddress, originalValue)
            }
            7 -> {
                check(role == Role.RMW)
                finishRmw(effectiveAddress)
            }
        }
    }

    private fun stepIndirectX() {
        when (phase) {
            2 -> operandLow = semanticRead(startPc + 1).toUnsignedInt()
            3 -> read(operandLow)
            4 -> pointerLow = semanticRead((operandLow + cpu.registers.indexX.toUnsignedInt()) and 0xFF).toUnsignedInt()
            5 -> {
                pointerHigh = semanticRead((operandLow + cpu.registers.indexX.toUnsignedInt() + 1) and 0xFF).toUnsignedInt()
                effectiveAddress = pointerLow or (pointerHigh shl 8)
            }
            6 -> when (role) {
                Role.READ -> { semanticRead(effectiveAddress); finishSemantics() }
                Role.STORE -> finishStore(effectiveAddress)
                Role.RMW -> originalValue = semanticRead(effectiveAddress)
                else -> error("Unexpected addressed role $role")
            }
            7 -> { check(role == Role.RMW); write(effectiveAddress, originalValue) }
            8 -> { check(role == Role.RMW); finishRmw(effectiveAddress) }
        }
    }

    private fun stepIndirectY() {
        when (phase) {
            2 -> operandLow = semanticRead(startPc + 1).toUnsignedInt()
            3 -> pointerLow = semanticRead(operandLow).toUnsignedInt()
            4 -> {
                pointerHigh = semanticRead((operandLow + 1) and 0xFF).toUnsignedInt()
                val base = pointerLow or (pointerHigh shl 8)
                effectiveAddress = (base + cpu.registers.indexY.toUnsignedInt()) and 0xFFFF
            }
            5 -> {
                val base = pointerLow or (pointerHigh shl 8)
                val provisional = (base and 0xFF00) or (effectiveAddress and 0xFF)
                val crossed = (base and 0xFF00) != (effectiveAddress and 0xFF00)
                if (role == Role.READ && !crossed) {
                    semanticRead(effectiveAddress)
                    finishSemantics()
                } else {
                    read(provisional)
                }
            }
            6 -> when (role) {
                Role.READ -> { semanticRead(effectiveAddress); finishSemantics() }
                Role.STORE -> finishStore(effectiveAddress)
                Role.RMW -> originalValue = semanticRead(effectiveAddress)
                else -> error("Unexpected addressed role $role")
            }
            7 -> { check(role == Role.RMW); write(effectiveAddress, originalValue) }
            8 -> { check(role == Role.RMW); finishRmw(effectiveAddress) }
        }
    }

    private fun finalAddressCycle(address: Int) {
        effectiveAddress = address
        when (role) {
            Role.READ -> { semanticRead(address); finishSemantics() }
            Role.STORE -> finishStore(address)
            Role.RMW -> {
                originalValue = semanticRead(address)
                // ZP/ABS RMW still have two following write cycles.
            }
            else -> error("Unexpected addressed role $role")
        }
    }

    private fun stepBranch(branch: Branch) {
        when (phase) {
            2 -> {
                operandLow = semanticRead(startPc + 1).toUnsignedInt()
                if (!branch.condition(cpu)) finishSemantics()
            }
            3 -> {
                read(startPc + 2)
                effectiveAddress = (startPc + 2 + operandLow.toSignedByte()) and 0xFFFF
                if (((startPc + 2) and 0xFF00) == (effectiveAddress and 0xFF00)) finishSemantics()
            }
            4 -> {
                val provisional = ((startPc + 2) and 0xFF00) or (effectiveAddress and 0xFF)
                read(provisional)
                finishSemantics()
            }
        }
    }

    private fun stepPush() {
        when (phase) {
            2 -> read(startPc + 1)
            3 -> {
                val (_, writes) = evaluateSemantics()
                val write = writes.single()
                write(write.first, write.second)
                complete = true
            }
        }
    }

    private fun stepPull() {
        when (phase) {
            2 -> read(startPc + 1)
            3 -> read(0x100 or initialSp)
            4 -> { semanticRead(0x100 or ((initialSp + 1) and 0xFF)); finishSemantics() }
        }
    }

    private fun stepJump() {
        if (opcode is Jump) {
            when (phase) {
                2 -> operandLow = semanticRead(startPc + 1).toUnsignedInt()
                3 -> { operandHigh = semanticRead(startPc + 2).toUnsignedInt(); finishSemantics() }
            }
        } else {
            when (phase) {
                2 -> operandLow = semanticRead(startPc + 1).toUnsignedInt()
                3 -> operandHigh = semanticRead(startPc + 2).toUnsignedInt()
                4 -> {
                    effectiveAddress = operandLow or (operandHigh shl 8)
                    pointerLow = semanticRead(effectiveAddress).toUnsignedInt()
                }
                5 -> {
                    val highAddress = (effectiveAddress and 0xFF00) or ((effectiveAddress + 1) and 0xFF)
                    pointerHigh = semanticRead(highAddress).toUnsignedInt()
                    finishSemantics()
                }
            }
        }
    }

    private fun stepJsr() {
        when (phase) {
            2 -> operandLow = semanticRead(startPc + 1).toUnsignedInt()
            3 -> read(0x100 or initialSp)
            4 -> write(0x100 or initialSp, ((startPc + 2) shr 8).toSignedByte())
            5 -> write(0x100 or ((initialSp - 1) and 0xFF), (startPc + 2).toSignedByte())
            6 -> { operandHigh = semanticRead(startPc + 2).toUnsignedInt(); finishSemantics() }
        }
    }

    private fun stepRts() {
        when (phase) {
            2 -> read(startPc + 1)
            3 -> read(0x100 or initialSp)
            4 -> pointerLow = semanticRead(0x100 or ((initialSp + 1) and 0xFF)).toUnsignedInt()
            5 -> pointerHigh = semanticRead(0x100 or ((initialSp + 2) and 0xFF)).toUnsignedInt()
            6 -> { read((pointerLow or (pointerHigh shl 8)) + 1); finishSemantics() }
        }
    }

    private fun stepRti() {
        when (phase) {
            2 -> read(startPc + 1)
            3 -> read(0x100 or initialSp)
            4 -> semanticRead(0x100 or ((initialSp + 1) and 0xFF))
            5 -> pointerLow = semanticRead(0x100 or ((initialSp + 2) and 0xFF)).toUnsignedInt()
            6 -> { pointerHigh = semanticRead(0x100 or ((initialSp + 3) and 0xFF)).toUnsignedInt(); finishSemantics() }
        }
    }

    private fun stepBrk() {
        when (phase) {
            2 -> read(startPc + 1)
            3 -> write(0x100 or initialSp, ((startPc + 2) shr 8).toSignedByte())
            4 -> write(0x100 or ((initialSp - 1) and 0xFF), (startPc + 2).toSignedByte())
            5 -> write(0x100 or ((initialSp - 2) and 0xFF), (cpu.processorStatus.asByte().toUnsignedInt() or 0x10).toSignedByte())
            6 -> semanticRead(0xFFFE)
            7 -> { semanticRead(0xFFFF); finishSemantics() }
        }
    }

    private fun finishStore(address: Int) {
        val (_, writes) = evaluateSemantics()
        val finalValue = writes.lastOrNull()?.also { finalWrite ->
            check(finalWrite.first == address) {
                "${opcode.mnemonic} replay wrote $${"%04X".format(finalWrite.first)}, expected $${"%04X".format(address)}"
            }
        }?.second ?: if (opcode is Tas) {
            val mask = ((address shr 8) + 1) and 0xFF
            (cpu.registers.accumulator.toUnsignedInt() and cpu.registers.indexX.toUnsignedInt() and mask).toSignedByte()
        } else {
            error("${opcode.mnemonic} produced no write during semantic replay")
        }
        write(address, finalValue)
        complete = true
    }

    private fun finishRmw(address: Int) {
        val (_, writes) = evaluateSemantics()
        val finalWrite = writes.last()
        write(address, finalWrite.second)
        complete = true
    }

    private fun finishSemantics() {
        evaluateSemantics()
        complete = true
    }

    private fun evaluateSemantics(): Pair<Unit, List<Pair<Int, Byte>>> {
        cpu.registers.programCounter = (startPc + 1).toSignedShort()
        cpu.registers.stackPointer = initialSp.toSignedByte()
        cpu.pageBoundaryFlag = false
        val result = try {
            cpu.memory.replayCpuSemantics(semanticReads) { opcode.evaluate(cpu) }
        } catch (error: IllegalStateException) {
            throw IllegalStateException(
                "${opcode.mnemonic} at $${"%04X".format(startPc)}: ${error.message}",
                error,
            )
        }
        cpu.workCyclesLeft = 0
        return result
    }

    private fun read(address: Int): Byte = cpu.memory[address and 0xFFFF]

    private fun semanticRead(address: Int): Byte {
        val normalized = address and 0xFFFF
        val value = read(normalized)
        semanticReads.getOrPut(normalized) { mutableListOf() }.add(value)
        return value
    }

    private fun write(address: Int, value: Byte) {
        cpu.memory[address and 0xFFFF] = value
    }

    private fun indexForMode(): Int = when {
        opcode is NopZpX || opcode is NopAbsX -> cpu.registers.indexX.toUnsignedInt()
        else -> when (val addressing = addressingOf(opcode)) {
        is ZeroPage -> if (addressing.x) cpu.registers.indexX.toUnsignedInt() else cpu.registers.indexY.toUnsignedInt()
        is Absolute -> if (addressing.x) cpu.registers.indexX.toUnsignedInt() else cpu.registers.indexY.toUnsignedInt()
        else -> 0
        }
    }

    fun save(out: DataOutput) {
        out.writeByte(opcodeByte)
        out.writeShort(startPc)
        out.writeByte(initialSp)
        out.writeByte(phase)
        out.writeShort(operandLow)
        out.writeShort(operandHigh)
        out.writeShort(pointerLow)
        out.writeShort(pointerHigh)
        out.writeInt(effectiveAddress)
        out.writeByte(originalValue.toInt())
        out.writeInt(semanticReads.size)
        semanticReads.forEach { (address, values) ->
            out.writeShort(address)
            out.writeInt(values.size)
            values.forEach { out.writeByte(it.toInt()) }
        }
    }

    companion object {
        fun start(cpu: Cpu, opcodeByte: Int, opcode: Opcode, startPc: Int) =
            MicrocodedInstruction(cpu, opcodeByte, opcode, startPc, cpu.registers.stackPointer.toUnsignedInt())

        fun load(cpu: Cpu, input: DataInput, lookup: (Int) -> Opcode?): MicrocodedInstruction {
            val opcodeByte = input.readUnsignedByte()
            val startPc = input.readUnsignedShort()
            val initialSp = input.readUnsignedByte()
            val instruction = MicrocodedInstruction(
                cpu,
                opcodeByte,
                requireNotNull(lookup(opcodeByte)) { "Cannot restore unmapped opcode $${"%02X".format(opcodeByte)}" },
                startPc,
                initialSp,
            )
            instruction.phase = input.readUnsignedByte()
            instruction.operandLow = input.readUnsignedShort()
            instruction.operandHigh = input.readUnsignedShort()
            instruction.pointerLow = input.readUnsignedShort()
            instruction.pointerHigh = input.readUnsignedShort()
            instruction.effectiveAddress = input.readInt()
            instruction.originalValue = input.readByte()
            repeat(input.readInt()) {
                val address = input.readUnsignedShort()
                val values = MutableList(input.readInt()) { input.readByte() }
                instruction.semanticReads[address] = values
            }
            return instruction
        }

        private fun roleOf(opcode: Opcode): Role = when (opcode) {
            is Load, is Logic, is Adc, is Sbc, is Compare, is Bit, is Lax, is Las,
            is NopZp, is NopAbs, is NopAbsX, is NopZpX, is NopImm, is Alr, is Arr -> Role.READ
            is Store, is Sax, is Ahx, is Shx, is Shy, is Tas -> Role.STORE
            is MemoryShiftRotate, is MemoryIncDec, is Dcp, is Isc, is Rla, is Rra, is Slo, is Sre -> Role.RMW
            is Branch -> Role.BRANCH
            is Push -> Role.PUSH
            is Pull -> Role.PULL
            is Jump, is JumpIndirect -> Role.JMP
            is JumpToSubroutine -> Role.JSR
            is ReturnFromSubroutine -> Role.RTS
            is ReturnFromInterrupt -> Role.RTI
            is Break -> Role.BRK
            else -> Role.IMPLIED
        }

        private fun modeOf(opcode: Opcode): Mode = when (opcode) {
            is NopZp -> Mode.ZERO_PAGE
            is NopAbs -> Mode.ABSOLUTE
            is NopAbsX -> Mode.ABSOLUTE_INDEXED
            is NopZpX -> Mode.ZERO_PAGE_INDEXED
            is NopImm, is Alr, is Arr -> Mode.IMMEDIATE
            else -> when (val addressing = addressingOf(opcode)) {
                Immediate -> Mode.IMMEDIATE
                is ZeroPage -> if (addressing.isIndexed) Mode.ZERO_PAGE_INDEXED else Mode.ZERO_PAGE
                is Absolute -> if (addressing.isIndexed) Mode.ABSOLUTE_INDEXED else Mode.ABSOLUTE
                IndirectX -> Mode.INDIRECT_X
                IndirectY -> Mode.INDIRECT_Y
                null -> Mode.IMPLIED
            }
        }

        private fun addressingOf(opcode: Opcode): Addressing? = when (opcode) {
            is Load -> opcode.addressing
            is Store -> opcode.addressing
            is Logic -> opcode.addressing
            is Adc -> opcode.addressing
            is Sbc -> opcode.addressing
            is Compare -> opcode.addressing
            is Bit -> opcode.addressing
            is MemoryShiftRotate -> opcode.addressing
            is MemoryIncDec -> opcode.addressing
            is Lax -> opcode.addressing
            is Sax -> opcode.addressing
            is Ahx -> opcode.addressing
            is Las -> opcode.addressing
            is Tas -> opcode.addressing
            is Shx -> opcode.addressing
            is Shy -> opcode.addressing
            is Dcp -> opcode.addressing
            is Isc -> opcode.addressing
            is Rla -> opcode.addressing
            is Rra -> opcode.addressing
            is Slo -> opcode.addressing
            is Sre -> opcode.addressing
            else -> null
        }
    }
}
