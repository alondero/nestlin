package com.github.alondero.nestlin.apu

import com.github.alondero.nestlin.perf.ExperimentalMixerTables
import com.github.alondero.nestlin.testutil.assertThrowsWithMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class MixerTablesTest {
    @Test
    fun `out of range pulse sums fail the table precondition`() {
        for (sum in intArrayOf(-1, 31, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertThrowsWithMessage<IllegalArgumentException>("pulse sum") { ExperimentalMixerTables.pulse(sum) }
        }
    }

    @Test
    fun `out of range TND components cannot alias neighboring tuples`() {
        for (value in intArrayOf(-1, 16, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertThrowsWithMessage<IllegalArgumentException>("triangle") { ExperimentalMixerTables.tnd(value, 0, 0) }
            assertThrowsWithMessage<IllegalArgumentException>("noise") { ExperimentalMixerTables.tnd(0, value, 0) }
        }
        for (value in intArrayOf(-1, 128, Int.MIN_VALUE, Int.MAX_VALUE)) {
            assertThrowsWithMessage<IllegalArgumentException>("dmc") { ExperimentalMixerTables.tnd(0, 0, value) }
        }
    }

    @Test
    fun `every pulse and TND entry matches the original expression bit for bit`() {
        for (sum in 0..30) {
            val expected = if (sum > 0) 95.88 / ((8128.0 / sum) + 100.0) else 0.0
            assertEquals(expected.toRawBits(), ExperimentalMixerTables.pulse(sum).toRawBits(), "pulse=$sum")
        }
        for (triangle in 0..15) for (noise in 0..15) for (dmc in 0..127) {
            val sum = (triangle / 8227.0) + (noise / 12241.0) + (dmc / 22638.0)
            val expected = if (sum > 0.0) 159.79 / ((1.0 / sum) + 100.0) else 0.0
            assertEquals(expected.toRawBits(), ExperimentalMixerTables.tnd(triangle, noise, dmc).toRawBits(),
                "triangle=$triangle noise=$noise dmc=$dmc")
        }
    }
}
