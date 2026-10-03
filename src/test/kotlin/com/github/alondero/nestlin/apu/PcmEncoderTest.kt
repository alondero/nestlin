package com.github.alondero.nestlin.apu

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmEncoderTest {
    @Test
    fun `16-bit encoding preserves every signed value in both byte orders`() {
        val samples = ShortArray(65536) { (it - 32768).toShort() }
        for (order in arrayOf(ByteOrder.BIG_ENDIAN, ByteOrder.LITTLE_ENDIAN)) {
            val expected = ByteBuffer.allocate(samples.size * 2).order(order)
            samples.forEach { expected.putShort(it) }
            val output = ByteArray(samples.size * 2 + 7) { 123 }
            assertEquals(samples.size * 2, PcmEncoder.encode(samples, samples.size, output, 16, order == ByteOrder.BIG_ENDIAN))
            assertArrayEquals(expected.array(), output.copyOf(samples.size * 2))
            for (i in samples.size * 2 until output.size) assertEquals(123.toByte(), output[i])
        }
    }

    @Test
    fun `8-bit fallback and partial conversion keep the existing bytes`() {
        val input = shortArrayOf(-32768, -1, 0, 32767, 1234)
        val output = ByteArray(6) { 99 }
        assertEquals(4, PcmEncoder.encode(input, 4, output, 8, false))
        assertArrayEquals(byteArrayOf(0, 127, -128, -1, 99, 99), output)
        assertEquals(0, PcmEncoder.encode(input, 0, output, 16, true))
    }
}
