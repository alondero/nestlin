package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.testutil.testGamePak
import com.github.alondero.nestlin.testutil.testRom
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
 *  - 32 KB PRG banks: every byte = bank index, byte 0x7FFF = bank XOR 0xFF.
 *  - 8 KB CHR banks: every byte = bank index, byte 0x1FFF = bank XOR 0xFF.
 *
 * The chip has 8 PRG banks (3 bits) and 16 CHR banks (4 bits), so the
 * default fixture is 256 KB PRG / 128 KB CHR — that exercises the full
 * range without any modulo wrap. Smaller fixtures use a smaller
 * `prgBankCount` and rely on the modulo to keep oversized bank numbers
 * from indexing past the array.
 *
 * TestRomBuilder owns the 16-byte iNES header encoding. We use a custom
 * bank-stamp helper (full-bank fill + XOR sentinel) because the
 * built-in `stampPrgBanks` only stamps byte 0 of each window — for a
 * 32 KB Mapper 113 bank, byte 0 alone can't tell us "the whole window
 * is one bank, not the start of a fixed second bank at $C000".
 */
class Mapper113Test {

    /**
     * Stamp every byte of every 32 KB PRG bank with its bank index, and
     * set the *last* byte of each bank (byte 0x7FFF, which maps to $FFFF)
     * to `bank xor 0xFF` as a sentinel. The whole 32 KB window at
     * $8000-$FFFF is one bank for this mapper, so we have to stamp every
     * byte — unlike the 8 KB-bank fixtures (Mapper 33, 71) where the
     * 8 KB window is itself the unit and the stamp at byte 0 + 0x1FFF
     * is enough.
     */
    private fun stampPrg32kBanks(prg: ByteArray) {
        val bankCount = prg.size / 0x8000
        for (bank in 0 until bankCount) {
            java.util.Arrays.fill(
                prg, bank * 0x8000, bank * 0x8000 + 0x8000,
                (bank and 0xFF).toByte()
            )
            prg[bank * 0x8000 + 0x7FFF] = (bank xor 0xFF).toByte()
        }
    }

    /**
     * Stamp every byte of every 8 KB CHR bank with its bank index, and
     * set the *last* byte of each bank (byte 0x1FFF) to `bank xor 0xFF`
     * as a sentinel. The 8 KB window at $0000-$1FFF is one bank for
     * this mapper.
     */
    private fun stampChr8kBanks(chr: ByteArray) {
        val bankCount = chr.size / 0x2000
        for (bank in 0 until bankCount) {
            java.util.Arrays.fill(
                chr, bank * 0x2000, bank * 0x2000 + 0x2000,
                (bank and 0xFF).toByte()
            )
            chr[bank * 0x2000 + 0x1FFF] = (bank xor 0xFF).toByte()
        }
    }

    private fun newMapper113(
        prgBanks32k: Int = 8,
        chrBanks8k: Int = 16,
        mirroring: Header.Mirroring = Header.Mirroring.HORIZONTAL
    ): Mapper113 {
        val pak = testGamePak {
            mapper = 113
            prgKb = prgBanks32k * 32
            chrKb = chrBanks8k * 8
            verticalMirroring = mirroring == Header.Mirroring.VERTICAL
            stampPrg32kBanks(prg)
            stampChr8kBanks(chr)
        }
        return pak.createMapper() as Mapper113
    }

    /**
     * Encode a register byte from intent, using the canonical Mapper 113
     * layout (NESdev / Mesen `Mapper113::WriteRegister`):
     * ```
     *   bit 7 6 5 4 3 2 1 0
     *       M C P P P C C C
     * ```
     * PRG = bits 3-5, CHR = (bit 6 << 3) | bits 0-2, mirroring = bit 7.
     *
     * Tests assert through this helper rather than hand-rolled magic bytes
     * so they encode the *spec*, not the implementation — the wrong-decode
     * (`PRG = bits 4-6`) that shipped in issue #163 passed 31 hand-rolled
     * tests precisely because those tests were written against the buggy
     * formula.
     */
    private fun reg(prg: Int = 0, chr: Int = 0, vertical: Boolean = false): Byte {
        val v = (if (vertical) 0x80 else 0x00) or
            ((prg and 0x07) shl 3) or
            ((chr and 0x08) shl 3) or   // CHR high bit (value 8) -> register bit 6
            (chr and 0x07)
        return v.toSignedByte()
    }

