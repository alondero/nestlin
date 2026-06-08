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
 * Unit tests for Mapper 64 (Tengen RAMBO-1), modelled on Mesen2's
 * `Rambo1.h` (the project's reference oracle). RAMBO-1 shares MMC3's
 * register *protocol* but its banking differs in three ways the tests
 * below pin down — all three are exercised by Klax (verified by
 * `Mapper64KlaxBankTraceTest`):
 *
 *   - 4-bit register select ($8000 bits 0-3 → R0-R15), so R8/R9 (extra
 *     1 KB CHR) and R15 (a third switchable PRG bank) are reachable.
 *   - Three switchable 8 KB PRG banks (R6, R7, R15); $E000 fixed to last.
 *     Neither $8000 nor $C000 is ever a fixed second-to-last bank (that's
 *     the MMC3 layout).
 *   - 1 KB CHR mode ($8000 bit 5, "K") adding R8/R9; bit 7 (A12 invert)
 *     XORs the 1 KB page index with 4.
 *
 * Test ROMs are stamped so a read identifies the bank: every PRG byte
 * equals its 8 KB bank index, and (via [makeStampedChr]) byte 0 of every
 * 1 KB CHR bank equals that bank's index.
 */
class Mapper64Test {

    // prg8k = 8 KB units, chr1k = 1 KB units. Defaults give 8 PRG banks
    // (matching real Klax / Skull & Crossbones) and 32 CHR banks.
    private fun createTestGamePak(
        prg8k: Int = 8,
        chr1k: Int = 32,
        mirroring: Header.Mirroring = Header.Mirroring.VERTICAL
    ): GamePak {
        val header = ByteArray(16)
        header[0] = 'N'.code.toByte(); header[1] = 'E'.code.toByte()
        header[2] = 'S'.code.toByte(); header[3] = 0x1A.toByte()
        header[4] = (prg8k / 2).toByte()           // PRG ROM size in 16 KB units
        header[5] = (chr1k / 8).toByte()           // CHR ROM size in 8 KB units
        val mirrorBit = if (mirroring == Header.Mirroring.VERTICAL) 0x01 else 0x00
        header[6] = (((64 and 0x0F) shl 4) or mirrorBit).toByte()
        header[7] = (64 and 0xF0).toByte()
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
    private fun createStampedGamePak(chr1kBanks: Int, prg8kBanks: Int = 8): GamePak {
        val header = ByteArray(16)
        header[0] = 'N'.code.toByte(); header[1] = 'E'.code.toByte()
        header[2] = 'S'.code.toByte(); header[3] = 0x1A.toByte()
        header[4] = (prg8kBanks / 2).toByte()       // 16 KB units
        header[5] = (chr1kBanks / 8).toByte()       // 8 KB units
        header[6] = (((64 and 0x0F) shl 4) or 0x00).toByte()
        header[7] = (64 and 0xF0).toByte()
        val prg = ByteArray(prg8kBanks * 0x2000) { ((it / 0x2000) and 0xFF).toByte() }
        val chr = makeStampedChr(chr1kBanks)
        return GamePak(header + prg + chr)
    }

    /** Send a (register, value) RAMBO-1 write pair: $8000 select then $8001 data. */
    private fun Mapper64.bankWrite(bankSelect: Int, value: Int) {
        cpuWrite(0x8000, bankSelect.toSignedByte())
        cpuWrite(0x8001, value.toSignedByte())
    }

    // ---- Factory wiring ----

    @Test
    fun `mapper 64 is selected for header mapper 64`() {
        assertThat(createTestGamePak().createMapper() is Mapper64, equalTo(true))
    }

    // ---- PRG banking: R6/R7/R15 switchable, $E000 fixed to last ----

    @Test
    fun `power-on PRG banks are all register 0 with E000 fixed to last`() {
        // Mesen initialises every bank register to 0, so in PRG mode 0:
        // $8000=R6=0, $A000=R7=0, $C000=R15=0, $E000=last.
        val m = Mapper64(createTestGamePak(prg8k = 8))
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(7))   // last bank
    }

    @Test
    fun `R6 at 8000 and R7 at A000 switch 8KB PRG banks in mode 0`() {
        val m = Mapper64(createTestGamePak(prg8k = 8))
        m.bankWrite(6, 3)   // $8000-$9FFF -> bank 3
        m.bankWrite(7, 5)   // $A000-$BFFF -> bank 5
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(m.cpuRead(0x9FFF).toUnsignedInt(), equalTo(3))
        assertThat(m.cpuRead(0xA000).toUnsignedInt(), equalTo(5))
        assertThat(m.cpuRead(0xBFFF).toUnsignedInt(), equalTo(5))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(7))   // last, untouched
    }

    @Test
    fun `R15 is the third switchable PRG bank - maps to C000 in mode 0`() {
        // This is the key RAMBO-1 difference from MMC3: $C000 is NOT a fixed
        // second-to-last bank, it is register 15 (reachable only with a 4-bit
        // register select). Klax relies on this.
        val m = Mapper64(createTestGamePak(prg8k = 8))
        m.bankWrite(15, 3)   // R15 = bank 3
        assertThat(m.cpuRead(0xC000).toUnsignedInt(), equalTo(3))
        assertThat(m.cpuRead(0xDFFF).toUnsignedInt(), equalTo(3))
        // $8000/$A000 still their own registers, $E000 still last.
        assertThat(m.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0xE000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `PRG mode 1 swaps R15 to 8000 and R7 to C000`() {
        // mode 0: $8000=R6, $A000=R7, $C000=R15
        // mode 1: $8000=R15, $A000=R6, $C000=R7
        val m = Mapper64(createTestGamePak(prg8k = 8))
        m.bankWrite(6, 3)    // R6 = 3
        m.bankWrite(7, 5)    // R7 = 5
        m.bankWrite(15, 2)   // R15 = 2
        // Enter mode 1: $8000 bit 6 set. Keep selecting a harmless register (R6).
        m.cpuWrite(0x8000, (0x40 or 0x06).toSignedByte())
        assertThat("8000 = R15", m.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
        assertThat("A000 = R6", m.cpuRead(0xA000).toUnsignedInt(), equalTo(3))
        assertThat("C000 = R7", m.cpuRead(0xC000).toUnsignedInt(), equalTo(5))
        assertThat("E000 = last", m.cpuRead(0xE000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `register select uses 4 bits - R15 is not aliased onto R7`() {
        // MMC3 masks the register select to 3 bits, which would alias R15
        // (0xF) onto R7 (0x7). RAMBO-1 keeps 4 bits, so R15 and R7 are
        // independent. Writing R15 must NOT change R7's window ($A000).
        val m = Mapper64(createTestGamePak(prg8k = 8))
        m.bankWrite(7, 5)    // R7 = 5
        m.bankWrite(15, 2)   // R15 = 2 (would clobber R7 if select were 3-bit)
        assertThat("A000 still R7", m.cpuRead(0xA000).toUnsignedInt(), equalTo(5))
        assertThat("C000 is R15", m.cpuRead(0xC000).toUnsignedInt(), equalTo(2))
    }

    // ---- $6000-$7FFF / $4020-$5FFF: no PRG-RAM, no expansion ----

    @Test
    fun `6000-7FFF always reads 0 (no PRG-RAM)`() {
        val m = Mapper64(createTestGamePak())
        assertThat(m.cpuRead(0x6000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0x7FFF).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `4020-5FFF always reads 0 (no expansion wiring)`() {
        val m = Mapper64(createTestGamePak())
        assertThat("$4020 reads 0", m.cpuRead(0x4020).toUnsignedInt(), equalTo(0))
        assertThat("$5FFF reads 0", m.cpuRead(0x5FFF).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `6000-7FFF writes are silently discarded`() {
        val m = Mapper64(createTestGamePak())
        m.cpuWrite(0xA001, 0x80.toSignedByte())   // would enable PRG-RAM on MMC3
        m.cpuWrite(0x6000, 0x42.toSignedByte())
        m.cpuWrite(0x67FF, 0x99.toSignedByte())
        assertThat(m.cpuRead(0x6000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0x67FF).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `batteryBackedRam returns null (no PRG-RAM to back up)`() {
        val m = Mapper64(createTestGamePak())
        assertThat(m.batteryBackedRam(), equalTo(null as ByteArray?))
    }

    // ---- CHR banking: 2 KB default mode ----

    @Test
    fun `CHR reads from bank 0 by default`() {
        val m = Mapper64(createStampedGamePak(chr1kBanks = 16))
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x1C00).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `R0 covers 0000-07FF as a 2KB bank (reg and reg+1, no masking)`() {
        // Mesen maps page 0 = R0 and page 1 = R0+1 directly (no low-bit mask).
        val m = Mapper64(createStampedGamePak(chr1kBanks = 16))
        m.bankWrite(0, 6)
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(6))      // page 0 = R0
        assertThat(m.ppuRead(0x0400).toUnsignedInt(), equalTo(7))      // page 1 = R0+1
    }

    @Test
    fun `R1 covers 0800-0FFF as a 2KB bank (reg and reg+1)`() {
        val m = Mapper64(createStampedGamePak(chr1kBanks = 16))
        m.bankWrite(1, 4)
        assertThat(m.ppuRead(0x0800).toUnsignedInt(), equalTo(4))      // page 2 = R1
        assertThat(m.ppuRead(0x0C00).toUnsignedInt(), equalTo(5))      // page 3 = R1+1
    }

    @Test
    fun `R2-R5 each select a 1KB CHR bank at 1000-1FFF`() {
        val m = Mapper64(createStampedGamePak(chr1kBanks = 16))
        m.bankWrite(2, 10)   // $1000-$13FF
        m.bankWrite(3, 11)   // $1400-$17FF
        m.bankWrite(4, 12)   // $1800-$1BFF
        m.bankWrite(5, 13)   // $1C00-$1FFF
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(10))
        assertThat(m.ppuRead(0x1400).toUnsignedInt(), equalTo(11))
        assertThat(m.ppuRead(0x1800).toUnsignedInt(), equalTo(12))
        assertThat(m.ppuRead(0x1C00).toUnsignedInt(), equalTo(13))
    }

    @Test
    fun `R6-R7 do not affect CHR reads (they are PRG banks)`() {
        val m = Mapper64(createStampedGamePak(chr1kBanks = 16))
        m.bankWrite(6, 7)
        m.bankWrite(7, 7)
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0))
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(0))
    }

    // ---- CHR banking: 1 KB ("K") mode adds R8/R9 ----

    @Test
    fun `K mode (8000 bit 5) splits 0000-0FFF into four 1KB banks with R8 and R9`() {
        // K=0: page 0/1 = R0,R0+1 ; page 2/3 = R1,R1+1.
        // K=1: page 0/1 = R0,R8   ; page 2/3 = R1,R9.
        val m = Mapper64(createStampedGamePak(chr1kBanks = 32))
        m.bankWrite(0, 4)    // R0 = 4
        m.bankWrite(1, 6)    // R1 = 6
        m.bankWrite(8, 20)   // R8 = 20  (needs the 4-bit select)
        m.bankWrite(9, 21)   // R9 = 21
        // Enable K mode (bit 5) while leaving the other mode bits clear.
        m.cpuWrite(0x8000, 0x20.toSignedByte())
        assertThat("0000 = R0", m.ppuRead(0x0000).toUnsignedInt(), equalTo(4))
        assertThat("0400 = R8", m.ppuRead(0x0400).toUnsignedInt(), equalTo(20))
        assertThat("0800 = R1", m.ppuRead(0x0800).toUnsignedInt(), equalTo(6))
        assertThat("0C00 = R9", m.ppuRead(0x0C00).toUnsignedInt(), equalTo(21))
    }

    @Test
    fun `bit 7 (CHR A12 inversion) XORs the page index with 4`() {
        // Inversion swaps the $0000-$0FFF and $1000-$1FFF halves.
        val m = Mapper64(createStampedGamePak(chr1kBanks = 16))
        m.bankWrite(0, 6)    // R0 (2KB at $0000-$07FF when not inverted)
        m.bankWrite(2, 9)    // R2 (1KB at $1000-$13FF when not inverted)
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(6))   // page 0 -> R0
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(9))   // page 4 -> R2

        m.cpuWrite(0x8000, 0x80.toSignedByte())   // set invert, select R0
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(9))   // page 0 -> slot 4 -> R2
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(6))   // page 4 -> slot 0 -> R0
    }

    // ---- Bank-select register / $8000 protocol ----

    @Test
    fun `odd-address data write (not just 8001) still hits the bank-data branch`() {
        val m = Mapper64(createStampedGamePak(chr1kBanks = 16))
        m.cpuWrite(0x8000, 2.toSignedByte())   // select R2
        m.cpuWrite(0x9FFF, 11.toSignedByte())  // odd address -> $8001 branch
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(11))
    }

    @Test
    fun `bank-select register is cleared to 0 on power-on`() {
        val m = Mapper64(createTestGamePak())
        assertThat(m.ppuRead(0x1000).toUnsignedInt(), equalTo(0))   // R2 = 0
    }

    // ---- $A000 mirroring ----

    @Test
    fun `A000 mirroring write selects vertical or horizontal`() {
        val m = Mapper64(createTestGamePak(mirroring = Header.Mirroring.HORIZONTAL))
        m.cpuWrite(0xA000, 0x00.toSignedByte())
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        m.cpuWrite(0xA000, 0x01.toSignedByte())
        assertThat(m.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    @Test
    fun `A001 is a no-op (no PRG-RAM enable or write-protect register)`() {
        val m = Mapper64(createTestGamePak())
        m.cpuWrite(0xA001, 0x80.toSignedByte())
        m.cpuWrite(0xA001, 0x40.toSignedByte())
        m.cpuWrite(0xA001, 0xC0.toSignedByte())
        assertThat(m.cpuRead(0x6000).toUnsignedInt(), equalTo(0))
        assertThat(m.cpuRead(0x7FFF).toUnsignedInt(), equalTo(0))
    }

    // ---- A12 (scanline) IRQ ----

    @Test
    fun `IRQ latch sets the reload value`() {
        val m = Mapper64(createTestGamePak())
        m.cpuWrite(0xC000, 0x05.toSignedByte())
        assertThat(m.snapshot().irqState!!["irqLatch"], equalTo(0x05 as Any))
    }

    @Test
    fun `IRQ fires after enough A12 rising edges when enabled (latch 1)`() {
        // RAMBO-1 explicit reload of latch=1 -> counter=2; fires on the 3rd edge.
        val m = Mapper64(createTestGamePak())
        m.cpuWrite(0xC000, 0x01.toSignedByte())   // latch = 1
        m.cpuWrite(0xC001, 0x00.toSignedByte())   // trigger reload, A12 mode (bit 0 = 0)
        m.cpuWrite(0xE001, 0x00.toSignedByte())   // IRQ enable
        m.notifyA12Edge(true)
        assertThat("after one edge", m.isIrqPending(), equalTo(false))
        m.notifyA12Edge(true)
        assertThat("after two edges", m.isIrqPending(), equalTo(false))
        m.notifyA12Edge(true)
        assertThat("after three edges", m.isIrqPending(), equalTo(true))
    }

    @Test
    fun `IRQ disable write prevents the counter from asserting IRQ`() {
        val m = Mapper64(createTestGamePak())
        m.cpuWrite(0xC000, 0x00.toSignedByte())
        m.cpuWrite(0xC001, 0x00.toSignedByte())
        repeat(12) { m.notifyA12Edge(true) }
        assertThat(m.isIrqPending(), equalTo(false))
    }

    @Test
    fun `acknowledgeIrq clears the pending flag`() {
        val m = Mapper64(createTestGamePak())
        m.cpuWrite(0xC000, 0x00.toSignedByte())   // latch = 0 -> reload to 1
        m.cpuWrite(0xC001, 0x00.toSignedByte())
        m.cpuWrite(0xE001, 0x00.toSignedByte())
        m.notifyA12Edge(true)   // reload to 1
        m.notifyA12Edge(true)   // -> 0, IRQ
        assertThat(m.isIrqPending(), equalTo(true))
        m.acknowledgeIrq()
        assertThat(m.isIrqPending(), equalTo(false))
    }

    @Test
    fun `explicit reload uses latch+2 for latch greater than 1`() {
        // Mesen: explicit ($C001) reload is latch+2 when latch>1, latch+1 when
        // latch<=1. With latch=3 -> counter=5; the IRQ fires on the 6th edge.
        val m = Mapper64(createTestGamePak())
        m.cpuWrite(0xC000, 0x03.toSignedByte())   // latch = 3
        m.cpuWrite(0xC001, 0x00.toSignedByte())   // explicit reload -> counter = 5
        m.cpuWrite(0xE001, 0x00.toSignedByte())   // enable
        repeat(5) {
            m.notifyA12Edge(true)
            assertThat("edge ${it + 1} of 5 should not fire", m.isIrqPending(), equalTo(false))
        }
        m.notifyA12Edge(true)
        assertThat("6th edge fires", m.isIrqPending(), equalTo(true))
    }

    // ---- CPU-cycle IRQ ($C001 bit 0): clocked every 4 CPU cycles ----

    @Test
    fun `CPU-cycle IRQ mode clocks the counter once every 4 CPU cycles`() {
        val m = Mapper64(createTestGamePak())
        m.cpuWrite(0xC000, 0x01.toSignedByte())   // latch = 1
        m.cpuWrite(0xC001, 0x01.toSignedByte())   // reload + CPU-cycle mode (bit 0)
        m.cpuWrite(0xE001, 0x00.toSignedByte())   // enable
        // latch=1 -> explicit reload to 2. Three counter clocks fire the IRQ
        // (reload->2, ->1, ->0), and each clock takes 4 CPU cycles => 12 ticks.
        repeat(11) { m.tickCpuCycle() }
        assertThat("after 11 cpu cycles (< 12)", m.isIrqPending(), equalTo(false))
        m.tickCpuCycle()
        assertThat("after 12 cpu cycles (3 clocks)", m.isIrqPending(), equalTo(true))
    }

    @Test
    fun `CPU-cycle mode does not clock on A12 edges`() {
        val m = Mapper64(createTestGamePak())
        m.cpuWrite(0xC000, 0x01.toSignedByte())
        m.cpuWrite(0xC001, 0x01.toSignedByte())   // CPU-cycle mode
        m.cpuWrite(0xE001, 0x00.toSignedByte())
        repeat(20) { m.notifyA12Edge(true) }      // A12 edges must be ignored
        assertThat(m.isIrqPending(), equalTo(false))
    }

    // ---- CHR RAM fallback ----

    @Test
    fun `CHR RAM is writable when chrRom is empty`() {
        val header = ByteArray(16)
        header[0] = 'N'.code.toByte(); header[1] = 'E'.code.toByte()
        header[2] = 'S'.code.toByte(); header[3] = 0x1A.toByte()
        header[4] = 0x01.toByte()                          // 16 KB PRG
        header[5] = 0x00.toByte()                          // 0 CHR ROM
        header[6] = (((64 and 0x0F) shl 4) or 0x00).toByte()
        header[7] = (64 and 0xF0).toByte()
        val prg = ByteArray(0x4000) { ((it / 0x2000) and 0xFF).toByte() }
        val m = Mapper64(GamePak(header + prg))
        m.ppuWrite(0x0000, 0x42.toSignedByte())
        assertThat(m.ppuRead(0x0000).toUnsignedInt(), equalTo(0x42))
    }

    // ---- Save / load round-trip ----

    @Test
    fun `save and load round-trips R6 R7 R15 banking, modes, and IRQ state`() {
        val original = Mapper64(createStampedGamePak(chr1kBanks = 32, prg8kBanks = 8))
        original.bankWrite(0, 5)    // R0 = 5 (CHR)
        original.bankWrite(2, 11)   // R2 = 11 (CHR)
        original.bankWrite(6, 2)    // R6 = 2 (PRG)
        original.bankWrite(7, 4)    // R7 = 4 (PRG)
        original.bankWrite(15, 3)   // R15 = 3 (PRG)
        // 0xC0 = mode 1 (bit 6) + CHR invert (bit 7), register select 0.
        original.cpuWrite(0x8000, 0xC0.toSignedByte())
        original.cpuWrite(0xA000, 0x01.toSignedByte())   // horizontal mirroring
        original.cpuWrite(0xC000, 0x07.toSignedByte())   // IRQ latch = 7
        original.cpuWrite(0xE001, 0x00.toSignedByte())   // IRQ enable

        val bytes = ByteArrayOutputStream().also { original.saveState(DataOutputStream(it)) }.toByteArray()
        val restored = Mapper64(createStampedGamePak(chr1kBanks = 32, prg8kBanks = 8))
        restored.loadState(DataInputStream(ByteArrayInputStream(bytes)))

        // PRG mode 1: $8000=R15=3, $A000=R6=2, $C000=R7=4, $E000=last=7.
        assertThat(restored.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(restored.cpuRead(0xA000).toUnsignedInt(), equalTo(2))
        assertThat(restored.cpuRead(0xC000).toUnsignedInt(), equalTo(4))
        assertThat(restored.cpuRead(0xE000).toUnsignedInt(), equalTo(7))
        // CHR invert on: page 0 -> slot 4 -> R2 = 11; page 4 -> slot 0 -> R0 = 5.
        assertThat(restored.ppuRead(0x0000).toUnsignedInt(), equalTo(11))
        assertThat(restored.ppuRead(0x1000).toUnsignedInt(), equalTo(5))
        assertThat(restored.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
        assertThat(restored.snapshot().irqState!!["irqLatch"], equalTo(0x07 as Any))
        assertThat(restored.cpuRead(0x6000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `save and load preserves CPU-cycle IRQ mode`() {
        val original = Mapper64(createTestGamePak())
        original.cpuWrite(0xC000, 0x01.toSignedByte())   // latch = 1
        original.cpuWrite(0xC001, 0x01.toSignedByte())   // reload + CPU-cycle mode
        original.cpuWrite(0xE001, 0x00.toSignedByte())   // enable

        val bytes = ByteArrayOutputStream().also { original.saveState(DataOutputStream(it)) }.toByteArray()
        val restored = Mapper64(createTestGamePak())
        restored.loadState(DataInputStream(ByteArrayInputStream(bytes)))

        // After load, CPU cycles must clock the counter and A12 edges must not.
        repeat(20) { restored.notifyA12Edge(true) }
        assertThat("A12 still ignored after load", restored.isIrqPending(), equalTo(false))
        repeat(12) { restored.tickCpuCycle() }
        assertThat("CPU cycles still clock after load", restored.isIrqPending(), equalTo(true))
    }

    // ---- Snapshot ----

    @Test
    fun `snapshot reports mapper 64 type, R6 R0 banks, and null PRG-RAM`() {
        val m = Mapper64(createTestGamePak())
        m.bankWrite(0, 5)    // R0
        m.bankWrite(6, 2)    // R6
        m.bankWrite(15, 7)   // R15
        val snap = m.snapshot()
        assertThat(snap.mapperId, equalTo(64))
        assertThat(snap.type, equalTo("Tengen RAMBO-1"))
        assertThat(snap.banks["prgBank6"], equalTo(2))
        assertThat(snap.banks["prgBankF"], equalTo(7))
        assertThat(snap.banks["chrBankR0"], equalTo(5))
        assertThat(snap.prgRam, equalTo(null as Any?))
        assertThat(snap.irqState!!["irqLatch"], equalTo(0 as Any))
        assertThat(snap.registers["prgMode"], equalTo(0))
        assertThat(snap.registers["chrPrgInvert"], equalTo(0))
    }
}
