package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.*
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.log.Logger
import java.io.DataInput
import java.io.DataOutput
import java.io.File

class Cpu(var memory: Memory)
{
    var currentGame: GamePak? = null
    var workCyclesLeft = 0
    // The 6502 services an NMI with ~1 instruction of latency: when the NMI line is
    // asserted (PPU vblank) the CPU finishes its current instruction (and often issues
    // one more fetch) before vectoring. We model that by arming the NMI on the first
    // instruction boundary it is pending and only dispatching on the next one. This is
    // what lets a "poll $2002 for vblank" loop win the race against an enabled NMI on
    // real hardware — the in-flight poll reads the just-set flag, and that read clears
    // the latch, suppressing the NMI for that frame. Camerica/Codemasters titles (Big
    // Nose, Micro Machines) hang without it. See BigNoseHangTest.
    var nmiArmed = false
    var pageBoundaryFlag = false
    var registers = Registers()
    var processorStatus = ProcessorStatus()
    var idle = false
    private var logger: Logger? = null
    private val opcodes = Opcodes()

    fun getCurrentPc(): Short = registers.programCounter
    // TODO: Development-only feature - Remove undocumented opcode logging once emulator stability is proven
    // This allows us to identify missing opcodes without crashing, useful for game compatibility debugging
    private val undocumentedOpcodes = mutableSetOf<Int>()
    // Buffered log lines; flushed in one write at shutdown. Avoids per-cycle
    // disk I/O on the hot tick() path. See issue #29.
    private val undocumentedLogBuffer = mutableListOf<String>()
    private val UNDOCUMENTED_LOG_FILE = "undocumented_opcodes.txt"

    fun reset() {
        memory.clear()
        processorStatus.reset()
        registers.reset()
        workCyclesLeft = 0
        nmiArmed = false
        currentGame?.let {
            memory.readCartridge(it)
            registers.initialise(memory)
            if (it.isTestRom()) registers.activateAutomationMode()
        }
    }

    fun enableLogging() {
        logger = Logger()
    }

    private var instructionCount = 0
    private var traceAfterVBlank = false
    private var instructionTrace: MutableList<Pair<Int, Int>>? = null  // (PC, opcode) pairs
    private var maxTraceInstructions = 0

    fun getInstructionCount() = instructionCount

    /**
     * Enable instruction tracing for debugging.
     * @param maxInstructions Stop tracing after this many instructions (0 = unlimited)
     * @return List that will be populated with (pc, opcode) pairs
     */
    fun enableInstructionTrace(maxInstructions: Int = 0): MutableList<Pair<Int, Int>> {
        instructionTrace = mutableListOf()
        maxTraceInstructions = maxInstructions
        return instructionTrace!!
    }

    fun disableInstructionTrace() {
        instructionTrace = null
        maxTraceInstructions = 0
    }

    fun tick() {
        // Clock any CPU-cycle-driven mapper IRQ counter (e.g. FME-7) exactly once
        // per CPU cycle, before instruction/interrupt processing for this cycle.
        memory.mapper?.tickCpuCycle()

        if (readyForNextInstruction()) {
            // Check for NMI interrupt before executing next instruction
            if (checkAndHandleNmi()) {
                // An interrupt redirects the PC, breaking any spin loop the CPU
                // was parked in.
                idle = false
                // NMI was handled, skip regular instruction execution
                workCyclesLeft--
                return
            }

            if (checkAndHandleIrq()) {
                idle = false
                workCyclesLeft--
                return
            }

            // The CPU has branched/jumped to its own address — a spin loop that
            // can only be broken by an interrupt (handled above). Re-decoding the
            // same instruction every cycle just burns the host CPU, so park here.
            // workCyclesLeft stays at 0 so the interrupt checks keep running each
            // tick, and the PPU/APU keep advancing in the main loop to deliver one.
            if (idle) return

            val initialPC = registers.programCounter
            val opcodeVal = readByteAtPC().toUnsignedInt()
            if (traceAfterVBlank && instructionCount < 50) {
                println("[CPU] PC=$${String.format("%04X", initialPC.toInt())}, opcode=$${String.format("%02X", opcodeVal)}")
            }
            instructionCount++

            // Record instruction trace if enabled
            instructionTrace?.let { trace ->
                if (maxTraceInstructions <= 0 || trace.size < maxTraceInstructions) {
                    trace.add(Pair(initialPC.toUnsignedInt(), opcodeVal))
                }
            }

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
            // First time seeing this opcode; buffer the entry for a single bulk
            // write at shutdown. Avoids per-cycle disk I/O on the hot tick() path.
            // See issue #29.
            val logEntry = "PC: ${"%04X".format(pc.toUnsignedInt())} - Undocumented opcode: 0x${"%02X".format(opcodeVal)} (treating as NOP)\n"
            undocumentedLogBuffer.add(logEntry)
        }
    }

