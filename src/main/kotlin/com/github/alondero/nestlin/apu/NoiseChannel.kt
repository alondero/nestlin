package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

class NoiseChannel {
    private val envelope = Envelope()
    private val lengthCounter = LengthCounter()

    var isEnabled: Boolean = false
    var modeFlag: Boolean = false
    var period: Int = 0
    var timerCounter: Int = 0
    var shiftRegister: Int = 1  // 15-bit LFSR (starts at 1)

    // Noise period lookup table (CPU cycles) — values differ between NTSC and PAL.
    var region: Region = Region.NTSC
    private val noisePeriodTable get() = region.noisePeriods

    fun write400C(value: Byte) {
        // --lc vvvv
        // l: Loop envelope / halt length counter
        // c: Constant volume
        // vvvv: Volume/envelope period
        val loopHaltFlag = value.isBitSet(5)
        lengthCounter.halt = loopHaltFlag
        envelope.loop = loopHaltFlag  // Critical: loop flag controls envelope looping
        envelope.constantVolume = value.isBitSet(4)
        envelope.volume = value.toUnsignedInt() and 0x0F
    }

    fun write400E(value: Byte) {
        // M--- pppp
        // M: Mode flag (0=15-bit, 1=6-bit)
        // pppp: Period index
        modeFlag = value.isBitSet(7)
        period = value.toUnsignedInt() and 0x0F
    }

    fun write400F(value: Byte) {
        // llll l---
        // lllll: Length counter load
        val lengthLoad = (value.toUnsignedInt() shr 3) and 0x1F

        if (isEnabled) {
            lengthCounter.loadCounter(lengthLoad)
        }

        // Reset timer and start envelope. The timer is clocked once per APU
        // cycle (every other CPU cycle, see Apu.tick), so we count in APU
        // cycles: each table entry is divided by 2 here. Combined with the
        // decrement-then-check-zero structure in clockTimer(), this yields
        // successive LFSR shifts separated by exactly the table's CPU-cycle
        // value (4, 8, 16, ...) — see GitHub issue #295.
        timerCounter = noisePeriodTable[period] / 2
        envelope.startFlag = true
    }

    fun clockTimer() {
        if (timerCounter > 0) {
            timerCounter--
        }

        // Shift on the SAME call that decrements to zero (not the call after).
        // If we deferred the shift by one cycle the spacing would become
        // `period + 2` CPU cycles, which is exactly the "divide the initial
        // value without checking down-counter terminal semantics" trap called
        // out in issue #295. Decreasing-then-checking keeps the steady-state
        // spacing at `period` CPU cycles.
        if (timerCounter == 0) {
            timerCounter = noisePeriodTable[period] / 2

            // Clock LFSR
            val feedback = if (modeFlag) {
                // Mode 1: bit 0 XOR bit 6
                (shiftRegister and 1) xor ((shiftRegister shr 6) and 1)
            } else {
                // Mode 0: bit 0 XOR bit 1
                (shiftRegister and 1) xor ((shiftRegister shr 1) and 1)
            }

            shiftRegister = (shiftRegister shr 1) or (feedback shl 14)
        }
    }

    fun clockEnvelope() {
        envelope.clock()
    }

    fun clockLength() {
        lengthCounter.clock()
    }

    fun output(): Int {
        if (!isEnabled) return 0
        if (lengthCounter.value == 0) return 0
        if ((shiftRegister and 1) == 1) return 0  // Bit 0 = 1 means silence

        return envelope.value
    }

    fun disableChannel() {
        isEnabled = false
        lengthCounter.value = 0
    }

    fun getLengthCounterValue(): Int = lengthCounter.value

    fun saveState(out: DataOutput) {
        out.writeBoolean(isEnabled)
        out.writeBoolean(modeFlag)
        out.writeInt(period)
        out.writeInt(timerCounter)
        out.writeInt(shiftRegister)
        envelope.saveState(out)
        lengthCounter.saveState(out)
    }

    fun loadState(input: DataInput) {
        isEnabled = input.readBoolean()
        modeFlag = input.readBoolean()
        period = input.readInt()
        // NOTE: timerCounter is stored in APU cycles (= CPU-cycle period / 2),
        // not CPU cycles, per issue #295. A save written by a pre-#295 build
        // would have this field in CPU cycles; on load, the next reload will
        // snap it back to the current period/2 representation, so the worst
        // observable effect of an old-save load is one LFSR step at the wrong
        // rate. We deliberately do NOT bump the NSTL file version because the
        // on-disk byte layout is unchanged.
        timerCounter = input.readInt()
        shiftRegister = input.readInt()
        envelope.loadState(input)
        lengthCounter.loadState(input)
    }
}
