package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.cpu.opcode.*
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toSignedShort
import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

/**
 * Reusable 6502 instruction sequencer.
 *
 * The object is allocated once by [Cpu] and reset for each opcode. Its fields
 * are the live latches of one instruction; no reads are recorded and no opcode
 * is replayed. Every call to [step] performs one real CPU-bus operation and
 * updates architectural state at the point the 6502 does so.
 */
internal class MicrocodedInstruction(private val cpu: Cpu) {
    private enum class Mode { IMPLIED, IMMEDIATE, ZERO_PAGE, ZERO_PAGE_INDEXED, ABSOLUTE, ABSOLUTE_INDEXED, INDIRECT_X, INDIRECT_Y }

    private var opcodeByte = 0
    private var opcode: Opcode? = null
    private var startPc = 0
    private var initialSp = 0
    private var phase = 1
    private var operandLow = 0
    private var operandHigh = 0
    private var pointerLow = 0
    private var pointerHigh = 0
    private var effectiveAddress = 0
    private var baseAddress = 0
    private var originalValue: Byte = 0
    private var complete = false
    private var branchTaken = false
    private var branchTarget = 0
    private var branchCrossed = false

    val isComplete: Boolean get() = complete

    fun begin(opcodeByte: Int, opcode: Opcode, startPc: Int) {
        this.opcodeByte = opcodeByte and 0xFF
        this.opcode = opcode
        this.startPc = startPc and 0xFFFF
        this.initialSp = cpu.registers.stackPointer.toUnsignedInt()
        phase = 1
        operandLow = 0
        operandHigh = 0
        pointerLow = 0
        pointerHigh = 0
        effectiveAddress = 0
        baseAddress = 0
        originalValue = 0
        complete = false
        branchTaken = false
        branchTarget = 0
        branchCrossed = false
    }

    fun step() {
        check(!complete) { "completed CPU instruction stepped again" }
        phase++
        val current = requireNotNull(opcode)
        when {
            current is Branch -> stepBranch(current)
            current is Push -> stepPush(current)
            current is Pull -> stepPull(current)
            current is Jump -> stepJump()
            current is JumpIndirect -> stepJumpIndirect()
            current is JumpToSubroutine -> stepJsr()
            current is ReturnFromSubroutine -> stepRts()
            current is ReturnFromInterrupt -> stepRti()
            current is Break -> stepBrk()
            current is AddressedOpcode -> stepAddressed(current)
            else -> stepImplied(current)
        }
    }

    private fun stepImplied(current: Opcode) {
        check(phase == 2)
        read(startPc + 1)
        when (current) {
            is NopImplied, is Xaa -> Unit
            is Kil -> cpu.idle = true
            is Flag -> current.setter(cpu)
            is Transfer -> {
                val value = current.from(cpu)
                cpu.processorStatus.resolveZeroAndNegativeFlags(value)
                current.to(cpu, value)
            }
            is TransferNoFlags -> cpu.registers.stackPointer = cpu.registers.indexX
            is RegisterIncDec -> {
                val result = ((current.register(cpu).toUnsignedInt() + current.delta) and 0xFF).toSignedByte()
                current.setter(cpu, result)
                cpu.processorStatus.resolveZeroAndNegativeFlags(result)
            }
            is AccShiftRotate -> current.operation(cpu)
            else -> error("${current.mnemonic} has no implied implementation")
        }
        complete = true
    }

