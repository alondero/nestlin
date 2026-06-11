package com.github.alondero.nestlin

import com.github.alondero.nestlin.apu.*
import com.github.alondero.nestlin.isBitSet
import com.github.alondero.nestlin.setBit
import java.io.DataInput
import java.io.DataOutput

class Apu(private val dmaPort: DmaPort) {
    private val apuMemory = ApuAddressedMemory()
    private val frameCounter = FrameCounter()
    private val audioBuffer = AudioBuffer(sampleRate = 44100, bufferSize = 8192)

    val pulse1 = PulseChannel(channelId = 1)
    val pulse2 = PulseChannel(channelId = 2)
    val triangle = TriangleChannel()
    val noise = NoiseChannel()
    val dmc = DmcChannel(dmaPort)

    /**
     * Mapper-contributed audio (Issue #50). VRC6 registers three channels here;
     * future N163 / VRC7 / Sunsoft 5B mappers register their own. Kept private
     * + mutated only via [registerExpansionChannel] / [clearExpansionChannels]
     * so the audio thread sees a list that only grows or is replaced
     * wholesale, never partially mutated.
     */
    private val expansionChannels = mutableListOf<ExpansionAudioChannel>()

    private var cycleAccumulator = 0.0
    private var cpuCycleCounter = 0
    private var frameIrq = false

    private val SAMPLE_RATE = 44100.0

    /**
     * Per-expansion-channel gain in the final mix. The 2A03 pulse+tnd path
     * produces a [0, 0.5]-ish sum (then × 0.9 in [mixAndBuffer]); each
     * expansion channel reports `[0, 1]`. 0.15 puts a single fullscale
     * expansion channel at roughly one-pulse-worth of headroom, which is
     * about where real VRC6 cartridges sit relative to the 2A03 on hardware.
     * Three fullscale VRC6 channels = 0.45 added, leaving a few dB before
     * the soft clip at 32767.
     */
    private val EXPANSION_GAIN = 0.15

    /**
     * Active timing region. Setting it cascades to the frame counter and the
     * channels whose period/rate tables are region-dependent, and changes the CPU
     * clock used to pace audio sampling.
     */
    var region: Region = Region.NTSC
        set(value) {
            field = value
            frameCounter.region = value
            noise.region = value
            dmc.region = value
        }

    private val cpuFreq: Double get() = region.cpuFrequencyHz

    fun outputSampleRateHz(): Double = SAMPLE_RATE
    fun cpuCycles(): Int = cpuCycleCounter
    fun frameCounterStep(): Int = frameCounter.step
    fun frameCounterMaxCycles(): Int = frameCounter.maxCycles()
    fun audioBufferAvailableSamples(): Int = audioBuffer.availableSamples()
    fun audioBufferCapacity(): Int = 8192
    fun cycleAccumulatorValue(): Double = cycleAccumulator

    // Expose channel outputs for benchmarking
    fun pulse1Output(): Int = pulse1.output()
    fun pulse2Output(): Int = pulse2.output()
    fun triangleOutput(): Int = triangle.output()
    fun noiseOutput(): Int = noise.output()
    fun dmcOutput(): Int = dmc.output()

