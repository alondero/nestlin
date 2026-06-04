package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.toUnsignedInt
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO
import kotlin.streams.toList

/**
 * Regression for the Big Nose the Caveman (mapper 71) early boot hang (issue #88).
 *
 * Big Nose enables NMI *and* waits for vblank by polling $2002 (`LDA $2002 / BPL`),
 * while its NMI handler does `BIT $2002` (which clears the vblank flag). This relies on
 * the 6502's ~1-instruction NMI latency: the in-flight poll reads the just-set flag
 * (and that read suppresses the NMI) before the NMI is serviced. Without that latency,
 * Nestlin dispatched the NMI immediately, its handler cleared the flag first, and the
 * poll spun forever — rendering was never re-enabled, leaving a permanent black screen
 * after the Codemasters logo (~frame 266). Same root cause as the Micro Machines
 * race-start hang.
 *
 * After the fix the game progresses past the wait and re-enables rendering.
 */
class BigNoseHangTest {

    @Test
    fun `Big Nose re-enables rendering after the Codemasters logo (no NMI-vs-poll hang)`() {
        val rom = locateRom() ?: run { assumeTrue(false, "Big Nose ROM not found"); return }

        val nestlin = Nestlin().apply { config.speedThrottlingEnabled = false }
        val outDir = Paths.get("build/big-nose")
        Files.createDirectories(outDir)

        var frame = 0
        var lastFrameRef: Frame? = null
        var renderOnAfterTransition = false

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(f: Frame) {
                frame++
                lastFrameRef = f
                val mask = nestlin.memory.ppuAddressedMemory.mask.register.toUnsignedInt()
                // Bits 3/4 = show background / show sprites. The transition off the logo
                // happens ~frame 266; the title must turn rendering back on afterwards.
                if (frame > 300 && (mask and 0x18) != 0) renderOnAfterTransition = true
            }
        })
        nestlin.load(rom)
        nestlin.powerReset()

        val target = 600
        var guard = target.toLong() * 80_000
        while (frame < target && guard-- > 0) nestlin.stepCpuCycle()

        lastFrameRef?.let { savePng(it, outDir.resolve("frame600.png")) }
        val nonBlack = lastFrameRef?.let { countNonBlack(it) } ?: 0
        println("[BigNose] frame=$frame renderOnAfterTransition=$renderOnAfterTransition nonBlackPixels=$nonBlack")

        Assertions.assertTrue(renderOnAfterTransition
        , "Big Nose never re-enabled rendering after the Codemasters logo — stuck in the " +
            "NMI-vs-\$2002-poll hang (black screen).")
    }

    private fun countNonBlack(frame: Frame): Int {
        var n = 0
        for (y in 0 until 240) for (x in 0 until 256) if ((frame.scanlines[y][x] and 0xFFFFFF) != 0) n++
        return n
    }

    private fun savePng(frame: Frame, outputPath: Path) {
        val image = BufferedImage(256, 240, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 240) for (x in 0 until 256) image.setRGB(x, y, frame.scanlines[y][x])
        Files.createDirectories(outputPath.parent)
        ImageIO.write(image, "PNG", outputPath.toFile())
    }

    private fun locateRom(): Path? {
        System.getenv("NESTLIN_BIG_NOSE_ROM")?.let {
            val p = Paths.get(it); if (Files.exists(p)) return p
        }
        val libs = listOf("S:/Media/Nintendo NES/Games", "X:/src/nestlin/testroms")
        for (lib in libs) {
            val dir = Paths.get(lib)
            if (!Files.isDirectory(dir)) continue
            Files.list(dir).use { stream ->
                return stream.toList().firstOrNull {
                    val n = it.fileName.toString().lowercase()
                    n.endsWith(".nes") && n.contains("big nose") && n.contains("caveman")
                }
            }
        }
        return null
    }
}
