package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.*
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.log.Logger
import java.io.File

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
    // TODO: Development-only feature - Remove undocumented opcode logging once emulator stability is proven
    // This allows us to identify missing opcodes without crashing, useful for game compatibility debugging
    private val undocumentedOpcodes = mutableSetOf<Int>()
    private val UNDOCUMENTED_LOG_FILE = "undocumented_opcodes.txt"

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

            if (checkAndHandleIrq()) {
                workCyclesLeft--
                return
            }

            val initialPC = registers.programCounter
            val opcodeVal = readByteAtPC().toUnsignedInt()
            opcodes[opcodeVal]?.also {
                logger?.cpuTick(initialPC, opcodeVal, this)
                it.op(this)
            } ?: run {
                // For test ROMs, throw exception to maintain test compatibility
                // For regular games, log and treat as 2-cycle NOP
                // TODO: Development-only feature - Remove this fallback once opcode coverage is complete
                if (currentGame?.isTestRom() == true) {
                    throw UnhandledOpcodeException(opcodeVal)
                } else {
                    logUndocumentedOpcode(opcodeVal, initialPC)
                    workCyclesLeft = 2
                }
            }
        }

        if (workCyclesLeft > 0) workCyclesLeft--
    }

    private fun logUndocumentedOpcode(opcodeVal: Int, pc: Short) {
        if (undocumentedOpcodes.add(opcodeVal)) {
            // First time seeing this opcode, log it
            val logEntry = "PC: ${"%04X".format(pc.toUnsignedInt())} - Undocumented opcode: 0x${"%02X".format(opcodeVal)} (treating as NOP)\n"
            File(UNDOCUMENTED_LOG_FILE).appendText(logEntry)
        }
    }

    fun dumpUndocumentedOpcodes() {
        if (undocumentedOpcodes.isNotEmpty()) {
            val summary = "Found ${undocumentedOpcodes.size} unique undocumented opcodes: " +
                    undocumentedOpcodes.sorted().joinToString(", ") { "0x${"%02X".format(it)}" }
            File(UNDOCUMENTED_LOG_FILE).appendText("\n$summary\n")
        }
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

    private fun checkAndHandleIrq(): Boolean {
        if (processorStatus.interruptDisable) return false
        if (memory.apu?.isIrqPending() != true) return false

        // Push PC (high byte first, then low byte)
        val pc = registers.programCounter.toUnsignedInt()
        push((pc shr 8).toSignedByte())
        push((pc and 0xFF).toSignedByte())

        // Push processor status (with B flag clear for interrupts)
        val statusByte = processorStatus.asByte().toUnsignedInt()
        val statusForInterrupt = (statusByte and 0xEF).toSignedByte()
        push(statusForInterrupt)

        // Set interrupt disable flag
        processorStatus.interruptDisable = true

        // Load PC from IRQ/BRK vector at $FFFE-$FFFF
        registers.programCounter = memory[0xFFFE, 0xFFFF]

        // IRQ takes 7 cycles
        workCyclesLeft = 7

        return true
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
