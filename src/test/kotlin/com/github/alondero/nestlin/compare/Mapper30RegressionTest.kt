package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.gamepak.Mapper30
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Structured-state regression for issue #87 (Mapper 30 / UNROM 512).
 *
 * Lizard is the canonical modern UNROM 512 homebrew test ROM: 512 KiB PRG,
 * 32 KiB CHR-RAM, and a static-screen title card that holds the bank
 * registers in known positions for an entire frame — exactly what
 * `MapperRegressionTestBase.assertRenderOutputMatchesMesen2` needs for a
 * byte-equality CHR compare. The Meowtrix demo is an alternate oracle if
 * Lizard is missing on a runner (override both paths via env vars below).
 *
 * Subtests skip (not fail) when the ROM is absent, since CI runners and
 * worktrees don't carry the user's NO-INTRO library. The `@RequiresMesen2`
 * gate on the render-output test is what excludes this whole class from
 * `./gradlew test` and includes it from `./gradlew testMesenComparison`.
 *
 * `MapperCoverageLintTest` enforces both: (1) every `Mapper*RegressionTest`
 * sits in the `mesen` lane, and (2) every `MapperN.kt` has both a `GamePak`
 * dispatch arm and a `## Mapper N` section in `MAPPER_SUPPORT.md`. Issue #87
 * adds all three pieces together.
 */
class Mapper30RegressionTest : MapperRegressionTestBase() {

    // Lizard holds its title-screen frame for ~3s of NMI ticks; capture
    // mid-title (frame 60 = ~1s in) where the PPU has settled and the
    // CHR banks are stable across the two emulators.
    private val frameNumber = 60

    private fun lizardRom(): Path = resolveRom(
        "NESTLIN_LIZARD_ROM",
        "S:/Media/Nintendo NES/Games/Lizard (USA) (Unl).nes"
    )

    @Test
    fun `unrom 512 PRG bank switches during lizard boot`() {
        assertBankSwitchesDuringBoot(lizardRom(), frameNumber, "prgBank", Mapper30::class.java)
    }

    @Test
    @RequiresMesen2
    fun `lizard render output matches mesen2 at frame N`() {
        assertRenderOutputMatchesMesen2(lizardRom(), frameNumber, "lizard")
    }
}