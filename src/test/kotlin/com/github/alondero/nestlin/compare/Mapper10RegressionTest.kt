package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.gamepak.Mapper10
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Structured-state regression test for issue #49 (Mapper 10 / MMC4), against the
 * Fire Emblem Gaiden ROM (NO-INTRO library only — override with
 * NESTLIN_FIRE_EMBLEM_ROM). See MapperRegressionTestBase for the pattern and for
 * WHY only render outputs are compared against Mesen2.
 */
class Mapper10RegressionTest : MapperRegressionTestBase() {

    private val frameNumber = 120

    private fun fireEmblemRom(): Path = resolveRom(
        "NESTLIN_FIRE_EMBLEM_ROM",
        "S:/Media/Nintendo NES/Games/Fire Emblem Gaiden (Japan) (Translated En).nes"
    )

    @Test
    fun `mmc4 prg bank switches during fire emblem gaiden boot`() {
        assertBankSwitchesDuringBoot(fireEmblemRom(), frameNumber, "prgBank", Mapper10::class.java)
    }

    @Test
    @RequiresMesen2
    fun `fire emblem gaiden render output matches mesen2 at frame N`() {
        assertRenderOutputMatchesMesen2(fireEmblemRom(), frameNumber, "fire-emblem-gaiden")
    }
}
