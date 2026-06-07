package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Unit tests for Mapper 113 (HES NTD-8 / PT-554A) — *Mind Blower Pak*
 * and *Total Funpak* (HES Australia, 1992).
 *
 * Test PRG/CHR are stamped with their bank index so a read asserts
 * exactly which bank is mapped into a window:
 *  - 32 KB PRG banks: byte 0 = bank index, byte 0x7FFF = bank XOR 0xFF.
 *  - 8 KB CHR banks: byte 0 = bank index, byte 0x1FFF = bank XOR 0xFF.
 *
 * The chip has 8 PRG banks (3 bits) and 16 CHR banks (4 bits), so the
 * default fixture is 256 KB PRG / 128 KB CHR — that exercises the full
 * range without any modulo wrap. Smaller fixtures use a smaller
 * `prgBankCount` and rely on the modulo to keep oversized bank numbers
 * from indexing past the array.
 */
class Mapper113Test {

    /**
     * Build a minimal iNES image for mapper 113 with the given PRG/CHR
     * sizes. iNES counts PRG in 16 KB units and CHR in 8 KB units, so
     * 256 KB PRG needs byte 4 = 16 and 128 KB CHR needs byte 5 = 16.
     *
     * Mapper 113 = `(byte6 >> 4) | (byte7 & 0xF0) = 0x1 | 0x70 = 0x71`.
     * Mirroring bit 0 of byte 6 is wired so the test can pass H or V.
     */
    private fun buildIne(
        prg: ByteArray,
        chr: ByteArray,
        mirroring: Header.Mirroring = Header.Mirroring.HORIZONTAL
    ): ByteArray {
        require(prg.size % 0x4000 == 0) { "PRG must be a multiple of 16 KB" }
        require(chr.size % 0x2000 == 0) { "CHR must be a multiple of 8 KB" }
        val header = ByteArray(16).apply {
            this[0] = 'N'.code.toByte(); this[1] = 'E'.code.toByte()
            this[2] = 'S'.code.toByte(); this[3] = 0x1A.toByte()
            this[4] = (prg.size / 0x4000).toByte()
            this[5] = (chr.size / 0x2000).toByte()
            // 113 = 0x71: low nibble 1, high nibble 7
            this[6] = ((113 and 0x0F) shl 4 or when (mirroring) {
                Header.Mirroring.HORIZONTAL -> 0x00
                Header.Mirroring.VERTICAL -> 0x01
            }).toByte()
            this[7] = (113 and 0xF0).toByte()
        }
        return header + prg + chr
    }

    /**
     * Stamp every byte of every 32 KB PRG bank with its bank index, and
     * set the *last* byte of each bank (byte 0x7FFF, which maps to $FFFF)
     * to `bank xor 0xFF` as a sentinel. The whole 32 KB window at
     * $8000-$FFFF is one bank for this mapper, so we have to stamp every
     * byte — unlike the 8 KB-bank fixtures (Mapper 33, 71) where the
     * 8 KB window is itself the unit and the stamp at byte 0 + 0x1FFF
     * is enough.
     */
    private fun makeStampedPrg32k(banks32k: Int): ByteArray {
        val prg = ByteArray(banks32k * 0x8000)
        for (bank in 0 until banks32k) {
            java.util.Arrays.fill(
                prg, bank * 0x8000, bank * 0x8000 + 0x8000,
                (bank and 0xFF).toByte()
            )
            prg[bank * 0x8000 + 0x7FFF] = (bank xor 0xFF).toByte()
        }
        return prg
    }

    /** Stamp every 8 KB CHR bank with its bank index, and the last byte (0x1FFF) with bank xor 0xFF. */
    private fun makeStampedChr8k(banks8k: Int): ByteArray {
        val chr = ByteArray(banks8k * 0x2000)
        for (bank in 0 until banks8k) {
            java.util.Arrays.fill(
                chr, bank * 0x2000, bank * 0x2000 + 0x2000,
                (bank and 0xFF).toByte()
            )
            chr[bank * 0x2000 + 0x1FFF] = (bank xor 0xFF).toByte()
        }
        return chr
    }

    private fun newMapper113(
        prgBanks32k: Int = 8,
        chrBanks8k: Int = 16,
        mirroring: Header.Mirroring = Header.Mirroring.HORIZONTAL
    ): Mapper113 {
        val prg = makeStampedPrg32k(prgBanks32k)
        val chr = makeStampedChr8k(chrBanks8k)
        val gp = GamePak(buildIne(prg, chr, mirroring))
        return gp.createMapper() as Mapper113
    }

    // ---- Dispatch ----

    @Test
    fun `mapper113 is selected for header mapper 113`() {
        assertThat(newMapper113() is Mapper113, equalTo(true))
    }

    // ---- PRG banking ----

