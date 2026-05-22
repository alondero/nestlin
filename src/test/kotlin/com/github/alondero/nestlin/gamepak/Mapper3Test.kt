package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

/**
 * Per NESdev CNROM spec (iNES mapper 3): the CHR bank-select register
 * decodes anywhere in the entire $8000-$FFFF PRG window, not just the
 * bottom 8KB. Regression test for Star Soldier title-screen corruption
 * (issue #43) — the real ROM writes its bank selects to $C000-$DFFF,
 * which the previous implementation silently dropped.
 */
class Mapper3Test {

    /** Build a minimal valid iNES-1.0 image with the supplied PRG and CHR. */
    private fun buildIne(prg: ByteArray, chr: ByteArray, mapperId: Int = 3): ByteArray {
        require(prg.size % 0x4000 == 0) { "PRG must be a multiple of 16KB" }
        require(chr.size % 0x2000 == 0) { "CHR must be a multiple of 8KB" }
        val header = ByteArray(16).apply {
            this[0] = 'N'.code.toByte(); this[1] = 'E'.code.toByte()
            this[2] = 'S'.code.toByte(); this[3] = 0x1A.toByte()
            this[4] = (prg.size / 0x4000).toByte()
            this[5] = (chr.size / 0x2000).toByte()
            this[6] = ((mapperId and 0x0F) shl 4).toByte()
            this[7] = (mapperId and 0xF0).toByte()
        }
        return header + prg + chr
    }

    /** Stamp each 8KB CHR bank with its bank number so we can detect mis-banking. */
    private fun makeStampedChr(banks8k: Int): ByteArray {
        val chr = ByteArray(banks8k * 0x2000)
        for (bank in 0 until banks8k) {
            chr[bank * 0x2000] = bank.toByte()
            chr[bank * 0x2000 + 0x1FFF] = (bank xor 0xFF).toByte()
        }
        return chr
    }

    private fun newMapper3(): Mapper3 {
        val chr = makeStampedChr(banks8k = 4)          // 32KB CHR — matches Star Soldier
        val prg = ByteArray(0x4000 * 2)                // 32KB PRG
        val gp = GamePak(buildIne(prg, chr))
        return gp.createMapper() as Mapper3
    }

    @Test
    fun `defaults to bank 0`() {
        val m = newMapper3()
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x1FFF).toUnsignedInt(), equalTo(0 xor 0xFF))
    }

    @Test
    fun `write to $8000 selects CHR bank`() {
        val m = newMapper3()
        m.cpuWrite(0x8000, 2.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(2))
        assertThat(m.ppuRead(0x1FFF).toUnsignedInt(), equalTo(2 xor 0xFF))
    }

    /**
     * Star Soldier writes its bank-select to $C000-$DFFF (verified via
     * StarSoldierBaselineTest write trace). Real CNROM responds anywhere
     * in $8000-$FFFF — this test fails before the fix.
     */
    @Test
    fun `write to $C000 selects CHR bank (Star Soldier path)`() {
        val m = newMapper3()
        m.cpuWrite(0xC000, 1.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `write to $D030 selects CHR bank (exact Star Soldier address)`() {
        val m = newMapper3()
        // $31 is one of the two values the real ROM writes; low 2 bits = bank 1.
        m.cpuWrite(0xD030, 0x31.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `write to $FFFF selects CHR bank (upper window boundary)`() {
        val m = newMapper3()
        m.cpuWrite(0xFFFF, 3.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(3))
    }
}
