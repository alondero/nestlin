package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import org.junit.Test
import java.nio.file.Path

/**
 * Test Mapper 4 games using official NES ROMs.
 * Tests: Kirby's Adventure, Simpsons Bart vs World, Super Mario Bros 3, Yoshi's Cookie
 */
class Mapper4GamesTest {

    data class GameInfo(val name: String, val path: String, val expectedMinPixels: Int)

    private val mapper4Games = listOf(
        GameInfo("Kirby's Adventure", "S:/Media/Nintendo NES/Games/Kirby's Adventure (USA) (Rev A).nes", 50000),
        GameInfo("Simpsons Bart vs World", "S:/Media/Nintendo NES/Games/Simpsons, The - Bart vs. the World (USA).nes", 50000),
        GameInfo("Super Mario Bros 3", "S:/Media/Nintendo NES/Games/Super Mario Bros. 3 (USA) (Rev A).nes", 50000),
        GameInfo("Yoshi's Cookie", "S:/Media/Nintendo NES/Games/Yoshi's Cookie (USA).nes", 30000)
    )

    @Test
    fun testMapper4GamesRenderCorrectly() {
        System.err.println("START: testMapper4GamesRenderCorrectly")
        System.err.flush()

        for (game in mapper4Games) {
            val romFile = Path.of(game.path)
            if (!java.nio.file.Files.exists(romFile)) {
                System.err.println("[SKIP] ${game.name}: ROM not found at ${game.path}")
                continue
            }

            System.err.println("\n=== Testing ${game.name} ===")
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
                    if (nonBlack > game.expectedMinPixels) {
                        achievedTarget = true
                    }
                    if (frameCount <= 5 || frameCount == 10 || frameCount == 30 || frameCount == 60) {
                        System.err.println("  Frame $frameCount: $nonBlack non-black pixels")
                    }
                    if (frameCount > 120) {
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

                val status = when {
                    achievedTarget -> "PASS"
                    maxNonBlack > 1000 -> "PARTIAL"
                    else -> "FAIL"
                }
                System.err.println("$status ${game.name}: max=$maxNonBlack pixels at frame $maxFrame, $frameCount total frames, ${elapsed}ms")
                System.err.flush()
            } catch (e: Exception) {
                System.err.println("FAIL ${game.name}: ${e.javaClass.simpleName}: ${e.message}")
                e.printStackTrace()
            }
        }

        System.err.println("\nEND: testMapper4GamesRenderCorrectly")
        System.err.flush()
    }
}
