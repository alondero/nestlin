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
 * Unit tests for Mapper 153 (Bandai FCG variant with 8KB PRG-RAM at $6000-$7FFF).
 *
 * Test ROMs are filled so the contents identify the bank: every PRG byte
 * equals its 16KB bank index, and every CHR byte equals its 1KB bank index.
 * That lets a read assert exactly which bank is currently mapped into a window.
 */
class Mapper153Test {

    private fun createTestGamePak(
        prg16k: Int = 4, chr8k: Int = 2, hasBattery: Boolean = false
    ): GamePak {
        val header = ByteArray(16)
        header[4] = prg16k.toByte()
        header[5] = chr8k.toByte()
        // Mapper 153: low nibble = 9, high nibble = 9. Byte 6 = 0x90, byte 7 = 0x90.
        header[6] = 0x90.toByte()
        header[7] = 0x90.toByte()
        if (hasBattery) header[6] = (header[6].toInt() or 0x02).toByte()
        val prg = ByteArray(prg16k * 16384) { ((it / 0x4000) and 0xFF).toByte() }
        val chr = ByteArray(chr8k * 8192) { ((it / 0x400) and 0xFF).toByte() }
        return GamePak(header + prg + chr)
    }

    // ---- Header dispatch ----

    @Test
    fun `mapper 153 dispatches to Mapper153`() {
        val mapper = createTestGamePak().createMapper()
        assertThat(mapper is Mapper153, equalTo(true))
    }

    // ---- PRG banking ----

