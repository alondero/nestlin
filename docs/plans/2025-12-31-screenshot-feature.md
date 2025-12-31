# Screenshot Feature Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Implement an on-demand screenshot feature that captures the current emulator frame and saves it to disk with a keyboard shortcut, allowing progress documentation after changes.

**Architecture:** Create a `ScreenshotManager` utility class that handles file I/O and image encoding, then integrate it into `Application.kt` with a keyboard shortcut (S key) to trigger captures. Screenshots are saved with timestamps to a `screenshots/` directory at the project root, making them easy to access and commit.

**Tech Stack:**
- Kotlin for implementation
- JavaFX Canvas rendering (already in use)
- Java BufferedImage and ImageIO for PNG encoding
- JUnit + Hamkrest for unit testing

---

## Task 1: Create ScreenshotManager Utility Class

**Files:**
- Create: `src/main/kotlin/com/github/alondero/nestlin/ui/ScreenshotManager.kt`
- Test: `src/test/kotlin/com/github/alondero/nestlin/ui/ScreenshotManagerTest.kt`

**Step 1: Write the failing test for directory creation**

```kotlin
package com.github.alondero.nestlin.ui

import org.hamkrest.should.shouldMatch
import org.hamkrest.containsString
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.nio.file.Files
import java.nio.file.Paths

class ScreenshotManagerTest {
    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun `should create screenshots directory if it does not exist`() {
        val screenshotsDir = tempFolder.root.toPath().resolve("screenshots")
        val manager = ScreenshotManager(screenshotsDir)

        Files.exists(screenshotsDir) shouldMatch true
    }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.github.alondero.nestlin.ui.ScreenshotManagerTest.should create screenshots directory if it does not exist" -v
```

Expected: FAIL with "class ScreenshotManager not found"

**Step 3: Write minimal ScreenshotManager implementation**

```kotlin
package com.github.alondero.nestlin.ui

import java.nio.file.Files
import java.nio.file.Path

class ScreenshotManager(private val screenshotsDir: Path) {
    init {
        Files.createDirectories(screenshotsDir)
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "com.github.alondero.nestlin.ui.ScreenshotManagerTest.should create screenshots directory if it does not exist" -v
```

