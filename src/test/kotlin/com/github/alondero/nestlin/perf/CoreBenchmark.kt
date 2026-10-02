package com.github.alondero.nestlin.perf

import com.github.alondero.nestlin.Region
import com.sun.management.ThreadMXBean
import java.io.ByteArrayOutputStream
import java.lang.management.ManagementFactory
import java.security.MessageDigest
import java.util.HexFormat
import java.util.Locale

/** Manual benchmark: no wall-clock assertions in the test suite. See docs/PERFORMANCE.md. */
object CoreBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val frames = args.getOrNull(0)?.toInt() ?: 600
        val warmup = args.getOrNull(1)?.toInt() ?: 300
        require(frames > 0 && warmup >= 0)
        run("NTSC-render-rewind", Region.NTSC, true, true, frames, warmup)
        run("NTSC-render", Region.NTSC, true, false, frames, warmup)
        run("PAL-render-rewind", Region.PAL, true, true, frames, warmup)
        run("NTSC-blank", Region.NTSC, false, false, frames, warmup)
    }

    private fun run(name: String, region: Region, render: Boolean, rewind: Boolean, frames: Int, warmup: Int) {
        val bean = ManagementFactory.getThreadMXBean() as? ThreadMXBean
        val allocationSupported = bean?.isThreadAllocatedMemorySupported == true
        if (allocationSupported) requireNotNull(bean).isThreadAllocatedMemoryEnabled = true
        val threadId = Thread.currentThread().threadId()
        fun allocated(): Long = if (allocationSupported) requireNotNull(bean).getThreadAllocatedBytes(threadId) else 0
        val emu = RenderingWorkload.create(region, render, rewind)
        repeat(warmup) {
            RenderingWorkload.runFrame(emu)
            emu.getAudioSamples()
        }
        val nanos = LongArray(frames)
        var bytes = 0L
        val audio = MessageDigest.getInstance("SHA-256")
        val pixels = MessageDigest.getInstance("SHA-256")
        val rgb = ByteArray(256 * 240 * 3)
        repeat(frames) { i ->
            val beforeBytes = allocated()
            val start = System.nanoTime()
            RenderingWorkload.runFrame(emu)
            nanos[i] = System.nanoTime() - start
            bytes += allocated() - beforeBytes
            // Hash outside the measured section; do not count benchmark copies as core work.
            for (sample in emu.getAudioSamples()) {
                audio.update((sample.toInt() ushr 8).toByte())
                audio.update(sample.toByte())
            }
            var offset = 0
            for (row in emu.ppu.publishedFrame.scanlines) for (pixel in row) {
                rgb[offset++] = (pixel ushr 16).toByte()
                rgb[offset++] = (pixel ushr 8).toByte()
                rgb[offset++] = pixel.toByte()
            }
            pixels.update(rgb)
        }
        val state = ByteArrayOutputStream().also { emu.saveState(it) }.toByteArray()
        nanos.sort()
        val allocationReport = if (allocationSupported) "bytes/frame=${bytes / frames}" else "bytes/frame=unavailable"
        println(String.format(Locale.ROOT,
            "%s median=%.3fms p95=%.3fms p99=%.3fms max=%.3fms %s",
            name, nanos[frames / 2] / 1e6, nanos[(frames * .95).toInt()] / 1e6,
            nanos[(frames * .99).toInt()] / 1e6, nanos.last() / 1e6, allocationReport))
        val hex = HexFormat.of()
        println("state=${hex.formatHex(MessageDigest.getInstance("SHA-256").digest(state))} " +
            "frame=${hex.formatHex(pixels.digest())} audio=${hex.formatHex(audio.digest())}")
    }
}
