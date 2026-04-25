package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.ppu.Frame
import org.junit.Test
import java.nio.file.Path
import java.nio.file.Paths
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

/**
 * Capture screenshot of Kirby's Adventure at various frames.
 * This lets us see what's actually being rendered over time.
 */
class KirbyScreenshotTest {

    @Test
    fun captureKirbyScreenshot() {
        val romPath = Paths.get("testroms/kirby.nes")
        val outputPath = Paths.get("build/kirby-debug.png")

        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        var frameCount = 0
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                frameCount++
                System.err.println("Frame $frameCount captured")
                if (frameCount == 3) {
                    saveFrameAsPng(frame, outputPath)
                    System.err.println("Screenshot saved to $outputPath")
                    nestlin.stop()
                }
            }
        })

        nestlin.powerReset()
        nestlin.start()

        // Also print final state
        System.err.println("\n=== Final State ===")
        System.err.println("CPU PC: $${String.format("%04X", nestlin.cpu.registers.programCounter.toUnsignedInt())}")
        System.err.println("PPU $2000: ${String.format("%02X", nestlin.memory.ppuAddressedMemory.controller.register.toUnsignedInt())}")
        System.err.println("PPU $2001: ${String.format("%02X", nestlin.memory.ppuAddressedMemory.mask.register.toUnsignedInt())}")
    }

    @Test
    fun analyze633Pixels() {
        val romPath = Paths.get("testroms/kirby.nes")
        val outputPath = Paths.get("build/kirby-633-pixels.png")

        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        var frameCount = 0
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                frameCount++
                if (frameCount == 3) {
                    // Analyze where the 633 non-black pixels are
                    val pixelPositions = mutableListOf<Pair<Int, Int>>() // (x, y)
                    val pixelColors = mutableListOf<Int>()

                    for (y in 0 until 240) {
                        for (x in 0 until 256) {
                            val color = frame.scanlines[y][x]
                            if (color != 0x000000) {
                                pixelPositions.add(Pair(x, y))
                                pixelColors.add(color)
                            }
                        }
                    }

                    System.err.println("Frame $frameCount: ${pixelPositions.size} non-black pixels")
                    System.err.println("Pixel positions (first 20): ${pixelPositions.take(20)}")
                    System.err.println("Pixel colors (first 20): ${pixelColors.take(20).map { String.format("%06X", it) }}")

                    // Group by scanline to see pattern
                    val byScanline = pixelPositions.groupBy { it.second }
                    System.err.println("\nPixels per scanline:")
                    byScanline.entries.sortedBy { it.key }.forEach { (scanline, pixels) ->
                        System.err.println("  Scanline $scanline: ${pixels.size} pixels, x range ${pixels.minOf { it.first }}-${pixels.maxOf { it.first }}")
                    }

                    saveFrameAsPng(frame, outputPath)
                    nestlin.stop()
                }
            }
        })

        nestlin.powerReset()
        nestlin.start()
    }

    @Test
    fun captureKirbyFrames1to20() {
        val romPath = Paths.get("testroms/kirby.nes")
        val outputDir = Paths.get("build/kirby-frames")

        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        var frameCount = 0
        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                frameCount++
                val nonBlack = frame.scanlines.sumOf { line ->
                    line.count { it != 0x000000 }
                }
                System.err.println("Frame $frameCount: $nonBlack non-black pixels, mask=${String.format("%02X", nestlin.memory.ppuAddressedMemory.mask.register.toUnsignedInt())}")

                if (frameCount <= 5 || frameCount == 10 || frameCount == 20) {
                    saveFrameAsPng(frame, outputDir.resolve("frame_${frameCount.toString().padStart(3, '0')}.png"))
                }

                if (frameCount == 20) {
                    nestlin.stop()
                }
            }
        })

        nestlin.powerReset()
        nestlin.start()

        // Print final state
        System.err.println("\n=== Final State at frame 20 ===")
        System.err.println("CPU PC: $${String.format("%04X", nestlin.cpu.registers.programCounter.toUnsignedInt())}")
        System.err.println("PPU $2000 (ctrl): ${String.format("%02X", nestlin.memory.ppuAddressedMemory.controller.register.toUnsignedInt())}")
        System.err.println("PPU $2001 (mask): ${String.format("%02X", nestlin.memory.ppuAddressedMemory.mask.register.toUnsignedInt())}")
        System.err.println("PPU $2002 (status): ${String.format("%02X", nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt())}")

        val mapper = nestlin.memory.mapper
        if (mapper != null) {
            val snap = mapper.snapshot()
            if (snap != null) {
                System.err.println("CHR banks: R0=${snap.banks["chrBankR0"]}, R1=${snap.banks["chrBankR1"]}, R2=${snap.banks["chrBankR2"]}, R3=${snap.banks["chrBankR3"]}")
            }
        }
    }

    @Test
    fun compareKirbyWithTetris() {
        // Run Kirby for a few frames and capture state
        val kirbyPath = Paths.get("testroms/kirby.nes")
        val tetrisPath = Paths.get("testroms/tetris.nes")

        // Test Tetris (Mapper 0, known working)
        val tetris = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(tetrisPath)
        }

        var tetrisFrameCount = 0
        tetris.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                tetrisFrameCount++
                if (tetrisFrameCount == 3) {
                    val nonBlack = frame.scanlines.sumOf { line ->
                        line.count { it != 0x000000 }
                    }
                    System.err.println("Tetris frame 3: $nonBlack non-black pixels")
                    tetris.stop()
                }
            }
        })
        tetris.powerReset()
        tetris.start()

        // Test Kirby
        val kirby = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(kirbyPath)
        }

        var kirbyFrameCount = 0
        kirby.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                kirbyFrameCount++
                if (kirbyFrameCount == 3) {
                    val nonBlack = frame.scanlines.sumOf { line ->
                        line.count { it != 0x000000 }
                    }
                    System.err.println("Kirby frame 3: $nonBlack non-black pixels")
                    kirby.stop()
                }
            }
        })
        kirby.powerReset()
        kirby.start()
    }

    private fun saveFrameAsPng(frame: Frame, outputPath: Path) {
        val image = BufferedImage(256, 240, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 240) {
            for (x in 0 until 256) {
                image.setRGB(x, y, frame.scanlines[y][x])
            }
        }
        outputPath.parent.toFile().mkdirs()
        ImageIO.write(image, "PNG", outputPath.toFile())
    }
}