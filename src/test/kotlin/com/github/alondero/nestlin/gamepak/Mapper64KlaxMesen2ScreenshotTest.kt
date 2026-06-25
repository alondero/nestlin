package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.compare.Mesen2ReferenceRunner
import com.github.alondero.nestlin.compare.RequiresMesen2
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Visual diagnostic: capture a Klax screenshot from Mesen2 at the same
 * frame Nestlin's Mapper64KlaxScreenshotTest captured, so we can see
 * what Klax is *supposed* to look like vs what Nestlin actually shows.
 *
 * If Mesen2 shows the title screen and Nestlin shows "green zeros",
 * the divergence is in Nestlin's mapper / PPU / data-bus behaviour.
 * If Mesen2 also shows "green zeros", the ROM itself isn't reaching
 * the title screen at frame 600 with no controller input.
 *
 * Lane + Mesen2 availability are gated by [RequiresMesen2] (GH #44):
 * excluded from `./gradlew test`, included by `./gradlew testMesenComparison`,
 * loud SKIP naming the resolved path when Mesen2 is missing (or hard FAIL
 * under `NESTLIN_REQUIRE_MESEN2` strict mode). Mesen2-execution errors
 * mid-capture propagate as hard FAILs instead of being swallowed.
 */
@RequiresMesen2
class Mapper64KlaxMesen2ScreenshotTest {

    @Test
    fun `mesen2 klax screenshot at frame 600`() {
        val rom = Paths.get("S:/Media/Nintendo NES/Games/Klax (USA) (Unl).nes")
        val outDir = Paths.get("build/reports/klax-screenshots")
        Files.createDirectories(outDir)
        val outPath = outDir.resolve("mesen2-klax-frame600.png")

        // Let Mesen2ScreenshotException / Mesen2ExecutionException propagate
        // as hard FAILs (see GH #44 — these are real harness errors, not
        // "no oracle" situations).
        Mesen2ReferenceRunner.captureFrame(rom, 600, outPath)
        println("Saved $outPath")
    }
}
