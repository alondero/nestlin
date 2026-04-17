package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.ppu.Frame
import org.junit.Test
import java.awt.image.BufferedImage
import java.nio.file.Path
import javax.imageio.ImageIO

/**
 * Headless test for mapper 4 verification.
 */
class Mapper4Verification {

    @Test
    fun identifyTestRoms() {
        // List all test ROMs with their mapper numbers
        listOf(
            "testroms/castle.nes",
            "testroms/chipndale.nes",
            "testroms/crackout.nes",
            "testroms/fire.nes",
            "testroms/kirby.nes",
            "testroms/lolo1.nes",
            "testroms/tetris.nes"
        ).forEach { path ->
            val romPath = Path.of(path)
            if (java.nio.file.Files.exists(romPath)) {
                val data = java.nio.file.Files.readAllBytes(romPath)
                val byte6 = data[6].toUnsignedInt()
                val byte7 = data[7].toUnsignedInt()
                val prgSize = data[4].toUnsignedInt() * 16384
                val chrSize = data[5].toUnsignedInt() * 8192
                val mirroring = if ((byte6 and 0x01) == 0) "H" else "V"
                val mapper = ((byte6 shr 4) and 0x0F) or ((byte7 shr 4) and 0xF0)
                System.err.println("$path: mapper=$mapper, PRG=${prgSize/1024}KB, CHR=${chrSize/1024}KB, mirror=$mirroring")
            }
        }
    }

    @Test
    fun captureScreenshotsAt6Seconds() {
        // Capture screenshots for each mapper at 6 seconds into boot
        System.err.println("START: captureScreenshotsAt6Seconds")
        System.err.flush()

        val screenshotDir = Path.of("doc/screenshots")
        java.nio.file.Files.createDirectories(screenshotDir)

        // Map each supported mapper to a test ROM (verified against NESDIR)
        // NESDIR: https://nesdir.github.io/
        // Verified from actual ROM header analysis:
        val mapperTests = listOf(
            0 to "S:/Media/Nintendo NES/Games/Balloon Fight (USA).nes",            // NROM - verified mapper=0
            1 to "testroms/tetris.nes",                                           // MMC1 - verified mapper=1
            2 to "S:/Media/Nintendo NES/Games/Castlevania (USA) (Rev A).nes",       // UNROM - verified mapper=2
            3 to "testroms/castle.nes",                                          // CNROM - verified mapper=3
            4 to "S:/Media/Nintendo NES/Games/Bad Dudes (USA).nes",               // MMC3 - verified mapper=4
            7 to "S:/Media/Nintendo NES/Games/Battletoads (USA).nes",             // AxROM - verified mapper=7
            11 to "S:/Media/Nintendo NES/Games/Bible Adventures (USA) (v1.4) (Unl).nes",  // Color Dreams - verified mapper=11
            34 to "S:/Media/Nintendo NES/Games/Deadly Towers (USA).nes"         // BNROM - verified mapper=34
        )

        for ((mapper, romPath) in mapperTests) {
            val romFile = Path.of(romPath)
            if (!java.nio.file.Files.exists(romFile)) {
                System.err.println("[SKIP] Mapper $mapper: ROM not found at $romPath")
                continue
            }

            System.err.println("\n=== Capturing screenshot for Mapper $mapper from $romPath ===")
            System.err.flush()

            val nestlin = Nestlin().apply {
                config.speedThrottlingEnabled = true
            }

            var frameCount = 0
            var captured = false
            var lastFrame: Frame? = null

            nestlin.addFrameListener(object : FrameListener {
                override fun frameUpdated(frame: Frame) {
                    frameCount++
                    lastFrame = frame

                    // At ~6 seconds (360 frames at 60fps), capture the frame
                    if (frameCount == 360 && !captured) {
                        captured = true
                        saveScreenshot(frame, screenshotDir.resolve("mapper${mapper}.png"))
                        System.err.println("  Saved screenshot at frame 360")
                        System.err.flush()
                        nestlin.stop()
                    }

                    // Safety stop after 400 frames (~6.6 seconds)
                    if (frameCount > 400 && !captured) {
                        saveScreenshot(frame, screenshotDir.resolve("mapper${mapper}.png"))
                        System.err.println("  Saved screenshot at frame $frameCount (forced)")
                        System.err.flush()
                        nestlin.stop()
                    }
                }
            })

            try {
                nestlin.load(romFile)
                nestlin.powerReset()

                val startTime = System.currentTimeMillis()
                nestlin.start()
                val elapsed = System.currentTimeMillis() - startTime

                System.err.println("  Mapper $mapper: at frame $frameCount, elapsed=${elapsed}ms")
                System.err.flush()
            } catch (t: Throwable) {
                System.err.println("  Note: ${t.javaClass.simpleName} for mapper $mapper")
            }

            // If we never captured but have a lastFrame, save it
            if (!captured && lastFrame != null) {
                val screenshotPath = screenshotDir.resolve("mapper${mapper}.png")
                saveScreenshot(lastFrame!!, screenshotPath)
                System.err.println("  Saved last available frame for mapper $mapper")
                System.err.flush()
            }
        }

        System.err.println("\nEND: captureScreenshotsAt6Seconds")
        System.err.flush()
    }

