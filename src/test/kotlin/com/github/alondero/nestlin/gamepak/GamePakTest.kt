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

        var b: Byte = 0xFE0.toByte()

        println(b)

        val s: Short = 0x8000.toShort()
        println(s)

        assertThat(header.mapper, equalTo(65)) // 01000001 = 0100 from byte 7 and 0001 from byte 6
    }
}
