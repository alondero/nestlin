package com.github.alondero.nestlin

import com.google.gson.GsonBuilder
import org.junit.Test
import java.nio.file.Paths

/**
 * Audio dropout benchmark - runs Donkey Kong for 300 frames and produces
 * structured JSON output for AI-driven debugging of periodic audio dropouts.
 *
 * Output: build/reports/audio_benchmark.json
 */
class AudioDropoutBenchmark {

    @Test
    fun runBenchmark() {
        val FRAMES = 300
        val CYCLES_PER_FRAME = 29830
        val TOTAL_CYCLES = FRAMES * CYCLES_PER_FRAME
        val WINDOW_SIZE = 30
        val DROP_THRESHOLD = 0.7

        val nestlin = Nestlin()
        nestlin.load(Paths.get("testroms/donkeykong.nes"))
        nestlin.powerReset()
        val cpu = nestlin.cpu

        val frameData = mutableListOf<FrameData>()
        val samplesByFrame = mutableMapOf<Int, ShortArray>()
        val rmsByFrame = mutableMapOf<Int, Double>()
        val rawSamplesAtDropout = mutableMapOf<Int, List<Int>>()

        var cycles = 0
        var frameNum = 0
        var lastFrameSampleCount = 0

        while (cycles < TOTAL_CYCLES) {
            // Run full emulation tick (same as Nestlin.start() loop)
            (1..3).forEach { nestlin.ppu.tick() }
            nestlin.apu.tick()
            nestlin.cpu.tick()
            cycles++

            // Capture at end of each frame
            if (nestlin.ppu.frameJustCompleted()) {
                val pulse1Snapshot = nestlin.apu.pulse1Output()
                val pulse2Snapshot = nestlin.apu.pulse2Output()
                val triangleSnapshot = nestlin.apu.triangleOutput()
                val noiseSnapshot = nestlin.apu.noiseOutput()
                val dmcSnapshot = nestlin.apu.dmcOutput()
                val cpuPc = cpu.getCurrentPc().toInt()
                val bufferAvail = nestlin.apu.audioBufferAvailableSamples()

                val samples = nestlin.getAudioSamples()
                samplesByFrame[frameNum] = samples
                rmsByFrame[frameNum] = computeRms(samples)

                val state = FrameData(
                    frameNumber = frameNum,
                    rmsEnergy = rmsByFrame[frameNum]!!,
                    sampleCount = samples.size,
                    audioBufferAvailable = bufferAvail,
                    audioBufferCapacity = nestlin.apu.audioBufferCapacity(),
                    cpuCycleCounter = nestlin.apu.cpuCycles(),
                    cpuPc = cpuPc,
                    frameCounterStep = nestlin.apu.frameCounterStep(),
                    frameCounterMaxCycles = nestlin.apu.frameCounterMaxCycles(),
                    pulse1Out = pulse1Snapshot,
                    pulse2Out = pulse2Snapshot,
                    triangleOut = triangleSnapshot,
                    noiseOut = noiseSnapshot,
                    dmcOut = dmcSnapshot
                )
                frameData.add(state)
                lastFrameSampleCount = samples.size
                frameNum++
            }
        }

        // Compute sliding window average
        val rmsValues = (0 until frameNum).map { rmsByFrame[it]!! }
        val avgByFrame = (0 until frameNum).map { i ->
            val start = maxOf(0, i - WINDOW_SIZE / 2)
            val end = minOf(frameNum, i + WINDOW_SIZE / 2)
            rmsValues.subList(start, end).average()
        }

        // Mark dropout frames
        val dropoutFrames = mutableListOf<Int>()

        for (i in 0 until frameNum) {
            val ratio = if (avgByFrame[i] > 0) rmsByFrame[i]!! / avgByFrame[i] else 0.0
            if (ratio < DROP_THRESHOLD) {
                dropoutFrames.add(i)
                rawSamplesAtDropout[i] = samplesByFrame[i]!!.map { it.toInt() }
            }
        }

        val summary = BenchmarkSummary(
            totalFrames = frameNum,
            meanRms = rmsValues.average(),
            minRms = rmsValues.minOrNull() ?: 0.0,
            maxRms = rmsValues.maxOrNull() ?: 0.0,
            dropoutCount = dropoutFrames.size,
            dropoutFrameIndices = dropoutFrames
        )

        val report = BenchmarkReport(
            summary = summary,
            frames = frameData,
            dropoutFramesWithSamples = rawSamplesAtDropout
        )

        val json = GsonBuilder().setPrettyPrinting().create().toJson(report)
        val outputPath = Paths.get("build/reports/audio_benchmark.json")
        outputPath.parent?.toFile()?.mkdirs()
        outputPath.toFile().writeText(json)

        println("Audio benchmark complete: $frameNum frames, ${dropoutFrames.size} dropouts detected")
        println("Output: ${outputPath.toAbsolutePath()}")
    }

    private fun computeRms(samples: ShortArray): Double {
        if (samples.isEmpty()) return 0.0
        val sumSquares = samples.sumOf { (it.toDouble() / 32767.0).let { v -> v * v } }
        return kotlin.math.sqrt(sumSquares / samples.size)
    }

    data class FrameData(
        val frameNumber: Int,
        val rmsEnergy: Double,
        val sampleCount: Int,
        val audioBufferAvailable: Int,
        val audioBufferCapacity: Int,
        val cpuCycleCounter: Int,
        val cpuPc: Int,
        val frameCounterStep: Int,
        val frameCounterMaxCycles: Int,
        val pulse1Out: Int,
        val pulse2Out: Int,
        val triangleOut: Int,
        val noiseOut: Int,
        val dmcOut: Int
    )

    data class BenchmarkSummary(
        val totalFrames: Int,
        val meanRms: Double,
        val minRms: Double,
        val maxRms: Double,
        val dropoutCount: Int,
        val dropoutFrameIndices: List<Int>
    )

    data class BenchmarkReport(
        val summary: BenchmarkSummary,
        val frames: List<FrameData>,
        val dropoutFramesWithSamples: Map<Int, List<Int>>
    )
}