    // ---- Dispatch ----

    @Test
    fun `mapper113 is selected for header mapper 113`() {
        assertThat(newMapper113() is Mapper113, equalTo(true))
    }

    // ---- PRG banking ----

    @Test
    fun `defaults to PRG last bank across the whole 8000-FFFF window`() {
        // The chip powers on with the LAST bank mapped, not bank 0. The
        // 8-bank fixture stamps every byte with the bank index, so every
        // PRG-window read should return 7 (= the last bank), and the
        // 0x7FFF sentinel should be (7 xor 0xFF). Confirmed against
        // Mesen2 v2.1.1: Mind Blower Pak's bank-3 init code at $B44E
        // runs on the first instruction — the chip never even enters
        // the self-replicating bank-1 trampoline that a bank-0 default
        // would fall into. If a future refactor changes the power-on
        // state, this test fails loud.
        val m = newMapper113(prgBanks32k = 8)
        val last = 7
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(last))
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(last))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(last))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(last))
        assertThat(m.cpuRead(0xFFFF).toUnsignedInt(), equalTo(last xor 0xFF))
    }

    @Test
    fun `PRG initial state reads the last bank's reset vector at FFFC-FFFD`() {
        // Mind Blower Pak's bank-3 reset vector is $B400 (the real init
        // code), while bank-0's is $9FE0 (the self-replicating
        // trampoline that the game uses to navigate between PRG banks).
        // A bank-0 power-on default reads $9FE0 and gets stuck in the
        // trampoline; a last-bank default reads $B400 and the boot
        // escapes on the first instruction. Cross-checked against
        // Mesen2 v2.1.1's frame-1 trace, which shows the CPU executing
        // from $B476 (in bank 3) with zero mapper-register writes.
        //
        // Fixture: 4 PRG banks; bank 0 stamped with 0xAA everywhere, the
        // last bank stamped with 0x55 everywhere. The reset vector in
        // the LAST bank is read as 0x55 0x55 = $5555. If the power-on
        // bank were 0, we'd see 0xAA 0xAA = $AAAA instead.
        val pak = testGamePak {
            mapper = 113
            prgKb = 4 * 32
            chrKb = 8
            verticalMirroring = false
            stampPrg32kBanks(prg)
            // Override the stamp: bank 0 = 0xAA, all other banks = 0x55.
            java.util.Arrays.fill(prg, 0, 0x8000, 0xAA.toByte())
            for (bank in 1 until 4) {
                java.util.Arrays.fill(
                    prg, bank * 0x8000, bank * 0x8000 + 0x8000, 0x55.toByte()
                )
            }
        }
        val m = pak.createMapper() as Mapper113
        val lo = m.cpuRead(0xFFFC).toUnsignedInt()
        val hi = m.cpuRead(0xFFFD).toUnsignedInt()
        assertThat(lo, equalTo(0x55))
        assertThat(hi, equalTo(0x55))
    }

    @Test
    fun `4100 write sets PRG bank via bits 3-5`() {
        val m = newMapper113(prgBanks32k = 8)
        m.cpuWrite(0x4100, reg(prg = 1))
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
        assertThat(m.cpuRead(0xFFFF).toUnsignedInt(), equalTo(1 xor 0xFF))
    }

    @Test
    fun `all 8 PRG banks are reachable`() {
        // 8 PRG banks, 3-bit field (bits 3-5) — no wrap for any value 0..7.
        val m = newMapper113(prgBanks32k = 8)
        for (bank in 0..7) {
            m.cpuWrite(0x4100, reg(prg = bank))
            assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(bank))
        }
    }

    @Test
    fun `PRG bank field is bits 3-5 only`() {
        // The PRG field is bits 3-5. Bits 0-2 + bit 6 hold the CHR field and
        // bit 7 holds the mirroring bit. 0xFF = all bits set, so the PRG
        // decode picks (0xFF >> 3) & 0x07 = 7.
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
        m.cpuWrite(0x4100, reg(prg = 5))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(5))
        assertThat(m.cpuRead(0xFFFF).toUnsignedInt(), equalTo(5 xor 0xFF))
    }

    @Test
    fun `PRG bank number larger than prgBankCount wraps modulo`() {
        // 4 PRG banks: any value V%4 is the effective bank. Selecting PRG 7,
        // 7 % 4 = 3.
        val m = newMapper113(prgBanks32k = 4)
        m.cpuWrite(0x4100, reg(prg = 7))
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
    }

    // ---- Open-bus (6502 data-bus) reads for $0000-$7FFF ----
    //
    // HES NTD-8 has no PRG-RAM and no registers below $8000 — the chip
    // has nothing of its own to return for a CPU read in $0000-$7FFF.
    // On a real 6502 (and in Mesen2), such a read returns the residual
    // byte on the data bus. Nestlin tracks that byte globally in
    // Memory.kt and pushes it into the mapper via the `dataBus`
    // property; the mapper must return it. Returning 0 instead is what
    // trapped Mind Blower Pak's reset-vector trampoline at $59A9 in a
    // self-replicating boot loop (see issue #139 and
    // `Mapper113RegressionTest`). The Memory layer sets `dataBus`
    // before each `cpuRead`; the test pre-seeds the value the same
    // way the interface's setter would.

    @Test
    fun `read below 8000 returns the data-bus value, not zero`() {
        val m = newMapper113(prgBanks32k = 8)
        // 0x59 is the byte the divergent Mind Blower Pak read at $59A9
        // — the JMP $0400 trampoline loaded $0401 = 0x59 onto the bus
        // before fetching the opcode at $59A9. A correct mapper
        // returns 0x59 (the open-bus value); the buggy implementation
        // returns 0, which the CPU decodes as `BRK` and the boot
        // path goes off the rails.
        m.dataBus = 0x59.toSignedByte()
        assertThat(m.cpuRead(0x59A9).toUnsignedInt(), equalTo(0x59))
    }

    @Test
    fun `read below 8000 returns whatever byte was on the data bus`() {
        // Same scenario, different bus value. Proves the mapper
        // doesn't just return 0x59 — it returns the captured data-bus
        // byte regardless of address or value.
        val m = newMapper113(prgBanks32k = 8)
        m.dataBus = 0xC3.toSignedByte()
        assertThat(m.cpuRead(0x0001).toUnsignedInt(), equalTo(0xC3))
        assertThat(m.cpuRead(0x07FF).toUnsignedInt(), equalTo(0xC3))
        m.dataBus = 0x00.toSignedByte()
        assertThat(m.cpuRead(0x5999).toUnsignedInt(), equalTo(0x00))
        m.dataBus = 0xFF.toSignedByte()
        assertThat(m.cpuRead(0x7FFF).toUnsignedInt(), equalTo(0xFF))
    }

    @Test
    fun `read below 8000 ignores PRG bank selection`() {
        // A read in $0000-$7FFF must not accidentally return PRG bank
        // data (the bank window starts at $8000). If a future refactor
        // ever widens the window, this test fails loud.
        val m = newMapper113(prgBanks32k = 8)
        m.cpuWrite(0x4100, reg(prg = 5))  // PRG bank 5
        m.dataBus = 0x42.toSignedByte()
        assertThat(m.cpuRead(0x0001).toUnsignedInt(), equalTo(0x42))
        assertThat(m.cpuRead(0x7FFF).toUnsignedInt(), equalTo(0x42))
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
    fun `4100 write sets CHR bank via bit 6 + bits 0-2`() {
        val m = newMapper113(chrBanks8k = 16)
        m.cpuWrite(0x4100, reg(chr = 5))  // CHR bank 5, PRG bank 0
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(5))
        assertThat(m.ppuRead(0x1FFF).toUnsignedInt(), equalTo(5 xor 0xFF))
    }

    @Test
    fun `all 16 CHR banks are reachable`() {
        val m = newMapper113(chrBanks8k = 16)
        for (bank in 0..15) {
            m.cpuWrite(0x4100, reg(chr = bank))
            assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(bank))
        }
    }

    @Test
    fun `CHR bank 15 (high bit + low 3 bits set) is reachable`() {
        // The CHR high bit (bit 3 of the index) lives in register bit 6.
        // reg(chr = 15) sets bit 6 and bits 0-2.
        val m = newMapper113(chrBanks8k = 16)
        m.cpuWrite(0x4100, reg(chr = 15))
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(15))
    }

    @Test
    fun `CHR bank 8 (high bit only) is reachable`() {
        // CHR bank 8 = high bit set, low bits clear = register bit 6 only.
        val m = newMapper113(chrBanks8k = 16)
        m.cpuWrite(0x4100, reg(chr = 8))
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(8))
    }

    @Test
    fun `CHR bank number larger than chrBankCount wraps modulo`() {
        // 8 CHR banks; selecting CHR 15, 15 % 8 = 7.
        val m = newMapper113(chrBanks8k = 8)
        m.cpuWrite(0x4100, reg(chr = 15))
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(7))
    }

    // ---- Mirroring ----

    @Test
    fun `mirroring follows iNES header (vertical) when 4100 has not been written`() {
        val m = newMapper113(mirroring = Header.Mirroring.VERTICAL)
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    @Test
    fun `mirroring follows iNES header when 4100 has not been written`() {
        val m = newMapper113(mirroring = Header.Mirroring.HORIZONTAL)
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `bit 7 of 4100 write selects mirroring (1=vert, 0=horiz)`() {
        val m = newMapper113(mirroring = Header.Mirroring.HORIZONTAL)
        m.cpuWrite(0x4100, 0x80.toSignedByte())  // bit 7 set -> vertical
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        m.cpuWrite(0x4100, 0x00.toSignedByte())  // bit 7 clear -> horizontal
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `mirroring bit is independent of the PRG and CHR bits`() {
        val m = newMapper113(mirroring = Header.Mirroring.HORIZONTAL)
        // 0xFF = all bits set: PRG=7, CHR=15, mirror=vertical.
        m.cpuWrite(0x4100, 0xFF.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(7))   // PRG 7
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(15))  // CHR 15
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    // ---- Register decode ($4100-$5FFF window) ----

    @Test
    fun `writes outside 4100-5FFF do not change state`() {
        val m = newMapper113()
        // Sanity: writes to $4000 (clears A8) and $6100 (sets A13) and
        // $8000 (PRG window) all fall outside the chip-select gate.
        m.cpuWrite(0x4000, 0xFF.toSignedByte())
        m.cpuWrite(0x6100, 0xFF.toSignedByte())
        m.cpuWrite(0x8000, 0xFF.toSignedByte())
        m.cpuWrite(0xFFFF, 0xFF.toSignedByte())
        // Default state preserved: PRG = LAST bank (power-on default),
        // CHR 0, horizontal (from header). See the "defaults to PRG
        // last bank" test for the power-on state rationale.
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(7))
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `writes to the 8 base pages in 4100-5FFF all decode to the same register`() {
        // (addr & 0xE100) == 0x4100 — low 8 bits are aliased across the
        // entire $4100-$41FF range, and the same is true for $4300-$43FF
        // ... $5F00-$5FFF. Every page in the chip-select window must
        // reach the same single register.
        val m = newMapper113()
        // 8 distinct pages: 0x4100, 0x4300, 0x4500, 0x4700, 0x4900,
        // 0x4B00, 0x4D00, 0x4F00, 0x5100, 0x5300, 0x5500, 0x5700,
        // 0x5900, 0x5B00, 0x5D00, 0x5F00 — 16 total.
        for (page in 0x4100..0x5F00 step 0x0200) {
            m.cpuWrite(page, reg(prg = 6, chr = 5, vertical = true))
            assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(6))
            assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(5))
        }
    }

    @Test
    fun `low 8 bits of the address are aliased`() {
        // Within a single page (e.g. $4100), any low address bits should
        // decode to the same register. $4142, $41FF all write the same
        // single byte.
        val m = newMapper113()
        m.cpuWrite(0x4142, reg(prg = 6, chr = 5, vertical = true))
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(6))
        m.cpuWrite(0x41FF, reg(prg = 0))
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `multiple writes overwrite the register`() {
        val m = newMapper113()
        m.cpuWrite(0x4100, reg(prg = 1))
        m.cpuWrite(0x4100, reg(prg = 3))
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
    }

    // ---- Save state ----

    @Test
    fun `saveState then loadState round-trips PRG, CHR, and mirroring`() {
        // NOTE: do NOT introduce local `prg`/`chr` variables here — they
        // would shadow TestRomBuilder's fields inside the `testGamePak`
        // block, and the stamp helpers would mutate the wrong array.
        // The `prg`/`chr` references inside the block are the builder's.
        val pak = testGamePak {
            mapper = 113
            prgKb = 256
            chrKb = 128
            verticalMirroring = false
            stampPrg32kBanks(prg)
            stampChr8kBanks(chr)
        }
        val original = pak.createMapper() as Mapper113
        original.cpuWrite(0x4100, reg(prg = 6, chr = 5, vertical = true))
        val bytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { original.saveState(it) }
            baos.toByteArray()
        }
        val fresh = pak.createMapper() as Mapper113
        DataInputStream(ByteArrayInputStream(bytes)).use { fresh.loadState(it) }
        assertThat(fresh.cpuRead(0x8000).toUnsignedInt(), equalTo(6))
        assertThat(fresh.ppuRead(0x0000).toUnsignedInt(), equalTo(5))
        assertThat(fresh.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    @Test
    fun `saveState round-trips CHR RAM when present`() {
        // 0 KB CHR (chrKb = 0) -> mapper allocates an 8 KB CHR RAM. Writes
        // survive a save+load round-trip.
        val pak = testGamePak {
            mapper = 113
            prgKb = 256
            chrKb = 0           // CHR-RAM (8 KB fallback)
            stampPrg32kBanks(prg)
        }
        val m = pak.createMapper() as Mapper113
        m.ppuWrite(0x0123, 0x77.toSignedByte())
        val bytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { m.saveState(it) }
            baos.toByteArray()
        }
        val fresh = pak.createMapper() as Mapper113
        DataInputStream(ByteArrayInputStream(bytes)).use { fresh.loadState(it) }
        assertThat(fresh.ppuRead(0x0123).toUnsignedInt(), equalTo(0x77))
    }

    // ---- Snapshot ----

    @Test
    fun `snapshot reflects all current state`() {
        val m = newMapper113(prgBanks32k = 8, chrBanks8k = 16, mirroring = Header.Mirroring.HORIZONTAL)
        m.cpuWrite(0x4100, reg(prg = 6, chr = 5, vertical = true))
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

    // ---- CHR ROM fallback ----

    @Test
    fun `missing CHR ROM falls back to 8KB CHR RAM`() {
        // 0 KB CHR in the header -> mapper treats the chip as having
        // 8 KB of writable CHR RAM. ppuRead/ppuWrite must not throw.
        val pak = testGamePak {
            mapper = 113
            prgKb = 256
            chrKb = 0
            stampPrg32kBanks(prg)
        }
        val m = pak.createMapper() as Mapper113
        // CHR RAM is zero-initialised.
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        m.ppuWrite(0x0000, 0x42.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0x42))
    }
}
