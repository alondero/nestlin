package com.github.alondero.nestlin

import org.junit.Test
import java.nio.file.Paths

/**
 * Simulates the audio thread behavior to identify timing issues.
 * Runs the emulator single-threaded but simulates the audio thread's
 * burst-read pattern to find underrun conditions.
 */
class AudioThreadSimulation {

    @Test
    fun simulateAudioThread() {
        val nestlin = Nestlin()
        nestlin.load(Paths.get("testroms/donkeykong.nes"))
        nestlin.powerReset()

        val cpu = nestlin.cpu
        val apu = nestlin.apu

        // Simulate audio thread's resampler
        val resampler = com.github.alondero.nestlin.apu.AudioResampler(44100.0, 44100.0)
        val outputSamples = ShortArray(1024)

        // Track buffer levels over time
        val bufferLevels = mutableListOf<Int>()
        val underrunEvents = mutableListOf<Int>()
        val sampleProduction = mutableListOf<Int>()

        var cycles = 0
        var frameNum = 0

        // Run 600 frames (10 seconds)
        while (frameNum < 600) {
            // Emulation tick
            (1..3).forEach { nestlin.ppu.tick() }
            apu.tick()
            cpu.tick()
            cycles++

            if (nestlin.ppu.frameJustCompleted()) {
                // Get samples from emulation
                val samples = apu.getAudioSamples()
                sampleProduction.add(samples.size)
                resampler.push(samples)

                // Simulate audio thread: burst-read pattern
                // The audio thread calls resample multiple times per iteration
                var totalConsumed = 0
                var readCalls = 0
                var produced: Int
                do {
                    produced = resampler.resample(outputSamples, 1024)
                    if (produced > 0) {
                        totalConsumed += produced
                        readCalls++
                    }
                } while (produced > 0)

                // Track buffer state
                val bufferLevel = apu.audioBufferAvailableSamples()
                bufferLevels.add(bufferLevel)

                // Detect underrun (when audio thread consumed but resampler had nothing)
                if (readCalls > 0 && totalConsumed == 0) {
                    underrunEvents.add(frameNum)
                }

                frameNum++
            }
        }

        // Analysis
        println("\n=== Audio Thread Simulation ===")
        println("Frames: $frameNum")
        val minLevel = bufferLevels.minOrNull() ?: 0
        val maxLevel = bufferLevels.maxOrNull() ?: 0
        val avgLevel = bufferLevels.average()
        println("Buffer level stats: min=$minLevel, max=$maxLevel, avg=${String.format("%.1f", avgLevel)}")
        val minProd = sampleProduction.minOrNull() ?: 0
        val maxProd = sampleProduction.maxOrNull() ?: 0
        println("Sample production: min=$minProd, max=$maxProd")
        println("Underrun events: ${underrunEvents.size}")
        if (underrunEvents.isNotEmpty()) {
            println("First 10: ${underrunEvents.take(10)}")
        }

        // Check for periodic patterns
        if (underrunEvents.size >= 2) {
            val intervals = mutableListOf<Int>()
            for (i in 1 until underrunEvents.size) {
                intervals.add(underrunEvents[i] - underrunEvents[i-1])
            }
            val avgInterval = intervals.average()
            println("Average interval between underruns: ${String.format("%.1f", avgInterval)} frames")
            val frameSeconds = avgInterval / 60.0
            println("  = ${String.format("%.2f", frameSeconds)} seconds")
        }
    }
}
