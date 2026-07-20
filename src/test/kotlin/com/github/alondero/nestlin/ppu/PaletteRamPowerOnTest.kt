package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

class PaletteRamPowerOnTest {

    @Test
    fun `palette RAM starts with 2C02 power-on values`() {
        val palette = PaletteRam()
        val expected = intArrayOf(
            0x09, 0x01, 0x00, 0x01, 0x00, 0x02, 0x02, 0x0D,
            0x08, 0x10, 0x08, 0x24, 0x00, 0x00, 0x04, 0x2C,
            0x09, 0x01, 0x34, 0x03, 0x00, 0x04, 0x00, 0x14,
            0x08, 0x3A, 0x00, 0x02, 0x00, 0x20, 0x2C, 0x08
        )

        val actual = IntArray(0x20) { palette[it].toUnsignedInt() }

        assertThat(actual.toList(), equalTo(expected.toList()))
    }
}
