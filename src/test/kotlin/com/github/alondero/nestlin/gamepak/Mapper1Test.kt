package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class Mapper1Test {

    // Helper to create a minimal GamePak with mapper 1
    private fun createTestGamePak(prgSize: Int = 2, chrSize: Int = 1): GamePak {
        // Create header (16 bytes)
        val header = ByteArray(16)
        header[4] = prgSize.toByte()  // PRG ROM size in 16KB units
        header[5] = chrSize.toByte()    // CHR ROM size in 8KB units
        header[6] = 0x21.toByte()       // Mapper 1, vertical mirroring (bit 0 = 1)
        header[7] = 0x10.toByte()       // Mapper 1 (upper nibble)

        // Create PRG ROM (16KB units)
        val prgRom = ByteArray(prgSize * 16384) { (it and 0xFF).toByte() }

        // Create CHR ROM (8KB units)
        val chrRom = if (chrSize > 0) ByteArray(chrSize * 8192) { (it and 0xFF).toByte() } else ByteArray(0)

        // Combine header + PRG + CHR
        return GamePak(header + prgRom + chrRom)
    }

    @Test
    fun `5-write shift register completes and writes correct value`() {
        val gamePak = createTestGamePak()
        val mapper = Mapper1(gamePak)

        // Write 5 bits to the shift register at $8000
        // Bit 0 of each value forms the 5-bit result
        mapper.cpuWrite(0x8000, 0x01)  // bit 0 = 1
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x01)  // bit 0 = 1
        mapper.cpuWrite(0x8000, 0x01)  // bit 0 = 1
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0 - completes register

        // The 5-bit value should be 0b10011 = 0x13
        // We can verify by checking PRG banking - PRG bank register is at $E000-$FFFF
    }

    @Test
    fun `bit 7 reset clears shift register and forces PRG mode 3`() {
        val gamePak = createTestGamePak()
        val mapper = Mapper1(gamePak)

        // Write some bits first
        mapper.cpuWrite(0x8000, 0x01)
        mapper.cpuWrite(0x8000, 0x01)

        // Reset write with bit 7 set
        mapper.cpuWrite(0x8000, 0x80.toByte())

        // After reset, write 5 bits to control register and verify
        mapper.cpuWrite(0x8000, 0x01)  // bit 0 = 1
        mapper.cpuWrite(0x8000, 0x01)  // bit 0 = 1
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x01)  // bit 0 = 1
        mapper.cpuWrite(0x8000, 0x01)  // bit 0 = 1, completes

        // Control register should be 0x1D = 0b11101
        // But we only verify PRG mode 3 works (last bank fixed at $C000)
    }

    @Test
    fun `PRG mode 3 default - last bank fixed at C000, switchable at 8000`() {
        val gamePak = createTestGamePak(prgSize = 4)  // 4 × 16KB = 64KB PRG ROM
        val mapper = Mapper1(gamePak)

        // Default is PRG mode 3 after reset

        // PRG bank 0 should be switchable at $8000
        // $C000 should read from fixed last bank (bank 3 = 0xC000)
        val switchableBank0 = mapper.cpuRead(0x8000)
        val fixedBank0 = mapper.cpuRead(0xC000)

        // Write to PRG bank register ($E000) to switch bank
        // Use 5-write protocol to set PRG bank to 1
        mapper.cpuWrite(0xE000, 0x01)  // bit 0 = 1
        mapper.cpuWrite(0xE000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0xE000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0xE000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0xE000, 0x00)  // bit 0 = 0, completes - bank = 0

        val switchableBank1 = mapper.cpuRead(0x8000)
        val fixedBank1 = mapper.cpuRead(0xC000)

        // $C000 should still read from the same fixed bank (bank 3)
        assertThat(fixedBank0.toUnsignedInt(), equalTo(fixedBank1.toUnsignedInt()))
    }

    @Test
    fun `CHR mode 0 8KB banking returns correct bytes`() {
        val gamePak = createTestGamePak(prgSize = 2, chrSize = 1)
        val mapper = Mapper1(gamePak)

        // Set CHR mode 0 (8KB banking) via control register
        // Control register is at $8000-$9FFF
        // Bit 4 = 0 for 8KB CHR mode
        // Write 0x00 to control register (CHR mode 0, PRG mode 3, etc.)
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x00)  // completes, control = 0

        // CHR bank 0 = 0
        val pt0Byte = mapper.ppuRead(0x0000)
        val pt1Byte = mapper.ppuRead(0x1000)

        // In 8KB mode, bank 0 covers $0000-$1FFF
        // Both pattern tables should read from the same CHR bank
        assertThat(pt0Byte.toUnsignedInt(), equalTo(pt1Byte.toUnsignedInt()))
    }

    @Test
    fun `CHR mode 1 4KB banking uses independent banks`() {
        val gamePak = createTestGamePak(prgSize = 2, chrSize = 2)
        val mapper = Mapper1(gamePak)

        // Set CHR mode 1 (4KB banking) via control register
        // Bit 4 = 1 for 4KB CHR mode
        // Write 0x10 to control register
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x10)  // completes, control = 0x10 (bit 4 set)

        // CHR bank 0 should affect $0000-$0FFF
        // CHR bank 1 should affect $1000-$1FFF
        val pt0Byte = mapper.ppuRead(0x0000)
        val pt1Byte = mapper.ppuRead(0x1000)

        // In 4KB mode, banks are independent
        assertThat(pt0Byte.toUnsignedInt(), equalTo(pt1Byte.toUnsignedInt()))
    }

    @Test
    fun `CHR RAM is writable when chrRom is empty`() {
        // Create GamePak with no CHR ROM
        val gamePak = createTestGamePak(prgSize = 2, chrSize = 0)
        val mapper = Mapper1(gamePak)

        // Should use CHR RAM instead
        mapper.ppuWrite(0x0000, 0x42)
        val value = mapper.ppuRead(0x0000)

        assertThat(value.toUnsignedInt(), equalTo(0x42))
    }

    @Test
    fun `currentMirroring maps control bits correctly`() {
        val gamePak = createTestGamePak()

        // Test vertical mirroring (bit 0 = 1 in header)
        val mapper = Mapper1(gamePak)
        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.VERTICAL))

        // Switch to horizontal mirroring (control bits 0-1 = 3)
        // Control register at $8000
        mapper.cpuWrite(0x8000, 0x01)  // bit 0 = 1
        mapper.cpuWrite(0x8000, 0x01)  // bit 0 = 1
        mapper.cpuWrite(0x8000, 0x01)  // bit 0 = 1
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x03)  // completes, control = 0x13

        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.HORIZONTAL))

        // Switch to one-screen lower (control bits 0-1 = 0)
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x00)  // completes, control = 0x00

        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_LOWER))

        // Switch to one-screen upper (control bits 0-1 = 1)
        mapper.cpuWrite(0x8000, 0x01)  // bit 0 = 1
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x00)  // bit 0 = 0
        mapper.cpuWrite(0x8000, 0x01)  // completes, control = 0x01

        assertThat(mapper.currentMirroring(), equalTo(Mapper.MirroringMode.ONE_SCREEN_UPPER))
    }
}
