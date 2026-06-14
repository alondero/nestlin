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
    // --- Private backing state ---------------------------------------------------
    // The fields below are intentionally private (issue #23): the only way to read or
    // mutate them is through the public properties further down, which give us a
    // single, documentable access point. The properties are the *controlled surface*
    // for opcode implementations, the trace Logger, and the test harness. Adding
    // validation, logging, or swapping the backing storage is now a one-place change
    // rather than a sweep across Opcodes.kt and every test that pokes at the CPU.
    // The Registers/ProcessorStatus references are still returned by their getters —
    // callers can still mutate the individual register / flag fields — but they can
    // no longer replace the *reference* itself. (Truly sealing the inner state would
    // require read-only views on Registers/ProcessorStatus, which is a larger,
    // separate refactor.)
    private var _workCyclesLeft = 0
    // The 6502 services an NMI with ~1 instruction of latency: when the NMI line is
    // asserted (PPU vblank) the CPU finishes its current instruction (and often issues
    // one more fetch) before vectoring. We model that by arming the NMI on the first
    // instruction boundary it is pending and only dispatching on the next one. This is
    // what lets a "poll $2002 for vblank" loop win the race against an enabled NMI on
    // real hardware — the in-flight poll reads the just-set flag, and that read clears
    // the latch, suppressing the NMI for that frame. Camerica/Codemasters titles (Big
    // Nose, Micro Machines) hang without it. See BigNoseHangTest.
    private var _nmiArmed = false
    // Diagnostic counters: incremented exactly once per *dispatched* interrupt (the
    // point the vector is taken, not when armed/pending). Deliberately NOT part of
    // save-state serialisation — these are debugging telemetry, not emulation state.
    // The compare/DivergenceLocalizer harness reads per-frame deltas of these to
    // compare NMI/IRQ-per-frame against Mesen2's event counts.
    private var _nmiCount = 0
    private var _irqCount = 0
    private var _pageBoundaryFlag = false
    private val _registers = Registers()
    private val _processorStatus = ProcessorStatus()
    private var _idle = false
    private var logger: Logger? = null
    private val opcodes = Opcodes()

    // --- Controlled-access properties (issue #23) --------------------------------
    // Backing fields are private; these properties are the entire public surface for
    // opcode-state read/write. Adding invariant checks, trace logging, or swapping
    // the storage (e.g. to packed bits) is a one-line change here.
    var workCyclesLeft: Int
        get() = _workCyclesLeft
        set(value) { _workCyclesLeft = value }

    var nmiArmed: Boolean
        get() = _nmiArmed
        set(value) { _nmiArmed = value }

    var pageBoundaryFlag: Boolean
        get() = _pageBoundaryFlag
        set(value) { _pageBoundaryFlag = value }

    var idle: Boolean
        get() = _idle
        set(value) { _idle = value }

    /** The live [Registers] instance. Mutation goes through the field setters on the returned object. */
    val registers: Registers
        get() = _registers

    /** The live [ProcessorStatus] instance. Flag writes go through the field setters on the returned object. */
    val processorStatus: ProcessorStatus
        get() = _processorStatus

    /** Diagnostic: total NMIs dispatched since [reset]. See field doc above. */
    val nmiCount: Int
        get() = _nmiCount

    /** Diagnostic: total IRQs dispatched since [reset]. See field doc above. */
    val irqCount: Int
        get() = _irqCount

    /** Internal mutator for the NMI diagnostic counter. Not exposed via a public setter. */
    internal fun incrementNmiCount() { _nmiCount++ }

    /** Internal mutator for the IRQ diagnostic counter. Not exposed via a public setter. */
    internal fun incrementIrqCount() { _irqCount++ }

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
        _processorStatus.reset()
        _registers.reset()
        _workCyclesLeft = 0
        _nmiArmed = false
        _nmiCount = 0
        _irqCount = 0
        currentGame?.let {
            memory.readCartridge(it)
            _registers.initialise(memory)
            if (it.isTestRom()) _registers.activateAutomationMode()
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
            _nmiArmed = false
            return false
        }
        if (!_nmiArmed && !_idle) {
            // First boundary at which the NMI is pending: arm it and let one more
            // instruction execute before vectoring (the 6502's NMI latency). When the
            // CPU is parked in a spin loop (idle) there is no in-flight instruction to
            // finish, so skip the latency and service immediately — that is what breaks
            // the spin, and there is no $2002 poll to race against while parked.
            _nmiArmed = true
            return false
        }
        // Latency elapsed — service the NMI now.
        _nmiArmed = false
        // Clear the NMI flag (edge-triggered, not level-triggered)
        memory.ppuAddressedMemory.nmiOccurred = false

        // Push PC (high byte first, then low byte)
        val pc = _registers.programCounter.toUnsignedInt()
        push((pc shr 8).toSignedByte())
        push((pc and 0xFF).toSignedByte())

        // Push processor status (with B flag clear for interrupts)
        val statusByte = _processorStatus.asByte().toUnsignedInt()
        // Clear bit 4 (B flag) for interrupt context
        val statusForInterrupt = (statusByte and 0xEF).toSignedByte()
        push(statusForInterrupt)

        // Set interrupt disable flag
        _processorStatus.interruptDisable = true

        // Load PC from NMI vector at $FFFA-$FFFB
        _registers.programCounter = memory[0xFFFA, 0xFFFB]

        // NMI takes 7 cycles
        _workCyclesLeft = 7

        incrementNmiCount()

        return true
    }

    private fun checkAndHandleIrq(): Boolean {
        // NMI has priority over IRQ. While an NMI is armed (pending but waiting out its
        // 1-instruction latency) it owns the next interrupt-service slot, so an IRQ must
        // not slip in ahead of it — otherwise the armed boundary would vector to the IRQ
        // handler first, inverting hardware priority. The IRQ stays pending and is taken
        // after the NMI.
        if (_nmiArmed) return false
        if (_processorStatus.interruptDisable) return false
        if (memory.apu?.isIrqPending() != true && memory.mapper?.isIrqPending() != true) return false

        // Push PC (high byte first, then low byte)
        val pc = _registers.programCounter.toUnsignedInt()
        push((pc shr 8).toSignedByte())
        push((pc and 0xFF).toSignedByte())

        // Push processor status (with B flag clear for interrupts)
        val statusByte = _processorStatus.asByte().toUnsignedInt()
        val statusForInterrupt = (statusByte and 0xEF).toSignedByte()
        push(statusForInterrupt)

        // Set interrupt disable flag
        _processorStatus.interruptDisable = true

        // Load PC from IRQ/BRK vector at $FFFE-$FFFF
        _registers.programCounter = memory[0xFFFE, 0xFFFF]

        // IRQ takes 7 cycles
        _workCyclesLeft = 7

        // Acknowledge IRQ from mapper (APU IRQ is cleared elsewhere by the APU)
        memory.mapper?.acknowledgeIrq()

        incrementIrqCount()

        return true
    }

    fun saveState(out: DataOutput) {
        out.writeByte(_registers.stackPointer.toInt())
        out.writeByte(_registers.accumulator.toInt())
        out.writeByte(_registers.indexX.toInt())
        out.writeByte(_registers.indexY.toInt())
        out.writeShort(_registers.programCounter.toInt())
        out.writeByte(_processorStatus.asByte().toInt())
        // Workaround: ProcessorStatus.toFlags doesn't preserve breakCommand, so save explicitly.
        out.writeBoolean(_processorStatus.breakCommand)
        out.writeInt(_workCyclesLeft)
        out.writeBoolean(_pageBoundaryFlag)
        out.writeBoolean(_idle)
        out.writeBoolean(_nmiArmed)
        // Reserved: the old `Interrupt` enum (IRQ_BRK/NMI/RESET) was removed in issue #24.
        // The 4-byte slot stays in the format so save files made by older builds still load.
        out.writeInt(0)
    }

    fun loadState(input: DataInput) {
        _registers.stackPointer = input.readByte()
        _registers.accumulator = input.readByte()
        _registers.indexX = input.readByte()
        _registers.indexY = input.readByte()
        _registers.programCounter = input.readShort()
        _processorStatus.toFlags(input.readByte())
        _processorStatus.breakCommand = input.readBoolean()
        _workCyclesLeft = input.readInt()
        _pageBoundaryFlag = input.readBoolean()
        _idle = input.readBoolean()
        _nmiArmed = input.readBoolean()
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
