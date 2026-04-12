package com.github.alondero.nestlin

import org.junit.Test
import java.nio.file.Paths

/**
 * Detailed audio pipeline trace for debugging dropouts.
 * Captures resampler state over time to identify underrun patterns.
 */
class AudioResamplerTrace {

    @Test
    fun traceResampler() {
        val nestlin = Nestlin()
        nestlin.load(Paths.get("testroms/donkeykong.nes"))
        nestlin.powerReset()

        val cpu = nestlin.cpu
        val apu = nestlin.apu

        // Use a local resampler to mirror what the audio thread does
        val resampler = com.github.alondero.nestlin.apu.AudioResampler(44100.0, 44100.0)
        val outputSamples = ShortArray(1024)

        // Track state at each frame boundary
        val frameStates = mutableListOf<FrameState>()

        var cycles = 0
        var frameNum = 0
        val TOTAL_FRAMES = 600  // 10 seconds

        while (frameNum < TOTAL_FRAMES) {
            (1..3).forEach { nestlin.ppu.tick() }
            apu.tick()
            cpu.tick()
            cycles++

            if (nestlin.ppu.frameJustCompleted()) {
                val samples = apu.getAudioSamples()
                resampler.push(samples)

                // Try to consume samples like the audio thread does
                var totalProduced = 0
                var calls = 0
                var produced: Int
                do {
                    produced = resampler.resample(outputSamples, 1024)
                    if (produced > 0) {
                        totalProduced += produced
                        calls++
                    }
                } while (produced > 0)

                frameStates.add(FrameState(
                    frameNumber = frameNum,
                    samplesProduced = samples.size,
                    resamplerProduced = totalProduced,
                    resamplerCalls = calls,
                    bufferAvail = apu.audioBufferAvailableSamples(),
                    cpuPc = cpu.getCurrentPc().toInt() and 0xFFFF
                ))

                frameNum++
            }
        }

        // Analyze the trace
        printResults(frameStates)
    }

    private fun printResults(states: List<FrameState>) {
        println("=== AudioResampler Trace Analysis ===")
        println("${states.size} frames traced")
        println()

        // Check for underrun patterns (resamplerProduced < samplesProduced)
        val underrunFrames = states.filter { it.resamplerProduced < it.samplesProduced * 0.9 }
        println("Frames where resampler produced < 90% of input samples: ${underrunFrames.size}")
        if (underrunFrames.isNotEmpty()) {
            println("First 10 underrun frames:")
            underrunFrames.take(10).forEach {
                println("  Frame ${it.frameNumber}: input=${it.samplesProduced}, output=${it.resamplerProduced}, calls=${it.resamplerCalls}")
            }
        }

        println()
        // Check for consecutive low output
        var lowOutputStreaks = 0
        var inStreak = false
        for (state in states) {
            if (state.resamplerProduced < state.samplesProduced * 0.8) {
                if (!inStreak) {
                    inStreak = true
                    lowOutputStreaks++
                }
            } else {
                inStreak = false
            }
        }
        println("Low output streaks (consecutive frames < 80% efficiency): $lowOutputStreaks")

        println()
        // Show sample count distribution
        val sampleCounts = states.groupBy { it.samplesProduced }
        for ((count, frames) in sampleCounts.entries.sortedBy { it.key }) {
            println("  $count samples: ${frames.size} frames")
        }

        println()
        // Check for audio collapse patterns
        val collapseThreshold = 0.05
        var prevRms: Double? = null
        var collapses = 0
        for (state in states) {
            // Approximate RMS from resamplerProduced (if samples are non-zero)
            val approxRms = state.resamplerProduced.toDouble() / 1024.0 * 0.2  // rough approximation
            if (prevRms != null && prevRms > 0.1 && approxRms < collapseThreshold) {
                collapses++
                println("  COLLAPSE at frame ${state.frameNumber}: prev≈${String.format("%.4f", prevRms)}, curr≈${String.format("%.4f", approxRms)}")
            }
            prevRms = approxRms
        }
        println("Detected collapses: $collapses")
    }

    data class FrameState(
        val frameNumber: Int,
        val samplesProduced: Int,
        val resamplerProduced: Int,
        val resamplerCalls: Int,
        val bufferAvail: Int,
        val cpuPc: Int
    )
}
