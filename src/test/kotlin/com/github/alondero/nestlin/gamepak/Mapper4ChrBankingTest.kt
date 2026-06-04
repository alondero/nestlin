package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Per NESdev MMC3 spec:
 *   R0, R1 select 2 KB CHR banks; the bank number is expressed in 1 KB units
 *   and the least significant bit is ignored (so adjacent 1 KB pages are paired
 *   to form a 2 KB window).
 *
 * Therefore for any bank value N written to R0 or R1, the byte at PPU
 * address (window_base + offset) must equal chrRom[(N and 0xFE) * 0x400 + offset].
 *
 * This is the regression test for the Kirby title-screen sprite corruption bug
 * (see kirby-title-screen-sprite-overlay-bug-2026-05-18 memory entry).
 * Before the fix, Mapper4 used a multiplier of 0x800 instead of 0x400,
 * doubling the read offset and pulling in completely unrelated CHR data.
 */
class Mapper4ChrBankingTest {

    /** Build a minimal valid iNES-1.0 image with the supplied PRG and CHR. */
    private fun buildIne(prg: ByteArray, chr: ByteArray, mapperId: Int = 4): ByteArray {
        require(prg.size % 0x4000 == 0) { "PRG must be a multiple of 16KB" }
        require(chr.size % 0x2000 == 0) { "CHR must be a multiple of 8KB" }
        val header = ByteArray(16).apply {
            this[0] = 'N'.code.toByte(); this[1] = 'E'.code.toByte()
            this[2] = 'S'.code.toByte(); this[3] = 0x1A.toByte()
            this[4] = (prg.size / 0x4000).toByte()
            this[5] = (chr.size / 0x2000).toByte()
            // flags6: mapper-low nibble in upper 4 bits
            this[6] = ((mapperId and 0x0F) shl 4).toByte()
            // flags7: mapper-high nibble in upper 4 bits
            this[7] = (mapperId and 0xF0).toByte()
        }
        return header + prg + chr
    }

    /** Stamp distinct bytes into every 1 KB CHR bank so we can detect mis-banking. */
    private fun makeStampedChr(banks1k: Int): ByteArray {
        val chr = ByteArray(banks1k * 0x400)
        for (bank in 0 until banks1k) {
            // Two distinct bytes per bank: bank number for first byte, ~bank for last byte
            chr[bank * 0x400] = (bank and 0xFF).toByte()
            chr[bank * 0x400 + 0x3FF] = (bank xor 0xFF).toByte()
        }
        return chr
    }

    /** Send a (register, value) MMC3 write pair: $8000 select then $8001 data. */
    private fun mmc3Write(mapper: Mapper4, bankSelect: Int, value: Int) {
        mapper.cpuWrite(0x8000, bankSelect.toSignedByte())
        mapper.cpuWrite(0x8001, value.toSignedByte())
    }

    @Test
    fun `R1 in inverted mode reads from (bank and 0xFE) times 0x400`() {
        // Big CHR so bank 186 is in range
        val chr = makeStampedChr(banks1k = 256)  // 256 KB CHR
        val prg = ByteArray(0x4000 * 2)          // 32 KB PRG (minimum-ish)
        val gp = GamePak(buildIne(prg, chr))
        val mapper = gp.createMapper() as Mapper4

        mmc3Write(mapper, 0x80 or 1, 186)  // R1=186, chrPrgInvert=true → R1 maps to $1800-$1FFF

        assertThat(mapper.ppuRead(0x1800).toUnsignedInt(), equalTo(186))
        assertThat(mapper.ppuRead(0x1800 + 0x3FF).toUnsignedInt(), equalTo(186 xor 0xFF))
        // Second 1KB of the 2KB window = next 1KB sub-bank
        assertThat(mapper.ppuRead(0x1C00).toUnsignedInt(), equalTo(187))
    }

    @Test
    fun `R0 in inverted mode reads from (bank and 0xFE) times 0x400`() {
        val chr = makeStampedChr(banks1k = 256)
        val prg = ByteArray(0x4000 * 2)
        val gp = GamePak(buildIne(prg, chr))
        val mapper = gp.createMapper() as Mapper4

        mmc3Write(mapper, 0x80 or 0, 100)  // R0=100, chrPrgInvert=true → R0 maps to $1000-$17FF

        assertThat(mapper.ppuRead(0x1000).toUnsignedInt(), equalTo(100))
        assertThat(mapper.ppuRead(0x1400).toUnsignedInt(), equalTo(101))
    }

    @Test
    fun `R0 in normal mode reads from (bank and 0xFE) times 0x400`() {
        val chr = makeStampedChr(banks1k = 256)
        val prg = ByteArray(0x4000 * 2)
        val gp = GamePak(buildIne(prg, chr))
        val mapper = gp.createMapper() as Mapper4

        mmc3Write(mapper, 0x00 or 0, 50)  // R0=50, chrPrgInvert=false → R0 maps to $0000-$07FF

        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(50))
        assertThat(mapper.ppuRead(0x0400).toUnsignedInt(), equalTo(51))
    }

    @Test
    fun `R0_R1 bit 0 is ignored - odd bank value gives same window as even`() {
        val chr = makeStampedChr(banks1k = 256)
        val prg = ByteArray(0x4000 * 2)
        val gp = GamePak(buildIne(prg, chr))
        val mapper = gp.createMapper() as Mapper4

        // Per spec, bit 0 is ignored on R0/R1, so odd bank value maps to the
        // same 2KB window as the previous even value.
        mmc3Write(mapper, 0x80 or 1, 185)
        assertThat(mapper.ppuRead(0x1800).toUnsignedInt(), equalTo(184))
    }
}
