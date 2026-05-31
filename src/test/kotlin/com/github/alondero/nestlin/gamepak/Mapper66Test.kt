package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

/**
 * Tests for Mapper 66 (GxROM) - 32KB PRG and 8KB CHR bank switching.
 *
 * Games: Super Mario Bros. + Duck Hunt, Dragon Power, Gumshoe
 */
class Mapper66Test {

    private fun buildIne(prg: ByteArray, chr: ByteArray, mapperId: Int = 66): ByteArray {
        require(prg.size % 0x8000 == 0) { "PRG must be a multiple of 32KB" }
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

    private fun makeStampedPrg(banks32k: Int): ByteArray {
        val prg = ByteArray(banks32k * 0x8000)
        for (bank in 0 until banks32k) {
            prg[bank * 0x8000] = bank.toByte()
            prg[bank * 0x8000 + 0x7FFF] = (bank xor 0xFF).toByte()
        }
        return prg
    }

    private fun makeStampedChr(banks8k: Int): ByteArray {
        val chr = ByteArray(banks8k * 0x2000)
        for (bank in 0 until banks8k) {
            chr[bank * 0x2000] = bank.toByte()
            chr[bank * 0x2000 + 0x1FFF] = (bank xor 0xFF).toByte()
        }
        return chr
    }

    private fun newMapper66(prgBanks: Int = 2, chrBanks: Int = 4): Mapper66 {
        val prg = makeStampedPrg(prgBanks)
        val chr = makeStampedChr(chrBanks)
        val gp = GamePak(buildIne(prg, chr))
        return gp.createMapper() as Mapper66
    }

    @Test
    fun `defaults to bank 0`() {
        val m = newMapper66()
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x1FFF).toUnsignedInt(), equalTo(0 xor 0xFF))
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `write to $8000 selects PRG and CHR banks`() {
        val m = newMapper66()
        // xxPP xxCC: PRG bank 1 (bits 4-5), CHR bank 1 (bits 0-1) => 0x11
        m.cpuWrite(0x8000, 0x11.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `PRG bank 1 selected via bit 4`() {
        val m = newMapper66()
        m.cpuWrite(0x8000, 0x10.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
        assertThat(m.cpuRead(0xFFFF).toUnsignedInt(), equalTo(1 xor 0xFF))
    }

    @Test
    fun `CHR bank 1 selected via bit 0`() {
        val m = newMapper66()
        m.cpuWrite(0x8000, 0x01.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
        assertThat(m.ppuRead(0x1FFF).toUnsignedInt(), equalTo(1 xor 0xFF))
    }

    @Test
    fun `PRG bits 4-5 select 32KB bank`() {
        val m = newMapper66(prgBanks = 4)
        for (bank in 0..3) {
            m.cpuWrite(0x8000, (bank shl 4).toSignedByte())
            assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(bank))
        }
    }

    @Test
    fun `CHR bits 0-1 select 8KB bank`() {
        val m = newMapper66(chrBanks = 4)
        for (bank in 0..3) {
            m.cpuWrite(0x8000, bank.toSignedByte())
            assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(bank))
        }
    }

    @Test
    fun `PRG and CHR bank fields are independent`() {
        // Writing PRG bits must not disturb the CHR bank and vice-versa —
        // the original decode aliased them because the fields overlapped.
        val m = newMapper66(prgBanks = 4, chrBanks = 4)
        m.cpuWrite(0x8000, 0x30.toSignedByte()) // PRG bank 3, CHR bank 0
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        m.cpuWrite(0x8000, 0x03.toSignedByte()) // PRG bank 0, CHR bank 3
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(3))
    }

    @Test
    fun `write to $C000 selects bank (upper PRG window)`() {
        val m = newMapper66()
        m.cpuWrite(0xC000, 0x10.toSignedByte()) // PRG bank 1
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `write to $FFFF selects bank (top of PRG window)`() {
        val m = newMapper66()
        m.cpuWrite(0xFFFF, 0x10.toSignedByte()) // PRG bank 1
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `combined PRG and CHR bank selection`() {
        val m = newMapper66(prgBanks = 4, chrBanks = 4)
        // PRG bank 1 (bits 4-5), CHR bank 1 (bits 0-1) => 0x11
        m.cpuWrite(0x8000, 0x11.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `snapshot contains correct banks`() {
        val m = newMapper66()
        // PRG bank 3 (bits 4-5 = 11), CHR bank 1 (bits 0-1 = 01) => 0x31
        m.cpuWrite(0x8000, 0x31.toSignedByte())
        val snap = m.snapshot()
        assertThat(snap.banks["prg"], equalTo(3))
        assertThat(snap.banks["chr"], equalTo(1))
    }
}