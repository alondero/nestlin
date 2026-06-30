package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.gamepak.Mapper228
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test
import java.nio.file.Path

/**
 * Structured-state regression test for issue #140 (Mapper 228 / Action 52).
 *
 * Two-pronged, following the `Mapper10RegressionTest` worked example:
 *  1. A banking register moves during boot to the game-selection menu — proves
 *     the address-decoded bank-select is wired and the chip isn't frozen at its
 *     `InitMapper` state. Because Action 52's menu may page PRG *or* CHR, the
 *     guard accepts movement in any of `prgBank0` / `prgBank1` / `chrBank`
 *     (the Mapper33 multi-bank-guard idiom).
 *  2. Render output is byte-identical to Mesen2 at the menu frame.
 *
 * The game-selection menu is the only meaningful oracle: the 52 games are
 * mostly short and buggy on real hardware too, so per-game state isn't a stable
 * target. The ROM lives only in the NO-INTRO library (not in git); override with
 * `NESTLIN_ACTION52_ROM`. Skipped (not failed) when the ROM / Mesen2 is absent.
 */
class Mapper228RegressionTest : MapperRegressionTestBase() {

    private val frameNumber = 120

    private fun action52Rom(): Path = resolveRom(
        "NESTLIN_ACTION52_ROM",
        "S:/Media/Nintendo NES/Games/Action 52 (USA) (Rev A) (Unl).nes"
    )

    @Test
    fun `action 52 banking moves during boot to the menu`() {
        val rom = action52Rom()
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(rom)
        }
        nestlin.powerReset()
        val mapper = nestlin.memory.mapper as? Mapper228
            ?: error("Expected Mapper228 for Action 52; got ${nestlin.memory.mapper?.javaClass?.simpleName}")

        val prg0Seen = linkedSetOf<Int>()
        val prg1Seen = linkedSetOf<Int>()
        val chrSeen = linkedSetOf<Int>()
        var frameCount = 0
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                val banks = mapper.snapshot().banks
                prg0Seen.add(banks["prgBank0"] ?: -1)
                prg1Seen.add(banks["prgBank1"] ?: -1)
                chrSeen.add(banks["chrBank"] ?: -1)
                if (++frameCount >= frameNumber) nestlin.stop()
            }
        })
        nestlin.start()

        Assertions.assertTrue(
            prg0Seen.size > 1 || prg1Seen.size > 1 || chrSeen.size > 1,
            "Action 52 banking never changed during boot " +
                "(prgBank0=$prg0Seen, prgBank1=$prg1Seen, chrBank=$chrSeen) — " +
                "the address-decoded bank-select may not be wired."
        )
    }

    @Test
    @RequiresMesen2
    fun `action 52 menu render output matches mesen2 at frame N`() {
        assertRenderOutputMatchesMesen2(action52Rom(), frameNumber, "action-52")
    }
}
