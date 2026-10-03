package com.github.alondero.nestlin.apu

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt
import kotlin.random.Random

class ResamplerEquivalenceTest {
    @Test
    fun `partial pushes preserve interpolation and negative positions after overflow`() {
        for (capacity in intArrayOf(1, 3, 7, 17)) for (rate in doubleArrayOf(22050.0, 44100.0, 48000.0, 96000.0)) {
            val actual = AudioResampler(44100.0, rate, capacity)
            val original = OriginalAudioResampler(44100.0, rate, capacity)
            val random = Random(capacity)
            repeat(2000) {
                if (random.nextInt(50) == 0) {
                    actual.clear()
                    original.clear()
                }
                val count = random.nextInt(capacity * 3 + 1)
                val input = ShortArray(count + 11) { random.nextInt().toShort() }
                actual.push(input, count)
                original.push(input.copyOf(count))
                val limit = random.nextInt(80)
                val expected = ShortArray(80) { -321 }
                val output = expected.copyOf()
                assertEquals(original.resample(expected, limit), actual.resample(output, limit),
                    "capacity=$capacity rate=$rate iteration=$it")
                assertArrayEquals(expected, output)
            }
        }
    }
}

// Frozen pre-#324 implementation: differential oracle for overflow/negative-position behavior.
private class OriginalAudioResampler(
    inputRate: Double,
    outputRate: Double,
    bufferCapacity: Int = 16384
) {
    private val ratio = inputRate / outputRate
    private val buffer = ShortArray(bufferCapacity)
    private var head = 0
    private var tail = 0
    private var size = 0
    private var position = 0.0

    fun push(samples: ShortArray) {
        for (sample in samples) {
            if (size < buffer.size) {
                buffer[tail] = sample
                tail = ((tail + 1) % buffer.size + buffer.size) % buffer.size
                size++
            } else {
                // Drop oldest sample to avoid unbounded growth.
                buffer[tail] = sample
                tail = ((tail + 1) % buffer.size + buffer.size) % buffer.size
                head = ((head + 1) % buffer.size + buffer.size) % buffer.size
                // Decrement position to account for dropped sample.
                // Position can go negative when position < 1, which is OK -
                // the next resample() call will properly discard samples based on floor(position).
                position -= 1.0
            }
        }
    }

    fun resample(output: ShortArray, maxSamples: Int): Int {
        if (maxSamples <= 0) return 0

        var produced = 0
        while (produced < maxSamples) {
            val idx = position.toInt()
            if (idx + 1 >= size) break

            val s0 = sampleAt(idx).toInt()
            val s1 = sampleAt(idx + 1).toInt()
            val frac = position - idx
            val mixed = s0 + ((s1 - s0) * frac)
            output[produced] = mixed.roundToInt().coerceIn(-32768, 32767).toShort()
            produced++
            position += ratio
        }

        // After the loop, `position` points to the next input sample to interpolate
        // from. Everything before floor(position) has been fully consumed and can be
        // discarded; the fractional part stays as the offset for the next call.
        if (position < 0) {
            // Defensive: push() can decrement position when dropping samples on overflow.
            head = 0
            position = 0.0
        } else {
            val toDiscard = position.toInt()
            if (toDiscard > 0) {
                discard(toDiscard)
                position -= toDiscard
            }
        }

        return produced
    }

    fun clear() {
        head = 0
        tail = 0
        size = 0
        position = 0.0
    }

    private fun sampleAt(offset: Int): Short {
        // Ensure positive index by using proper modulo for potentially negative values
        val index = ((head + offset) % buffer.size + buffer.size) % buffer.size
        return buffer[index]
    }

    private fun discard(count: Int) {
        val toDrop = minOf(count, size)
        if (toDrop <= 0) return
        head = ((head + toDrop) % buffer.size + buffer.size) % buffer.size
        size -= toDrop
    }
}
