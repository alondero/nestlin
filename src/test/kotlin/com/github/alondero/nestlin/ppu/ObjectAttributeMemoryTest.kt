package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.DataInputStream

class ObjectAttributeMemoryTest {

    @Test
    fun `attribute writes clear unimplemented bits 2 through 4`() {
        val oam = ObjectAttributeMemory()

        oam[2] = 0xFF.toByte()

        assertThat(oam[2].toUnsignedInt(), equalTo(0xE3))
    }

    @Test
    fun `loading state clears unimplemented attribute bits`() {
        val oam = ObjectAttributeMemory()
        val saved = ByteArray(256) { 0xFF.toByte() }

        oam.loadState(DataInputStream(ByteArrayInputStream(saved)))

        assertThat(oam[2].toUnsignedInt(), equalTo(0xE3))
        assertThat(oam[1].toUnsignedInt(), equalTo(0xFF))
    }

    @Test
    fun `non-attribute writes preserve all bits`() {
        val oam = ObjectAttributeMemory()

        oam[1] = 0xFF.toByte()

        assertThat(oam[1].toUnsignedInt(), equalTo(0xFF))
    }
}
