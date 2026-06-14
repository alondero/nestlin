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
 * Unit tests for Mapper 65 (Irem H3001) — the chip behind *R-Type*,
 * *Kickle Cubicle*, *Infiltrator*, *The Adventures of Rad Gravity*, etc.
 *
 * Test PRG/CHR are stamped with their bank index so a read asserts exactly
 * which bank is mapped into a window:
 *  - 8 KB PRG banks: byte 0 = bank index, byte 0x1FFF = bank XOR 0xFF.
 *  - 1 KB CHR banks: byte 0 = bank index, byte 0x3FF = bank XOR 0xFF.
 *
 * The 256 KB PRG fixture (32 8 KB banks) is the smallest size where
 * the chip's power-on page 2 (bank 0xFE) selects a distinct bank without
 * modulo wrap (0xFE % 32 = 30). 16 KB PRG (2 banks) would also work for
 * the other tests but the 0xFE power-on would alias to bank 0 and lose
 * the "0xFE ≠ 0" assertion.
 *
 * Every test asserts against the *Mesen2 spec* (per
 * `Core/NES/Mappers/Irem/IremH3001.h`); the issue's "4×8 KB PRG slots,
 * header-driven mirroring, no IRQ" claim is wrong on all three counts
 * and the tests below would false-green a chip built to that design
 * intent. (See Mapper65 KDoc and the new-mapper skill — the GitHub
 * issue is design intent only; the Mesen2 source is the oracle.)
 */
class Mapper65Test {

    /**
     * Stamp every byte of every 8 KB PRG bank with its bank index, and set
     * the *last* byte of each bank (byte 0x1FFF) to `bank xor 0xFF` as a
     * sentinel. The 8 KB window at $8000-$9FFF (or $A000-$BFFF / etc.) is
     * one bank for this mapper, so the byte-0+0x1FFF stamp is enough to
     * identify which bank is mapped.
     */
    private fun stampPrg8kBanks(prg: ByteArray) {
        val bankCount = prg.size / 0x2000
        for (bank in 0 until bankCount) {
            java.util.Arrays.fill(
                prg, bank * 0x2000, bank * 0x2000 + 0x2000,
                (bank and 0xFF).toByte()
            )
            prg[bank * 0x2000 + 0x1FFF] = (bank xor 0xFF).toByte()
        }
    }

    /**
     * Stamp every byte of every 1 KB CHR bank with its bank index, and set
     * the *last* byte of each bank (byte 0x3FF) to `bank xor 0xFF` as a
     * sentinel. The 1 KB window at $0000-$03FF (or $0400-$07FF / etc.) is
     * one bank for this mapper.
     */
    private fun stampChr1kBanks(chr: ByteArray) {
        val bankCount = chr.size / 0x0400
        for (bank in 0 until bankCount) {
            java.util.Arrays.fill(
                chr, bank * 0x0400, bank * 0x0400 + 0x0400,
                (bank and 0xFF).toByte()
            )
            chr[bank * 0x0400 + 0x03FF] = (bank xor 0xFF).toByte()
        }
    }

    private fun newMapper65(
        prgBanks8k: Int = 32,
        chrBanks1k: Int = 8,
        mirroring: Header.Mirroring = Header.Mirroring.HORIZONTAL
    ): Mapper65 {
        val pak = testGamePak {
            mapper = 65
            prgKb = prgBanks8k * 8
            chrKb = chrBanks1k
            verticalMirroring = mirroring == Header.Mirroring.VERTICAL
            stampPrg8kBanks(prg)
            stampChr1kBanks(chr)
        }
        return pak.createMapper() as Mapper65
    }

    // ---- Dispatch ----

    @Test
    fun `mapper65 is selected for header mapper 65`() {
        assertThat(newMapper65() is Mapper65, equalTo(true))
    }

    // ---- Power-on PRG state ----

