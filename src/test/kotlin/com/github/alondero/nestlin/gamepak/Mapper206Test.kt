package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Unit tests for Mapper 206 (DxROM / Namcot 108 / Namcot 109 / Namcot 118).
 *
 * The Namco 108 family is a simplified MMC3:
 *   - PRG mode 0 is hardwired (R6 at $8000, R7 at $A000, last two banks fixed at $C000/$E000).
 *   - CHR mode 0 is hardwired (R0/R1 = 2 KB at $0000-$0FFF, R2-R5 = 1 KB at $1000-$1FFF).
 *   - No scanline IRQ.
 *   - Mirroring is hardwired from the iNES header.
 *
 * Test ROMs are filled so the contents identify the bank: every PRG byte equals its
 * 8 KB bank index, and every CHR byte equals its 1 KB bank index. That lets a read
 * assert exactly which bank is currently mapped into a window.
 */
class Mapper206Test {

    // prg8k = 8KB units, chr1k = 1KB units. Defaults give 8 PRG banks and 32 CHR banks
    // — large enough that no test bank overlaps with the fixed second-to-last / last bank.
    private fun createTestGamePak(
        prg8k: Int = 8,
        chr1k: Int = 32,
        mirroring: Header.Mirroring = Header.Mirroring.VERTICAL
    ): GamePak {
        val header = ByteArray(16)
        header[0] = 'N'.code.toByte(); header[1] = 'E'.code.toByte()
        header[2] = 'S'.code.toByte(); header[3] = 0x1A.toByte()
        header[4] = (prg8k / 2).toByte()           // PRG ROM size in 16KB units
        header[5] = (chr1k / 8).toByte()           // CHR ROM size in 8KB units
        // mapper 206 = 0xCE. flags6 = mapper-low nibble in upper 4 bits, plus bit 0 = mirror.
        val mirrorBit = if (mirroring == Header.Mirroring.VERTICAL) 0x01 else 0x00
        header[6] = (((206 and 0x0F) shl 4) or mirrorBit).toByte()
        header[7] = (206 and 0xF0).toByte()
        val prg = ByteArray(prg8k * 0x2000) { ((it / 0x2000) and 0xFF).toByte() }
        val chr = ByteArray(chr1k * 0x400) { ((it / 0x400) and 0xFF).toByte() }
        return GamePak(header + prg + chr)
    }

    /** Stamp distinct bytes into every 1 KB CHR bank so we can detect mis-banking. */
    private fun makeStampedChr(banks1k: Int): ByteArray {
        val chr = ByteArray(banks1k * 0x400)
        for (bank in 0 until banks1k) {
            chr[bank * 0x400] = (bank and 0xFF).toByte()
            chr[bank * 0x400 + 0x3FF] = (bank xor 0xFF).toByte()
        }
        return chr
    }

    /** Build a GamePak with stamped CHR for unambiguous read-after-bank-write tests. */
    private fun createStampedGamePak(chr1kBanks: Int, prg8kBanks: Int = 4): GamePak {
        val header = ByteArray(16)
        header[0] = 'N'.code.toByte(); header[1] = 'E'.code.toByte()
        header[2] = 'S'.code.toByte(); header[3] = 0x1A.toByte()
        header[4] = (prg8kBanks / 2).toByte()       // 16KB units
        header[5] = (chr1kBanks / 8).toByte()       // 8KB units
        // Mapper 206 = 0xCE: low nibble 0xE in byte 6 bits 4-7, high nibble 0xC in byte 7 bits 4-7.
        // bit 0 of byte 6 = 0 → horizontal mirroring (matches createTestGamePak default).
        header[6] = (((206 and 0x0F) shl 4) or 0x00).toByte()
        header[7] = (206 and 0xF0).toByte()
        val prg = ByteArray(prg8kBanks * 0x2000) { ((it / 0x2000) and 0xFF).toByte() }
        val chr = makeStampedChr(chr1kBanks)
        return GamePak(header + prg + chr)
    }

    /** Send a (register, value) Namco 108 write pair: $8000 select then $8001 data. */
    private fun Mapper206.bankWrite(bankSelect: Int, value: Int) {
        cpuWrite(0x8000, bankSelect.toSignedByte())
        cpuWrite(0x8001, value.toSignedByte())
    }

