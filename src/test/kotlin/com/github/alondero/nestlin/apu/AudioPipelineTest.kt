package com.github.alondero.nestlin.apu

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.greaterThan
import com.natpryce.hamkrest.lessThan
import com.natpryce.hamkrest.greaterThanOrEqualTo
import org.junit.jupiter.api.Test
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
        // Factory (issue #22): Memory and Apu are wired so register writes through
        // Memory dispatch to the APU rather than being silently dropped.
        val (_, apu) = Memory.createWithApu()

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
        val (_, apu) = Memory.createWithApu()

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
        // Factory (issue #22): Memory and Apu are wired together so writes to
        // $4000-$4015 actually configure the channels. Before the factory this
        // test only saw silent output (apu?. was null); now it exercises the real
        // pulse channel path.
        val (memory, apu) = Memory.createWithApu()

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
    fun `write to 4000 through Memory actually configures the APU pulse channel`() {
        // Regression for issue #22: pre-factory, `Memory` had a nullable Apu and
        // `apu?.handleRegisterWrite(...)` was a silent no-op when unwired, so a
        // test that built `Memory()` + `Apu(memory)` WITHOUT setting `memory.apu = apu`
        // (or the AudioPipelineTest pre-#22) wrote to `$4000` and saw the byte land
        // in `Memory.apuAddressedMemory`, but the pulse channel driver never ran
        // and `getAudioSamples()` returned all-zero buffers. Now the factory always
        // wires the pair, so writing `$4000` through Memory MUST configure pulse1
        // and the channel output MUST go non-zero. If anyone re-introduces a
        // nullable Apu field (or accidentally restores `apu?.`), this test fails
        // fast with a zero-output assertion.
        val (memory, apu) = Memory.createWithApu()

        // Enable pulse1 BEFORE the $4003 write so the length counter actually loads.
        memory[0x4015] = 0x01.toByte()  // enable pulse1 (length counter now loads)
        // 75% duty (bits 7-6 = 11 — duty bit is 1 at sequenceStep 0), length halt,
        // constant volume=15. 75% duty is the only NES duty that has bit=1 at
        // sequenceStep=0, so the channel output is non-zero IMMEDIATELY after
        // write4003 resets the sequencer (no need to wait for the timer to wrap).
        memory[0x4000] = 0xFF.toByte()
        memory[0x4001] = 0x00.toByte()  // no sweep
        memory[0x4002] = 0x08.toByte()  // timer low = 8
        // lengthLoad=1 (NTSC table → 254 frames), timer high=0 → timerPeriod = 8.
        // Period 8 = ~14 kHz at 1.79 MHz CPU clock, ticks pulse1 every other CPU
        // cycle. Period >= 8 avoids the `timerPeriod < 8 → silenced` output gate.
        memory[0x4003] = 0x08.toByte()

        // Two CPU cycles is enough to clock pulse1 once (timer ticks on even
        // cycles, period 8 → advances the sequence by 1). After that, pulse1.output()
        // returns either 0 or 15 depending on the duty-cycle bit at the new step.
        repeat(2) { apu.tick() }

        // The wiring is exercised iff the channel state actually advanced.
        // pulse1.output() returns 0 when isEnabled=false, lengthCounter=0,
        // timerPeriod<8, or sweep-muted. With the writes above, all four should
        // pass — pre-#22 the writes never reached the channel and output was
        // ALWAYS 0 regardless of how many ticks elapsed.
        val output = apu.pulse1Output()
        assertThat(
            "Memory.write($4000-$4003) must configure pulse1 via the factory wiring; " +
                "pulse1.output() was $output — wiring regression (issue #22)",
            output,
            greaterThan(0)
        )
    }

    @Test
    fun emptyBufferDoesNotCauseCrash() {
        val (_, apu) = Memory.createWithApu()

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
        val (_, apu) = Memory.createWithApu()

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