    fun tick() {
        cpuCycleCounter++

        // Check frame counter sequencing (~7,457 cycles per quarter frame)
        val frameResult = frameCounter.tick(cpuCycleCounter)

        if (frameResult.quarterFrame) clockQuarterFrame()

        if (frameResult.halfFrame) clockHalfFrame()

        if (frameResult.irq) {
            frameIrq = true
        }

        // Clock channel timers (pulse/noise on APU clock, triangle/DMC on CPU clock)
        if (cpuCycleCounter % 2 == 0) {
            pulse1.clockTimer()
            pulse2.clockTimer()
            noise.clockTimer()
        }
        triangle.clockTimer()
        dmc.clockTimer()

        // Mapper-contributed channels see one CPU cycle per Apu.tick — they
        // own their own internal divider state (VRC6 saw divides by 2, N163
        // round-robins voices, etc.) so the APU stays oblivious.
        if (expansionChannels.isNotEmpty()) {
            for (i in expansionChannels.indices) expansionChannels[i].tick(1)
        }

        // Mix audio at 44.1 kHz sampling rate
        cycleAccumulator += SAMPLE_RATE / cpuFreq
        if (cycleAccumulator >= 1.0) {
            cycleAccumulator -= 1.0
            mixAndBuffer()
        }

        // Reset CPU cycle counter at end of frame
        if (cpuCycleCounter >= frameCounter.maxCycles()) {
            cpuCycleCounter = 0
            // Don't reset cycleAccumulator to 0 - preserve fractional samples
            // that have been accumulated since the last sample was produced.
            // This prevents losing partial sample contributions at frame boundaries.
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
        var mixed = (pulseScaled + tndScaled) * 0.9  // 0.9 scale factor prevents clipping

        // Linear sum of mapper-contributed channels. The 2A03 mix above is
        // non-linear (the two NESdev formulas approximate the real DAC); the
        // expansion contribution is added linearly because the real cartridge
        // bus mixed expansion audio onto the same wire after the 2A03 had
        // already DACed. For VRC6 specifically, NESdev: "The DAC of the VRC6,
        // unlike the 2A03, appears to be linear."
        if (expansionChannels.isNotEmpty()) {
            var expSum = 0.0
            for (i in expansionChannels.indices) expSum += expansionChannels[i].currentSample()
            mixed += expSum * EXPANSION_GAIN
        }

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
            0x17 -> {
                val immediateClock = frameCounter.write4017(value)
                if (value.isBitSet(6)) {
                    frameIrq = false
                }
                if (immediateClock) {
                    clockQuarterFrame()
                    clockHalfFrame()
                }
            }
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

        dmc.setChannelEnabled(value.isBitSet(4))
    }

    private fun handleStatusRead(): Byte {
        var status: Byte = 0

        // Bits 0-4: Length counter status (non-zero = 1)
        if (pulse1.getLengthCounterValue() > 0) status = status.setBit(0)
        if (pulse2.getLengthCounterValue() > 0) status = status.setBit(1)
        if (triangle.getLengthCounterValue() > 0) status = status.setBit(2)
        if (noise.getLengthCounterValue() > 0) status = status.setBit(3)
        if (dmc.getBytesRemaining() > 0) status = status.setBit(4)

        // Bit 6: Frame IRQ, Bit 7: DMC IRQ
        if (frameIrq) status = status.setBit(6)
        if (dmc.irqPending) status = status.setBit(7)

        // Reading $4015 clears IRQ flags
        frameIrq = false
        dmc.clearIrq()

        return status
    }

    fun isIrqPending(): Boolean = frameIrq || dmc.irqPending

    /**
     * Hook a mapper's [ExpansionAudioChannel] into the mix (Issue #50). Called
     * by mapper constructors that ship their own audio (VRC6, future N163,
     * VRC7, Sunsoft 5B). The same channel may not be registered twice;
     * cartridge swaps go through [clearExpansionChannels] first.
     */
    fun registerExpansionChannel(channel: ExpansionAudioChannel) {
        if (!expansionChannels.contains(channel)) {
            expansionChannels.add(channel)
        }
    }

    /**
     * Drop every mapper-side channel. Called on cartridge unload / power-cycle
     * so a Mapper-0 game loaded after a Mapper-24 one doesn't drag the prior
     * VRC6 oscillators along.
     */
    fun clearExpansionChannels() {
        expansionChannels.clear()
    }

    /**
     * For tests + debugging. Defensive copy because the audio thread keeps a
     * live reference to the internal list.
     */
    fun expansionChannelCount(): Int = expansionChannels.size

    private fun clockQuarterFrame() {
        pulse1.clockEnvelope()
        pulse2.clockEnvelope()
        triangle.clockLinearCounter()
        noise.clockEnvelope()
    }

    private fun clockHalfFrame() {
        pulse1.clockLengthAndSweep()
        pulse2.clockLengthAndSweep()
        triangle.clockLength()
        noise.clockLength()
    }

    fun saveState(out: DataOutput) {
        out.writeDouble(cycleAccumulator)
        out.writeInt(cpuCycleCounter)
        out.writeBoolean(frameIrq)
        apuMemory.saveState(out)
        frameCounter.saveState(out)
        pulse1.saveState(out)
        pulse2.saveState(out)
        triangle.saveState(out)
        noise.saveState(out)
        dmc.saveState(out)
    }

    fun loadState(input: DataInput) {
        cycleAccumulator = input.readDouble()
        cpuCycleCounter = input.readInt()
        frameIrq = input.readBoolean()
        apuMemory.loadState(input)
        frameCounter.loadState(input)
        pulse1.loadState(input)
        pulse2.loadState(input)
        triangle.loadState(input)
        noise.loadState(input)
        dmc.loadState(input)
    }
}