    // ---- Factory wiring ----

    @Test
    fun `mapper 206 is selected for header mapper 206`() {
        assertThat(createTestGamePak().createMapper() is Mapper206, equalTo(true))
    }

    // ---- PRG banking (mode 0 is hardwired) ----

    @Test
    fun `default banks are R6=0 at 8000 and R7=1 at A000`() {
        val m = Mapper206(createTestGamePak())
        // 0x8000 -> bank 0, 0xA000 -> bank 1
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `C000 is fixed to the second-to-last PRG bank`() {
        val m = Mapper206(createTestGamePak(prg8k = 8))   // last=7, second-to-last=6
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(6))
    }

    @Test
    fun `E000 is fixed to the last PRG bank`() {
        val m = Mapper206(createTestGamePak(prg8k = 8))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `R6 at 8000 and R7 at A000 switch 8KB PRG banks via 8000-8001 writes`() {
        val m = Mapper206(createTestGamePak(prg8k = 8))
        m.bankWrite(6, 3)   // $8000-$9FFF -> bank 3
        m.bankWrite(7, 5)   // $A000-$BFFF -> bank 5
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(m.cpuRead(0x9FFF).toUnsignedInt(), equalTo(3))
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(5))
        assertThat(m.cpuRead(0xBFFF).toUnsignedInt(), equalTo(5))
        // Fixed banks unaffected.
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(6))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `bank data low 6 bits select PRG bank - bits 6 and 7 are ignored for PRG`() {
        val m = Mapper206(createTestGamePak(prg8k = 8))
        // Bank value 0xFF with select=R6: stored prgBank6 should be `(0xFF and 0x7F) and 0x3F`
        // = 0x3F = 63. The snapshot is the cleanest way to assert the masked value.
        m.bankWrite(6, 0xFF)
        assertThat(m.snapshot().banks["prgBank6"], equalTo(0x3F))
    }

    // ---- PRG-RAM ($6000-$7FFF) ----

