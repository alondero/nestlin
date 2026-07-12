package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.gamepak.Mapper22
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Structured-state regression test for issue #137 (Mapper 22 / Konami VRC2a)
 * using TwinBee 3 - Poko Poko Daimaou (Japan) — the only VRC2a title in
 * the local NO-INTRO library. CRCs:
 *   - TwinBee 3 - Poko Poko Daimaou (Japan) (Translated En).nes  0x96529C68
 *
 * Other VRC2a games named in issue #137 (Takeshi no Chōsenjō, Akumajō
 * Special: Boku Dracula-kun) are not in the local library; the latter is
 * also Mapper 23 / VRC4 in the available dumps, not VRC2a.
 *
 * Two-pronged verification (Mapper10RegressionTest pattern):
 *  1. The boot code actually moves the `prg0` bank register during boot —
 *     proves the $8000 register-decode is wired and the chip isn't stuck
 *     at its reset state.
 *  2. Nestlin's CHR banks are byte-identical to Mesen2's at frame N —
 *     proves the full PRG-bank + CHR-bank + mirroring interaction is
 *     correct, including the VRC2a `page >>= 1` CHR shift quirk.
 *
 * The ROM lives only in the NO-INTRO library (not in git). Override with
 * `NESTLIN_TWINBEE3_ROM`. Skipped when neither the ROM nor Mesen2 is
 * present, since CI runners won't have either.
 */
class Mapper22RegressionTest : MapperRegressionTestBase() {

    private val frameNumber = 60

    private fun twinbee3Rom(): Path = resolveRom(
        "NESTLIN_TWINBEE3_ROM",
        "S:/Media/Nintendo NES/Games/TwinBee 3 - Poko Poko Daimaou (Japan) (Translated En).nes"
    )

    @Test
    fun `vrc2a prg bank switches during TwinBee 3 boot`() {
        assertBankSwitchesDuringBoot(twinbee3Rom(), frameNumber, "prg0", Mapper22::class.java)
    }

    @Test
    @RequiresMesen2
    fun `TwinBee 3 chr banks match mesen2 at frame N`() {
        val rom = twinbee3Rom()
        val reportsDir = java.nio.file.Paths.get("build/reports/state-diffs/twinbee3-frame-$frameNumber")

        val nestlinState = NestlinStateCapturer.captureState(rom, frameNumber)
        val mesen2State = Mesen2StateCapturer.captureState(rom, frameNumber)

        java.nio.file.Files.createDirectories(reportsDir)
        val fullDiff = StateComparator.compare(nestlinState, mesen2State)
        StateComparator.writeReport(fullDiff, nestlinState, mesen2State, reportsDir.resolve("diff-report.txt"))

        // The mapper's job is CHR banking. OAM is reported alongside for human
        // inspection but is NOT asserted here — see the kdoc on
        // MapperRegressionTestBase for the cross-emulator NMI/OAM offset
        // rationale. CHR byte-equality is the proof the VRC2a banking + shift
        // quirk (`page >>= 1`) match Mesen2.
        val chrDiffs = (nestlinState.chr.indices).filter {
            nestlinState.chr[it] != mesen2State.chr[it]
        }
        if (chrDiffs.isNotEmpty()) {
            val sample = chrDiffs.take(8).joinToString(", ") {
                "[0x%04X] N=0x%02X M=0x%02X".format(it, nestlinState.chr[it], mesen2State.chr[it])
            }
            throw org.opentest4j.AssertionFailedError(
                "CHR banks diverged from Mesen2 oracle in ${chrDiffs.size} byte(s): $sample\n" +
                    "This is a VRC2a bug — see: ${reportsDir.resolve("diff-report.txt")}"
            )
        }
        println("TwinBee 3 frame $frameNumber CHR banks: MATCH (8KB across all 8 1KB windows)")
    }
}