package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.*
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.log.Logger

class Cpu(var memory: Memory)
{
    var currentGame: GamePak? = null
    var interrupt: Interrupt? = null
    var workCyclesLeft = 0
    var pageBoundaryFlag = false
    var registers = Registers()
    var processorStatus = ProcessorStatus()
    var idle = false
    private var logger: Logger? = null
    private val opcodes = Opcodes()

    fun reset() {
        memory.clear()
        processorStatus.reset()
        registers.reset()
        workCyclesLeft = 0
        currentGame?.let {
            memory.readCartridge(it)
            registers.initialise(memory)
            if (it.isTestRom()) registers.activateAutomationMode()
        }
    }

    fun enableLogging() {
        logger = Logger()
    }

    fun tick() {
        if (readyForNextInstruction()) {
            // Check for NMI interrupt before executing next instruction
            if (checkAndHandleNmi()) {
                // NMI was handled, skip regular instruction execution
                workCyclesLeft--
                return
            }

            val initialPC = registers.programCounter
            val opcodeVal = readByteAtPC().toUnsignedInt()
            opcodes[opcodeVal]?.also {
                logger?.cpuTick(initialPC, opcodeVal, this)
                it.op(this)
            } ?: throw UnhandledOpcodeException(opcodeVal)
        }

        if (workCyclesLeft > 0) workCyclesLeft--
    }

    private fun checkAndHandleNmi(): Boolean {
        // NMI is edge-triggered: check if NMI occurred and NMI generation is enabled
        if (memory.ppuAddressedMemory.nmiOccurred && memory.ppuAddressedMemory.controller.generateNmi()) {
            // Clear the NMI flag (edge-triggered, not level-triggered)
            memory.ppuAddressedMemory.nmiOccurred = false

            // Push PC (high byte first, then low byte)
            val pc = registers.programCounter.toUnsignedInt()
            push((pc shr 8).toSignedByte())
            push((pc and 0xFF).toSignedByte())

            // Push processor status (with B flag clear for interrupts)
            val statusByte = processorStatus.asByte().toUnsignedInt()
            // Clear bit 4 (B flag) for interrupt context
            val statusForInterrupt = (statusByte and 0xEF).toSignedByte()
            push(statusForInterrupt)

            // Set interrupt disable flag
            processorStatus.interruptDisable = true

            // Load PC from NMI vector at $FFFA-$FFFB
            registers.programCounter = memory[0xFFFA, 0xFFFB]

            // NMI takes 7 cycles
            workCyclesLeft = 7

            return true
        }

        return false
    }

    fun push(value: Byte) { memory[0x100 + ((registers.stackPointer--).toUnsignedInt())] = value }
    fun pop() = memory[(0x100 + (++registers.stackPointer).toUnsignedInt())]

    fun readByteAtPC() = memory[registers.programCounter++.toUnsignedInt()]
    fun readShortAtPC() = memory[registers.programCounter++.toUnsignedInt(), registers.programCounter++.toUnsignedInt()]
    fun hasCrossedPageBoundary(previousCounter: Short, programCounter: Short) = (previousCounter.toUnsignedInt() and 0xFF00) != (programCounter.toUnsignedInt() and 0xFF00)

    private fun readyForNextInstruction() = workCyclesLeft <= 0
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

    fun initialise(memory: Memory) {
        programCounter = memory.resetVector()
    }

    fun activateAutomationMode() {
        programCounter = 0xc000.toSignedShort()
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

    fun asByte() =
        ((if (negative) (1 shl 7) else 0) or
         (if (overflow) (1 shl 6) else 0) or
         (1 shl 5) or // Special logic needed for the B flag...
         (0 shl 4) or
         (if (decimalMode) (1 shl 3) else 0) or
         (if (interruptDisable) (1 shl 2) else 0) or
         (if (zero) (1 shl 1) else 0) or
         (if (carry) 1 else 0)).toSignedByte()

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

