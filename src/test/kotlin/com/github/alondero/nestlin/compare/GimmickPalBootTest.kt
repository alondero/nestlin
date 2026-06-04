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

/**
 * Issue #82 acceptance: Mr. Gimmick (Europe) boots to a real rendered image.
 *
 * The earlier diagnosis ("disables NMI, drives its frame loop entirely from the
 * FME-7 IRQ, hangs under the wrong region") was WRONG. Verified against a Mesen2
 * --testRunner capture: Gimmick's boot is driven by its NMI handler (it ENABLES
 * NMI via $2000=$B0 and runs the handler at $E9C6 every frame); zero FME-7 IRQs
 * fire during boot. The real bug was in the PPU: the CPU-visible `nmiOccurred`
 * latch was not cleared at the pre-render scanline (only PPUSTATUS bit 7 was), so
 * after a vblank the game didn't acknowledge via $2002, the stale latch fired a
 * spurious immediate NMI the moment the game enabled NMI mid-frame — and that
 * mistimed NMI disabled NMI for good. Fixed in PpuAddressedMemory.clearVBlankAtPreRender.
 *
 * Boot is therefore region-independent (it happens under forced NTSC too); region
 * only affects whether the rendered content is correct. PAL-vs-NTSC timing is
 * covered by KickOffPalSmokeTest / RegionTimingTest, not here.
 *
 * This is a behavioural assertion (rendering turns on and the frame becomes a real
 * multi-colour image) rather than a pixel diff. It skips loudly if the ROM isn't
 * on this machine.
 */
class GimmickPalBootTest {

    private data class BootResult(
        val ppuMask: Int,
        val distinctColors: Int,
        val instructions: Int,
        val maxMaskSeen: Int,
        val firstRenderFrame: Int
    )

    @Test
    fun `Gimmick Europe boots under PAL timing`() {
        val rom = locateRom()
        assumeTrue(rom != null, "Mr. Gimmick (Europe) ROM not found — set NESTLIN_GIMMICK_ROM or place it in the NO-INTRO library. Skipping PAL boot validation.")

        val pal = run(rom!!, Region.PAL, frames = 900)
        println("[GimmickPalBoot] PAL  -> $pal")

        // Booting means the game left its spin loop, enabled rendering, and drew a
        // real (multi-colour) frame rather than a flat backdrop.
        assertTrue(pal.maxMaskSeen and 0x18 != 0, "PAL: rendering never enabled (PPUMASK=${pal.ppuMask}, maxSeen=${pal.maxMaskSeen}) — still stuck at boot")
        assertTrue(pal.distinctColors > 4, "PAL: frame is essentially blank (${pal.distinctColors} colours) — game did not boot")
    }

    private fun run(rom: Path, region: Region, frames: Int): BootResult {
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            config.regionOverride = region
        }
        var frameCount = 0
        var last: Frame? = null
        var maxMask = 0
        var firstRenderFrame = -1
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                frameCount++; last = frame
                val m = nestlin.ppuMask()
                if (m > maxMask) maxMask = m
                if (firstRenderFrame < 0 && (m and 0x18) != 0) firstRenderFrame = frameCount
            }
        })
        nestlin.load(rom)
        nestlin.powerReset()

        // Safety cap on CPU cycles so a genuinely-stuck run can't loop forever.
        var guard = frames.toLong() * 40_000
        while (frameCount < frames && guard-- > 0) nestlin.stepCpuCycle()

        val colors = last?.let { f -> f.scanlines.flatMap { it.asList() }.toHashSet().size } ?: 0
        return BootResult(nestlin.ppuMask(), colors, nestlin.cpu.getInstructionCount(), maxMask, firstRenderFrame)
    }

    private fun locateRom(): Path? {
        val candidates = listOfNotNull(
            System.getenv("NESTLIN_GIMMICK_ROM"),
            "S:/Media/Nintendo NES/Games/Mr. Gimmick (Europe).nes",
            "S:/Media/Nintendo NES/Games/Gimmick! (Europe).nes",
            "X:/src/nestlin/testroms/Mr. Gimmick (Europe).nes"
        )
        return candidates.map { Paths.get(it) }.firstOrNull { Files.exists(it) }
    }
}
