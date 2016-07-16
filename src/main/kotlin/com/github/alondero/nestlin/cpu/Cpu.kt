package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.*
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.log.Logger

private const val TEST_ROM_CRC = 0x9e179d92

class Cpu(
        var memory: Memory,
        var opcodes: Opcodes = Opcodes()
) {
    var currentGame: GamePak? = null
    var interrupt: Interrupt? = null
    var workCyclesLeft: Int = 0
    var pageBoundaryFlag: Boolean = false
    var registers: Registers = Registers()
    var processorStatus: ProcessorStatus = ProcessorStatus()
    var idle = false
    private var logger: Logger? = null

    fun reset() {
        memory.clear()
        var isTestRom = false
        currentGame?.let {
            memory.readCartridge(it)
            isTestRom = it.crc.value == TEST_ROM_CRC
        }

        processorStatus.reset()
        registers.reset()
        workCyclesLeft = 0

        if (isTestRom) {
            // Starts test rom in automation mode
            registers.programCounter = 0xc000.toSignedShort()
        } else {
            registers.programCounter = resetVector()
        }
    }

    fun enableLogging() {
        logger = Logger()
    }

    fun tick() {
        if (readyForNextInstruction()) {
            val initialPC = registers.programCounter
            val opcodeVal = readByteAtPC().toUnsignedInt()
            val opcode = opcodes[opcodeVal] ?: throw UnhandledOpcodeException(opcodeVal)

            opcode?.apply {
                logger?.cpuTick(initialPC, opcodeVal, this@Cpu)
                op(this@Cpu)
            }
        }

        if (workCyclesLeft > 0) workCyclesLeft--
    }

    fun push(value: Byte) {
        memory[0x100 + (registers.stackPointer.toUnsignedInt())] = value
        registers.stackPointer--
    }

    fun pop(): Byte {
        registers.stackPointer++
        return memory[(0x100 + registers.stackPointer.toUnsignedInt())]
    }

    fun readByteAtPC() = memory[registers.programCounter++.toUnsignedInt()]
    fun readShortAtPC() = memory[registers.programCounter++.toUnsignedInt(), registers.programCounter++.toUnsignedInt()]
    fun hasCrossedPageBoundary(previousCounter: Short, programCounter: Short) = (previousCounter.toUnsignedInt() and 0xFF00) != (programCounter.toUnsignedInt() and 0xFF00)

    private fun readyForNextInstruction() = workCyclesLeft <= 0
    private fun resetVector() = memory[0xFFFC, 0xFFFD]
}

enum class Interrupt {
    IRQ_BRK,
    NMI,
    RESET
}

data class Registers(
        var stackPointer: Byte = 0,
        var accumulator: Byte = 0,
        var indexX: Byte = 0,
        var indexY: Byte = 0,
        var programCounter: Short = 0
) {
    fun reset() {
        stackPointer = -3 // Skips decrementing three times from init
        accumulator = 0
        indexX = 0
        indexY = 0
    }
}

data class ProcessorStatus(
        var carry: Boolean = false,
        var zero: Boolean = true,
        var interruptDisable: Boolean = true,
        var decimalMode: Boolean = false,
        var breakCommand: Boolean = false,
        var overflow: Boolean = false,
        var negative: Boolean = false
) {
    fun reset() {
        carry = false
        zero = false
        interruptDisable = true
        decimalMode = false
        breakCommand = false
        overflow = false
        negative = false
    }

    fun asByte(): Byte {
        return ((if (negative) (1 shl 7) else 0) or
                (if (overflow) (1 shl 6) else 0) or
                (1 shl 5) or // Special logic needed for the B flag...
                (0 shl 4) or
                (if (decimalMode) (1 shl 3) else 0) or
                (if (interruptDisable) (1 shl 2) else 0) or
                (if (zero) (1 shl 1) else 0) or
                (if (carry) 1 else 0)).toSignedByte()
    }

    fun toFlags(status: Byte) {
        carry = status.isBitSet(0)
        zero = status.isBitSet(1)
        interruptDisable = status.isBitSet(2)
        decimalMode = status.isBitSet(3)
        overflow = status.isBitSet(6)
        negative = status.isBitSet(7)
    }

    fun resolveZeroAndNegativeFlags(result: Byte) {
        zero = (result.toUnsignedInt() == 0)
        negative = (result.toUnsignedInt() and 0xFF).toSignedByte().isBitSet(7)
    }
}

