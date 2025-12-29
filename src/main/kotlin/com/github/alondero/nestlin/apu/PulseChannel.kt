package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toUnsignedInt

class PulseChannel(val channelId: Int) {
    private val envelope = Envelope()
    private val lengthCounter = LengthCounter()
    private val sweep = Sweep(channelId)

    var isEnabled: Boolean = false
    var dutyCycle: Int = 0
    var timerPeriod: Int = 0
    var timerCounter: Int = 0
    var sequenceStep: Int = 0

    // Duty cycle sequences (8 steps each)
    private val dutySequences = arrayOf(
        intArrayOf(0, 1, 0, 0, 0, 0, 0, 0),  // 12.5%
        intArrayOf(0, 1, 1, 0, 0, 0, 0, 0),  // 25%
        intArrayOf(0, 1, 1, 1, 1, 0, 0, 0),  // 50%
        intArrayOf(1, 0, 0, 1, 1, 1, 1, 1)   // 75% (inverted 25%)
    )

    fun write4000(value: Byte) {
        // DDlc vvvv
        // DD: Duty cycle
        // l: Loop envelope / disable length counter
        // c: Constant volume
        // vvvv: Volume/envelope period
        dutyCycle = (value.toUnsignedInt() shr 6) and 0x03
        val loopHaltFlag = value.isBitSet(5)
        lengthCounter.halt = loopHaltFlag
        envelope.loop = loopHaltFlag  // Critical: loop flag controls envelope looping
        envelope.constantVolume = value.isBitSet(4)
        envelope.volume = value.toUnsignedInt() and 0x0F
    }

    fun write4001(value: Byte) {
        // EPPP NSSS
        // E: Enabled
        // PPP: Period
        // N: Negate
        // SSS: Shift
        val sweepEnabled = value.isBitSet(7)
        val sweepPeriod = (value.toUnsignedInt() shr 4) and 0x07
        val sweepNegate = value.isBitSet(3)
        val sweepShift = value.toUnsignedInt() and 0x07

        sweep.configure(sweepEnabled, sweepPeriod, sweepNegate, sweepShift)
        sweep.reloadFlag = true
    }

    fun write4002(value: Byte) {
        // Timer low 8 bits
        timerPeriod = (timerPeriod and 0x700) or value.toUnsignedInt()
    }

    fun write4003(value: Byte) {
        // llll lHHH
        // lllll: Length counter load
        // HHH: Timer high 3 bits
        val lengthLoad = (value.toUnsignedInt() shr 3) and 0x1F
        timerPeriod = (timerPeriod and 0x0FF) or ((value.toUnsignedInt() and 0x07) shl 8)

        if (isEnabled) {
            lengthCounter.loadCounter(lengthLoad)
        }

        // Reset sequencer, timer, and start envelope
        sequenceStep = 0
        timerCounter = timerPeriod
        envelope.startFlag = true
    }

    fun clockTimer() {
        if (timerCounter > 0) {
            timerCounter--
        } else {
            timerCounter = timerPeriod
            sequenceStep = (sequenceStep + 1) and 0x07
        }
    }

    fun clockEnvelope() {
        envelope.clock()
    }

    fun clockLengthAndSweep() {
        lengthCounter.clock()
        sweep.clock(timerPeriod) { newPeriod ->
            timerPeriod = newPeriod
        }
    }

    fun output(): Int {
        // Return current sample value (0-15)
        if (!isEnabled) return 0
        if (lengthCounter.value == 0) return 0
        if (timerPeriod < 8) return 0  // Ultrasonic (silenced)
        if (sweep.isMuting(timerPeriod)) return 0

        val amplitude = envelope.value
        val dutyCycleBit = dutySequences[dutyCycle][sequenceStep]

        return dutyCycleBit * amplitude
    }

    fun disableChannel() {
        isEnabled = false
        lengthCounter.value = 0
        // Note: Envelope and sweep are NOT reset when disabled (hardware behavior)
    }

    fun getLengthCounterValue(): Int = lengthCounter.value
}