    private fun stepAddressed(current: AddressedOpcode) {
        when (modeOf(current.addressing)) {
            Mode.IMMEDIATE -> {
                check(phase == 2)
                applyRead(current, fetchOperand().toSignedByte())
                complete = true
            }
            Mode.ZERO_PAGE -> when (phase) {
                2 -> operandLow = fetchOperand()
                3 -> readAddressedFinal(current, operandLow)
                4 -> write(effectiveAddress, originalValue)
                5 -> finishRmw(current)
            }
            Mode.ZERO_PAGE_INDEXED -> when (phase) {
                2 -> operandLow = fetchOperand()
                3 -> read(operandLow)
                4 -> readAddressedFinal(current, (operandLow + indexOf(current.addressing)) and 0xFF)
                5 -> write(effectiveAddress, originalValue)
                6 -> finishRmw(current)
            }
            Mode.ABSOLUTE -> when (phase) {
                2 -> operandLow = fetchOperand()
                3 -> operandHigh = fetchOperand()
                4 -> readAddressedFinal(current, operandLow or (operandHigh shl 8))
                5 -> write(effectiveAddress, originalValue)
                6 -> finishRmw(current)
            }
            Mode.ABSOLUTE_INDEXED -> stepAbsoluteIndexed(current)
            Mode.INDIRECT_X -> stepIndirectX(current)
            Mode.INDIRECT_Y -> stepIndirectY(current)
            Mode.IMPLIED -> error("${(current as Opcode).mnemonic} has no addressed micro-sequence")
        }
    }

    private fun stepAbsoluteIndexed(current: AddressedOpcode) {
        when (phase) {
            2 -> operandLow = fetchOperand()
            3 -> {
                operandHigh = fetchOperand()
                baseAddress = operandLow or (operandHigh shl 8)
                effectiveAddress = (baseAddress + indexOf(current.addressing)) and 0xFFFF
                branchCrossed = (baseAddress and 0xFF00) != (effectiveAddress and 0xFF00)
                if (!current.isStoreLike() && !current.isRmwLike() && branchCrossed) cpu.workCyclesLeft++
            }
            4 -> {
                val provisional = (baseAddress and 0xFF00) or (effectiveAddress and 0xFF)
                when {
                    current.isStoreLike() || current.isRmwLike() || branchCrossed -> read(provisional)
                    else -> readAddressedFinal(current, effectiveAddress)
                }
            }
            5 -> when {
                current.isRmwLike() -> readAddressedFinal(current, effectiveAddress)
                current.isStoreLike() -> writeStore(current, effectiveAddress)
                else -> readAddressedFinal(current, effectiveAddress)
            }
            6 -> {
                check(current.isRmwLike())
                write(effectiveAddress, originalValue)
            }
            7 -> {
                check(current.isRmwLike())
                finishRmw(current)
            }
        }
    }

    private fun stepIndirectX(current: AddressedOpcode) {
        when (phase) {
            2 -> operandLow = fetchOperand()
            3 -> read(operandLow)
            4 -> pointerLow = read((operandLow + indexOf(current.addressing)) and 0xFF).toUnsignedInt()
            5 -> {
                pointerHigh = read((operandLow + indexOf(current.addressing) + 1) and 0xFF).toUnsignedInt()
                effectiveAddress = pointerLow or (pointerHigh shl 8)
            }
            6 -> {
                if (current.isRmwLike()) readAddressedFinal(current, effectiveAddress)
                else if (current.isStoreLike()) writeStore(current, effectiveAddress)
                else {
                    applyRead(current, read(effectiveAddress))
                    complete = true
                }
            }
            7 -> {
                check(current.isRmwLike())
                write(effectiveAddress, originalValue)
            }
            8 -> {
                check(current.isRmwLike())
                finishRmw(current)
            }
        }
    }

    private fun stepIndirectY(current: AddressedOpcode) {
        when (phase) {
            2 -> operandLow = fetchOperand()
            3 -> pointerLow = read(operandLow).toUnsignedInt()
            4 -> {
                pointerHigh = read((operandLow + 1) and 0xFF).toUnsignedInt()
                baseAddress = pointerLow or (pointerHigh shl 8)
                effectiveAddress = (baseAddress + indexOf(current.addressing)) and 0xFFFF
                branchCrossed = (baseAddress and 0xFF00) != (effectiveAddress and 0xFF00)
                if (!current.isStoreLike() && !current.isRmwLike() && branchCrossed) cpu.workCyclesLeft++
            }
            5 -> {
                val provisional = (baseAddress and 0xFF00) or (effectiveAddress and 0xFF)
                when {
                    current.isStoreLike() || current.isRmwLike() || branchCrossed -> read(provisional)
                    else -> {
                        applyRead(current, read(effectiveAddress))
                        complete = true
                    }
                }
            }
            6 -> when {
                current.isStoreLike() -> writeStore(current, effectiveAddress)
                current.isRmwLike() -> readAddressedFinal(current, effectiveAddress)
                else -> {
                    applyRead(current, read(effectiveAddress))
                    complete = true
                }
            }
            7 -> {
                check(current.isRmwLike())
                write(effectiveAddress, originalValue)
            }
            8 -> {
                check(current.isRmwLike())
                finishRmw(current)
            }
        }
    }

