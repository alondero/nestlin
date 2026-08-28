package com.github.alondero.nestlin.cpu

import com.github.alondero.nestlin.*
import com.github.alondero.nestlin.cpu.opcode.OpcodesRefactor
import com.github.alondero.nestlin.cpu.opcode.Kil
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
        // Wire this CPU as the stall source so Memory's $4014 handler can start
        // a resumable DMA transfer through the StallSource interface.
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
    // Cumulative CPU cycles elapsed since the last reset. Incremented at the
    // END of every tick (issue #17 / GoldenLogTest cycle comparison). The
    // Logger multiplies by 3 for nestest.log's PPU-cycle format; other
    // consumers (frame counters, audio scheduling) can read the raw CPU count.
    private var _cycleCount = 0
    private val _registers = Registers()
    private val _processorStatus = ProcessorStatus()
    private var _idle = false
    private var logger: Logger? = null
    private val opcodes = OpcodesRefactor
    private var activeInstruction: MicrocodedInstruction? = null
    private var activeInterrupt: MicrocodedInterrupt? = null
    private data class OamDmaState(
        val page: Int,
        var dummyCycles: Int,
        var index: Int = 0,
        var reading: Boolean = true,
        var buffer: Byte = 0,
    )
    private var oamDma: OamDmaState? = null
    private var genericStallCycles = 0

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

    /**
     * Cumulative CPU cycles elapsed since the last [reset] / [softReset].
     * Incremented at the end of every [tick]. The trace [Logger] multiplies
     * this by 3 to produce nestest.log's PPU-cycle column; the raw CPU count
     * is exposed here for any consumer that wants CPU-cycle timing. Version-8
     * save states persist it because its parity controls the alignment cycle
     * of a future OAM DMA. See issue #17 and #298.
     */
    val cycleCount: Int
        get() = _cycleCount

    internal val executionInFlight: Boolean
        get() = activeInstruction != null || activeInterrupt != null ||
            oamDma != null || genericStallCycles > 0

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
        resetCpuState()
    }

    /**
     * Soft reset — equivalent to pressing the NES RESET button (issue #125). The CPU
     * is redirected to its RESET vector and registers are zeroed, but **internal RAM
     * ($0000-$07FF) and PPU registers are preserved** — the RESET line on real hardware
     * does NOT power-cycle the work RAM or the PPU's latched state.
     *
     * Contrast with [reset] (the power-cycle / "hard reset" path), which calls
     * [com.github.alondero.nestlin.Memory.clear] to wipe RAM and reset the PPU.
     */
    fun softReset() {
        resetCpuState()
    }

    /**
     * Reset the CPU's own state (registers, processor status, cycle counters, PC from
     * the RESET vector). Shared by [reset] (full power-cycle, which clears RAM first)
     * and [softReset] (RESET button, which preserves RAM).
     */
    private fun resetCpuState() {
        _processorStatus.reset()
        _registers.reset()
        _workCyclesLeft = 0
        _nmiCount = 0
        _irqCount = 0
        _cycleCount = 0
        activeInstruction = null
        activeInterrupt = null
        oamDma = null
        genericStallCycles = 0
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

        try {
            oamDma?.let { dma ->
                when {
                    dma.dummyCycles > 0 -> {
                        memory[registers.programCounter.toUnsignedInt()]
                        dma.dummyCycles--
                    }
                    dma.reading -> {
                        dma.buffer = memory[(dma.page shl 8) or dma.index]
                        dma.reading = false
                    }
                    else -> {
                        memory[0x2004] = dma.buffer
                        dma.reading = true
                        dma.index++
                    }
                }
                if (_workCyclesLeft > 0) _workCyclesLeft--
                if (dma.index == 256) {
                    oamDma = null
                    _workCyclesLeft = 0
                }
                return
            }

            if (genericStallCycles > 0) {
                // RDY holds the current read cycle; the same micro-step resumes
                // after the stall rather than being discarded or repeated.
                memory[registers.programCounter.toUnsignedInt()]
                genericStallCycles--
                _workCyclesLeft = genericStallCycles
                return
            }

            activeInterrupt?.let { interrupt ->
                interrupt.step()
                if (_workCyclesLeft > 0) _workCyclesLeft--
                if (interrupt.isComplete) {
                    activeInterrupt = null
                    _workCyclesLeft = 0
                }
                return
            }

            activeInstruction?.let { instruction ->
                instruction.step()
                if (_workCyclesLeft > 0) _workCyclesLeft--
                if (instruction.isComplete) {
                    activeInstruction = null
                    if (oamDma == null && genericStallCycles == 0) _workCyclesLeft = 0
                }
                return
            }

            if (readyForNextInstruction()) {
                // Ask the controller what (if anything) to dispatch RIGHT NOW.
                // The controller owns the 1-instruction NMI latency and the NMI>IRQ
                // ordering — see InterruptController for the contract. Idle is
                // passed in so a parked CPU (spin loop) skips the latency, which is
                // what breaks the loop.
                when (interruptController.pendingInterrupt(idle, processorStatus.interruptDisable)) {
                    InterruptKind.NMI -> {
                        interruptController.acknowledge(InterruptKind.NMI)
                        activeInterrupt = MicrocodedInterrupt.start(this, InterruptKind.NMI)
                        idle = false
                        activeInterrupt!!.step()
                        workCyclesLeft = 6
                        return
                    }
                    InterruptKind.IRQ -> {
                        interruptController.acknowledge(InterruptKind.IRQ)
                        activeInterrupt = MicrocodedInterrupt.start(this, InterruptKind.IRQ)
                        idle = false
                        activeInterrupt!!.step()
                        workCyclesLeft = 6
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
                    // Reset pageBoundaryFlag before each opcode's addressing
                    // mode runs. The flag is write-only from the
                    // Addressing class's perspective: an indexed mode may
                    // set it to true if the effective address crosses a
                    // page; non-indexed modes leave it as-is. Without this
                    // reset, a +1 cycle bonus from a previous indexed
                    // instruction would leak into a subsequent
                    // non-indexed one (e.g. CMP #imm right after LDA
                    // ($zp),Y with page cross). Issue #17 / #172.
                    pageBoundaryFlag = false
                    logger?.cpuTick(initialPC, opcodeVal, this)
                    activeInstruction = MicrocodedInstruction.start(
                        this,
                        opcodeVal,
                        it,
                        initialPC.toUnsignedInt(),
                    )
                    workCyclesLeft = when {
                        it is Kil -> 0
                        it is com.github.alondero.nestlin.cpu.opcode.Branch && it.condition(this) -> {
                            val offset = memory.peek((initialPC.toUnsignedInt() + 1) and 0xFFFF).toInt()
                            val target = (initialPC.toUnsignedInt() + 2 + offset.toByte()) and 0xFFFF
                            val total = if (((initialPC.toUnsignedInt() + 2) and 0xFF00) != (target and 0xFF00)) 4 else 3
                            total - 1
                        }
                        else -> (it.cycles - 1).coerceAtLeast(0)
                    }
                } ?: run {
                    // For test ROMs, throw exception to maintain test compatibility
                    // For regular games, log and treat as 2-cycle NOP
                    // TODO: Development-only feature - Remove this fallback once opcode coverage is complete
                    if (currentGame?.isTestRom() == true) {
                        throw UnhandledOpcodeException(opcodeVal)
                    } else {
                        logUndocumentedOpcode(opcodeVal, initialPC)
                        genericStallCycles = 1
                        workCyclesLeft = 1
                    }
                }
        }

        } finally {
            // Bump the cumulative CPU cycle counter exactly once per tick.
            // The `try { ... } finally { ... }` ensures the increment runs
            // on every code path — including the early `return` after an
            // NMI/IRQ dispatch, and an uncaught `UnhandledOpcodeException`
            // from a test ROM. Without this, the cycle counter would stall
            // mid-frame and the trace would diverge from nestest.log.
            // Issue #17 / GoldenLogTest cycle comparison.
            _cycleCount++
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
     * StallSource implementation (issue #190). Called by [Memory] for an
     * explicit, non-DMA stall. OAM DMA uses [startOamDma] so the scheduler
     * can emit its alternating read/write bus cycles and preserve alignment.
     */
    override fun stallFor(cycles: Int) {
        genericStallCycles = cycles
        _workCyclesLeft = cycles
    }

    override fun startOamDma(page: Int) {
        val dummyCycles = 1 + (_cycleCount and 1)
        // Nestlin's PPU model (and the existing Akira regression contract)
        // starts every DMA at OAM[0], regardless of the last $2003 write.
        memory.ppuAddressedMemory.oamAddress = 0
        oamDma = OamDmaState(page and 0xFF, dummyCycles)
        _workCyclesLeft = dummyCycles + 512
    }

    /**
     * Save state — issue #190 removes the `_nmiArmed` field from the CPU
     * block (it now lives in [interruptController] as the controller's
     * own state). The save-state format is bumped to VERSION 8 in
     * [SaveState]; the new "interrupt controller" sub-block lives
     * between the CPU and RAM blocks and holds the controller's `nmiArmed`.
     *
     * The trailing 4-byte reserved int slot is the pre-existing reservation
     * for the removed `Interrupt` enum (issue #24) — unrelated to nmiArmed.
     * VERSION 3 savestates are NOT loadable: the VERSION check at
     * [SaveState.load] rejects mismatched versions before reaching this
     * readInt(), so we never need to consume a VERSION 3 byte stream.
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
        // Reserved 4-byte int slot — see kdoc above.
        out.writeInt(0)
        // Cycle parity determines the alignment delay for a future OAM DMA;
        // preserve the full counter so save/load also keeps logger timing.
        out.writeInt(_cycleCount)
        out.writeBoolean(activeInstruction != null)
        activeInstruction?.save(out)
        out.writeBoolean(activeInterrupt != null)
        activeInterrupt?.save(out)
        out.writeInt(genericStallCycles)
        out.writeBoolean(oamDma != null)
        oamDma?.let { dma ->
            out.writeByte(dma.page)
            out.writeByte(dma.dummyCycles)
            out.writeShort(dma.index)
            out.writeBoolean(dma.reading)
            out.writeByte(dma.buffer.toInt())
        }
    }

    fun loadState(input: DataInput, version: Int = SaveState.VERSION) {
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
        if (version >= 8) {
            _cycleCount = input.readInt()
            activeInstruction = if (input.readBoolean()) {
                MicrocodedInstruction.load(this, input) { opcodes[it] }
            } else null
            activeInterrupt = if (input.readBoolean()) MicrocodedInterrupt.load(this, input) else null
            genericStallCycles = input.readInt()
            oamDma = if (input.readBoolean()) {
                OamDmaState(
                    page = input.readUnsignedByte(),
                    dummyCycles = input.readUnsignedByte(),
                    index = input.readUnsignedShort(),
                    reading = input.readBoolean(),
                    buffer = input.readByte(),
                )
            } else null
        } else {
            _cycleCount = 0
            activeInstruction = null
            activeInterrupt = null
            genericStallCycles = _workCyclesLeft.coerceAtLeast(0)
            oamDma = null
        }
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

    /**
     * Serialise the flag bits into the pushed-status byte. Note the
     * "magical" bits: bit 5 is always 1 (the unused flag is hardwired on
     * the die), and bit 4 (the B/break flag) only exists in the *pushed*
     * byte — not as stored state. BRK and PHP force B=1; IRQ and NMI
     * force B=0. The caller sets [breakCommand] before calling and is
     * responsible for resetting it (BRK does so explicitly; PHP is a
     * separate follow-up — issue #9 is scoped to BRK).
     */
    fun asByte() =
        ((if (negative) (1 shl 7) else 0) or
         (if (overflow) (1 shl 6) else 0) or
         (1 shl 5) or
         (if (breakCommand) (1 shl 4) else 0) or
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
