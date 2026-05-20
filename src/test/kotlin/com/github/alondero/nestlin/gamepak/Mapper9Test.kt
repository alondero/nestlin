package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

/**
 * Unit tests for Mapper 9 (MMC2 / PxROM).
 *
 * MMC2 powers Punch-Out!! and uses two mechanisms not present in earlier mappers:
 *  - PRG with a single switchable 8KB bank at $8000-$9FFF and three fixed 8KB
 *    banks at $A000-$FFFF (always the last three).
 *  - Two CHR 4KB windows whose bank registers are selected by a latch driven
 *    by PPU reads at $0FD8-$0FDF / $0FE8-$0FEF / $1FD8-$1FDF / $1FE8-$1FEF.
 */
class Mapper9Test {

    private fun buildIne(prg: ByteArray, chr: ByteArray, mapperId: Int = 9): ByteArray {
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

    /** Stamp each 8KB PRG bank with its bank index in byte 0. */
    private fun makeStampedPrg(banks8k: Int): ByteArray {
        val prg = ByteArray(banks8k * 0x2000)
        for (bank in 0 until banks8k) {
            prg[bank * 0x2000] = (bank and 0xFF).toByte()
            prg[bank * 0x2000 + 0x1FFF] = (bank xor 0xFF).toByte()
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

    private fun buildMapper(prgBanks8k: Int = 16, chrBanks4k: Int = 8): Mapper9 {
        // PRG must be a multiple of 16KB (2 banks); force even.
        val prg = makeStampedPrg(((prgBanks8k + 1) and 0xFE.toInt()))
        val chr = makeStampedChr(chrBanks4k)
        val gp = GamePak(buildIne(prg, chr))
        return gp.createMapper() as Mapper9
    }

    @Test
    fun `prg fixed banks at A000 C000 E000 are last three banks`() {
        // 16 banks of 8KB = 128KB PRG. Last three banks are 13, 14, 15.
        val mapper = buildMapper(prgBanks8k = 16)

        assertThat(mapper.cpuRead(0xA000).toUnsignedInt(), equalTo(13))
        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(14))
        assertThat(mapper.cpuRead(0xE000).toUnsignedInt(), equalTo(15))
    }

    @Test
    fun `prg fixed banks unchanged by writes to A000 register`() {
        val mapper = buildMapper(prgBanks8k = 16)

        // Write to PRG bank-select register at $A000 — should only affect $8000-$9FFF.
        mapper.cpuWrite(0xA000, 0x05.toSignedByte())

        assertThat(mapper.cpuRead(0xA000).toUnsignedInt(), equalTo(13))
        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(14))
        assertThat(mapper.cpuRead(0xE000).toUnsignedInt(), equalTo(15))
    }

    @Test
    fun `prg bank select sets switchable window at 8000`() {
        val mapper = buildMapper(prgBanks8k = 16)

        mapper.cpuWrite(0xA000, 0x03.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(3))

        mapper.cpuWrite(0xA000, 0x07.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `chr latch 0 read at 0FE8 selects FE bank for subsequent reads`() {
        val mapper = buildMapper(chrBanks4k = 8)

        // Configure: latch0 FD -> bank 1, latch0 FE -> bank 4.
        mapper.cpuWrite(0xB000, 0x01.toSignedByte()) // chrBank0FD = 1
        mapper.cpuWrite(0xC000, 0x04.toSignedByte()) // chrBank0FE = 4

        // Force latch0 to FD by reading at $0FD8.
        mapper.ppuRead(0x0FD8)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(1))

        // Read at $0FE8 should flip latch0 to FE for the NEXT read.
        mapper.ppuRead(0x0FE8)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(4))
    }

    @Test
    fun `chr latch trigger fires after returning data from current bank`() {
        // The read of $0FD8 itself must return data from whichever bank is
        // currently selected — latch only flips for subsequent reads.
        val mapper = buildMapper(chrBanks4k = 8)

        // Configure: latch0 FD -> bank 2, latch0 FE -> bank 5.
        mapper.cpuWrite(0xB000, 0x02.toSignedByte())
        mapper.cpuWrite(0xC000, 0x05.toSignedByte())

        // Force latch0 to FE first (by reading at $0FE8).
        mapper.ppuRead(0x0FE8)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(5))

        // Read at $0FD8 returns from FE bank (bank 5), then latches to FD for next read.
        mapper.ppuRead(0x0FD8)

        // Now next read should come from FD bank (2).
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(2))
    }

    @Test
    fun `chr latch 1 is independent of latch 0`() {
        val mapper = buildMapper(chrBanks4k = 8)

        // Configure both windows.
        mapper.cpuWrite(0xB000, 0x01.toSignedByte()) // chrBank0FD = 1
        mapper.cpuWrite(0xC000, 0x04.toSignedByte()) // chrBank0FE = 4
        mapper.cpuWrite(0xD000, 0x02.toSignedByte()) // chrBank1FD = 2
        mapper.cpuWrite(0xE000, 0x06.toSignedByte()) // chrBank1FE = 6

        // Set latch0 = FD (bank 1 in window 0).
        mapper.ppuRead(0x0FD8)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(1))

        // Flip latch1 via $1FE8. Window-0 selection must not change.
        mapper.ppuRead(0x1FE8)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
        assertThat(mapper.ppuRead(0x1000).toUnsignedInt(), equalTo(6))

        // Flip latch1 to FD.
        mapper.ppuRead(0x1FD8)
        assertThat(mapper.ppuRead(0x1000).toUnsignedInt(), equalTo(2))
        // Window 0 still on FD.
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `latch transitions only fire on bytes 0xFD8 to 0xFDF and 0xFE8 to 0xFEF`() {
        val mapper = buildMapper(chrBanks4k = 8)

        mapper.cpuWrite(0xB000, 0x01.toSignedByte()) // chrBank0FD = 1
        mapper.cpuWrite(0xC000, 0x04.toSignedByte()) // chrBank0FE = 4

        // Force FD.
        mapper.ppuRead(0x0FD8)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(1))

        // Reads at $0FD0-$0FD7 must NOT flip the latch.
        mapper.ppuRead(0x0FD0)
        mapper.ppuRead(0x0FD7)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(1))

        // Reads at $0FE0-$0FE7 must NOT flip the latch either.
        mapper.ppuRead(0x0FE0)
        mapper.ppuRead(0x0FE7)
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(1))

        // But any byte in $0FE8-$0FEF should flip.
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
