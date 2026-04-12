package com.github.alondero.nestlin

import org.junit.Test
import java.nio.file.Paths

/**
 * Test to identify audio dropout causes by simulating
 * the actual timing behavior of the audio thread.
 */
class AudioDropoutInvestigation {

    @Test
    fun investigateDropouts() {
        val nestlin = Nestlin()
        nestlin.load(Paths.get("testroms/donkeykong.nes"))
        nestlin.powerReset()

        val cpu = nestlin.cpu
        val apu = nestlin.apu
        val resampler = com.github.alondero.nestlin.apu.AudioResampler(44100.0, 44100.0)
        val outputSamples = ShortArray(1024)

        // Track events that could cause dropouts
        val events = mutableListOf<DropoutEvent>()

        var cycles = 0
        var frameNum = 0
        var lastPushFrame = -1
        var consecutiveEmptyFrames = 0

        // Simulate 600 frames (10 seconds)
        while (frameNum < 600) {
            (1..3).forEach { nestlin.ppu.tick() }
            apu.tick()
            cpu.tick()
            cycles++

            if (nestlin.ppu.frameJustCompleted()) {
                val samples = apu.getAudioSamples()

                // Check if we went multiple frames without getting samples
                if (samples.isEmpty()) {
                    consecutiveEmptyFrames++
                } else {
                    if (consecutiveEmptyFrames > 2) {
                        events.add(DropoutEvent(
                            frame = frameNum,
                            type = "MULTI_FRAME_GAP",
                            detail = "No samples for $consecutiveEmptyFrames frames before frame $frameNum"
                        ))
                    }
                    consecutiveEmptyFrames = 0
                    resampler.push(samples)
                    lastPushFrame = frameNum
                }

                // Consume from resampler like audio thread does
                var totalProduced = 0
                var callCount = 0
                var produced: Int
                do {
                    produced = resampler.resample(outputSamples, 1024)
                    if (produced > 0) {
                        totalProduced += produced
                        callCount++
                    }
                } while (produced > 0)

                // Check for underrun condition
                if (callCount == 0 && apu.audioBufferAvailableSamples() > 0) {
                    events.add(DropoutEvent(
                        frame = frameNum,
                        type = "RESAMPLER_STARVED",
                        detail = "Buffer has samples but resampler produced nothing"
                    ))
                }

                // Track channel state at each frame
                if (frameNum % 30 == 0 || frameNum in listOf(240, 300, 360, 420)) {
                    events.add(DropoutEvent(
                        frame = frameNum,
                        type = "STATE_SNAPSHOT",
                        detail = "p1=${apu.pulse1Output()}, p2=${apu.pulse2Output()}, tri=${apu.triangleOutput()}, noise=${apu.noiseOutput()}, dmc=${apu.dmcOutput()}"
                    ))
                }

                frameNum++
            }
        }

        // Print analysis
        println("\n=== Audio Dropout Investigation ===")
        println("Total frames: $frameNum")
        println("Events recorded: ${events.size}")
        println()

        val stateSnapshots = events.filter { it.type == "STATE_SNAPSHOT" }
        println("State snapshots:")
        stateSnapshots.forEach { println("  Frame ${it.frame}: ${it.detail}") }

        println()
        val gaps = events.filter { it.type == "MULTI_FRAME_GAP" }
        println("Multi-frame gaps: ${gaps.size}")
        gaps.take(5).forEach { println("  ${it.detail}") }

        println()
        val starved = events.filter { it.type == "RESAMPLER_STARVED" }
        println("Resampler starved events: ${starved.size}")
    }

    data class DropoutEvent(
        val frame: Int,
        val type: String,
        val detail: String
    )
}