    private fun readAddressedFinal(current: AddressedOpcode, address: Int) {
        effectiveAddress = address and 0xFFFF
        if (current.isStoreLike()) {
            writeStore(current, effectiveAddress)
            return
        }
        val value = read(effectiveAddress)
        if (current.isRmwLike()) originalValue = value
        else {
            applyRead(current, value)
            complete = true
        }
    }

    private fun stepBranch(branch: Branch) {
        when (phase) {
            2 -> {
                val offset = fetchOperand()
                branchTaken = branch.condition(cpu)
                if (!branchTaken) {
                    complete = true
                    return
                }
                branchTarget = (cpu.registers.programCounter.toUnsignedInt() + offset.toSignedByte()) and 0xFFFF
                branchCrossed = (cpu.registers.programCounter.toUnsignedInt() and 0xFF00) != (branchTarget and 0xFF00)
                cpu.workCyclesLeft++
                cpu.pageBoundaryFlag = branchCrossed
            }
            3 -> {
                read(cpu.registers.programCounter.toUnsignedInt())
                if (branchCrossed) cpu.workCyclesLeft++
                else {
                    cpu.registers.programCounter = branchTarget.toSignedShort()
                    if (branchTarget == startPc) cpu.idle = true
                    complete = true
                }
            }
            4 -> {
                val provisional = (cpu.registers.programCounter.toUnsignedInt() and 0xFF00) or (branchTarget and 0xFF)
                read(provisional)
                cpu.registers.programCounter = branchTarget.toSignedShort()
                if (branchTarget == startPc) cpu.idle = true
                complete = true
            }
        }
    }

    private fun stepPush(push: Push) {
        when (phase) {
            2 -> read(startPc + 1)
            3 -> {
                write(0x100 or cpu.registers.stackPointer.toUnsignedInt(), push.source(cpu))
                cpu.registers.stackPointer--
                complete = true
            }
        }
    }

    private fun stepPull(pull: Pull) {
        when (phase) {
            2 -> read(startPc + 1)
            3 -> read(0x100 or cpu.registers.stackPointer.toUnsignedInt())
            4 -> {
                cpu.registers.stackPointer++
                val value = read(0x100 or cpu.registers.stackPointer.toUnsignedInt())
                pull.setter(cpu, value)
                if (pull.resolvesFlags) cpu.processorStatus.resolveZeroAndNegativeFlags(value)
                complete = true
            }
        }
    }

    private fun stepJump() {
        when (phase) {
            2 -> operandLow = fetchOperand()
            3 -> {
                operandHigh = fetchOperand()
                val target = operandLow or (operandHigh shl 8)
                cpu.registers.programCounter = target.toSignedShort()
                if (target == startPc) cpu.idle = true
                complete = true
            }
        }
    }

    private fun stepJumpIndirect() {
        when (phase) {
            2 -> operandLow = fetchOperand()
            3 -> operandHigh = fetchOperand()
            4 -> {
                effectiveAddress = operandLow or (operandHigh shl 8)
                pointerLow = read(effectiveAddress).toUnsignedInt()
            }
            5 -> {
                val highAddress = (effectiveAddress and 0xFF00) or ((effectiveAddress + 1) and 0xFF)
                pointerHigh = read(highAddress).toUnsignedInt()
                val target = pointerLow or (pointerHigh shl 8)
                cpu.registers.programCounter = target.toSignedShort()
                if (target == startPc) cpu.idle = true
                complete = true
            }
        }
    }

