package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO

object NestlinHeadlessRunner {

    /**
     * Capture a single frame from a ROM and save as PNG.
     * @param romPath Path to the .nes ROM file
     * @param frameNumber Frame to capture (0 = first frame)
     * @param outputPath Where to save the PNG
     */
    fun captureFrame(romPath: Path, frameNumber: Int, outputPath: Path) {
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
            var frameCount = 0
            addFrameListener(object : FrameListener {
                override fun frameUpdated(frame: Frame) {
                    if (++frameCount == frameNumber) {
                        saveFrameAsPng(frame, outputPath)
                        stop()
                    }
                }
            })
        }
        nestlin.powerReset()
        nestlin.start()
    }

    /**
     * Capture multiple frames from the same ROM session.
     * Useful for capturing at different timestamps without restarting emulation.
     */
    fun captureFrames(romPath: Path, frames: List<Int>, outputDir: Path, baseName: String) {
        if (frames.isEmpty()) return

        val sortedFrames: List<Int> = frames.sorted()
        val outputPaths = sortedFrames.associateWith { f ->
            outputDir.resolve("${baseName}_frame${f}.png")
        }

        // Ensure output directory exists once before emulation
        Files.createDirectories(outputDir)

        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
            var frameCount = 0
            addFrameListener(object : FrameListener {
                override fun frameUpdated(frame: Frame) {
                    frameCount++
                    outputPaths[frameCount]?.let { path ->
                        saveFrameAsPng(frame, path)
                    }
                    if (frameCount == sortedFrames.last) {
                        stop()
                    }
                }
            })
        }
        nestlin.powerReset()
        nestlin.start()
    }

    private fun saveFrameAsPng(frame: Frame, outputPath: Path) {
        val image = BufferedImage(256, 240, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 240) {
            for (x in 0 until 256) {
                image.setRGB(x, y, frame.scanlines[y][x])
            }
        }
        Files.createDirectories(outputPath.parent)
        ImageIO.write(image, "PNG", outputPath.toFile())
    }
}