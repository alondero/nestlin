package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO

/**
 * Visual diagnostic for Klax (mapper 64 / RAMBO-1). Captures screenshots at
 * several frames of the attract sequence. After the RAMBO-1 banking fix
 * (4-bit register select, R15 third PRG bank, 1 KB CHR mode) Klax renders
 * its attract screens correctly — frame 240 shows the "TENGEN PRESENTS"
 * logo, frame 600 the credits — where it previously showed a blank screen.
 *
 * Note: frames are written as ARGB with zero alpha, so a plain viewer
 * composites them over white; convert to RGB to see the (mostly black)
 * background. The authoritative check is the byte-level CHR/palette match
 * in `Mapper64KlaxRegressionTest`.
 *
 * Output to `build/reports/klax-screenshots/klax-frame<N>.png`.
 */
class Mapper64KlaxScreenshotTest {

    @Test
    fun `klax screenshots at frames 30, 60, 120, 240, 600`() {
        val rom = Paths.get("S:/Media/Nintendo NES/Games/Klax (USA) (Unl).nes")
        assumeTrue(Files.exists(rom), "Klax ROM not found at $rom")

        val outDir = Paths.get("build/reports/klax-screenshots")
        Files.createDirectories(outDir)

        val targetFrames = listOf(30, 60, 120, 240, 600)

        for (frame in targetFrames) {
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

            val png = captured ?: continue
            val img = BufferedImage(256, 240, BufferedImage.TYPE_INT_ARGB)
            for (y in 0 until 240) {
                for (x in 0 until 256) {
                    val argb = png.scanlines[y][x]
                    img.setRGB(x, y, argb)
                }
            }

            val outPath = outDir.resolve("klax-frame$frame.png")
            ImageIO.write(img, "png", outPath.toFile())
            println("Saved $outPath")
        }
    }
}
