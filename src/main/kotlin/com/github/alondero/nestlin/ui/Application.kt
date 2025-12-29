package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.Nestlin
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
import java.nio.file.Paths
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
    // 4x larger buffer to hold magnified pixels
    private var nextFrame = ByteArray(scaledHeight * scaledWidth * 3)

    override fun start(stage: Stage) {
        this.stage = stage.apply {
            title = "Nestlin - ${DISPLAY_SCALE}x Magnification"
            scene = Scene(StackPane().apply { children.add(canvas) })
            show()
        }

        object: AnimationTimer() {
            override fun handle(now: Long) {
                val pixelWriter = canvas.graphicsContext2D.pixelWriter
                val pixelFormat = PixelFormat.getByteRgbInstance()

                // Write the full magnified buffer to the canvas
                pixelWriter.setPixels(0, 0, scaledWidth, scaledHeight, pixelFormat, nextFrame, 0, scaledWidth*3)
            }

        }.start()

        thread {
            with(nestlin) {
                println("[APP] Parameters: named=${parameters.named}, unnamed=${parameters.unnamed}")
                if (!parameters.named["debug"].isNullOrEmpty()) {
                    enableLogging()
                }
                // Check both named parameters and unnamed for diagnostic flag
                val hasDiagFlag = !parameters.named["ppu-diag"].isNullOrEmpty() ||
                                  parameters.unnamed.any { it.contains("--ppu-diag") }
                if (hasDiagFlag) {
                    println("[APP] PPU diagnostics enabled!")
                    // Sample frames 300-310 to see state after game has been running
                    enablePpuDiagnostics(300, 310)
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
    }

    override fun frameUpdated(frame: Frame) {
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