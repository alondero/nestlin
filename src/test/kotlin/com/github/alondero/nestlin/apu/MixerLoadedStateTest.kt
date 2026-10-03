package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.Apu
import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.testutil.TestRoms
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInput
import java.io.DataInputStream
import java.io.DataOutput
import java.io.DataOutputStream
import java.nio.ByteBuffer

class MixerLoadedStateTest {
    enum class Channel { PULSE, NOISE, DMC }

    @ParameterizedTest
    @EnumSource(Channel::class)
    fun `unchecked channel amplitudes restored from a save retain the original finite mixer output`(channel: Channel) {
        for (level in intArrayOf(128, 16, 31, -1, Int.MIN_VALUE, Int.MAX_VALUE)) {
            val source = emulator()
            val apu = source.apu
            when (channel) {
                Channel.PULSE -> {
                    apu.handleRegisterWrite(0x15, 1)
                    apu.handleRegisterWrite(0x00, 0xFD.toByte()) // constant envelope volume 13
                    apu.handleRegisterWrite(0x02, 0xFC.toByte())
                    apu.handleRegisterWrite(0x03, 0x08)
                    corruptAmplitude(apu.pulse1::saveState, apu.pulse1::loadState, level)
                }
                Channel.NOISE -> {
                    apu.handleRegisterWrite(0x15, 8)
                    apu.handleRegisterWrite(0x0C, 0x3D)
                    apu.handleRegisterWrite(0x0E, 0x0F) // retain the audible LFSR phase for the first sample
                    apu.handleRegisterWrite(0x0F, 0x08)
                    apu.noise.shiftRegister = 0x4000 // reachable non-silent phase
                    corruptAmplitude(apu.noise::saveState, apu.noise::loadState, level)
                }
                Channel.DMC -> {
                    apu.handleRegisterWrite(0x11, 13)
                    corruptAmplitude(apu.dmc::saveState, apu.dmc::loadState, level)
                }
            }
            // Exercise the complete public .nstl loader as well as the channel's unchecked readInt.
            val save = ByteArrayOutputStream().also { source.saveState(it) }.toByteArray()
            val restored = emulator()
            restored.loadState(ByteArrayInputStream(save))
            repeat(50) { restored.apu.tick() }
            val amplitude = when (channel) {
                Channel.PULSE -> restored.apu.pulse1Output()
                Channel.NOISE -> restored.apu.noiseOutput()
                Channel.DMC -> restored.apu.dmcOutput()
            }
            assertEquals(level, amplitude, "$channel must reach mixing with the raw loaded amplitude")
            val output = ShortArray(restored.getAudioBufferCapacity())
            assertEquals(1, restored.getAudioSamples(output))
            assertEquals(originalSample(restored.apu).toInt(), output[0].toInt(), "$channel level=$level")
        }
    }

    private fun emulator(): Nestlin = Nestlin().also {
        it.loadBytes(TestRoms.nestestBytes())
        it.powerReset()
    }

    private fun corruptAmplitude(save: (DataOutput) -> Unit, load: (DataInput) -> Unit, level: Int) {
        val bytes = ByteArrayOutputStream().also { save(DataOutputStream(it)) }.toByteArray()
        // Find the unique constant-volume/output-level marker within this channel's own wire block.
        val offsets = (0..bytes.size - 4).filter { ByteBuffer.wrap(bytes).getInt(it) == 13 }
        assertEquals(1, offsets.size, "Amplitude marker must be unique in the serialized channel")
        ByteBuffer.wrap(bytes).putInt(offsets.single(), level)
        load(DataInputStream(ByteArrayInputStream(bytes)))
    }

    private fun originalSample(apu: Apu): Short {
        val pulseSum = apu.pulse1Output() + apu.pulse2Output()
        val pulse = if (pulseSum > 0) 95.88 / ((8128.0 / pulseSum) + 100.0) else 0.0
        val triangle = apu.triangleOutput()
        val noise = apu.noiseOutput()
        val dmc = apu.dmcOutput()
        val sum = (triangle / 8227.0) + (noise / 12241.0) + (dmc / 22638.0)
        val tnd = if ((triangle > 0 || noise > 0 || dmc > 0) && sum > 0.0) {
            159.79 / ((1.0 / sum) + 100.0)
        } else 0.0
        val filtered = AnalogFilter(apu.outputSampleRateHz()).process((pulse + tnd) * 0.9)
        return (filtered * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
    }
}
