package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.ppu.Frame
import javafx.application.Application
import javafx.scene.Scene
import javafx.scene.canvas.Canvas
import javafx.scene.image.PixelWriter
import javafx.scene.layout.StackPane
import javafx.stage.Stage
import tornadofx.App
import java.nio.file.Paths
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

fun main(args: Array<String>) {
    if (args.size == 0) {
        println("Please provide a rom file as an argument")
        return
    }

    Application.launch(NestlinApplication::class.java, *args)
}

class NestlinApplication: FrameListener, App() {
    private lateinit var stage: Stage
    private var canvas: Canvas
    private var pixelWriter: PixelWriter
    private var nestlin: Nestlin
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    init {
        nestlin = Nestlin().apply {addFrameListener(this@NestlinApplication)}
        canvas = Canvas()
        pixelWriter = canvas.graphicsContext2D.pixelWriter
    }

    override fun start(stage: Stage) {
        this.stage = stage
        stage.apply {
            title = "Nestlin"

            val sp = StackPane()
            sp.children.add(canvas)
            val scene = Scene(sp)
            stage.scene = scene

            show()
        }

        executor.submit {
            nestlin.apply {
                load(Paths.get(parameters.unnamed[0]))
                powerReset()
                start()
            }
        }
    }

    override fun stop() {
        nestlin.stop()
        executor.shutdown()
    }

    override fun frameUpdated(frame: Frame) {
        for ((y, scanline) in frame.scanlines.withIndex()) {
            for ((x, pixel) in scanline.withIndex()) {
                pixelWriter.setArgb(x, y, pixel)
            }
        }
    }
}