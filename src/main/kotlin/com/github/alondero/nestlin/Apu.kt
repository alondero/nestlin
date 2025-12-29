package com.github.alondero.nestlin

import com.github.alondero.nestlin.apu.*
import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.setBit

class Apu(val memory: Memory) {
    private val apuMemory = ApuAddressedMemory()
    private val frameCounter = FrameCounter()
    private val audioBuffer = AudioBuffer(sampleRate = 44100, bufferSize = 4096)

    val pulse1 = PulseChannel(channelId = 1)
    val pulse2 = PulseChannel(channelId = 2)
    val triangle = TriangleChannel()
    val noise = NoiseChannel()
    val dmc = DmcChannel(memory)

    private var cycleAccumulator = 0.0
    private var cpuCycleCounter = 0

    // NES APU CPU frequency (1.789773 MHz)
    private val CPU_FREQ = 1789773.0
    private val SAMPLE_RATE = 44100.0

    fun tick() {
        cpuCycleCounter++

        // Check frame counter sequencing (~7,457 cycles per quarter frame)
        val (quarterFrameClock, halfFrameClock) = frameCounter.tick(cpuCycleCounter)

        if (quarterFrameClock) {
            pulse1.clockEnvelope()
            pulse2.clockEnvelope()
            triangle.clockLinearCounter()
            noise.clockEnvelope()
        }

        if (halfFrameClock) {
            pulse1.clockLengthAndSweep()
            pulse2.clockLengthAndSweep()
            triangle.clockLength()
            noise.clockLength()
        }

        // Clock all channel timers at APU rate
        pulse1.clockTimer()
        pulse2.clockTimer()
        triangle.clockTimer()
        noise.clockTimer()
        dmc.clockTimer()

        // Mix audio at 44.1 kHz sampling rate
        cycleAccumulator += SAMPLE_RATE / CPU_FREQ
        if (cycleAccumulator >= 1.0) {
            cycleAccumulator -= 1.0
            mixAndBuffer()
        }

        // Reset CPU cycle counter at end of frame
        if (cpuCycleCounter >= 29830) {
            cpuCycleCounter = 0
        }
    }

    private fun mixAndBuffer() {
        // Get output from each channel (0-15 for pulse/triangle/noise, 0-127 for dmc)
        val pulse1Out = pulse1.output()
        val pulse2Out = pulse2.output()
        val triangleOut = triangle.output()
        val noiseOut = noise.output()
        val dmcOut = dmc.output()

        // NES audio mixing formula (accurate to hardware)
        // Uses two separate mixing equations for pulse and triangle/noise/dmc channels
        val pulseSum = pulse1Out + pulse2Out

        // Pulse output scaling (0-30 from two 0-15 channels)
        // Formula: 95.88 / ((8128.0 / pulseSum) + 100.0)
        val pulseScaled = if (pulseSum > 0) {
            95.88 / ((8128.0 / pulseSum) + 100.0)
        } else {
            0.0
        }

        // Triangle + Noise + DMC scaling
        // Formula: 159.79 / ((1.0 / tndSum) + 100.0)
        // where tndSum = triangleOut/8227 + noiseOut/12241 + dmcOut/22638
        val tndScaled = if (triangleOut > 0 || noiseOut > 0 || dmcOut > 0) {
            val tndSum = (triangleOut / 8227.0) + (noiseOut / 12241.0) + (dmcOut / 22638.0)
            if (tndSum > 0.0) {
                159.79 / ((1.0 / tndSum) + 100.0)
            } else {
                0.0
            }
        } else {
            0.0
        }

        // Combine both components and normalize to 16-bit range
        // The output is approximately in range [0, 1.0] so scale to 16-bit signed max
        val mixed = (pulseScaled + tndScaled) * 0.9  // 0.9 scale factor prevents clipping
        val sample = (mixed * 32767.0).toInt().coerceIn(-32768, 32767).toShort()

        audioBuffer.write(sample)
    }

    fun getAudioSamples(): ShortArray {
        val available = audioBuffer.availableSamples()
        if (available == 0) {
            return ShortArray(0)
        }

        val output = ShortArray(available)
        audioBuffer.read(output, available)
        return output
    }

    // Integrate with Memory for register writes/reads
    fun handleRegisterWrite(address: Int, value: Byte) {
        apuMemory[address] = value

        when (address) {
            0x00 -> pulse1.write4000(value)
            0x01 -> pulse1.write4001(value)
            0x02 -> pulse1.write4002(value)
            0x03 -> pulse1.write4003(value)
            0x04 -> pulse2.write4000(value)
            0x05 -> pulse2.write4001(value)
            0x06 -> pulse2.write4002(value)
            0x07 -> pulse2.write4003(value)
            0x08 -> triangle.write4008(value)
            0x0A -> triangle.write400A(value)
            0x0B -> triangle.write400B(value)
            0x0C -> noise.write400C(value)
            0x0E -> noise.write400E(value)
            0x0F -> noise.write400F(value)
            0x10 -> dmc.write4010(value)
            0x11 -> dmc.write4011(value)
            0x12 -> dmc.write4012(value)
            0x13 -> dmc.write4013(value)
            0x15 -> handleStatusWrite(value)
            0x17 -> frameCounter.write4017(value)
        }
    }

    fun handleRegisterRead(address: Int): Byte {
        return when (address) {
            0x15 -> handleStatusRead()
            else -> apuMemory[address]
        }
    }

    private fun handleStatusWrite(value: Byte) {
        apuMemory.status = value

        // Enable/disable channels based on register bits
        if (!value.isBitSet(0)) {
            pulse1.disableChannel()
        } else {
            pulse1.isEnabled = true
        }

        if (!value.isBitSet(1)) {
            pulse2.disableChannel()
        } else {
            pulse2.isEnabled = true
        }

        if (!value.isBitSet(2)) {
            triangle.disableChannel()
        } else {
            triangle.isEnabled = true
        }

        if (!value.isBitSet(3)) {
            noise.disableChannel()
        } else {
            noise.isEnabled = true
        }

        if (!value.isBitSet(4)) {
            dmc.disableChannel()
        } else {
            dmc.isEnabled = true
        }
    }

    private fun handleStatusRead(): Byte {
        var status: Byte = 0

        // Bits 0-4: Length counter status (non-zero = 1)
        if (pulse1.getLengthCounterValue() > 0) status = status.setBit(0)
        if (pulse2.getLengthCounterValue() > 0) status = status.setBit(1)
        if (triangle.getLengthCounterValue() > 0) status = status.setBit(2)
        if (noise.getLengthCounterValue() > 0) status = status.setBit(3)
        if (dmc.getBytesRemaining() > 0) status = status.setBit(4)

        // Bits 5-6: IRQ flags (not implemented yet)
        // Bit 7: Unused

        return status
    }

}