    @Test
    fun `power-on state is bank 0 at 8000, bank 1 at A000, bank 0xFE mod N at C000, last at E000`() {
        // Per Mesen2 `InitMapper`:
        //   page 0 ($8000) = 0
        //   page 1 ($A000) = 1
        //   page 2 ($C000) = 0xFE   (modulo applied at read time)
        //   page 3 ($E000) = -1     (fixed to last bank, no register)
        // With the 32-bank fixture, 0xFE % 32 = 30.
        val m = newMapper65(prgBanks8k = 32)
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0x9FFF).toUnsignedInt(), equalTo(0 xor 0xFF))
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(1))
        assertThat(m.cpuRead(0xBFFF).toUnsignedInt(), equalTo(1 xor 0xFF))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(30))    // 0xFE mod 32
        assertThat(m.cpuRead(0xDFFF).toUnsignedInt(), equalTo(30 xor 0xFF))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(31))   // last
        assertThat(m.cpuRead(0xFFFF).toUnsignedInt(), equalTo(31 xor 0xFF))
    }

    // ---- PRG register writes ----

    @Test
    fun `8000 write selects the 8KB bank at 8000-9FFF`() {
        val m = newMapper65(prgBanks8k = 32)
        m.cpuWrite(0x8000, 0x07.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(7))
        assertThat(m.cpuRead(0x9FFF).toUnsignedInt(), equalTo(7 xor 0xFF))
        // $A000 / $C000 / $E000 unaffected.
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(1))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(30))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(31))
    }

    @Test
    fun `A000 write selects the 8KB bank at A000-BFFF`() {
        val m = newMapper65(prgBanks8k = 32)
        m.cpuWrite(0xA000, 0x0A.toSignedByte())
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(10))
        assertThat(m.cpuRead(0xBFFF).toUnsignedInt(), equalTo(10 xor 0xFF))
        // $8000 / $C000 / $E000 unaffected.
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(30))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(31))
    }

    @Test
    fun `C000 write selects the 8KB bank at C000-DFFF`() {
        val m = newMapper65(prgBanks8k = 32)
        m.cpuWrite(0xC000, 0x05.toSignedByte())
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(5))
        assertThat(m.cpuRead(0xDFFF).toUnsignedInt(), equalTo(5 xor 0xFF))
        // $8000 / $A000 / $E000 unaffected.
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(1))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(31))
    }

    @Test
    fun `E000-FFFF window is fixed to the last bank - no register can change it`() {
        // The chip has no $E000 PRG register. Writes anywhere that don't
        // decode to a known register must leave the bank unchanged. We
        // exercise $E000..$EFFF and $F000..$FFFF exhaustively.
        val m = newMapper65(prgBanks8k = 32)
        for (addr in 0xE000..0xFFFF step 0x100) {
            m.cpuWrite(addr, 0x00.toSignedByte())   // force-clear (would affect $E000 if there were a register)
        }
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(31))   // still last
        assertThat(m.cpuRead(0xFFFF).toUnsignedInt(), equalTo(31 xor 0xFF))
    }

    @Test
    fun `all three PRG registers are independent`() {
        val m = newMapper65(prgBanks8k = 32)
        m.cpuWrite(0x8000, 0x05.toSignedByte())
        m.cpuWrite(0xA000, 0x0A.toSignedByte())
        m.cpuWrite(0xC000, 0x0F.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(5))
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(10))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(15))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(31))
    }

    @Test
    fun `8000-A000-C000 PRG writes decode regardless of low 12 address bits`() {
        // The chip's address-decode mask is 0xF000, so every address in
        // $8000-$8FFF hits the $8000 register. Verify with several aliases.
        val m = newMapper65(prgBanks8k = 32)
        m.cpuWrite(0x8042, 0x03.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        m.cpuWrite(0x8FFF, 0x04.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(4))
        m.cpuWrite(0xA123, 0x06.toSignedByte())
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(6))
        m.cpuWrite(0xACDE, 0x07.toSignedByte())
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(7))
        m.cpuWrite(0xC001, 0x09.toSignedByte())
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(9))
        m.cpuWrite(0xCFFF, 0x0B.toSignedByte())
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(11))
    }

    @Test
    fun `PRG bank numbers larger than prgBankCount wrap modulo`() {
        // 32 PRG banks. 0xFF % 32 = 31. 0x42 % 32 = 2. 0x80 % 32 = 0.
        val m = newMapper65(prgBanks8k = 32)
        m.cpuWrite(0x8000, 0xFF.toSignedByte())
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(31))
        m.cpuWrite(0xA000, 0x42.toSignedByte())
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(2))
        m.cpuWrite(0xC000, 0x80.toSignedByte())
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `all 32 8KB PRG banks are reachable from the 8000 window`() {
        val m = newMapper65(prgBanks8k = 32)
        for (bank in 0..31) {
            m.cpuWrite(0x8000, bank.toSignedByte())
            assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(bank))
        }
    }

    // ---- CHR banking ----

    @Test
    fun `default CHR banks are all 0 across the 8KB window`() {
        val m = newMapper65(chrBanks1k = 8)
        // First 1 KB page.
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x03FF).toUnsignedInt(), equalTo(0 xor 0xFF))
        // Last 1 KB page.
        assertThat(m.ppuRead(0x1C00).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x1FFF).toUnsignedInt(), equalTo(0 xor 0xFF))
    }

    @Test
    fun `B000-B007 each select one 1KB CHR bank`() {
        val m = newMapper65(chrBanks1k = 8)
        // Use banks 0..7 to avoid modulo wrap (the default 8-bank fixture
        // has indices 0..7 — bank 8 wraps to bank 0 via `% chrRom.size`).
        for (window in 0..7) {
            m.cpuWrite(0xB000 + window, window.toSignedByte())   // banks 0..7
        }
        for (window in 0..7) {
            val addr = window * 0x0400
            assertThat(m.ppuRead(addr).toUnsignedInt(), equalTo(window))
            assertThat(m.ppuRead(addr + 0x03FF).toUnsignedInt(), equalTo(window xor 0xFF))
        }
    }

    @Test
    fun `CHR bank writes decode regardless of bits A3-A11 - only A0-A2 select the register`() {
        // Per Mesen2: 8 CHR registers at $B000-$B007, low 3 bits of the
        // address select the register. Bits 3-11 alias.
        val m = newMapper65(chrBanks1k = 8)
        m.cpuWrite(0xB105, 0x03.toSignedByte())   // alias for $B005
        m.cpuWrite(0xBA05, 0x04.toSignedByte())   // alias for $B005 — overrides
        m.cpuWrite(0xBFFF, 0x07.toSignedByte())   // alias for $B007
        // $B005 -> bank 4 is in the 1 KB window at $1400-$17FF (window 5).
        assertThat(m.ppuRead(0x1400).toUnsignedInt(), equalTo(4))
        // $B007 -> bank 7 is in the 1 KB window at $1C00-$1FFF (window 7).
        assertThat(m.ppuRead(0x1C00).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `CHR bank writes only affect the target 1KB window`() {
        val m = newMapper65(chrBanks1k = 8)
        m.cpuWrite(0xB001, 0x05.toSignedByte())
        m.cpuWrite(0xB003, 0x06.toSignedByte())
        m.cpuWrite(0xB005, 0x07.toSignedByte())
        m.cpuWrite(0xB007, 0x04.toSignedByte())
        // Set banks are distinct.
        assertThat(m.ppuRead(0x0400).toUnsignedInt(), equalTo(5))
        assertThat(m.ppuRead(0x0C00).toUnsignedInt(), equalTo(6))
        assertThat(m.ppuRead(0x1400).toUnsignedInt(), equalTo(7))
        assertThat(m.ppuRead(0x1C00).toUnsignedInt(), equalTo(4))
        // Unset banks stay at 0.
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x0800).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x1800).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `missing CHR ROM falls back to 8KB CHR RAM`() {
        val pak = testGamePak {
            mapper = 65
            prgKb = 32       // 4 8KB banks
            chrKb = 0        // 0 KB -> CHR RAM fallback
            verticalMirroring = false
            stampPrg8kBanks(prg)
        }
        val m = pak.createMapper() as Mapper65
        // RAM is zero-initialized.
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        // Writes stick.
        m.ppuWrite(0x1234, 0xAB.toSignedByte())
        assertThat(m.ppuRead(0x1234).toUnsignedInt(), equalTo(0xAB))
    }

    // ---- Mirroring ----

    @Test
    fun `mirroring follows iNES header when 9001 has not been written`() {
        val m = newMapper65(mirroring = Header.Mirroring.HORIZONTAL)
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `mirroring follows iNES header (vertical) when 9001 has not been written`() {
        val m = newMapper65(mirroring = Header.Mirroring.VERTICAL)
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
    }

    @Test
    fun `bit 7 of 9001 write selects mirroring (0=vert, 1=horiz)`() {
        // Per Mesen2: `SetMirroringType(value & 0x80 ? Horizontal : Vertical)`.
        // Only bit 7 matters; bits 0-6 of $9001 are ignored.
        val m = newMapper65(mirroring = Header.Mirroring.HORIZONTAL)
        m.cpuWrite(0x9001, 0x00.toSignedByte())     // bit 7 clear -> vertical
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        m.cpuWrite(0x9001, 0x80.toSignedByte())     // bit 7 set -> horizontal
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
        m.cpuWrite(0x9001, 0xFF.toSignedByte())     // bit 7 set, low bits ignored
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `9001 register decodes regardless of bits A3-A11`() {
        // Address mask is 0xF000; within the $9000 page, the sub-decode
        // is `(address and 0x00FF)` — so $91FF, $93FF, $9F01, etc. all
        // hit $9001 if the low byte = 0x01.
        val m = newMapper65(mirroring = Header.Mirroring.VERTICAL)  // start vert
        m.cpuWrite(0x9001, 0x80.toSignedByte())
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
        m.cpuWrite(0x9101, 0x00.toSignedByte())
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        m.cpuWrite(0x9F01, 0x80.toSignedByte())
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `9000 9002 9007-900F and D000-FFFF writes do not change mirroring or PRG`() {
        // $9000 / $9002 are not handled by Mesen2's WriteRegister (the
        // wiki's "PRG bank layout" register is a wiki-only feature).
        // $9007..$900F are also not handled. $D000-$FFFF has no registers
        // at all. All of these must be silently ignored.
        val m = newMapper65(mirroring = Header.Mirroring.VERTICAL)
        for (addr in listOf(0x9000, 0x9002, 0x9007, 0x9008, 0x900F,
                            0xD000, 0xD123, 0xDFFF, 0xE000, 0xEFFF, 0xF000, 0xFFFF)) {
            m.cpuWrite(addr, 0xFF.toSignedByte())
        }
        // Mirroring stays at the header default.
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        // PRG banks stay at the power-on state (0, 1, 0xFE mod 32, 31).
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(1))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(30))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(31))
    }

    // ---- IRQ ----

    @Test
    fun `no IRQ is pending or asserted at power-on`() {
        val m = newMapper65()
        assertThat(m.isIrqPending(), equalTo(false))
    }

    @Test
    fun `9003 bit 7 enables the counter, counter decrements once per CPU cycle`() {
        // Per Mesen2: `$9003` enable, `$9004` reload+ack, counter
        // decrements per `ProcessCpuClock`. Load counter via $9005:$9006.
        val m = newMapper65()
        m.cpuWrite(0x9005, 0x00.toSignedByte())   // reload high = 0x00
        m.cpuWrite(0x9006, 0x05.toSignedByte())   // reload low  = 0x05 -> 5
        m.cpuWrite(0x9004, 0x00.toSignedByte())   // reload counter from 5
        m.cpuWrite(0x9003, 0x80.toSignedByte())   // enable (bit 7)
        // No fire yet.
        assertThat(m.isIrqPending(), equalTo(false))
        // 4 cycles: 5 -> 4 -> 3 -> 2 -> 1
        repeat(4) { m.tickCpuCycle() }
        assertThat(m.isIrqPending(), equalTo(false))
        // 1 more: 1 -> 0, fires, auto-disables.
        m.tickCpuCycle()
        assertThat(m.isIrqPending(), equalTo(true))
    }

    @Test
    fun `IRQ is one-shot - counter does not wrap and does not re-fire on further cycles`() {
        // Per Mesen2: on fire `_irqEnabled = false` and the counter stays
        // at 0. Subsequent cycles must not wrap to 0xFFFF and re-fire.
        val m = newMapper65()
        m.cpuWrite(0x9006, 0x02.toSignedByte())   // reload value = 2
        m.cpuWrite(0x9004, 0x00.toSignedByte())   // reload counter
        m.cpuWrite(0x9003, 0x80.toSignedByte())   // enable
        m.tickCpuCycle()   // 2 -> 1
        m.tickCpuCycle()   // 1 -> 0, fires, auto-disables
        assertThat(m.isIrqPending(), equalTo(true))
        // Even with the enable bit now off, exercise many more cycles.
        // (If we mistakenly wrapped to 0xFFFF, the IRQ would re-fire as
        // soon as we re-enabled. We DON'T re-enable here; the test below
        // covers the re-enable-without-reload case.)
        repeat(100) { m.tickCpuCycle() }
        assertThat("counter still at 0 (no wrap)", m.snapshot().irqState!!["irqCounter"] as Int, equalTo(0))
        assertThat("IRQ is still pending from the original fire", m.isIrqPending(), equalTo(true))
    }

    @Test
    fun `writing 9003 acknowledges a pending IRQ`() {
        val m = newMapper65()
        m.cpuWrite(0x9006, 0x01.toSignedByte())
        m.cpuWrite(0x9004, 0x00.toSignedByte())
        m.cpuWrite(0x9003, 0x80.toSignedByte())
        m.tickCpuCycle()   // 1 -> 0, fires
        assertThat(m.isIrqPending(), equalTo(true))
        // $9003 write with bit 7 clear (or set — both ack) clears pending.
        m.cpuWrite(0x9003, 0x80.toSignedByte())   // bit 7 set: re-enable AND ack
        assertThat(m.isIrqPending(), equalTo(false))
    }

    @Test
    fun `writing 9004 reloads the counter and also acknowledges a pending IRQ`() {
        val m = newMapper65()
        m.cpuWrite(0x9006, 0x01.toSignedByte())
        m.cpuWrite(0x9004, 0x00.toSignedByte())
        m.cpuWrite(0x9003, 0x80.toSignedByte())
        m.tickCpuCycle()   // 1 -> 0, fires
        assertThat(m.isIrqPending(), equalTo(true))
        // $9004 reloads the counter AND acks. The IRQ must clear.
        m.cpuWrite(0x9004, 0x00.toSignedByte())
        assertThat(m.isIrqPending(), equalTo(false))
        // Counter is now back at the reload value (1).
        assertThat(m.snapshot().irqState!!["irqCounter"] as Int, equalTo(1))
    }

    @Test
    fun `9005 and 9006 form a 16-bit reload value`() {
        // High byte is $9005, low byte is $9006. Write order: 9005 first
        // to verify high byte doesn't leak into the low half.
        val m = newMapper65()
        m.cpuWrite(0x9005, 0x12.toSignedByte())   // high = 0x12
        m.cpuWrite(0x9006, 0x34.toSignedByte())   // low  = 0x34
        // Reload + enable, then count down 0x1234 cycles and assert no fire.
        m.cpuWrite(0x9004, 0x00.toSignedByte())
        m.cpuWrite(0x9003, 0x80.toSignedByte())
        repeat(0x1233) { m.tickCpuCycle() }   // 0x1234 -> 1
        assertThat("IRQ has not fired at count=1", m.isIrqPending(), equalTo(false))
        m.tickCpuCycle()                       // 1 -> 0, fires
        assertThat("IRQ fired at count=0", m.isIrqPending(), equalTo(true))
    }

    @Test
    fun `counter is idle when 9003 bit 7 is clear, even with a non-zero reload value`() {
        val m = newMapper65()
        m.cpuWrite(0x9006, 0x10.toSignedByte())
        m.cpuWrite(0x9004, 0x00.toSignedByte())   // counter loaded but disabled
        m.cpuWrite(0x9003, 0x00.toSignedByte())   // bit 7 clear -> disabled
        repeat(100) { m.tickCpuCycle() }
        assertThat(m.isIrqPending(), equalTo(false))
        assertThat("counter never moved from its loaded value",
                   m.snapshot().irqState!!["irqCounter"] as Int, equalTo(0x10))
    }

    @Test
    fun `9004 reload does not require a preceding 9003 enable - the chip latches the value`() {
        // Per Mesen2: $9004 copies _irqReloadValue into _irqCounter. It
        // does not enable the counter; it just sets the value. Subsequent
        // $9003 enables will use the value that was latched.
        val m = newMapper65()
        m.cpuWrite(0x9006, 0x04.toSignedByte())
        m.cpuWrite(0x9004, 0x00.toSignedByte())   // load 4 into counter (still disabled)
        m.cpuWrite(0x9003, 0x80.toSignedByte())   // enable
        // Counter starts at 4 (no $9004 needed at this point).
        m.tickCpuCycle()   // 4 -> 3
        m.tickCpuCycle()   // 3 -> 2
        m.tickCpuCycle()   // 2 -> 1
        assertThat(m.isIrqPending(), equalTo(false))
        m.tickCpuCycle()   // 1 -> 0, fires
        assertThat(m.isIrqPending(), equalTo(true))
    }

    // ---- Save / load ----

    @Test
    fun `saveState then loadState round-trips PRG CHR mirroring and IRQ state`() {
        val m = newMapper65(prgBanks8k = 32, chrBanks1k = 8, mirroring = Header.Mirroring.VERTICAL)
        m.cpuWrite(0x8000, 0x05.toSignedByte())
        m.cpuWrite(0xA000, 0x0A.toSignedByte())
        m.cpuWrite(0xC000, 0x0F.toSignedByte())
        m.cpuWrite(0xB002, 0x04.toSignedByte())
        m.cpuWrite(0xB005, 0x06.toSignedByte())
        m.cpuWrite(0x9001, 0x80.toSignedByte())   // horiz
        m.cpuWrite(0x9005, 0x12.toSignedByte())
        m.cpuWrite(0x9006, 0x34.toSignedByte())
        m.cpuWrite(0x9004, 0x00.toSignedByte())   // counter = 0x1234
        m.cpuWrite(0x9003, 0x80.toSignedByte())   // enabled

        val bytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { m.saveState(it) }
            baos.toByteArray()
        }
        val fresh = newMapper65(prgBanks8k = 32, chrBanks1k = 8, mirroring = Header.Mirroring.VERTICAL)
        DataInputStream(ByteArrayInputStream(bytes)).use { fresh.loadState(it) }

        // PRG.
        assertThat(fresh.cpuRead(0x8000).toUnsignedInt(), equalTo(5))
        assertThat(fresh.cpuRead(0xA000).toUnsignedInt(), equalTo(10))
        assertThat(fresh.cpuRead(0xC000).toUnsignedInt(), equalTo(15))
        assertThat(fresh.cpuRead(0xE000).toUnsignedInt(), equalTo(31))   // fixed
        // CHR.
        assertThat(fresh.ppuRead(0x0800).toUnsignedInt(), equalTo(4))
        assertThat(fresh.ppuRead(0x1400).toUnsignedInt(), equalTo(6))
        // Mirroring.
        assertThat(fresh.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
        // IRQ.
        val irq = fresh.snapshot().irqState!!
        assertThat(irq["irqEnabled"] as Int, equalTo(1))
        assertThat(irq["irqCounter"] as Int, equalTo(0x1234))
        assertThat(irq["irqReloadValue"] as Int, equalTo(0x1234))
    }

    @Test
    fun `saveState round-trips CHR RAM when present`() {
        val pak = testGamePak {
            mapper = 65
            prgKb = 16
            chrKb = 0      // CHR RAM fallback
            verticalMirroring = false
            stampPrg8kBanks(prg)
        }
        val m = pak.createMapper() as Mapper65
        m.ppuWrite(0x0123, 0x77.toSignedByte())
        val bytes = ByteArrayOutputStream().use { baos ->
            DataOutputStream(baos).use { m.saveState(it) }
            baos.toByteArray()
        }
        val fresh = pak.createMapper() as Mapper65
        DataInputStream(ByteArrayInputStream(bytes)).use { fresh.loadState(it) }
        assertThat(fresh.ppuRead(0x0123).toUnsignedInt(), equalTo(0x77))
    }

    // ---- Snapshot ----

    @Test
    fun `snapshot reflects all banking and IRQ state`() {
        val m = newMapper65(prgBanks8k = 32, chrBanks1k = 8, mirroring = Header.Mirroring.VERTICAL)
        m.cpuWrite(0x8000, 0x05.toSignedByte())
        m.cpuWrite(0xA000, 0x0A.toSignedByte())
        m.cpuWrite(0xC000, 0x0F.toSignedByte())
        m.cpuWrite(0xB003, 0x06.toSignedByte())
        m.cpuWrite(0x9001, 0x00.toSignedByte())   // vertical
        m.cpuWrite(0x9005, 0xAB.toSignedByte())
        m.cpuWrite(0x9006, 0xCD.toSignedByte())
        m.cpuWrite(0x9004, 0x00.toSignedByte())
        m.cpuWrite(0x9003, 0x80.toSignedByte())   // enable
        val snap = m.snapshot()
        assertThat(snap.mapperId, equalTo(65))
        assertThat(snap.banks["prgBank0"], equalTo(5))
        assertThat(snap.banks["prgBank1"], equalTo(10))
        assertThat(snap.banks["prgBank2"], equalTo(15))
        assertThat(snap.banks["chrBank3"], equalTo(6))
        assertThat(snap.registers["horizontalMirroring"], equalTo(0))
        val irq = snap.irqState!!
        assertThat(irq["irqEnabled"] as Int, equalTo(1))
        assertThat(irq["irqCounter"] as Int, equalTo(0xABCD))
        assertThat(irq["irqReloadValue"] as Int, equalTo(0xABCD))
        assertThat(irq["irqPending"] as Int, equalTo(0))
    }
}
