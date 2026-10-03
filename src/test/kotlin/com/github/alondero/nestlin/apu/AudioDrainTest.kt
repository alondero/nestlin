package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.perf.AudioWorkload
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.ArrayDeque
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

class AudioDrainTest {
    @Test
    fun `arbitrary capacities match a FIFO through wrap overflow clear and short reads`() {
        for (capacity in intArrayOf(1, 3, 7, 100)) {
            val ring = AudioBuffer(bufferSize = capacity)
            val expected = ArrayDeque<Short>()
            val random = Random(capacity)
            repeat(5000) {
                when (random.nextInt(10)) {
                    in 0..5 -> {
                        val sample = random.nextInt().toShort()
                        ring.write(sample)
                        if (expected.size == capacity) expected.removeFirst()
                        expected.addLast(sample)
                    }
                    9 -> { ring.clear(); expected.clear() }
                    else -> {
                        val output = ShortArray(random.nextInt(capacity + 2)) { -123 }
                        val requested = random.nextInt(capacity + 3)
                        val count = minOf(requested, output.size, expected.size)
                        assertEquals(count, ring.read(output, requested))
                        repeat(count) { assertEquals(expected.removeFirst(), output[it]) }
                        for (i in count until output.size) assertEquals(-123, output[i].toInt())
                    }
                }
                assertEquals(expected.size, ring.availableSamples())
            }
        }
    }

    @Test
    fun `simultaneous producer and consumer preserve every sample in order`() {
        val ring = AudioBuffer(bufferSize = 509)
        val consumed = AtomicInteger()
        val threads = Executors.newFixedThreadPool(2)
        try {
            val producer = threads.submit {
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                repeat(10000) {
                    // Bound outstanding samples so this test checks lossless concurrent wrap.
                    while (it - consumed.get() >= 400 && System.nanoTime() < deadline) Thread.yield()
                    check(System.nanoTime() < deadline)
                    ring.write(it.toShort())
                }
            }
            val consumer = threads.submit<Int> {
                val output = ShortArray(137)
                var next = 0
                val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
                while (next < 10000 && System.nanoTime() < deadline) {
                    val count = ring.read(output, output.size)
                    repeat(count) { assertEquals(next++, output[it].toInt()) }
                    consumed.set(next)
                    if (count == 0) Thread.yield()
                }
                next
            }
            producer.get(6, TimeUnit.SECONDS)
            assertEquals(10000, consumer.get(6, TimeUnit.SECONDS))
        } finally {
            threads.shutdownNow()
        }
    }

    @Test
    fun `reusable APU drains match allocating drains and preserve unused storage`() {
        for (region in Region.entries) {
            val allocating = AudioWorkload.create(region, 1.0f)
            val reusable = AudioWorkload.create(region, 1.0f)
            val output = ShortArray(113) { -123 }
            repeat(20) {
                repeat(30000) { allocating.tick(); reusable.tick() }
                val expected = allocating.getAudioSamples()
                var offset = 0
                while (true) {
                    output.fill(-123)
                    val count = reusable.getAudioSamples(output)
                    for (i in 0 until count) assertEquals(expected[offset++], output[i])
                    for (i in count until output.size) assertEquals(-123, output[i].toInt())
                    if (count == 0) break
                }
                assertEquals(expected.size, offset)
                assertEquals(0, reusable.getAudioSamples(ShortArray(0)))
            }
        }
    }
}