    @Test
    fun `defaults to PRG bank 0 across the whole 8000-FFFF window`() {
        // 8 PRG banks -> no fixed last bank; the entire 32KB at $8000-$FFFF
        // is the selected bank (default 0). The fixture fills every byte
        // with the bank index, so any PRG-window read returns 0 here. The
        // end-of-bank byte 0x7FFF (= $FFFF) is also stamped with the
        // xor-0xFF sentinel so the test can prove the full 32KB window is
        // one bank (not the start of a fixed second bank).
        val m = newMapper113(prgBanks32k = 8)
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xFFFF).toUnsignedInt(), equalTo(0 xor 0xFF))
    }

    @Test
    fun `4100 write sets PRG bank via bits 4-6`() {
        val m = newMapper113(prgBanks32k = 8)
        // 0x10 = bit 4 set -> PRG bank 1.
        m.cpuWrite(0x4100, 0x10.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
        assertThat(m.cpuRead(0xFFFF).toUnsignedInt(), equalTo(1 xor 0xFF))
    }

    @Test
    fun `all 8 PRG banks are reachable`() {
        // 8 PRG banks, 3-bit field — no wrap for any value 0..7.
        val m = newMapper113(prgBanks32k = 8)
        for (bank in 0..7) {
            m.cpuWrite(0x4100, (bank shl 4).toSignedByte())
            assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(bank))
        }
    }

    @Test
    fun `PRG bank field ignores bits 0-3 and bit 7`() {
        // The PRG field is bits 4-6 only. Bits 0-3 hold the CHR field and
        // bit 7 holds the mirroring bit. 0xFF = all bits set, but the PRG
        // decode picks (0xFF >> 4) & 0x07 = 7.
        val m = newMapper113(prgBanks32k = 8)
        m.cpuWrite(0x4100, 0xFF.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `32KB PRG bank covers the whole 8000-FFFF window (no fixed bank at C000)`() {
        // Differentiates Mapper 113 from Mapper 2 (UNROM, which has the
        // last 16 KB bank fixed at $C000). After switching to bank 5, the
        // byte at $C000 must be the start of bank 5, NOT a fixed last bank.
        val m = newMapper113(prgBanks32k = 8)
        m.cpuWrite(0x4100, (5 shl 4).toSignedByte())
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(5))
        assertThat(m.cpuRead(0xFFFF).toUnsignedInt(), equalTo(5 xor 0xFF))
    }

    @Test
    fun `PRG bank number larger than prgBankCount wraps modulo`() {
        // 4 PRG banks: any value V%4 is the effective bank. 0x70 -> bank 7,
        // 7 % 4 = 3.
        val m = newMapper113(prgBanks32k = 4)
        m.cpuWrite(0x4100, 0x70.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
    }

    // ---- CHR banking ----

    @Test
    fun `defaults to CHR bank 0 across 0000-1FFF`() {
        val m = newMapper113(chrBanks8k = 16)
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        // 0x1FFF is the last byte of the first 8KB CHR bank — sentinel.
        assertThat(m.ppuRead(0x1FFF).toUnsignedInt(), equalTo(0 xor 0xFF))
        assertThat(m.ppuRead(0x0800).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `4100 write sets CHR bank via bits 0-3`() {
        val m = newMapper113(chrBanks8k = 16)
        // 0x05 = CHR bank 5, PRG bank 0.
        m.cpuWrite(0x4100, 0x05.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(5))
        assertThat(m.ppuRead(0x1FFF).toUnsignedInt(), equalTo(5 xor 0xFF))
    }

    @Test
    fun `all 16 CHR banks are reachable`() {
        val m = newMapper113(chrBanks8k = 16)
        for (bank in 0..15) {
            m.cpuWrite(0x4100, bank.toSignedByte())
            assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(bank))
        }
    }

    @Test
    fun `CHR bank 15 (bit 3 set + low 3 bits set) is reachable`() {
        // Bit 3 is the high bit of the CHR field. 0x0F = bank 15.
        val m = newMapper113(chrBanks8k = 16)
        m.cpuWrite(0x4100, 0x0F.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(15))
    }

    @Test
    fun `CHR bank 8 (bit 3 set) is reachable`() {
        val m = newMapper113(chrBanks8k = 16)
        m.cpuWrite(0x4100, 0x08.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(8))
    }

    @Test
    fun `CHR bank number larger than chrBankCount wraps modulo`() {
        // 4 CHR banks: 0x0F = 15, 15 % 4 = 3.
        val m = newMapper113(chrBanks8k = 4)
        m.cpuWrite(0x4100, 0x0F.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(3))
    }

    // ---- Mirroring ----

    @Test
    fun `mirroring follows iNES header when 4100 has not been written`() {
        val m = newMapper113(mirroring = Header.Mirroring.HORIZONTAL)
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `mirroring follows iNES header (vertical) when 4100 has not been written`() {
        val m = newMapper113(mirroring = Header.Mirroring.VERTICAL)
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    @Test
    fun `bit 7 of 4100 write selects mirroring (1=vert, 0=horiz)`() {
        val m = newMapper113(mirroring = Header.Mirroring.HORIZONTAL)   // start horiz
        m.cpuWrite(0x4100, 0x80.toSignedByte())   // bit 7 set -> vertical
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        m.cpuWrite(0x4100, 0x00.toSignedByte())   // bit 7 clear -> horizontal
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `mirroring bit is independent of the PRG and CHR bits`() {
        // The mirroring field (bit 7) and the PRG/CHR fields (bits 0-6)
        // are independent. Mirroring outcome depends only on bit 7.
        val m = newMapper113(mirroring = Header.Mirroring.VERTICAL)   // start vert
        m.cpuWrite(0x4100, 0x05.toSignedByte())   // bit 7 clear, CHR 5
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
        // PRG field (bits 4-6) must still be applied normally.
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        m.cpuWrite(0x4100, 0xE0.toSignedByte())   // bits 4-6,7 set -> PRG 6, bit 7 -> vert
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(6))
    }

    // ---- Register-decode window ($4100-$5FFF, mask 0xE100) ----

    @Test
    fun `writes to the 8 base pages in 4100-5FFF all decode to the same register`() {
        // The chip-select gate is `(addr & 0xE100) == 0x4100`. A8 set,
        // A9-A12 clear, A13-A15 clear. That picks one 256-byte page out
        // of every 0x0200 (A8 set, A9-A12 clear) within $4100-$5FFF:
        // 4100, 4300, 4500, 4700, 4900, 4B00, 4D00, 4F00, 5100, ..., 5F00.
        val testAddrs = listOf(
            0x4100, 0x41FF, 0x4300, 0x43FF, 0x4500, 0x45FF,
            0x4700, 0x49FF, 0x4B00, 0x4DFF, 0x4F00,
            0x5100, 0x53FF, 0x5500, 0x57FF, 0x5900, 0x5BFF, 0x5D00, 0x5FFF
        )
        for (addr in testAddrs) {
            val m = newMapper113(prgBanks32k = 8, chrBanks8k = 16)
            m.cpuWrite(addr, 0xE5.toSignedByte())   // PRG 6, CHR 5, vert
            assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(6))
            assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(5))
            assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        }
    }

    @Test
    fun `writes outside 4100-5FFF do not change state`() {
        // The chip-select gate rejects everything outside $4100-$5FFF.
        // PRG window writes ($8000-$FFFF) are NOT the register, and
        // neither is the PRG-RAM window ($6000-$7FFF — irrelevant here
        // because the chip has no PRG-RAM, but the decode must still
        // reject it), nor APU/IO ($4000-$401F, $4020-$40FF), nor RAM.
        val m = newMapper113(prgBanks32k = 8, chrBanks8k = 16)
        val nonRegisterAddrs = listOf(
            0x0000, 0x07FF, 0x1FFF,       // RAM
            0x2000, 0x2002,                // PPU regs
            0x4000, 0x4015, 0x4016, 0x4017,   // APU/IO
            0x4020, 0x40FF,                // expansion (just below 0x4100)
            0x4080,                        // A8 clear -> rejected
            0x4200,                        // A9 set -> rejected
            0x4400,                        // A10 set -> rejected
            0x4800,                        // A11 set -> rejected
            0x5000,                        // A12 set -> rejected
            0x6100,                        // A13 set -> rejected
            0x8100,                        // A15 set -> rejected
            0x8000, 0xC000, 0xFFFF,        // PRG window (not the register)
            0x6000, 0x7FFF                 // PRG-RAM window (chip has none)
        )
        for (addr in nonRegisterAddrs) {
            m.cpuWrite(addr, 0xFF.toSignedByte())   // would change everything
            // State must be untouched: bank 0, CHR 0, horizontal.
            assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
            assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
            assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
        }
    }

    @Test
    fun `low 8 bits of the address are aliased`() {
        // The low 8 bits are not in the decode mask, so writes to $4100
        // and $41FF must hit the same register.
        val m1 = newMapper113(prgBanks32k = 8, chrBanks8k = 16)
        m1.cpuWrite(0x4100, 0x35.toSignedByte())   // PRG 3, CHR 5, horiz
        assertThat(m1.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(m1.ppuRead(0x0000).toUnsignedInt(), equalTo(5))

        val m2 = newMapper113(prgBanks32k = 8, chrBanks8k = 16)
        m2.cpuWrite(0x41FF, 0x35.toSignedByte())
        assertThat(m2.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(m2.ppuRead(0x0000).toUnsignedInt(), equalTo(5))
    }

    @Test
    fun `multiple writes overwrite the register`() {
        // Real games write to the register many times. The latest write wins.
        val m = newMapper113(prgBanks32k = 8, chrBanks8k = 16)
        m.cpuWrite(0x4100, 0x10.toSignedByte())   // PRG 1
        m.cpuWrite(0x4100, 0x25.toSignedByte())   // PRG 2, CHR 5
        m.cpuWrite(0x4100, 0x9F.toSignedByte())   // PRG 9 % 8 = 1, CHR 15, vert
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(15))
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    @Test
    fun `missing CHR ROM falls back to 8KB CHR RAM`() {
        val prg = makeStampedPrg32k(2)               // 64 KB PRG
        val header = ByteArray(16).apply {
            this[0] = 'N'.code.toByte(); this[1] = 'E'.code.toByte()
            this[2] = 'S'.code.toByte(); this[3] = 0x1A.toByte()
            this[4] = (prg.size / 0x4000).toByte()
            this[5] = 0.toByte()                     // 0 KB CHR -> CHR RAM fallback
            this[6] = ((113 and 0x0F) shl 4).toByte()
            this[7] = (113 and 0xF0).toByte()
        }
        val m = GamePak(header + prg).createMapper() as Mapper113
        // RAM is zero-initialized.
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        // Writes stick.
        m.ppuWrite(0x1234, 0xAB.toSignedByte())
        assertThat(m.ppuRead(0x1234).toUnsignedInt(), equalTo(0xAB))
    }

    // ---- Save / load ----

    @Test
    fun `saveState then loadState round-trips PRG, CHR, and mirroring`() {
        val m = newMapper113(prgBanks32k = 8, chrBanks8k = 16, mirroring = Header.Mirroring.VERTICAL)
        m.cpuWrite(0x4100, 0xE5.toSignedByte())   // PRG 6, CHR 5, vert
        val bytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { m.saveState(it) }
            baos.toByteArray()
        }
        val fresh = newMapper113(prgBanks32k = 8, chrBanks8k = 16, mirroring = Header.Mirroring.VERTICAL)
        // Sanity: fresh mapper is at its defaults.
        assertThat(fresh.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(fresh.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        assertThat(fresh.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        DataInputStream(ByteArrayInputStream(bytes)).use { fresh.loadState(it) }
        assertThat(fresh.cpuRead(0x8000).toUnsignedInt(), equalTo(6))
        assertThat(fresh.ppuRead(0x0000).toUnsignedInt(), equalTo(5))
        assertThat(fresh.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    @Test
    fun `saveState round-trips CHR RAM when present`() {
        val prg = makeStampedPrg32k(2)
        val header = ByteArray(16).apply {
            this[0] = 'N'.code.toByte(); this[1] = 'E'.code.toByte()
            this[2] = 'S'.code.toByte(); this[3] = 0x1A.toByte()
            this[4] = (prg.size / 0x4000).toByte()
            this[5] = 0.toByte()                     // 0 KB CHR -> CHR RAM fallback
            this[6] = ((113 and 0x0F) shl 4).toByte()
            this[7] = (113 and 0xF0).toByte()
        }
        val m = GamePak(header + prg).createMapper() as Mapper113
        m.ppuWrite(0x0123, 0x77.toSignedByte())
        val bytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { m.saveState(it) }
            baos.toByteArray()
        }
        val fresh = GamePak(header + prg).createMapper() as Mapper113
        DataInputStream(ByteArrayInputStream(bytes)).use { fresh.loadState(it) }
        assertThat(fresh.ppuRead(0x0123).toUnsignedInt(), equalTo(0x77))
    }

    // ---- Snapshot ----

    @Test
    fun `snapshot reflects all current state`() {
        val m = newMapper113(prgBanks32k = 8, chrBanks8k = 16, mirroring = Header.Mirroring.HORIZONTAL)
        m.cpuWrite(0x4100, 0xE5.toSignedByte())   // PRG 6, CHR 5, vert
        val snap = m.snapshot()
        assertThat(snap.mapperId, equalTo(113))
        assertThat(snap.banks["prgBank"], equalTo(6))
        assertThat(snap.banks["chrBank"], equalTo(5))
        assertThat(snap.registers["verticalMirroring"], equalTo(1))
    }

    @Test
    fun `snapshot reports header mirroring when 4100 has not been written`() {
        val m = newMapper113(mirroring = Header.Mirroring.VERTICAL)
        val snap = m.snapshot()
        assertThat(snap.registers["verticalMirroring"], equalTo(1))
    }

    @Test
    fun `snapshot reports horizontal mirroring after a 0x00 write`() {
        val m = newMapper113(mirroring = Header.Mirroring.VERTICAL)
        m.cpuWrite(0x4100, 0x10.toSignedByte())   // bit 7 clear -> horiz
        val snap = m.snapshot()
        assertThat(snap.registers["verticalMirroring"], equalTo(0))
    }
}
