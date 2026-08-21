package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.testutil.testGamePak
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Bus-conflict (GH #236) regression for Mapper 3 (CNROM).
 *
 * The original CNROM board decrements the CHR bank-select latch against
 * whatever PRG byte is currently on the data bus at the write address.
 * Per the NESdev wiki, the effective value is the CPU write AND-ed with
 * the PRG byte; only the low 2 bits of the result select the CHR bank.
 *
 * Commercial games (Star Soldier) write to addresses whose PRG byte
 * matches the write value, so the mask is normally a no-op. These tests
 * construct PRG patterns where the byte at the write address differs
 * from the written value, so the AND mask has a visible effect.
 */
class Mapper3BusConflictTest {

    private fun newMapper(romByte: Byte, writeAddr: Int = 0x8000, prgKb: Int = 32): Mapper3 {
        val pak = testGamePak {
            mapper = 3
            this.prgKb = prgKb
            chrKb = 32
            fillPrg(0xFF.toByte())
            // Stamp the write address with the desired PRG byte.
            val offset = (writeAddr - 0x8000) and 0x7FFF
            prg[offset] = romByte
        }
        return pak.createMapper() as Mapper3
    }

    @Test
    fun `write value is ANDed with PRG byte at write address`() {
        // PRG[0x8000] = 0x0F. Writing 0xFF should yield effective 0x0F.
        val mapper = newMapper(romByte = 0x0F.toByte())

        mapper.cpuWrite(0x8000, 0xFF.toSignedByte())

        // CHR bank is the low 2 bits of the effective value.
        assertThat(mapper.snapshot().banks["chr"], equalTo(0x0F and 0x03))
    }

    @Test
    fun `write value with no common bits is masked to zero`() {
        // PRG[0x8000] = 0x0F. Writing 0xF0 should yield effective 0x00.
        val mapper = newMapper(romByte = 0x0F.toByte())

        mapper.cpuWrite(0x8000, 0xF0.toSignedByte())

        assertThat(mapper.snapshot().banks["chr"], equalTo(0))
    }

    @Test
    fun `write value that matches PRG byte is unchanged`() {
        val mapper = newMapper(romByte = 0x42.toByte())

        mapper.cpuWrite(0x8000, 0x42.toSignedByte())

        assertThat(mapper.snapshot().banks["chr"], equalTo(0x42 and 0x03))
    }

    @Test
    fun `16KB PRG mirrors the conflict at C000-FFFF (issue 231)`() {
        // Real CNROM with 16KB PRG ignores A14; the modulo in cpuRead
        // (and the bus-conflict mask) treats $C000-$FFFF as a mirror of
        // $8000-$BFFF. PRG[0x4000] wraps to PRG[0] for 16KB PRG, so
        // stamping PRG[0] is what the bus-conflict mask sees when the
        // game writes to $C000.
        val pak = testGamePak {
            mapper = 3
            prgKb = 16
            chrKb = 8
            fillPrg(0xFF.toByte())
            prg[0] = 0x0F.toByte()
        }
        val mapper = pak.createMapper() as Mapper3

        mapper.cpuWrite(0xC000, 0xFF.toSignedByte())

        // 0xFF AND 0x0F = 0x0F; CHR bank = 0x0F and 0x03 = 3.
        assertThat(mapper.snapshot().banks["chr"], equalTo(3))
    }

    @Test
    fun `non-stamp address writes bypass the conflict`() {
        // Writing to a non-stamp address (filled with 0xFF) makes the
        // mask a no-op. This is what test fixtures use to disable bus
        // conflicts via `fillPrg(0xFF)`.
        val mapper = newMapper(romByte = 0x0F.toByte(), writeAddr = 0x8010)

        mapper.cpuWrite(0x8010, 0xFF.toSignedByte())

        // PRG[0x8010] = 0xFF (filled), so 0xFF AND 0xFF = 0xFF.
        // CHR bank = 0xFF and 0x03 = 3.
        assertThat(mapper.snapshot().banks["chr"], equalTo(3))
    }
}
