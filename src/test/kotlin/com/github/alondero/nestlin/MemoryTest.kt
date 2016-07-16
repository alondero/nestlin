package com.github.alondero.nestlin

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class MemoryTest {
    @Test
    fun retrievesA16BitValuefromMemory() {
        val mem = Memory()
        mem[0] = 4
        mem[1] = -64

        assertThat(mem[0,1], equalTo((-16380).toShort()))
    }
}