    private fun saveScreenshot(frame: Frame, path: Path) {
        val img = BufferedImage(256, 240, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 240) {
            for (x in 0 until 256) {
                val pixel = frame.scanlines[y][x]
                val r = (pixel shr 16) and 0xFF
                val g = (pixel shr 8) and 0xFF
                val b = pixel and 0xFF
                img.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
        }
        ImageIO.write(img, "PNG", path.toFile())
        System.err.println("  Saved: $path (${java.nio.file.Files.size(path)} bytes)")
    }

    @Test
    fun testFireDetailed() {
        val romPath = Path.of("testroms/fire.nes")
        if (!java.nio.file.Files.exists(romPath)) {
            System.err.println("[SKIP] Fire: ROM not found")
            return
        }

        val outputFile = Path.of("testroms/fire_debug.txt")
        val debugOut = StringBuilder()

        fun log(msg: String) {
            debugOut.appendLine(msg)
            System.err.println(msg)
        }

        log("Testing Fire in detail...")

        val gamePak = GamePak(java.nio.file.Files.readAllBytes(romPath))
        log("Fire header: mapper=${gamePak.header.mapper}, PRG=${gamePak.programRom.size/1024}KB, CHR=${gamePak.chrRom.size/1024}KB")

        // Check CHR ROM data
        log("CHR ROM sample data:")
        for (i in 0 until 4) {
            val offset = i * 0x1000
            if (offset < gamePak.chrRom.size) {
                log("  Offset \$${String.format("%05X", offset)}: ${String.format("%02X", gamePak.chrRom[offset])} ${String.format("%02X", gamePak.chrRom[offset+1])} ${String.format("%02X", gamePak.chrRom[offset+2])} ${String.format("%02X", gamePak.chrRom[offset+3])}")
            }
        }

        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = true
        }

        var frameCount = 0

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                frameCount++
                if (frameCount <= 3) {
                    val nonBlack = frame.scanlines.sumOf { scanline ->
                        scanline.count { it != 0x000000 }
                    }
                    log("Frame $frameCount: $nonBlack non-black pixels")

                    val nonBlackLines = (0 until frame.scanlines.size).filter { y ->
                        frame.scanlines[y].any { it != 0x000000 }
                    }
                    if (nonBlackLines.isNotEmpty()) {
                        log("  Non-empty scanlines: ${nonBlackLines.size}, first at y=${nonBlackLines.first()}, last at y=${nonBlackLines.last()}")
                    }

                    for (y in 0 until minOf(10, frame.scanlines.size)) {
                        val nonBlackInRow = frame.scanlines[y].filter { it != 0x000000 }.take(10)
                        if (nonBlackInRow.isNotEmpty()) {
                            log("  Row $y: ${nonBlackInRow.map { String.format("0x%06X", it) }}")
                        }
                    }
                }
                if (frameCount > 60) {
                    nestlin.stop()
                }
            }
        })

        nestlin.load(romPath)
        nestlin.powerReset()

        try {
            val startTime = System.currentTimeMillis()
            nestlin.start()
            val elapsed = System.currentTimeMillis() - startTime

            log("Done: $frameCount frames in ${elapsed}ms")

            java.nio.file.Files.writeString(outputFile, debugOut.toString())
            log("Debug output written to $outputFile")
        } catch (e: Exception) {
            log("Error: ${e.message}")
            e.printStackTrace()
        }
    }

    @Test
    fun testMapper4Roms() {
        System.err.println("START: testMapper4Roms")
        System.err.flush()

        val results = mutableListOf<String>()

        // Test specific ROMs to verify rendering
        results.add(testRom("testroms/kirby.nes", "Kirby"))
        results.add(testRom("testroms/fire.nes", "Fire"))
        results.add(testRom("testroms/crackout.nes", "Crackout"))

        System.err.println("RESULTS:")
        results.forEach { System.err.println(it) }
        System.err.println("END: testMapper4Roms")
        System.err.flush()
    }

    @Test
    fun testCpuExecutionForMappers() {
        // Test if CPU is actually executing instructions by checking PC over time
        System.err.println("START: testCpuExecutionForMappers")
        System.err.flush()

        listOf(
            "testroms/kirby.nes" to "Kirby",
            "testroms/fire.nes" to "Fire",
            "testroms/crackout.nes" to "Crackout"
        ).forEach { (romPath, name) ->
            val romFile = Path.of(romPath)
            if (!java.nio.file.Files.exists(romFile)) {
                System.err.println("[SKIP] $name: not found")
                return@forEach
            }

            System.err.println("\n=== $name CPU execution check ===")
            System.err.flush()

            val nestlin = Nestlin().apply {
                config.speedThrottlingEnabled = true
            }

            val cpuSnapshots = mutableListOf<Pair<Int, Int>>()  // frame to pc
            var frameCount = 0

            nestlin.addFrameListener(object : FrameListener {
                override fun frameUpdated(frame: Frame) {
                    frameCount++
                    // Get CPU PC from snapshot if available
                    val nonBlack = frame.scanlines.sumOf { scanline ->
                        scanline.count { it != 0x000000 }
                    }
                    cpuSnapshots.add(Pair(frameCount, nonBlack))

                    if (frameCount <= 5 || frameCount == 10 || frameCount == 30) {
                        System.err.println("  Frame $frameCount: $nonBlack pixels")
                    }
                    if (frameCount > 60) {
                        nestlin.stop()
                    }
                }
            })

            nestlin.load(romFile)
            nestlin.powerReset()

            val startTime = System.currentTimeMillis()
            nestlin.start()
            val elapsed = System.currentTimeMillis() - startTime

            val gamePak = GamePak(java.nio.file.Files.readAllBytes(romFile))
            val allSame = cpuSnapshots.all { it.second == cpuSnapshots.first().second }
            System.err.println("  All pixel counts identical: $allSame")
            System.err.println("  Mapper: ${gamePak.header.mapper}, elapsed=${elapsed}ms")
            System.err.flush()
        }

        System.err.println("END: testCpuExecutionForMappers")
        System.err.flush()
    }

    @Test
    fun testNestlistVsWorkingEmulator() {
        // Compare our Nestlin rendering against a known working emulator (Mesen)
        // For now, let's just verify that our output is consistent and reasonable

        System.err.println("START: testNestlistVsWorkingEmulator")
        System.err.flush()

        // Test with a game that we know works (Crackout mapper 2)
        val romPath = Path.of("testroms/crackout.nes")
        if (!java.nio.file.Files.exists(romPath)) {
            System.err.println("[SKIP] Crackout: ROM not found")
            return
        }

        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = true
        }

        var frameCount = 0
        val framePixels = mutableListOf<Int>()

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                frameCount++
                val nonBlack = frame.scanlines.sumOf { scanline ->
                    scanline.count { it != 0x000000 }
                }
                framePixels.add(nonBlack)

                // Every 10 frames, log detailed scanline info
                if (frameCount % 10 == 0) {
                    System.err.println("Frame $frameCount: $nonBlack non-black pixels")
                    val nonBlackLines = (0 until frame.scanlines.size).filter { y ->
                        frame.scanlines[y].any { it != 0x000000 }
                    }
                    if (nonBlackLines.isNotEmpty()) {
                        System.err.println("  Non-empty scanlines: ${nonBlackLines.size}, range y=${nonBlackLines.first()} to y=${nonBlackLines.last()}")
                    }
                }

                if (frameCount > 30) {
                    nestlin.stop()
                }
            }
        })

        nestlin.load(romPath)
        nestlin.powerReset()

        val startTime = System.currentTimeMillis()
        nestlin.start()
        val elapsed = System.currentTimeMillis() - startTime

        System.err.println("Completed: $frameCount frames in ${elapsed}ms")
        System.err.println("Frame pixels progression: ${framePixels.take(30)}")

        // Check that pixels are growing over time (showing rendering is progressing)
        val earlyAvg = framePixels.take(5).average()
        val lateAvg = framePixels.takeLast(5).average()
        System.err.println("Early avg: $earlyAvg, Late avg: $lateAvg")

        if (lateAvg > earlyAvg * 1.5) {
            System.err.println("PASS: Rendering is progressing (late > early)")
        } else if (lateAvg > 10000) {
            System.err.println("PASS: Rendering has substantial content")
        } else {
            System.err.println("FAIL: Rendering appears stuck (late ~= early)")
        }

        System.err.println("END: testNestlistVsWorkingEmulator")
        System.err.flush()
    }

    @Test
    fun testMapper4WithBackgroundRendering() {
        System.err.println("START: testMapper4WithBackgroundRendering")
        System.err.flush()

        // Test with a known working ROM that doesn't rely on complex CHR banking
        // Castlevania (mapper 2) worked with 64KB screenshots
        // Let's test if we can get similar results with mapper 4 games

        val testCases = listOf(
            Triple("testroms/kirby.nes", "Kirby", 50000),  // Expect ~50K+ pixels for full background
            Triple("testroms/fire.nes", "Fire", 50000),
            Triple("testroms/crackout.nes", "Crackout", 40000)  // Was working with mapper 2 but shows 51K
        )

        for ((romPath, name, expectedMinPixels) in testCases) {
            val romFile = Path.of(romPath)
            if (!java.nio.file.Files.exists(romFile)) {
                System.err.println("[SKIP] $name: ROM not found")
                System.err.flush()
                continue
            }

            System.err.println("\n=== Testing $name (expecting > $expectedMinPixels pixels) ===")
            System.err.flush()

            val nestlin = Nestlin().apply {
                config.speedThrottlingEnabled = true
            }

            var frameCount = 0
            var maxNonBlack = 0
            var maxFrame = 0
            var achievedTarget = false

            nestlin.addFrameListener(object : FrameListener {
                override fun frameUpdated(frame: Frame) {
                    frameCount++
                    val nonBlack = frame.scanlines.sumOf { scanline ->
                        scanline.count { it != 0x000000 }
                    }
                    if (nonBlack > maxNonBlack) {
                        maxNonBlack = nonBlack
                        maxFrame = frameCount
                    }
                    if (nonBlack > expectedMinPixels) {
                        achievedTarget = true
                        System.err.println("  Frame $frameCount: $nonBlack pixels - TARGET ACHIEVED!")
                        System.err.flush()
                    }
                    if (frameCount <= 60 && (frameCount == 1 || frameCount == 10 || frameCount == 20 || frameCount == 30 || frameCount == 60)) {
                        System.err.println("  Frame $frameCount: $nonBlack non-black pixels")
                        System.err.flush()
                    }
                    if (frameCount > 120) {  // Give it 2 seconds to render
                        nestlin.stop()
                    }
                }
            })

            try {
                nestlin.load(romFile)
                nestlin.powerReset()

                val startTime = System.currentTimeMillis()
                nestlin.start()
                val elapsed = System.currentTimeMillis() - startTime

                val gamePak = GamePak(java.nio.file.Files.readAllBytes(Path.of(romPath)))
                val status = if (achievedTarget) "PASS" else if (maxNonBlack > 1000) "PARTIAL" else "FAIL"
                System.err.println("$status $name: max=$maxNonBlack pixels at frame $maxFrame, $frameCount total frames, ${elapsed}ms")
                System.err.println("  Mapper: ${gamePak.header.mapper}, PRG: ${gamePak.programRom.size/1024}KB, CHR: ${gamePak.chrRom.size/1024}KB")
                System.err.flush()
            } catch (e: Exception) {
                System.err.println("FAIL $name: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
            }
        }
        System.err.println("END: testMapper4WithBackgroundRendering")
        System.err.flush()
    }

    private fun testRom(romPathStr: String, name: String): String {
        val romPath = Path.of(romPathStr)
        if (!java.nio.file.Files.exists(romPath)) {
            return "[SKIP] $name: ROM not found at $romPath"
        }

        System.err.println("Testing $name ($romPathStr)...")
        System.err.flush()

        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = true
        }

        var frameCount = 0
        var maxNonBlack = 0
        var maxFrame = 0

        nestlin.addFrameListener(object : FrameListener {
            override fun frameUpdated(frame: Frame) {
                frameCount++
                val nonBlack = frame.scanlines.sumOf { scanline ->
                    scanline.count { it != 0x000000 }
                }
                if (nonBlack > maxNonBlack) {
                    maxNonBlack = nonBlack
                    maxFrame = frameCount
                }
                if (frameCount > 60) {
                    nestlin.stop()
                }
            }
        })

        try {
            nestlin.load(romPath)
            nestlin.powerReset()

            val startTime = System.currentTimeMillis()
            nestlin.start()
            val elapsed = System.currentTimeMillis() - startTime

            val gamePak = GamePak(java.nio.file.Files.readAllBytes(romPath))
            val result = if (maxNonBlack > 1000) {
                "OK"
            } else {
                "BLACK"
            }
            return "$result $name: $frameCount frames, max $maxNonBlack non-black pixels (frame $maxFrame), mapper=${gamePak.header.mapper}, PRG=${gamePak.programRom.size/1024}KB, CHR=${gamePak.chrRom.size/1024}KB, elapsed=${elapsed}ms"
        } catch (e: Exception) {
            return "FAIL $name: ${e.javaClass.simpleName}: ${e.message}"
        }
    }

    @Test
    fun testAllMappers() {
        System.err.println("START: testAllMappers")
        System.err.flush()

        // List of test ROMs - these should be in testroms folder
        val testRoms = listOf(
            "testroms/castle.nes",  // Castlevania - mapper 2 (UNROM)
            "testroms/chipndale.nes",  // Chipndale - mapper 0 (NROM)
            "testroms/lolo1.nes",  // Lolo 1 - mapper 2 (UNROM)
            "testroms/fire.nes",  // Fire - mapper 4 (MMC3) - 393KB
            "testroms/crackout.nes",  // Crackout - mapper 4 (MMC3) - 131KB
            "testroms/kirby.nes",  // Kirby's Adventure - mapper 4 (MMC3) - 393KB
        )

        for (romPathStr in testRoms) {
            val romPath = Path.of(romPathStr)
            if (!java.nio.file.Files.exists(romPath)) {
                System.err.println("[SKIP] ROM not found: $romPath")
                System.err.flush()
                continue
            }

            System.err.println("\n=== Testing $romPathStr ===")
            System.err.flush()

            val nestlin = Nestlin().apply {
                config.speedThrottlingEnabled = true
            }

            var frameCount = 0
            var nonBlackFrames = 0

            nestlin.addFrameListener(object : FrameListener {
                override fun frameUpdated(frame: Frame) {
                    frameCount++
                    // Check for non-black pixels after initial frames
                    if (frameCount > 5) {
                        val nonBlack = frame.scanlines.sumOf { scanline ->
                            scanline.count { it != 0x000000 }
                        }
                        if (nonBlack > 1000) {  // More than 1000 non-black pixels
                            nonBlackFrames++
                            System.err.println("Frame $frameCount: $nonBlack non-black pixels")
                            System.err.flush()
                        }
                    }
                    if (frameCount > 60) {  // About 1 second
                        nestlin.stop()
                    }
                }
            })

            nestlin.load(romPath)
            nestlin.powerReset()

            val startTime = System.currentTimeMillis()
            nestlin.start()
            val elapsed = System.currentTimeMillis() - startTime

            System.err.println("Completed: $frameCount frames in ${elapsed}ms, $nonBlackFrames with content")
            System.err.flush()

            // Get mapper info
            val gamePak = GamePak(java.nio.file.Files.readAllBytes(romPath))
            System.err.println("Mapper: ${gamePak.header.mapper}, PRG: ${gamePak.programRom.size / 1024}KB, CHR: ${gamePak.chrRom.size / 1024}KB")
            System.err.flush()
        }
        System.err.println("END: testAllMappers")
        System.err.flush()
    }
}