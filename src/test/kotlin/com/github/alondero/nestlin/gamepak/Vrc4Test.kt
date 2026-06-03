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
 * Unit tests for Konami VRC4 (Mappers 21, 23, 25).
 *
 * VRC4 is one chip; the three mapper numbers differ only in which CPU address
 * pins drive the chip's register-select inputs. We test the shared behaviour
 * (PRG/CHR banking, mirroring, IRQ) through Mapper25 (the most common VRC4
 * board, used by Gradius II) and exercise the variant-specific address decode
 * separately in [Vrc4VariantDecodeTest].
 *
 * Test ROMs are filled so the contents identify the bank: every PRG byte
 * equals its 8KB bank index, every CHR byte equals its 1KB bank index.
 */
class Vrc4Test {

    private fun createTestGamePak(
        mapperNumber: Int = 25,
        prg8k: Int = 8,
        chr1k: Int = 32,
        mirroring: Header.Mirroring = Header.Mirroring.VERTICAL
    ): GamePak {
        val header = ByteArray(16)
        header[4] = (prg8k / 2).toByte()        // 16KB PRG units
        header[5] = (chr1k / 8).toByte()        // 8KB CHR units
        val mirrorBit = if (mirroring == Header.Mirroring.VERTICAL) 0x01 else 0x00
        header[6] = (((mapperNumber and 0x0F) shl 4) or mirrorBit).toByte()
        header[7] = (mapperNumber and 0xF0).toByte()
        val prg = ByteArray(prg8k * 0x2000) { ((it / 0x2000) and 0xFF).toByte() }
        val chr = ByteArray(chr1k * 0x400) { ((it / 0x400) and 0xFF).toByte() }
        return GamePak(header + prg + chr)
    }

    /**
     * Issue a canonical-layout (VRC4f-style) sub-register write for a Mapper25
     * test. VRC4b picks address bits 1+0 for the sub index, so a sub of N goes
     * to address `(sub & 0x1) << 1 | (sub & 0x2) >> 1`. We do the swap here so
     * tests can read like the documentation.
     */
    private fun Mapper.vrc25write(group: Int, sub: Int, value: Int) {
        val low = (sub and 0x01) shl 1
        val high = (sub and 0x02) shr 1
        cpuWrite(group or low or high, value.toSignedByte())
    }

    // ---- Factory wiring ----

    @Test
    fun `mapper 21 is selected for header mapper 21`() {
        assertThat(createTestGamePak(mapperNumber = 21).createMapper() is Mapper21, equalTo(true))
    }

    @Test
    fun `mapper 23 is selected for header mapper 23`() {
        assertThat(createTestGamePak(mapperNumber = 23).createMapper() is Mapper23, equalTo(true))
    }

    @Test
    fun `mapper 25 is selected for header mapper 25`() {
        assertThat(createTestGamePak(mapperNumber = 25).createMapper() is Mapper25, equalTo(true))
    }

    // ---- PRG banking ----