    fun dumpUndocumentedOpcodes(file: File = File(UNDOCUMENTED_LOG_FILE)) {
        if (undocumentedOpcodes.isEmpty()) return
        val perOpcodeText = undocumentedLogBuffer.joinToString("")
        val summary = "\nFound ${undocumentedOpcodes.size} unique undocumented opcodes: " +
                undocumentedOpcodes.sorted().joinToString(", ") { "0x${"%02X".format(it)}" }
        file.writeText(perOpcodeText + summary + "\n")
    }

    private fun checkAndHandleNmi(): Boolean {
        // NMI is edge-triggered: check if NMI occurred and NMI generation is enabled.
        val nmiPending = memory.ppuAddressedMemory.nmiOccurred && memory.ppuAddressedMemory.controller.generateNmi()
        if (!nmiPending) {
            // No (longer any) pending NMI. If it was armed, the latch was cleared within
            // the 1-instruction latency window — e.g. the program read $2002 (a vblank
            // poll). That correctly suppresses the NMI for this frame.
            nmiArmed = false
            return false
        }
        if (!nmiArmed && !idle) {
            // First boundary at which the NMI is pending: arm it and let one more
            // instruction execute before vectoring (the 6502's NMI latency). When the
            // CPU is parked in a spin loop (idle) there is no in-flight instruction to
            // finish, so skip the latency and service immediately — that is what breaks
            // the spin, and there is no $2002 poll to race against while parked.
            nmiArmed = true
            return false
        }
        // Latency elapsed — service the NMI now.
        nmiArmed = false
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

    private fun checkAndHandleIrq(): Boolean {
        // NMI has priority over IRQ. While an NMI is armed (pending but waiting out its
        // 1-instruction latency) it owns the next interrupt-service slot, so an IRQ must
        // not slip in ahead of it — otherwise the armed boundary would vector to the IRQ
        // handler first, inverting hardware priority. The IRQ stays pending and is taken
        // after the NMI.
        if (nmiArmed) return false
        if (processorStatus.interruptDisable) return false
        if (memory.apu?.isIrqPending() != true && memory.mapper?.isIrqPending() != true) return false

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

        // Acknowledge IRQ from mapper (APU IRQ is cleared elsewhere by the APU)
        memory.mapper?.acknowledgeIrq()

        return true
    }

    fun saveState(out: DataOutput) {
        out.writeByte(registers.stackPointer.toInt())
        out.writeByte(registers.accumulator.toInt())
        out.writeByte(registers.indexX.toInt())
        out.writeByte(registers.indexY.toInt())
        out.writeShort(registers.programCounter.toInt())
        out.writeByte(processorStatus.asByte().toInt())
        // Workaround: ProcessorStatus.toFlags doesn't preserve breakCommand, so save explicitly.
        out.writeBoolean(processorStatus.breakCommand)
        out.writeInt(workCyclesLeft)
        out.writeBoolean(pageBoundaryFlag)
        out.writeBoolean(idle)
        out.writeBoolean(nmiArmed)
        // Reserved: the old `Interrupt` enum (IRQ_BRK/NMI/RESET) was removed in issue #24.
        // The 4-byte slot stays in the format so save files made by older builds still load.
        out.writeInt(0)
    }

    fun loadState(input: DataInput) {
        registers.stackPointer = input.readByte()
        registers.accumulator = input.readByte()
        registers.indexX = input.readByte()
        registers.indexY = input.readByte()
        registers.programCounter = input.readShort()
        processorStatus.toFlags(input.readByte())
        processorStatus.breakCommand = input.readBoolean()
        workCyclesLeft = input.readInt()
        pageBoundaryFlag = input.readBoolean()
        idle = input.readBoolean()
        nmiArmed = input.readBoolean()
        // Reserved slot — see saveState.
        input.readInt()
    }

    fun push(value: Byte) { memory[0x100 + ((registers.stackPointer--).toUnsignedInt())] = value }
    fun pop() = memory[(0x100 + (++registers.stackPointer).toUnsignedInt())]

    fun readByteAtPC() = memory[registers.programCounter++.toUnsignedInt()]
    fun readShortAtPC() = memory[registers.programCounter++.toUnsignedInt(), registers.programCounter++.toUnsignedInt()]
    fun hasCrossedPageBoundary(previousCounter: Short, programCounter: Short) = (previousCounter.toUnsignedInt() and 0xFF00) != (programCounter.toUnsignedInt() and 0xFF00)

    private fun readyForNextInstruction() = workCyclesLeft <= 0
}

class Registers(
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

class ProcessorStatus(
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
