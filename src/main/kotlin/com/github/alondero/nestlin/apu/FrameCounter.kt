package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.SaveState
import com.github.alondero.nestlin.isBitSet
import java.io.DataInput
import java.io.DataOutput

class FrameCounter {
    enum class Mode {
        FOUR_STEP,
        FIVE_STEP
    }

    var mode: Mode = Mode.FOUR_STEP
    var irqInhibit: Boolean = false
    var step: Int = 0
    var cyclesSinceReset: Int = 0

    // Frame-counter step boundaries and wrap point differ between NTSC and PAL.
    var region: Region = Region.NTSC

    // Issue #297: a write to $4017 defers the sequencer reset by 3 or 4 CPU
    // cycles, depending on the APU's get/put phase at the time of the write.
    // Until [cyclesToReset] decrements to 0, the sequencer keeps ticking on
    // its *old* mode and step — no burst-fire of already-passed step clocks,
    // no mode change, and no immediate quarter/half clock.
    //
    // IRQ-inhibit acknowledgement is the one piece that does NOT defer: that
    // bit takes effect at the write (and is the caller's responsibility to
    // clear the frame interrupt flag), see [Apu.handleRegisterWrite].
    private var pendingMode: Mode? = null
    private var cyclesToReset: Int = 0

    private val fourStepSequence get() = region.apuFourStepSequence
    private val fiveStepSequence get() = region.apuFiveStepSequence

    /**
     * Read-only test seam: returns the mode queued by a deferred $4017 write
     * (null when no write is pending). Public so the regression test can
     * assert save/load round-tripping without having to peek the private
     * field via reflection.
     */
    fun pendingModeForTest(): Mode? = pendingMode

    /**
     * Read-only test seam: cycles remaining on the deferred $4017 reset.
     */
    fun cyclesToResetForTest(): Int = cyclesToReset

    data class Result(
        val quarterFrame: Boolean,
        val halfFrame: Boolean,
        val irq: Boolean,
        /**
         * True iff a deferred $4017 reset fired on this tick AND the newly
         * installed mode is 5-step (which clocks quarter+half on reset). The
         * caller MUST additionally clock quarter+half; for 4-step mode this
         * is always false (4-step reset is silent). The sequence-driven
         * [quarterFrame] / [halfFrame] are NOT set on the reset tick — the
         * sequencer was just restarted and [cyclesSinceReset] is well below
         * any step boundary.
         */
        val resetClock: Boolean = false
    )

    /**
     * Advance the frame sequencer by one CPU cycle and return the clocks it
     * emits.
     *
     * The comparison is made against [cyclesSinceReset] — the sequencer's
     * *own* cycle reference — NOT an absolute CPU-cycle counter. This is
     * what makes a `$4017` write restart the sequence cleanly: the reset
     * (whether immediate in older buggy revisions, or deferred via
     * [pendingMode] here) zeroes [cyclesSinceReset], so the next quarter
     * clock lands 7457 cycles *after the reset fired* instead of dumping
     * every already-passed step at once.
     */
    fun tick(): Result {
        var quarterFrameClock = false
        var halfFrameClock = false
        var irq = false
        var resetClock = false

        // Drain any pending $4017 reset BEFORE advancing the sequencer. The
        // reset takes effect on the tick that decrements cyclesToReset to 0:
        // we install the new mode, restart the step counter, and (for
        // 5-step) flag the caller to clock quarter+half immediately.
        if (pendingMode != null) {
            cyclesToReset--
            if (cyclesToReset <= 0) {
                val installedMode = pendingMode!!
                pendingMode = null
                cyclesToReset = 0
                mode = installedMode
                step = 0
                cyclesSinceReset = 0
                if (installedMode == Mode.FIVE_STEP) {
                    resetClock = true
                }
            }
        }

        cyclesSinceReset++

        val sequence = if (mode == Mode.FOUR_STEP) fourStepSequence else fiveStepSequence

        // Check each step in the sequence and fire if we've reached that cycle
        while (step < sequence.size && cyclesSinceReset >= sequence[step]) {
            when (step) {
                0 -> {
                    // Step 0: Quarter frame (envelope & linear counter)
                    quarterFrameClock = true
                }
                1 -> {
                    // Step 1: Half frame (length counter & sweep)
                    quarterFrameClock = true
                    halfFrameClock = true
                }
                2 -> {
                    // Step 2: Quarter frame
                    quarterFrameClock = true
                }
                3 -> {
                    // Step 3: In 4-step mode this is the end of frame (quarter +
                    // half + frame IRQ). In 5-step mode this slot is EMPTY — the
                    // quarter+half moves to step 4. Firing here in 5-step mode
                    // over-clocks envelopes/length by 25-50% (nesdev APU Frame
                    // Counter).
                    if (mode == Mode.FOUR_STEP) {
                        quarterFrameClock = true
                        halfFrameClock = true
                        if (!irqInhibit) {
                            irq = true
                        }
                    }
                }
                4 -> {
                    // Step 4: 5-step mode only - clock everything
                    quarterFrameClock = true
                    halfFrameClock = true
                }
            }
            step++
        }

        // Reset at end of sequence
        if (cyclesSinceReset >= maxCycles()) {
            step = 0
            cyclesSinceReset = 0
        }

        return Result(quarterFrameClock, halfFrameClock, irq, resetClock)
    }

