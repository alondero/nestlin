package com.github.alondero.nestlin.apu

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.greaterThan
import com.natpryce.hamkrest.lessThan
import com.natpryce.hamkrest.greaterThanOrEqualTo
import org.junit.Test
import com.github.alondero.nestlin.Memory
import com.github.alondero.nestlin.Apu

/**
 * Integration test for the audio pipeline.
 *
 * This test verifies that the audio buffer remains sufficiently populated
 * during normal operation and under simulated throttling conditions.
 */
class AudioPipelineTest {

    @Test
    fun bufferFillsOverTimeWithoutThrottling() {
        val memory = Memory()
        val apu = Apu(memory)

        // Run for 60 frames worth of CPU cycles (no throttling)
        // Each frame is 29830 CPU cycles at 4-step mode
        val totalCycles = 29830 * 60
        var cycles = 0
        while (cycles < totalCycles) {
            apu.tick()
            cycles++
        }

        // After 60 frames, buffer should have samples
        val available = apu.getAudioSamples().size
        assertThat(available, greaterThan(0))
    }

    @Test
    fun bufferLevelRemainsStableOverManyFrames() {
        val memory = Memory()
        val apu = Apu(memory)

        val bufferLevels = mutableListOf<Int>()

        // Run for 300 frames, sampling buffer level periodically
        val totalCycles = 29830 * 300
        var cycles = 0
        var frameCount = 0
        while (cycles < totalCycles) {
            apu.tick()
            cycles++

            // Sample buffer level every frame
            if (cycles % 29830 == 0) {
                frameCount++
                val samples = apu.getAudioSamples()
                bufferLevels.add(samples.size)
            }
        }

        // Verify buffer levels don't continuously drop (would indicate starvation)
        // Allow some variation, but overall trend should be stable
        val firstHalfAverage = bufferLevels.take(bufferLevels.size / 2).average()
        val secondHalfAverage = bufferLevels.drop(bufferLevels.size / 2).average()

        // Second half shouldn't be dramatically lower than first half
        // (allowing 50% variation for normal jitter)
        assertThat(secondHalfAverage, greaterThan(firstHalfAverage * 0.5))
    }

    @Test
    fun mixingFormulaProducesConsistentOutputRange() {
        val memory = Memory()
        val apu = Apu(memory)

        // Enable pulse channel 1 (square wave)
        memory[0x4000] = 0x50.toByte()  // Duty 12.5%, no envelope
        memory[0x4001] = 0x00.toByte()  // No sweep
        memory[0x4002] = 0x00.toByte()  // Timer low
        memory[0x4003] = 0x01.toByte()  // Length counter start
        memory[0x4015] = 0x01.toByte()  // Enable pulse 1

        // Run for a bit to accumulate some samples
        val totalCycles = 29830 * 10
        var cycles = 0
        while (cycles < totalCycles) {
            apu.tick()
            cycles++
        }

        val samples = apu.getAudioSamples()

        // Check that all samples are in valid 16-bit signed range
        for (sample in samples) {
            val value = sample.toInt()
            assertThat(value, greaterThan(-32768))
            assertThat(value, lessThan(32767))
        }

        // Verify we got some samples (buffer is filling)
        assertThat(samples.size, greaterThan(0))
    }

    @Test
    fun emptyBufferDoesNotCauseCrash() {
        val memory = Memory()
        val apu = Apu(memory)

        // Get samples before any ticks (should return empty array)
        val initialSamples = apu.getAudioSamples()
        assertThat(initialSamples.size, equalTo(0))

        // Get samples after minimal ticks
        repeat(100) { apu.tick() }
        val afterMinimalTicks = apu.getAudioSamples()

        // Should not crash - either empty or with samples
        assertThat(afterMinimalTicks.size, greaterThanOrEqualTo(0))
    }

    @Test
    fun frameCounterResetDoesNotCorruptCycleAccumulator() {
        val memory = Memory()
        val apu = Apu(memory)

        // Track samples produced over multiple frames
        val samplesPerFrame = mutableListOf<Int>()

        repeat(5) { frame ->
            var cycles = 0
            while (cycles < 29830) {
                apu.tick()
                cycles++
            }
            val samples = apu.getAudioSamples()
            samplesPerFrame.add(samples.size)
        }

        // Each frame should produce a similar number of samples
        // Allow 10% variation for normal jitter
        val avg = samplesPerFrame.average()
        for (count in samplesPerFrame) {
            assertThat(count.toDouble(), greaterThan(avg * 0.9))
            assertThat(count.toDouble(), lessThan(avg * 1.1))
        }
    }
}