    private fun stepJsr() {
        when (phase) {
            2 -> operandLow = fetchOperand()
            3 -> read(0x100 or cpu.registers.stackPointer.toUnsignedInt())
            4 -> {
                val returnAddress = cpu.registers.programCounter.toUnsignedInt()
                write(0x100 or cpu.registers.stackPointer.toUnsignedInt(), (returnAddress shr 8).toSignedByte())
                cpu.registers.stackPointer--
            }
            5 -> {
                val returnAddress = cpu.registers.programCounter.toUnsignedInt()
                write(0x100 or cpu.registers.stackPointer.toUnsignedInt(), returnAddress.toSignedByte())
                cpu.registers.stackPointer--
            }
            6 -> {
                operandHigh = fetchOperand()
                cpu.registers.programCounter = (operandLow or (operandHigh shl 8)).toSignedShort()
                complete = true
            }
        }
    }

    private fun stepRts() {
        when (phase) {
            2 -> read(startPc + 1)
            3 -> read(0x100 or cpu.registers.stackPointer.toUnsignedInt())
            4 -> {
                cpu.registers.stackPointer++
                pointerLow = read(0x100 or cpu.registers.stackPointer.toUnsignedInt()).toUnsignedInt()
            }
            5 -> {
                cpu.registers.stackPointer++
                pointerHigh = read(0x100 or cpu.registers.stackPointer.toUnsignedInt()).toUnsignedInt()
            }
            6 -> {
                val returnAddress = ((pointerHigh shl 8) or pointerLow) + 1
                read(returnAddress)
                cpu.registers.programCounter = (returnAddress and 0xFFFF).toSignedShort()
                complete = true
            }
        }
    }

    private fun stepRti() {
        when (phase) {
            2 -> read(startPc + 1)
            3 -> read(0x100 or cpu.registers.stackPointer.toUnsignedInt())
            4 -> {
                cpu.registers.stackPointer++
                cpu.processorStatus.toFlags(read(0x100 or cpu.registers.stackPointer.toUnsignedInt()))
            }
            5 -> {
                cpu.registers.stackPointer++
                pointerLow = read(0x100 or cpu.registers.stackPointer.toUnsignedInt()).toUnsignedInt()
            }
            6 -> {
                cpu.registers.stackPointer++
                pointerHigh = read(0x100 or cpu.registers.stackPointer.toUnsignedInt()).toUnsignedInt()
                cpu.registers.programCounter = (pointerLow or (pointerHigh shl 8)).toSignedShort()
                complete = true
            }
        }
    }

    private fun stepBrk() {
        when (phase) {
            2 -> {
                read(startPc + 1)
                cpu.registers.programCounter++
            }
            3 -> {
                write(0x100 or cpu.registers.stackPointer.toUnsignedInt(), (cpu.registers.programCounter.toUnsignedInt() shr 8).toSignedByte())
                cpu.registers.stackPointer--
            }
            4 -> {
                write(0x100 or cpu.registers.stackPointer.toUnsignedInt(), cpu.registers.programCounter.toUnsignedInt().toSignedByte())
                cpu.registers.stackPointer--
            }
            5 -> {
                cpu.processorStatus.breakCommand = true
                write(0x100 or cpu.registers.stackPointer.toUnsignedInt(), cpu.processorStatus.asByte())
                cpu.registers.stackPointer--
                cpu.processorStatus.breakCommand = false
            }
            6 -> pointerLow = read(0xFFFE).toUnsignedInt()
            7 -> {
                pointerHigh = read(0xFFFF).toUnsignedInt()
                cpu.registers.programCounter = (pointerLow or (pointerHigh shl 8)).toSignedShort()
                cpu.processorStatus.interruptDisable = true
                complete = true
            }
        }
    }

    private fun applyRead(current: AddressedOpcode, value: Byte) {
        (current as? ReadOpcode)?.applyRead(cpu, value)
            ?: error("${(current as Opcode).mnemonic} cannot consume a memory read")
    }

    private fun finishRmw(current: AddressedOpcode) {
        val rmw = current as? RmwOpcode
            ?: error("${(current as Opcode).mnemonic} is not read-modify-write")
        val result = rmw.transformedValue(cpu, originalValue)
        write(effectiveAddress, result)
        rmw.commitResult(cpu, originalValue, result)
        complete = true
    }

