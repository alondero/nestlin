package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.testutil.testGamePak
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Bus-conflict (GH #236) regression for Mapper 2 (UNROM/UOROM).
 *
 * The discrete-logic UxROM board shares its data bus between the PRG ROM
 * and the 74HC161 bank-select latch, so a CPU write to the bank register
 * is bitwise-ANDed with whatever PRG byte is currently at the write
 * address. The window split matters: writes to `$8000-$BFFF` see the
 * switchable bank; writes to `$C000-$FFFF` see the fixed-last bank.
 *
 * These tests use a 16KB PRG image so the bank-th offset modulo-wraps
 * back to byte 0 of the same PRG array, which keeps the test focus on
 * the AND mask rather than the per-bank address arithmetic.
 */
class Mapper2BusConflictTest {

    /**
     * Build a 32KB (two-bank) PRG. The initial prgBank is the last bank
     * (per Mapper2's `(prgBankCount - 1).coerceAtLeast(1)` initialiser),
     * so all bus-conflict reads happen at bank 1's offset within PRG.
     * The PRG byte at the write address is the AND-mask neighbour.
     */
    private fun newMapper(romByte: Byte, writeAddr: Int = 0x8000): Mapper2 {
        val pak = testGamePak {
            mapper = 2
            prgKb = 32
            chrKb = 0
            fillPrg(0xFF.toByte())
            // For 32KB PRG (2 banks), the bus-conflict address resolves
            // to PRG[0x4000 + (writeAddr - 0x8000) and 0x3FFF]. Stamp
            // that byte with the desired mask.
            val bankOffset = 0x4000 + ((writeAddr - 0x8000) and 0x3FFF)
            prg[bankOffset] = romByte
        }
        return pak.createMapper() as Mapper2
    }

    @Test
    fun `write value is ANDed with PRG byte at write address`() {
        // PRG[0x8000] = 0x0F. Writing 0xFF should yield effective 0x0F.
        val mapper = newMapper(romByte = 0x0F.toByte())

        mapper.cpuWrite(0x8000, 0xFF.toSignedByte())

        assertThat(mapper.snapshot().banks["prgBank"], equalTo(0x0F))
    }

    @Test
    fun `write value with no common bits is masked to zero`() {
        // PRG[0x8000] = 0x0F. Writing 0xF0 should yield effective 0x00.
        val mapper = newMapper(romByte = 0x0F.toByte())

        mapper.cpuWrite(0x8000, 0xF0.toSignedByte())

        assertThat(mapper.snapshot().banks["prgBank"], equalTo(0))
    }

    @Test
    fun `write value that matches PRG byte is unchanged`() {
        // When the write value equals the PRG byte, the mask is a no-op.
        val mapper = newMapper(romByte = 0x42.toByte())

        mapper.cpuWrite(0x8000, 0x42.toSignedByte())

        assertThat(mapper.snapshot().banks["prgBank"], equalTo(0x42))
    }

    @Test
    fun `CHR bank field inherits the masked value`() {
        // Mapper 2's CHR bank field is bits 3-4 of the effective value.
        // With PRG byte 0x0F and write 0xFF, the effective value is 0x0F
        // which has bits 0-3 set; the CHR bank is (0x0F shr 3) and 0x03 = 1.
        val mapper = newMapper(romByte = 0x0F.toByte())

        mapper.cpuWrite(0x8000, 0xFF.toSignedByte())

        assertThat(mapper.snapshot().banks["chrBank"], equalTo(1))
    }

    @Test
    fun `C000 window reads at fixed bank for bus-conflict calculation`() {
        // Writes to $C000-$FFFF see the fixed-last bank, not the
        // switchable one. With 32KB PRG (2 banks), the last bank is
        // bank 1; stamp bank 1's $C000 byte (= PRG[0x4000]) with a
        // value that decodes the upper nibble of the write.
        val pak = testGamePak {
            mapper = 2
            prgKb = 32
            chrKb = 0
            fillPrg(0xFF.toByte())
            // PRG[0x4000] = 0xF0; the write to 0xC000 sees this byte.
            prg[0x4000] = 0xF0.toByte()
        }
        val mapper = pak.createMapper() as Mapper2

        mapper.cpuWrite(0xC000, 0x33.toSignedByte())

        // 0x33 AND 0xF0 = 0x30, so prgBank = 0x30.
        assertThat(mapper.snapshot().banks["prgBank"], equalTo(0x30))
    }

    @Test
    fun `non-stamp address writes bypass the conflict`() {
        // Writing to a non-stamp address (filled with 0xFF) makes the
        // mask a no-op. This is what test fixtures use to disable bus
        // conflicts via `fillPrg(0xFF)`. The mapper's initial prgBank
        // is the last 32KB bank (1 for 32KB PRG, 2 banks), so the
        // bus-conflict address resolves to PRG[0x4010] = 0xFF (filled).
        val pak = testGamePak {
            mapper = 2
            prgKb = 32
            chrKb = 0
            fillPrg(0xFF.toByte())
        }
        val mapper = pak.createMapper() as Mapper2

        mapper.cpuWrite(0x8010, 0xFF.toSignedByte())

        // PRG[0x4010] = 0xFF (filled), so 0xFF AND 0xFF = 0xFF.
        assertThat(mapper.snapshot().banks["prgBank"], equalTo(0xFF))
    }
}
