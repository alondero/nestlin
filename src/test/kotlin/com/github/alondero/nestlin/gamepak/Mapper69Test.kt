package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Unit tests for Mapper 69 (Sunsoft FME-7).
 *
 * Test ROMs are filled so the contents identify the bank: every PRG byte equals
 * its 8KB bank index, and every CHR byte equals its 1KB bank index. That lets a
 * read assert exactly which bank is currently mapped into a window.
 */
class Mapper69Test {

    // prg16k 16KB units, chr8k 8KB units. Defaults give 8 PRG banks (8KB) and 16 CHR banks (1KB).
    private fun createTestGamePak(prg16k: Int = 4, chr8k: Int = 2): GamePak {
        val header = ByteArray(16)
        header[4] = prg16k.toByte()
        header[5] = chr8k.toByte()
        header[6] = 0x50.toByte()       // mapper low nibble = 5
        header[7] = 0x40.toByte()       // mapper high nibble = 4 -> mapper 0x45 = 69
        val prg = ByteArray(prg16k * 16384) { ((it / 0x2000) and 0xFF).toByte() }
        val chr = ByteArray(chr8k * 8192) { ((it / 0x400) and 0xFF).toByte() }
        return GamePak(header + prg + chr)
    }

    /** Invoke an FME-7 command: write the command number, then the parameter. */
    private fun Mapper.command(cmd: Int, param: Int) {
        cpuWrite(0x8000, cmd.toByte())
        cpuWrite(0xA000, param.toByte())
    }

    @Test
    fun `mapper69 is selected for header mapper 69`() {
        assertThat(createTestGamePak().createMapper() is Mapper69, equalTo(true))
    }

    // ---- PRG banking ----

    @Test
    fun `E000-FFFF is fixed to the last 8KB bank`() {
        val mapper = Mapper69(createTestGamePak())   // 8 PRG banks -> last = 7
        assertThat(mapper.cpuRead(0xE000).toUnsignedInt(), equalTo(7))
    }

