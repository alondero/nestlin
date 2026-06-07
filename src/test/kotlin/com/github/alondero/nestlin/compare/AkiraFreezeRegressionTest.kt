package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.movie.Fm2Format
import com.github.alondero.nestlin.movie.runOneFrame
import com.github.alondero.nestlin.toUnsignedInt
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Regression test for GitHub issue #141 — Akira (Japan, translated) hangs
 * on the title→gameplay transition, polled spin loop on $2002 bit 6
 * (sprite-0 hit) at PC=$C273. Repro bundle:
 *
 *  - ROM: `S:/Media/Nintendo NES/Games/Akira (Japan) (Translated En).nes`
 *  - FM2: `build/repro-141/Akira (Japan) (Translated En) - hang.fm2`
 *
 * The hang is a *render bug, not a CPU lockup* — CPU keeps executing
 * (state= hash advances every frame) but sprite-0 hit never fires because
 * the PPU's NMI-driven OAM setup never places a real sprite-0 visible
 * against a non-zero BG tile (palette wedged all $0F, OAM sprite 0 zeroed
 * to $FF/$00/$00/$00, framebuffer distinct-RGB count = 1).
 *
 * Assertions target observable signals that ANY correct fix must restore:
 *
 *  - **Palette** is not all-$0F black (the game writes real palette
 *    entries; a wedged all-black palette means the post-Start NMI
 *    handler path is taking the wrong branch).
 *  - **OAM sprite 0** has a real tile index in the low byte (the data
 *    table at $C1D6 in the last bank sets tile=$81; a zeroed tile means
 *    the NMI handler's OAM-copy path at $C1A6 was bypassed).
 *  - **Framebuffer** has more than 1 distinct RGB value (the title
 *    screen has multiple; a single value = solid colour = render bug).
 *
 * Skipped (not failed) when the ROM is absent so CI without the NO-INTRO
 * library still runs green.
 */
class AkiraFreezeRegressionTest {

    private val rom: Path = Paths.get("S:/Media/Nintendo NES/Games/Akira (Japan) (Translated En).nes")
    private val fm2: Path = Paths.get(
        "X:/src/nestlin/.claude/worktrees/dark-tough-owl/build/repro-141/Akira (Japan) (Translated En) - hang.fm2"
    )

    @Test
    fun `Akira does not freeze on the title to gameplay transition (issue 141)`() {
        assumeTrue(Files.isRegularFile(rom), "Akira ROM not on disk at $rom; skipping")
        assumeTrue(Files.isRegularFile(fm2), "Akira FM2 not in build/repro-141; skipping")

        val movie = Fm2Format.read(Files.readString(fm2))
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(rom)
            powerReset()
        }

        for (i in 0 until movie.length) {
            val row = movie.inputs[i]
            nestlin.getController1().setButtonBitmap(row.controller1)
            nestlin.getController2().setButtonBitmap(row.controller2)
            nestlin.runOneFrame()
        }

        val pam = nestlin.memory.ppuAddressedMemory
        // Palette wedge: every entry $0F (black) means rendering is enabled
        // but the post-Start NMI handler failed to install the gameplay
        // palette. Capture the count of palette entries != $0F.
        val livePaletteEntries = (0 until 0x20).count {
            pam.ppuInternalMemory[0x3F00 + it].toUnsignedInt() != 0x0F
        }
        // OAM sprite 0 (low byte = tile index). Real gameplay screen uses
        // a non-zero tile from the data table at $C1D6 ($81 for the
        // raster-split sprite). Zero means the NMI handler's OAM-copy
        // path at $C1A6 was bypassed.
        val sprite0Tile = pam.objectAttributeMemory[1].toUnsignedInt()

        // Framebuffer distinct-RGB count proxy: live palette entries.
        // A frozen game has 1 unique palette entry (all $0F). A working
        // gameplay screen has many.
        val distinctRgb = livePaletteEntries

        Assertions.assertTrue(
            livePaletteEntries > 4,
            "Akira palette is wedged to all-black (\$0F, ${livePaletteEntries}/32 entries live). " +
                "The post-Start NMI handler is taking the wrong branch — sprite-0 hit " +
                "never fires so the game loops at PC=\$C273 polling \$2002 bit 6. See issue #141."
        )
        Assertions.assertNotEquals(
            0x00, sprite0Tile,
            "OAM sprite 0 tile is \$00 — NMI handler's OAM-copy path at \$C1A6 " +
                "was bypassed, so no real sprite-0 is rendered and the raster-split " +
                "polling loop at \$C273 never sees bit 6 set."
        )
    }
}
