package com.github.alondero.nestlin.compare

import org.junit.Assert
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Cross-emulator byte-compare regression for Mapper 71 (Camerica / Codemasters)
 * on Micro Machines (USA) (Unl).
 *
 * Pattern mirrors `GxRomStateComparisonTest`. Mapper 71 has no CHR banking and
 * no PRG-RAM, so the high-signal asserts are:
 *  - **Palette RAM** (32 bytes): if PRG banking is wrong, the game's boot
 *    routine uploads a different palette, which is the same failure mode the
 *    GxROM regression test caught.
 *  - **Mapper state**: Nestlin's `prgBank` should stay in the valid 16-bank
 *    range (`[0, 16)`) for a 256 KB ROM. (Mesen2 doesn't expose the BF909x
 *    register through the capturer's JSON, so this is a one-sided assert on
 *    Nestlin — the smoke test already verifies it's monotonic and consistent.)
 *
 * CHR pattern data is identical between correct Mapper 71 implementations
 * (no CHR banking) so it's a weaker signal here, but we still print it for
 * the reviewer. OAM and PPUCTRL are phase-sensitive (Mesen2 captures at
 * scanline 240, Nestlin at 261) so we print them but don't assert — same
 * reasoning as the GxROM test.
 *
 * ROMs come from the NO-INTRO library and skip loudly when absent.
 */
class MicroMachinesMapper71StateComparisonTest {

    @Test
    fun `Micro Machines render outputs match Mesen2 (mapper 71)`() {
        assumeTrue("Mesen2 not available", Mesen2StateCapturer.isMesen2Available())
        val romPath: Path = locateRom() ?: run {
            assumeTrue(
                "Micro Machines (USA) ROM not found at S:/Media/Nintendo NES/Games — skipping.",
                false
            )
            return
        }

        val frameNumber = 120   // Code Masters logo "ABSOLUTELY BRILLIANT!" — clean MATCH

        // KNOWN LIMITATION: state diff at frame >= 270 DIVERGES from Mesen2 — see
        // MicroMachinesDivergenceSweepTest for the trajectory. Nestlin drifts ~100
        // CPU cycles/frame ahead of Mesen2 in the boot path; by frame 270 the game
        // is at a different PC (Nestlin: 0x9C12 in switchable bank 13; Mesen2:
        // 0xFF71 in fixed bank 15) and writes different values to palette/CHR-RAM.
        // Frame 120 is the last clean MATCH. Root cause is upstream of Mapper71 —
        // candidate: PPU NMI/VBlank timing.

        val nestlin = NestlinStateCapturer.captureState(romPath, frameNumber)
        val mesen2 = Mesen2StateCapturer.captureState(romPath, frameNumber)

        val chrDiff = firstDiff(nestlin.chr, mesen2.chr)
        val paletteDiff = firstDiff(nestlin.paletteRam, mesen2.paletteRam)
        val oamDiff = firstDiff(nestlin.oam, mesen2.oam)

        // Pull the live PRG bank Nestlin has after frame 120.
        val nestlinPrgBank = nestlin.mapper?.banks?.get("prgBank") ?: -1
        val nestlinMirroringMode = nestlin.mapper?.registers?.get("firehawkMirrorUpper")

        val report = buildString {
            appendLine("Micro Machines (mapper 71) render-output compare at frame $frameNumber")
            appendLine("  PPU mask:    Nestlin=0x%02X  Mesen2=0x%02X"
                .format(nestlin.ppu.mask, mesen2.ppu.mask))
            appendLine("  PPU control: Nestlin=0x%02X  Mesen2=0x%02X (informational; phase-sensitive)"
                .format(nestlin.ppu.control, mesen2.ppu.control))
            appendLine("  Mapper prgBank (Nestlin snapshot): $nestlinPrgBank")
            appendLine("  Mapper firehawkMirrorUpper (Nestlin): $nestlinMirroringMode")
            appendLine("  CHR pattern: " + (chrDiff?.let { "MISMATCH at $it" } ?: "MATCH"))
            appendLine("  palette:     " + (paletteDiff?.let { "MISMATCH at $it" } ?: "MATCH"))
            appendLine("  OAM:         " + (oamDiff?.let { "MISMATCH at $it (informational; phase-sensitive)" } ?: "MATCH"))
        }
        println(report)

        Assert.assertTrue(
            "CHR pattern data not captured — cannot validate banking. " +
            "Mesen2 chr=${mesen2.chr.size} bytes, Nestlin chr=${nestlin.chr.size} bytes.",
            nestlin.chr.size == 0x2000 && mesen2.chr.size == 0x2000
        )
        Assert.assertNull(
            "Palette RAM diverges from Mesen2 — wrong PRG bank executed (mapper 71 regression).\n$report",
            paletteDiff
        )
        // The mapper prgBank has to stay in [0, 16) for a 256KB ROM. This
        // catches the "no modulo" bug class.
        Assert.assertTrue(
            "Nestlin prgBank=$nestlinPrgBank is out of valid 16-bank range.\n$report",
            nestlinPrgBank in 0..15
        )
    }

    private fun firstDiff(a: IntArray, b: IntArray): String? {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            if (a[i] != b[i]) return "index %d: Nestlin=0x%02X Mesen2=0x%02X"
                .format(i, a[i], b[i])
        }
        if (a.size != b.size) return "length ${a.size} vs ${b.size}"
        return null
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
