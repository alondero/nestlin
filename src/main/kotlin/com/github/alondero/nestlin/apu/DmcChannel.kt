package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.apu.DmaPort
import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.toUnsignedInt
import java.io.DataInput
import java.io.DataOutput

class DmcChannel(private val dmaPort: DmaPort) {
    var isEnabled: Boolean = false
    var irqEnabled: Boolean = false
    var irqPending: Boolean = false
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

    // DMC rate lookup table (CPU cycles) — values differ between NTSC and PAL.
    var region: Region = Region.NTSC
    private val dmcRateTable get() = region.dmcRates

    fun write4010(value: Byte) {
        // IL-- rrrr
        irqEnabled = value.isBitSet(7)
        loop = value.isBitSet(6)
        rateIndex = value.toUnsignedInt() and 0x0F

        if (!irqEnabled) {
            irqPending = false
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
        if (isEnabled) {
            refillSampleBuffer()
        }

        if (timerCounter > 0) {
            timerCounter--
        } else {
            timerCounter = dmcRateTable[rateIndex]
            clockOutputUnit()
        }
    }

    fun output(): Int {
        return outputLevel
    }

    fun disableChannel() {
        isEnabled = false
        remainingBytes = 0
        bitsRemaining = 0
        sampleBuffer = null
        silenceFlag = true
        irqPending = false
    }

    fun getBytesRemaining(): Int = remainingBytes

    fun clearIrq() {
        irqPending = false
    }

    fun setChannelEnabled(enabled: Boolean) {
        if (enabled) {
            if (!isEnabled) {
                isEnabled = true
            }
            if (remainingBytes == 0) {
                restartSample()
            }
        } else {
            disableChannel()
        }
    }

    private fun restartSample() {
        if (sampleLength <= 0) return
        currentAddress = sampleAddress
        remainingBytes = sampleLength
    }

    private fun refillSampleBuffer() {
        if (sampleBuffer != null || remainingBytes == 0) return

        sampleBuffer = dmaPort[currentAddress]
        currentAddress = (currentAddress + 1) and 0xFFFF
        if (currentAddress < 0x8000) {
            currentAddress = 0x8000
        }

        remainingBytes--
        if (remainingBytes == 0) {
            if (loop) {
                restartSample()
            } else if (irqEnabled) {
                irqPending = true
            }
        }
    }

    private fun clockOutputUnit() {
        if (bitsRemaining == 0) {
            if (sampleBuffer != null) {
                shiftRegister = sampleBuffer!!.toUnsignedInt()
                sampleBuffer = null
                bitsRemaining = 8
                silenceFlag = false
            } else {
                silenceFlag = true
            }
        }

        if (!silenceFlag) {
            if ((shiftRegister and 1) == 1) {
                if (outputLevel <= 125) outputLevel += 2
            } else {
                if (outputLevel >= 2) outputLevel -= 2
            }
        }

        if (bitsRemaining > 0) {
            shiftRegister = shiftRegister shr 1
            bitsRemaining--
        }
    }

    fun saveState(out: DataOutput) {
        out.writeBoolean(isEnabled)
        out.writeBoolean(irqEnabled)
        out.writeBoolean(irqPending)
        out.writeBoolean(loop)
        out.writeInt(rateIndex)
        out.writeInt(outputLevel)
        out.writeInt(sampleAddress)
        out.writeInt(sampleLength)
        out.writeInt(currentAddress)
        out.writeInt(remainingBytes)
        out.writeBoolean(sampleBuffer != null)
        if (sampleBuffer != null) out.writeByte(sampleBuffer!!.toInt())
        out.writeInt(shiftRegister)
        out.writeInt(bitsRemaining)
        out.writeInt(timerCounter)
        out.writeBoolean(silenceFlag)
    }

    fun loadState(input: DataInput) {
        isEnabled = input.readBoolean()
        irqEnabled = input.readBoolean()
        irqPending = input.readBoolean()
        loop = input.readBoolean()
        rateIndex = input.readInt()
        outputLevel = input.readInt()
        sampleAddress = input.readInt()
        sampleLength = input.readInt()
        currentAddress = input.readInt()
        remainingBytes = input.readInt()
        sampleBuffer = if (input.readBoolean()) input.readByte() else null
        shiftRegister = input.readInt()
        bitsRemaining = input.readInt()
        timerCounter = input.readInt()
        silenceFlag = input.readBoolean()
    }
}
