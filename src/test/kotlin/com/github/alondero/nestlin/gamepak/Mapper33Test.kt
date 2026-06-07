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
 * Unit tests for Mapper 33 (Taito TC0190).
 *
 * Test PRG/CHR are stamped with their bank index so a read asserts exactly
 * which bank is mapped into a window. The stamp convention follows the
 * existing in-repo test style (see `Mapper71Test` / `Mapper10Test`):
 *  - 8KB PRG banks: byte 0 = bank index, byte 0x1FFF = bank XOR 0xFF.
 *  - 1KB CHR banks: byte 0 = bank index, byte 0x3FF = bank XOR 0xFF.
 *
 * The TC0190 has four 8KB PRG banks ($8000, $A000, $C000 fixed-to-penultimate,
 * $E000 fixed-to-last) and eight 1KB CHR banks. PRG window sizing in the
 * fixtures is therefore 8KB (a "bank" here means 8KB for PRG and 1KB for CHR).
 */
class Mapper33Test {

    private fun buildIne(
        prg: ByteArray,
        chr: ByteArray,
        mirroring: Header.Mirroring = Header.Mirroring.HORIZONTAL
    ): ByteArray {
        require(prg.size % 0x4000 == 0) { "PRG must be a multiple of 16KB (iNES unit)" }
        require(chr.size % 0x2000 == 0) { "CHR must be a multiple of 8KB" }
        val header = ByteArray(16).apply {
            this[0] = 'N'.code.toByte(); this[1] = 'E'.code.toByte()
            this[2] = 'S'.code.toByte(); this[3] = 0x1A.toByte()
            // PRG is stamped in 8KB units for the mapper's read path, but the
            // iNES header counts 16KB units — so we always stamp an even
            // number of 8KB banks and encode as 16KB banks here.
            this[4] = (prg.size / 0x4000).toByte()
            this[5] = (chr.size / 0x2000).toByte()
            // mapper 33 = 0x21 -> low nibble 1, high nibble 2
            this[6] = ((33 and 0x0F) shl 4 or when (mirroring) {
                Header.Mirroring.HORIZONTAL -> 0x00
                Header.Mirroring.VERTICAL -> 0x01
            }).toByte()
            this[7] = (33 and 0xF0).toByte()
        }
        return header + prg + chr
    }

    /** Stamp each 8KB PRG bank with its bank index in byte 0. */
    private fun makeStampedPrg(banks8k: Int): ByteArray {
        val prg = ByteArray(banks8k * 0x2000)
        for (bank in 0 until banks8k) {
            prg[bank * 0x2000] = (bank and 0xFF).toByte()
            prg[bank * 0x2000 + 0x1FFF] = (bank xor 0xFF).toByte()
        }
        return prg
    }

    /** Stamp every 1KB page in [size8k] 8KB banks with its 1KB page index. */
    private fun makeStampedChr(size8k: Int): ByteArray {
        val total1k = size8k * 8
        val chr = ByteArray(size8k * 0x2000)
        for (page in 0 until total1k) {
            chr[page * 0x0400] = (page and 0xFF).toByte()
            chr[page * 0x0400 + 0x3FF] = (page xor 0xFF).toByte()
        }
        return chr
    }

    private fun newMapper33(
        prgBanks8k: Int = 8,
        chrSize8k: Int = 1,
        mirroring: Header.Mirroring = Header.Mirroring.HORIZONTAL
    ): Mapper33 {
        val prg = makeStampedPrg(prgBanks8k)
        val chr = makeStampedChr(chrSize8k)
        val gp = GamePak(buildIne(prg, chr, mirroring))
        return gp.createMapper() as Mapper33
    }

    // ---- Dispatch ----

    @Test
    fun `mapper33 is selected for header mapper 33`() {
        assertThat(newMapper33() is Mapper33, equalTo(true))
    }

    // ---- PRG banking ----

