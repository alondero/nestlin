package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.testutil.testGamePak
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Bus-conflict (GH #236) regression for Mapper 7 (AxROM).
 *
 * The discrete-logic AxROM board shares its data bus between PRG ROM and
 * the 74HC161 bank-select latch, so a CPU write to the bank register is
 * bitwise-ANDed with whatever PRG byte is currently at the write address.
 * The whole 32KB window is one bank, so the bank offset is fixed for the
 * duration of the write.
 *
 * AMROM/AOROM are the boards with conflicts; ANROM uses a 74HC02 to disable
 * PRG ROM during writes (no mask). We apply the mask uniformly because the
 * iNES header doesn't distinguish the variants.
 */
class Mapper7BusConflictTest {

    private fun newMapper(romByte: Byte, writeAddr: Int = 0x8000, prgKb: Int = 256): Mapper7 {
        val pak = testGamePak {
            mapper = 7
            this.prgKb = prgKb
            chrKb = 0
            fillPrg(0xFF.toByte())
            // Stamp the write address with the desired PRG byte.
            val offset = (writeAddr - 0x8000) and 0x7FFF
            prg[offset] = romByte
        }
        return pak.createMapper() as Mapper7
    }

    @Test
    fun `write value is ANDed with PRG byte at write address`() {
        // PRG[0x8000] = 0x0F. Writing 0xFF should yield effective 0x0F.
        val mapper = newMapper(romByte = 0x0F.toByte())

        mapper.cpuWrite(0x8000, 0xFF.toSignedByte())

        // PRG bank is bits 0-2 of the effective value.
        assertThat(mapper.snapshot().banks["prgBank"], equalTo(0x0F and 0x07))
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
        val mapper = newMapper(romByte = 0x42.toByte())

        mapper.cpuWrite(0x8000, 0x42.toSignedByte())

        // 0x42 AND 0x07 = 0x02.
        assertThat(mapper.snapshot().banks["prgBank"], equalTo(0x02))
    }

    @Test
    fun `mirroring bit is masked by the PRG byte`() {
        // Mapper 7's mirroring comes from bit 4 of the effective value.
        // With PRG byte 0x0F and write 0x10, the effective value is 0x00
        // which has bit 4 clear — so mirroring should be lower, not upper.
        val mapper = newMapper(romByte = 0x0F.toByte())

        mapper.cpuWrite(0x8000, 0x10.toSignedByte())

        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
    }

    @Test
    fun `mirroring bit survives when PRG byte has bit 4 set`() {
        // With PRG byte 0x1F and write 0x10, the effective value is 0x10
        // which has bit 4 set — mirroring should be upper.
        val mapper = newMapper(romByte = 0x1F.toByte())

        mapper.cpuWrite(0x8000, 0x10.toSignedByte())

        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
    }

    @Test
    fun `non-stamp address writes bypass the conflict`() {
        // Writing to a non-stamp address (filled with 0xFF) makes the
        // mask a no-op. This is what test fixtures use to disable bus
        // conflicts via `fillPrg(0xFF)`. The mapper's initial prgBank
        // is 0, so the bus-conflict address resolves to PRG[0x10] =
        // 0xFF (filled).
        val pak = testGamePak {
            mapper = 7
            prgKb = 256
            chrKb = 0
            fillPrg(0xFF.toByte())
        }
        val mapper = pak.createMapper() as Mapper7

        mapper.cpuWrite(0x8010, 0xFF.toSignedByte())

        // PRG[0x8010] = 0xFF (filled), so 0xFF AND 0xFF = 0xFF.
        // PRG bank = 0xFF and 0x07 = 7.
        assertThat(mapper.snapshot().banks["prgBank"], equalTo(7))
    }
}
