package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.gamepak.Mapper2
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Structured-state regression for issue #232 using Paperboy 2, a 256 KiB UOROM.
 *
 * OAM is not asserted because unused sprite bytes retain different power-on
 * values in the two emulators. CHR RAM is stable across the capture offset and
 * proves that the correctly banked program populated the same pattern data.
 */
class Mapper2RegressionTest : MapperRegressionTestBase() {

    private val frameNumber = 120

    private fun paperboy2Rom(): Path = resolveRom(
        "NESTLIN_PAPERBOY2_ROM",
        "S:/Media/Nintendo NES/Games/Paperboy 2 (USA).nes"
    )

    @Test
    fun `uorom PRG bank switches during paperboy 2 boot`() {
        assertBankSwitchesDuringBoot(paperboy2Rom(), frameNumber, "prgBank", Mapper2::class.java)
    }

    @Test
    @RequiresMesen2
    fun `paperboy 2 CHR RAM matches mesen2 at frame N`() {
        assertChrMatchesMesen2(paperboy2Rom(), frameNumber, "paperboy-2")
    }
}
