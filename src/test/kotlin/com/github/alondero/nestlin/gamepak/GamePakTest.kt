package com.github.alondero.nestlin.gamepak

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class GamePakTest {

    @Test
    fun MapperAsHighNybblesOf6ByteAsLowAnd7ByteAsHigh() {
        val header = Header(ByteArray(16, {when (it) {
            6 -> 0b00010000
            7 -> 0b01000000
            else -> 0
        }}))

        assertThat(header.mapper, equalTo(0b01000001)) // nybble(0100) b7, nybble(0001) b6
    }
}
