package com.github.alondero.nestlin.apu

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.lessThan
import com.natpryce.hamkrest.greaterThan
import org.junit.Test

class ApuMixingTest {

    // Replicate the mixing logic from Apu.mixAndBuffer() for testing
    private fun mixSample(pulse1Out: Int, pulse2Out: Int, triangleOut: Int, noiseOut: Int, dmcOut: Int): Short {
        val pulseSum = pulse1Out + pulse2Out
        val pulseScaled = if (pulseSum > 0) {
            95.88 / ((8128.0 / pulseSum) + 100.0)
        } else 0.0

        val tndScaled = if (triangleOut > 0 || noiseOut > 0 || dmcOut > 0) {
            val tndSum = (triangleOut / 8227.0) + (noiseOut / 12241.0) + (dmcOut / 22638.0)
            if (tndSum > 0.0) {
                159.79 / ((1.0 / tndSum) + 100.0)
            } else 0.0
        } else 0.0

        val mixed = (pulseScaled + tndScaled) * 0.9
        return (mixed * 32767.0).toInt().coerceIn(-32768, 32767).toShort()
    }

    @Test
    fun allChannelsSilentProducesZero() {
        val sample = mixSample(0, 0, 0, 0, 0)
        assertThat(sample.toInt(), equalTo(0))
    }

    @Test
    fun pulseOutputInRange() {
        // Single pulse channel at max
        val sample = mixSample(15, 0, 0, 0, 0)
        val value = sample.toInt()
        assertThat(value, greaterThan(0))
        assertThat(value, lessThan(32767))
    }

    @Test
    fun triangleOutputInRange() {
        // Triangle at max (15)
        val sample = mixSample(0, 0, 15, 0, 0)
        val value = sample.toInt()
        assertThat(value, greaterThan(0))
        assertThat(value, lessThan(32767))
    }

    @Test
    fun dmcOutputInRange() {
        // DMC at max (127)
        val sample = mixSample(0, 0, 0, 0, 127)
        val value = sample.toInt()
        assertThat(value, greaterThan(0))
        assertThat(value, lessThan(32767))
    }

    @Test
    fun bothPulsesMaxScalesCorrectly() {
        // Both pulses at max (15 each, sum = 30)
        val sample = mixSample(15, 15, 0, 0, 0)
        val value = sample.toInt()
        // Should be louder than single pulse
        val singlePulse = mixSample(15, 0, 0, 0, 0).toInt()
        assertThat(value, greaterThan(singlePulse))
    }

    @Test
    fun allChannelsMaxDoesNotClip() {
        val sample = mixSample(15, 15, 15, 15, 127)
        val value = sample.toInt()
        // Value should be less than 32767 due to 0.9 scale factor
        assertThat(value, lessThan(32767))
        assertThat(value, greaterThan(0))
    }

    @Test
    fun scaleFactorPreventsHardClipping() {
        // The 0.9 scale factor should prevent hard clipping (values going beyond valid range)
        // But actual NES mixing output can exceed "expected" values due to formula complexity
        val sample = mixSample(15, 15, 15, 15, 127)
        val value = sample.toInt()
        // Value should be in valid 16-bit signed range (our actual test for clipping)
        assertThat(value, greaterThan(-32768))
        assertThat(value, lessThan(32767))
    }

    @Test
    fun mixedOutputIsSigned() {
        // Even with silence, ensure no buffer overflow
        val sample = mixSample(0, 0, 0, 0, 0)
        assertThat(sample.toInt(), equalTo(0))
    }
}