    @Test
    fun `commands 9 A B switch the 8KB windows at 8000 A000 C000`() {
        val mapper = Mapper69(createTestGamePak())
        mapper.command(0x9, 3)   // $8000-$9FFF -> bank 3
        mapper.command(0xA, 5)   // $A000-$BFFF -> bank 5
        mapper.command(0xB, 2)   // $C000-$DFFF -> bank 2

        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(mapper.cpuRead(0xA000).toUnsignedInt(), equalTo(5))
        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(2))
        assertThat(mapper.cpuRead(0xE000).toUnsignedInt(), equalTo(7))  // still fixed
    }

    // ---- CHR banking ----

    @Test
    fun `commands 0 through 7 each select a 1KB CHR bank`() {
        val mapper = Mapper69(createTestGamePak())
        // Map a distinct bank into each of the eight 1KB windows.
        for (window in 0..7) {
            mapper.command(window, window + 8)   // bank window+8 (all < 16)
        }
        for (window in 0..7) {
            val addr = window * 0x400
            assertThat(mapper.ppuRead(addr).toUnsignedInt(), equalTo(window + 8))
        }
    }

    // ---- $6000-$7FFF RAM / ROM ----

    @Test
    fun `command 8 maps a ROM bank at 6000 when ROM is selected`() {
        val mapper = Mapper69(createTestGamePak())
        mapper.command(0x8, 0x02)   // bit6=0 -> ROM, bank 2
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(2))
    }

    @Test
    fun `command 8 with RAM selected and enabled allows reads and writes at 6000`() {
        val mapper = Mapper69(createTestGamePak())
        mapper.command(0x8, 0xC0)   // bit7=1 RAM enable, bit6=1 RAM select
        mapper.cpuWrite(0x6000, 0x42.toByte())
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
    }

    @Test
    fun `command 8 with RAM selected but disabled is open bus and write-protected`() {
        val mapper = Mapper69(createTestGamePak())
        mapper.command(0x8, 0xC0)               // RAM enabled
        mapper.cpuWrite(0x6000, 0x42.toByte())  // seed RAM
        mapper.command(0x8, 0x40)               // RAM selected, disabled (bit7=0)
        mapper.cpuWrite(0x6000, 0x99.toByte())  // must be ignored
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0))  // open bus
    }

    // ---- Mirroring ----

    @Test
    fun `command C selects mirroring mode`() {
        val mapper = Mapper69(createTestGamePak())
        mapper.command(0xC, 0)
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        mapper.command(0xC, 1)
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
        mapper.command(0xC, 2)
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
        mapper.command(0xC, 3)
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
    }

    // ---- IRQ (16-bit counter, decremented per CPU cycle) ----

    @Test
    fun `counter wrapping from 0 to FFFF generates an IRQ`() {
        val mapper = Mapper69(createTestGamePak())
        mapper.command(0xE, 0x05)   // counter low  = 5
        mapper.command(0xF, 0x00)   // counter high = 0  -> counter = 5
        mapper.command(0xD, 0x81)   // counter enable (bit7) + IRQ enable (bit0)

        // 5 ticks reach 0 without wrapping; the 6th wraps 0 -> FFFF.
        repeat(5) { mapper.tickCpuCycle() }
        assertThat("pending before wrap", mapper.isIrqPending(), equalTo(false))
        mapper.tickCpuCycle()
        assertThat("pending after wrap", mapper.isIrqPending(), equalTo(true))
    }

    @Test
    fun `no IRQ asserted when IRQ-enable bit is clear even though counter wraps`() {
        val mapper = Mapper69(createTestGamePak())
        mapper.command(0xE, 0x02)
        mapper.command(0xF, 0x00)
        mapper.command(0xD, 0x80)   // counter enable only, IRQ disabled
        repeat(10) { mapper.tickCpuCycle() }
        assertThat(mapper.isIrqPending(), equalTo(false))
    }

    @Test
    fun `counter does not decrement when counter-enable bit is clear`() {
        val mapper = Mapper69(createTestGamePak())
        mapper.command(0xE, 0x05)
        mapper.command(0xF, 0x00)
        mapper.command(0xD, 0x01)   // IRQ enabled, counter disabled
        repeat(100) { mapper.tickCpuCycle() }
        assertThat(mapper.isIrqPending(), equalTo(false))
        assertThat(mapper.snapshot().irqState!!["irqCounter"], equalTo(5 as Any))
    }

    @Test
    fun `writing the IRQ control register acknowledges a pending IRQ`() {
        val mapper = Mapper69(createTestGamePak())
        mapper.command(0xE, 0x01)
        mapper.command(0xF, 0x00)
        mapper.command(0xD, 0x81)
        mapper.tickCpuCycle()   // 1 -> 0
        mapper.tickCpuCycle()   // 0 -> wrap, IRQ
        assertThat(mapper.isIrqPending(), equalTo(true))
        mapper.command(0xD, 0x00)   // any write to $D acknowledges
        assertThat(mapper.isIrqPending(), equalTo(false))
    }

    @Test
    fun `counter low and high bytes compose into a 16-bit value`() {
        val mapper = Mapper69(createTestGamePak())
        mapper.command(0xE, 0x34)
        mapper.command(0xF, 0x12)
        assertThat(mapper.snapshot().irqState!!["irqCounter"], equalTo(0x1234 as Any))
    }

    // ---- $D enable bits are live, not sticky ----

    /**
     * Writing $D=0x00 (a "pure acknowledge") clears bit 7 and bit 0, so the
     * counter HALTS and the IRQ will not re-assert until the game re-enables.
     * This is hardware-accurate FME-7 behaviour (the enable bits are level, not
     * latched). An earlier sticky-latch workaround asserted the opposite as a
     * failed attempt at Mr. Gimmick #82; that game's boot is actually driven by
     * its NMI handler (which fires zero IRQs during boot — see the PPU
     * pre-render nmiOccurred fix), so the sticky behaviour was both wrong and
     * unnecessary, and would spuriously re-fire one-shot raster IRQs in other
     * FME-7 titles (Batman, Gremlins 2).
     */
    @Test
    fun `writing D=00 halts the counter and the IRQ does not re-assert`() {
        val mapper = Mapper69(createTestGamePak())
        mapper.command(0xE, 0x02)
        mapper.command(0xF, 0x00)   // counter = 2
        mapper.command(0xD, 0x81)   // counter-enable + IRQ-enable

        mapper.tickCpuCycle()       // 2 -> 1
        mapper.tickCpuCycle()       // 1 -> 0
        mapper.tickCpuCycle()       // 0 -> wrap, IRQ fires
        assertThat("IRQ fired on underflow", mapper.isIrqPending(), equalTo(true))

        mapper.command(0xD, 0x00)   // pure acknowledge: ack + clear both enables
        assertThat("IRQ acked", mapper.isIrqPending(), equalTo(false))

        // Counter must be HALTED (bit 7 cleared) ...
        val counterAfterAck = mapper.snapshot().irqState!!["irqCounter"] as Int
        repeat(50) { mapper.tickCpuCycle() }
        assertThat("counter frozen while counter-enable is clear",
            mapper.snapshot().irqState!!["irqCounter"] as Int, equalTo(counterAfterAck))

        // ... and even a full wrap-around's worth of ticks must NOT re-assert
        // the IRQ, because both the counter and the IRQ-enable bit are off.
        mapper.command(0xD, 0x80)              // re-enable counter ONLY (IRQ still masked)
        repeat(0x10000) { mapper.tickCpuCycle() }
        assertThat("IRQ stays masked with bit 0 clear even across a wrap",
            mapper.isIrqPending(), equalTo(false))
    }

    // ---- Save / load ----

    @Test
    fun `save and load round-trips banking and IRQ state`() {
        val original = Mapper69(createTestGamePak())
        original.command(0x9, 3)
        original.command(0x3, 6)
        original.command(0x8, 0xC0)
        original.cpuWrite(0x6000, 0x42.toByte())
        original.command(0xC, 1)
        original.command(0xE, 0x78)
        original.command(0xF, 0x56)
        original.command(0xD, 0x81)
        original.tickCpuCycle()

        val bytes = ByteArrayOutputStream().also { original.saveState(DataOutputStream(it)) }.toByteArray()

        val restored = Mapper69(createTestGamePak())
        restored.loadState(DataInputStream(ByteArrayInputStream(bytes)))

        assertThat(restored.snapshot(), equalTo(original.snapshot()))
        assertThat(restored.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
        assertThat(restored.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
        assertThat(restored.ppuRead(0x0C00).toUnsignedInt(), equalTo(6))
    }
}
