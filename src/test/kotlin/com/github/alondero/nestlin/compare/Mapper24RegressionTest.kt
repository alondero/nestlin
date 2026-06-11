package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.gamepak.Mapper24
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Structured-state regression test for issue #58 (Mapper 24 / Konami VRC6a) using
 * Akumajou Densetsu (Castlevania III: Dracula's Curse, Japan) — the canonical
 * VRC6a boot oracle. CRCs:
 *   - Akumajou Densetsu (Japan).nes          0x2E93CE72  iNES 1.0
 *
 * Two-pronged verification (the Mapper10RegressionTest pattern, plus a CHR-only
 * Mesen2 oracle for the NMI/OAM-offset case):
 *  1. The boot code actually moves the PRG bank register (`prg16Bank` for
 *     $8000-$BFFF) during boot — proves the $8000 register-decode is wired and
 *     the chip isn't stuck at its reset state.
 *  2. Nestlin's CHR banks are byte-identical to Mesen2's at frame N — proves
 *     the full PRG-bank + CHR-bank + mirroring interaction is correct.
 *
 * Why CHR-only rather than the full render-output assert: Akumajou Densetsu's
 * NMI handler rewrites OAM between Mesen2's pre-NMI capture and Nestlin's
 * post-NMI capture, exactly like Don Doko Don (Mapper33RegressionTest). This
 * is a game-property, not a mapper bug — the CHR compare is the proof that
 * the banking itself is right.
 *
 * The ROM lives only in the NO-INTRO library (not in git). Override with
 * `NESTLIN_AKUMAJOU_DENSETSU_ROM`. Skipped when neither the ROM nor Mesen2 is
 * present, since CI runners won't have either.
 */
class Mapper24RegressionTest : MapperRegressionTestBase() {

    private val frameNumber = 60

    private fun akumajouRom(): Path = resolveRom(
        "NESTLIN_AKUMAJOU_DENSETSU_ROM",
        "S:/Media/Nintendo NES/Games/Akumajou Densetsu (Japan).nes"
    )

    @Test
    fun `vrc6a prg bank switches during akumajou densetsu boot`() {
        assertBankSwitchesDuringBoot(akumajouRom(), frameNumber, "prg16", Mapper24::class.java)
    }

    @Test
    @RequiresMesen2
    fun `akumajou densetsu chr banks match mesen2 at frame N`() {
        val rom = akumajouRom()
        val reportsDir = java.nio.file.Paths.get("build/reports/state-diffs/akumajou-densetsu-frame-$frameNumber")

        val nestlinState = NestlinStateCapturer.captureState(rom, frameNumber)
        val mesen2State = Mesen2StateCapturer.captureState(rom, frameNumber)

        java.nio.file.Files.createDirectories(reportsDir)
        val fullDiff = StateComparator.compare(nestlinState, mesen2State)
        StateComparator.writeReport(fullDiff, nestlinState, mesen2State, reportsDir.resolve("diff-report.txt"))

        // The mapper's job is CHR banking. OAM is reported alongside for human
        // inspection but is NOT asserted here — see the kdoc for the
        // cross-emulator NMI/OAM offset rationale.
        val chrDiffs = (nestlinState.chr.indices).filter {
            nestlinState.chr[it] != mesen2State.chr[it]
        }
        if (chrDiffs.isNotEmpty()) {
            val sample = chrDiffs.take(8).joinToString(", ") {
                "[0x%04X] N=0x%02X M=0x%02X".format(it, nestlinState.chr[it], mesen2State.chr[it])
            }
            throw org.opentest4j.AssertionFailedError(
                "CHR banks diverged from Mesen2 oracle in ${chrDiffs.size} byte(s): $sample\n" +
                    "This is a VRC6a bug — see: ${reportsDir.resolve("diff-report.txt")}"
            )
        }
        println("Akumajou Densetsu frame $frameNumber CHR banks: MATCH (8KB across all 8 1KB windows)")
    }
}