    /**
     * Schedule a `$4017` write (issue #297). The mode change and sequencer
     * reset are deferred by 3 or 4 CPU cycles, depending on the APU's
     * get/put phase at the moment of the write. The IRQ-inhibit bit (bit 6)
     * takes effect **immediately** — per nesdev APU Frame Counter, this is
     * the one piece of the write that does NOT defer.
     *
     * @param value the byte written to $4017
     * @param cpuCycle the CPU-cycle count at the moment of the write; even
     *   cycles align with the APU's "get" phase (3-cycle delay), odd cycles
     *   with the "put" phase (4-cycle delay), per nesdev APU Frame Counter.
     * @return true if the pending mode is 5-step — the caller uses this
     *   signal to decide whether to clock quarter+half when the deferred
     *   reset fires (carried in [Result.resetClock]).
     */
    fun write4017(value: Byte, cpuCycle: Int): Boolean {
        val newMode = if (value.isBitSet(7)) Mode.FIVE_STEP else Mode.FOUR_STEP
        // Issue #297: IRQ-inhibit takes effect immediately (nesdev APU Frame
        // Counter). Only the mode change + sequencer reset are deferred.
        irqInhibit = value.isBitSet(6)
        pendingMode = newMode
        cyclesToReset = if (cpuCycle % 2 == 0) 3 else 4
        return newMode == Mode.FIVE_STEP
    }

    /**
     * Backwards-compatible overload used by the original `write4017(value)`
     * test sites. Treats the write as landing on the APU "get" phase
     * (CPU cycle 0), giving the shorter 3-cycle delay. Production callers
     * should always pass the real CPU cycle via [write4017] above.
     */
    fun write4017(value: Byte): Boolean = write4017(value, cpuCycle = 0)

    fun reset() {
        step = 0
        cyclesSinceReset = 0
        pendingMode = null
        cyclesToReset = 0
    }

    fun maxCycles(): Int = if (mode == Mode.FOUR_STEP) region.apuFourStepMaxCycles else region.apuFiveStepMaxCycles

    fun saveState(out: DataOutput, version: Int = SaveState.VERSION) {
        out.writeInt(mode.ordinal)
        out.writeBoolean(irqInhibit)
        out.writeInt(step)
        out.writeInt(cyclesSinceReset)
        // Issue #297: persist the pending write so a savestate taken during
        // the 3/4-cycle delay still produces a correct reset on load. The
        // pending block is only written for v11+ saves; v4–v10 saves carry
        // exactly the same 4 fields they always did, so a v10 save produced
        // by older code round-trips through the v11 reader without an offset
        // mismatch (and the v11 writer can synthesise a v10-format byte
        // stream when `version` is passed in below SaveState.VERSION 11).
        if (version >= 11) {
            out.writeBoolean(pendingMode != null)
            if (pendingMode != null) {
                out.writeInt(pendingMode!!.ordinal)
                out.writeInt(cyclesToReset)
            }
        }
    }

    fun loadState(input: DataInput, version: Int = SaveState.VERSION) {
        mode = Mode.entries[input.readInt()]
        irqInhibit = input.readBoolean()
        step = input.readInt()
        cyclesSinceReset = input.readInt()
        if (version >= 11) {
            val hasPending = input.readBoolean()
            if (hasPending) {
                pendingMode = Mode.entries[input.readInt()]
                cyclesToReset = input.readInt()
            } else {
                pendingMode = null
                cyclesToReset = 0
            }
        } else {
            // Pre-v11 saves never carried a pending write; if a $4017 reset
            // was in flight at the moment of capture, the old format
            // implicitly dropped it on load — match that behaviour for
            // backwards compatibility.
            pendingMode = null
            cyclesToReset = 0
        }
    }
}