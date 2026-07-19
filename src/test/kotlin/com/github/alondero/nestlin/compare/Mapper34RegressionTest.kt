package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.gamepak.Mapper34
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Structured-state regression test for issue #233 (Mapper 34 BNROM / NINA-001).
 *
 * Two games, two variants, one test class — the cheap "did the mapper do
 * anything" guard is run for both, plus a CHR-byte-compare against Mesen2
 * (the mapper's job is PRG/CHR banking; OAM/PPUCTRL/PPUMASK are out of
 * scope here because the mesen lane has a pre-existing Nestlin-vs-Mesen2
 * OAM attribute-byte divergence that is NOT a mapper-34 bug).
 *
 * Games tested:
 *  1. **Deadly Towers (USA)** — plain iNES, 8 KB CHR → BNROM. Asserts the
 *     32 KB PRG bank register actually pages during boot (proves the
 *     BNROM-style `$8000-$FFFF` decode is wired).
 *  2. **Impossible Mission II (USA) (Unl)** — plain iNES, >8 KB CHR →
 *     NINA-001 via the heuristic. Asserts `$7FFE`/`$7FFF` CHR-bank
 *     register paging during boot (proves the NINA-001 register decode
 *     is wired).
 *
 * Each variant also gets a Mesen2 CHR byte-compare at frame 60 — the
 * decisive evidence the variant's CHR banking matches Mesen2. The CHR-only
 * comparison follows the `Mapper22RegressionTest` / `Mapper24RegressionTest`
 * pattern; the full OAM/palette/PPUCTRL/PPUMASK byte-compare from
 * `MapperRegressionTestBase.assertRenderOutputMatchesMesen2` is *not* used
 * here because Nestlin's OAM attribute-byte handling differs from Mesen2
 * (Nestlin preserves bits 2-4 of the attribute as the game wrote them;
 * Mesen2 masks them to 0). That divergence is unrelated to mapper 34 and
 * pre-dates the NINA-001 work — see memory entry
 * `mesen-comparison-lane-preexisting-failures-2026-07-17`.
 *
 * Both ROMs live only in the NO-INTRO library on Adam's machine — override
 * with `NESTLIN_DEADLY_TOWERS_ROM` / `NESTLIN_IMPOSSIBLE_MISSION_II_ROM`.
 */
class Mapper34RegressionTest : MapperRegressionTestBase() {

    /** Boot deep enough to hit both the boot-screen music and the title. */
    private val frameNumber = 60

    private fun deadlyTowersRom(): Path = resolveRom(
        "NESTLIN_DEADLY_TOWERS_ROM",
        "S:/Media/Nintendo NES/Games/Deadly Towers (USA).nes"
    )

    private fun impossibleMissionIiRom(): Path = resolveRom(
        "NESTLIN_IMPOSSIBLE_MISSION_II_ROM",
        "S:/Media/Nintendo NES/Games/Impossible Mission II (USA) (Unl).nes"
    )

    // ------------------------------------------------------------------
    // BNROM — Deadly Towers
    // ------------------------------------------------------------------

    @Test
    fun `BNROM prg bank switches during Deadly Towers boot`() {
        // The 'prgBank' bank key is the BNROM snapshot key (see Mapper34.snapshot()).
        // If the BNROM decode is broken, this snapshot key never changes.
        assertBankSwitchesDuringBoot(deadlyTowersRom(), frameNumber, "prgBank", Mapper34::class.java)
    }

    @Test
    @RequiresMesen2
    fun `Deadly Towers chr banks match mesen2 at frame N`() {
        val rom = deadlyTowersRom()
        val reportsDir = java.nio.file.Paths.get("build/reports/state-diffs/deadly-towers-frame-$frameNumber")

        val nestlinState = NestlinStateCapturer.captureState(rom, frameNumber)
        val mesen2State = Mesen2StateCapturer.captureState(rom, frameNumber)

        java.nio.file.Files.createDirectories(reportsDir)
        val fullDiff = StateComparator.compare(nestlinState, mesen2State)
        StateComparator.writeReport(fullDiff, nestlinState, mesen2State, reportsDir.resolve("diff-report.txt"))

        // CHR byte-equality is the decisive mapper-34 evidence (the mapper's
        // job is PRG/CHR banking). OAM/palette/PPUCTRL are reported alongside
        // for human inspection but NOT asserted — see the class kdoc on the
        // pre-existing OAM attribute-byte divergence.
        val chrDiffs = nestlinState.chr.indices.filter {
            nestlinState.chr[it] != mesen2State.chr[it]
        }
        if (chrDiffs.isNotEmpty()) {
            val sample = chrDiffs.take(8).joinToString(", ") {
                "[0x%04X] N=0x%02X M=0x%02X".format(it, nestlinState.chr[it], mesen2State.chr[it])
            }
            throw org.opentest4j.AssertionFailedError(
                "CHR banks diverged from Mesen2 oracle in ${chrDiffs.size} byte(s): $sample\n" +
                    "This is a BNROM CHR-banking bug — see: ${reportsDir.resolve("diff-report.txt")}"
            )
        }
        println("Deadly Towers frame $frameNumber CHR banks: MATCH (8KB BNROM CHR window)")
    }

    // ------------------------------------------------------------------
    // NINA-001 — Impossible Mission II
    // ------------------------------------------------------------------

    @Test
    fun `NINA-001 chr bank 1 switches during Impossible Mission II boot`() {
        // The NINA-001 snapshot exposes 'chrBank1' (the high 4KB window
        // controlled by $7FFF). Impossible Mission II actively pages this
        // window to switch between the title screen graphics and the
        // status-bar sprites, so a multi-value trace is the cheapest
        // "is the NINA-001 register wired" smoke.
        assertBankSwitchesDuringBoot(
            impossibleMissionIiRom(),
            frameNumber,
            "chrBank1",
            Mapper34::class.java
        )
    }

    @Test
    @RequiresMesen2
    fun `Impossible Mission II chr banks match mesen2 at frame N`() {
        val rom = impossibleMissionIiRom()
        val reportsDir = java.nio.file.Paths.get("build/reports/state-diffs/impossible-mission-ii-frame-$frameNumber")

        val nestlinState = NestlinStateCapturer.captureState(rom, frameNumber)
        val mesen2State = Mesen2StateCapturer.captureState(rom, frameNumber)

        java.nio.file.Files.createDirectories(reportsDir)
        val fullDiff = StateComparator.compare(nestlinState, mesen2State)
        StateComparator.writeReport(fullDiff, nestlinState, mesen2State, reportsDir.resolve("diff-report.txt"))

        // CHR byte-equality across the two NINA-001 4KB windows — proves
        // both $7FFE (window 0) and $7FFF (window 1) bank selects match Mesen2.
        val chrDiffs = nestlinState.chr.indices.filter {
            nestlinState.chr[it] != mesen2State.chr[it]
        }
        if (chrDiffs.isNotEmpty()) {
            val sample = chrDiffs.take(8).joinToString(", ") {
                "[0x%04X] N=0x%02X M=0x%02X".format(it, nestlinState.chr[it], mesen2State.chr[it])
            }
            throw org.opentest4j.AssertionFailedError(
                "CHR banks diverged from Mesen2 oracle in ${chrDiffs.size} byte(s): $sample\n" +
                    "This is a NINA-001 CHR-banking bug — see: ${reportsDir.resolve("diff-report.txt")}"
            )
        }
        println("Impossible Mission II frame $frameNumber CHR banks: MATCH (two 4KB NINA-001 windows)")
    }
}