package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.testutil.testGamePak
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Bus-conflict (GH #236) regression for Mapper 66 (GxROM).
 *
 * The GxROM board shares its data bus between PRG ROM and the bank-select
 * latch, so the CPU's write value is bitwise-ANDed with the PRG byte at
 * the write address. The whole 32KB window is one bank.
 *
 * Commercial games almost always write a value that matches the PRG byte
 * at the write address (so the mask is a no-op), but a few titles can
 * bank differently as a result.
 */
class Mapper66BusConflictTest {

    private fun newMapper(romByte: Byte, writeAddr: Int = 0x8000, prgKb: Int = 64): Mapper66 {
        val pak = testGamePak {
            mapper = 66
            this.prgKb = prgKb
            chrKb = 32
            fillPrg(0xFF.toByte())
            // Mapper66's initial prgBank is 0, so the bus-conflict
            // address resolves to PRG[writeAddr - 0x8000]. Stamp that
            // byte with the desired mask.
            val offset = (writeAddr - 0x8000) and 0x7FFF
            prg[offset] = romByte
        }
        return pak.createMapper() as Mapper66
    }

    @Test
    fun `write value is ANDed with PRG byte at write address`() {
        // PRG[0x8000] = 0x0F. Writing 0xFF should yield effective 0x0F.
        val mapper = newMapper(romByte = 0x0F.toByte())

        mapper.cpuWrite(0x8000, 0xFF.toSignedByte())

        // PRG bank is bits 4-5 of the effective value; CHR bank is bits 0-1.
        // 0x0F shr 4 = 0; PRG bank = 0.
        assertThat(mapper.snapshot().banks["prg"], equalTo(0))
    }

    @Test
    fun `write value with no common bits is masked to zero`() {
        // PRG[0x8000] = 0x0F. Writing 0xF0 should yield effective 0x00.
        val mapper = newMapper(romByte = 0x0F.toByte())

        mapper.cpuWrite(0x8000, 0xF0.toSignedByte())

        assertThat(mapper.snapshot().banks["prg"], equalTo(0))
        assertThat(mapper.snapshot().banks["chr"], equalTo(0))
    }

    @Test
    fun `write value that matches PRG byte is unchanged`() {
        val mapper = newMapper(romByte = 0x42.toByte())

        mapper.cpuWrite(0x8000, 0x42.toSignedByte())

        // 0x42 shr 4 = 0x04; PRG bank = 0x04 and 0x03 = 0.
        // CHR bank = 0x42 and 0x03 = 0x02.
        assertThat(mapper.snapshot().banks["prg"], equalTo(0))
        assertThat(mapper.snapshot().banks["chr"], equalTo(0x02))
    }

    @Test
    fun `PRG bank field survives when PRG byte has bit 4 set`() {
        // With PRG byte 0xF0 and write 0x10, the effective value is
        // 0x10 — bit 4 set, so PRG bank = 1.
        val mapper = newMapper(romByte = 0xF0.toByte())

        mapper.cpuWrite(0x8000, 0x10.toSignedByte())

        assertThat(mapper.snapshot().banks["prg"], equalTo(1))
    }

    @Test
    fun `non-stamp address writes bypass the conflict`() {
        // Writing to a non-stamp address (filled with 0xFF) makes the
        // mask a no-op. This is what test fixtures use to disable bus
        // conflicts via `fillPrg(0xFF)`. The mapper's initial prgBank
        // is the last 32KB bank (1 for 64KB PRG), so the bus-conflict
        // address resolves to PRG[0x8010] = 0xFF (filled).
        val pak = testGamePak {
            mapper = 66
            prgKb = 64
            chrKb = 32
            fillPrg(0xFF.toByte())
        }
        val mapper = pak.createMapper() as Mapper66

        mapper.cpuWrite(0x8010, 0xFF.toSignedByte())

        // PRG[0x8010] = 0xFF (filled), so 0xFF AND 0xFF = 0xFF.
        // PRG bank = (0xFF shr 4) and 0x03 = 3.
        // CHR bank = 0xFF and 0x03 = 3.
        assertThat(mapper.snapshot().banks["prg"], equalTo(3))
        assertThat(mapper.snapshot().banks["chr"], equalTo(3))
    }
}