    @Test
    fun `defaults to PRG bank 0 at 8000 and A000, penultimate at C000, last at E000`() {
        // 8 PRG banks -> second-to-last = 6, last = 7.
        val m = newMapper33(prgBanks8k = 8)
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0x9FFF).toUnsignedInt(), equalTo(0 xor 0xFF))
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xBFFF).toUnsignedInt(), equalTo(0 xor 0xFF))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(6))
        assertThat(m.cpuRead(0xDFFF).toUnsignedInt(), equalTo(6 xor 0xFF))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(7))
        assertThat(m.cpuRead(0xFFFF).toUnsignedInt(), equalTo(7 xor 0xFF))
    }

    @Test
    fun `8000 write sets PRG bank 0 with low 6 bits`() {
        // 8 PRG banks of 8KB = 64KB. prgBank0 has a 6-bit field, so any
        // value 0..63 is valid; modulo is applied at the read site so an
        // out-of-range value wraps to the corresponding bank.
        val m = newMapper33(prgBanks8k = 8)
        m.cpuWrite(0x8000, 0x05.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(5))
        assertThat(m.cpuRead(0x9FFF).toUnsignedInt(), equalTo(5 xor 0xFF))
        // High bits 6-7 in $8000 don't affect the PRG bank.
        m.cpuWrite(0x8000, 0xC2.toSignedByte())   // 0xC2 & 0x3F = 0x02 = 2
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
    }

    @Test
    fun `8001 write sets PRG bank 1 with low 6 bits`() {
        val m = newMapper33(prgBanks8k = 8)
        m.cpuWrite(0x8001, 0x03.toSignedByte())
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(3))
        assertThat(m.cpuRead(0xBFFF).toUnsignedInt(), equalTo(3 xor 0xFF))
        // The $8000 window keeps its own bank.
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `C000 and E000 windows stay fixed across PRG bank writes`() {
        val m = newMapper33(prgBanks8k = 8)
        m.cpuWrite(0x8000, 0x05.toSignedByte())
        m.cpuWrite(0x8001, 0x04.toSignedByte())
        // $C000 still second-to-last, $E000 still last.
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(6))
        assertThat(m.cpuRead(0xDFFF).toUnsignedInt(), equalTo(6 xor 0xFF))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(7))
        assertThat(m.cpuRead(0xFFFF).toUnsignedInt(), equalTo(7 xor 0xFF))
    }

    @Test
    fun `all 8KB PRG banks are reachable from the 8000 window`() {
        val m = newMapper33(prgBanks8k = 8)
        for (bank in 0..7) {
            m.cpuWrite(0x8000, bank.toSignedByte())
            assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(bank))
        }
    }

    @Test
    fun `all 8KB PRG banks are reachable from the A000 window`() {
        val m = newMapper33(prgBanks8k = 8)
        for (bank in 0..7) {
            m.cpuWrite(0x8001, bank.toSignedByte())
            assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(bank))
        }
    }

    @Test
    fun `PRG bank numbers larger than prgBankCount wrap modulo`() {
        // 8 PRG banks. 0x39 & 0x3F = 0x39 = 57, 57 % 8 = 1.
        val m = newMapper33(prgBanks8k = 8)
        m.cpuWrite(0x8000, 0x39.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `single 16KB PRG ROM mirrors across all four windows`() {
        // The iNES header counts PRG in 16KB units, so the smallest fixture
        // is two 8KB banks = 16KB. Both windows point at the same two banks
        // when only one 16KB "iNES bank" is present, so the fixed PRG banks
        // at $C000 / $E000 collapse to bank 0 / bank 1.
        val m = newMapper33(prgBanks8k = 2)
        m.cpuWrite(0x8000, 0x3F.toSignedByte())    // any value wraps modulo 2
        m.cpuWrite(0x8001, 0x3F.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))   // 0x3F % 2 = 1
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(1))
        // coerceAtLeast(0) keeps the fixed-bank read safe at the
        // penultimate/last bank.
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(1))
    }

    // ---- Register-decode address aliasing (addr & 0xA003) ----

    @Test
    fun `8002 register decodes regardless of bits A2-A12`() {
        // $8042, $8FF2, $9FF2 all decode to $8002 via `addr & 0xA003`.
        val m = newMapper33()
        m.cpuWrite(0x8042, 0x01.toSignedByte())   // 2KB bank 1 at $0000
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(2))   // bank 1*2
        assertThat(m.ppuRead(0x0400).toUnsignedInt(), equalTo(3))   // bank 1*2+1

        // Reset, then use a different alias.
        val m2 = newMapper33()
        m2.cpuWrite(0x8FF2, 0x02.toSignedByte())
        assertThat(m2.ppuRead(0x0000).toUnsignedInt(), equalTo(4))

        val m3 = newMapper33()
        m3.cpuWrite(0x9FFA, 0x03.toSignedByte())
        assertThat(m3.ppuRead(0x0000).toUnsignedInt(), equalTo(6))
    }

    @Test
    fun `A000 register decodes regardless of bits A2-A12`() {
        // $A040, $B004, $BFEC all decode to $A000 via `addr & 0xA003`.
        // (Bit 0 = 0 AND bit 1 = 0 to keep the result; the $A0/A1 bit
        // (bit 13) just picks the $Axxx half — either 0 or 1 is fine.)
        val m = newMapper33()
        m.cpuWrite(0xA040, 0x05.toSignedByte())   // 1KB bank 5 at $1000
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(5))
        // $A001 (1KB bank for $1400) is a *different* register — must not
        // have been overwritten by the $A040 write.
        assertThat(m.ppuRead(0x1400).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `BFFE decodes to A002`() {
        val m = newMapper33()
        m.cpuWrite(0xBFFE, 0x07.toSignedByte())   // 1KB bank 7 at $1800
        assertThat(m.ppuRead(0x1800).toUnsignedInt(), equalTo(7))
    }

    // ---- CHR banking ----

    @Test
    fun `default CHR banks are all 0 across the 8KB window`() {
        val m = newMapper33(chrSize8k = 1)   // 8KB CHR -> 8 1KB banks
        // $0000 = bank 0
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        // $07FF is still inside the first 1KB bank (page 0)
        assertThat(m.ppuRead(0x07FF).toUnsignedInt(), equalTo(0 xor 0xFF))
        // $0800 starts a new 1KB page — still page 0
        assertThat(m.ppuRead(0x0800).toUnsignedInt(), equalTo(0))
        // $1000 — also page 0
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(0))
        // $1FFF is end of last 1KB page
        assertThat(m.ppuRead(0x1FFF).toUnsignedInt(), equalTo(0 xor 0xFF))
    }

    @Test
    fun `8002 write sets 2KB CHR bank at 0000-07FF as pair v2 and v2+1`() {
        val m = newMapper33(chrSize8k = 1)
        m.cpuWrite(0x8002, 0x03.toSignedByte())
        // 1KB bank 6 at $0000, bank 7 at $0400 (3*2=6, 3*2+1=7)
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(6))
        assertThat(m.ppuRead(0x03FF).toUnsignedInt(), equalTo(6 xor 0xFF))
        assertThat(m.ppuRead(0x0400).toUnsignedInt(), equalTo(7))
        assertThat(m.ppuRead(0x07FF).toUnsignedInt(), equalTo(7 xor 0xFF))
        // The 2KB write must not affect other windows.
        assertThat(m.ppuRead(0x0800).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `8002 preserves the LSB - unlike MMC3 R0 R1`() {
        // Per NESdev: "the value written for the two 2 KiB CHR banks do not
        // drop the LSB". So $05 -> banks 10 and 11, NOT banks 10 and 11
        // (masked to 10/11) which would be the MMC3 R0/R1 behaviour.
        val m = newMapper33(chrSize8k = 2)
        m.cpuWrite(0x8002, 0x05.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(10))
        assertThat(m.ppuRead(0x0400).toUnsignedInt(), equalTo(11))
    }

    @Test
    fun `8003 write sets 2KB CHR bank at 0800-0FFF as pair v2 and v2+1`() {
        val m = newMapper33(chrSize8k = 1)
        m.cpuWrite(0x8003, 0x02.toSignedByte())
        assertThat(m.ppuRead(0x0800).toUnsignedInt(), equalTo(4))
        assertThat(m.ppuRead(0x0BFF).toUnsignedInt(), equalTo(4 xor 0xFF))
        assertThat(m.ppuRead(0x0C00).toUnsignedInt(), equalTo(5))
        assertThat(m.ppuRead(0x0FFF).toUnsignedInt(), equalTo(5 xor 0xFF))
        // Other windows untouched.
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `A000 through A003 set 1KB CHR banks at 1000-1FFF`() {
        val m = newMapper33(chrSize8k = 1)
        m.cpuWrite(0xA000, 0x01.toSignedByte())   // 1KB bank 1 at $1000
        m.cpuWrite(0xA001, 0x02.toSignedByte())   // 1KB bank 2 at $1400
        m.cpuWrite(0xA002, 0x03.toSignedByte())   // 1KB bank 3 at $1800
        m.cpuWrite(0xA003, 0x04.toSignedByte())   // 1KB bank 4 at $1C00
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(1))
        assertThat(m.ppuRead(0x13FF).toUnsignedInt(), equalTo(1 xor 0xFF))
        assertThat(m.ppuRead(0x1400).toUnsignedInt(), equalTo(2))
        assertThat(m.ppuRead(0x1800).toUnsignedInt(), equalTo(3))
        assertThat(m.ppuRead(0x1C00).toUnsignedInt(), equalTo(4))
    }

    @Test
    fun `2KB and 1KB CHR writes are independent`() {
        // The $8002 / $8003 2KB writes must not bleed into the four 1KB
        // registers at $A000-$A003, and vice versa.
        val m = newMapper33(chrSize8k = 2)
        m.cpuWrite(0xA001, 0x07.toSignedByte())   // bank 7 at $1400
        m.cpuWrite(0x8002, 0x04.toSignedByte())   // banks 8,9 at $0000-$07FF
        assertThat(m.ppuRead(0x1400).toUnsignedInt(), equalTo(7))    // unchanged
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(8))
    }

    @Test
    fun `CHR bank writes only affect the target 1KB window`() {
        // Each 1KB CHR page is independent. Writing $A001 must not affect
        // $A000's bank, and so on.
        val m = newMapper33(chrSize8k = 1)
        m.cpuWrite(0xA000, 0x01.toSignedByte())
        m.cpuWrite(0xA001, 0x02.toSignedByte())
        m.cpuWrite(0xA002, 0x03.toSignedByte())
        m.cpuWrite(0xA003, 0x04.toSignedByte())
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(1))
        assertThat(m.ppuRead(0x1400).toUnsignedInt(), equalTo(2))
        assertThat(m.ppuRead(0x1800).toUnsignedInt(), equalTo(3))
        assertThat(m.ppuRead(0x1C00).toUnsignedInt(), equalTo(4))
    }

    @Test
    fun `missing CHR ROM falls back to 8KB CHR RAM`() {
        val prg = makeStampedPrg(2)                // 16 KB PRG (2 8KB banks)
        val header = ByteArray(16).apply {
            this[0] = 'N'.code.toByte(); this[1] = 'E'.code.toByte()
            this[2] = 'S'.code.toByte(); this[3] = 0x1A.toByte()
            // iNES header counts PRG in 16 KB units -> 1 means 16 KB
            this[4] = 1.toByte()
            this[5] = 0.toByte()                   // 0 KB CHR -> CHR RAM fallback
            this[6] = ((33 and 0x0F) shl 4).toByte()
            this[7] = (33 and 0xF0).toByte()
        }
        val m = GamePak(header + prg).createMapper() as Mapper33
        // RAM is zero-initialized.
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        // Writes stick.
        m.ppuWrite(0x1234, 0xAB.toSignedByte())
        assertThat(m.ppuRead(0x1234).toUnsignedInt(), equalTo(0xAB))
    }

    // ---- Mirroring ----

    @Test
    fun `mirroring follows iNES header when 8000 has not been written`() {
        val m = newMapper33(mirroring = Header.Mirroring.HORIZONTAL)
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `mirroring follows iNES header (vertical) when 8000 has not been written`() {
        val m = newMapper33(mirroring = Header.Mirroring.VERTICAL)
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    @Test
    fun `bit 6 of 8000 write selects mirroring (0=vert, 1=horiz)`() {
        val m = newMapper33(mirroring = Header.Mirroring.HORIZONTAL)  // start horiz
        // Write 0x00 to $8000: bit 6 clear -> vertical.
        m.cpuWrite(0x8000, 0x00.toSignedByte())
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        // Write 0x40 to $8000: bit 6 set -> horizontal.
        m.cpuWrite(0x8000, 0x40.toSignedByte())
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `mirroring bit is independent of the PRG bank value`() {
        // Bit 6 is the only mirroring-relevant bit in the $8000 register.
        // Bits 0-5 are the PRG bank, bits 7+ are unused. The mirroring
        // outcome must depend only on bit 6.
        val m = newMapper33(mirroring = Header.Mirroring.VERTICAL)  // start vert
        m.cpuWrite(0x8000, 0x05.toSignedByte())    // bit 6 clear, PRG bank 5
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(5))     // PRG = 5
        m.cpuWrite(0x8000, 0xC0.toSignedByte())    // bit 6,7 set, PRG bank 0
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))     // PRG = 0
    }

    @Test
    fun `mirroring is not changed by writes to other registers`() {
        // Writes to $8001, $8002, $8003, $A000-$A003 must leave mirroring alone.
        val m = newMapper33(mirroring = Header.Mirroring.VERTICAL)
        for (addr in listOf(0x8001, 0x8002, 0x8003, 0xA000, 0xA001, 0xA002, 0xA003, 0xBFFF)) {
            m.cpuWrite(addr, 0xFF.toSignedByte())
            assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        }
    }

    // ---- Save / load ----

    @Test
    fun `saveState then loadState round-trips all registers and mirroring`() {
        val m = newMapper33(prgBanks8k = 8, chrSize8k = 1, mirroring = Header.Mirroring.VERTICAL)
        m.cpuWrite(0x8000, 0x45.toSignedByte())   // prgBank0=5, horiz
        m.cpuWrite(0x8001, 0x07.toSignedByte())   // prgBank1=7
        m.cpuWrite(0x8002, 0x02.toSignedByte())   // chr 4,5 at $0000
        m.cpuWrite(0x8003, 0x03.toSignedByte())   // chr 6,7 at $0800
        m.cpuWrite(0xA000, 0x01.toSignedByte())   // chr 1 at $1000
        m.cpuWrite(0xA001, 0x02.toSignedByte())
        m.cpuWrite(0xA002, 0x03.toSignedByte())
        m.cpuWrite(0xA003, 0x04.toSignedByte())

        val bytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { m.saveState(it) }
            baos.toByteArray()
        }
        val fresh = newMapper33(prgBanks8k = 8, chrSize8k = 1, mirroring = Header.Mirroring.VERTICAL)
        DataInputStream(ByteArrayInputStream(bytes)).use { fresh.loadState(it) }

        // PRG
        assertThat(fresh.cpuRead(0x8000).toUnsignedInt(), equalTo(5))
        assertThat(fresh.cpuRead(0xA000).toUnsignedInt(), equalTo(7))
        assertThat(fresh.cpuRead(0xC000).toUnsignedInt(), equalTo(6))   // still penultimate
        assertThat(fresh.cpuRead(0xE000).toUnsignedInt(), equalTo(7))   // still last
        // CHR
        assertThat(fresh.ppuRead(0x0000).toUnsignedInt(), equalTo(4))
        assertThat(fresh.ppuRead(0x0400).toUnsignedInt(), equalTo(5))
        assertThat(fresh.ppuRead(0x0800).toUnsignedInt(), equalTo(6))
        assertThat(fresh.ppuRead(0x0C00).toUnsignedInt(), equalTo(7))
        assertThat(fresh.ppuRead(0x1000).toUnsignedInt(), equalTo(1))
        assertThat(fresh.ppuRead(0x1400).toUnsignedInt(), equalTo(2))
        assertThat(fresh.ppuRead(0x1800).toUnsignedInt(), equalTo(3))
        assertThat(fresh.ppuRead(0x1C00).toUnsignedInt(), equalTo(4))
        // Mirroring
        assertThat(fresh.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `saveState round-trips CHR RAM when present`() {
        val prg = makeStampedPrg(2)                // 16 KB PRG (2 8KB banks)
        val header = ByteArray(16).apply {
            this[0] = 'N'.code.toByte(); this[1] = 'E'.code.toByte()
            this[2] = 'S'.code.toByte(); this[3] = 0x1A.toByte()
            this[4] = 1.toByte()                    // 1 16KB PRG bank
            this[5] = 0.toByte()                    // 0 KB CHR -> CHR RAM fallback
            this[6] = ((33 and 0x0F) shl 4).toByte()
            this[7] = (33 and 0xF0).toByte()
        }
        val m = GamePak(header + prg).createMapper() as Mapper33
        m.ppuWrite(0x0123, 0x77.toSignedByte())
        val bytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { m.saveState(it) }
            baos.toByteArray()
        }
        val fresh = GamePak(header + prg).createMapper() as Mapper33
        DataInputStream(ByteArrayInputStream(bytes)).use { fresh.loadState(it) }
        assertThat(fresh.ppuRead(0x0123).toUnsignedInt(), equalTo(0x77))
    }

    // ---- Snapshot ----

    @Test
    fun `snapshot reflects all current state`() {
        val m = newMapper33()
        m.cpuWrite(0x8000, 0x02.toSignedByte())    // prgBank0=2, vert
        m.cpuWrite(0x8001, 0x03.toSignedByte())
        m.cpuWrite(0x8002, 0x01.toSignedByte())
        m.cpuWrite(0xA001, 0x04.toSignedByte())
        val snap = m.snapshot()
        assertThat(snap.mapperId, equalTo(33))
        assertThat(snap.banks["prgBank0"], equalTo(2))
        assertThat(snap.banks["prgBank1"], equalTo(3))
        // $8002 = 1 -> chr[0] = 2, chr[1] = 3
        assertThat(snap.banks["chrBank0"], equalTo(2))
        assertThat(snap.banks["chrBank1"], equalTo(3))
        // $A001 = 4 -> chr[5] = 4
        assertThat(snap.banks["chrBank5"], equalTo(4))
        // $8000 = 0x02 -> mirroring bit clear -> 0
        assertThat(snap.registers["horizontalMirroring"], equalTo(0))
    }

    @Test
    fun `snapshot reports horizontal mirroring after a 0x40 write`() {
        val m = newMapper33()
        m.cpuWrite(0x8000, 0x40.toSignedByte())
        val snap = m.snapshot()
        assertThat(snap.registers["horizontalMirroring"], equalTo(1))
    }
}
