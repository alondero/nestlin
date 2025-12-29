package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toUnsignedInt

class TriangleChannel {
    private val lengthCounter = LengthCounter()

    var isEnabled: Boolean = false
    var controlFlag: Boolean = false
    var linearCounterReload: Int = 0
    var timerPeriod: Int = 0
    var timerCounter: Int = 0
    var sequenceStep: Int = 0
    var linearCounter: Int = 0
    var linearCounterReloadFlag: Boolean = false

    // 32-step triangle wave sequence
    private val triangleSequence = intArrayOf(
        15, 14, 13, 12, 11, 10,  9,  8,  7,  6,  5,  4,  3,  2,  1,  0,
         0,  1,  2,  3,  4,  5,  6,  7,  8,  9, 10, 11, 12, 13, 14, 15
    )

    fun write4008(value: Byte) {
        // Crrr rrrr
        // C: Control flag (halt length counter)
        // rrrrrrr: Linear counter reload value
        controlFlag = value.isBitSet(7)
        linearCounterReload = value.toUnsignedInt() and 0x7F
        lengthCounter.halt = controlFlag
    }

    fun write400A(value: Byte) {
        // Timer low 8 bits
        timerPeriod = (timerPeriod and 0x700) or value.toUnsignedInt()
    }

    fun write400B(value: Byte) {
        // llll lHHH
        // lllll: Length counter load
        // HHH: Timer high 3 bits
        val lengthLoad = (value.toUnsignedInt() shr 3) and 0x1F
        timerPeriod = (timerPeriod and 0x0FF) or ((value.toUnsignedInt() and 0x07) shl 8)

        if (isEnabled) {
            lengthCounter.loadCounter(lengthLoad)
        }

        // Reset timer and reload linear counter
        timerCounter = timerPeriod
        linearCounterReloadFlag = true
    }

    fun clockTimer() {
        if (timerCounter > 0) {
            timerCounter--
        } else {
            timerCounter = timerPeriod

            // Triangle only clocks sequencer if both counters are non-zero
            if (lengthCounter.value > 0 && linearCounter > 0) {
                sequenceStep = (sequenceStep + 1) and 0x1F
            }
        }
    }

    fun clockLinearCounter() {
        if (linearCounterReloadFlag) {
            linearCounter = linearCounterReload
        } else if (linearCounter > 0) {
            linearCounter--
        }

        if (!controlFlag) {
            linearCounterReloadFlag = false
        }
    }

    fun clockLength() {
        lengthCounter.clock()
    }

    fun output(): Int {
        if (!isEnabled) return 0
        if (lengthCounter.value == 0) return 0
        if (linearCounter == 0) return 0
        if (timerPeriod < 2) return 7  // Ultrasonic returns mid-level

        return triangleSequence[sequenceStep]
    }

    fun disableChannel() {
        isEnabled = false
        lengthCounter.value = 0
    }

    fun getLengthCounterValue(): Int = lengthCounter.value
}