    @Test
    fun `C000-FFFF is fixed to the last 16KB bank`() {
        val mapper = Mapper153(createTestGamePak(prg16k = 4))   // last = 3
        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(3))
    }

    @Test
    fun `register 8 at 6008 selects the 16KB bank at 8000-BFFF`() {
        val mapper = Mapper153(createTestGamePak(prg16k = 4))
        mapper.cpuWrite(0x6008, 0x02.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
        mapper.cpuWrite(0x6008, 0x01.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
    }

    // ---- $6000-$7FFF: D register selects PRG-RAM or PRG bank ----

    @Test
    fun `D bit 7 = 0 maps 6000-7FFF to PRG-RAM for read and write`() {
        val mapper = Mapper153(createTestGamePak(prg16k = 4, hasBattery = true))
        // $D defaults to 0 -> PRG-RAM.
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0))
        mapper.cpuWrite(0x6000, 0x42.toSignedByte())
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
    }

    @Test
    fun `D bit 7 = 1 maps 6000-7FFF to PRG bank 0-3 selected by D low bits`() {
        val mapper = Mapper153(createTestGamePak(prg16k = 4, hasBattery = true))
        mapper.cpuWrite(0x600D, 0x82.toSignedByte())   // bit 7 = 1, low bits = 2
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(2))
    }

    @Test
    fun `D bit 7 = 1 also masks writes from going to PRG-RAM`() {
        val mapper = Mapper153(createTestGamePak(prg16k = 4, hasBattery = true))
        mapper.cpuWrite(0x600D, 0x80.toSignedByte())   // bit 7 = 1 -> PRG-RAM masked
        mapper.cpuWrite(0x6000, 0x42.toSignedByte())
        // Re-enable PRG-RAM and read back -> we should see 0, not 0x42.
        mapper.cpuWrite(0x600D, 0x00.toSignedByte())
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0))
    }

    // ---- CHR banking ----

    @Test
    fun `registers 0 through 7 each select a 1KB CHR bank`() {
        val mapper = Mapper153(createTestGamePak(chr8k = 2))   // 16 CHR banks
        for (window in 0..7) {
            mapper.cpuWrite(0x6000 + window, (window + 4).toSignedByte())
        }
        for (window in 0..7) {
            val addr = window * 0x400
            assertThat(mapper.ppuRead(addr).toUnsignedInt(), equalTo(window + 4))
        }
    }

    // ---- Mirroring ----

    @Test
    fun `register 9 selects mirroring mode by lower 2 bits`() {
        val mapper = Mapper153(createTestGamePak())
        mapper.cpuWrite(0x6009, 0x00.toSignedByte())
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))
        mapper.cpuWrite(0x6009, 0x01.toSignedByte())
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
        mapper.cpuWrite(0x6009, 0x02.toSignedByte())
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))
        mapper.cpuWrite(0x6009, 0x03.toSignedByte())
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
    }

    // ---- IRQ: 16-bit counter, decremented per CPU cycle ----

    @Test
    fun `counter loads from B C latch on A write`() {
        val mapper = Mapper153(createTestGamePak())
        mapper.cpuWrite(0x600B, 0x34.toSignedByte())
        mapper.cpuWrite(0x600C, 0x12.toSignedByte())
        mapper.cpuWrite(0x600A, 0x01.toSignedByte())
        assertThat(mapper.snapshot().irqState!!["irqCounter"], equalTo(0x1234 as Any))
    }

    @Test
    fun `counter wrapping from 0 to FFFF generates an IRQ when enabled`() {
        val mapper = Mapper153(createTestGamePak())
        mapper.cpuWrite(0x600B, 0x01.toSignedByte())
        mapper.cpuWrite(0x600C, 0x00.toSignedByte())
        mapper.cpuWrite(0x600A, 0x01.toSignedByte())

        mapper.tickCpuCycle()   // 1 -> 0
        assertThat("pending before wrap", mapper.isIrqPending(), equalTo(false))
        mapper.tickCpuCycle()   // 0 -> FFFF, IRQ
        assertThat("pending after wrap", mapper.isIrqPending(), equalTo(true))
    }

    @Test
    fun `counter does not decrement when IRQ-enable bit is clear`() {
        val mapper = Mapper153(createTestGamePak())
        mapper.cpuWrite(0x600B, 0x05.toSignedByte())
        mapper.cpuWrite(0x600C, 0x00.toSignedByte())
        // Note: writing $A=0x00 also reloads from latch; counter = 0x0005.
        mapper.cpuWrite(0x600A, 0x00.toSignedByte())
        repeat(100) { mapper.tickCpuCycle() }
        assertThat(mapper.isIrqPending(), equalTo(false))
        assertThat(mapper.snapshot().irqState!!["irqCounter"], equalTo(5 as Any))
    }

    @Test
    fun `writing A with bit 0 = 0 acknowledges a pending IRQ`() {
        val mapper = Mapper153(createTestGamePak())
        mapper.cpuWrite(0x600B, 0x01.toSignedByte())
        mapper.cpuWrite(0x600C, 0x00.toSignedByte())
        mapper.cpuWrite(0x600A, 0x01.toSignedByte())
        mapper.tickCpuCycle()
        mapper.tickCpuCycle()
        assertThat(mapper.isIrqPending(), equalTo(true))
        mapper.cpuWrite(0x600A, 0x00.toSignedByte())
        assertThat(mapper.isIrqPending(), equalTo(false))
    }

    // ---- Battery-backed PRG-RAM ----

    @Test
    fun `battery-backed PRG-RAM is exposed only when header has battery flag`() {
        val noBattery = Mapper153(createTestGamePak(hasBattery = false))
        assertThat(noBattery.batteryBackedRam(), equalTo(null))

        val withBattery = Mapper153(createTestGamePak(hasBattery = true))
        assertThat(withBattery.batteryBackedRam()?.size, equalTo(0x2000))
    }

    @Test
    fun `battery-dirty flag is set on PRG-RAM write`() {
        val mapper = Mapper153(createTestGamePak(hasBattery = true))
        mapper.cpuWrite(0x6000, 0x42.toSignedByte())
        assertThat(mapper.batteryDirty, equalTo(true))
    }

    // ---- Save / load ----

    @Test
    fun `save and load round-trips banking IRQ and PRG-RAM`() {
        val original = Mapper153(createTestGamePak(prg16k = 4, chr8k = 2, hasBattery = true))
        original.cpuWrite(0x6008, 0x02.toSignedByte())
        original.cpuWrite(0x6001, 0x07.toSignedByte())
        original.cpuWrite(0x6009, 0x01.toSignedByte())
        original.cpuWrite(0x6000, 0x42.toSignedByte())   // PRG-RAM
        original.cpuWrite(0x600B, 0x78.toSignedByte())
        original.cpuWrite(0x600C, 0x56.toSignedByte())
        original.cpuWrite(0x600A, 0x01.toSignedByte())
        original.tickCpuCycle()

        val bytes = ByteArrayOutputStream().also { original.saveState(DataOutputStream(it)) }.toByteArray()

        val restored = Mapper153(createTestGamePak(prg16k = 4, chr8k = 2, hasBattery = true))
        restored.loadState(DataInputStream(ByteArrayInputStream(bytes)))

        assertThat(restored.snapshot(), equalTo(original.snapshot()))
        assertThat(restored.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
        assertThat(restored.ppuRead(0x0400).toUnsignedInt(), equalTo(7))
        assertThat(restored.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
        assertThat(restored.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
    }
}
