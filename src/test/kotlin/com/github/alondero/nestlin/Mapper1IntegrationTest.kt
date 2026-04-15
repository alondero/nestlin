package com.github.alondero.nestlin

import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ui.FrameListener
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.greaterThanOrEqualTo
import org.junit.Test
import java.nio.file.Paths

class Mapper1IntegrationTest {

    @Test
    fun `tetris enables rendering within 120 frames`() {
        var renderingEnabled = false
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(Paths.get("testroms/tetris.nes"))
            var frameCount = 0
            addFrameListener(object : FrameListener {
                override fun frameUpdated(frame: Frame) {
                    val mask = ppuMask()
                    if (mask and 0x18 != 0) renderingEnabled = true
                    if (++frameCount >= 120) stop()
                }
            })
        }
        nestlin.powerReset()
        nestlin.start()
        assertThat(renderingEnabled, equalTo(true))
    }

    @Test
    fun `tetris frame is not all black after 120 frames`() {
        var lastFrame: Frame? = null
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(Paths.get("testroms/tetris.nes"))
            var frameCount = 0
            addFrameListener(object : FrameListener {
                override fun frameUpdated(frame: Frame) {
                    lastFrame = frame
                    if (++frameCount >= 120) stop()
                }
            })
        }
        nestlin.powerReset()
        nestlin.start()
        assertThat(lastFrame!!.scanlines.any { scanline -> scanline.any { it != 0x000000 } }, equalTo(true))
    }

    @Test
    fun `lolo1 enables rendering within 120 frames`() {
        var renderingEnabled = false
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(Paths.get("testroms/lolo1.nes"))
            var frameCount = 0
            addFrameListener(object : FrameListener {
                override fun frameUpdated(frame: Frame) {
                    val mask = ppuMask()
                    if (mask and 0x18 != 0) renderingEnabled = true
                    if (++frameCount >= 120) stop()
                }
            })
        }
        nestlin.powerReset()
        nestlin.start()
        assertThat(renderingEnabled, equalTo(true))
    }

    @Test
    fun `chipndale enables rendering within 120 frames`() {
        var renderingEnabled = false
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(Paths.get("testroms/chipndale.nes"))
            var frameCount = 0
            addFrameListener(object : FrameListener {
                override fun frameUpdated(frame: Frame) {
                    val mask = ppuMask()
                    if (mask and 0x18 != 0) renderingEnabled = true
                    if (++frameCount >= 120) stop()
                }
            })
        }
        nestlin.powerReset()
        nestlin.start()
        assertThat(renderingEnabled, equalTo(true))
    }

    @Test
    fun `tetris renders non-black frames at 3 second intervals`() {
        val nonBlackFrameCount = java.util.concurrent.atomic.AtomicInteger(0)
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = true  // Enable real-time throttling
            load(Paths.get("testroms/tetris.nes"))
            var frameCount = 0
            addFrameListener(object : FrameListener {
                override fun frameUpdated(frame: Frame) {
                    // Capture at 3s, 6s, 9s, 12s, 15s (every 180 frames at 60fps)
                    if (frameCount > 0 && frameCount % 180 == 0) {
                        val nonBlackPixels = frame.scanlines
                            .sumOf { scanline -> scanline.count { it != 0x000000 } }
                        if (nonBlackPixels > 0) nonBlackFrameCount.incrementAndGet()
                    }
                    if (++frameCount > 900) stop()  // 15 seconds at 60fps
                }
            })
        }
        nestlin.powerReset()
        nestlin.start()
        assertThat(nonBlackFrameCount.get(), greaterThanOrEqualTo(3))
    }

    @Test
    fun `lolo1 renders non-black frames at 3 second intervals`() {
        val nonBlackFrameCount = java.util.concurrent.atomic.AtomicInteger(0)
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = true
            load(Paths.get("testroms/lolo1.nes"))
            var frameCount = 0
            addFrameListener(object : FrameListener {
                override fun frameUpdated(frame: Frame) {
                    if (frameCount > 0 && frameCount % 180 == 0) {
                        val nonBlackPixels = frame.scanlines
                            .sumOf { scanline -> scanline.count { it != 0x000000 } }
                        if (nonBlackPixels > 0) nonBlackFrameCount.incrementAndGet()
                    }
                    if (++frameCount > 900) stop()
                }
            })
        }
        nestlin.powerReset()
        nestlin.start()
        assertThat(nonBlackFrameCount.get(), greaterThanOrEqualTo(3))
    }

    @Test
    fun `chipndale renders non-black frames at 3 second intervals`() {
        val nonBlackFrameCount = java.util.concurrent.atomic.AtomicInteger(0)
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = true
            load(Paths.get("testroms/chipndale.nes"))
            var frameCount = 0
            addFrameListener(object : FrameListener {
                override fun frameUpdated(frame: Frame) {
                    if (frameCount > 0 && frameCount % 180 == 0) {
                        val nonBlackPixels = frame.scanlines
                            .sumOf { scanline -> scanline.count { it != 0x000000 } }
                        if (nonBlackPixels > 0) nonBlackFrameCount.incrementAndGet()
                    }
                    if (++frameCount > 900) stop()
                }
            })
        }
        nestlin.powerReset()
        nestlin.start()
        assertThat(nonBlackFrameCount.get(), greaterThanOrEqualTo(3))
    }
}
