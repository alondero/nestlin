package com.github.alondero.nestlin.perf

import com.github.alondero.nestlin.testutil.AudioTestFixture
import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.apu.AudioBuffer
import com.sun.management.ThreadMXBean
import java.lang.management.ManagementFactory
import java.util.Locale
import com.github.alondero.nestlin.testutil.OriginalAudioBuffer

/** Manual timings, never test assertions. Keep drains outside the producer measurement. */
object AudioBenchmark {
    @JvmStatic
    fun main(args: Array<String>) {
        val bean = ManagementFactory.getThreadMXBean() as ThreadMXBean
        check(bean.isThreadAllocatedMemorySupported && bean.isCurrentThreadCpuTimeSupported)
        bean.isThreadAllocatedMemoryEnabled = true
        bean.isThreadCpuTimeEnabled = true
        val id = Thread.currentThread().threadId()
        val ring = AudioBuffer(bufferSize = 8192)
        val output = ShortArray(ring.capacity)
        val original = OriginalAudioBuffer(bufferSize = 8192)
        for (legacy in booleanArrayOf(true, false)) {
            var writeCpu = 0L
            var drainCpu = 0L
            var drainWall = 0L
            repeat(12000) { round ->
                val startWrite = bean.currentThreadCpuTime
                repeat(735) { if (legacy) original.write(it.toShort()) else ring.write(it.toShort()) }
                val endWrite = bean.currentThreadCpuTime
                val startRead = System.nanoTime()
                val count = if (legacy) original.read(output, output.size) else ring.read(output, output.size)
                val endReadWall = System.nanoTime()
                val endRead = bean.currentThreadCpuTime
                check(count == 735 && output[734] == 734.toShort())
                if (round >= 2000) {
                    writeCpu += endWrite - startWrite
                    drainCpu += endRead - endWrite
                    drainWall += endReadWall - startRead
                }
            }
            println(String.format(Locale.ROOT, "ring %s writeCPU=%.2fns/sample drainCPU=%.2fns/sample drainWall=%.2fns/sample",
                if (legacy) "original" else "bulk", writeCpu / 7350000.0, drainCpu / 7350000.0, drainWall / 7350000.0))
        }
        measureMixer()
        for (region in Region.entries) {
            val apu = AudioTestFixture.create(region, 1.0f)
            val cycles = (region.cpuFrequencyHz / 60).toInt()
            var tickCpu = 0L
            var pollCpu = 0L
            var pollBytes = 0L
            var samples = 0L
            repeat(1500) { frame ->
                val start = bean.currentThreadCpuTime
                repeat(cycles) { apu.tick() }
                val end = bean.currentThreadCpuTime
                val beforeBytes = bean.getThreadAllocatedBytes(id)
                val count = apu.getAudioSamples(output)
                val afterBytes = bean.getThreadAllocatedBytes(id)
                val drained = bean.currentThreadCpuTime
                if (frame >= 500) {
                    tickCpu += end - start
                    pollCpu += drained - end
                    pollBytes += afterBytes - beforeBytes
                    samples += count
                }
            }
            println(String.format(Locale.ROOT, "%s tick+mix=%.2fns/sample drain=%.2fns/sample drainBytes/poll=%d samples=%d",
                region, tickCpu.toDouble() / samples, pollCpu.toDouble() / samples, pollBytes / 1000, samples))
            for (expansion in floatArrayOf(0.0f, 1.0f, 20.0f)) println("$region expansion=$expansion pcm,state=${AudioWorkload.fingerprint(region, expansion)}")
        }
    }

    @Volatile private var sink = 0.0

    private fun measureMixer() {
        fun run(table: Boolean, iterations: Int): Long {
            var sum = 0.0
            val start = System.nanoTime()
            for (i in 0 until iterations) {
                val pulse = i % 31
                val triangle = (i ushr 11) and 15
                val noise = (i ushr 7) and 15
                val dmc = i and 127
                if (table) {
                    sum += (ExperimentalMixerTables.pulse(pulse) + ExperimentalMixerTables.tnd(triangle, noise, dmc)) * 0.9
                } else {
                    val p = if (pulse > 0) 95.88 / ((8128.0 / pulse) + 100.0) else 0.0
                    val tnd = (triangle / 8227.0) + (noise / 12241.0) + (dmc / 22638.0)
                    val t = if (tnd > 0.0) 159.79 / ((1.0 / tnd) + 100.0) else 0.0
                    sum += (p + t) * 0.9
                }
            }
            sink = sum
            return System.nanoTime() - start
        }
        repeat(6) { run(false, 1000000); run(true, 1000000) }
        val formula = LongArray(7)
        val table = LongArray(7)
        repeat(7) {
            formula[it] = run(false, 5000000)
            val expected = sink
            table[it] = run(true, 5000000)
            check(expected.toRawBits() == sink.toRawBits())
        }
        formula.sort()
        table.sort()
        println(String.format(Locale.ROOT, "mixer formula=%.2fns/sample experimentalTable=%.2fns/sample tablePayloadBytes=262392",
            formula[3] / 5000000.0, table[3] / 5000000.0))
    }
}
