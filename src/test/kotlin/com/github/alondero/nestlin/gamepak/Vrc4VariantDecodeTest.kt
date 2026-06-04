package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.toSignedByte
import com.github.alondero.nestlin.toUnsignedInt
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Tests that each VRC4 mapper variant correctly decodes the sub-register from
 * its specific address-pin layout.
 *
 * VRC4 boards use one of these submapper layouts:
 *
 * | Mapper | Variant | A0→sub-bit | A1→sub-bit | Addresses for sub 0/1/2/3      |
 * |--------|---------|------------|------------|--------------------------------|
 * | 21     | VRC4a   | A1, A2     | (none)     | $8000/$8002/$8004/$8006        |
 * | 21     | VRC4c   | A6, A7     | (none)     | $8000/$8040/$8080/$80C0        |
 * | 23     | VRC4f   | A0, A1     | (none)     | $8000/$8001/$8002/$8003        |
 * | 23     | VRC4e   | A2, A3     | (none)     | $8000/$8004/$8008/$800C        |
 * | 25     | VRC4b   | A1, A0     | swapped    | $8000/$8002/$8001/$8003        |
 * | 25     | VRC4d   | A3, A2     | swapped    | $8000/$8008/$8004/$800C        |
 *
 * We verify the decode indirectly: each variant's canonical addresses for
 * sub-registers 0, 1, 2, 3 must drive the corresponding internal effect. We
 * use the mirroring control register at $9000 (sub 0 / sub 1) and the PRG
 * mode register at $9002 (sub 2 / sub 3) as observable effects, since they
 * each toggle a distinct piece of mapper state.
 */
class Vrc4VariantDecodeTest {

    private fun createTestGamePak(mapperNumber: Int): GamePak {
        val header = ByteArray(16)
        header[4] = 4.toByte()                                  // 64KB PRG
        header[5] = 1.toByte()                                  // 8KB CHR
        header[6] = (((mapperNumber and 0x0F) shl 4)).toByte()  // horizontal default
        header[7] = (mapperNumber and 0xF0).toByte()
        val prg = ByteArray(0x10000) { ((it / 0x2000) and 0xFF).toByte() }
        val chr = ByteArray(0x2000) { ((it / 0x400) and 0xFF).toByte() }
        return GamePak(header + prg + chr)
    }

    /**
     * Drive a mirroring write to $9000 + offset and confirm the mirroring
     * mode changes to the expected value. The offset is whatever sub-register
     * address the variant uses for sub 0 (i.e. mirroring control).
     */
    private fun assertMirroringWrite(mapper: Mapper, sub0Offset: Int, value: Int, expected: Mapper.MirroringMode) {
        mapper.cpuWrite(0x9000 + sub0Offset, value.toSignedByte())
        assertThat(mapper.currentMirroring(), equalTo(expected))
    }

    // ---- Mapper 21 (VRC4a + VRC4c) ----

    @Test
    fun `mapper 21 VRC4a decodes sub 0 at A1=0, A2=0`() {
        val mapper = Mapper21(createTestGamePak(21))
        // $9000 in VRC4a is sub 0 (mirroring). Setting mirroring=2 → 1-screen lower.
        assertMirroringWrite(mapper, sub0Offset = 0x00, value = 0x02, expected = Mapper.MirroringMode.ONE_SCREEN_LOWER)
    }

    @Test
    fun `mapper 21 VRC4a decodes sub 2 at A2=1 (address 9004)`() {
        val mapper = Mapper21(createTestGamePak(21))
        // VRC4a: A2 → sub bit 1. $9004 has A2=1 → sub 2 (PRG mode + WRAM).
        // bit 0 = WRAM enable; verify by enabling WRAM and confirming a write sticks.
        mapper.cpuWrite(0x9004, 0x01.toSignedByte())            // sub 2: WRAM enable
        mapper.cpuWrite(0x6000, 0x42.toSignedByte())
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
    }

