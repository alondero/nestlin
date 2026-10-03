package com.github.alondero.nestlin.apu

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import com.github.alondero.nestlin.testutil.OriginalAudioResampler
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
