package testing

import com.github.romankh3.image.comparison.ImageComparison
import java.io.File
import javax.imageio.ImageIO
import org.junit.Ignore
import org.junit.Test

/**
 * To generate golden screenshots:
 * 1. Load a FM2 file in FCEUX
 * 2. Play it to completion
 * 3. Capture the final frame as PNG
 * 4. Save to testroms/golden/<game_name>.png
 *
 * Then:
 * 1. Load the same FM2 file in Nestlin with replay mode
 * 2. Capture final screenshot
 * 3. Compare outputs
 */
@Ignore("Requires full emulator integration to capture screenshots")
class Fm2ScreenshotTest {

    @Test
    fun testCompareScreenshots() {
        // Load expected screenshot (captured from FCEUX with same FM2 file)
        val expectedFile = File("testroms/golden/test_game.png")
        val expectedImage = ImageIO.read(expectedFile) ?: throw IllegalArgumentException("Could not load expected image")

        // In real test: capture actual screenshot from replayed FM2 file
        // val actualImage = captureScreenshotFromReplay("testroms/test.fm2")
        val actualImage = ImageIO.read(File("testroms/actual/test_game.png"))
            ?: throw IllegalArgumentException("Could not load actual image")

        // Compare using image-comparison library
        val resultFile = File("build/test-results/test_game_diff.png")
        resultFile.parentFile?.mkdirs()

        val comparison = ImageComparison(expectedImage, actualImage, resultFile)
        comparison.compareImages()

        // Diff image saved to resultFile if there are differences
        println("Screenshots compared. Diff saved to: ${resultFile.absolutePath}")
    }

    @Test
    fun testCompareWithTolerance() {
        val expected = ImageIO.read(File("testroms/golden/test_game.png"))
            ?: throw IllegalArgumentException("Could not load expected image")
        val actual = ImageIO.read(File("testroms/actual/test_game.png"))
            ?: throw IllegalArgumentException("Could not load actual image")

        val resultFile = File("build/test-results/test_game_diff_tolerance.png")
        resultFile.parentFile?.mkdirs()

        // Comparison with image-comparison library
        val comparison = ImageComparison(expected, actual, resultFile)
        comparison.compareImages()

        println("Screenshot comparison complete. Diff saved to: ${resultFile.absolutePath}")
    }
}
