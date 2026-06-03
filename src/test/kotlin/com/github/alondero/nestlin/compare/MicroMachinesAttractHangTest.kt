package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.toUnsignedInt
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

/**
 * Long attract-mode run for Micro Machines (mapper 71). The attract sequence plays a
 * demo race, which exercises the same gameplay/render path as a real race. This test
 * runs ~2500 frames (~42s) and watches for a stall: the user reported the game
 * "hangs when starting a race". A stall shows up as the instruction count plateauing
 * (CPU parked in a spin loop that never breaks) across many frames.
 */
class MicroMachinesAttractHangTest {

    @Test
    fun `attract demo runs without stalling`() {
        val rom = locateRom() ?: run { assumeTrue("ROM not found", false); return }

        val nestlin = Nestlin().apply { config.speedThrottlingEnabled = false }
        var frame = 0
        val samples = mutableListOf<Pair<Int, Int>>() // frame -> instructionCount
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(f: Frame) {
                frame++
                if (frame % 100 == 0) {
                    samples += frame to nestlin.cpu.getInstructionCount()
                }
            }
        })
        nestlin.load(rom)
        nestlin.powerReset()

        val targetFrames = 2500
        var guard = targetFrames.toLong() * 80_000
        while (frame < targetFrames && guard-- > 0) {
            nestlin.stepCpuCycle()
        }

        println("[AttractHang] reached frame=$frame instr=${nestlin.cpu.getInstructionCount()} pc=0x${"%04X".format(nestlin.cpu.registers.programCounter.toUnsignedInt())}")
        var minDelta = Int.MAX_VALUE
        for (i in 1 until samples.size) {
            val delta = samples[i].second - samples[i - 1].second
            println("  frame ${samples[i].first}: +$delta instructions")
            if (delta < minDelta) minDelta = delta
        }
        // A live game executes thousands of instructions per 100 frames. A near-zero
        // delta over a 100-frame window means the CPU is stuck in a spin loop = hang.
        if (samples.size >= 2) {
            org.junit.Assert.assertTrue(
                "Attract demo stalled: a 100-frame window advanced only $minDelta instructions",
                minDelta > 10_000
            )
        }
    }

    private fun locateRom(): Path? {
        System.getenv("NESTLIN_MICRO_MACHINES_ROM")?.let {
            val p = Paths.get(it); if (Files.exists(p)) return p
        }
        val libs = listOf("S:/Media/Nintendo NES/Games", "X:/src/nestlin/testroms")
        for (lib in libs) {
            val dir = Paths.get(lib)
            if (!Files.isDirectory(dir)) continue
            Files.list(dir).use { stream ->
                return stream.toList().firstOrNull {
                    val n = it.fileName.toString().lowercase()
                    n.endsWith(".nes") && n.contains("micro machines") && n.contains("usa")
                }
            }
        }
        return null
    }
}