    @Test
    fun `mapper 21 VRC4c decodes sub 2 at A7=1 (address 9080)`() {
        val mapper = Mapper21(createTestGamePak(21))
        // VRC4c: A7 → sub bit 1. $9080 has A7=1 → sub 2 (PRG mode + WRAM).
        mapper.cpuWrite(0x9080, 0x01.toSignedByte())            // sub 2: WRAM enable
        mapper.cpuWrite(0x6000, 0x99.toSignedByte())
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0x99))
    }

    // ---- Mapper 23 (VRC4e + VRC4f) ----

    @Test
    fun `mapper 23 VRC4f decodes sub 1 at A0=1 (address 9001)`() {
        val mapper = Mapper23(createTestGamePak(23))
        // VRC4f: A0 → sub bit 0. $9001 has A0=1 → sub 1 (still mirroring).
        assertMirroringWrite(mapper, sub0Offset = 0x01, value = 0x03, expected = Mapper.MirroringMode.ONE_SCREEN_UPPER)
    }

    @Test
    fun `mapper 23 VRC4f decodes sub 2 at A1=1 (address 9002)`() {
        val mapper = Mapper23(createTestGamePak(23))
        // VRC4f: A1 → sub bit 1. $9002 → sub 2 (PRG mode + WRAM).
        mapper.cpuWrite(0x9002, 0x01.toSignedByte())            // sub 2: WRAM enable
        mapper.cpuWrite(0x6000, 0x42.toSignedByte())
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
    }

    @Test
    fun `mapper 23 VRC4e decodes sub 2 at A3=1 (address 9008)`() {
        val mapper = Mapper23(createTestGamePak(23))
        // VRC4e: A3 → sub bit 1. $9008 → sub 2 (PRG mode + WRAM).
        mapper.cpuWrite(0x9008, 0x01.toSignedByte())            // sub 2: WRAM enable
        mapper.cpuWrite(0x6000, 0x77.toSignedByte())
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0x77))
    }

    // ---- Mapper 25 (VRC4b + VRC4d, both with swapped low bits) ----

    @Test
    fun `mapper 25 VRC4b decodes sub 1 at A1=1 (address 9002)`() {
        val mapper = Mapper25(createTestGamePak(25))
        // VRC4b SWAPS: A1 → sub bit 0 (low), A0 → sub bit 1 (high).
        // $9002 has A1=1, A0=0 → sub bit 0 set → sub 1 (mirroring).
        assertMirroringWrite(mapper, sub0Offset = 0x02, value = 0x02, expected = Mapper.MirroringMode.ONE_SCREEN_LOWER)
    }

    @Test
    fun `mapper 25 VRC4b decodes sub 2 at A0=1 (address 9001)`() {
        val mapper = Mapper25(createTestGamePak(25))
        // VRC4b SWAPPED: $9001 has A0=1, A1=0 → sub bit 1 set → sub 2 (PRG/WRAM).
        mapper.cpuWrite(0x9001, 0x01.toSignedByte())            // sub 2: WRAM enable
        mapper.cpuWrite(0x6000, 0x42.toSignedByte())
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0x42))
    }

    @Test
    fun `mapper 25 VRC4d decodes sub 2 at A2=1 (address 9004)`() {
        val mapper = Mapper25(createTestGamePak(25))
        // VRC4d SWAPPED: A3 → sub bit 0, A2 → sub bit 1.
        // $9004 has A2=1, A3=0 → sub bit 1 set → sub 2 (PRG/WRAM).
        mapper.cpuWrite(0x9004, 0x01.toSignedByte())            // sub 2: WRAM enable
        mapper.cpuWrite(0x6000, 0x77.toSignedByte())
        assertThat(mapper.cpuRead(0x6000).toUnsignedInt(), equalTo(0x77))
    }

    @Test
    fun `mapper 25 VRC4d decodes sub 1 at A3=1 (address 9008)`() {
        val mapper = Mapper25(createTestGamePak(25))
        // VRC4d SWAPPED: $9008 has A3=1, A2=0 → sub bit 0 set → sub 1 (mirroring).
        assertMirroringWrite(mapper, sub0Offset = 0x08, value = 0x03, expected = Mapper.MirroringMode.ONE_SCREEN_UPPER)
    }
}
