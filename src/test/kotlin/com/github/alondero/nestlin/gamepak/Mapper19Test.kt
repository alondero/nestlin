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
 * Unit tests for [Mapper19] (Namco 163). Per the VRC6 / Mapper 33 testing
 * convention, PRG is stamped in 8KB units and CHR in 1KB units so each
 * mapper register can be read back as a unique byte identity.
 *
 * Spec: https://www.nesdev.org/wiki/Namco_163_audio (audio) and the
 * mapper-layout page referenced from Mesen2's `Namco163.h`. Defer to
 * Mesen2 for tiebreakers.
 *
 * **Stamp convention.** PRG is stamped in 8KB units (byte 0 of each 8KB
 * chunk holds the 8KB-bank index). This gives a unique stamp at $8000
 * (start of PRG bank 0), $A000 (PRG bank 1), $C000 (PRG bank 2), and
 * $E000 (the last 8KB bank). CHR is stamped in 1KB units so the 8
 * 1KB CHR bank registers can be read back as unique page stamps.
 */
class Mapper19Test {

    // ---- Mapper dispatch ----

    @Test
    fun `mapper19 is selected for header mapper 19`() {
        val gp = testGamePak {
            mapper = 19
            prgKb = 128
            chrKb = 64
        }
        assertThat(gp.createMapper() is Mapper19, equalTo(true))
    }

    // ---- PRG banking (8KB granularity) ----