Expected: PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/com/github/alondero/nestlin/ui/ScreenshotManager.kt src/test/kotlin/com/github/alondero/nestlin/ui/ScreenshotManagerTest.kt
git commit -m "feat: create ScreenshotManager with directory initialization"
```

---

## Task 2: Add Screenshot Saving with Timestamps

**Files:**
- Modify: `src/main/kotlin/com/github/alondero/nestlin/ui/ScreenshotManager.kt`
- Modify: `src/test/kotlin/com/github/alondero/nestlin/ui/ScreenshotManagerTest.kt`

**Step 1: Write the failing test for screenshot filename generation**

Add this test to ScreenshotManagerTest:

```kotlin
@Test
fun `should generate screenshot filename with timestamp`() {
    val screenshotsDir = tempFolder.root.toPath().resolve("screenshots")
    val manager = ScreenshotManager(screenshotsDir)

    val filename = manager.generateScreenshotFilename()
    filename shouldMatch containsString("screenshot-")
    filename shouldMatch containsString(".png")
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.github.alondero.nestlin.ui.ScreenshotManagerTest.should generate screenshot filename with timestamp" -v
```

Expected: FAIL with "method generateScreenshotFilename not found"

**Step 3: Implement filename generation**

Update `ScreenshotManager.kt`:

```kotlin
package com.github.alondero.nestlin.ui

import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

class ScreenshotManager(private val screenshotsDir: Path) {
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")

    init {
        Files.createDirectories(screenshotsDir)
    }

    fun generateScreenshotFilename(): String {
        val timestamp = LocalDateTime.now().format(dateTimeFormatter)
        return "screenshot-$timestamp.png"
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "com.github.alondero.nestlin.ui.ScreenshotManagerTest.should generate screenshot filename with timestamp" -v
```

Expected: PASS

**Step 5: Commit**

```bash
git add src/main/kotlin/com/github/alondero/nestlin/ui/ScreenshotManager.kt src/test/kotlin/com/github/alondero/nestlin/ui/ScreenshotManagerTest.kt
git commit -m "feat: add timestamp-based screenshot filename generation"
```

---

## Task 3: Add Screenshot Capture Logic

**Files:**
- Modify: `src/main/kotlin/com/github/alondero/nestlin/ui/ScreenshotManager.kt`
- Modify: `src/test/kotlin/com/github/alondero/nestlin/ui/ScreenshotManagerTest.kt`

**Step 1: Write the failing test for frame buffer to PNG conversion**

Add this test to ScreenshotManagerTest:

```kotlin
@Test
fun `should save frame buffer as PNG file`() {
    val screenshotsDir = tempFolder.root.toPath().resolve("screenshots")
    val manager = ScreenshotManager(screenshotsDir)

    // Create a simple test frame buffer: 4x4 pixels, RGB (3 bytes per pixel)
    // All red: R=255, G=0, B=0
    val testFrame = ByteArray(4 * 4 * 3) { i ->
        when (i % 3) {
            0 -> 255.toByte()  // R channel
            else -> 0.toByte() // G and B channels
        }
    }

    val savedPath = manager.saveScreenshot(testFrame, width = 4, height = 4)

    Files.exists(savedPath) shouldMatch true
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew test --tests "com.github.alondero.nestlin.ui.ScreenshotManagerTest.should save frame buffer as PNG file" -v
```

Expected: FAIL with "method saveScreenshot not found"

**Step 3: Implement frame buffer to PNG conversion**

Update `ScreenshotManager.kt`:

```kotlin
package com.github.alondero.nestlin.ui

import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import javax.imageio.ImageIO

class ScreenshotManager(private val screenshotsDir: Path) {
    private val dateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss-SSS")

    init {
        Files.createDirectories(screenshotsDir)
    }

    fun generateScreenshotFilename(): String {
        val timestamp = LocalDateTime.now().format(dateTimeFormatter)
        return "screenshot-$timestamp.png"
    }

    fun saveScreenshot(frameBuffer: ByteArray, width: Int, height: Int): Path {
        // Create a BufferedImage from the frame buffer
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)

        // Convert byte array (RGB) to BufferedImage pixels (int RGB)
        var byteIndex = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val r = frameBuffer[byteIndex++].toInt() and 0xFF
                val g = frameBuffer[byteIndex++].toInt() and 0xFF
                val b = frameBuffer[byteIndex++].toInt() and 0xFF

                val rgb = (r shl 16) or (g shl 8) or b
                image.setRGB(x, y, rgb)
            }
        }

        // Save to file
        val filename = generateScreenshotFilename()
        val filepath = screenshotsDir.resolve(filename)
        ImageIO.write(image, "PNG", filepath.toFile())

        return filepath
    }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew test --tests "com.github.alondero.nestlin.ui.ScreenshotManagerTest.should save frame buffer as PNG file" -v
```

Expected: PASS

**Step 5: Run all ScreenshotManager tests**

```bash
./gradlew test --tests "com.github.alondero.nestlin.ui.ScreenshotManagerTest" -v
```

Expected: All 3 tests PASS

**Step 6: Commit**

```bash
git add src/main/kotlin/com/github/alondero/nestlin/ui/ScreenshotManager.kt src/test/kotlin/com/github/alondero/nestlin/ui/ScreenshotManagerTest.kt
git commit -m "feat: implement frame buffer to PNG conversion in ScreenshotManager"
```

---

## Task 4: Integrate Screenshot Manager into Application

**Files:**
- Modify: `src/main/kotlin/com/github/alondero/nestlin/ui/Application.kt`

**Step 1: Add ScreenshotManager instance to Application**

In `NestlinApplication` class, add after line 46 (after `private var audioThread: Thread? = null`):

```kotlin
private val screenshotManager = ScreenshotManager(Paths.get("screenshots"))
```

Also add the necessary import at the top:

```kotlin
import java.nio.file.Paths
```

(Note: `Paths` is already imported, so just ensure it's there)

**Step 2: Add screenshot capture method to Application**

Add this method to the `NestlinApplication` class after the `handleInput` method (after line 250):

```kotlin
private fun captureScreenshot() {
    try {
        val path = screenshotManager.saveScreenshot(nextFrame, scaledWidth, scaledHeight)
        println("[SCREENSHOT] Saved to: $path")
    } catch (e: Exception) {
        println("[SCREENSHOT] Failed to save screenshot: ${e.message}")
        e.printStackTrace()
    }
}
```

**Step 3: Add S key binding for screenshot**

Modify the `handleInput` method to add screenshot capture. Update the `when` statement (starting at line 239) to add this case before the `else`:

```kotlin
            javafx.scene.input.KeyCode.S -> if (pressed) captureScreenshot()
```

**Step 4: Test the feature manually**

Run the emulator:

```bash
./gradlew run --args="testroms/nestest.nes"
```

Expected behavior:
- Press 'S' key while emulator is running
- Console should show: `[SCREENSHOT] Saved to: /path/to/screenshots/screenshot-YYYY-MM-DD_HH-mm-ss-SSS.png`
- File should appear in the `screenshots/` directory
- You should be able to press 'S' multiple times to take multiple screenshots

**Step 5: Verify the build still passes**

```bash
./gradlew build
```

Expected: BUILD SUCCESSFUL

**Step 6: Commit**

```bash
git add src/main/kotlin/com/github/alondero/nestlin/ui/Application.kt
git commit -m "feat: integrate screenshot manager with S key binding"
```

---

## Task 5: Add screenshots Directory to .gitignore (Optional)

**Files:**
- Modify: `.gitignore`

**Step 1: Check if .gitignore exists**

```bash
ls -la .gitignore
```

**Step 2: Add screenshots/ to .gitignore**

If `.gitignore` exists, add this line:

```
screenshots/
```

If it doesn't exist, create it with:

```bash
echo "screenshots/" >> .gitignore
```

**Step 3: Commit**

```bash
git add .gitignore
git commit -m "build: ignore screenshots directory"
```

---

## Summary

This plan implements a complete screenshot feature:

1. **ScreenshotManager** - Reusable utility for file I/O and PNG encoding
2. **Keyboard Integration** - Press 'S' to capture screenshots
3. **Timestamped Files** - Each screenshot gets a unique filename with millisecond precision
4. **User Feedback** - Console output confirms successful saves
5. **Well-Tested** - Unit tests for filename generation and image encoding

The feature is non-invasive—it doesn't modify core emulation logic and integrates cleanly into the existing UI layer. Screenshots can be committed to git history to document progress through development.
