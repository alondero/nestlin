package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.gamepak.Mapper65
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Structured-state regression test for issue #133 (Mapper 65 / Irem H3001),
 * against *Spartan X 2 (Japan, translated)* — the actual mapper 65 game
 * in the local NO-INTRO library. (R-Type is named in the issue as the
 * worked example, and *The Adventures of Rad Gravity* / *Kickle Cubicle*
 * / *Infiltrator* / *Metal Storm* are also Irem H3001 titles — but their
 * iNES dumps in this library are labelled mapper 1 or mapper 4, not 65;
 * see the rom_info.py output. *Spartan X 2* is the only H3001 game whose
 * header actually says mapper 65 here.) Every H3001 game drives the
 * same `$8000`/`$A000`/`$C000` PRG and `$Bxxx` CHR registers, so a
 * green byte-compare on Spartan X 2 is meaningful evidence the chip is
 * correct.
 *
 * The bank-moves-during-boot guard asserts the `$8000` PRG register is
 * actually wired (Spartan X 2's loader pages multiple 8 KB PRG banks
 * into `$8000-$9FFF` to reach the second-stage init code), and the
 * render-output compare asserts the CHR banking, OAM, palette, and
 * PPU state byte-equal the Mesen2 oracle at frame 120.
 *
 * See [MapperRegressionTestBase] for why only render outputs (OAM,
 * palette, PPUCTRL, PPUMASK) are compared against Mesen2 — the
 * sub-frame capture offset (Mesen2 pre-NMI vs Nestlin post-NMI) makes
 * CPU registers / cycle count inherently non-comparable.
 */
class Mapper65RegressionTest : MapperRegressionTestBase() {

    // Frame 120 is the first stable title-screen frame for Spartan X 2:
    // the intro has finished and the title logo is fully drawn but
    // sprite-0-driven text animations haven't started swapping CHR banks
    // mid-frame (which would break the byte-compare the way animated
    // GxROM screens do — see Mapper 33 / Mapper 66 lessons).
    private val frameNumber = 120

    private fun spartanX2Rom(): Path = resolveRom(
        "NESTLIN_SPARTAN_X2_ROM",
        "S:/Media/Nintendo NES/Games/Spartan X 2 (Japan) (Translated En).nes"
    )

    @Test
    fun `irem h3001 prg bank 0 switches during spartan x 2 boot`() {
        // The cheap "did the mapper do anything" guard. If $8000 (the
        // PRG-0 register) never changes during boot, the chip isn't
        // wiring its PRG banking and the real-game state diff would
        // diverge from the first frame.
        assertBankSwitchesDuringBoot(spartanX2Rom(), frameNumber, "prgBank0", Mapper65::class.java)
    }

    @Test
    @RequiresMesen2
    fun `spartan x 2 render output matches mesen2 at frame N`() {
        assertRenderOutputMatchesMesen2(spartanX2Rom(), frameNumber, "spartan-x2")
    }
}
