package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.compare.Mesen2ReferenceRunner
import com.github.alondero.nestlin.compare.Mesen2ScreenshotException
import com.github.alondero.nestlin.compare.Mesen2ExecutionException
import org.junit.jupiter.api.Assumptions.assumeTrue
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
 */
class Mapper64KlaxMesen2ScreenshotTest {

    @Test
    fun `mesen2 klax screenshot at frame 600`() {
        val mesen2Path = Mesen2ReferenceRunner.getMesen2Path()
        assumeTrue(Files.exists(mesen2Path), "Mesen2 not available at $mesen2Path")

        val rom = Paths.get("S:/Media/Nintendo NES/Games/Klax (USA) (Unl).nes")
        val outDir = Paths.get("build/reports/klax-screenshots")
        Files.createDirectories(outDir)
        val outPath = outDir.resolve("mesen2-klax-frame600.png")

        try {
            Mesen2ReferenceRunner.captureFrame(rom, 600, outPath)
            println("Saved $outPath")
        } catch (e: Mesen2ScreenshotException) {
            assumeTrue(false, e.message)
        } catch (e: Mesen2ExecutionException) {
            assumeTrue(false, e.message)
        }
    }
}
