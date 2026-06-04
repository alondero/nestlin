package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Assertions.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.streams.toList

/**
 * Issue #77 end-to-end validation on a real PAL game with a simple, known-good
 * mapper (Kick Off (Europe)). Unlike Gimmick (blocked by a separate FME-7 IRQ
 * issue), this isolates the PAL *timing core*: the ROM must auto-detect as PAL
 * from its NO-INTRO name and then actually boot to a rendered screen under the
 * 3.2:1 / 312-line PAL timing. Skips loudly if the ROM isn't on this machine.
 */
class KickOffPalSmokeTest {

    @Test
    fun `Kick Off Europe auto-detects PAL and boots to a rendered screen`() {
        val rom = locateRom()
        assumeTrue(rom != null, "Kick Off (Europe) ROM not found — set NESTLIN_KICKOFF_ROM or place it in the NO-INTRO library. Skipping PAL real-ROM validation.")

        val nestlin = Nestlin().apply { config.speedThrottlingEnabled = false }
        var frame = 0
        var maxMask = 0
        var firstRenderFrame = -1
        var last: Frame? = null
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(f: Frame) {
                frame++; last = f
                val m = nestlin.ppuMask()
                if (m > maxMask) maxMask = m
                if (firstRenderFrame < 0 && (m and 0x18) != 0) firstRenderFrame = frame
            }
        })
        // No region override: this also exercises auto-detection from the "(Europe)" name.
        nestlin.load(rom!!)
        nestlin.powerReset()

        assertEquals(Region.PAL, nestlin.currentRegion(), "ROM should auto-detect as PAL from its NO-INTRO region tag")

        var guard = 240L * 40_000
        while (frame < 240 && guard-- > 0) nestlin.stepCpuCycle()

        val colors = last?.let { f -> f.scanlines.flatMap { it.asList() }.toHashSet().size } ?: 0
        println("[KickOffPal] region=${nestlin.currentRegion()} firstRenderFrame=$firstRenderFrame maxMask=${"%02X".format(maxMask)} colors=$colors")

        assertTrue(maxMask and 0x18 != 0, "rendering never enabled under PAL (maxMask=${"%02X".format(maxMask)}) — PAL timing did not let the game boot")
        assertTrue(colors > 4, "frame essentially blank ($colors colours) — game did not reach a real screen")
    }

    private fun locateRom(): Path? {
        System.getenv("NESTLIN_KICKOFF_ROM")?.let { val p = Paths.get(it); if (Files.exists(p)) return p }
        val libs = listOf("S:/Media/Nintendo NES/Games", "X:/src/nestlin/testroms")
        for (lib in libs) {
            val dir = Paths.get(lib)
            if (!Files.isDirectory(dir)) continue
            Files.list(dir).use { stream ->
                val match = stream.toList().firstOrNull {
                    val n = it.fileName.toString().lowercase()
                    n.endsWith(".nes") && n.contains("kick off") && n.contains("europe")
                }
                if (match != null) return match
            }
        }
        return null
    }
}
