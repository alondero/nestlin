package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.Controller
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ppu.RESOLUTION_HEIGHT
import com.github.alondero.nestlin.ppu.RESOLUTION_WIDTH
import fm2.Fm2Parser
import fm2.InputReplayer
import javafx.animation.AnimationTimer
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.image.PixelFormat
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import tornadofx.App
import java.io.File
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

    // Replay mode
    private var replayPath: String? = null
    private var romPathArg: String? = null

    override fun start(stage: Stage) {
        // Parse command line arguments
        val args = parameters.raw
        var romPath: String? = null

        var i = 0
        while (i < args.size) {
            when {
                args[i] == "--replay" && i + 1 < args.size -> replayPath = args[++i]
                !args[i].startsWith("--") -> romPath = args[i]
            }
            i++
        }

        if (romPath != null) {
            nestlin.load(Paths.get(romPath))

            // Initialize with replay if provided
            if (replayPath != null) {
                try {
                    val fm2Content = File(replayPath!!).readText()
                    val movie = Fm2Parser.parse(fm2Content)
                    val replayer = InputReplayer(movie.frames)
                    nestlin.setInputReplayer(replayer)
                    println("[APP] Loaded FM2 replay: $replayPath (${movie.frames.size} frames)")
                } catch (e: Exception) {
                    println("[APP] Failed to load replay: ${e.message}")
                    e.printStackTrace()
                }
            }
        }

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

        object: AnimationTimer() {
            override fun handle(now: Long) {
                val pixelWriter = canvas.graphicsContext2D.pixelWriter
                val pixelFormat = PixelFormat.getByteRgbInstance()

                // Thread-safe read of frame buffer
                synchronized(frameBufferLock) {
                    pixelWriter.setPixels(0, 0, scaledWidth, scaledHeight, pixelFormat, nextFrame, 0, scaledWidth*3)
                }
            }

        }.start()

        // Initialize audio playback
        initAudio()

        thread {
            with(nestlin) {
                println("[APP] Parameters: named=${parameters.named}, unnamed=${parameters.unnamed}")
                if (!parameters.named["debug"].isNullOrEmpty()) {
                    enableLogging()
                }
                if (!parameters.named["no-audio"].isNullOrEmpty()) {
                    audioEnabled = false
                    println("[APP] Audio disabled")
                }
                // Check both named parameters and unnamed for diagnostic flag
                val hasDiagFlag = !parameters.named["ppu-diag"].isNullOrEmpty() ||
                                  parameters.unnamed.any { it.contains("--ppu-diag") }
                if (hasDiagFlag) {
                    println("[APP] PPU diagnostics enabled!")
                    // Sample frames 0-5 to capture game initialization and early writes
                    enablePpuDiagnostics(0, 5)
                }
                load(Paths.get(parameters.unnamed[0]))
                powerReset()
                start()
            }
        }
        running = true
    }

    override fun stop() {
        nestlin.stop()
        running = false

        // Clean up audio
        audioLine?.stop()
        audioLine?.close()
        audioThread?.join(1000)  // Wait up to 1 second for audio thread to finish
    }

    private fun initAudio() {
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

        while (running && audioEnabled) {
            try {
                val samples = nestlin.getAudioSamples()

                if (samples.isNotEmpty()) {
                    when {
                        format.sampleSizeInBits == 16 -> {
                            // Convert shorts to bytes
                            if (format.isBigEndian) {
                                // Big-endian: MSB first
                                for (i in samples.indices) {
                                    buffer[i * 2] = (samples[i].toInt() shr 8).toByte()
                                    buffer[i * 2 + 1] = (samples[i].toInt() and 0xFF).toByte()
                                }
                            } else {
                                // Little-endian: LSB first
                                for (i in samples.indices) {
                                    buffer[i * 2] = (samples[i].toInt() and 0xFF).toByte()
                                    buffer[i * 2 + 1] = (samples[i].toInt() shr 8).toByte()
                                }
                            }
                            audioLine?.write(buffer, 0, samples.size * 2)
                        }
                        format.sampleSizeInBits == 8 -> {
                            // Convert shorts to 8-bit unsigned
                            for (i in samples.indices) {
                                // Scale from -32768..32767 to 0..255
                                val scaledValue = ((samples[i].toInt() + 32768) shr 8).toByte()
                                buffer[i] = scaledValue
                            }
                            audioLine?.write(buffer, 0, samples.size)
                        }
                    }
                } else {
                    Thread.sleep(1)  // Avoid busy-waiting
                }
            } catch (e: Exception) {
                if (running && audioEnabled) {
                    println("[AUDIO] Error in audio playback: ${e.message}")
                }
                break
            }
        }

        println("[AUDIO] Audio thread terminated")
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
        val controller = nestlin.getController1()
        when (code) {
            javafx.scene.input.KeyCode.Z -> controller.setButton(Controller.Button.A, pressed)
            javafx.scene.input.KeyCode.X -> controller.setButton(Controller.Button.B, pressed)
            javafx.scene.input.KeyCode.SPACE -> controller.setButton(Controller.Button.SELECT, pressed)
            javafx.scene.input.KeyCode.ENTER -> controller.setButton(Controller.Button.START, pressed)
            javafx.scene.input.KeyCode.UP -> controller.setButton(Controller.Button.UP, pressed)
            javafx.scene.input.KeyCode.DOWN -> controller.setButton(Controller.Button.DOWN, pressed)
            javafx.scene.input.KeyCode.LEFT -> controller.setButton(Controller.Button.LEFT, pressed)
            javafx.scene.input.KeyCode.RIGHT -> controller.setButton(Controller.Button.RIGHT, pressed)
            javafx.scene.input.KeyCode.S -> if (pressed) captureScreenshot()
            else -> {}
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