    @Test
    fun `6000-7FFF reads and writes PRG-RAM`() {
        val m = Mapper206(createTestGamePak())
        m.cpuWrite(0x6000, 0x42.toSignedByte())
        m.cpuWrite(0x67FF, 0x99.toSignedByte())
        assertThat(m.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
        assertThat(m.cpuRead(0x67FF).toUnsignedInt(), equalTo(0x99))
    }

    @Test
    fun `writes to PRG-RAM set batteryDirty`() {
        val m = Mapper206(createTestGamePak())
        // Battery-backed default off until written.
        // (Header.hasBattery is false in our test, but the dirty flag still flips.)
        m.cpuWrite(0x6000, 0x42.toSignedByte())
        assertThat(m.batteryDirty, equalTo(true))
        assertThat(m.batteryBackedRam()!![0].toUnsignedInt(), equalTo(0x42))
    }

    // ---- CHR banking (mode 0 is hardwired) ----

    @Test
    fun `CHR reads from bank 0 by default`() {
        val m = Mapper206(createStampedGamePak(chr1kBanks = 16))
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x17FF).toUnsignedInt(), equalTo(0 xor 0xFF))
    }

    @Test
    fun `R0 selects a 2KB CHR bank at 0000-07FF - low bit ignored`() {
        val m = Mapper206(createStampedGamePak(chr1kBanks = 16))
        // Bank 3 (low bit ignored) -> page 2 in 1KB units. Bytes 2 and 3 are paired.
        m.bankWrite(0, 3)
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(2))  // page 2
        assertThat(m.ppuRead(0x03FF).toUnsignedInt(), equalTo(2 xor 0xFF))
        assertThat(m.ppuRead(0x0400).toUnsignedInt(), equalTo(3))  // page 3 (paired)
        assertThat(m.ppuRead(0x07FF).toUnsignedInt(), equalTo(3 xor 0xFF))
    }

    @Test
    fun `R1 selects a 2KB CHR bank at 0800-0FFF - low bit ignored`() {
        val m = Mapper206(createStampedGamePak(chr1kBanks = 16))
        // Bank 4 -> pages 4,5
        m.bankWrite(1, 4)
        assertThat(m.ppuRead(0x0800).toUnsignedInt(), equalTo(4))
        assertThat(m.ppuRead(0x0BFF).toUnsignedInt(), equalTo(4 xor 0xFF))
        assertThat(m.ppuRead(0x0C00).toUnsignedInt(), equalTo(5))
        assertThat(m.ppuRead(0x0FFF).toUnsignedInt(), equalTo(5 xor 0xFF))
    }

    @Test
    fun `R2-R5 each select a 1KB CHR bank at 1000-1FFF`() {
        val m = Mapper206(createStampedGamePak(chr1kBanks = 16))
        m.bankWrite(2, 10)   // $1000-$13FF
        m.bankWrite(3, 11)   // $1400-$17FF
        m.bankWrite(4, 12)   // $1800-$1BFF
        m.bankWrite(5, 13)   // $1C00-$1FFF
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(10))
        assertThat(m.ppuRead(0x13FF).toUnsignedInt(), equalTo(10 xor 0xFF))
        assertThat(m.ppuRead(0x1400).toUnsignedInt(), equalTo(11))
        assertThat(m.ppuRead(0x17FF).toUnsignedInt(), equalTo(11 xor 0xFF))
        assertThat(m.ppuRead(0x1800).toUnsignedInt(), equalTo(12))
        assertThat(m.ppuRead(0x1BFF).toUnsignedInt(), equalTo(12 xor 0xFF))
        assertThat(m.ppuRead(0x1C00).toUnsignedInt(), equalTo(13))
        assertThat(m.ppuRead(0x1FFF).toUnsignedInt(), equalTo(13 xor 0xFF))
    }

    @Test
    fun `R6-R7 do not affect CHR reads (they are PRG banks)`() {
        val m = Mapper206(createStampedGamePak(chr1kBanks = 16))
        m.bankWrite(6, 7)   // PRG bank — should NOT change CHR
        m.bankWrite(7, 7)
        // CHR still from R0..R5 (all zero at this point).
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(0))
    }

    // ---- Bank-select register / $8000 protocol ----

    @Test
    fun `bank-select latches the next data write`() {
        // Use a stamped CHR ROM so the byte we read at $1000 directly identifies the bank.
        val m = Mapper206(createStampedGamePak(chr1kBanks = 16))
        m.cpuWrite(0x8000, 2.toSignedByte())   // select R2
        m.cpuWrite(0x9FFF, 11.toSignedByte())  // odd address != $8001 — still data
        // Writes to $9FFF are mapped to 0x8001 by the addr &= 0x8001 mask, so the
        // data byte for R2 should be 11. The PPU window $1000-$13FF must reflect it.
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(11))
    }

    @Test
    fun `bank-select register is cleared to 0 on power-on`() {
        val m = Mapper206(createTestGamePak())
        // Default: PPU $1000 reads from R2 = 0.
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(0))
    }

    // ---- Hardwired banking modes ----

    @Test
    fun `bit 6 of 8000 (PRG mode) is ignored - bank order stays 6 at 8000`() {
        // Without the mask, a 1 in bit 6 would switch to MMC3 PRG mode 1, which
        // would change the $8000 window from R6 to "second-to-last". We want
        // R6 to stay mapped at $8000.
        val m = Mapper206(createTestGamePak(prg8k = 8))
        m.cpuWrite(0x8000, 0x47.toSignedByte())   // select R7, PRG mode bit set
        m.cpuWrite(0x8001, 0x05.toSignedByte())   // R7 = bank 5
        m.cpuWrite(0x8000, 0x46.toSignedByte())   // select R6, PRG mode bit set
        m.cpuWrite(0x8001, 0x03.toSignedByte())   // R6 = bank 3
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(3))   // R6 wins, mode 0 holds
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(5))   // R7
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(6))   // fixed second-to-last
    }

    @Test
    fun `bit 7 of 8000 (CHR invert) is ignored - R2-R5 stay at 1000-1FFF`() {
        // With CHR invert ignored, setting bit 7 must NOT move R2-R5 to $0000.
        val m = Mapper206(createStampedGamePak(chr1kBanks = 16))
        m.cpuWrite(0x8000, 0x82.toSignedByte())   // select R2, CHR invert bit set
        m.cpuWrite(0x8001, 0x09.toSignedByte())   // R2 = bank 9
        // Window $1000-$13FF should still see R2 (= bank 9).
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(9))
        // Window $0000-$07FF should still be served by R0 (default 0).
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
    }

    // ---- Mirroring is hardwired from the header ----

    @Test
    fun `currentMirroring returns header mirroring and is not affected by 8001 writes`() {
        val v = Mapper206(createTestGamePak(mirroring = Header.Mirroring.VERTICAL))
        assertThat(v.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        // Writes to $A000 (mirroring register on MMC3) and $A001 are no-ops.
        v.cpuWrite(0xA000, 0x01.toSignedByte())
        v.cpuWrite(0xA001, 0x80.toSignedByte())
        assertThat(v.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))

        val h = Mapper206(createTestGamePak(mirroring = Header.Mirroring.HORIZONTAL))
        assertThat(h.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    // ---- No scanline IRQ ----

    @Test
    fun `no scanline IRQ is ever pending`() {
        val m = Mapper206(createTestGamePak())
        // Even after writing IRQ-latch / IRQ-reload / IRQ-enable registers
        // (which Namco 108 accepts but does nothing with), no IRQ fires.
        m.cpuWrite(0xC000, 0x05.toSignedByte())   // IRQ latch
        m.cpuWrite(0xC001, 0x00.toSignedByte())   // IRQ reload
        m.cpuWrite(0xE001, 0x00.toSignedByte())   // IRQ enable
        assertThat(m.isIrqPending(), equalTo(false))
        m.acknowledgeIrq()                         // no-op
        assertThat(m.isIrqPending(), equalTo(false))
    }

    // ---- CHR RAM fallback ----

    @Test
    fun `CHR RAM is writable when chrRom is empty`() {
        val header = ByteArray(16)
        header[0] = 'N'.code.toByte(); header[1] = 'E'.code.toByte()
        header[2] = 'S'.code.toByte(); header[3] = 0x1A.toByte()
        header[4] = 0x01.toByte()                          // 16KB PRG (1 × 16KB)
        header[5] = 0x00.toByte()                          // 0 CHR ROM
        header[6] = (((206 and 0x0F) shl 4) or 0x00).toByte()   // mapper 206, horizontal mirror
        header[7] = (206 and 0xF0).toByte()
        val prg = ByteArray(0x4000) { ((it / 0x2000) and 0xFF).toByte() }   // 2 × 8KB
        val m = Mapper206(GamePak(header + prg))
        m.ppuWrite(0x0000, 0x42.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0x42))
    }

    // ---- Save / load round-trip ----

    @Test
    fun `save and load round-trips banking and PRG-RAM`() {
        val original = Mapper206(createTestGamePak())
        original.bankWrite(0, 5)   // R0 = 5
        original.bankWrite(2, 11)  // R2 = 11
        original.bankWrite(6, 2)   // R6 = 2 (PRG)
        original.bankWrite(7, 4)   // R7 = 4 (PRG)
        original.cpuWrite(0x6000, 0x42.toSignedByte())   // seed PRG-RAM

        val bytes = ByteArrayOutputStream().also { original.saveState(DataOutputStream(it)) }.toByteArray()

        val restored = Mapper206(createTestGamePak())
        restored.loadState(DataInputStream(ByteArrayInputStream(bytes)))

        assertThat(restored.cpuRead(0x8000).toUnsignedInt(), equalTo(2))   // R6
        assertThat(restored.cpuRead(0xA000).toUnsignedInt(), equalTo(4))   // R7
        // R0=5 with low bit ignored -> page 4 in 1KB units -> stamp at $1000 = 4
        assertThat(restored.ppuRead(0x0000).toUnsignedInt(), equalTo(4))
        assertThat(restored.ppuRead(0x1000).toUnsignedInt(), equalTo(11))  // R2 = 11
        assertThat(restored.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
    }
}
