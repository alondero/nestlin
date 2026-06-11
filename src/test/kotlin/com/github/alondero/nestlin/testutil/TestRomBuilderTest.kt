package com.github.alondero.nestlin.testutil

import com.github.alondero.nestlin.gamepak.GamePak
import com.github.alondero.nestlin.gamepak.Header
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Round-trip tests for [TestRomBuilder]: every assertion goes THROUGH the real
 * [Header]/[GamePak] decode rather than poking at raw bytes, so the builder is
 * proven against the authoritative decoder, not against a re-statement of the
 * same encoding assumptions.
 */
class TestRomBuilderTest {

    private fun headerOf(rom: ByteArray) = Header(rom.copyOfRange(0, 16))

    @Test
    fun `mapper 0 defaults decode as plain iNES`() {
        val header = headerOf(testRom { mapper = 0 })
        assertThat(header.mapper, equalTo(0))
        assertFalse(header.isNes20)
        assertThat(header.submapper, equalTo(0))
        assertThat(header.mirroring, equalTo(Header.Mirroring.HORIZONTAL))
        assertFalse(header.hasBattery)
    }

    @Test
    fun `mapper 4 decodes from byte 6 high nibble`() {
        assertThat(headerOf(testRom { mapper = 4 }).mapper, equalTo(4))
    }

    @Test
    fun `mapper 33 splits across bytes 6 and 7`() {
        // 33 = 0x21: low nibble 1 -> byte 6 bits 4-7, high nibble 2 -> byte 7 bits 4-7
        assertThat(headerOf(testRom { mapper = 33 }).mapper, equalTo(33))
    }

    @Test
    fun `mapper 113 uses byte 7 bit 4 as mapper bit D4 not a flag`() {
        // 113 = 0x71: byte 7 high nibble = 0x7, which has bit 4 set. The PR #117
        // regression treated that bit as a "title present" flag and decoded a
        // bogus mapper number. The real Header decode must see 113.
        assertThat(headerOf(testRom { mapper = 113 }).mapper, equalTo(113))
    }

    @Test
    fun `mapper 206 with both high nibbles populated`() {
        // 206 = 0xCE — exercises bits in both byte 6 and byte 7 nibbles at once.
        assertThat(headerOf(testRom { mapper = 206 }).mapper, equalTo(206))
    }

    @Test
    fun `setting submapper forces NES 2 dot 0 and decodes mapper 16 submapper 5`() {
        val header = headerOf(testRom {
            mapper = 16
            submapper = 5
        })
        assertTrue(header.isNes20, "submapper must force the NES 2.0 signature (byte 7 bits 2-3 = 0b10)")
        assertThat(header.mapper, equalTo(16))
        assertThat(header.submapper, equalTo(5))
    }

    @Test
    fun `mapper above 255 forces NES 2 dot 0 and uses byte 8 low nibble`() {
        // 277 = 0x115: bits 8-11 = 1 (byte 8 low nibble), must not leak into submapper.
        val header = headerOf(testRom { mapper = 277 })
        assertTrue(header.isNes20)
        assertThat(header.mapper, equalTo(277))
        assertThat(header.submapper, equalTo(0))
    }

    @Test
    fun `prg and chr sizes decode through GamePak including the signed-byte hazard`() {
        // prgKb = 2048 -> header byte 4 = 128 = 0x80, which is NEGATIVE as a Kotlin
        // Byte. GamePak must still slice 2MB of PRG (it widens with toUnsignedInt;
        // a signed multiply would produce a negative bound and throw).
        val pak = testGamePak {
            prgKb = 2048
            chrKb = 32
        }
        assertThat(pak.programRom.size, equalTo(2048 * 1024))
        assertThat(pak.chrRom.size, equalTo(32 * 1024))
    }

    @Test
    fun `chrKb zero means CHR-RAM and an empty chrRom`() {
        val pak = testGamePak { chrKb = 0 }
        assertThat(pak.chrRom.size, equalTo(0))
    }

    @Test
    fun `battery and vertical mirroring bits decode`() {
        val header = headerOf(testRom {
            battery = true
            verticalMirroring = true
        })
        assertTrue(header.hasBattery)
        assertThat(header.mirroring, equalTo(Header.Mirroring.VERTICAL))
    }

    @Test
    fun `stamped prg banks land at the right file offsets`() {
        val rom = testRom {
            prgKb = 32
            chrKb = 8
            stampPrgBanks(windowKb = 8)
        }
        val pak = GamePak(rom, "stamp-test.nes")
        // 32KB PRG / 8KB windows = banks 0..3 stamped at the start of each window.
        for (bank in 0..3) {
            assertThat(pak.programRom[bank * 8 * 1024].toInt(), equalTo(bank))
        }
        // And at the raw file offsets (16-byte header first).
        assertThat(rom[16 + 8 * 1024].toInt(), equalTo(1))
    }

    @Test
    fun `stamped chr banks land at the right file offsets`() {
        val rom = testRom {
            prgKb = 16
            chrKb = 32
            stampChrBanks(windowKb = 1)
        }
        val pak = GamePak(rom, "stamp-test.nes")
        for (page in 0 until 32) {
            assertThat(pak.chrRom[page * 1024].toInt(), equalTo(page))
        }
        // Raw offset: header (16) + full PRG comes before CHR.
        assertThat(rom[16 + 16 * 1024 + 2 * 1024].toInt(), equalTo(2))
    }

    @Test
    fun `reset vector lands at header plus prgSize minus 4`() {
        val rom = testRom {
            prgKb = 128
            chrKb = 8
            resetVector(0x8000)
        }
        val prgBytes = 128 * 1024
        assertThat(rom[16 + prgBytes - 4].toInt() and 0xFF, equalTo(0x00))
        assertThat(rom[16 + prgBytes - 3].toInt() and 0xFF, equalTo(0x80))
        // The decoded PRG slice agrees.
        val pak = GamePak(rom, "vector-test.nes")
        assertThat(pak.programRom[prgBytes - 3].toInt() and 0xFF, equalTo(0x80))
    }

    @Test
    fun `buildGamePak loads through the real constructor and validation`() {
        val pak = testGamePak {
            mapper = 33
            prgKb = 128
            chrKb = 32
        }
        assertThat(pak.header.mapper, equalTo(33))
        assertThat(pak.programRom.size, equalTo(128 * 1024))
        assertThat(pak.chrRom.size, equalTo(32 * 1024))
    }

    @Test
    fun `non-unit sizes are rejected up front`() {
        val prgMessage = try {
            testRom { prgKb = 8 }
            ""
        } catch (e: IllegalArgumentException) {
            e.message ?: ""
        }
        assertTrue(prgMessage.contains("multiple of 16"), "expected PRG unit guidance, got: $prgMessage")

        val chrMessage = try {
            testRom { chrKb = 4 }
            ""
        } catch (e: IllegalArgumentException) {
            e.message ?: ""
        }
        assertTrue(chrMessage.contains("multiple of 8"), "expected CHR unit guidance, got: $chrMessage")
    }
}