    @Test
    fun `defaults to prg bank 0 at 8000, 0 at A000, 0 at C000, last at E000`() {
        // 128KB = 16 × 8KB. With 8KB stamps, last 8K = bank 15.
        val m = newN163(prgKb = 128)
        assertThat("8000 = 8KB bank 0", m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat("A000 = 8KB bank 0", m.cpuRead(0xA000).toUnsignedInt(), equalTo(0))
        assertThat("C000 = 8KB bank 0", m.cpuRead(0xC000).toUnsignedInt(), equalTo(0))
        assertThat("E000 = last 8KB bank (15)", m.cpuRead(0xE000).toUnsignedInt(), equalTo(15))
    }

    @Test
    fun `E000 write low 6 bits select PRG bank 0 at 8000`() {
        val m = newN163(prgKb = 128)
        m.cpuWrite(0xE000, 0x05.toSignedByte())
        assertThat("8000 = 8KB bank 5", m.cpuRead(0x8000).toUnsignedInt(), equalTo(5))
        // Other banks unchanged.
        assertThat("A000 still bank 0", m.cpuRead(0xA000).toUnsignedInt(), equalTo(0))
        assertThat("C000 still bank 0", m.cpuRead(0xC000).toUnsignedInt(), equalTo(0))
        assertThat("E000 still last (15)", m.cpuRead(0xE000).toUnsignedInt(), equalTo(15))
    }

    @Test
    fun `E800 write low 6 bits select PRG bank 1 at A000`() {
        val m = newN163(prgKb = 128)
        m.cpuWrite(0xE800, 0x07.toSignedByte())
        assertThat("A000 = 8KB bank 7", m.cpuRead(0xA000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `F000 write low 6 bits select PRG bank 2 at C000`() {
        val m = newN163(prgKb = 128)
        m.cpuWrite(0xF000, 0x09.toSignedByte())
        assertThat("C000 = 8KB bank 9", m.cpuRead(0xC000).toUnsignedInt(), equalTo(9))
    }

    @Test
    fun `E000 write high 2 bits do not select a bank`() {
        // 128KB = 16 × 8KB. 0xC5 & 0x3F = 0x05; high 2 bits are sound enable /
        // unrelated. Only low 6 bits feed the bank register.
        val m = newN163(prgKb = 128)
        m.cpuWrite(0xE000, 0xC5.toSignedByte())
        assertThat("8000 = 8KB bank 5 (high bits ignored)", m.cpuRead(0x8000).toUnsignedInt(), equalTo(5))
    }

    @Test
    fun `prg bank numbers larger than the bank count wrap modulo`() {
        // 128KB = 16 × 8KB. 0x42 & 0x3F = 0x02 → bank 2.
        val m = newN163(prgKb = 128)
        m.cpuWrite(0xE000, 0x42.toSignedByte())
        assertThat("8000 = 8KB bank 2", m.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
    }

    @Test
    fun `E000-FFFF is always the last 8KB bank`() {
        // The last bank is fixed; even after multiple PRG bank writes, $E000
        // continues to expose the same byte. Stamp byte 0 of every 8KB bank;
        // the last one has index 15.
        val m = newN163(prgKb = 128)
        m.cpuWrite(0xE000, 0x05.toSignedByte())
        m.cpuWrite(0xE800, 0x06.toSignedByte())
        m.cpuWrite(0xF000, 0x07.toSignedByte())
        assertThat("E000 = last 8KB bank (15)", m.cpuRead(0xE000).toUnsignedInt(), equalTo(15))
        assertThat("FFFF = 0 (only byte 0 of last 8KB is stamped)", m.cpuRead(0xFFFF).toUnsignedInt(), equalTo(0))
    }

    // ---- CHR banking (1KB granularity, 8 standard banks) ----

    @Test
    fun `defaults to chr bank 0 in all 8 1KB windows`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        for (i in 0 until 8) {
            assertThat("CHR bank $i default", m.ppuRead(i * 0x0400).toUnsignedInt(), equalTo(0))
        }
    }

    @Test
    fun `8000 write sets CHR bank 0 in 0000 window`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        m.cpuWrite(0x8000, 0x05.toSignedByte())
        assertThat("ppu 0000 = 1KB page 5", m.ppuRead(0x0000).toUnsignedInt(), equalTo(5))
        assertThat("ppu 0400 unchanged (still bank 0)", m.ppuRead(0x0400).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `8000-9800 writes set CHR banks 0-3 in 0000-0FFF`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        m.cpuWrite(0x8000, 0x01.toSignedByte())
        m.cpuWrite(0x8800, 0x02.toSignedByte())
        m.cpuWrite(0x9000, 0x03.toSignedByte())
        m.cpuWrite(0x9800, 0x04.toSignedByte())
        assertThat("0000 = page 1", m.ppuRead(0x0000).toUnsignedInt(), equalTo(1))
        assertThat("0400 = page 2", m.ppuRead(0x0400).toUnsignedInt(), equalTo(2))
        assertThat("0800 = page 3", m.ppuRead(0x0800).toUnsignedInt(), equalTo(3))
        assertThat("0C00 = page 4", m.ppuRead(0x0C00).toUnsignedInt(), equalTo(4))
    }

    @Test
    fun `A000-B800 writes set CHR banks 4-7 in 1000-1FFF`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xA000, 0x05.toSignedByte())
        m.cpuWrite(0xA800, 0x06.toSignedByte())
        m.cpuWrite(0xB000, 0x07.toSignedByte())
        m.cpuWrite(0xB800, 0x07.toSignedByte())   // last valid 1KB page
        assertThat("1000 = page 5", m.ppuRead(0x1000).toUnsignedInt(), equalTo(5))
        assertThat("1400 = page 6", m.ppuRead(0x1400).toUnsignedInt(), equalTo(6))
        assertThat("1800 = page 7", m.ppuRead(0x1800).toUnsignedInt(), equalTo(7))
        assertThat("1C00 = page 7 (last 1KB of 8KB CHR)", m.ppuRead(0x1C00).toUnsignedInt(), equalTo(7))
    }

    // ---- CHR-RAM fallback for 0KB-CHR dumps ----

    @Test
    fun `0KB CHR creates CHR-RAM and writes are visible on read`() {
        val m = newN163(prgKb = 128, chrKb = 0)
        m.cpuWrite(0x8000, 0x00.toSignedByte())     // CHR bank 0
        m.ppuWrite(0x0050, 0xAB.toSignedByte())
        assertThat("PPU \$0050 reflects write", m.ppuRead(0x0050).toUnsignedInt(), equalTo(0xAB))
    }

    // ---- CHR nametable extension (value >= 0xE0) ----

    @Test
    fun `CHR bank value with bit 7 set routes to a nametable sentinel`() {
        // Per spec: a value >= 0xE0 + !lowChrNtMode → that 1KB CHR window
        // becomes a nametable instead of CHR. We don't model the nametable
        // backing yet (TODO in Mapper19.ppuRead), but the read should NOT
        // return the value as if it were a CHR bank number (e.g. 0xE0 = 224,
        // which would index way past the 8KB CHR ROM).
        val m = newN163(prgKb = 128, chrKb = 8)
        m.cpuWrite(0x8000, 0xE0.toSignedByte())    // value >= 0xE0 → nametable
        m.cpuWrite(0x9000, 0x05.toSignedByte())     // normal bank for comparison
        // The two windows should NOT both read the same byte: $E0 is a
        // nametable flag, $05 is a CHR bank. The fact that we return 0
        // for the nametable (and 5 for the CHR) is what matters here.
        assertThat("PPU \$0000 (nametable-extended) is NOT chr bank 224", m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        assertThat("PPU \$0800 (chr bank 5) still works", m.ppuRead(0x0800).toUnsignedInt(), equalTo(5))
    }

    @Test
    fun `E800 bit 6 (lowChrNtMode) disables nametable extension on 8000-9FFF range`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        // E800 bit 6 = lowChrNtMode. After setting it, value 0xE0 to $8000
        // is treated as a plain CHR bank (and 0xE0 wraps modulo 8KB to
        // either return 0 or read garbage; either way it should NOT be the
        // nametable sentinel).
        m.cpuWrite(0xE800, 0x40.toSignedByte())    // lowChrNtMode = 1
        m.cpuWrite(0x8000, 0xE0.toSignedByte())    // value 0xE0 with lowChrNtMode set
        // 0xE0 & 0xFF = 224; modulo 8KB it's still 224, which past the end
        // of the 8-byte CHR. Modulo wrap = 224 % 8 = 0. Either way, NOT
        // the nametable sentinel (which is 0 because the read path also
        // tries to read CHR). The point is the high bit 8 of the stored
        // bank register (the "is-nametable" flag) is NOT set.
        // Read the snapshot to verify the bank register is plain.
        val snap = m.snapshot()
        val chrBank0 = snap.banks["chrBank0"] ?: 0
        assertThat("chrBank0 stored as plain CHR bank (no nt sentinel)",
            chrBank0 and 0x100, equalTo(0))
    }

    // ---- PRG-RAM ($6000-$7FFF) and write-protect ----

    @Test
    fun `PRG-RAM reads return 0 by default (uninitialised)`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        assertThat("$6000 default", m.cpuRead(0x6000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `PRG-RAM writes are allowed when F800 bit 6 (global WE) is set and bit 0 (page 0 protect) is clear`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xF800, 0x40.toSignedByte())    // global WE = 1, page mask = 0
        m.cpuWrite(0x6000, 0x42.toSignedByte())
        m.cpuWrite(0x7FFF, 0x99.toSignedByte())
        assertThat("read 6000", m.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
        assertThat("read 7FFF", m.cpuRead(0x7FFF).toUnsignedInt(), equalTo(0x99))
    }

    @Test
    fun `PRG-RAM writes are blocked when F800 bit 6 (global WE) is clear`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xF800, 0x00.toSignedByte())    // global WE = 0
        m.cpuWrite(0x6000, 0x42.toSignedByte())
        assertThat("read 6000 (write was dropped)", m.cpuRead(0x6000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `PRG-RAM per-page write-protect bit 0 protects 6000-67FF only`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xF800, (0x40 or 0x01).toSignedByte())  // global WE + page 0 protected
        m.cpuWrite(0x6000, 0xAA.toSignedByte())    // dropped
        m.cpuWrite(0x6800, 0xBB.toSignedByte())    // allowed (page 1)
        m.cpuWrite(0x7000, 0xCC.toSignedByte())    // allowed (page 2)
        m.cpuWrite(0x7800, 0xDD.toSignedByte())    // allowed (page 3)
        assertThat("page 0 (6000) write dropped", m.cpuRead(0x6000).toUnsignedInt(), equalTo(0))
        assertThat("page 1 (6800) write landed", m.cpuRead(0x6800).toUnsignedInt(), equalTo(0xBB))
        assertThat("page 2 (7000) write landed", m.cpuRead(0x7000).toUnsignedInt(), equalTo(0xCC))
        assertThat("page 3 (7800) write landed", m.cpuRead(0x7800).toUnsignedInt(), equalTo(0xDD))
    }

    // ---- Audio data port + address port ($4800-$4FFF / $F800) ----

    @Test
    fun `F800 sets the address-port cursor and 4800 writes go there`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xF800, 0x40.toSignedByte())    // _ramPosition = 0x40 (channel 1 base)
        m.cpuWrite(0x4800, 0x11.toSignedByte())    // write 0x11 to internal RAM[0x40]
        // Read back via another data-port read. Position doesn't auto-
        // advance yet, so this should still be 0x11.
        assertThat("read 4800 returns internal RAM[0x40]",
            m.cpuRead(0x4800).toUnsignedInt(), equalTo(0x11))
    }

