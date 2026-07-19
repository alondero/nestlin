package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.ppu.PpuInternalMemory
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
    fun `CHR bank value with bit 7 set routes to the extended CHR-RAM nametable`() {
        // Issue #234: a value >= 0xE0 + !lowChrNtMode turns that 1KB CHR window
        // into a nametable mirror backed by the chip's extended 4KB CHR-RAM.
        // Previously this branch logged and returned 0; now the read goes
        // through to the NT slot. With a fresh extendedChrRam, the read still
        // returns 0 — but for the *right* reason (uninitialised RAM) instead
        // of a sentinel placeholder.
        val m = newN163(prgKb = 128, chrKb = 8)
        m.cpuWrite(0x8000, 0xE0.toSignedByte())    // value >= 0xE0 → NT mode
        m.cpuWrite(0x9000, 0x05.toSignedByte())    // normal CHR bank for control
        assertThat("PPU \$0000 (NT-mode) reads from extended CHR-RAM (fresh = 0)",
            m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        assertThat("PPU \$0800 (chr bank 5) still works",
            m.ppuRead(0x0800).toUnsignedInt(), equalTo(5))
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

    // ---- CHR-as-nametable wiring (issue #234) --------------------------------
    //
    // The CHR-as-nametable trick has TWO coupled surfaces that must agree:
    //   (a) PPU reads at $0000-$03FF (CHR bank 0 in NT mode) read from the
    //       extended CHR-RAM "NT A" slot.
    //   (b) PPU reads at $2000-$23FF (when CHR bank 8 is in NT mode with bit 0=0)
    //       read from the SAME extended CHR-RAM "NT A" slot.
    //
    // Both surfaces must observe the same byte at the same offset — that's the
    // whole point of the trick (one window of internal RAM accessible from
    // both a CHR address and a NT address). Without this wiring, the game
    // writes nametable data to one address and reads back through the other
    // and gets 0, so the screen renders wrong.

    @Test
    fun `CHR bank 0 in NT mode bit 0 = 0 reads from extended CHR-RAM NT A slot`() {
        // After issue #234, NT-mode bank 0 (value 0xE0) routes the PPU read
        // at $0000-$03FF to extendedChrRam[0x000-0x3FF]. The bit-0=0 case is
        // NT A, so we offset 0x000 (not 0x400).
        val m = newN163(prgKb = 128, chrKb = 0)
        m.cpuWrite(0x8000, 0xE0.toSignedByte())    // bank 0 → NT mode, bit 0 = 0 (NT A)
        m.ppuWrite(0x0050, 0xAB.toSignedByte())    // writes to NT A offset 0x050
        assertThat("PPU \$0050 reads back 0xAB from NT A slot",
            m.ppuRead(0x0050).toUnsignedInt(), equalTo(0xAB))
    }

    @Test
    fun `CHR bank 0 in NT mode bit 0 = 1 reads from extended CHR-RAM NT B slot`() {
        // NT-mode bank 0 with bit 0=1 routes to NT B at extendedChrRam[0x400-0x7FF].
        // The two slots are distinct — a write to one doesn't bleed into the
        // other (which would defeat the 4-screen arrangement).
        val m = newN163(prgKb = 128, chrKb = 0)
        m.cpuWrite(0x8000, 0xE1.toSignedByte())    // bank 0 → NT mode, bit 0 = 1 (NT B)
        m.ppuWrite(0x0100, 0xCD.toSignedByte())    // writes to NT B offset 0x100
        assertThat("PPU \$0100 reads back 0xCD from NT B slot",
            m.ppuRead(0x0100).toUnsignedInt(), equalTo(0xCD))
        // Also verify NT A is independent — write to NT A offset 0x100 and
        // confirm NT B at the same offset is unaffected.
        m.cpuWrite(0x8000, 0xE0.toSignedByte())    // bank 0 → NT A
        m.ppuWrite(0x0100, 0x11.toSignedByte())    // writes NT A offset 0x100
        assertThat("PPU \$0100 with NT A reads 0x11 (independent from NT B)",
            m.ppuRead(0x0100).toUnsignedInt(), equalTo(0x11))
        m.cpuWrite(0x8000, 0xE1.toSignedByte())    // bank 0 → NT B
        assertThat("PPU \$0100 with NT B still reads 0xCD (unaffected)",
            m.ppuRead(0x0100).toUnsignedInt(), equalTo(0xCD))
    }

    @Test
    fun `CHR bank 8 in NT mode redirects PPU 2000 reads to NT A extended CHR-RAM`() {
        // Banks 8-11 live at the extended CHR/nametable register range
        // ($C000-$D800) and per the wiki "always select CHR banks for PPU
        // $2000-$2FFF". When bank 8 is in NT mode (value 0xE0), PPU reads at
        // $2000-$23FF go to extendedChrRam via readNametableOverride —
        // bypassing the standard PpuInternalMemory CIRAM.
        val m = newN163(prgKb = 128, chrKb = 0)
        m.cpuWrite(0xC000, 0xE0.toSignedByte())    // bank 8 → NT A
        assertThat("readNametableOverride(\$2000) returns the NT slot byte",
            m.readNametableOverride(0x2000)?.toUnsignedInt(), equalTo(0))
        assertThat("readNametableOverride(\$2001) reads from extended CHR-RAM",
            m.readNametableOverride(0x2001)?.toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `writeNametableOverride writes to extended CHR-RAM when bank 8 is in NT mode`() {
        // Mirror of the read path: when bank 8 (PPU $2000-$23FF) is in NT
        // mode, a PPU write at $2000-$23FF lands in extendedChrRam, NOT in
        // PpuInternalMemory's nameTable0. The override returns `true` to
        // signal the write was consumed.
        val m = newN163(prgKb = 128, chrKb = 0)
        m.cpuWrite(0xC000, 0xE0.toSignedByte())    // bank 8 → NT A
        val consumed = m.writeNametableOverride(0x2050, 0xAB.toSignedByte())
        assertThat("writeNametableOverride returns true (consumed) when bank 8 is NT",
            consumed, equalTo(true))
        assertThat("readNametableOverride(\$2050) reads back 0xAB",
            m.readNametableOverride(0x2050)?.toUnsignedInt(), equalTo(0xAB))
    }

    @Test
    fun `writeNametableOverride is no-op when bank 8 is NOT in NT mode`() {
        // When bank 8's value is below 0xE0, the bank is a regular CHR bank
        // (or unused) and the PPU $2000-$23FF path should fall through to
        // PpuInternalMemory's CIRAM. The override returns `false` to signal
        // "I didn't consume this".
        val m = newN163(prgKb = 128, chrKb = 0)
        m.cpuWrite(0xC000, 0x05.toSignedByte())    // bank 8 → plain CHR bank (not NT)
        val consumed = m.writeNametableOverride(0x2050, 0xAB.toSignedByte())
        assertThat("writeNametableOverride returns false (passthrough) when bank 8 is not NT",
            consumed, equalTo(false))
        assertThat("readNametableOverride returns null (passthrough)",
            m.readNametableOverride(0x2050), equalTo(null))
    }

    @Test
    fun `CHR bank 0 in NT mode and CHR bank 8 in NT mode see the same extended CHR-RAM byte`() {
        // The whole point of the CHR-as-NT trick: writing to PPU $0000-$03FF
        // (bank 0 in NT mode) and reading back at PPU $2000-$23FF (bank 8 in
        // NT mode, bit 0=0) must return the same byte, because both windows
        // address the SAME NT A slot in extendedChrRam. Without this, games
        // can't coordinate the "extra screen" between the CHR-side and
        // NT-side writes.
        val m = newN163(prgKb = 128, chrKb = 0)
        m.cpuWrite(0x8000, 0xE0.toSignedByte())    // bank 0 → NT A
        m.cpuWrite(0xC000, 0xE0.toSignedByte())    // bank 8 → NT A
        // Write via the CHR window (bank 0 in NT mode), read via the NT
        // window (bank 8 in NT mode) — must match.
        m.ppuWrite(0x0080, 0x77.toSignedByte())
        assertThat("PPU \$2080 (NT side) reflects the CHR-side write",
            m.readNametableOverride(0x2080)?.toUnsignedInt(), equalTo(0x77))
        // And the inverse: write via the NT window, read via the CHR window.
        m.writeNametableOverride(0x2090, 0x88.toSignedByte())
        assertThat("PPU \$0090 (CHR side) reflects the NT-side write",
            m.ppuRead(0x0090).toUnsignedInt(), equalTo(0x88))
    }

    @Test
    fun `CHR bank 4 in NT mode bit 0 = 0 reads from extended CHR-RAM NT A slot`() {
        // Banks 4-7 are gated by highChrNtMode (bit 7 of $E800), which is
        // independent of lowChrNtMode (bit 6). Default state is highChrNtMode
        // = 0, so banks 4-7 honor NT mode like banks 0-3. Bit 0=0 still
        // selects NT A.
        val m = newN163(prgKb = 128, chrKb = 0)
        m.cpuWrite(0xA000, 0xE0.toSignedByte())    // bank 4 → NT mode, bit 0 = 0 (NT A)
        m.ppuWrite(0x1050, 0xEE.toSignedByte())
        assertThat("PPU \$1050 (bank 4 NT mode) reads back 0xEE from NT A slot",
            m.ppuRead(0x1050).toUnsignedInt(), equalTo(0xEE))
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

    @Test
    fun `extended CHR-RAM survives save and load round-trip`() {
        // Issue #234: extendedChrRam backs the CHR-as-nametable mode and
        // must round-trip through save state. Without this, restoring a save
        // taken mid-game would zero the 4KB of NT-extension data and the
        // screen would render wrong until the game re-wrote it (which games
        // typically don't do between save and load).
        val m = newN163(prgKb = 128, chrKb = 0)
        m.cpuWrite(0x8000, 0xE0.toSignedByte())    // bank 0 → NT A
        m.cpuWrite(0xA000, 0xE1.toSignedByte())    // bank 4 → NT B
        m.cpuWrite(0xC000, 0xE0.toSignedByte())    // bank 8 → NT A
        m.cpuWrite(0xC800, 0xE1.toSignedByte())    // bank 9 → NT B
        // Write a few distinct bytes across both NT slots.
        m.ppuWrite(0x0005, 0x11.toSignedByte())    // NT A offset 5 (via bank 0)
        m.ppuWrite(0x03FF, 0x22.toSignedByte())    // NT A offset 0x3FF (via bank 0)
        m.writeNametableOverride(0x240A, 0x33.toSignedByte())    // NT B offset 0xA (via bank 9)

        val baos = ByteArrayOutputStream()
        val out = DataOutputStream(baos)
        m.saveState(out)
        out.flush()
        val bytes = baos.toByteArray()

        val m2 = newN163(prgKb = 128, chrKb = 0)
        val inp = DataInputStream(ByteArrayInputStream(bytes))
        m2.loadState(inp)

        // NT A bytes (bank 0 still in NT mode bit 0=0)
        assertThat("NT A offset 5 restored",
            m2.ppuRead(0x0005).toUnsignedInt(), equalTo(0x11))
        assertThat("NT A offset 0x3FF restored",
            m2.ppuRead(0x03FF).toUnsignedInt(), equalTo(0x22))
        // NT B byte (bank 9 still in NT mode bit 0=1 — bank 9 owns the
        // $2400-$27FF PPU window that readNametableOverride dispatches on)
        assertThat("NT B offset 0xA restored (via readNametableOverride)",
            m2.readNametableOverride(0x240A)?.toUnsignedInt(), equalTo(0x33))
        // Bytes we DIDN'T write are still 0
        assertThat("NT A offset 0 untouched",
            m2.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
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

    // ---- End-to-end wiring through PpuInternalMemory (issue #234) -----------
    //
    // These tests replicate the delegate wiring that `Memory.readCartridge`
    // installs (`ppuInternalMemory.nametableReadDelegate = ...` +
    // `nametableWriteDelegate = ...`). Going through `PpuInternalMemory` (not
    // calling Mapper19 directly) catches bugs that a direct-call test would
    // miss — e.g. an off-by-one in `mapNametableAddress`, a typo in the
    // delegate lambda, or the fallback path incorrectly skipping the override.
    //
    // The "usage trace" framing: a game running N163's CHR-as-NT mode reads
    // and writes nametables through PPU $2000-$2FFF during normal operation
    // (CPU $2007 PPUDATA + PPU rendering-time $2000-$2FFF fetches). After
    // issue #234 those accesses route through Mapper19's extended CHR-RAM,
    // not PpuInternalMemory's CIRAM, and the test below proves that.

    @Test
    fun `end-to-end - PPU 2000 reads hit the mapper's extended CHR-RAM when bank 8 is NT`() {
        // Wiring: same pattern Memory.kt:readCartridge uses. We don't go
        // through Memory here because that pulls in the full CPU + APU +
        // PPU stack — overkill for a delegate round-trip test.
        val mapper = newN163(prgKb = 128, chrKb = 0)
        val ppuMem = PpuInternalMemory()
        ppuMem.nametableReadDelegate = { addr -> mapper.readNametableOverride(addr) }
        ppuMem.nametableWriteDelegate = { addr, v -> mapper.writeNametableOverride(addr, v) }
        mapper.cpuWrite(0xC000, 0xE0.toSignedByte())   // bank 8 → NT A

        // CPU-side path: PPUDATA write via $2007. PpuInternalMemory.set is
        // called for the vramAddress'd PPU address — write to $2000 lands
        // there because vRamAddress is 0 by default.
        ppuMem[0x2000] = 0x42.toSignedByte()
        // Re-read goes through the delegate → mapper.readNametableOverride →
        // extendedChrRam[0x000], NOT the standard CIRAM. Verify both paths
        // agree on the source so a regression that bypassed the override
        // would surface as "CIRAM and override return different bytes".
        assertThat("PPU \$2000 read goes through the override (round-trip)",
            ppuMem[0x2000].toUnsignedInt(), equalTo(0x42))
        assertThat("mapper.readNametableOverride(\$2000) returns 0x42 (owning the byte)",
            mapper.readNametableOverride(0x2000)?.toUnsignedInt(), equalTo(0x42))
    }

    @Test
    fun `end-to-end - PPU 2000 reads fall through to CIRAM when bank 8 is NOT NT`() {
        // Negative case: with bank 8 in normal (non-NT) mode, the delegate
        // returns null/false and the standard CIRAM path runs. Without this
        // we couldn't distinguish a wired-up mapper from one that always
        // returns a value.
        val mapper = newN163(prgKb = 128, chrKb = 0)
        val ppuMem = PpuInternalMemory()
        ppuMem.nametableReadDelegate = { addr -> mapper.readNametableOverride(addr) }
        ppuMem.nametableWriteDelegate = { addr, v -> mapper.writeNametableOverride(addr, v) }
        mapper.cpuWrite(0xC000, 0x05.toSignedByte())   // bank 8 → plain CHR bank (not NT)

        // With no override, writes go to nameTable0 (via mapNametableAddress).
        ppuMem[0x2000] = 0x42.toSignedByte()
        // Re-read through the delegate path: should still be 0x42 (came
        // from CIRAM, not extended CHR-RAM, so the mapper.readNametableOverride
        // returns null and we see the same byte via fall-through).
        assertThat("ppu \$2000 reads back 0x42 from CIRAM (no override)",
            ppuMem[0x2000].toUnsignedInt(), equalTo(0x42))
        // And confirm Mapper19's extended CHR-RAM was untouched (still 0).
        assertThat("mapper's readNametableOverride(\$2000) is null (no claim)",
            mapper.readNametableOverride(0x2000), equalTo(null))
    }

    @Test
    fun `end-to-end - CHR and NT sides share extended CHR-RAM`() {
        // The "4-screen" arrangement: writes at PPU $0000-$03FF (CHR side,
        // bank 0 in NT mode) and reads at PPU $2000-$23FF (NT side, bank 8
        // in NT mode with bit 0=0) must agree, because both windows address
        // the same NT A slot. This is the headline test for issue #234.
        val mapper = newN163(prgKb = 128, chrKb = 0)
        val ppuMem = PpuInternalMemory()
        ppuMem.nametableReadDelegate = { addr -> mapper.readNametableOverride(addr) }
        ppuMem.nametableWriteDelegate = { addr, v -> mapper.writeNametableOverride(addr, v) }
        mapper.cpuWrite(0x8000, 0xE0.toSignedByte())   // bank 0 → NT A
        mapper.cpuWrite(0xC000, 0xE0.toSignedByte())   // bank 8 → NT A

        // Write via PPU $2000-$23FF (NT side, the typical PPU rendering fetch).
        ppuMem[0x2010] = 0xAB.toSignedByte()
        // Read via PPU $0000-$03FF (CHR side, same backing slot).
        assertThat("CHR side reads 0xAB written through NT side",
            mapper.ppuRead(0x0010).toUnsignedInt(), equalTo(0xAB))

        // Inverse: write via PPU $0000-$03FF (CHR side), read via $2000-$23FF.
        mapper.ppuWrite(0x0020, 0xCD.toSignedByte())
        assertThat("NT side reads 0xCD written through CHR side",
            ppuMem[0x2020].toUnsignedInt(), equalTo(0xCD))
    }

    @Test
    fun `end-to-end - bit 0 selects NT A vs NT B (independent slots)`() {
        // Banks 8 and 9 can simultaneously point to NT A and NT B (or both
        // to NT A, or both to NT B). The two NT slots are independent —
        // writes to one don't bleed into the other. This is what makes the
        // 4-screen arrangement actually give 4 distinct screens of RAM.
        val mapper = newN163(prgKb = 128, chrKb = 0)
        val ppuMem = PpuInternalMemory()
        ppuMem.nametableReadDelegate = { addr -> mapper.readNametableOverride(addr) }
        ppuMem.nametableWriteDelegate = { addr, v -> mapper.writeNametableOverride(addr, v) }
        mapper.cpuWrite(0xC000, 0xE0.toSignedByte())   // bank 8 ($2000) → NT A
        mapper.cpuWrite(0xC800, 0xE1.toSignedByte())   // bank 9 ($2400) → NT B

        ppuMem[0x2010] = 0x11.toSignedByte()    // writes to NT A
        ppuMem[0x2410] = 0x22.toSignedByte()    // writes to NT B
        assertThat("NT A reads 0x11 (independent of NT B)",
            ppuMem[0x2010].toUnsignedInt(), equalTo(0x11))
        assertThat("NT B reads 0x22 (independent of NT A)",
            ppuMem[0x2410].toUnsignedInt(), equalTo(0x22))
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
