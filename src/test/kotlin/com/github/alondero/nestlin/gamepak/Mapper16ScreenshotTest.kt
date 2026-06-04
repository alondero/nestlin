package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO

/**
 * Visual diagnostic: capture a screenshot of the Bandai FCG games at frame N
 * so we can eyeball whether the boot is progressing. Output to
 * `build/reports/bandai-fcg-screenshots/<rom>-frameN.png`.
 */
class Mapper16ScreenshotTest {

    @Test
    fun `crayon shin-chan screenshot at frame 60`() {
        captureFrame(
            Paths.get("S:/Media/Nintendo NES/Games/Crayon Shin-chan - Ora to Poi Poi (Japan).nes"),
            "crayon-shin-chan", 60
        )
    }

    @Test
    fun `dragon ball screenshot at frame 60`() {
        captureFrame(
            Paths.get("S:/Media/Nintendo NES/Games/Dragon Ball - Daimaou Fukkatsu (Japan) (Translated En).nes"),
            "dragon-ball", 60
        )
    }

    @Test
    fun `crayon shin-chan screenshot at frame 300`() {
        captureFrame(
            Paths.get("S:/Media/Nintendo NES/Games/Crayon Shin-chan - Ora to Poi Poi (Japan).nes"),
            "crayon-shin-chan", 300
        )
    }

    @Test
    fun `dragon ball screenshot at frame 300`() {
        captureFrame(
            Paths.get("S:/Media/Nintendo NES/Games/Dragon Ball - Daimaou Fukkatsu (Japan) (Translated En).nes"),
            "dragon-ball", 300
        )
    }

    @Test
    fun `dragon ball screenshot at frame 1500`() {
        captureFrame(
            Paths.get("S:/Media/Nintendo NES/Games/Dragon Ball - Daimaou Fukkatsu (Japan) (Translated En).nes"),
            "dragon-ball", 1500
        )
    }

    private fun captureFrame(rom: Path, label: String, frame: Int) {
        assumeTrue("ROM not found at $rom", Files.exists(rom))

        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(rom)
        }
        nestlin.powerReset()

        var frameCount = 0
        var captured: Frame? = null
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(f: Frame) {
                if (++frameCount == frame) {
                    captured = f
                    nestlin.stop()
                }
            }
        })
        nestlin.start()

        val png = captured!!
        val img = BufferedImage(256, 240, BufferedImage.TYPE_INT_ARGB)
        for (y in 0 until 240) {
            for (x in 0 until 256) {
                val argb = png.scanlines[y][x]
                img.setRGB(x, y, argb)
            }
        }

        val outDir = Paths.get("build/reports/bandai-fcg-screenshots")
        Files.createDirectories(outDir)
        val outPath = outDir.resolve("$label-frame$frame.png")
        ImageIO.write(img, "png", outPath.toFile())
        println("Saved $outPath")
    }
}