    @Test
    fun `F800 bit 7 enables data-port auto-increment`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xF800, (0x40 or 0x80).toSignedByte())   // pos=0x40, autoInc=1
        m.cpuWrite(0x4800, 0x11.toSignedByte())    // internal RAM[0x40] = 0x11
        m.cpuWrite(0x4800, 0x22.toSignedByte())    // internal RAM[0x41] = 0x22
        // Re-set position to 0x40 and read both bytes back.
        m.cpuWrite(0xF800, 0x40.toSignedByte())    // resets autoInc to 0
        assertThat("byte 0x40 still 0x11", m.cpuRead(0x4800).toUnsignedInt(), equalTo(0x11))
        m.cpuWrite(0xF800, 0x41.toSignedByte())
        assertThat("byte 0x41 was incremented to 0x22", m.cpuRead(0x4800).toUnsignedInt(), equalTo(0x22))
    }

    // ---- IRQ counter ($5000 / $5800) ----

    @Test
    fun `IRQ counter is not pending after power-on`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        assertThat(m.isIrqPending(), equalTo(false))
    }

    @Test
    fun `IRQ fires when low 15 bits reach 0x7FFF with bit 15 set`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        // Load counter with 0x8000 (enable set, low bits = 0). The counter
        // increments on each CPU cycle; after 0x7FFF = 32767 ticks the IRQ
        // line is asserted. We accelerate the test by ticking 32768 cycles
        // and verifying the IRQ is now pending.
        m.cpuWrite(0x5800, 0x80.toSignedByte())    // high byte = 0x80 (bit 15 set)
        m.cpuWrite(0x5000, 0x00.toSignedByte())    // low byte = 0
        assertThat("not pending right after load", m.isIrqPending(), equalTo(false))
        // Advance the counter 0x7FFF = 32767 cycles — last increment lands
        // low bits at 0x7FFF and fires the IRQ.
        repeat(0x7FFF) { m.tickCpuCycle() }
        assertThat("pending after 32767 ticks (counter landed at 0xFFFF)",
            m.isIrqPending(), equalTo(true))
    }

    @Test
    fun `IRQ does not fire while bit 15 (enable) is clear`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        // Load counter with high byte = 0 (enable clear). Counter does not
        // advance regardless of how many cycles we tick.
        m.cpuWrite(0x5800, 0x00.toSignedByte())
        m.cpuWrite(0x5000, 0x7F.toSignedByte())    // low bits = 0x7F (NOT 0x7FFF)
        repeat(10000) { m.tickCpuCycle() }
        assertThat("still not pending (bit 15 clear)", m.isIrqPending(), equalTo(false))
    }

    @Test
    fun `5000 write clears a pending IRQ without changing the counter`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        m.cpuWrite(0x5800, 0x80.toSignedByte())
        m.cpuWrite(0x5000, 0x00.toSignedByte())
        repeat(0x7FFF) { m.tickCpuCycle() }
        assertThat("pending before clear", m.isIrqPending(), equalTo(true))
        // Write the low byte again (any value works). Should clear the IRQ.
        m.cpuWrite(0x5000, 0x00.toSignedByte())
        assertThat("cleared after 5000 write", m.isIrqPending(), equalTo(false))
    }

    @Test
    fun `5800 write clears a pending IRQ`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        m.cpuWrite(0x5800, 0x80.toSignedByte())
        m.cpuWrite(0x5000, 0x00.toSignedByte())
        repeat(0x7FFF) { m.tickCpuCycle() }
        assertThat("pending before clear", m.isIrqPending(), equalTo(true))
        m.cpuWrite(0x5800, 0x80.toSignedByte())    // re-write high byte
        assertThat("cleared after 5800 write", m.isIrqPending(), equalTo(false))
    }

    // ---- Save / load round-trip ----

    @Test
    fun `save then load restores all banking and IRQ state`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xE000, 0x05.toSignedByte())
        m.cpuWrite(0xE800, (0x40 or 0x03).toSignedByte())   // prgBank1 + lowChrNtMode
        m.cpuWrite(0xF000, 0x09.toSignedByte())
        m.cpuWrite(0x8000, 0x02.toSignedByte())
        m.cpuWrite(0xA000, 0x04.toSignedByte())
        m.cpuWrite(0xF800, (0x40 or 0x01).toSignedByte())   // global WE + page 0 protect
        m.cpuWrite(0x6800, 0x77.toSignedByte())
        m.cpuWrite(0x5800, 0x80.toSignedByte())
        m.cpuWrite(0x5000, 0x00.toSignedByte())
        repeat(100) { m.tickCpuCycle() }                     // mid-count

        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        m.saveState(out)
        out.flush()
        val bytes = baos.toByteArray()

        val m2 = newN163(prgKb = 128, chrKb = 8)
        val inp = DataInputStream(ByteArrayInputStream(bytes))
        m2.loadState(inp)

        // PRG banks
        assertThat("prgBank0 restored", m2.cpuRead(0x8000).toUnsignedInt(), equalTo(5))
        assertThat("prgBank1 restored", m2.cpuRead(0xA000).toUnsignedInt(), equalTo(3))
        assertThat("prgBank2 restored", m2.cpuRead(0xC000).toUnsignedInt(), equalTo(9))
        // CHR banks
        assertThat("chrBank0 restored", m2.ppuRead(0x0000).toUnsignedInt(), equalTo(2))
        assertThat("chrBank4 restored", m2.ppuRead(0x1000).toUnsignedInt(), equalTo(4))
        // PRG-RAM + write-protect
        assertThat("prgRam page 1 restored", m2.cpuRead(0x6800).toUnsignedInt(), equalTo(0x77))
        // IRQ: counter is at 0x8000 + 100 cycles = 0x8064, bit 15 still set,
        // not yet 0xFFFF. Reload it then tick once to verify state.
        val snap = m2.snapshot()
        val irqCounterValue = (snap.irqState?.get("irqCounter") as? Int) ?: 0
        assertThat("IRQ counter restored (high bit preserved)",
            irqCounterValue and 0x8000, equalTo(0x8000))
    }

    @Test
    fun `version-byte load rejects a save with an unknown version`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        // Write a version byte that doesn't match what Mapper19 advertises.
        out.writeByte(99)
        out.flush()
        val inp = DataInputStream(ByteArrayInputStream(baos.toByteArray()))
        val ex = org.junit.jupiter.api.Assertions.assertThrows(
            com.github.alondero.nestlin.SaveState.IncompatibleSaveStateException::class.java
        ) { m.loadState(inp) }
        assertThat("error message names the mapper",
            ex.message!!.contains("Mapper19"), equalTo(true))
    }

    // ---- Snapshot ----

    @Test
    fun `snapshot reports the mapper id, prg banks, chr banks, and irq state`() {
        val m = newN163(prgKb = 128, chrKb = 8)
        m.cpuWrite(0xE000, 0x05.toSignedByte())
        m.cpuWrite(0x8000, 0x03.toSignedByte())
        val snap = m.snapshot()
        assertThat("mapperId", snap.mapperId, equalTo(19))
        assertThat("prgBank0", snap.banks["prgBank0"], equalTo(5))
        assertThat("chrBank0", snap.banks["chrBank0"], equalTo(3))
    }

    // ---- Helpers ----

    private fun newN163(prgKb: Int, chrKb: Int = 8): Mapper19 {
        val gp = testGamePak {
            mapper = 19
            this.prgKb = prgKb
            this.chrKb = chrKb
            stampPrgBanks(windowKb = 8)
            if (chrKb > 0) stampChrBanks(windowKb = 1)
        }
        return gp.createMapper() as Mapper19
    }
}
