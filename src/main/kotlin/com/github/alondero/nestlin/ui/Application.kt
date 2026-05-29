package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.EmulatorConfig
import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.Controller
import com.github.alondero.nestlin.SaveState
import com.github.alondero.nestlin.apu.AudioResampler
import com.github.alondero.nestlin.input.GamepadInput
import com.github.alondero.nestlin.input.InputConfig
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ppu.RESOLUTION_HEIGHT
import com.github.alondero.nestlin.ppu.RESOLUTION_WIDTH
import javafx.animation.AnimationTimer
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.Group
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.image.ImageView
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.image.Image
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Stage
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

const val DISPLAY_SCALE = 4  // 4x magnification for debugging

// Periodic SRAM flush interval. 10s matches RetroArch's default and means a crash
// loses at most ~10s of in-game saves. Skipped when batteryDirty is false (no cost).
private const val BATTERY_FLUSH_INTERVAL_MS = 10_000L

fun main(args: Array<String>) {
    Application.launch(NestlinApplication::class.java, *args)
}

class NestlinApplication : FrameListener, Application() {
    private lateinit var stage: Stage
    // Native-resolution backing image; the ImageView's fitWidth/fitHeight drives upscaling.
    // isSmooth=false selects nearest-neighbor when stretching to the fit-rect, giving crisp
    // pixel-art edges. (Note: smooth is honored only on the fitWidth/fitHeight path, NOT on
    // scene-graph scaleX/scaleY transforms — that's why we drive size via fitWidth, not scale.)
    private val frameImage = WritableImage(RESOLUTION_WIDTH, RESOLUTION_HEIGHT)
    private val imageView = ImageView(frameImage).apply { isSmooth = false }
    // Group wraps the view so its bounds participate normally in StackPane sizing.
    private val canvasGroup = Group(imageView)
    private val canvasHolder = StackPane(canvasGroup)
    private var nestlin = Nestlin().also { it.addFrameListener(this) }
    private var running = false
    // Frame buffer synchronization for thread-safe screenshot capture
    private val frameBufferLock = Any()
    // Native-resolution RGB buffer. Upscaling is handled by ImageView.fitWidth/Height, not by replication.
    private var nextFrame = ByteArray(RESOLUTION_HEIGHT * RESOLUTION_WIDTH * 3)

    private var displayConfig = DisplayConfig.load()
    private val scaleMenuItems = mutableMapOf<ScaleMode, javafx.scene.control.RadioMenuItem>()

    // Cached windowed dimensions so we can restore the stage explicitly on fullscreen exit
    // (JavaFX on Windows doesn't always restore prior size reliably, especially in Fit mode
    // where the canvas scale is bound reactively to the holder width).
    private var windowedWidth: Double = 0.0
    private var windowedHeight: Double = 0.0

    private val mouseNearTopProperty = javafx.beans.property.SimpleBooleanProperty(false)

    // Current ROM path for Hard Reset functionality
    private var currentRomPath: Path? = null
    // Emulation thread reference for stop/start control
    private var emulationThread: Thread? = null

    // Audio playback
    private var audioLine: SourceDataLine? = null
    private var audioEnabled = true
    private var audioThread: Thread? = null

    // Screenshot management
    private val screenshotManager = ScreenshotManager(Paths.get("screenshots"))

    // Automated screenshot interval mode (for validation)
    private var screenshotIntervalSeconds: Int = 0
    private var screenshotDurationSeconds: Int = 0
    private var screenshotElapsedSeconds: Int = 0
    private var screenshotTimer: java.util.Timer? = null

    // Periodic battery-backed SRAM flush. Writes <rom>.sav every 10s if dirty,
    // so a crash or force-kill costs at most ~10s of in-game saves.
    private var batteryFlushTimer: java.util.Timer? = null

    // Input configuration and gamepad support
    private val inputConfig = InputConfig.load()
    private lateinit var gamepadInput: GamepadInput

    // Held to keep the menu's check state in sync with keyboard shortcuts and
    // to clear pause when starting a fresh game via Load / Hard Reset.
    private var pauseMenuItem: javafx.scene.control.CheckMenuItem? = null

    // Load Recent submenu
    private val recentRomsMenu = Menu("Load Recent")

