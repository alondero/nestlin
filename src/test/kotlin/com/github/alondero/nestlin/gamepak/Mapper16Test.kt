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
 * Unit tests for Mapper 16 (Bandai FCG).
 *
 * Test ROMs are filled so the contents identify the bank: every PRG byte
 * equals its 16KB bank index (mod 256), and every CHR byte equals its 1KB
 * bank index. That lets a read assert exactly which bank is currently
 * mapped into a window.
 */
class Mapper16Test {

    private fun createTestGamePak(
        prg16k: Int = 4, chr8k: Int = 2, submapper: Int = 0
    ): GamePak {
        require(prg16k in 1..16) { "prg16k must be 1..16 (16KB units)" }
        val header = ByteArray(16)
        header[0] = 'N'.code.toByte(); header[1] = 'E'.code.toByte()
        header[2] = 'S'.code.toByte(); header[3] = 0x1A.toByte()
        header[4] = prg16k.toByte()
        header[5] = chr8k.toByte()
        // Mapper 16 = 0x10. Low nibble in byte 6 high nibble, high nibble in byte 7 high nibble.
        // 16 & 0x0F = 0, shl 4 = 0x00 -> byte 6 = 0x00
        // 16 & 0xF0 = 0x10 -> byte 7 = 0x10
        header[6] = 0x00.toByte()
        header[7] = 0x10.toByte()
        if (submapper != 0) {
            // Set NES 2.0 signature in byte 7 (bits 2-3 = 0b10) and the submapper
            // in the high nibble of byte 8.
            header[7] = (header[7].toInt() or 0x08).toByte()
            header[8] = (submapper shl 4).toByte()
        }
        val prg = ByteArray(prg16k * 16384) { ((it / 0x4000) and 0xFF).toByte() }
        val chr = ByteArray(chr8k * 8192) { ((it / 0x400) and 0xFF).toByte() }
        return GamePak(header + prg + chr)
    }

    // ---- Header dispatch ----

    @Test
    fun `mapper 16 dispatches to Mapper16`() {
        val mapper = createTestGamePak().createMapper()
        assertThat(mapper is Mapper16, equalTo(true))
    }

    // ---- PRG banking ----

