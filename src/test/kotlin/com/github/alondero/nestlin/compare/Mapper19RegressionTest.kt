package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.gamepak.Mapper19
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Structured-state regression test for issue #59 (Mapper 19 / Namco 163),
 * against the Kaijuu Monogatari (translated "Megami Tensei II" — Atlus
 * Namco-published) ROM from the NO-INTRO library.
 *
 * The ROM lives only on Adam's machine — override with
 * `NESTLIN_KAIJUU_MONOGATARI_ROM`. The base class skips (not fails) the
 * boot-movement test when the ROM is absent, since CI runners won't have
 * the NO-INTRO library. The render-output test is `@RequiresMesen2` and
 * uses the same skip semantics, with strict mode available via
 * `NESTLIN_REQUIRE_MESEN2=1`.
 *
 * Two-pronged verification, mirroring `Mapper10RegressionTest` and
 * `Mapper33RegressionTest`:
 *  1. The boot code actually moves a PRG bank register during the first
 *     N frames — proves the 8KB-bank decode is wired and the chip is
 *     responding to the game's writes, not stuck at its reset state.
 *  2. Nestlin's render output (OAM, palette, PPUCTRL, PPUMASK) is
 *     byte-identical to Mesen2's at a chosen frame — proves the full
 *     PRG + CHR + mirroring + IRQ interaction is correct, not just the
 *     individual bank-decodes.
 *
 * See `MapperRegressionTestBase`'s KDoc for why only render outputs are
 * compared (sub-frame offset between Mesen2's pre-NMI capture and
 * Nestlin's post-NMI capture makes CPU regs/cycleCount incomparable).
 */
class Mapper19RegressionTest : MapperRegressionTestBase() {

    /**
     * Frame 60 is the static title screen: N163 games are usually
     * idle on the title, so the rendering is stable. Picked low so
     * the test doesn't take too long; bump if a future regression
     * needs more boot time to reach the title.
     */
    private val frameNumber = 60

    private fun kaijuuMonogatariRom(): Path = resolveRom(
        "NESTLIN_KAIJUU_MONOGATARI_ROM",
        "S:/Media/Nintendo NES/Games/Kaijuu Monogatari (Japan) (Translated En).nes"
    )

    @Test
    fun `namco 163 prg bank switches during kaijuu monogatari boot`() {
        // 128KB PRG = 16 × 8KB banks; the boot code must page the
        // $8000/$A000/$C000/$E000 windows off the last fixed bank.
        // Any one of prgBank0/1/2 moving is enough to prove the
        // $8000/$A000/$C000 register decode is wired.
        assertBankSwitchesDuringBoot(
            kaijuuMonogatariRom(), frameNumber, "prgBank0", Mapper19::class.java
        )
    }

    @Test
    @RequiresMesen2
    fun `kaijuu monogatari render output matches mesen2 at frame N`() {
        assertRenderOutputMatchesMesen2(
            kaijuuMonogatariRom(), frameNumber, "kaijuu-monogatari"
        )
    }
}
