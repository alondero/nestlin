package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.testutil.testGamePak
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
 * Unit tests for the VRC6 chip shared by [Mapper24] (VRC6a) and [Mapper26]
 * (VRC6b). Per the VRC6 family pattern in this project (see Vrc4 and its three
 * concrete mappers), the chip itself is one `Vrc6` class; the variants are
 * thin concrete classes that differ only in their address-pin decode.
 *
 * **Stamp convention.** PRG is stamped in 8KB units (byte 0 of each 8KB
 * chunk holds the 8KB-bank index). This puts a unique stamp at $8000
 * (start of the 16KB window = 8KB bank 0), at $A000 (the second 8KB of
 * the 16KB window = 8KB bank 1), and at $C000 / $E000 (8KB windows). CHR
 * is stamped in 1KB units. CHR bank values written to $D000-$E003 stay
 * 0..7 to fit within the 8KB CHR ROM.
 */
class Vrc6Test {

    // ---- Mapper dispatch ----

    @Test
    fun `mapper24 is selected for header mapper 24`() {
        val gp = testGamePak {
            mapper = 24
            prgKb = 128
            chrKb = 64
        }
        assertThat(gp.createMapper() is Mapper24, equalTo(true))
    }

    @Test
    fun `mapper26 is selected for header mapper 26`() {
        val gp = testGamePak {
            mapper = 26
            prgKb = 128
            chrKb = 64
        }
        assertThat(gp.createMapper() is Mapper26, equalTo(true))
    }

    // ---- PRG banking ----

