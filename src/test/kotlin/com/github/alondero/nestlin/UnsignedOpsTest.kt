package com.github.alondero.nestlin

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Assert
import org.junit.Test

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
        Assert.assertFalse(0b00110011.toSignedByte().isBitSet(3))
        Assert.assertTrue(0b00110011.toSignedByte().isBitSet(0))
    }
}



