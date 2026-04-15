package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.Controller
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
import javafx.scene.canvas.Canvas
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import javafx.scene.image.PixelFormat
import javafx.scene.layout.StackPane
import javafx.stage.FileChooser
import javafx.stage.Stage
import tornadofx.App
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

const val DISPLAY_SCALE = 4  // 4x magnification for debugging

fun main(args: Array<String>) {
    when {
        args.size == 0 -> throw IllegalStateException("Please provide a rom file as an argument")
        else -> Application.launch(NestlinApplication::class.java, *args)
    }

}

class NestlinApplication : FrameListener, App() {
    private lateinit var stage: Stage
    private val scaledWidth = RESOLUTION_WIDTH * DISPLAY_SCALE
    private val scaledHeight = RESOLUTION_HEIGHT * DISPLAY_SCALE
    private var canvas = Canvas(scaledWidth.toDouble(), scaledHeight.toDouble())
    private var nestlin = Nestlin().also { it.addFrameListener(this) }
    private var running = false
    // Frame buffer synchronization for thread-safe screenshot capture
    private val frameBufferLock = Any()
    // 4x larger buffer to hold magnified pixels
    private var nextFrame = ByteArray(scaledHeight * scaledWidth * 3)

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

    // Input configuration and gamepad support
    private val inputConfig = InputConfig.load()
    private lateinit var gamepadInput: GamepadInput

    override fun start(stage: Stage) {
        this.stage = stage.apply {
            title = "Nestlin - ${DISPLAY_SCALE}x Magnification"

            // Create menu bar
            val menuBar = javafx.scene.control.MenuBar()

            // File menu
            val fileMenu = Menu("File")

            val loadGameItem = MenuItem("Load Game...")
            loadGameItem.setOnAction { handleLoadGame() }

            val hardResetItem = MenuItem("Hard Reset Game")
            hardResetItem.setOnAction { handleHardReset() }

            val exitItem = MenuItem("Exit")
            exitItem.setOnAction { handleExit() }

            fileMenu.items.addAll(loadGameItem, hardResetItem, exitItem)
            menuBar.menus.add(fileMenu)

            // Settings menu
            val settingsMenu = javafx.scene.control.Menu("Settings")

            val throttleMenuItem = javafx.scene.control.CheckMenuItem("Speed Throttling (60 FPS)")
            throttleMenuItem.isSelected = nestlin.config.speedThrottlingEnabled
            throttleMenuItem.setOnAction {
                nestlin.config.speedThrottlingEnabled = throttleMenuItem.isSelected
                println("[APP] Speed throttling ${if (throttleMenuItem.isSelected) "enabled" else "disabled"}")
            }

            settingsMenu.items.add(throttleMenuItem)
            menuBar.menus.add(settingsMenu)

            // Create layout with menu bar and canvas
            val root = javafx.scene.layout.VBox()
            root.children.addAll(menuBar, canvas)

            scene = Scene(root)

            scene.setOnKeyPressed { event ->
                // Check for Ctrl+T keyboard shortcut to toggle throttling
                if (event.code == javafx.scene.input.KeyCode.T && event.isControlDown) {
                    nestlin.config.speedThrottlingEnabled = !nestlin.config.speedThrottlingEnabled
                    throttleMenuItem.isSelected = nestlin.config.speedThrottlingEnabled
                    println("[APP] Speed throttling ${if (nestlin.config.speedThrottlingEnabled) "enabled" else "disabled"}")
                    event.consume()
                } else {
                    handleInput(event.code, true)
                }
            }
            scene.setOnKeyReleased { event -> handleInput(event.code, false) }

            show()
        }

        // Initialize gamepad input
        gamepadInput = GamepadInput(nestlin.getController1(), inputConfig.gamepad)
        gamepadInput.initialize()

        // Create default config file for user reference
        InputConfig.createDefaultIfMissing()

        object: AnimationTimer() {
            override fun handle(now: Long) {
                // Poll gamepad input
                gamepadInput.poll()

                val pixelWriter = canvas.graphicsContext2D.pixelWriter
                val pixelFormat = PixelFormat.getByteRgbInstance()

                // Thread-safe read of frame buffer
                synchronized(frameBufferLock) {
                    pixelWriter.setPixels(0, 0, scaledWidth, scaledHeight, pixelFormat, nextFrame, 0, scaledWidth*3)
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

        // Load and start emulation from command line argument
        thread {
            with(nestlin) {
                println("[APP] Parameters: named=${parameters.named}, unnamed=${parameters.unnamed}")
                // Reconstruct the ROM path from parameters that were split on spaces.
                // Filter out any parameter that looks like a named flag (starts with --)
                // since those should be handled by parameters.named, not as part of the path.
                val nonFlagParams = parameters.unnamed.filter { !it.startsWith("--") }
                val debugEnabled = !parameters.named["debug"].isNullOrEmpty() ||
                        parameters.unnamed.any { it.startsWith("--debug") }
                if (debugEnabled) enableLogging()
                val romPath = if (nonFlagParams.size > 1) {
                    nonFlagParams.joinToString(" ")
                } else {
                    nonFlagParams[0]
                }
                currentRomPath = Paths.get(romPath)
                load(currentRomPath!!)
                powerReset()
            }
        }.also { emulationThread = it }
    }

    private fun stopEmulation() {
        nestlin.stop()
        emulationThread?.join(1000)
        emulationThread = null
    }

    private fun startEmulation() {
        emulationThread = thread {
            nestlin.start()
        }
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
            currentRomPath = romPath
            nestlin.load(romPath)
            nestlin.powerReset()
            startEmulation()
        }
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
        nestlin.load(currentRomPath!!)
        nestlin.powerReset()
        startEmulation()
    }

    private fun handleExit() {
        stopEmulation()
        Platform.exit()
    }

    override fun stop() {
        nestlin.stop()
        running = false

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
                        line.open(format, 4096)
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
            // Replicate each pixel DISPLAY_SCALE times in both X and Y dimensions
            frame.scanlines.withIndex().forEach { (y, scanline) ->
                scanline.withIndex().forEach { (x, pixel) ->
                    val r = (pixel shr 16).toByte()
                    val g = (pixel shr 8).toByte()
                    val b = pixel.toByte()

                    // Write this pixel DISPLAY_SCALE x DISPLAY_SCALE times
                    for (dy in 0 until DISPLAY_SCALE) {
                        for (dx in 0 until DISPLAY_SCALE) {
                            val scaledX = x * DISPLAY_SCALE + dx
                            val scaledY = y * DISPLAY_SCALE + dy
                            val pixIdx = (scaledY * scaledWidth + scaledX) * 3
                            nextFrame[pixIdx] = r
                            nextFrame[pixIdx + 1] = g
                            nextFrame[pixIdx + 2] = b
                        }
                    }
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
                val path = screenshotManager.saveScreenshot(frameData, scaledWidth, scaledHeight)
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