    @Test
    fun `E000-FFFF is fixed to the last 8KB bank`() {
        val mapper = Mapper25(createTestGamePak(prg8k = 8))    // last bank = 7
        assertThat(mapper.cpuRead(0xE000).toUnsignedInt(), equalTo(7))
        assertThat(mapper.cpuRead(0xFFFF).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `default PRG bank 0 maps bank 0 at 8000`() {
        val mapper = Mapper25(createTestGamePak(prg8k = 8))
        // prg0 defaults to 0; with swap mode off this lives at $8000.
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `8000 PRG select switches the 8KB window at 8000 in swap mode 0`() {
        val mapper = Mapper25(createTestGamePak(prg8k = 8))
        mapper.vrc25write(0x8000, 0, 3)   // prg0 = bank 3
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(mapper.cpuRead(0x9FFF).toUnsignedInt(), equalTo(3))
        // $C000 is fixed to second-to-last (bank 6) in swap mode 0.
        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(6))
    }

    @Test
    fun `A000 PRG select switches the 8KB window at A000`() {
        val mapper = Mapper25(createTestGamePak(prg8k = 8))
        mapper.vrc25write(0xA000, 0, 5)   // prg1 = bank 5
        assertThat(mapper.cpuRead(0xA000).toUnsignedInt(), equalTo(5))
        assertThat(mapper.cpuRead(0xBFFF).toUnsignedInt(), equalTo(5))
    }

    @Test
    fun `PRG swap mode 1 moves prg0 from 8000 to C000`() {
        val mapper = Mapper25(createTestGamePak(prg8k = 8))
        mapper.vrc25write(0x8000, 0, 3)         // prg0 = bank 3
        mapper.vrc25write(0x9000, 2, 0x02)      // bit 1 → swap mode ON, bit 0 → WRAM disable
        // Now $8000 should be fixed to second-to-last and $C000 should be bank 3.
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(6))
        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(3))
        // $E000 still fixed to last.
        assertThat(mapper.cpuRead(0xE000).toUnsignedInt(), equalTo(7))
    }

    // ---- WRAM ($6000-$7FFF) ----

