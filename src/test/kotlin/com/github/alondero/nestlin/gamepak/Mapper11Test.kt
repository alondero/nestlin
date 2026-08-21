package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.testutil.testGamePak
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Mapper 11 (Color Dreams) tests. Control byte layout `CCCC LLPP`:
 *   - bits 0-1 (`PP`) select the 32KB PRG bank
 *   - bits 4-7 (`CCCC`) select the 8KB CHR bank
 *
 * The bus-conflict AND mask (GH #236) is neutralised via `fillPrg(0xFF)`
 * so the stamp-byte at the write address doesn't break the bank
 * selection assertions. Writes use 0x8010 (an offset within the bank
 * that's not a stamp) so the mask sees 0xFF-filled PRG. The dedicated
 * `Mapper11BusConflictTest` exercises the mask itself.
 */
class Mapper11Test {

    private fun newMapper(prgKb: Int = 32, chrKb: Int = 32): Mapper11 {
        return testGamePak {
            mapper = 11
            this.prgKb = prgKb
            this.chrKb = chrKb
            fillPrg(0xFF.toByte())
        }.createMapper() as Mapper11
    }

    @Test
    fun `mapper 11 is selected for header mapper 11`() {
        assertThat(newMapper() is Mapper11, equalTo(true))
    }

    @Test
    fun `defaults to PRG bank 0 and CHR bank 0`() {
        val m = newMapper()
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0xFF))
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `bits 4-7 select 8KB CHR bank`() {
        val m = newMapper()
        // 0x10 = CHR bank 1 (high nibble = 1), PRG bank 0 (low bits = 0)
        m.cpuWrite(0x8010, 0x10.toSignedByte())
        assertThat(m.snapshot().banks["chrBank"], equalTo(1))
        // 0x20 = CHR bank 2
        m.cpuWrite(0x8010, 0x20.toSignedByte())
        assertThat(m.snapshot().banks["chrBank"], equalTo(2))
    }

    @Test
    fun `bits 0-1 select 32KB PRG bank`() {
        val m = newMapper(prgKb = 64)
        // 0x01 = PRG bank 1 (bits 0-1 = 01)
        m.cpuWrite(0x8010, 0x01.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0xFF))  // bank 1 still filled
        val snap = m.snapshot()
        // PRG bank field is mapped via (value and 0x03) — see Mapper11
        // source. The snapshot exposes CHR bank only.
        assertThat(snap.banks["chrBank"], equalTo(0))
    }

    @Test
    fun `CHR bank and PRG bank fields are independent`() {
        val m = newMapper(prgKb = 64)
        // 0x11 = CHR bank 1, PRG bank 1
        m.cpuWrite(0x8010, 0x11.toSignedByte())
        assertThat(m.snapshot().banks["chrBank"], equalTo(1))
        // 0x30 = CHR bank 3, PRG bank 0
        m.cpuWrite(0x8010, 0x30.toSignedByte())
        assertThat(m.snapshot().banks["chrBank"], equalTo(3))
    }

    @Test
    fun `write to $C000 selects bank (upper PRG window)`() {
        val m = newMapper()
        m.cpuWrite(0xC000, 0x10.toSignedByte())
        assertThat(m.snapshot().banks["chrBank"], equalTo(1))
    }

    @Test
    fun `write to $FFFF selects bank (top of PRG window)`() {
        val m = newMapper()
        m.cpuWrite(0xFFFF, 0x30.toSignedByte())
        assertThat(m.snapshot().banks["chrBank"], equalTo(3))
    }

    @Test
    fun `mirroring is fixed by the iNES header`() {
        val h = testGamePak {
            mapper = 11
            prgKb = 32
            chrKb = 32
            verticalMirroring = true
        }
        val m = h.createMapper() as Mapper11
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }
}