    override fun start(stage: Stage) {
        // Assign lateinit *before* the apply block — applyScale (called inside) reads this.stage.
        this.stage = stage
        stage.apply {
            title = "Nestlin"

            // Set the application icon
            try {
                val iconStream = NestlinApplication::class.java.getResourceAsStream("/images/app-icon.png")
                if (iconStream != null) {
                    val iconImage = Image(iconStream)
                    icons.add(iconImage)
                    iconStream.close()
                }
            } catch (e: Exception) {
                println("[APP] Warning: Could not load application icon: ${e.message}")
            }

            // Create menu bar
            val menuBar = javafx.scene.control.MenuBar()

            // File menu
            val fileMenu = Menu("File")

            val loadGameItem = MenuItem("Load Game...")
            loadGameItem.setOnAction { handleLoadGame() }

            val hardResetItem = MenuItem("Hard Reset Game")
            hardResetItem.setOnAction { handleHardReset() }

            val saveStateItem = MenuItem("Save State...")
            saveStateItem.setOnAction { handleSaveState() }

            val loadStateItem = MenuItem("Load State...")
            loadStateItem.setOnAction { handleLoadState() }

            val exitItem = MenuItem("Exit")
            exitItem.setOnAction { handleExit() }

            fileMenu.items.addAll(loadGameItem, recentRomsMenu, hardResetItem, saveStateItem, loadStateItem, exitItem)
            menuBar.menus.add(fileMenu)
            updateRecentMenu(EmulatorConfig.getRecentRoms())

            // Settings menu
            val settingsMenu = javafx.scene.control.Menu("Settings")

            val throttleMenuItem = javafx.scene.control.CheckMenuItem("Speed Throttling (60 FPS)")
            throttleMenuItem.isSelected = nestlin.config.speedThrottlingEnabled
            throttleMenuItem.setOnAction {
                nestlin.config.speedThrottlingEnabled = throttleMenuItem.isSelected
                println("[APP] Speed throttling ${if (throttleMenuItem.isSelected) "enabled" else "disabled"}")
            }

            // Scale submenu (1x / 2x / 3x / 4x / Fit) as a mutually-exclusive radio group.
            val scaleMenu = javafx.scene.control.Menu("Scale")
            val scaleGroup = javafx.scene.control.ToggleGroup()
            ScaleMode.values().forEach { mode ->
                val item = javafx.scene.control.RadioMenuItem(mode.label())
                item.toggleGroup = scaleGroup
                item.isSelected = displayConfig.scale == mode
                item.setOnAction {
                    setScaleMode(mode)
                }
                scaleMenu.items.add(item)
                scaleMenuItems[mode] = item
            }

            val fullscreenItem = javafx.scene.control.CheckMenuItem("Fullscreen")
            fullscreenItem.isSelected = displayConfig.fullscreen
            fullscreenItem.accelerator = javafx.scene.input.KeyCombination.keyCombination("F11")
            fullscreenItem.setOnAction { setFullscreen(fullscreenItem.isSelected) }

            settingsMenu.items.addAll(throttleMenuItem, scaleMenu, fullscreenItem)
            menuBar.menus.add(settingsMenu)

            // Emulation menu
            val emulationMenu = javafx.scene.control.Menu("Emulation")

            val pauseItem = javafx.scene.control.CheckMenuItem("Pause")
            pauseItem.isSelected = nestlin.config.paused
            pauseItem.accelerator = javafx.scene.input.KeyCombination.keyCombination("Ctrl+P")
            pauseItem.setOnAction {
                nestlin.config.paused = pauseItem.isSelected
                updateTitle()
                println("[APP] Emulation ${if (nestlin.config.paused) "paused" else "resumed"}")
            }
            pauseMenuItem = pauseItem

            emulationMenu.items.add(pauseItem)
            menuBar.menus.add(emulationMenu)

            // Create layout with menu bar and the canvas holder. VBox.setVgrow lets the
            // holder expand to fill remaining vertical space in fullscreen / Fit mode.
            val root = VBox()
            root.children.addAll(menuBar, canvasHolder)
            VBox.setVgrow(canvasHolder, javafx.scene.layout.Priority.ALWAYS)

            // Letterbox area outside the scaled canvas paints black.
            canvasHolder.style = "-fx-background-color: black;"
            // Decouple holder's min size from the Group's bounds. Without this, the scaled
            // canvas's visual extent propagates upward through the StackPane as a min-size
            // constraint, preventing the stage from shrinking back after fullscreen exit
            // and trapping Fit mode at the fullscreen scale value.
            canvasHolder.minWidth = 0.0
            canvasHolder.minHeight = 0.0

            scene = Scene(root)
            scene.fill = javafx.scene.paint.Color.BLACK

            // Apply persisted scale + fullscreen now that the scene exists.
            applyScale(displayConfig.scale)
            isFullScreen = displayConfig.fullscreen
            fullScreenExitHint = ""
            fullScreenExitKeyCombination = javafx.scene.input.KeyCombination.NO_MATCH

            // Menu reveals when (a) not in fullscreen, (b) mouse is near the top edge, or
            // (c) any submenu is already open (so dropdowns don't vanish mid-click).
            // Accelerator F11 lives on the Scene and still fires while menu is hidden.
            val anyMenuShowing = javafx.beans.binding.Bindings.createBooleanBinding(
                { menuBar.menus.any { it.isShowing } },
                *menuBar.menus.map { it.showingProperty() }.toTypedArray()
            )
            val menuRevealed = fullScreenProperty().not()
                .or(mouseNearTopProperty)
                .or(anyMenuShowing)
            menuBar.visibleProperty().bind(menuRevealed)
            menuBar.managedProperty().bind(menuRevealed)

            // Reveal threshold: top 4 logical pixels. Tight enough not to interfere with
            // gameplay; generous enough that a fast mouse-to-top motion still triggers.
            scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED) { e ->
                mouseNearTopProperty.set(stage.isFullScreen && e.sceneY < 4.0)
            }

            fullScreenProperty().addListener { _, wasFs, nowFs ->
                if (nowFs && !wasFs) {
                    // Capture pre-fullscreen size for explicit restore on exit.
                    if (width > 0) windowedWidth = width
                    if (height > 0) windowedHeight = height
                } else if (wasFs && !nowFs && windowedWidth > 0) {
                    // Explicitly restore — JavaFX's auto-restore isn't reliable on Windows,
                    // and in Fit mode the canvas scale is bound to the holder so a stuck-large
                    // stage means a stuck-large canvas that overflows the window frame.
                    width = windowedWidth
                    height = windowedHeight
                }
                if (displayConfig.fullscreen != nowFs) {
                    displayConfig = displayConfig.copy(fullscreen = nowFs)
                    DisplayConfig.save(displayConfig)
                }
                fullscreenItem.isSelected = nowFs
                // Reset hover state on transition so the menu isn't stuck visible.
                if (!nowFs) mouseNearTopProperty.set(false)
            }

            scene.setOnKeyPressed { event ->
                when {
                    // Ctrl+T: toggle throttling
                    event.code == javafx.scene.input.KeyCode.T && event.isControlDown -> {
                        nestlin.config.speedThrottlingEnabled = !nestlin.config.speedThrottlingEnabled
                        throttleMenuItem.isSelected = nestlin.config.speedThrottlingEnabled
                        println("[APP] Speed throttling ${if (nestlin.config.speedThrottlingEnabled) "enabled" else "disabled"}")
                        event.consume()
                    }
                    // Ctrl+P: toggle pause
                    event.code == javafx.scene.input.KeyCode.P && event.isControlDown -> {
                        nestlin.config.paused = !nestlin.config.paused
                        pauseMenuItem?.isSelected = nestlin.config.paused
                        updateTitle()
                        println("[APP] Emulation ${if (nestlin.config.paused) "paused" else "resumed"}")
                        event.consume()
                    }
                    // F5: quick save state (Mesen/FCEUX convention)
                    event.code == javafx.scene.input.KeyCode.F5 -> {
                        handleQuickSaveState()
                        event.consume()
                    }
                    // F8: quick load state
                    event.code == javafx.scene.input.KeyCode.F8 -> {
                        handleQuickLoadState()
                        event.consume()
                    }
                    // F11 is handled by the Fullscreen menu accelerator (see Settings menu).
                    else -> handleInput(event.code, true)
                }
            }
            scene.setOnKeyReleased { event -> handleInput(event.code, false) }

            // Window close (X button) goes through handleExit so battery RAM gets flushed.
            // Without this the JavaFX runtime would jump straight to stop() and bypass the flush.
            setOnCloseRequest { handleExit() }

            show()
        }

