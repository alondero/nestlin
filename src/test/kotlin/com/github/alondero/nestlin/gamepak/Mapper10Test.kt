package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

/**
 * Unit tests for Mapper 10 (MMC4 / FxROM) — Fire Emblem Gaiden, Famicom Wars.
 *
 * MMC4 shares MMC2's CHR latch verbatim (see [[mmc2-mmc4-latch-read-then-flip-ordering-2026-05-20]]);
 * the only behavioural difference is PRG granularity:
 *  - PRG: a single switchable 16KB bank at $8000-$BFFF (selected by writes to
 *    $A000-$AFFF, low 4 bits) and a fixed last 16KB bank at $C000-$FFFF.
 *    (MMC2 instead switches an 8KB window and fixes the last three 8KB banks.)
 *  - 8KB PRG RAM at $6000-$7FFF (FxROM boards are battery-backed; persistence is
 *    out of scope here — the RAM just has to read/write so save-heavy RPGs boot).
 *
 * The CHR-latch tests are intentionally parallel to Mapper9Test so a regression in
 * the shared latch mechanism surfaces in both.
 */
class Mapper10Test {

    private fun buildIne(prg: ByteArray, chr: ByteArray, mapperId: Int = 10): ByteArray {
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

    /** Stamp each 16KB PRG bank with its bank index in byte 0. */
    private fun makeStampedPrg(banks16k: Int): ByteArray {
        val prg = ByteArray(banks16k * 0x4000)
        for (bank in 0 until banks16k) {
            prg[bank * 0x4000] = (bank and 0xFF).toByte()
            prg[bank * 0x4000 + 0x3FFF] = (bank xor 0xFF).toByte()
        }
        return prg
    }

    /** Stamp each 4KB CHR bank with its bank index in byte 0. */
    private fun makeStampedChr(banks4k: Int): ByteArray {
        val chr = ByteArray(banks4k * 0x1000)
        for (bank in 0 until banks4k) {
            chr[bank * 0x1000] = (bank and 0xFF).toByte()
            chr[bank * 0x1000 + 0x0FFF] = (bank xor 0xFF).toByte()
        }
        return chr
    }

    private fun buildMapper(prgBanks16k: Int = 8, chrBanks4k: Int = 8): Mapper10 {
        val prg = makeStampedPrg(prgBanks16k)
        val chr = makeStampedChr(chrBanks4k)
        val gp = GamePak(buildIne(prg, chr))
        return gp.createMapper() as Mapper10
    }

    @Test
    fun `createMapper returns Mapper10 for ines mapper id 10`() {
        val mapper = buildMapper()
        assertThat(mapper.snapshot().mapperId, equalTo(10))
    }

    @Test
    fun `prg fixed bank at C000 is the last 16KB bank`() {
        // 8 banks of 16KB = 128KB PRG. Last bank is 7.
        val mapper = buildMapper(prgBanks16k = 8)

        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(7))
        assertThat(mapper.cpuRead(0xFFFF).toUnsignedInt(), equalTo(7 xor 0xFF))
    }

    @Test
    fun `prg fixed bank unchanged by writes to A000 register`() {
        val mapper = buildMapper(prgBanks16k = 8)

        // Write to the PRG bank-select register — must only move the $8000 window.
        mapper.cpuWrite(0xA000, 0x05.toSignedByte())

        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `prg bank select sets switchable 16KB window at 8000`() {
        val mapper = buildMapper(prgBanks16k = 8)

        // Default switchable bank is 0.
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(0))

        mapper.cpuWrite(0xA000, 0x03.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(mapper.cpuRead(0xBFFF).toUnsignedInt(), equalTo(3 xor 0xFF))

        mapper.cpuWrite(0xA000, 0x06.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(6))
    }

    @Test
    fun `prg bank select uses only low 4 bits`() {
        val mapper = buildMapper(prgBanks16k = 8)

        // High nibble must be ignored; 0x13 -> bank 3.
        mapper.cpuWrite(0xA000, 0x13.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
    }

    @Test
    fun `prg ram at 6000-7FFF is readable and writable`() {
        val mapper = buildMapper()

        mapper.cpuWrite(0x6000, 0x42.toSignedByte())
        mapper.cpuWrite(0x7FFF, 0x99.toSignedByte())

        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
        assertThat(mapper.cpuRead(0x7FFF).toUnsignedInt(), equalTo(0x99))
    }

    @Test
    fun `chr latch 0 read at 0FE8 selects FE bank for subsequent reads`() {
        val mapper = buildMapper(chrBanks4k = 8)

        mapper.cpuWrite(0xB000, 0x01.toSignedByte()) // chrBank0FD = 1
        mapper.cpuWrite(0xC000, 0x04.toSignedByte()) // chrBank0FE = 4

        mapper.ppuRead(0x0FD8) // force latch0 = FD
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(1))

        mapper.ppuRead(0x0FE8) // flip latch0 = FE for the NEXT read
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(4))
    }

    @Test
    fun `chr latch trigger fires after returning data from current bank`() {
        val mapper = buildMapper(chrBanks4k = 8)

        mapper.cpuWrite(0xB000, 0x02.toSignedByte()) // chrBank0FD = 2
        mapper.cpuWrite(0xC000, 0x05.toSignedByte()) // chrBank0FE = 5

        mapper.ppuRead(0x0FE8) // latch0 = FE
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(5))

        // The $0FD8 read itself must still return the FE bank, only flipping after.
        mapper.ppuRead(0x0FD8)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(2))
    }

    @Test
    fun `chr latch 1 is independent of latch 0`() {
        val mapper = buildMapper(chrBanks4k = 8)

        mapper.cpuWrite(0xB000, 0x01.toSignedByte()) // chrBank0FD = 1
        mapper.cpuWrite(0xC000, 0x04.toSignedByte()) // chrBank0FE = 4
        mapper.cpuWrite(0xD000, 0x02.toSignedByte()) // chrBank1FD = 2
        mapper.cpuWrite(0xE000, 0x06.toSignedByte()) // chrBank1FE = 6

        mapper.ppuRead(0x0FD8)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(1))

        mapper.ppuRead(0x1FE8)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
        assertThat(mapper.ppuRead(0x1000).toUnsignedInt(), equalTo(6))

        mapper.ppuRead(0x1FD8)
        assertThat(mapper.ppuRead(0x1000).toUnsignedInt(), equalTo(2))
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `latch transitions only fire on bytes 0xFD8 to 0xFDF and 0xFE8 to 0xFEF`() {
        val mapper = buildMapper(chrBanks4k = 8)

        mapper.cpuWrite(0xB000, 0x01.toSignedByte()) // chrBank0FD = 1
        mapper.cpuWrite(0xC000, 0x04.toSignedByte()) // chrBank0FE = 4

        mapper.ppuRead(0x0FD8)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(1))

        mapper.ppuRead(0x0FD0)
        mapper.ppuRead(0x0FD7)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(1))

        mapper.ppuRead(0x0FE0)
        mapper.ppuRead(0x0FE7)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(1))

        mapper.ppuRead(0x0FEF)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(4))
    }

    @Test
    fun `mirroring register at F000 selects vertical or horizontal`() {
        val mapper = buildMapper()

        mapper.cpuWrite(0xF000, 0x00.toSignedByte())
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))

        mapper.cpuWrite(0xF000, 0x01.toSignedByte())
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))

        mapper.cpuWrite(0xFFFF, 0x00.toSignedByte())
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }
}
