package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.Region
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Region auto-detection from the iNES / NES 2.0 header and the NO-INTRO ROM name.
 * Detection priority: NES 2.0 byte 12 > iNES byte 9 (PAL bit) > filename marker > NTSC.
 */
class RegionDetectionTest {

    /**
     * @param byte7  raw value for header byte 7 (NES 2.0 marker lives in bits 2-3)
     * @param byte9  raw value for header byte 9 (iNES PAL bit is bit 0)
     * @param byte12 raw value for header byte 12 (NES 2.0 region is bits 0-1)
     */
    private fun rom(
        mapper: Int = 0,
        byte7: Int = 0,
        byte9: Int = 0,
        byte12: Int = 0
    ): ByteArray {
        val header = ByteArray(16)
        header[0] = 'N'.code.toByte(); header[1] = 'E'.code.toByte()
        header[2] = 'S'.code.toByte(); header[3] = 0x1A
        header[4] = 1  // 1x16KB PRG
        header[5] = 1  // 1x8KB CHR
        header[6] = ((mapper and 0x0F) shl 4).toByte()
        header[7] = (byte7 or (mapper and 0xF0)).toByte()
        header[9] = byte9.toByte()
        header[12] = byte12.toByte()
        return header + ByteArray(16384) + ByteArray(8192)
    }

    @Test
    fun `plain iNES with no region evidence defaults to NTSC`() {
        assertEquals(Region.NTSC, GamePak(rom()).region)
    }

    @Test
    fun `iNES byte 9 bit 0 set means PAL`() {
        assertEquals(Region.PAL, GamePak(rom(byte9 = 0x01)).region)
    }

    @Test
    fun `NES 2_0 byte 12 selects region`() {
        // byte7 bits 2-3 == 10b marks NES 2.0
        val nes20 = 0x08
        assertEquals(Region.NTSC, GamePak(rom(byte7 = nes20, byte12 = 0)).region)
        assertEquals(Region.PAL, GamePak(rom(byte7 = nes20, byte12 = 1)).region)
        assertEquals(Region.PAL, GamePak(rom(byte7 = nes20, byte12 = 3)).region)  // Dendy → PAL family
    }

    @Test
    fun `NES 2_0 region overrides a conflicting iNES PAL bit`() {
        val nes20 = 0x08
        // byte 9 says PAL, but NES 2.0 byte 12 says NTSC → NES 2.0 wins.
        assertEquals(Region.NTSC, GamePak(rom(byte7 = nes20, byte9 = 0x01, byte12 = 0)).region)
    }

    @Test
    fun `filename Europe marker implies PAL when header is silent`() {
        assertEquals(Region.PAL, GamePak(rom(), "Mr. Gimmick (Europe)").region)
    }

    @Test
    fun `filename USA marker implies NTSC`() {
        assertEquals(Region.NTSC, GamePak(rom(), "Batman - Return of the Joker (USA)").region)
    }

    @Test
    fun `header PAL bit beats an NTSC-looking filename`() {
        assertEquals(Region.PAL, GamePak(rom(byte9 = 0x01), "Some Game (USA)").region)
    }

    @Test
    fun `regionFromName ignores names without a region marker`() {
        assertNull(GamePak.regionFromName("nestest"))
    }
}