    @Test
    fun `WRAM at 6000 is gated by the wramEnabled bit and reads zero when disabled`() {
        val mapper = Mapper25(createTestGamePak())
        // Default: WRAM disabled → writes are dropped, reads return zero.
        mapper.cpuWrite(0x6000, 0x42.toSignedByte())
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0))
    }

    @Test
    fun `enabling WRAM via 9002 bit 0 makes 6000 a writable RAM page`() {
        val mapper = Mapper25(createTestGamePak())
        mapper.vrc25write(0x9000, 2, 0x01)      // sub 2 = swap+wram register; bit 0 = WRAM enable
        mapper.cpuWrite(0x6000, 0x42.toSignedByte())
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
        assertThat(mapper.batteryDirty, equalTo(true))
    }

    // ---- CHR banking ----

    @Test
    fun `each CHR window selects an independent 1KB bank`() {
        val mapper = Mapper25(createTestGamePak(chr1k = 64))   // 64 distinct 1KB banks
        // Stamp banks 8..15 into the 8 CHR windows.
        val groups = listOf(0xB000, 0xB000, 0xC000, 0xC000, 0xD000, 0xD000, 0xE000, 0xE000)
        // bank pair within each group: sub 0/1 → first bank, sub 2/3 → second.
        // sub 0 = low nibble, sub 1 = high nibble of the same bank.
        for (window in 0..7) {
            val group = groups[window]
            val pairOffset = (window % 2) * 2   // 0 or 2 — selects which bank in the group
            val bankValue = window + 8
            mapper.vrc25write(group, pairOffset + 0, bankValue and 0x0F)         // low nibble
            mapper.vrc25write(group, pairOffset + 1, (bankValue shr 4) and 0x1F) // high nibble
        }
        for (window in 0..7) {
            val addr = window * 0x400
            assertThat("window $window", mapper.ppuRead(addr).toUnsignedInt(), equalTo(window + 8))
        }
    }

    @Test
    fun `CHR high nibble adds bank bits 4-8 for 9-bit bank addresses`() {
        // With 32 1KB CHR banks (only 8KB CHR), pick a bank >= 16 to exercise
        // the high nibble. 32 banks × 1 KB = 32 KB CHR ROM total.
        val mapper = Mapper25(createTestGamePak(chr1k = 32))
        mapper.vrc25write(0xB000, 0, 0x05)       // low  nibble for chr0 = 0x5
        mapper.vrc25write(0xB000, 1, 0x01)       // high nibble for chr0 = 0x01 → bank 0x15 = 21
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(21))
    }

    @Test
    fun `CHR RAM is writable when chrRom is empty`() {
        val header = ByteArray(16)
        header[4] = 0x02.toByte()                                       // 32KB PRG
        header[5] = 0x00.toByte()                                       // no CHR ROM
        header[6] = (((25 and 0x0F) shl 4) or 0x00).toByte()            // mapper 25, horizontal
        header[7] = (25 and 0xF0).toByte()
        val prg = ByteArray(0x8000) { ((it / 0x2000) and 0xFF).toByte() }
        val mapper = Mapper25(GamePak(header + prg))
        mapper.ppuWrite(0x0000, 0x77.toSignedByte())
        assertThat(mapper.ppuRead(0x0000).toUnsignedInt(), equalTo(0x77))
    }

    // ---- Mirroring ($9000 sub 0/1) ----

    @Test
    fun `9000 mirroring control covers all four VRC4 modes`() {
        val mapper = Mapper25(createTestGamePak(mirroring = Header.Mirroring.HORIZONTAL))
        mapper.vrc25write(0x9000, 0, 0x00)
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        mapper.vrc25write(0x9000, 0, 0x01)
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
        mapper.vrc25write(0x9000, 0, 0x02)
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
        mapper.vrc25write(0x9000, 0, 0x03)
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
    }

    @Test
    fun `mirroring falls back to header when no override has been written`() {
        val v = Mapper25(createTestGamePak(mirroring = Header.Mirroring.VERTICAL))
        assertThat(v.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        val h = Mapper25(createTestGamePak(mirroring = Header.Mirroring.HORIZONTAL))
        assertThat(h.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    // ---- IRQ (VRC IRQ — 8-bit counter, scanline or cycle mode) ----

    /** Pre-load latch + start the counter at `latch` with the given mode. */
    private fun Mapper.startIrq(latch: Int, cycleMode: Boolean) {
        vrc25write(0xF000, 0, latch and 0x0F)               // latch low nibble
        vrc25write(0xF000, 1, (latch shr 4) and 0x0F)       // latch high nibble
        // Control: bit 1 = enable, bit 2 = mode. Writing also reloads counter when E=1.
        val ctrl = 0x02 or (if (cycleMode) 0x04 else 0x00)
        vrc25write(0xF000, 2, ctrl)
    }

    @Test
    fun `cycle mode IRQ fires after 256 - latch CPU cycles`() {
        val mapper = Mapper25(createTestGamePak())
        mapper.startIrq(latch = 0xFE, cycleMode = true)
        // From 0xFE the counter increments: FE, FF, then wrap → IRQ.
        // That's 2 ticks before pending (first increments to FF, second wraps).
        mapper.tickCpuCycle()                       // 0xFE → 0xFF
        assertThat("not yet pending", mapper.isIrqPending(), equalTo(false))
        mapper.tickCpuCycle()                       // 0xFF → wrap → IRQ
        assertThat("pending after wrap", mapper.isIrqPending(), equalTo(true))
    }

    @Test
    fun `cycle mode IRQ is gated by the enable bit even on wrap`() {
        val mapper = Mapper25(createTestGamePak())
        // Enable then disable (E=0 leaves counter alone but stops it ticking).
        mapper.startIrq(latch = 0xFD, cycleMode = true)
        mapper.vrc25write(0xF000, 2, 0x04)          // E=0, M=1 → counter halted
        repeat(10) { mapper.tickCpuCycle() }
        assertThat(mapper.isIrqPending(), equalTo(false))
    }

    @Test
    fun `scanline mode prescaler takes ~341 CPU cycles to clock the counter once`() {
        val mapper = Mapper25(createTestGamePak())
        mapper.startIrq(latch = 0xFF, cycleMode = false)
        // First clock of the counter wraps FF → IRQ. We need exactly enough
        // prescaler ticks to clock it once. Starting at 341 with step -3, we
        // cross zero after 114 cycles (341 / 3 rounded up).
        repeat(113) { mapper.tickCpuCycle() }
        assertThat("not yet at scanline boundary", mapper.isIrqPending(), equalTo(false))
        mapper.tickCpuCycle()                       // 114th cycle → prescaler ≤ 0, IRQ fires
        assertThat("pending after one scanline", mapper.isIrqPending(), equalTo(true))
    }

    @Test
    fun `writing F003 acknowledge clears pending and copies A to E`() {
        val mapper = Mapper25(createTestGamePak())
        mapper.startIrq(latch = 0xFF, cycleMode = true)
        mapper.tickCpuCycle()                       // FF → wrap → IRQ
        assertThat("setup: IRQ fired", mapper.isIrqPending(), equalTo(true))

        // First, set A=0 via F002 (bit 0 = A) so that ack will disable.
        // We need to keep E=1 + M=1 so the counter continues — set A=0 explicitly.
        // (Re-running startIrq would do this with A=0, but it also reloads
        //  the counter; we just want to flip A. So write control directly.)
        mapper.vrc25write(0xF000, 2, 0x06)          // E=1, M=1, A=0
        // Above also acks pending IRQ. Re-trigger one to test ack.
        mapper.tickCpuCycle()                       // counter currently 0xFF (reloaded) → wrap
        assertThat("IRQ re-fires", mapper.isIrqPending(), equalTo(true))

        mapper.vrc25write(0xF000, 3, 0)             // F003 ack: copies A→E
        assertThat("pending cleared by ack", mapper.isIrqPending(), equalTo(false))
        // E is now 0; counter shouldn't advance.
        repeat(10) { mapper.tickCpuCycle() }
        assertThat("counter halted by A=0 ack", mapper.isIrqPending(), equalTo(false))
    }

    @Test
    fun `IRQ reload uses the 8-bit latch low+high nibbles together`() {
        val mapper = Mapper25(createTestGamePak())
        mapper.vrc25write(0xF000, 0, 0x05)          // latch low = 5
        mapper.vrc25write(0xF000, 1, 0x03)          // latch high = 3 → full latch = 0x35 = 53
        mapper.vrc25write(0xF000, 2, 0x06)          // E=1, M=1 → reload from latch
        assertThat(mapper.snapshot().irqState!!["irqCounter"], equalTo(0x35 as Any))
    }

    // ---- Save / load round-trip ----

    @Test
    fun `save and load round-trips banking, WRAM, and IRQ state`() {
        val original = Mapper25(createTestGamePak())
        original.vrc25write(0x8000, 0, 3)            // prg0 = 3
        original.vrc25write(0xA000, 0, 5)            // prg1 = 5
        original.vrc25write(0x9000, 2, 0x01)         // WRAM enable
        original.cpuWrite(0x6000, 0x42.toSignedByte())
        original.vrc25write(0x9000, 0, 0x02)         // 1-screen lower mirroring
        original.vrc25write(0xB000, 0, 0x07)         // chr0 low nibble
        original.vrc25write(0xB000, 1, 0x01)         // chr0 high nibble → bank 0x17
        original.startIrq(latch = 0x80, cycleMode = true)
        original.tickCpuCycle()

        val bytes = ByteArrayOutputStream().also { original.saveState(DataOutputStream(it)) }.toByteArray()

        val restored = Mapper25(createTestGamePak())
        restored.loadState(DataInputStream(ByteArrayInputStream(bytes)))

        assertThat(restored.snapshot(), equalTo(original.snapshot()))
        assertThat(restored.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
        assertThat(restored.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(restored.cpuRead(0xA000).toUnsignedInt(), equalTo(5))
        assertThat(restored.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
        assertThat(restored.ppuRead(0x0000).toUnsignedInt(), equalTo(0x17))
    }

    @Test
    fun `loadState rejects a mismatched version byte`() {
        val mapper = Mapper25(createTestGamePak())
        // Forge a state stream with a bogus version byte (0xFF).
        val bogus = ByteArrayOutputStream().also {
            DataOutputStream(it).writeByte(0xFF)
        }.toByteArray()
        try {
            mapper.loadState(DataInputStream(ByteArrayInputStream(bogus)))
            assert(false) { "expected IncompatibleSaveStateException" }
        } catch (e: com.github.alondero.nestlin.SaveState.IncompatibleSaveStateException) {
            // expected
        }
    }
}