        startBatteryFlushTimer()

        // Initialize gamepad input
        gamepadInput = GamepadInput(nestlin.getController1(), inputConfig.gamepad)
        gamepadInput.initialize()

        // Create default config file for user reference
        InputConfig.createDefaultIfMissing()

        object: AnimationTimer() {
            override fun handle(now: Long) {
                // Poll gamepad input
                gamepadInput.poll()

                val pixelWriter = frameImage.pixelWriter
                val pixelFormat = PixelFormat.getByteRgbInstance()

                // Thread-safe read of frame buffer (native NES resolution; ImageView fit-rect handles upscaling)
                synchronized(frameBufferLock) {
                    pixelWriter.setPixels(0, 0, RESOLUTION_WIDTH, RESOLUTION_HEIGHT, pixelFormat, nextFrame, 0, RESOLUTION_WIDTH * 3)
                }
            }

        }.start()

        running = true

        if (!parameters.named["no-audio"].isNullOrEmpty()) {
            audioEnabled = false
            println("[APP] Audio disabled")
        }

        // Initialize audio playback
        initAudio()

        // Parse screenshot interval parameters for automated validation
        // Note: JavaFX doesn't support --name value syntax, only --name=value
        // So we parse from unnamed parameters
        fun getNamedParam(name: String): String? {
            val index = parameters.unnamed.indexOf(name)
            return if (index >= 0 && index + 1 < parameters.unnamed.size) {
                parameters.unnamed[index + 1]
            } else null
        }
        val intervalStr = getNamedParam("--screenshot-interval") ?: parameters.named["screenshot-interval"]
        val durationStr = getNamedParam("--screenshot-duration") ?: parameters.named["screenshot-duration"]
        if (intervalStr != null && durationStr != null) {
            screenshotIntervalSeconds = intervalStr.toIntOrNull() ?: 0
            screenshotDurationSeconds = durationStr.toIntOrNull() ?: 15
            if (screenshotIntervalSeconds > 0 && screenshotDurationSeconds > 0) {
                println("[APP] Automated screenshot mode: every ${screenshotIntervalSeconds}s for ${screenshotDurationSeconds}s")
                // Wait before starting screenshots to let emulation stabilize
                screenshotTimer = java.util.Timer(true)
                screenshotTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
                    private var nextCapture = 5  // First capture at 5 seconds
                    override fun run() {
                        if (nextCapture >= screenshotDurationSeconds) {
                            println("[APP] Screenshot duration reached (${nextCapture}s >= ${screenshotDurationSeconds}s), shutting down...")
                            Platform.runLater {
                                handleExit()
                            }
                            cancel()
                        } else {
                            println("[APP] Capturing screenshot at elapsed=${nextCapture}s...")
                            captureScreenshot()
                            nextCapture += screenshotIntervalSeconds
                        }
                    }
                }, screenshotIntervalSeconds * 1000L, screenshotIntervalSeconds * 1000L)
            }
        }

        // Load and start emulation from command line argument
        thread {
            with(nestlin) {
                println("[APP] Parameters: named=${parameters.named}, unnamed=${parameters.unnamed}")
                // Reconstruct the ROM path from parameters. The ROM path is the first non-flag parameter.
                // Filter out any parameter that looks like a named flag (starts with --).
                val nonFlagParams = parameters.unnamed.filter { !it.startsWith("--") }
                val debugEnabled = !parameters.named["debug"].isNullOrEmpty() ||
                        parameters.unnamed.any { it.startsWith("--debug") }
                if (debugEnabled) enableLogging()
                // Take only the first non-flag parameter as the ROM path (rest are other arguments)
                val romPath = nonFlagParams.firstOrNull() ?: throw IllegalStateException("No ROM file provided")
                currentRomPath = Paths.get(romPath)
                load(currentRomPath!!)
                powerReset()
                loadBatteryRam(currentRomPath!!)
                Platform.runLater { updateTitle() }
                startEmulation()
            }
        }.also { emulationThread = it }
    }

    private fun stopEmulation() {
        nestlin.stop()
        // Unbounded join: save state correctness requires the emulation thread to be fully stopped
        // before mutable CPU/PPU/APU state is serialised. The loop has no blocking IO; only brief
        // throttling sleeps (<=16ms), so this should return promptly.
        emulationThread?.join()
        emulationThread = null
    }

    private fun startEmulation() {
        emulationThread = thread {
            nestlin.start()
        }
    }

    private fun startBatteryFlushTimer() {
        batteryFlushTimer = java.util.Timer("nestlin-sram-flush", true)
        batteryFlushTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                val rom = currentRomPath ?: return
                try {
                    nestlin.flushBatteryRamIfDirty(rom)
                } catch (e: Exception) {
                    System.err.println("[SRAM] Periodic flush failed: ${e.message}")
                }
            }
        }, BATTERY_FLUSH_INTERVAL_MS, BATTERY_FLUSH_INTERVAL_MS)
    }

    private fun startScreenshotTimer() {
        screenshotTimer = java.util.Timer(true)
        // First screenshot after interval, then every intervalSeconds
        screenshotTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                if (screenshotElapsedSeconds >= screenshotDurationSeconds) {
                    // Duration reached - stop emulation and exit
                    println("[APP] Screenshot duration reached (${screenshotElapsedSeconds}s >= ${screenshotDurationSeconds}s), shutting down...")
                    Platform.runLater {
                        handleExit()
                    }
                    cancel()
                } else {
                    // Capture screenshot at interval
                    println("[APP] Capturing screenshot at elapsed=${screenshotElapsedSeconds}s...")
                    captureScreenshot()
                    screenshotElapsedSeconds += screenshotIntervalSeconds
                }
            }
        }, screenshotIntervalSeconds * 1000L, screenshotIntervalSeconds * 1000L)
    }

    private fun handleLoadGame() {
        val chooser = FileChooser()
        chooser.title = "Load NES ROM"
        chooser.extensionFilters.addAll(
            FileChooser.ExtensionFilter("NES ROMs (*.nes)", "*.nes"),
            FileChooser.ExtensionFilter("7z Archives (*.7z)", "*.7z"),
            FileChooser.ExtensionFilter("All Files", "*.*")
        )
        val file = chooser.showOpenDialog(stage)
        if (file != null) {
            val romPath = file.toPath()
            stopEmulation()
            // Flush the previous ROM's SRAM before its mapper is replaced.
            currentRomPath?.let { nestlin.saveBatteryRam(it) }
            currentRomPath = romPath
            nestlin.load(romPath)
            nestlin.powerReset()
            nestlin.loadBatteryRam(romPath)
            clearPauseState()
            updateTitle()
            EmulatorConfig.addRecentRom(romPath)
            updateRecentMenu(EmulatorConfig.getRecentRoms())
            startEmulation()
        }
    }

    // Reset pause so a new game session always begins running.
    private fun clearPauseState() {
        nestlin.config.paused = false
        pauseMenuItem?.isSelected = false
    }

    private fun setScaleMode(mode: ScaleMode) {
        if (displayConfig.scale != mode) {
            displayConfig = displayConfig.copy(scale = mode)
            DisplayConfig.save(displayConfig)
        }
        scaleMenuItems[mode]?.isSelected = true
        applyScale(mode)
        println("[APP] Display scale set to ${mode.label()}")
    }

    private fun setFullscreen(enable: Boolean) {
        stage.isFullScreen = enable
        // fullScreenProperty listener persists the change.
    }

    private fun applyScale(mode: ScaleMode) {
        val factor = mode.factor()
        // Drop any previous binding before swapping mode to avoid leaking listeners.
        imageView.fitWidthProperty().unbind()
        imageView.fitHeightProperty().unbind()
        if (factor != null) {
            imageView.fitWidth = (RESOLUTION_WIDTH * factor).toDouble()
            imageView.fitHeight = (RESOLUTION_HEIGHT * factor).toDouble()
            // Resize the window to the view's natural extent unless fullscreen owns it.
            if (!stage.isFullScreen) {
                stage.sizeToScene()
            }
        } else {
            // Fit: seed the window at 3x when entering Fit while windowed, so the user
            // always gets a usable starting size before the binding takes over.
            if (!stage.isFullScreen) {
                imageView.fitWidth = (RESOLUTION_WIDTH * 3).toDouble()
                imageView.fitHeight = (RESOLUTION_HEIGHT * 3).toDouble()
                stage.sizeToScene()
            }
            // Then bind fit-rect to live holder dimensions, preserving aspect ratio.
            // Computed as an integer-or-fractional scale factor, then multiplied back out to
            // pixel dimensions — keeps the aspect ratio locked to 256:240 regardless of
            // window letterboxing.
            val widthFactor = javafx.beans.binding.Bindings.createDoubleBinding(
                { (canvasHolder.width / RESOLUTION_WIDTH).coerceAtLeast(1.0) },
                canvasHolder.widthProperty()
            )
            val heightFactor = javafx.beans.binding.Bindings.createDoubleBinding(
                { (canvasHolder.height / RESOLUTION_HEIGHT).coerceAtLeast(1.0) },
                canvasHolder.heightProperty()
            )
            val fitFactor = javafx.beans.binding.Bindings.min(widthFactor, heightFactor)
            imageView.fitWidthProperty().bind(fitFactor.multiply(RESOLUTION_WIDTH.toDouble()))
            imageView.fitHeightProperty().bind(fitFactor.multiply(RESOLUTION_HEIGHT.toDouble()))
        }
    }

    private fun updateTitle() {
        val gameName = nestlin.currentGameName()
        val base = if (gameName.isNotEmpty()) "Nestlin - $gameName" else "Nestlin"
        stage.title = if (nestlin.config.paused) "$base (Paused)" else base
    }

    private fun updateRecentMenu(recentRoms: List<Path>) {
        recentRomsMenu.items.clear()
        if (recentRoms.isEmpty()) {
            val emptyItem = MenuItem("(empty)")
            emptyItem.isDisable = true
            recentRomsMenu.items.add(emptyItem)
        } else {
            for (path in recentRoms) {
                val item = MenuItem(path.fileName.toString())
                item.setOnAction { loadRom(path) }
                recentRomsMenu.items.add(item)
            }
        }
    }

    private fun loadRom(path: Path) {
        stopEmulation()
        currentRomPath = path
        nestlin.load(path)
        nestlin.powerReset()
        clearPauseState()
        updateTitle()
        startEmulation()
    }

    private fun handleSaveState() {
        if (currentRomPath == null) {
            showError("No ROM Loaded", "Load a game before saving state.")
            return
        }
        val chooser = FileChooser()
        chooser.title = "Save Nestlin State"
        chooser.extensionFilters.add(FileChooser.ExtensionFilter("Nestlin Save (*.nstl)", "*.nstl"))
        chooser.initialFileName = defaultSaveFileName()
        val file = chooser.showSaveDialog(stage) ?: return
        performWithEmulationPaused {
            try {
                nestlin.saveState(file.toPath())
                println("[STATE] Saved to: ${file.absolutePath}")
            } catch (e: Exception) {
                println("[STATE] Save failed: ${e.message}")
                e.printStackTrace()
                Platform.runLater { showError("Save Failed", e.message ?: "Unknown error") }
            }
        }
    }

    private fun handleLoadState() {
        if (currentRomPath == null) {
            showError("No ROM Loaded", "Load a game before loading state.")
            return
        }
        val chooser = FileChooser()
        chooser.title = "Load Nestlin State"
        chooser.extensionFilters.add(FileChooser.ExtensionFilter("Nestlin Save (*.nstl)", "*.nstl"))
        val file = chooser.showOpenDialog(stage) ?: return
        performWithEmulationPaused {
            try {
                nestlin.loadState(file.toPath())
                println("[STATE] Loaded from: ${file.absolutePath}")
            } catch (e: SaveState.IncompatibleSaveStateException) {
                println("[STATE] Incompatible save: ${e.message}")
                Platform.runLater { showError("Incompatible Save State", e.message ?: "") }
            } catch (e: Exception) {
                println("[STATE] Load failed: ${e.message}")
                e.printStackTrace()
                Platform.runLater { showError("Load Failed", e.message ?: "Unknown error") }
            }
        }
    }

    private fun handleQuickSaveState() {
        if (currentRomPath == null) return
        val path = quickSavePath() ?: return
        performWithEmulationPaused {
            try {
                java.nio.file.Files.createDirectories(path.parent)
                nestlin.saveState(path)
                println("[STATE] Quick-saved to: $path")
            } catch (e: Exception) {
                println("[STATE] Quick-save failed: ${e.message}")
            }
        }
    }

    private fun handleQuickLoadState() {
        if (currentRomPath == null) return
        val path = quickSavePath() ?: return
        performWithEmulationPaused {
            try {
                nestlin.loadState(path)
                println("[STATE] Quick-loaded from: $path")
            } catch (e: java.nio.file.NoSuchFileException) {
                println("[STATE] No quick-save at $path")
            } catch (e: Exception) {
                println("[STATE] Quick-load failed: ${e.message}")
            }
        }
    }

    private fun defaultSaveFileName(): String {
        val romName = currentRomPath?.fileName?.toString()
            ?.removeSuffix(".nes")?.removeSuffix(".7z") ?: "state"
        return "$romName.nstl"
    }

    private fun quickSavePath(): java.nio.file.Path? {
        val rom = currentRomPath ?: return null
        val name = rom.fileName.toString().removeSuffix(".nes").removeSuffix(".7z")
        return Paths.get("savestates", "$name.quick.nstl")
    }

    /**
     * Pause the emulation thread, run [action] synchronously, then resume.
     * Avoids racing mutable CPU/PPU/APU state with the emulation loop.
     */
    private fun performWithEmulationPaused(action: () -> Unit) {
        stopEmulation()
        action()
        startEmulation()
    }

    private fun showError(title: String, message: String) {
        val alert = javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR)
        alert.title = title
        alert.headerText = null
        alert.contentText = message
        alert.showAndWait()
    }

    private fun handleHardReset() {
        if (currentRomPath == null) {
            val alert = javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR)
            alert.title = "No ROM Loaded"
            alert.contentText = "Please load a game first."
            alert.showAndWait()
            return
        }
        stopEmulation()
        // Real cartridges keep their battery alive across a power-cycle.
        // Mirror that: persist current SRAM, then reload it post-reset.
        nestlin.saveBatteryRam(currentRomPath!!)
        nestlin.load(currentRomPath!!)
        nestlin.powerReset()
        nestlin.loadBatteryRam(currentRomPath!!)
        clearPauseState()
        updateTitle()
        startEmulation()
    }

    private fun handleExit() {
        stopEmulation()
        currentRomPath?.let { nestlin.saveBatteryRam(it) }
        Platform.exit()
    }

    override fun stop() {
        nestlin.stop()
        running = false

        // Clean up screenshot timer
        screenshotTimer?.cancel()
        screenshotTimer = null

        // Clean up battery-flush timer; defensive final flush in case handleExit()
        // wasn't the entry point (e.g. JavaFX runtime tears us down through stop() directly).
        batteryFlushTimer?.cancel()
        batteryFlushTimer = null
        currentRomPath?.let { nestlin.saveBatteryRam(it) }

        // Clean up gamepad
        gamepadInput.shutdown()

        // Clean up audio
        audioLine?.stop()
        audioLine?.close()
        audioThread?.join(1000)  // Wait up to 1 second for audio thread to finish
    }

    private fun initAudio() {
        if (!audioEnabled) return

        try {
            // Try multiple audio format configurations (most to least compatible)
            val formats = listOf(
                // Try big-endian first (more common on many systems)
                AudioFormat(44100f, 16, 1, true, true),
                // Try little-endian
                AudioFormat(44100f, 16, 1, true, false),
                // Try 48kHz (common on modern systems)
                AudioFormat(48000f, 16, 1, true, true),
                AudioFormat(48000f, 16, 1, true, false),
                // Try 8-bit mono as fallback
                AudioFormat(44100f, 8, 1, true, true),
                AudioFormat(44100f, 8, 1, true, false)
            )

            var selectedFormat: AudioFormat? = null
            var bestMatch: SourceDataLine? = null

            for (format in formats) {
                try {
                    val info = DataLine.Info(SourceDataLine::class.java, format)
                    val line = AudioSystem.getLine(info) as? SourceDataLine
                    if (line != null) {
                        // ~93 ms headroom at 44.1 kHz 16-bit mono (~46 ms stereo) to absorb
                        // emulation-thread throttle jitter.
                        line.open(format, 8192)
                        line.start()
                        selectedFormat = format
                        bestMatch = line
                        println("[AUDIO] Found compatible format: ${format.sampleRate} Hz, ${format.sampleSizeInBits}-bit, ${if (format.isBigEndian) "big" else "little"}-endian")
                        break
                    }
                } catch (e: Exception) {
                    // Continue to next format
                    continue
                }
            }

            if (bestMatch != null && selectedFormat != null) {
                audioLine = bestMatch
                // Start audio playback thread
                audioThread = thread(isDaemon = true) {
                    audioPlaybackLoop(selectedFormat)
                }
                println("[AUDIO] Audio initialized successfully")
            } else {
                println("[AUDIO] No compatible audio formats found")
                audioEnabled = false
            }
        } catch (e: Exception) {
            println("[AUDIO] Failed to initialize audio: ${e.message}")
            e.printStackTrace()
            audioEnabled = false
        }
    }

    private fun audioPlaybackLoop(format: AudioFormat) {
        val buffer = ByteArray(2048)
        val bytesPerSample = if (format.sampleSizeInBits == 16) 2 else 1
        val maxSamplesPerWrite = buffer.size / bytesPerSample
        val resampler = AudioResampler(nestlin.getAudioSampleRateHz(), format.sampleRate.toDouble())
        val outputSamples = ShortArray(maxSamplesPerWrite)
        var exitReason = "stopped"

        // Debug: track underrun events
        var totalUnderrunEvents = 0
        var totalSilentReads = 0

        while (running && audioEnabled) {
            try {
                val inputSamples = nestlin.getAudioSamples()
                if (inputSamples.isNotEmpty()) {
                    resampler.push(inputSamples)
                } else {
                    totalSilentReads++
                }

                var produced = resampler.resample(outputSamples, maxSamplesPerWrite)
                var wrote = false
                while (produced > 0) {
                    wrote = true
                    when {
                        format.sampleSizeInBits == 16 -> {
                            // Convert shorts to bytes
                            if (format.isBigEndian) {
                                // Big-endian: MSB first
                                for (i in 0 until produced) {
                                    val sample = outputSamples[i].toInt()
                                    buffer[i * 2] = (sample shr 8).toByte()
                                    buffer[i * 2 + 1] = (sample and 0xFF).toByte()
                                }
                            } else {
                                // Little-endian: LSB first
                                for (i in 0 until produced) {
                                    val sample = outputSamples[i].toInt()
                                    buffer[i * 2] = (sample and 0xFF).toByte()
                                    buffer[i * 2 + 1] = (sample shr 8).toByte()
                                }
                            }
                            audioLine?.write(buffer, 0, produced * 2)
                        }
                        format.sampleSizeInBits == 8 -> {
                            // Convert shorts to 8-bit unsigned
                            for (i in 0 until produced) {
                                // Scale from -32768..32767 to 0..255
                                val scaledValue = ((outputSamples[i].toInt() + 32768) shr 8).toByte()
                                buffer[i] = scaledValue
                            }
                            audioLine?.write(buffer, 0, produced)
                        }
                    }
                    produced = resampler.resample(outputSamples, maxSamplesPerWrite)
                }

                if (!wrote) {
                    totalUnderrunEvents++
                    Thread.sleep(1)  // Avoid busy-waiting
                }
            } catch (e: Exception) {
                if (running && audioEnabled) {
                    println("[AUDIO] Error in audio playback: ${e.message}")
                }
                exitReason = "error"
                break
            }
        }

        println("[AUDIO] Audio thread terminated (${exitReason})")
        println("[AUDIO] Debug: silent reads=${totalSilentReads}, underrun events=${totalUnderrunEvents}")
    }

    override fun frameUpdated(frame: Frame) {
        synchronized(frameBufferLock) {
            // Write directly at native 256x240 — upscaling is the Canvas's job.
            frame.scanlines.withIndex().forEach { (y, scanline) ->
                val rowBase = y * RESOLUTION_WIDTH * 3
                scanline.withIndex().forEach { (x, pixel) ->
                    val idx = rowBase + x * 3
                    nextFrame[idx] = (pixel shr 16).toByte()
                    nextFrame[idx + 1] = (pixel shr 8).toByte()
                    nextFrame[idx + 2] = pixel.toByte()
                }
            }
        }
    }

    private fun handleInput(code: javafx.scene.input.KeyCode, pressed: Boolean) {
        // Handle screenshot separately (always S key)
        if (code == javafx.scene.input.KeyCode.S && pressed) {
            captureScreenshot()
            return
        }

        // Use configurable keyboard mapping
        val controller = nestlin.getController1()
        inputConfig.getButtonForKey(code)?.let { button ->
            controller.setButton(button, pressed)
        }
    }

    /**
     * Captures the current screen buffer and saves it as a PNG file.
     * Thread-safe: Creates a copy of the frame buffer before file I/O.
     * Non-blocking: File write happens on background thread.
     * Triggered by pressing the 'S' key.
     */
    private fun captureScreenshot() {
        // Get a thread-safe copy of the frame buffer
        val frameData = synchronized(frameBufferLock) {
            nextFrame.copyOf()
        }

        // Run file I/O on background thread to avoid blocking the UI
        thread {
            try {
                val path = screenshotManager.saveScreenshot(frameData, RESOLUTION_WIDTH, RESOLUTION_HEIGHT)
                println("[SCREENSHOT] Saved to: $path")
            } catch (e: IOException) {
                println("[SCREENSHOT] File I/O error: ${e.message}")
            } catch (e: IllegalArgumentException) {
                println("[SCREENSHOT] Invalid parameters: ${e.message}")
            } catch (e: Exception) {
                println("[SCREENSHOT] Unexpected error: ${e.message}")
                e.printStackTrace()
            }
        }
    }
}
