package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.testutil.testGamePak
import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Mapper 7 (AxROM). Control byte layout `xxxM xPPP`:
 *   - bits 0-2 (`PPP`) select the 32KB PRG bank
 *   - bit 4 (`M`) selects the single-screen nametable (0 = lower, 1 = upper)
 *
 * The mirroring select is **bit 4 (0x10)**, not bit 3 — matching Mesen
 * (`value & 0x10`) and FCEUX. Reading it from bit 3 flips the nametable on the
 * wrong write and corrupts scrolling in Battletoads, Cobra Triangle, etc.
 */
class Mapper7Test {

    /**
     * `fillPrg(0xFF)` neutralises the bus-conflict AND mask (GH #236): a
     * PRG byte of `0xFF` makes the mask a no-op (`value AND 0xFF == value`).
     * Without it, the stamp at `$8000` (bank 1's index in the 32KB window)
     * would mask off unrelated bits and break the bank / mirroring
     * assertions below. The dedicated `Mapper7BusConflictTest` exercises
     * the mask itself.
     */
    private fun newMapper7(prgKb: Int = 256): Mapper7 {
        val pak = testGamePak {
            mapper = 7
            this.prgKb = prgKb
            chrKb = 0            // AxROM is CHR-RAM
            fillPrg(0xFF.toByte())
            stampPrgBanks(windowKb = 32)
        }
        return pak.createMapper() as Mapper7
    }

    @Test
    fun `mapper 7 is selected for header mapper 7`() {
        assertThat(newMapper7() is Mapper7, equalTo(true))
    }

    @Test
    fun `PRG bank select uses bits 0-2`() {
        val m = newMapper7()
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        // Write at 0x8010 (offset 0x10 within the bank) so the bus-
        // conflict AND mask (GH #236) reads from 0xFF-filled PRG
        // (a no-op) instead of the bank-0 stamp at 0x8000.
        m.cpuWrite(0x8010, 0x03.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        m.cpuWrite(0x8010, 0x07.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `default mirroring is single-screen lower`() {
        assertThat(newMapper7().currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
    }

    @Test
    fun `bit 4 set selects single-screen upper`() {
        val m = newMapper7()
        m.cpuWrite(0x8010, 0x10.toSignedByte())
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
    }

    @Test
    fun `bit 4 clear selects single-screen lower`() {
        val m = newMapper7()
        m.cpuWrite(0x8010, 0x10.toSignedByte())   // -> upper
        m.cpuWrite(0x8010, 0x00.toSignedByte())   // -> lower
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
    }

    /** The key regression: bit 3 must NOT influence mirroring (only bit 4 does). */
    @Test
    fun `bit 3 set with bit 4 clear stays single-screen lower`() {
        val m = newMapper7()
        m.cpuWrite(0x8010, 0x08.toSignedByte())   // bit 3 set, bit 4 clear
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
    }

    @Test
    fun `bank and mirroring decode from independent bit fields`() {
        val m = newMapper7()
        // 0x1F = bank 7 (bits 0-2) + mirroring upper (bit 4); bit 3 is a don't-care.
        m.cpuWrite(0x8010, 0x1F.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(7))
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
    }
}
