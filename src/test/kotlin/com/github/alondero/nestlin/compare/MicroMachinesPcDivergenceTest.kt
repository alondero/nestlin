package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.gamepak.Mapper71
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.toUnsignedInt
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

/**
 * Traces the CPU PC and mapper prgBank on every frame from 250 to 300 to
 * pin down the *moment* of divergence. Each step takes ~1 sec, so the test
 * is slow but the trajectory is the diagnostic.
 */
class MicroMachinesPcDivergenceTest {

    @Test
    fun `trace PC and prgBank across the divergence`() {
        val rom = locateRom() ?: run {
            assumeTrue(false, "ROM not found"); return
        }

        val nestlin = Nestlin().apply { config.speedThrottlingEnabled = false }
        val events = mutableListOf<String>()

        var frame = 0
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(f: Frame) {
                frame++
                if (frame in 250..300) {
                    val pc = nestlin.cpu.registers.programCounter.toUnsignedInt()
                    val prgBank = (nestlin.memory.mapper as? Mapper71)
                        ?.snapshot()?.banks?.get("prgBank") ?: -1
                    val inSwitchable = pc in 0x8000..0xBFFF
                    val inFixed = pc in 0xC000..0xFFFF
                    val region = when {
                        inSwitchable -> "SWITCHABLE"
                        inFixed -> "FIXED"
                        else -> "OTHER"
                    }
                    events += "frame=$frame pc=0x%04X prgBank=$prgBank region=$region".format(pc)
                }
            }
        })

        nestlin.load(rom)
        nestlin.powerReset()
        var guard = 300L * 80_000
        while (frame < 300 && guard-- > 0) {
            nestlin.stepCpuCycle()
        }

        println("[PC-Divergence trace]")
        events.forEach { println("  $it") }
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
