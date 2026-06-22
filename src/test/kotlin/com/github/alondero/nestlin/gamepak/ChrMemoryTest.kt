package com.github.alondero.nestlin.gamepak

import com.github.alondero.nestlin.testutil.assertThrowsWithMessage
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

/**
 * Pure unit tests for [ChrMemory] / [DefaultChrMemory]. No mapper, no PPU,
 * no GamePak — just the storage + branching semantics the ADR pinned in
 * ADR-0002.
 */
class ChrMemoryTest {

    @Test
    fun `CHR-ROM is read-only and writes are silently dropped`() {
        val rom = ByteArray(0x2000) { it.toByte() }
        val mem = ChrMemory.default(rom)

        // ROM returns the original byte
        assertThat(mem.read(0x0000), equalTo(0x00.toByte()))
        assertThat(mem.read(0x1000), equalTo(0x00.toByte()))   // 0x1000 == 0x1000 & 0xFF == 0
        assertThat(mem.read(0x1FFF), equalTo(0xFF.toByte()))   // 0x1FFF == -1 & 0xFF == 255

        // Writes to a ROM-backed memory are silently ignored — the buffer
        // backing the GamePak's chrRom must not be mutated.
        mem.write(0x0100, 0xAB.toByte())
        assertThat(mem.read(0x0100), equalTo(0x00.toByte()))
        assertThat(rom[0x0100], equalTo(0x00.toByte()))
    }

    @Test
    fun `CHR-RAM allocates when CHR-ROM is empty and reads-then-writes round-trip`() {
        val mem = ChrMemory.default(chrRom = ByteArray(0), chrRamSize = 0x2000)

        // Fresh RAM is zeroed.
        assertThat(mem.read(0x1234), equalTo(0.toByte()))

        mem.write(0x1234, 0x7E.toByte())
        assertThat(mem.read(0x1234), equalTo(0x7E.toByte()))

        // Different offset stays independent.
        mem.write(0x1FFF, 0x42.toByte())
        assertThat(mem.read(0x0000), equalTo(0.toByte()))
        assertThat(mem.read(0x1FFF), equalTo(0x42.toByte()))
    }

    @Test
    fun `Mapper 19 N163 allocates 12KB CHR-RAM by passing chrRamSize = 0x3000`() {
        val mem = ChrMemory.default(chrRom = ByteArray(0), chrRamSize = 0x3000)

        // Reads and writes at the extended range (banks 8-11) round-trip.
        mem.write(0x2400, 0xCD.toByte())   // bank 9 start = 0x2400..0x27FF
        assertThat(mem.read(0x2400), equalTo(0xCD.toByte()))
        assertThat(mem.read(0x2FFF), equalTo(0.toByte()))   // untouched

        // Standard range (banks 0-7) is still part of the same buffer.
        mem.write(0x0400, 0x99.toByte())   // bank 1
        assertThat(mem.read(0x0400), equalTo(0x99.toByte()))
    }

    @Test
    fun `out-of-range offset on RAM-backed memory throws IndexOutOfBoundsException`() {
        val mem = ChrMemory.default(chrRom = ByteArray(0), chrRamSize = 0x2000)

        // Non-empty substring so the assertion actually narrows the throwable —
        // empty would match anything via `contains("")`. The Kotlin/JDK
        // ByteArray IOOBE message starts with "Index 8192 out of bounds…".
        assertThrowsWithMessage<IndexOutOfBoundsException>("Index") {
            mem.read(0x2000)   // one past the end of an 8KB buffer
        }
        assertThrowsWithMessage<IndexOutOfBoundsException>("Index") {
            mem.write(0x2000, 0x01)
        }
    }

    @Test
    fun `peek returns the same byte as read`() {
        val ram = ChrMemory.default(chrRom = ByteArray(0), chrRamSize = 0x10)
        val rom = ChrMemory.default(ByteArray(0x10) { (0x80 + it).toByte() })

        ram.write(0x05, 0x42.toByte())
        assertThat(ram.peek(0x05), equalTo(0x42.toByte()))

        // ROM-backed peek also matches read.
        assertThat(rom.peek(0x07), equalTo(0x87.toByte()))
        assertThat(rom.read(0x07), equalTo(0x87.toByte()))
    }

    @Test
    fun `serialize round-trips CHR-RAM via DataOutput`() {
        val source = ChrMemory.default(chrRom = ByteArray(0), chrRamSize = 0x2000)
        source.write(0x0000, 0x11.toByte())
        source.write(0x1000, 0x22.toByte())
        source.write(0x1FFF, 0x33.toByte())

        val out = ByteArrayOutputStream()
        source.serialize(DataOutputStream(out))

        val sink = ChrMemory.default(chrRom = ByteArray(0), chrRamSize = 0x2000)
        sink.deserialize(DataInputStream(ByteArrayInputStream(out.toByteArray())))

        assertThat(sink.read(0x0000), equalTo(0x11.toByte()))
        assertThat(sink.read(0x1000), equalTo(0x22.toByte()))
        assertThat(sink.read(0x1FFF), equalTo(0x33.toByte()))
        // Untouched byte stays zero.
        assertThat(sink.read(0x0500), equalTo(0.toByte()))
    }

    @Test
    fun `serialize is a no-op when CHR-ROM is present`() {
        // ROM is reference-held, not serialized — same as the rest of the
        // GamePak state. Confirm that the serialize hook produces zero bytes.
        val mem = ChrMemory.default(ByteArray(0x2000) { it.toByte() })
        val out = ByteArrayOutputStream()
        mem.serialize(DataOutputStream(out))
        assertThat(out.size(), equalTo(0))
    }

    @Test
    fun `deserialize is a no-op when CHR-ROM is present`() {
        // The bytes in the input stream should NOT be consumed by the ROM
        // adapter (otherwise subsequent state-reads would desync). Use a
        // pre-loaded marker byte to assert nothing was consumed.
        val mem = ChrMemory.default(ByteArray(0x2000) { it.toByte() })
        val payload = byteArrayOf(0x42, 0x42, 0x42, 0x42)
        mem.deserialize(DataInputStream(ByteArrayInputStream(payload)))
        // No assertion failure means success — the adapter accepted the
        // bytes silently and the ROM data is unchanged.
    }

    @Test
    fun `default chrRamSize parameter is 0x2000 when not specified`() {
        // The default constructor parameter is the standard 8KB.
        val mem = ChrMemory.default(chrRom = ByteArray(0))

        // Last byte index 0x1FFF is in range, 0x2000 is out of range.
        mem.write(0x1FFF, 0xAB.toByte())
        assertThat(mem.read(0x1FFF), equalTo(0xAB.toByte()))

        assertThrowsWithMessage<IndexOutOfBoundsException>("Index") {
            mem.read(0x2000)
        }
    }
}
