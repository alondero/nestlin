package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.ppu.Frame
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger

/**
 * Headless verification test for mapper implementations.
 *
 * Runs the emulator for a short period and checks that frames are rendered
 * (not black screens or major glitches).
 *
 * This test harness is separate from the production UI code.
 */
class MapperVerificationTest {

    /**
     * Verifies that a ROM with the given mapper produces valid graphical output.
     */
    private fun verifyMapper(romPath: Path, mapperId: Int, durationSeconds: Int = 15) {
        val nonBlackFrameCount = AtomicInteger(0)
        val frameCountRef = intArrayOf(0)
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = true
            load(romPath)
            var frameCount = 0
            addFrameListener(object : FrameListener {
                override fun frameUpdated(frame: Frame) {
                    frameCountRef[0] = frameCount
                    // Skip first frame (often black during init)
                    // Capture at 3s, 6s, 9s, 12s, 15s (every 180 frames at 60fps)
                    if (frameCount > 0 && frameCount % 180 == 0) {
                        val nonBlackPixels = frame.scanlines
                            .sumOf { scanline -> scanline.count { it != 0x000000 } }
                        if (nonBlackPixels > 0) {
                            nonBlackFrameCount.incrementAndGet()
                            println("[MapperVerification] Mapper $mapperId: frame $frameCount has $nonBlackPixels non-black pixels")
                        } else {
                            println("[MapperVerification] Mapper $mapperId: frame $frameCount is all black")
                        }
                    }
                    // Run for duration + buffer
                    if (frameCount > (durationSeconds + 2) * 60) stop()
                    frameCount++
                }
            })
        }
        nestlin.powerReset()
        nestlin.start()

        println("[MapperVerification] Mapper $mapperId: nonBlackFrameCount = ${nonBlackFrameCount.get()} (at frame ${frameCountRef[0]})")
        assert(nonBlackFrameCount.get() >= 3) {
            "Expected at least 3 non-black frames, got ${nonBlackFrameCount.get()}. " +
                    "Mapper $mapperId may not be working correctly."
        }
    }

    @Test
    fun `Mapper 3 - Paperboy runs without black screen`() {
        val romPath = Path.of("S:\\Media\\Nintendo NES\\Games\\Paperboy (USA).nes")
        if (!Files.exists(romPath)) {
            println("[SKIP] ROM not found: $romPath")
            return
        }

        // Paperboy uses NINA-003 (mapper variant compatible with CNROM)
        val gamePak = com.github.alondero.nestlin.gamepak.GamePak(Files.readAllBytes(romPath))
        println("[MapperVerification] Paperboy: PRG ROM = ${gamePak.programRom.size / 1024}KB, " +
                "CHR ROM = ${gamePak.chrRom.size / 1024}KB, mapper = ${gamePak.header.mapper}")

        verifyMapper(romPath, mapperId = 3)
    }

    @Test
    fun `Mapper 2 - Castlevania runs without black screen`() {
        val romPath = Path.of("S:\\Media\\Nintendo NES\\Games\\Castlevania (USA) (Rev A).nes")
        if (!Files.exists(romPath)) {
            println("[SKIP] ROM not found: $romPath")
            return
        }

        val gamePak = com.github.alondero.nestlin.gamepak.GamePak(Files.readAllBytes(romPath))
        val prgKB = gamePak.programRom.size / 1024
        println("[MapperVerification] Castlevania: PRG ROM = ${prgKB}KB, " +
                "CHR ROM = ${gamePak.chrRom.size / 1024}KB, mapper = ${gamePak.header.mapper}")

        // Castlevania has 128KB PRG which uses UNROM-style PRG banking
        // Our Mapper 2 now implements UNROM with 16KB PRG bank switching
        println("[MapperVerification] Castlevania uses UNROM variant with 16KB PRG banking")

        verifyMapper(romPath, mapperId = 2)
    }

    @Test
    fun `Mapper 7 - Marble Madness runs without black screen`() {
        val romPath = Path.of("S:\\Media\\Nintendo NES\\Games\\Marble Madness (USA).nes")
        if (!Files.exists(romPath)) {
            println("[SKIP] ROM not found: $romPath")
            return
        }

        val gamePak = GamePak(Files.readAllBytes(romPath))
        println("[MapperVerification] Marble Madness: PRG ROM = ${gamePak.programRom.size / 1024}KB, " +
                "CHR RAM = ${gamePak.chrRom.size / 1024}KB (CHR RAM, no CHR ROM), mapper = ${gamePak.header.mapper}")

        verifyMapper(romPath, mapperId = 7)
    }

    @Test
    fun `Mapper 7 - R C Pro-Am runs without black screen`() {
        val romPath = Path.of("S:\\Media\\Nintendo NES\\Games\\R.C. Pro-Am (USA) (Rev A).nes")
        if (!Files.exists(romPath)) {
            println("[SKIP] ROM not found: $romPath")
            return
        }

        val gamePak = GamePak(Files.readAllBytes(romPath))
        println("[MapperVerification] R.C. Pro-Am: PRG ROM = ${gamePak.programRom.size / 1024}KB, " +
                "CHR RAM = ${gamePak.chrRom.size / 1024}KB, mapper = ${gamePak.header.mapper}")

        verifyMapper(romPath, mapperId = 7)
    }
}
