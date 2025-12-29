package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toUnsignedInt

class NoiseChannel {
    private val envelope = Envelope()
    private val lengthCounter = LengthCounter()

    var isEnabled: Boolean = false
    var modeFlag: Boolean = false
    var period: Int = 0
    var timerCounter: Int = 0
    var shiftRegister: Int = 1  // 15-bit LFSR (starts at 1)

    // NTSC noise period table (in CPU cycles)
    private val noisePeriodTable = intArrayOf(
        4, 8, 16, 32, 64, 96, 128, 160, 202, 254, 380, 508, 762, 1016, 2034, 4068
    )

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

        // Reset timer and start envelope
        timerCounter = noisePeriodTable[period]
        envelope.startFlag = true
    }

    fun clockTimer() {
        if (timerCounter > 0) {
            timerCounter--
        } else {
            timerCounter = noisePeriodTable[period]

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
}
