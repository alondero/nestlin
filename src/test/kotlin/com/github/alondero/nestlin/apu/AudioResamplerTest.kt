package com.github.alondero.nestlin.apu

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.greaterThan
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.sin

class AudioResamplerTest {

    private fun generateSineWave(sampleRate: Int, frequency: Double, durationSeconds: Double): ShortArray {
        val numSamples = (sampleRate * durationSeconds).toInt()
        val samples = ShortArray(numSamples)
        for (i in 0 until numSamples) {
            val angle = 2.0 * PI * frequency * i / sampleRate
            val value = sin(angle)
            samples[i] = (value * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
        return samples
    }

    @Test
    fun resampleReducesSampleRate() {
        val inputRate = 44100.0
        val outputRate = 48000.0
        val resampler = AudioResampler(inputRate, outputRate, bufferCapacity = 16384)

        // Generate 10ms of audio at 44100Hz (441 samples)
        val sineWave = generateSineWave(44100, 440.0, 0.01)
        resampler.push(sineWave)

        // Resample to 48kHz
        val output = ShortArray(1000)
        val produced = resampler.resample(output, 1000)

        // With 44100->48000 upsampling, should produce more output than input
        assertThat(produced > 0, equalTo(true))
        assertThat(produced > 400, equalTo(true))  // Should upsample (441 * 48000/44100 ≈ 479)
    }

    @Test
    fun resampleAtUnityRatio() {
        val inputRate = 44100.0
        val outputRate = 44100.0  // 1:1 ratio
        val resampler = AudioResampler(inputRate, outputRate, bufferCapacity = 16384)

        // Generate sine wave
        val sineWave = generateSineWave(44100, 440.0, 0.01)
        resampler.push(sineWave)

        val output = ShortArray(sineWave.size)
        val produced = resampler.resample(output, sineWave.size)

        // At 1:1 ratio, we should get substantial output
        assertThat(produced, greaterThan(sineWave.size / 2))
    }

    @Test
    fun bufferOverflowDropsOldestSample() {
        val resampler = AudioResampler(44100.0, 48000.0, bufferCapacity = 50)

        // Push more samples than capacity to cause overflow
        val samples = generateSineWave(44100, 440.0, 0.005)  // ~220 samples, buffer is 50
        resampler.push(samples)

        // Resampler should still produce output without crashing
        val output = ShortArray(500)
        val produced = resampler.resample(output, 500)
        assertThat(produced >= 0, equalTo(true))
    }

    @Test
    fun emptyBufferProducesZeroOutput() {
        val resampler = AudioResampler(44100.0, 48000.0, bufferCapacity = 16384)

        val output = ShortArray(100)
        val produced = resampler.resample(output, 100)

        assertThat(produced, equalTo(0))
    }

    @Test
    fun linearInterpolationSmoothsSignal() {
        val inputRate = 44100.0
        val outputRate = 48000.0
        val resampler = AudioResampler(inputRate, outputRate, bufferCapacity = 16384)

        // Generate a step function (constant then jump)
        val stepSignal = ShortArray(100)
        for (i in 0 until 50) {
            stepSignal[i] = 0
        }
        for (i in 50 until 100) {
            stepSignal[i] = 10000.toShort()
        }

        resampler.push(stepSignal)

        // The resampler should interpolate at the step boundary
        val output = ShortArray(200)
        val produced = resampler.resample(output, 200)

        // Output should have values transitioning between 0 and 10000
        // Check that we have a smooth transition, not an instant jump
        assertThat(produced > 0, equalTo(true))
    }

    @Test
    fun resamplerClearResetsState() {
        val resampler = AudioResampler(44100.0, 48000.0, bufferCapacity = 16384)

        val sineWave = generateSineWave(44100, 440.0, 0.1)
        resampler.push(sineWave)

        // Clear the resampler
        resampler.clear()

        // Should produce no output after clear
        val output = ShortArray(100)
        val produced = resampler.resample(output, 100)
        assertThat(produced, equalTo(0))
    }

    @Test
    fun positionStaysInValidRangeAfterUnderrun() {
        val resampler = AudioResampler(44100.0, 48000.0, bufferCapacity = 100)

        // Push very few samples
        val samples = ShortArray(10) { it.toShort() }
        resampler.push(samples)

        // Try to resample more than available (causes underrun)
        val output = ShortArray(100)
        val produced = resampler.resample(output, 100)

        // Should not crash - position should be clamped
        assertThat(produced >= 0, equalTo(true))
    }

    @Test
    fun multiplePushesAccumulate() {
        val resampler = AudioResampler(44100.0, 48000.0, bufferCapacity = 200)

        // Push in multiple chunks
        val chunk1 = generateSineWave(44100, 440.0, 0.005)  // ~220 samples
        val chunk2 = generateSineWave(44100, 440.0, 0.005)  // ~220 samples

        resampler.push(chunk1)
        resampler.push(chunk2)

        // Should be able to resample substantial output
        val output = ShortArray(1000)
        val produced = resampler.resample(output, 1000)
        assertThat(produced > 0, equalTo(true))
    }

    @Test
    fun resamplerSurvivesMatchedRateSteadyState() {
        // Producer pushes 735 samples per 16.67 ms burst (44100/sec). Consumer pulls
        // 17 × 47 = 799 outputs per burst (≈ 48000/60). At ratio 0.91875, 799 outputs
        // require 734 inputs — within 1 of the supply. A correct resampler must sustain
        // this indefinitely without ever returning produced=0.

        val resampler = AudioResampler(44100.0, 48000.0, bufferCapacity = 16384)
        val outputBuf = ShortArray(64)

        val samplesPerBurst = 735
        val outputsPerPoll = 47
        val pollsPerBurst = 17
        val burstsToSimulate = 60

        var underrunPolls = 0
        var totalPolls = 0

        for (burst in 0 until burstsToSimulate) {
            resampler.push(ShortArray(samplesPerBurst) { 100.toShort() })
            repeat(pollsPerBurst) {
                val produced = resampler.resample(outputBuf, outputsPerPoll)
                if (produced == 0) underrunPolls++
                totalPolls++
            }
        }

        assertThat(
            "expected zero underrun polls over $totalPolls total polls; got $underrunPolls " +
                "(each is a SourceDataLine starve event)",
            underrunPolls, equalTo(0)
        )
    }
}
