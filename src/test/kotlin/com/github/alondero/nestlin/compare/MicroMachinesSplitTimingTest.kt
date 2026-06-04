package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
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
 * Diagnostic: logs the raster position at which Micro Machines' mid-frame PPUMASK
 * ($2001) and PPUCTRL ($2000) writes land in Nestlin during frame 360, so we can
 * compare against Mesen2's ground truth (forced-blank at scanline 107, re-enable at
 * scanline 115). Reveals how far Nestlin's cycle-counted split drifts from hardware.
 */
class MicroMachinesSplitTimingTest {

    @Test
    fun `log PPUMASK and PPUCTRL write scanlines during frame 360`() {
        val rom = locateRom() ?: run { assumeTrue(false, "ROM not found"); return }

        val nestlin = Nestlin().apply { config.speedThrottlingEnabled = false }
        var frame = 0
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(f: Frame) { frame++ }
        })
        nestlin.load(rom)
        nestlin.powerReset()

        var lastMask = -1
        var lastCtrl = -1
        val log = mutableListOf<String>()
        var forcedBlankScanline = -1
        var guard = 400L * 80_000
        while (frame < 361 && guard-- > 0) {
            nestlin.stepCpuCycle()
            if (frame == 360) {
                val mask = nestlin.memory.ppuAddressedMemory.mask.register.toUnsignedInt()
                val ctrl = nestlin.memory.ppuAddressedMemory.controller.register.toUnsignedInt()
                if (mask != lastMask) {
                    val sl = nestlin.ppu.currentScanline
                    log += "MASK \$2001=0x%02X at scanline=%d cycle=%d".format(mask, sl, nestlin.ppu.currentCycle)
                    // The first mid-frame transition to rendering-disabled is the forced-blank.
                    if (mask == 0x00 && forcedBlankScanline < 0 && sl in 1..239) forcedBlankScanline = sl
                    lastMask = mask
                }
                if (ctrl != lastCtrl) {
                    log += "CTRL \$2000=0x%02X at scanline=%d cycle=%d".format(
                        ctrl, nestlin.ppu.currentScanline, nestlin.ppu.currentCycle)
                    lastCtrl = ctrl
                }
            }
        }
        println("[Nestlin split timing, frame 360]")
        log.forEach { println("  $it") }
        println("Mesen2 ground truth: MASK=0x00 at scanline 107, MASK=0x1E at scanline 115")

        // Guard the split's gross position. Mesen blanks at scanline 107; Nestlin's
        // cycle-counted split currently lands a hair early (~scanline 107, re-enable
        // ~114-115) — a known cycle-accuracy residual. A wide tolerance catches a gross
        // regression (split drifting tens of scanlines) without pinning the exact dot.
        org.junit.jupiter.api.Assertions.assertTrue(forcedBlankScanline in 100..114
        , "Forced-blank split landed at scanline $forcedBlankScanline, expected ~107 (100..114)")
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
