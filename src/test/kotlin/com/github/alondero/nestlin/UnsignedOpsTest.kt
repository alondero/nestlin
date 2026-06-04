package com.github.alondero.nestlin

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Assertions
import org.junit.jupiter.api.Test

class UnsignedOpsTest {

    @Test
    fun applies2sComplementCorrectlyToByte() {
        assertThat(0x80.toSignedByte(), equalTo((-128).toByte()))
    }

    @Test
    fun applies2sComplementCorrectlyToShort() {
        assertThat(0xFFFF.toSignedShort(), equalTo((-1).toShort()))
    }

    @Test
    fun convertsToShortAndBackPreservingValue() {
        assertThat(0xFFFF.toSignedShort().toUnsignedInt(), equalTo(0xFFFF))
    }

    @Test
    fun convertsToByteAndBackPreservingValue() {
        assertThat(0xFF.toSignedByte().toUnsignedInt(), equalTo(0xFF))
    }

    @Test
    fun correctlyIdentifiesSetBit() {
        Assertions.assertFalse(0b00110011.toSignedByte().isBitSet(3))
        Assertions.assertTrue(0b00110011.toSignedByte().isBitSet(0))
    }
}



