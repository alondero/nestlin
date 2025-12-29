package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toUnsignedInt

class DmcChannel(val memory: Memory) {
    var isEnabled: Boolean = false
    var irqEnabled: Boolean = false
    var loop: Boolean = false
    var rateIndex: Int = 0
    var outputLevel: Int = 0  // 7 bits (0-127)
    var sampleAddress: Int = 0
    var sampleLength: Int = 0
    var currentAddress: Int = 0
    var remainingBytes: Int = 0
    var sampleBuffer: Byte? = null
    var shiftRegister: Int = 0
    var bitsRemaining: Int = 0
    var timerCounter: Int = 428  // Initialize to rate[0] to avoid immediate trigger
    var silenceFlag: Boolean = true

    // NTSC DMC rate table (in CPU cycles)
    private val dmcRateTable = intArrayOf(
        428, 380, 340, 320, 286, 254, 226, 214,
        190, 160, 142, 128, 106,  84,  72,  54
    )

    fun write4010(value: Byte) {
        // IL-- rrrr
        irqEnabled = value.isBitSet(7)
        loop = value.isBitSet(6)
        rateIndex = value.toUnsignedInt() and 0x0F

        if (!irqEnabled) {
            // Clear DMC IRQ flag
        }
    }

    fun write4011(value: Byte) {
        // -ddd dddd
        outputLevel = value.toUnsignedInt() and 0x7F
    }

    fun write4012(value: Byte) {
        // aaaa aaaa
        // Sample address = $C000 + (value * 64)
        sampleAddress = 0xC000 + (value.toUnsignedInt() * 64)
    }

    fun write4013(value: Byte) {
        // llll llll
        // Sample length = (value * 16) + 1 bytes
        sampleLength = (value.toUnsignedInt() * 16) + 1
    }

    fun clockTimer() {
        if (timerCounter > 0) {
            timerCounter--
        } else {
            timerCounter = dmcRateTable[rateIndex]

            // Clock output unit (stub - full implementation in Phase 5)
        }
    }

    fun output(): Int {
        return outputLevel
    }

    fun disableChannel() {
        isEnabled = false
        remainingBytes = 0
    }

    fun getBytesRemaining(): Int = remainingBytes
}
