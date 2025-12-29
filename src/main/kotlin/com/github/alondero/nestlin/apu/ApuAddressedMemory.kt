package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.letBit
import com.github.alondero.nestlin.setBit

class ApuAddressedMemory {
    // Pulse 1 ($4000-$4003)
    var pulse1Duty: Byte = 0
    var pulse1Sweep: Byte = 0
    var pulse1TimerLow: Byte = 0
    var pulse1Length: Byte = 0

    // Pulse 2 ($4004-$4007)
    var pulse2Duty: Byte = 0
    var pulse2Sweep: Byte = 0
    var pulse2TimerLow: Byte = 0
    var pulse2Length: Byte = 0

    // Triangle ($4008-$400B)
    var triangleLinear: Byte = 0
    var triangleTimerLow: Byte = 0
    var triangleTimerHigh: Byte = 0
    var triangleLength: Byte = 0

    // Noise ($400C-$400F)
    var noiseEnvelope: Byte = 0
    var noisePeriod: Byte = 0
    var noiseLength: Byte = 0

    // DMC ($4010-$4013)
    var dmcFlags: Byte = 0
    var dmcDirectLoad: Byte = 0
    var dmcSampleAddress: Byte = 0
    var dmcSampleLength: Byte = 0

    // Control/Status ($4015, $4017)
    var status: Byte = 0
    var frameCounter: Byte = 0

    operator fun get(addr: Int): Byte = when(addr) {
        0x00 -> pulse1Duty
        0x01 -> pulse1Sweep
        0x02 -> pulse1TimerLow
        0x03 -> pulse1Length
        0x04 -> pulse2Duty
        0x05 -> pulse2Sweep
        0x06 -> pulse2TimerLow
        0x07 -> pulse2Length
        0x08 -> triangleLinear
        0x09 -> triangleTimerLow
        0x0A -> triangleTimerHigh
        0x0B -> triangleLength
        0x0C -> noiseEnvelope
        0x0D -> 0  // Unused
        0x0E -> noisePeriod
        0x0F -> noiseLength
        0x10 -> dmcFlags
        0x11 -> dmcDirectLoad
        0x12 -> dmcSampleAddress
        0x13 -> dmcSampleLength
        0x14 -> 0  // Unused
        0x15 -> status  // $4015 - Status register
        else -> 0
    }

    operator fun set(addr: Int, value: Byte) {
        when(addr) {
            0x00 -> pulse1Duty = value
            0x01 -> pulse1Sweep = value
            0x02 -> pulse1TimerLow = value
            0x03 -> pulse1Length = value
            0x04 -> pulse2Duty = value
            0x05 -> pulse2Sweep = value
            0x06 -> pulse2TimerLow = value
            0x07 -> pulse2Length = value
            0x08 -> triangleLinear = value
            0x09 -> triangleTimerLow = value
            0x0A -> triangleTimerHigh = value
            0x0B -> triangleLength = value
            0x0C -> noiseEnvelope = value
            0x0E -> noisePeriod = value
            0x0F -> noiseLength = value
            0x10 -> dmcFlags = value
            0x11 -> dmcDirectLoad = value
            0x12 -> dmcSampleAddress = value
            0x13 -> dmcSampleLength = value
            0x15 -> status = value  // $4015 - Enable/disable channels
            0x17 -> frameCounter = value  // $4017 - Frame counter control
        }
    }

    // Helper methods for channel enable/disable
    fun isPulse1Enabled(): Boolean = status.isBitSet(0)
    fun setPulse1Enabled(enabled: Boolean) { status = status.letBit(0, enabled) }

    fun isPulse2Enabled(): Boolean = status.isBitSet(1)
    fun setPulse2Enabled(enabled: Boolean) { status = status.letBit(1, enabled) }

    fun isTriangleEnabled(): Boolean = status.isBitSet(2)
    fun setTriangleEnabled(enabled: Boolean) { status = status.letBit(2, enabled) }

    fun isNoiseEnabled(): Boolean = status.isBitSet(3)
    fun setNoiseEnabled(enabled: Boolean) { status = status.letBit(3, enabled) }

    fun isDmcEnabled(): Boolean = status.isBitSet(4)
    fun setDmcEnabled(enabled: Boolean) { status = status.letBit(4, enabled) }

    fun getFrameCounterMode(): Boolean = frameCounter.isBitSet(7)
    fun getFrameCounterIrqInhibit(): Boolean = frameCounter.isBitSet(6)

    fun reset() {
        pulse1Duty = 0
        pulse1Sweep = 0
        pulse1TimerLow = 0
        pulse1Length = 0
        pulse2Duty = 0
        pulse2Sweep = 0
        pulse2TimerLow = 0
        pulse2Length = 0
        triangleLinear = 0
        triangleTimerLow = 0
        triangleTimerHigh = 0
        triangleLength = 0
        noiseEnvelope = 0
        noisePeriod = 0
        noiseLength = 0
        dmcFlags = 0
        dmcDirectLoad = 0
        dmcSampleAddress = 0
        dmcSampleLength = 0
        status = 0
        frameCounter = 0
    }
}
