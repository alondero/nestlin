package com.github.alondero.nestlin.apu

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MixerTablesTest {
    @Test
    fun `every pulse and TND entry matches the original expression bit for bit`() {
        for (sum in 0..30) {
            val expected = if (sum > 0) 95.88 / ((8128.0 / sum) + 100.0) else 0.0
            assertEquals(expected.toRawBits(), MixerTables.pulse(sum).toRawBits(), "pulse=$sum")
        }
        for (triangle in 0..15) for (noise in 0..15) for (dmc in 0..127) {
            val sum = (triangle / 8227.0) + (noise / 12241.0) + (dmc / 22638.0)
            val expected = if (sum > 0.0) 159.79 / ((1.0 / sum) + 100.0) else 0.0
            assertEquals(expected.toRawBits(), MixerTables.tnd(triangle, noise, dmc).toRawBits(),
                "triangle=$triangle noise=$noise dmc=$dmc")
        }
    }
}
