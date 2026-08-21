package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.testutil.testGamePak
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

class Mapper2Test {

    /**
     * `fillPrg(0xFF)` neutralises the bus-conflict AND mask (GH #236): any
     * write value passes through unchanged. The stamp at `$8000` (the bank
     * whose index is `2` after `stampPrgBanks(windowKb = 16)`) would
     * otherwise mask off unrelated bits and break the bank-selection
     * assertions below. The dedicated `Mapper2BusConflictTest` exercises
     * the mask itself.
     */
    private fun newMapper(prgKb: Int): Mapper2 {
        val gamePak = testGamePak {
            mapper = 2
            this.prgKb = prgKb
            chrKb = 0
            fillPrg(0xFF.toByte())
            stampPrgBanks(windowKb = 16)
        }
        return gamePak.createMapper() as Mapper2
    }

    @Test
    fun `UOROM can select all 16 PRG banks`() {
        val mapper = newMapper(prgKb = 256)

        for (bank in 0..15) {
            // Write at 0x8010 (offset 0x10 within the bank) so the
            // bus-conflict AND mask (GH #236) reads from 0xFF-filled
            // PRG (a no-op) instead of hitting the bank stamp at 0x8000.
            mapper.cpuWrite(0x8010, bank.toSignedByte())
            assertThat("bank $bank", mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(bank))
        }
    }

    @Test
    fun `C000 window remains fixed to the last PRG bank`() {
        val mapper = newMapper(prgKb = 256)

        mapper.cpuWrite(0x8010, 8.toSignedByte())

        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(8))
        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(15))
    }

    @Test
    fun `oversized bank number wraps modulo PRG bank count`() {
        val mapper = newMapper(prgKb = 64)

        mapper.cpuWrite(0x8010, 5.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(1))

        mapper.cpuWrite(0xFFFF, 0xFF.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
    }
}
