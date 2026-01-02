package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.Controller
import com.github.alondero.nestlin.input.GamepadInput
import com.github.alondero.nestlin.input.InputConfig
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ppu.RESOLUTION_HEIGHT
import com.github.alondero.nestlin.ppu.RESOLUTION_WIDTH
import javafx.animation.AnimationTimer
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.image.PixelFormat
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import tornadofx.App
import java.io.IOException
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

        thread {
            with(nestlin) {
                println("[APP] Parameters: named=${parameters.named}, unnamed=${parameters.unnamed}")
                if (!parameters.named["debug"].isNullOrEmpty()) {
                    enableLogging()
                }
                load(Paths.get(parameters.unnamed[0]))
                powerReset()
                start()
            }
        }
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
        var exitReason = "stopped"

        while (running && audioEnabled) {
            try {
                val samples = nestlin.getAudioSamples()

                if (samples.isNotEmpty()) {
                    var offset = 0
                    while (offset < samples.size) {
                        val count = minOf(maxSamplesPerWrite, samples.size - offset)
                        when {
                            format.sampleSizeInBits == 16 -> {
                                // Convert shorts to bytes
                                if (format.isBigEndian) {
                                    // Big-endian: MSB first
                                    for (i in 0 until count) {
                                        val sample = samples[offset + i].toInt()
                                        buffer[i * 2] = (sample shr 8).toByte()
                                        buffer[i * 2 + 1] = (sample and 0xFF).toByte()
                                    }
                                } else {
                                    // Little-endian: LSB first
                                    for (i in 0 until count) {
                                        val sample = samples[offset + i].toInt()
                                        buffer[i * 2] = (sample and 0xFF).toByte()
                                        buffer[i * 2 + 1] = (sample shr 8).toByte()
                                    }
                                }
                                audioLine?.write(buffer, 0, count * 2)
                            }
                            format.sampleSizeInBits == 8 -> {
                                // Convert shorts to 8-bit unsigned
                                for (i in 0 until count) {
                                    // Scale from -32768..32767 to 0..255
                                    val scaledValue = ((samples[offset + i].toInt() + 32768) shr 8).toByte()
                                    buffer[i] = scaledValue
                                }
                                audioLine?.write(buffer, 0, count)
                            }
                        }
                        offset += count
                    }
                } else {
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

        if (exitReason != "error") {
            exitReason = if (!running) "stopped" else "disabled"
        }
        println("[AUDIO] Audio thread terminated (${exitReason})")
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
