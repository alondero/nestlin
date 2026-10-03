package com.github.alondero.nestlin.perf

import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.apu.AudioResampler
import com.github.alondero.nestlin.apu.PcmEncoder
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.LockSupport
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.LineEvent
import kotlin.concurrent.thread

/** Opt-in real-device probe. STOP events during running playback are device underflows. */
object AudioDeviceBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val seconds = args.getOrNull(0)?.toInt() ?: 30
        val stallMs = args.getOrNull(1)?.toLong() ?: 0L
        require(seconds >= 3 && stallMs >= 0)
        for (region in Region.entries) run(region, seconds, stallMs)
    }

    private fun run(region: Region, seconds: Int, stallMs: Long) {
        val format = AudioFormat(44100f, 16, 1, true, false)
        val line = try {
            AudioSystem.getSourceDataLine(format).also { it.open(format, 8192) }
        } catch (e: Exception) {
            println("$region device=unavailable (${e.message}); actual device underruns were not measured")
            return
        }
        val bufferBytes = line.bufferSize
        val running = AtomicBoolean(true)
        val measuring = AtomicBoolean(false)
        val played = AtomicBoolean(false)
        val underruns = AtomicInteger()
        val failure = AtomicReference<Throwable?>()
        val listener = javax.sound.sampled.LineListener { event ->
            if (event.type == LineEvent.Type.START) played.set(true)
            if (event.type == LineEvent.Type.STOP && played.get() && measuring.get() && running.get()) {
                underruns.incrementAndGet()
            }
        }
        line.addLineListener(listener)
        val emu = RenderingWorkload.create(region, rewind = true)
        // Warm the producer before starting the device and prefill 46 ms of silence.
        repeat(150) { RenderingWorkload.runFrame(emu); emu.getAudioSamples() }
        val prefill = ByteArray(minOf(4096, line.bufferSize))
        line.write(prefill, 0, prefill.size)
        val period = (1e9 / region.refreshRateHz).toLong()
        val producer = thread(name = "audio-device-producer") {
            try {
                var deadline = System.nanoTime()
                while (running.get()) {
                    RenderingWorkload.runFrame(emu)
                    deadline += period
                    val wait = deadline - System.nanoTime()
                    if (wait > 0) LockSupport.parkNanos(wait)
                }
            } catch (e: Throwable) {
                failure.set(e)
                running.set(false)
            }
        }
        val input = ShortArray(emu.apu.audioBufferCapacity())
        val output = ShortArray(1024)
        val bytes = ByteArray(2048)
        val resampler = AudioResampler(emu.getAudioSampleRateHz(), format.sampleRate.toDouble())
        var emptyPolls = 0L
        var idlePolls = 0L
        var minQueued = line.bufferSize
        var queueStarvations = 0L
        var queueWasEmpty = false
        var injected = false
        val start = System.nanoTime()
        val end = start + (seconds + 1L) * 1_000_000_000L
        line.start()
        try {
            while (running.get() && System.nanoTime() < end) {
                val elapsed = System.nanoTime() - start
                if (elapsed >= 1_000_000_000L) measuring.set(true)
                if (stallMs > 0 && !injected && elapsed >= (seconds / 2 + 1L) * 1_000_000_000L) {
                    injected = true
                    Thread.sleep(stallMs)
                }
                val count = emu.getAudioSamples(input)
                resampler.push(input, count)
                var produced = resampler.resample(output, output.size)
                if (measuring.get()) {
                    if (count == 0) emptyPolls++
                    if (produced == 0) idlePolls++
                    val queued = line.bufferSize - line.available()
                    minQueued = minOf(minQueued, queued)
                    if (queued == 0 && !queueWasEmpty) queueStarvations++
                    queueWasEmpty = queued == 0
                }
                val idle = produced == 0
                while (produced > 0) {
                    val encoded = PcmEncoder.encode(output, produced, bytes, 16, false)
                    check(line.write(bytes, 0, encoded) == encoded)
                    produced = resampler.resample(output, output.size)
                }
                if (idle) Thread.sleep(1)
            }
        } finally {
            measuring.set(false)
            running.set(false)
            LockSupport.unpark(producer)
            producer.join(10000)
            line.removeLineListener(listener)
            line.stop()
            line.close()
        }
        failure.get()?.let { throw it }
        check(!producer.isAlive) { "Audio producer did not stop" }
        println("$region device=${line.lineInfo} duration=${seconds}s bufferBytes=$bufferBytes " +
            "underflowStopEvents=${underruns.get()} deviceQueueStarvations=$queueStarvations emptyPolls=$emptyPolls idlePolls=$idlePolls " +
            "minQueuedBytes=$minQueued injectedStallMs=$stallMs")
        if (injected && underruns.get() == 0) {
            println("$region STOP-event probe did not detect the forced stall; zero events cannot establish zero device underruns")
        }
    }
}