    @Test
    fun `defaults to prg bank 0 at 8000 and A000, last at E000`() {
        // 128KB = 16 × 8KB. With 8KB stamps, last 8K = bank 15.
        val m = newVrc6a(prgKb = 128)
        assertThat("8000 = 8KB bank 0", m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        // The 16K window contains 8KB banks 0 and 1; $A000 lands at the
        // START of 8KB bank 1, so byte 0 = stamp 1.
        assertThat("A000 = 8KB bank 1 (second half of 16K window)",
            m.cpuRead(0xA000).toUnsignedInt(), equalTo(1))
        assertThat("C000 = 8KB bank 0", m.cpuRead(0xC000).toUnsignedInt(), equalTo(0))
        assertThat("E000 = last 8KB bank (15)", m.cpuRead(0xE000).toUnsignedInt(), equalTo(15))
        // FFFF lands in the middle of the last 8KB bank; only byte 0 of each
        // 8KB bank is stamped, so this byte is 0. The fixed-last-window
        // behaviour is verified by $E000 above.
        assertThat("FFFF = 0 (only byte 0 of last 8KB is stamped)", m.cpuRead(0xFFFF).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `8000 write sets the 16K prg bank with low 4 bits`() {
        val m = newVrc6a(prgKb = 128)
        // Write bank 5 (4-bit field, so 5 in 4 bits = 0x05).
        m.cpuWrite(0x8000, 0x05.toSignedByte())
        // The 16K window is now bank 5 (8KB banks 10+11).
        // $8000 = byte 0 of 8KB bank 10 = 10.
        assertThat("8000 = 8KB bank 10", m.cpuRead(0x8000).toUnsignedInt(), equalTo(10))
        // $A000 = byte 0 of 8KB bank 11 = 11.
        assertThat("A000 = 8KB bank 11", m.cpuRead(0xA000).toUnsignedInt(), equalTo(11))
        // C000 / E000 windows stay at their defaults.
        assertThat("C000 = 8KB bank 0 still", m.cpuRead(0xC000).toUnsignedInt(), equalTo(0))
        assertThat("E000 = last 8KB bank (15) still", m.cpuRead(0xE000).toUnsignedInt(), equalTo(15))
    }

    @Test
    fun `c000 write sets the 8K prg bank with low 5 bits`() {
        val m = newVrc6a(prgKb = 128)
        m.cpuWrite(0xC000, 0x07.toSignedByte())
        // The 8KB window at $C000 is now bank 7. $C000 = byte 0 of 8KB bank 7 = 7.
        assertThat("C000 = 8KB bank 7", m.cpuRead(0xC000).toUnsignedInt(), equalTo(7))
        // 8000 window unchanged.
        assertThat("8000 = 8KB bank 0 still", m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `prg bank numbers larger than the bank count wrap modulo`() {
        // 128KB = 16 × 8KB. 0x42 & 0x0F = 0x02 (only low 4 bits used for $8000).
        // The 16K window is now bank 2 (8KB banks 4+5). $8000 = 8KB bank 4.
        val m = newVrc6a(prgKb = 128)
        m.cpuWrite(0x8000, 0x42.toSignedByte())
        assertThat("8000 = 8KB bank 4 (low 4 bits of write = 2 → 16K bank 2 → 8KB bank 4)",
            m.cpuRead(0x8000).toUnsignedInt(), equalTo(4))
    }

    // ---- CHR banking ----

    @Test
    fun `defaults to chr bank 0 in all 8 1KB windows`() {
        val m = newVrc6a(prgKb = 128, chrKb = 8)
        for (i in 0 until 8) {
            assertThat("CH bank $i default", m.ppuRead(i * 0x0400).toUnsignedInt(), equalTo(0))
        }
    }

    @Test
    fun `D000 write sets chr bank R0 in 0000 window`() {
        val m = newVrc6a(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xD000, 0x05.toSignedByte())
        // chrBanks[0] = 5 → ppuRead(0) = chrRom[5 * 0x400] = page 5 stamp.
        assertThat("ppu $0000 = 1KB page 5", m.ppuRead(0x0000).toUnsignedInt(), equalTo(5))
        // Other 1KB windows unchanged.
        assertThat("ppu $0400 = 1KB page 0 still", m.ppuRead(0x0400).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `D001-D003 writes set chr banks R1-R3 in 0400-0FFF`() {
        val m = newVrc6a(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xD001, 0x01.toSignedByte())
        m.cpuWrite(0xD002, 0x02.toSignedByte())
        m.cpuWrite(0xD003, 0x03.toSignedByte())
        assertThat("0400 = R1 (page 1)", m.ppuRead(0x0400).toUnsignedInt(), equalTo(1))
        assertThat("0800 = R2 (page 2)", m.ppuRead(0x0800).toUnsignedInt(), equalTo(2))
        assertThat("0C00 = R3 (page 3)", m.ppuRead(0x0C00).toUnsignedInt(), equalTo(3))
    }

    @Test
    fun `E000-E003 writes set chr banks R4-R7 in 1000-1FFF`() {
        val m = newVrc6a(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xE000, 0x04.toSignedByte())
        m.cpuWrite(0xE001, 0x05.toSignedByte())
        m.cpuWrite(0xE002, 0x06.toSignedByte())
        m.cpuWrite(0xE003, 0x07.toSignedByte())
        assertThat("1000 = R4 (page 4)", m.ppuRead(0x1000).toUnsignedInt(), equalTo(4))
        assertThat("1400 = R5 (page 5)", m.ppuRead(0x1400).toUnsignedInt(), equalTo(5))
        assertThat("1800 = R6 (page 6)", m.ppuRead(0x1800).toUnsignedInt(), equalTo(6))
        assertThat("1C00 = R7 (page 7)", m.ppuRead(0x1C00).toUnsignedInt(), equalTo(7))
    }

    // ---- Mirroring ----

    @Test
    fun `mirroring defaults to vertical (b003 MM=00) when B003 has not been written`() {
        // The VRC6 chip explicitly drives mirroring from $B003 bits 2-3. With
        // b003=0 (default), MM=00 → VERTICAL regardless of the iNES header.
        val m = newVrc6a(prgKb = 128, chrKb = 8, verticalMirroring = false)
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    @Test
    fun `B003 bits 2-3 select mirroring mode`() {
        val m = newVrc6a(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xB003, 0x00.toSignedByte())
        assertThat("MM=00 → VERTICAL", m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        m.cpuWrite(0xB003, 0x04.toSignedByte())
        assertThat("MM=01 → HORIZONTAL", m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
        m.cpuWrite(0xB003, 0x08.toSignedByte())
        assertThat("MM=10 → ONE_SCREEN_LOWER", m.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
        m.cpuWrite(0xB003, 0x0C.toSignedByte())
        assertThat("MM=11 → ONE_SCREEN_UPPER", m.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
    }

    // ---- WRAM ($6000-$7FFF) ----

    @Test
    fun `6000-7FFF is open-bus when WRAM is disabled`() {
        // B003 bit 7 = 0 → wramEnabled = false → reads return dataBus.
        val m = newVrc6a(prgKb = 128, chrKb = 8)
        m.dataBus = 0xAB.toSignedByte()
        m.cpuWrite(0xB003, 0x00.toSignedByte())   // W=0
        assertThat("read at $6000 with WRAM disabled", m.cpuRead(0x6000).toUnsignedInt(), equalTo(0xAB))
    }

    @Test
    fun `6000-7FFF is read-write when WRAM is enabled`() {
        val m = newVrc6a(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xB003, 0x80.toSignedByte())   // W=1
        m.cpuWrite(0x6000, 0x42.toSignedByte())
        m.cpuWrite(0x7FFF, 0x99.toSignedByte())
        assertThat("read $6000", m.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
        assertThat("read $7FFF", m.cpuRead(0x7FFF).toUnsignedInt(), equalTo(0x99))
    }

    // ---- IRQ ----

    @Test
    fun `irq is not pending after power-on`() {
        val m = newVrc6a(prgKb = 128, chrKb = 8)
        assertThat(m.isIrqPending(), equalTo(false))
    }

    @Test
    fun `irq fires when counter wraps from FF to latch`() {
        val m = newVrc6a(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xF000, 0x00.toSignedByte())
        m.cpuWrite(0xF001, 0x07.toSignedByte())   // A=1, E=1, M=1
        // Latch 0x00, counter starts at 0. After 256 cycles, counter wraps
        // from 0xFF to 0x00 and an IRQ fires.
        repeat(256) { m.tickCpuCycle() }
        assertThat("IRQ pending after 256 cycles", m.isIrqPending(), equalTo(true))
    }

    @Test
    fun `irq does not fire when disabled`() {
        val m = newVrc6a(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xF000, 0x00.toSignedByte())
        m.cpuWrite(0xF001, 0x00.toSignedByte())   // A=0, E=0, M=0
        repeat(1_000) { m.tickCpuCycle() }
        assertThat("IRQ never fires when E=0", m.isIrqPending(), equalTo(false))
    }

    @Test
    fun `irq acknowledge at F002 clears pending and re-enables from A`() {
        val m = newVrc6a(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xF000, 0x00.toSignedByte())
        m.cpuWrite(0xF001, 0x07.toSignedByte())   // E=1, A=1, M=1
        repeat(256) { m.tickCpuCycle() }
        assertThat("IRQ pending pre-ack", m.isIrqPending(), equalTo(true))
        m.cpuWrite(0xF002, 0x00.toSignedByte())   // ack
        assertThat("IRQ cleared after \$F002", m.isIrqPending(), equalTo(false))
    }

    // ---- Snapshot ----

    @Test
    fun `snapshot reports prg and chr banks and mapper id`() {
        val m = newVrc6a(prgKb = 128, chrKb = 8)
        m.cpuWrite(0x8000, 0x03.toSignedByte())
        m.cpuWrite(0xC000, 0x05.toSignedByte())
        m.cpuWrite(0xD002, 0x07.toSignedByte())
        m.cpuWrite(0xE001, 0x09.toSignedByte())   // 9 wraps to page 1 in 8KB chr
        val snap = m.snapshot()
        assertThat("mapper id 24", snap.mapperId, equalTo(24))
        assertThat("prg16 reported", snap.banks["prg16"], equalTo(3))
        assertThat("prg8 reported", snap.banks["prg8"], equalTo(5))
        assertThat("chr0 = R0", snap.banks["chr0"], equalTo(0))
        assertThat("chr2 = R2", snap.banks["chr2"], equalTo(7))
        // 9 mod 8 = 1
        // snapshot reports the raw register value, not the wrapped page index
        assertThat("chr5 = R5 (= 9)", snap.banks["chr5"], equalTo(9))
    }

    // ---- Save / load ----

    @Test
    fun `saveState then loadState round-trips all banks and mirroring`() {
        val m = newVrc6a(prgKb = 128, chrKb = 8, verticalMirroring = false)
        m.cpuWrite(0x8000, 0x03.toSignedByte())
        m.cpuWrite(0xC000, 0x05.toSignedByte())
        m.cpuWrite(0xD000, 0x01.toSignedByte())
        m.cpuWrite(0xD001, 0x02.toSignedByte())
        m.cpuWrite(0xD002, 0x03.toSignedByte())
        m.cpuWrite(0xD003, 0x04.toSignedByte())
        m.cpuWrite(0xE000, 0x05.toSignedByte())
        m.cpuWrite(0xE001, 0x06.toSignedByte())
        m.cpuWrite(0xE002, 0x07.toSignedByte())
        m.cpuWrite(0xE003, 0x00.toSignedByte())
        m.cpuWrite(0xB003, 0x04.toSignedByte())   // MM=01 (horiz)

        val bytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { m.saveState(it) }
            baos.toByteArray()
        }
        val fresh = newVrc6a(prgKb = 128, chrKb = 8, verticalMirroring = false)
        DataInputStream(ByteArrayInputStream(bytes)).use { fresh.loadState(it) }

        // PRG — read the start of each 8KB chunk.
        // $8000 = first 8KB of 16K bank 3 (= 8KB bank 6).
        assertThat("8000 = 8KB bank 6", fresh.cpuRead(0x8000).toUnsignedInt(), equalTo(6))
        // $C000 = 8KB bank 5.
        assertThat("C000 = 8KB bank 5", fresh.cpuRead(0xC000).toUnsignedInt(), equalTo(5))
        // CHR
        assertThat("CHR 0000 = page 1 (R0=1)", fresh.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
        assertThat("CHR 0400 = page 2 (R1=2)", fresh.ppuRead(0x0400).toUnsignedInt(), equalTo(2))
        assertThat("CHR 1C00 = page 0 (R7=0)", fresh.ppuRead(0x1C00).toUnsignedInt(), equalTo(0))
        // Mirroring
        assertThat("mirroring round-trip", fresh.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    // ---- VRC6b address-pin swap ----

    @Test
    fun `vrc6b sub-register decode swaps bits 0 and 1`() {
        // For VRC6a: $x000 → sub 0, $x001 → sub 1, $x002 → sub 2, $x003 → sub 3.
        // For VRC6b: $x000 → sub 0, $x001 → sub 2, $x002 → sub 1, $x003 → sub 3.
        //
        // We verify this by writing 0xAB to $D001 and reading the byte back at
        // both $0400 (R1's window) and $0800 (R2's window). For VRC6b, $D001
        // should route to sub-register 2 → $0800 should see chrBanks[2] = 0xAB
        // reflected through the chrRom, while $0400 should still see R1=0.
        val m = newVrc6b(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xD001, 0xAB.toSignedByte())
        // chrBanks[2] = 0xAB. ppuRead(0x0800) = chrRom[(0xAB * 0x400) % 0x2000]
        // = chrRom[0xC00] (0xAB mod 8 = 3, byte 0 of page 3 = stamp 3).
        assertThat("\$D001 → R2 on VRC6b (reads page 3 stamp at \$0800)",
            m.ppuRead(0x0800).toUnsignedInt(), equalTo(3))
        // R1 (chrBanks[1]) untouched, default 0.
        assertThat("\$D001 does NOT touch R1 on VRC6b", m.ppuRead(0x0400).toUnsignedInt(), equalTo(0))

        // And $D002 should route to sub-register 1 (not 2), so CHR R1 ($0400)
        // gets the value.
        m.cpuWrite(0xD002, 0xCD.toSignedByte())
        // chrBanks[1] = 0xCD. ppuRead(0x0400) = chrRom[(0xCD * 0x400) % 0x2000]
        // = chrRom[0x400] (0xCD mod 8 = 5) = page 5 stamp = 5.
        assertThat("\$D002 → R1 on VRC6b (reads page 5 stamp at \$0400)",
            m.ppuRead(0x0400).toUnsignedInt(), equalTo(5))
    }

    // ---- Expansion audio channels exposed ----

    @Test
    fun `expansion audio channels are 3 entries in pulse1-pulse2-saw order`() {
        val m = newVrc6a(prgKb = 128, chrKb = 8)
        val channels = m.expansionAudioChannels()
        assertThat("3 expansion channels", channels.size, equalTo(3))
        assertThat("first is Vrc6Pulse", channels[0] is Vrc6Pulse, equalTo(true))
        assertThat("second is Vrc6Pulse", channels[1] is Vrc6Pulse, equalTo(true))
        assertThat("third is Vrc6Saw", channels[2] is Vrc6Saw, equalTo(true))
    }

    // ---- helpers ----

    private fun newVrc6a(
        prgKb: Int = 128,
        chrKb: Int = 8,
        verticalMirroring: Boolean = false
    ): Mapper24 {
        val gp = testGamePak {
            mapper = 24
            this.prgKb = prgKb
            this.chrKb = chrKb
            this.verticalMirroring = verticalMirroring
            stampPrgBanks(windowKb = 8)
            stampChrBanks(windowKb = 1)
        }
        return gp.createMapper() as Mapper24
    }

    private fun newVrc6b(
        prgKb: Int = 128,
        chrKb: Int = 8
    ): Mapper26 {
        val gp = testGamePak {
            mapper = 26
            this.prgKb = prgKb
            this.chrKb = chrKb
            stampPrgBanks(windowKb = 8)
            stampChrBanks(windowKb = 1)
        }
        return gp.createMapper() as Mapper26
    }
}
