package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.*
import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.log.Logger
import java.io.DataInput
import java.io.DataOutput
import java.io.File

class Cpu(
    var memory: Memory,
    /**
     * The interrupt controller — the seam between producers (PPU/APU/mapper)
     * and the CPU consumer. Issue #190 / ADR-0003.
     *
     * Defaults to the production wiring built from [memory] (PpuAddressedMemory
     * as the NMI source; the current mapper and APU as IRQ sources). Tests
     * pass a `testutil.FakeInterruptController` to drive interrupt scenarios
     * without a real PPU.
     */
    val interruptController: InterruptController = defaultInterruptController(memory),
) : StallSource
{
    init {
        // Wire this CPU as the stall source so Memory's $4014 (OAM DMA) handler
        // can request a 513-cycle halt through the StallSource interface — no
        // longer reaches into `Cpu.workCyclesLeft` via a back-reference.
        memory.stallSource = this
    }

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
            // Ask the controller what (if anything) to dispatch RIGHT NOW.
            // The controller owns the 1-instruction NMI latency and the NMI>IRQ
            // ordering — see InterruptController for the contract. Idle is
            // passed in so a parked CPU (spin loop) skips the latency, which is
            // what breaks the loop.
            when (interruptController.pendingInterrupt(idle, processorStatus.interruptDisable)) {
                InterruptKind.NMI -> {
                    interruptController.acknowledge(InterruptKind.NMI)
                    dispatchInterrupt(InterruptKind.NMI, memory[0xFFFA, 0xFFFB])
                    // An interrupt redirects the PC, breaking any spin loop.
                    idle = false
                    workCyclesLeft--
                    return
                }
                InterruptKind.IRQ -> {
                    interruptController.acknowledge(InterruptKind.IRQ)
                    dispatchInterrupt(InterruptKind.IRQ, memory[0xFFFE, 0xFFFF])
                    idle = false
                    workCyclesLeft--
                    return
                }
                null -> {
                    // No interrupt pending — fall through to opcode dispatch
                    // (or stay parked if idle).
                }
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

    /**
     * Push PC + status, set the I-flag, load the vector, and set the 7-cycle
     * cost. Shared by NMI and IRQ dispatch (issue #190 / ADR-0003) — the only
     * differences are which vector is loaded and which diagnostic counter is
     * bumped, both of which are the caller's choice.
     */
    private fun dispatchInterrupt(kind: InterruptKind, vector: Short) {
        // Push PC (high byte first, then low byte)
        val pc = _registers.programCounter.toUnsignedInt()
        push((pc shr 8).toSignedByte())
        push((pc and 0xFF).toSignedByte())

        // Push processor status (with B flag clear for interrupts — that bit
        // distinguishes BRK from IRQ/NMI on the stack).
        val statusByte = _processorStatus.asByte().toUnsignedInt()
        val statusForInterrupt = (statusByte and 0xEF).toSignedByte()
        push(statusForInterrupt)

        // Set interrupt disable flag — the handler re-enables with RTI.
        _processorStatus.interruptDisable = true

        _registers.programCounter = vector
        _workCyclesLeft = 7

        when (kind) {
            InterruptKind.NMI -> incrementNmiCount()
            InterruptKind.IRQ -> incrementIrqCount()
        }
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

    /**
     * StallSource implementation (issue #190). Called by `Memory` when a
     * `$4014` (OAM DMA) write needs to halt the CPU for 513 cycles. The
     * scheduler in [tick] decrements `_workCyclesLeft` every tick while
     * it's positive, suspending instruction fetch — exactly as a real
     * CPU is stalled for the duration of an OAM DMA.
     */
    override fun stallFor(cycles: Int) {
        _workCyclesLeft = cycles
    }

    /**
     * Save state — issue #190 removes the `_nmiArmed` field from the CPU
     * block (it now lives in [interruptController] as the controller's
     * own state). The save-state format is bumped to VERSION 4 in
     * [SaveState]; the new "interrupt controller" sub-block lives
     * between the CPU and RAM blocks and holds the controller's `nmiArmed`.
     */
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
        // nmiArmed moved to interruptController.saveState in VERSION 4.
        // The reserved slot below remains for backward compatibility with
        // VERSION 3 savestates (the boolean was written here before).
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
        // nmiArmed moved to interruptController.loadState in VERSION 4.
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
