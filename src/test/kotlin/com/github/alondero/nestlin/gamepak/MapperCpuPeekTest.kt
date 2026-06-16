package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.testutil.testGamePak
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * [Mapper.cpuPeek] default behaviour (issue #168). The default delegates to
 * [Mapper.cpuRead], which is pure for every mapper currently implemented, so peek
 * and read must agree byte-for-byte across the cartridge window.
 */
class MapperCpuPeekTest {

    @Test
    fun `default cpuPeek returns the same value as cpuRead`() {
        val mapper = testGamePak { mapper = 0; prgKb = 32; chrKb = 8 }.createMapper()

        for (addr in 0x8000..0xFFFF step 0x111) {
            assertThat(mapper.cpuPeek(addr), equalTo(mapper.cpuRead(addr)))
        }
    }
}
