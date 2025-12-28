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
    private var canvas = Canvas((RESOLUTION_WIDTH * DISPLAY_SCALE).toDouble(), (RESOLUTION_HEIGHT * DISPLAY_SCALE).toDouble())
    private var nestlin = Nestlin().also { it.addFrameListener(this) }
    private var running = false
    private var nextFrame = ByteArray(RESOLUTION_HEIGHT * RESOLUTION_WIDTH * 3)

    override fun start(stage: Stage) {
        this.stage = stage.apply {
            title = "Nestlin - ${DISPLAY_SCALE}x Magnification"
            scene = Scene(StackPane().apply { children.add(canvas) })
            show()
        }

        object: AnimationTimer() {
            override fun handle(now: Long) {
                val gc = canvas.graphicsContext2D

                // Scale the graphics context for 4x magnification
                gc.save()
                gc.scale(DISPLAY_SCALE.toDouble(), DISPLAY_SCALE.toDouble())

                val pixelWriter = gc.pixelWriter
                val pixelFormat = PixelFormat.getByteRgbInstance()

                pixelWriter.setPixels(0, 0, RESOLUTION_WIDTH, RESOLUTION_HEIGHT, pixelFormat, nextFrame, 0, RESOLUTION_WIDTH*3)

                gc.restore()
            }

        }.start()

        thread {
            with(nestlin) {
                if (!parameters.named["debug"].isNullOrEmpty()) {
                    enableLogging()
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
        frame.scanlines.withIndex().forEach { (y, scanline) ->
            scanline.withIndex().forEach { (x, pixel) ->
                val r = (pixel shr 16).toByte()
                val g = (pixel shr 8).toByte()
                val b = pixel.toByte()
                val pixIdx = (y * RESOLUTION_WIDTH + x) * 3
                nextFrame[pixIdx] = r
                nextFrame[pixIdx + 1] = g
                nextFrame[pixIdx + 2] = b
            }
        }
    }
}