    @Test
    fun `C000-FFFF is fixed to the last 16KB bank`() {
        val mapper = Mapper16(createTestGamePak(prg16k = 4))   // 4 banks -> last = 3
        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(3))
        // First byte of last bank: PRG is stamped so byte N = (N / 0x4000) & 0xFF.
        // Bank 3 starts at offset 0xC000; first byte is bank 3.
        assertThat(mapper.cpuRead(0xC000).toUnsignedInt(), equalTo(3))
    }

    @Test
    fun `register 8 at 6008 selects the 16KB bank at 8000-BFFF`() {
        val mapper = Mapper16(createTestGamePak(prg16k = 4))
        mapper.cpuWrite(0x6008, 0x02.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
        mapper.cpuWrite(0x6008, 0x01.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(1))
    }

    @Test
    fun `submapper 0 accepts writes at either 6000-7FFF or 8000-FFFF`() {
        // iNES 1.0 has no submapper byte; we decode both windows for submapper 0
        // so a game that happens to place its register writes in either range
        // boots. Writes to the same register in either window target the
        // same internal state, so the last write wins.
        val mapper = Mapper16(createTestGamePak(prg16k = 4))
        mapper.cpuWrite(0x6008, 0x02.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
        // Same register via the upper window.
        mapper.cpuWrite(0x8008, 0x03.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(3))
    }

    @Test
    fun `register select uses lower 4 bits of address`() {
        // $6018 is also register 8 (the high bit is ignored).
        val mapper = Mapper16(createTestGamePak(prg16k = 4))
        mapper.cpuWrite(0x6018, 0x02.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
    }

    // ---- CHR banking ----

    @Test
    fun `registers 0 through 7 each select a 1KB CHR bank`() {
        val mapper = Mapper16(createTestGamePak(chr8k = 2))   // 16 CHR banks total
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
        val mapper = Mapper16(createTestGamePak())
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
        val mapper = Mapper16(createTestGamePak())
        mapper.cpuWrite(0x600B, 0x34.toSignedByte())
        mapper.cpuWrite(0x600C, 0x12.toSignedByte())
        mapper.cpuWrite(0x600A, 0x01.toSignedByte())  // enable + reload

        assertThat(mapper.snapshot().irqState!!["irqCounter"], equalTo(0x1234 as Any))
    }

    @Test
    fun `counter decrements per CPU cycle when enabled`() {
        val mapper = Mapper16(createTestGamePak())
        mapper.cpuWrite(0x600B, 0x05.toSignedByte())
        mapper.cpuWrite(0x600C, 0x00.toSignedByte())
        mapper.cpuWrite(0x600A, 0x01.toSignedByte())

        mapper.tickCpuCycle()   // 5 -> 4
        assertThat(mapper.snapshot().irqState!!["irqCounter"], equalTo(4 as Any))
        mapper.tickCpuCycle()   // 4 -> 3
        assertThat(mapper.snapshot().irqState!!["irqCounter"], equalTo(3 as Any))
    }

    @Test
    fun `counter wrapping from 0 to FFFF generates an IRQ when enabled`() {
        val mapper = Mapper16(createTestGamePak())
        mapper.cpuWrite(0x600B, 0x01.toSignedByte())
        mapper.cpuWrite(0x600C, 0x00.toSignedByte())
        mapper.cpuWrite(0x600A, 0x01.toSignedByte())

        mapper.tickCpuCycle()   // 1 -> 0
        assertThat("pending before wrap", mapper.isIrqPending(), equalTo(false))
        mapper.tickCpuCycle()   // 0 -> FFFF, IRQ
        assertThat("pending after wrap", mapper.isIrqPending(), equalTo(true))
    }

    @Test
    fun `counter wrap without IRQ-enable bit does not assert IRQ`() {
        val mapper = Mapper16(createTestGamePak())
        mapper.cpuWrite(0x600B, 0x01.toSignedByte())
        mapper.cpuWrite(0x600C, 0x00.toSignedByte())
        mapper.cpuWrite(0x600A, 0x00.toSignedByte())  // IRQ-enable bit clear
        repeat(10) { mapper.tickCpuCycle() }
        assertThat(mapper.isIrqPending(), equalTo(false))
    }

    @Test
    fun `counter does not decrement when IRQ-enable bit is clear`() {
        val mapper = Mapper16(createTestGamePak())
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
        val mapper = Mapper16(createTestGamePak())
        mapper.cpuWrite(0x600B, 0x01.toSignedByte())
        mapper.cpuWrite(0x600C, 0x00.toSignedByte())
        mapper.cpuWrite(0x600A, 0x01.toSignedByte())
        mapper.tickCpuCycle()   // 1 -> 0
        mapper.tickCpuCycle()   // 0 -> FFFF, IRQ
        assertThat(mapper.isIrqPending(), equalTo(true))
        mapper.cpuWrite(0x600A, 0x00.toSignedByte())
        assertThat(mapper.isIrqPending(), equalTo(false))
    }

    // ---- Save / load ----

    @Test
    fun `save and load round-trips banking and IRQ state`() {
        val original = Mapper16(createTestGamePak(prg16k = 4, chr8k = 2))
        original.cpuWrite(0x6008, 0x02.toSignedByte())           // PRG bank
        original.cpuWrite(0x6001, 0x07.toSignedByte())           // CHR bank 1
        original.cpuWrite(0x6009, 0x01.toSignedByte())           // horiz mirroring
        original.cpuWrite(0x600B, 0x78.toSignedByte())
        original.cpuWrite(0x600C, 0x56.toSignedByte())
        original.cpuWrite(0x600A, 0x01.toSignedByte())           // enable
        original.tickCpuCycle()

        val bytes = ByteArrayOutputStream().also { original.saveState(DataOutputStream(it)) }.toByteArray()

        val restored = Mapper16(createTestGamePak(prg16k = 4, chr8k = 2))
        restored.loadState(DataInputStream(ByteArrayInputStream(bytes)))

        assertThat(restored.snapshot(), equalTo(original.snapshot()))
        assertThat(restored.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
        assertThat(restored.ppuRead(0x0400).toUnsignedInt(), equalTo(7))
        assertThat(restored.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))
    }

    // ---- Submapper 4: registers at $6000-$7FFF, direct IRQ writes ----

    @Test
    fun `submapper 4 uses 6000-7FFF as register window`() {
        val mapper = Mapper16(createTestGamePak(submapper = 4), submapper = 4)
        // $6008 is register 8: select PRG bank 2.
        mapper.cpuWrite(0x6008, 0x02.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
        // $8008 is no longer the register window — it just writes to PRG
        // (no effect here since PRG is ROM).
        mapper.cpuWrite(0x8008, 0x03.toSignedByte())
        assertThat(mapper.cpuRead(0x8000).toUnsignedInt(), equalTo(2))
    }

    @Test
    fun `submapper 4 writes B C directly to counter (no latch)`() {
        val mapper = Mapper16(createTestGamePak(submapper = 4), submapper = 4)
        mapper.cpuWrite(0x600B, 0x34.toSignedByte())
        // Counter should reflect the low byte immediately (no $A reload needed).
        assertThat(mapper.snapshot().irqState!!["irqCounter"] as Int and 0xFF, equalTo(0x34))
        mapper.cpuWrite(0x600C, 0x12.toSignedByte())
        assertThat(mapper.snapshot().irqState!!["irqCounter"], equalTo(0x1234 as Any))
    }

    // ---- Submapper 5: LZ93D50 with EEPROM ----

    @Test
    fun `submapper 5 enables EEPROM via D register`() {
        val mapper = Mapper16(createTestGamePak(submapper = 5), submapper = 5)
        // 24Cxx device-select byte is 1010_000_R/W: 0xA0 for write, 0xA1 for read.
        // Write 0xAB to EEPROM address 0x10.
        i2cStart(mapper)
        i2cSend(mapper, 0xA0)                       // device select + write
        i2cSend(mapper, 0x10)                       // word address
        i2cSend(mapper, 0xAB)                       // data
        i2cStop(mapper)

        // Random read it back.
        i2cStart(mapper)
        i2cSend(mapper, 0xA0)                       // device select + write (sets address pointer)
        i2cSend(mapper, 0x10)                       // word address
        i2cStart(mapper)                            // repeated start
        i2cSend(mapper, 0xA1)                       // device select + read
        val read = i2cRecv(mapper)
        i2cStop(mapper)

        assertThat(read, equalTo(0xAB))
    }

    @Test
    fun `submapper 5 EEPROM reads back a value with the MSB clear`() {
        // Regression for the read-path off-by-one: a value whose bit 7 is 0
        // (e.g. 0x42) was previously read back as 0xC2 because the first data
        // bit was dropped and the MSB forced high. 0xAB (bit 7 set) hid the bug.
        val mapper = Mapper16(createTestGamePak(submapper = 5), submapper = 5)
        i2cStart(mapper)
        i2cSend(mapper, 0xA0)                       // device select + write
        i2cSend(mapper, 0x20)                       // word address
        i2cSend(mapper, 0x42)                       // data (MSB clear)
        i2cStop(mapper)

        i2cStart(mapper)
        i2cSend(mapper, 0xA0)                       // device select + write (sets address pointer)
        i2cSend(mapper, 0x20)                       // word address
        i2cStart(mapper)                            // repeated start
        i2cSend(mapper, 0xA1)                       // device select + read
        val read = i2cRecv(mapper)
        i2cStop(mapper)

        assertThat(read, equalTo(0x42))
    }

    /** SDA falls while SCL is high -> start condition. */
    private fun i2cStart(mapper: Mapper16) {
        mapper.cpuWrite(EEPROM_REG, 0x60.toSignedByte())  // SCL=1, SDA=1 (idle)
        mapper.cpuWrite(EEPROM_REG, 0x20.toSignedByte())  // SCL=1, SDA=0 -> start
    }

    /** SDA rises while SCL is high -> stop condition. */
    private fun i2cStop(mapper: Mapper16) {
        mapper.cpuWrite(EEPROM_REG, 0x00.toSignedByte())  // SCL=0
        mapper.cpuWrite(EEPROM_REG, 0x20.toSignedByte())  // SCL=1, SDA=0
        mapper.cpuWrite(EEPROM_REG, 0x60.toSignedByte())  // SCL=1, SDA=1 -> stop
    }

    /** Master transmits one byte (8 data + 1 ACK clock where master reads). */
    private fun i2cSend(mapper: Mapper16, value: Int) {
        for (bit in 7 downTo 0) {
            val sda = (value shr bit) and 1
            mapper.cpuWrite(EEPROM_REG, (sda * 0x40).toSignedByte())           // SCL low, drive SDA
            mapper.cpuWrite(EEPROM_REG, (0x20 + sda * 0x40).toSignedByte())   // SCL high
        }
        // ACK clock: master releases SDA (let slave drive), reads it on rising.
        mapper.cpuWrite(EEPROM_REG, 0x00.toSignedByte())                       // SCL low, SDA released
        mapper.cpuWrite(EEPROM_REG, 0x20.toSignedByte())                       // SCL high
    }

    /** Master receives one byte (8 data + 1 NACK clock). */
    private fun i2cRecv(mapper: Mapper16): Int {
        var byte = 0
        for (bit in 7 downTo 0) {
            mapper.cpuWrite(EEPROM_REG, 0x00.toSignedByte())                   // SCL low
            mapper.cpuWrite(EEPROM_REG, 0x20.toSignedByte())                   // SCL high; slave drives SDA
            val sda = (mapper.cpuRead(EEPROM_REG).toUnsignedInt() shr 6) and 1
            byte = (byte shl 1) or sda
        }
        // NACK clock: master drives SDA high (the ACK_AFTER_RX first phase).
        mapper.cpuWrite(EEPROM_REG, 0x40.toSignedByte())                       // SCL low, SDA=1
        mapper.cpuWrite(EEPROM_REG, 0x60.toSignedByte())                       // SCL high, SDA=1
        return byte
    }

    /**
     * LZ93D50 (submapper 5) puts the $D register at $8000-$FFFF, not $6000-$7FFF.
     * We use $800D as the canonical EEPROM port address.
     */
    private val EEPROM_REG = 0x800D
}
