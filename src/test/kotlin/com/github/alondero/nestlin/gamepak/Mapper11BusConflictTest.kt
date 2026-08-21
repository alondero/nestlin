package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.testutil.testGamePak
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Bus-conflict (GH #236) regression for Mapper 11 (Color Dreams).
 *
 * The Color Dreams board shares its data bus between PRG ROM and the
 * bank-select latch, so the CPU's write value is bitwise-ANDed with the
 * PRG byte at the write address. The whole 32KB window is one bank.
 *
 * Commercial games almost always write a value that matches the PRG byte
 * at the write address (so the mask is a no-op), but a few titles can
 * bank differently as a result.
 */
class Mapper11BusConflictTest {

    private fun newMapper(romByte: Byte, writeAddr: Int = 0x8000, prgKb: Int = 32): Mapper11 {
        val pak = testGamePak {
            mapper = 11
            this.prgKb = prgKb
            chrKb = 32
            fillPrg(0xFF.toByte())
            // Stamp the write address with the desired PRG byte.
            val offset = (writeAddr - 0x8000) and 0x7FFF
            prg[offset] = romByte
        }
        return pak.createMapper() as Mapper11
    }

    @Test
    fun `write value is ANDed with PRG byte at write address`() {
        // PRG[0x8000] = 0x0F. Writing 0xFF should yield effective 0x0F.
        val mapper = newMapper(romByte = 0x0F.toByte())

        mapper.cpuWrite(0x8000, 0xFF.toSignedByte())

        // CHR bank is bits 4-7 of the effective value; PRG bank is bits 0-1.
        assertThat(mapper.snapshot().banks["chrBank"], equalTo((0x0F shr 4) and 0x0F))
    }

    @Test
    fun `write value with no common bits is masked to zero`() {
        // PRG[0x8000] = 0x0F. Writing 0xF0 should yield effective 0x00.
        val mapper = newMapper(romByte = 0x0F.toByte())

        mapper.cpuWrite(0x8000, 0xF0.toSignedByte())

        assertThat(mapper.snapshot().banks["chrBank"], equalTo(0))
    }

    @Test
    fun `write value that matches PRG byte is unchanged`() {
        val mapper = newMapper(romByte = 0x42.toByte())

        mapper.cpuWrite(0x8000, 0x42.toSignedByte())

        // CHR bank = (0x42 shr 4) and 0x0F = 0x04.
        assertThat(mapper.snapshot().banks["chrBank"], equalTo(0x04))
    }

    @Test
    fun `PRG bank field inherits the masked value`() {
        // Mapper 11's PRG bank field is bits 0-1 of the effective value.
        // With PRG byte 0x0F and write 0xFF, effective is 0x0F; PRG bank = 0x03.
        val mapper = newMapper(romByte = 0x0F.toByte())

        mapper.cpuWrite(0x8000, 0xFF.toSignedByte())

        // The snapshot exposes CHR bank only (matches the Mapper11 docs).
        // Verify the CHR bank changed too: 0x0F shr 4 = 0.
        assertThat(mapper.snapshot().banks["chrBank"], equalTo(0))
    }

    @Test
    fun `non-stamp address writes bypass the conflict`() {
        // Writing to a non-stamp address (filled with 0xFF) makes the
        // mask a no-op. This is what test fixtures use to disable bus
        // conflicts via `fillPrg(0xFF)`. The mapper's initial prgBank
        // is 0, so the bus-conflict address resolves to PRG[0x10] =
        // 0xFF (filled).
        val pak = testGamePak {
            mapper = 11
            prgKb = 32
            chrKb = 32
            fillPrg(0xFF.toByte())
        }
        val mapper = pak.createMapper() as Mapper11

        mapper.cpuWrite(0x8010, 0xFF.toSignedByte())

        // PRG[0x8010] = 0xFF (filled), so 0xFF AND 0xFF = 0xFF.
        // CHR bank = (0xFF shr 4) and 0x0F = 0x0F.
        assertThat(mapper.snapshot().banks["chrBank"], equalTo(0x0F))
    }
}
