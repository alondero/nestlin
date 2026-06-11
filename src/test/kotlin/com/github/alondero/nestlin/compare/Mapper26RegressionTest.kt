package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.gamepak.Mapper26
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Structured-state regression test for issue #58 (Mapper 26 / Konami VRC6b) using
 * Esper Dream 2 - Aratanaru Tatakai (Japan) — the canonical VRC6b boot oracle.
 * CRCs:
 *   - Esper Dream 2 - Aratanaru Tatakai (Japan) (Translated En).nes  0x72344F1D  iNES 1.0
 *
 * Two-pronged verification, mirroring `Mapper24RegressionTest`:
 *  1. The boot code actually moves the PRG bank register (`prg16Bank` for
 *     $8000-$BFFF) during boot — proves the $8000 register-decode is wired
 *     AND that VRC6b's address-pin swap (A1→sub-bit 0, A0→sub-bit 1) is
 *     being honoured. If the swap were wrong, writes to $x001 would route
 *     to sub-register 0 instead of sub-register 1, and the boot code would
 *     page garbage into $8000-$BFFF — either crashing or running from
 *     a single fixed bank (caught by the bank-moves assertion).
 *  2. Nestlin's CHR banks are byte-identical to Mesen2's at frame N —
 *     proves the full PRG-bank + CHR-bank + mirroring interaction is correct
 *     under the VRC6b pin-out.
 *
 * Why CHR-only: same NMI/OAM offset caveat as Mapper24RegressionTest and
 * Mapper33RegressionTest — the game's NMI handler rewrites OAM between
 * Mesen2's pre-NMI capture and Nestlin's post-NMI capture.
 *
 * The ROM lives only in the NO-INTRO library (not in git). Override with
 * `NESTLIN_ESPER_DREAM2_ROM`. Skipped when neither the ROM nor Mesen2 is
 * present.
 */
class Mapper26RegressionTest : MapperRegressionTestBase() {

    private val frameNumber = 60

    private fun esperDream2Rom(): Path = resolveRom(
        "NESTLIN_ESPER_DREAM2_ROM",
        "S:/Media/Nintendo NES/Games/Esper Dream 2 - Aratanaru Tatakai (Japan) (Translated En).nes"
    )

    @Test
    fun `vrc6b prg bank switches during esper dream 2 boot`() {
        assertBankSwitchesDuringBoot(esperDream2Rom(), frameNumber, "prg16", Mapper26::class.java)
    }

    @Test
    @RequiresMesen2
    fun `esper dream 2 chr banks match mesen2 at frame N`() {
        val rom = esperDream2Rom()
        val reportsDir = java.nio.file.Paths.get("build/reports/state-diffs/esper-dream-2-frame-$frameNumber")

        val nestlinState = NestlinStateCapturer.captureState(rom, frameNumber)
        val mesen2State = Mesen2StateCapturer.captureState(rom, frameNumber)

        java.nio.file.Files.createDirectories(reportsDir)
        val fullDiff = StateComparator.compare(nestlinState, mesen2State)
        StateComparator.writeReport(fullDiff, nestlinState, mesen2State, reportsDir.resolve("diff-report.txt"))

        val chrDiffs = (nestlinState.chr.indices).filter {
            nestlinState.chr[it] != mesen2State.chr[it]
        }
        if (chrDiffs.isNotEmpty()) {
            val sample = chrDiffs.take(8).joinToString(", ") {
                "[0x%04X] N=0x%02X M=0x%02X".format(it, nestlinState.chr[it], mesen2State.chr[it])
            }
            throw org.opentest4j.AssertionFailedError(
                "CHR banks diverged from Mesen2 oracle in ${chrDiffs.size} byte(s): $sample\n" +
                    "This is a VRC6b bug — see: ${reportsDir.resolve("diff-report.txt")}"
            )
        }
        println("Esper Dream 2 frame $frameNumber CHR banks: MATCH (8KB across all 8 1KB windows)")
    }
}
