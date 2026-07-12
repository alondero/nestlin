package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.compare.Mesen2ReferenceRunner
import com.github.alondero.nestlin.compare.NestlinHeadlessRunner
import com.github.alondero.nestlin.compare.RequiresMesen2
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

/**
 * Capture side-by-side Nestlin vs Mesen2 screenshots of the two MMC6 ROMs
 * in the project library for PR #136 evidence.
 *
 * The PNGs land under `build/reports/screenshot-diffs/<slug>-frame-<n>/`
 * AND a flat mirror at `build/reports/mapper119-screenshots/`.
 *
 * 3 frames per ROM are captured so the reviewer can see the animation
 * phase difference between the two emulators (see KDoc on
 * [com.github.alondero.nestlin.compare.Mapper119RegressionTest] for the
 * ~60-frame boot-phase offset). Both emulators reach the title screen
 * by frame 60 and run from a cold power-on with no controller input.
 *
 * Lane membership + Mesen2 availability are gated by [RequiresMesen2]:
 * excluded from `./gradlew test`, included by `testMesenComparison`. This
 * is the standard project pattern (see `Mapper64KlaxMesen2ScreenshotTest`).
 *
 * The PNGs themselves are committed separately to the repo under
 * `docs/screenshots/mapper119/` so reviewers can eyeball them without
 * needing Mesen2 on their machine.
 */
@RequiresMesen2
class Mapper119ScreenshotCaptureTest {

    private val roms = listOf(
        "Pin-Bot (USA)" to Paths.get("S:/Media/Nintendo NES/Games/Pin-Bot (USA).nes"),
        "High Speed (USA)" to Paths.get("S:/Media/Nintendo NES/Games/High Speed (USA).nes")
    )
    // Capture 3 frames per ROM so reviewers can see the animation phase
    // difference. Both emulators reach the title screen by frame 60; the
    // boot animation cycles through CHR-bank states, so frames 120/180/240
    // catch different phases.
    private val frames = listOf(120, 180, 240)
    // Two output trees:
    //   - build/reports/screenshot-diffs/<slug>-frame-<n>/{nestlin,mesen2}.png
    //     matches the layout `ScreenshotComparisonTest` writes, so reviewers
    //     find the files at the same path they expect from that test.
    //   - build/reports/mapper119-screenshots/ keeps a single flat tree for
    //     the PR description to link to without per-frame subdirs.
    private val screenshotDiffsRoot = Paths.get("build/reports/screenshot-diffs")
    private val mapper119Root = Paths.get("build/reports/mapper119-screenshots")

    @Test
    fun `capture Nestlin + Mesen2 PNGs for both MMC6 ROMs`() {
        Files.createDirectories(mapper119Root)
        for ((name, rom) in roms) {
            val slug = name.lowercase().replace(" ", "-")
            for (frame in frames) {
                val perFrameDir = screenshotDiffsRoot.resolve("$slug-frame-$frame")
                Files.createDirectories(perFrameDir)
                val nestlinPng = perFrameDir.resolve("nestlin.png")
                val mesen2Png = perFrameDir.resolve("mesen2.png")
                NestlinHeadlessRunner.captureFrame(rom, frame, nestlinPng)
                Mesen2ReferenceRunner.captureFrame(rom, frame, mesen2Png)
                // Mirror into the flat tree for easy PR linking.
                NestlinHeadlessRunner.captureFrame(rom, frame, mapper119Root.resolve("$slug-nestlin-frame$frame.png"))
                Mesen2ReferenceRunner.captureFrame(rom, frame, mapper119Root.resolve("$slug-mesen2-frame$frame.png"))
                println("Captured $nestlinPng")
                println("Captured $mesen2Png")
            }
        }
    }
}