    private fun writeStore(current: AddressedOpcode, address: Int) {
        val store = current as? StoreOpcode
            ?: error("${(current as Opcode).mnemonic} is not a store")
        write(address, store.storeValue(cpu, address))
        complete = true
    }

    private fun fetchOperand(): Int {
        val result = read(cpu.registers.programCounter.toUnsignedInt()).toUnsignedInt()
        cpu.registers.programCounter++
        return result
    }

    private fun read(address: Int): Byte = cpu.memory[address and 0xFFFF]

    private fun write(address: Int, value: Byte) {
        cpu.memory[address and 0xFFFF] = value
    }

    private fun indexOf(addressing: Addressing): Int = when (addressing) {
        is ZeroPage -> if (addressing.x) cpu.registers.indexX.toUnsignedInt() else cpu.registers.indexY.toUnsignedInt()
        is Absolute -> if (addressing.x) cpu.registers.indexX.toUnsignedInt() else cpu.registers.indexY.toUnsignedInt()
        IndirectX -> cpu.registers.indexX.toUnsignedInt()
        IndirectY -> cpu.registers.indexY.toUnsignedInt()
        else -> 0
    }

    private fun modeOf(addressing: Addressing): Mode = when (addressing) {
        Immediate -> Mode.IMMEDIATE
        is ZeroPage -> if (addressing.isIndexed) Mode.ZERO_PAGE_INDEXED else Mode.ZERO_PAGE
        is Absolute -> if (addressing.isIndexed) Mode.ABSOLUTE_INDEXED else Mode.ABSOLUTE
        IndirectX -> Mode.INDIRECT_X
        IndirectY -> Mode.INDIRECT_Y
    }

    private fun AddressedOpcode.isStoreLike(): Boolean = this is StoreOpcode
    private fun AddressedOpcode.isRmwLike(): Boolean = this is RmwOpcode

    fun save(out: DataOutput) {
        out.writeByte(opcodeByte)
        out.writeShort(startPc)
        out.writeByte(initialSp)
        out.writeByte(phase)
        out.writeShort(operandLow)
        out.writeShort(operandHigh)
        out.writeShort(pointerLow)
        out.writeShort(pointerHigh)
        out.writeShort(effectiveAddress)
        out.writeShort(baseAddress)
        out.writeByte(originalValue.toInt())
        out.writeBoolean(branchTaken)
        out.writeShort(branchTarget)
        out.writeBoolean(branchCrossed)
    }

    fun load(input: DataInput, lookup: (Int) -> Opcode?) {
        opcodeByte = input.readUnsignedByte()
        startPc = input.readUnsignedShort()
        initialSp = input.readUnsignedByte()
        opcode = requireNotNull(lookup(opcodeByte)) { "Cannot restore unmapped opcode $${"%02X".format(opcodeByte)}" }
        phase = input.readUnsignedByte()
        operandLow = input.readUnsignedShort()
        operandHigh = input.readUnsignedShort()
        pointerLow = input.readUnsignedShort()
        pointerHigh = input.readUnsignedShort()
        effectiveAddress = input.readUnsignedShort()
        baseAddress = input.readUnsignedShort()
        originalValue = input.readByte()
        branchTaken = input.readBoolean()
        branchTarget = input.readUnsignedShort()
        branchCrossed = input.readBoolean()
        complete = false
    }

    /** Consume the obsolete v8 journal so the loader can report a precise error. */
    fun discardLegacy(input: DataInput) {
        input.readUnsignedByte() // opcode
        input.readUnsignedShort() // start PC
        input.readUnsignedByte() // initial SP
        input.readUnsignedByte() // phase
        input.readUnsignedShort() // operand low
        input.readUnsignedShort() // operand high
        input.readUnsignedShort() // pointer low
        input.readUnsignedShort() // pointer high
        input.readInt() // effective address
        input.readByte() // original value
        repeat(input.readInt()) {
            input.readUnsignedShort()
            repeat(input.readInt()) { input.readByte() }
        }
    }
}
