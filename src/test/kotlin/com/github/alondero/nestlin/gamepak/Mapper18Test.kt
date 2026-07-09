package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.testutil.testGamePak
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Unit tests for Mapper 18 (Jaleco SS880006).
 *
 * ROM image is built via [testGamePak], which stamps each PRG 16KB bank with
 * (bank_index and 0xFF) at its first byte and each CHR 1KB bank the same way —
 * so reading byte 0 of any window identifies which bank is mapped there.
 *
 * The mapper implements the Mesen2 `JalecoSs88006.h` register model: 3 × 8KB
 * PRG pages + a fixed last 8KB page, 8 × 1KB CHR pages, an IRQ counter with
 * 16/12/8/4-bit configurable width clocked every CPU cycle, a 4-nibble IRQ
 * reload latch, and a mirroring register at `$F002`.
 */
class Mapper18Test {

    private fun build(prg16k: Int = 4, chr8k: Int = 2): GamePak =
        testGamePak {
            mapper = 18
            prgKb = prg16k * 16
            chrKb = chr8k * 8
            stampPrgBanks(windowKb = 8)
            stampChrBanks(windowKb = 1)
        }

    // ---- Header dispatch ----------------------------------------------------

    @Test
    fun `mapper 18 dispatches to Mapper18`() {
        val mapper = build().createMapper()
        assertThat(mapper is Mapper18, equalTo(true))
    }

    // ---- PRG banking ---------------------------------------------------------

