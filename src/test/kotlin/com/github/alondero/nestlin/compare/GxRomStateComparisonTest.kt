package com.github.alondero.nestlin.compare

import org.junit.Assert
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Cross-emulator byte-compare regression for Mapper 66 (GxROM).
 *
 * The original GxROM implementation decoded the bank-select register with the
 * wrong bit fields (PRG bits 0-2 / CHR bits 3-4 instead of the correct
 * `xxPP xxCC` — PRG bits 4-5 / CHR bits 0-1). That mapped the wrong 32KB PRG
 * bank, so the CPU executed the wrong code at boot: Gumshoe / SMB+Duck Hunt
 * rendered a corrupt menu and Doraemon failed to boot at all.
 *
 * Following docs/TESTING_STRATEGY.md, this asserts at the *structured-state*
 * layer rather than with pixel diffs. The hard assertions are byte-compares of
 * the render data the banking bug actually corrupts:
 *
 *  - **CHR pattern data** ($0000-$1FFF, read through the live CHR bank). The
 *    "corrupt menu" symptom is wrong-CHR-bank corruption: the nametable tile
 *    *indices* are correct but the *pixel data* behind them is fetched from the
 *    wrong 8KB bank. That only shows up in the pattern bytes, so this is the
 *    primary guard (and the one that catches Gumshoe / SMB+Duck Hunt).
 *  - **Palette RAM** (32 bytes). Under a wrong PRG bank the boot code path
 *    diverges and uploads a different palette (catches Dragon Power).
 *
 * CPU registers, OAM, and PPUCTRL are deliberately NOT asserted: Mesen2 captures
 * at scanline 240 (pre-NMI) while Nestlin captures post-NMI, so sprite
 * Y-positions and PPUCTRL are rewritten between those scanlines and are not
 * phase-stable across the boundary (see the `mesen2-capturer-instant-offset`
 * memory note). They are printed for context only.
 *
 * ROMs come from the NO-INTRO library and are skipped when absent (e.g. CI).
 */
@RunWith(Parameterized::class)
class GxRomStateComparisonTest(
    private val label: String,
    private val romPathString: String,
    private val frameNumber: Int
) {

    companion object {
        private const val GAMES_DIR = "S:\\Media\\Nintendo NES\\Games"

        // All three have a static (non-CHR-animated) screen at frame 120, so their
        // CHR pattern data and palette are phase-stable across the capture boundary,
        // and all three diverge from Mesen2 under the wrong bit decode (verified
        // red-under-bug / green-under-fix). Gumshoe and Doraemon witness the CHR-bank
        // half (their CHR diverges); Dragon Power and Doraemon witness the PRG-bank
        // half (their palette diverges). SMB+Duck Hunt is intentionally excluded: its
        // title animates by swapping the CHR bank every frame in NMI, so a
        // single-frame CHR compare straddles a bank swap and is not phase-stable. It
        // is covered by the non-black render check in MapperVerificationTest instead.
        @JvmStatic
        @Parameterized.Parameters(name = "{0} @ frame {2}")
        fun data(): Array<Array<Any>> = arrayOf(
            arrayOf("Gumshoe", "$GAMES_DIR\\Gumshoe (USA, Europe).nes", 120),
            arrayOf("Dragon Power", "$GAMES_DIR\\Dragon Power (USA).nes", 120),
            arrayOf("Doraemon", "$GAMES_DIR\\Doraemon (Japan) (Rev A).nes", 120)
        )
    }

    @Test
    fun `render outputs match Mesen2`() {
        assumeTrue("Mesen2 not available", Mesen2StateCapturer.isMesen2Available())
        val romPath: Path = Paths.get(romPathString)
        assumeTrue("ROM not present: $romPath", Files.exists(romPath))

        val nestlin = NestlinStateCapturer.captureState(romPath, frameNumber)
        val mesen2 = Mesen2StateCapturer.captureState(romPath, frameNumber)

        val chrDiff = firstDiff(nestlin.chr, mesen2.chr)
        val paletteDiff = firstDiff(nestlin.paletteRam, mesen2.paletteRam)
        val oamDiff = firstDiff(nestlin.oam, mesen2.oam)

        val report = buildString {
            appendLine("$label (mapper 66) render-output compare at frame $frameNumber")
            appendLine("  PPU mask:    Nestlin=0x%02X  Mesen2=0x%02X".format(nestlin.ppu.mask, mesen2.ppu.mask))
            appendLine("  PPU control: Nestlin=0x%02X  Mesen2=0x%02X (informational; phase-sensitive)".format(nestlin.ppu.control, mesen2.ppu.control))
            appendLine("  CHR pattern: " + (chrDiff?.let { "MISMATCH at $it" } ?: "MATCH"))
            appendLine("  palette:     " + (paletteDiff?.let { "MISMATCH at $it" } ?: "MATCH"))
            appendLine("  OAM:         " + (oamDiff?.let { "MISMATCH at $it (informational; phase-sensitive)" } ?: "MATCH"))
        }
        println(report)

        Assert.assertTrue(
            "CHR pattern data not captured — cannot validate banking. Mesen2 chr=${mesen2.chr.size} bytes, Nestlin chr=${nestlin.chr.size} bytes.",
            nestlin.chr.size == 0x2000 && mesen2.chr.size == 0x2000
        )
        Assert.assertNull(
            "CHR pattern data diverges from Mesen2 — wrong CHR bank mapped (GxROM decode regression).\n$report",
            chrDiff
        )
        Assert.assertNull(
            "Palette RAM diverges from Mesen2 — wrong PRG bank executed (GxROM decode regression).\n$report",
            paletteDiff
        )
    }

    /** First index where the two byte-arrays differ, with both values, or null if identical. */
    private fun firstDiff(a: IntArray, b: IntArray): String? {
        val len = minOf(a.size, b.size)
        for (i in 0 until len) {
            if (a[i] != b[i]) return "index %d: Nestlin=0x%02X Mesen2=0x%02X".format(i, a[i], b[i])
        }
        if (a.size != b.size) return "length ${a.size} vs ${b.size}"
        return null
    }
}
