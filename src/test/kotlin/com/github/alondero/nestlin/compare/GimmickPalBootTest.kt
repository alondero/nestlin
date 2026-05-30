package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import org.junit.Test
import org.junit.Ignore
import org.junit.Assume.assumeTrue
import org.junit.Assert.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Issue #77 acceptance: Mr. Gimmick (Europe) is a PAL FME-7 title that disables NMI
 * and drives its frame loop entirely from the mapper's CPU-cycle IRQ, with reload
 * values tuned to the PAL per-scanline cycle budget. On NTSC timing the IRQ lands at
 * the wrong phase and the boot routine spins forever; on PAL timing it boots.
 *
 * This is a behavioural assertion (rendering turns on and the frame becomes a real
 * image) rather than a pixel diff. It skips loudly if the ROM isn't on this machine.
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
    @Ignore("Pending a SEPARATE FME-7 IRQ bug, not the PAL timing core (which is validated by KickOffPalSmokeTest). Diagnosed 2026-05-30: Gimmick (Europe) sets up the FME-7 IRQ during early init (irqEnable=1, counter running), then spins forever at \$F2B8 (LDA \$F0 / BNE) with the IRQ now disabled, waiting for zero-page \$F0 to be cleared by an interrupt-driven routine (~\$E9C6) that never runs. Same under forced NTSC. OAM DMA is NOT involved (zero \$4014 transfers observed). Controls kirby/lolo1/tetris and Batman (same FME-7 mapper) all boot through this harness, so it's mapper-69/IRQ-specific, not a harness artifact. Un-ignore once the FME-7 raster-IRQ behaviour is fixed.")
    fun `Gimmick Europe boots under PAL timing`() {
        val rom = locateRom()
        assumeTrue("Mr. Gimmick (Europe) ROM not found — set NESTLIN_GIMMICK_ROM or place it in the NO-INTRO library. Skipping PAL boot validation.", rom != null)

        val pal = run(rom!!, Region.PAL, frames = 900)
        println("[GimmickPalBoot] PAL  -> $pal")

        // Booting means the game left its spin loop, enabled rendering, and drew a
        // real (multi-colour) frame rather than a flat backdrop.
        assertTrue("PAL: rendering never enabled (PPUMASK=${pal.ppuMask}, maxSeen=${pal.maxMaskSeen}) — still stuck at boot", pal.maxMaskSeen and 0x18 != 0)
        assertTrue("PAL: frame is essentially blank (${pal.distinctColors} colours) — game did not boot", pal.distinctColors > 4)
    }

    @Test
    @Ignore("Negative control for the PAL boot test above. NOTE: Gimmick currently hangs identically under both regions (the \$F2B8 FME-7-IRQ spin above), so this passes trivially and proves nothing yet. Only meaningful once the FME-7 bug is fixed and PAL actually boots. Un-ignore alongside the PAL boot test.")
    fun `Gimmick Europe stays stuck under forced NTSC timing`() {
        val rom = locateRom()
        assumeTrue("Mr. Gimmick (Europe) ROM not found — skipping NTSC negative-control check.", rom != null)

        val ntsc = run(rom!!, Region.NTSC, frames = 240)
        println("[GimmickPalBoot] NTSC -> $ntsc")
        // The whole point of #77: on the wrong region the PAL-tuned IRQ never advances
        // the boot state machine. This guards against a fix that accidentally makes the
        // game boot regardless of region (which would mean the timing isn't really used).
        assertTrue("NTSC: rendering enabled (PPUMASK=${ntsc.ppuMask}) — region timing isn't actually gating boot", ntsc.ppuMask and 0x18 == 0)
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