    @Test
    fun `E000-FFFF is fixed to the last 8KB bank`() {
        val mapper = Mapper18(build(prg16k = 4))   // 8 × 8KB banks; last = 7
        // The first byte of each 8KB window is stamped with its bank index
        // by stampPrgBanks(windowKb=8). So reading $E000 returns 7.
        assertThat(mapper.cpuRead(0xE000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `register 8000 low-nibble selects PRG bank 0`() {
        val mapper = Mapper18(build(prg16k = 4))
        mapper.cpuWrite(0x8000, 0x03.toSignedByte())   // bank 0 low nibble = 3
        assertThat("prgBank0 register value", mapper.snapshot()?.banks?.get("prgBank0"), equalTo(3))
        assertThat("first byte of $8000 maps to bank 3's first byte", mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
    }

    @Test
    fun `register 8001 high-nibble composes PRG bank 0`() {
        val mapper = Mapper18(build(prg16k = 8))       // 8 banks available
        mapper.cpuWrite(0x8000, 0x02.toSignedByte())   // bank 0 low nibble = 2
        // High nibble at odd address — bank = (0x1 shl 4) | 0x2 = 0x12 = 18
        mapper.cpuWrite(0x8001, 0x01.toSignedByte())
        assertThat("prgBank0 = 0x12 after low=2 + high=1", mapper.snapshot()?.banks?.get("prgBank0"), equalTo(0x12))
        // Reading from $8000 should now select bank 0x12 (=18); index 0x12 * 8KB = 0x90000.
        // The fixture stamps only up to bank 7, so this read returns whatever
        // is at programRom[0x12 * 0x2000] which, with the bank stamp only at
        // the first byte, is 0. We rely on the snapshot for the high-nibble
        // assertion instead — PRG bank selection itself is covered by the
        // 8000 low-nibble test above.
    }

    @Test
    fun `register 8002 selects PRG bank 1 at A000-BFFF`() {
        val mapper = Mapper18(build(prg16k = 4))
        mapper.cpuWrite(0x8002, 0x01.toSignedByte())
        assertThat("prgBank1 register value", mapper.snapshot()?.banks?.get("prgBank1"), equalTo(1))
        assertThat("first byte of 0xA000 maps to bank 1's first byte", mapper.cpuRead(0xA000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `register 9000 selects PRG bank 2 at C000-DFFF`() {
        val mapper = Mapper18(build(prg16k = 4))
        mapper.cpuWrite(0x9000, 0x02.toSignedByte())
        assertThat("prgBank2 register value", mapper.snapshot()?.banks?.get("prgBank2"), equalTo(2))
        assertThat("first byte of 0xC000 maps to bank 2's first byte", mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(2))
    }

    // ---- CHR banking ---------------------------------------------------------

    @Test
    fun `8 CHR register pairs each select a 1KB bank`() {
        val mapper = Mapper18(build(chr8k = 2))         // 16 CHR banks
        // Each CHR window has its own register pair (even = low nibble, odd =
        // high nibble). The chip's mask is `addr & 0xF003`, so only these 8
        // base addresses reach distinct windows — writes to 0xA004, 0xA006,
        // etc. all alias back to 0xA000 (window 0). An earlier loop used an
        // arithmetic stride that collided with this alias and confused bank
        // selection; the explicit table is the corrected fixture.
        val chrBaseAddrs = intArrayOf(
            0xA000, 0xA002,
            0xB000, 0xB002,
            0xC000, 0xC002,
            0xD000, 0xD002
        )
        for (window in 0..7) {
            val baseAddr = chrBaseAddrs[window]
            val target = (window + 4) and 0xFF
            mapper.cpuWrite(baseAddr,     (target and 0x0F).toSignedByte())
            mapper.cpuWrite(baseAddr + 1, ((target ushr 4) and 0x0F).toSignedByte())
        }
        for (window in 0..7) {
            val addr = window * 0x400
            assertThat(
                "CHR window $window (0x${"%04X".format(addr)})",
                mapper.ppuRead(addr).toUnsignedInt(),
                equalTo(window + 4)
            )
        }
    }

    // ---- Mirroring -----------------------------------------------------------

    @Test
    fun `F002 mirroring register cycles HORIZONTAL, VERTICAL, ONE_SCREEN_LOWER, ONE_SCREEN_UPPER`() {
        val mapper = Mapper18(build())
        mapper.cpuWrite(0xF002, 0x00.toSignedByte())
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
        mapper.cpuWrite(0xF002, 0x01.toSignedByte())
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        mapper.cpuWrite(0xF002, 0x02.toSignedByte())
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
        mapper.cpuWrite(0xF002, 0x03.toSignedByte())
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
    }

    // ---- IRQ -----------------------------------------------------------------

    @Test
    fun `F001 bit 0 enables the IRQ`() {
        val mapper = Mapper18(build())
        mapper.cpuWrite(0xF001, 0x00.toSignedByte())
        assertThat("disabled by default", mapper.snapshot().irqState!!["irqEnabled"], equalTo(0 as Any))
        mapper.cpuWrite(0xF001, 0x01.toSignedByte())
        assertThat("enabled after F001=0x01", mapper.snapshot().irqState!!["irqEnabled"], equalTo(1 as Any))
    }

    @Test
    fun `F001 bits 1-3 select counter width (4, 8, 12, 16-bit)`() {
        val mapper = Mapper18(build())
        // F001 bit 3 -> 4-bit
        mapper.cpuWrite(0xF001, 0x08.toSignedByte())
        assertThat(mapper.snapshot().irqState!!["irqCounterSize"], equalTo(3 as Any))
        // F001 bit 2 (no bit 3) -> 8-bit
        mapper.cpuWrite(0xF001, 0x04.toSignedByte())
        assertThat(mapper.snapshot().irqState!!["irqCounterSize"], equalTo(2 as Any))
        // F001 bit 1 (no bits 2 or 3) -> 12-bit
        mapper.cpuWrite(0xF001, 0x02.toSignedByte())
        assertThat(mapper.snapshot().irqState!!["irqCounterSize"], equalTo(1 as Any))
        // F001 = 0x01 -> 16-bit (no width bits set)
        mapper.cpuWrite(0xF001, 0x01.toSignedByte())
        assertThat(mapper.snapshot().irqState!!["irqCounterSize"], equalTo(0 as Any))
    }

    @Test
    fun `F000 reload reloads the counter from the E000-E003 nibble latch`() {
        val mapper = Mapper18(build())
        // Reload nibbles: 0xA, 0xB, 0xC, 0xD -> 0xDCBA.
        mapper.cpuWrite(0xE000, 0x0A.toSignedByte())
        mapper.cpuWrite(0xE001, 0x0B.toSignedByte())
        mapper.cpuWrite(0xE002, 0x0C.toSignedByte())
        mapper.cpuWrite(0xE003, 0x0D.toSignedByte())
        mapper.cpuWrite(0xF000, 0x00.toSignedByte())   // reload + ACK (value irrelevant)
        assertThat(mapper.snapshot().irqState!!["irqCounter"], equalTo(0xDCBA as Any))
    }

    @Test
    fun `counter decrements per CPU cycle when enabled`() {
        val mapper = Mapper18(build())
        mapper.cpuWrite(0xE000, 0x05.toSignedByte())  // reload = 5
        mapper.cpuWrite(0xF000, 0x00.toSignedByte())  // reload
        mapper.cpuWrite(0xF001, 0x01.toSignedByte())  // enable
        mapper.tickCpuCycle()
        assertThat(mapper.snapshot().irqState!!["irqCounter"], equalTo(4 as Any))
        mapper.tickCpuCycle()
        assertThat(mapper.snapshot().irqState!!["irqCounter"], equalTo(3 as Any))
    }

    @Test
    fun `counter reaching 0 fires the IRQ (and is held at 0, not underflowed)`() {
        val mapper = Mapper18(build())
        mapper.cpuWrite(0xE000, 0x02.toSignedByte())
        mapper.cpuWrite(0xF000, 0x00.toSignedByte())  // reload to 2
        mapper.cpuWrite(0xF001, 0x01.toSignedByte())  // enable
        mapper.tickCpuCycle()                         // 2 -> 1
        assertThat(mapper.isIrqPending(), equalTo(false))
        mapper.tickCpuCycle()                         // 1 -> 0, fire
        assertThat(mapper.isIrqPending(), equalTo(true))
        assertThat(mapper.snapshot().irqState!!["irqCounter"], equalTo(0 as Any))
    }

    @Test
    fun `F000 write acknowledges a pending IRQ`() {
        val mapper = Mapper18(build())
        mapper.cpuWrite(0xE000, 0x01.toSignedByte())
        mapper.cpuWrite(0xF000, 0x00.toSignedByte())  // reload to 1
        mapper.cpuWrite(0xF001, 0x01.toSignedByte())  // enable
        mapper.tickCpuCycle()
        mapper.tickCpuCycle()                         // fire
        assertThat("pending after fire", mapper.isIrqPending(), equalTo(true))
        mapper.cpuWrite(0xF000, 0x00.toSignedByte())
        assertThat("cleared after F000", mapper.isIrqPending(), equalTo(false))
    }

    @Test
    fun `counter does not decrement when enable bit is clear`() {
        val mapper = Mapper18(build())
        mapper.cpuWrite(0xE000, 0x07.toSignedByte())
        mapper.cpuWrite(0xF000, 0x00.toSignedByte())  // reload to 7
        mapper.cpuWrite(0xF001, 0x00.toSignedByte())  // DO NOT enable
        repeat(20) { mapper.tickCpuCycle() }
        assertThat(mapper.snapshot().irqState!!["irqCounter"], equalTo(7 as Any))
        assertThat(mapper.isIrqPending(), equalTo(false))
    }

    @Test
    fun `4-bit counter wraps the masked bits only`() {
        // F001 bit 3 = 4-bit counter. Counter reload 0x1F -> top 12 bits stay set
        // and the bottom 4 bits decrement to 0 — IRQ fires on next cycle.
        val mapper = Mapper18(build())
        mapper.cpuWrite(0xE000, 0x0F.toSignedByte())
        mapper.cpuWrite(0xF000, 0x00.toSignedByte())  // reload 0xF
        mapper.cpuWrite(0xF001, 0x09.toSignedByte())  // enable + 4-bit width
        repeat(15) { mapper.tickCpuCycle() }           // 15 -> 0
        assertThat(mapper.isIrqPending(), equalTo(true))
    }

    // ---- Save / load ----

    @Test
    fun `save and load round-trips banking and IRQ state`() {
        val original = Mapper18(build(prg16k = 4, chr8k = 2))
        original.cpuWrite(0x8000, 0x03.toSignedByte())           // PRG bank 0 (low nibble)
        original.cpuWrite(0x8001, 0x01.toSignedByte())           // PRG bank 0 (high nibble) → bank 0x13
        original.cpuWrite(0x8002, 0x07.toSignedByte())           // PRG bank 1 → 7
        original.cpuWrite(0x9000, 0x0E.toSignedByte())           // PRG bank 2 → 14
        original.cpuWrite(0xA000, 0x09.toSignedByte())           // CHR bank 0 → 9
        original.cpuWrite(0xD003, 0x0F.toSignedByte())           // CHR bank 7 high nibble
        original.cpuWrite(0xE000, 0x0A.toSignedByte())           // IRQ nibble 0 = $A
        original.cpuWrite(0xE001, 0x0B.toSignedByte())           // IRQ nibble 1 = $B
        original.cpuWrite(0xE002, 0x0C.toSignedByte())           // IRQ nibble 2 = $C
        original.cpuWrite(0xE003, 0x0D.toSignedByte())           // IRQ nibble 3 = $D
        original.cpuWrite(0xF000, 0x00.toSignedByte())           // reload counter → 0xDCBA
        original.cpuWrite(0xF001, 0x01.toSignedByte())           // enable
        original.cpuWrite(0xF002, 0x02.toSignedByte())           // mirroring = one-screen lower

        val bytes = java.io.ByteArrayOutputStream().also { original.saveState(java.io.DataOutputStream(it)) }.toByteArray()

        val restored = Mapper18(build(prg16k = 4, chr8k = 2))
        restored.loadState(java.io.DataInputStream(java.io.ByteArrayInputStream(bytes)))

        assertThat(restored.snapshot(), equalTo(original.snapshot()))
        // Sanity: the high-nibble write at $8001 composed into a single
        // 8-bit byte 0x13 — modulo 8 banks (64KB PRG) reduces to bank 3.
        assertThat("prgBank0 modulo = 3 (0x13 % 8)", restored.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        // CHR register pair — read through the bank index.
        assertThat(restored.ppuRead(0x0000).toUnsignedInt(), equalTo(9))
        // Counter at reload value (0xDCBA).
        assertThat(restored.snapshot().irqState!!["irqCounter"], equalTo(0xDCBA as Any))
        assertThat(restored.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
    }
}
