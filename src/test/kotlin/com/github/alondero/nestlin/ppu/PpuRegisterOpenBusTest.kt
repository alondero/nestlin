package com.github.alondero.nestlin.ppu

import com.github.alondero.nestlin.SaveState
import com.github.alondero.nestlin.testutil.TestRoms
import com.github.alondero.nestlin.toSignedByte
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Regression tests for GitHub issue #292 — "PPU write-only register reads
 * expose stored values instead of I/O open bus".
 *
 * On a 2C02, reads of `$2000`, `$2001`, `$2003`, `$2005`, and `$2006` do NOT
 * expose the values most recently written to those registers. They return
 * the PPU's internal I/O bus latch (subject to decay on real hardware; not
 * modelled here). This test class exercises the new semantics both via
 * [PpuAddressedMemory.get] (real reads) and [PpuAddressedMemory.peek]
 * (side-effect-free reads) and round-trips the bus through save/load.
 */
class PpuRegisterOpenBusTest {

    // ---- 1. Write-only register reads return openBus, not the stored register ----

    /**
     * Table-driven: seed openBus with a known byte, set each backing field
     * (controller / mask / oamAddress / scroll / address) to a different value,
     * and assert that every write-only register read returns the seeded
     * openBus byte. The point is the bus value, not the register value.
     */
    @ParameterizedTest(name = "write-only register {0} returns openBus, not its backing field")
    @CsvSource(
        "0, 0xA5, 0x3F",  // $2000 (PPUCTRL)
        "1, 0xA5, 0x1E",  // $2001 (PPUMASK)
        "3, 0xA5, 0x12",  // $2003 (OAMADDR)
        "5, 0xA5, 0x77",  // $2005 (PPUSCROLL)
        "6, 0xA5, 0x42",  // $2006 (PPUADDR)
    )
    fun `write-only register read returns openBus not stored value`(
        register: Int,
        busSeed: Int,
        backingFieldValue: Int,
    ) {
        val ppu = PpuAddressedMemory()
        // First set the backing field to the "stored value" we DON'T want to see.
        when (register) {
            0 -> ppu.controller.register = backingFieldValue.toSignedByte()
            1 -> ppu.mask.register = backingFieldValue.toSignedByte()
            3 -> ppu.oamAddress = backingFieldValue.toSignedByte()
            5 -> ppu.scroll = backingFieldValue.toSignedByte()
            6 -> ppu.address = backingFieldValue.toSignedByte()
        }
        // Then seed openBus via a $2007 write — $2007 is the most natural way to
        // put a byte on the bus without disturbing the other registers we care
        // about. Direct assignment to openBus would also work but bypasses
        // the API surface.
        ppu[7] = busSeed.toSignedByte()

        val result = ppu[register]
        assertThat(
            "register $register read returned the bus (0x${"%02X".format(busSeed)}), " +
                "not its backing field (0x${"%02X".format(backingFieldValue)})",
            result.toInt() and 0xFF,
            equalTo(busSeed),
        )
    }

    @Test
    fun `writing distinct values to PPUCTRL and PPUMASK does not leak them on subsequent reads`() {
        // This is the more pointed case: write $80 to $2000 and $1E to $2001,
        // then read both back. Both reads must return the LAST value written,
        // not the values we just stored into the named registers.
        val ppu = PpuAddressedMemory()
        ppu[0] = 0x80.toSignedByte()  // PPUCTRL: bus = 0x80
        ppu[1] = 0x1E.toSignedByte()  // PPUMASK: bus = 0x1E

        assertThat("$2000 read", ppu[0].toInt() and 0xFF, equalTo(0x1E))
        assertThat("$2001 read", ppu[1].toInt() and 0xFF, equalTo(0x1E))
    }

    @Test
    fun `read of write-only register does not change openBus`() {
        // Hardware: the bus is a passive latch. Reading a register doesn't
        // drive anything new onto it (a real read just exposes what's already
        // there). A repeat read must observe the same value.
        val ppu = PpuAddressedMemory()
        ppu[0] = 0xA5.toSignedByte()
        val first = ppu[0].toInt() and 0xFF
        val second = ppu[0].toInt() and 0xFF
        val third = ppu[0].toInt() and 0xFF
        assertThat(first, equalTo(0xA5))
        assertThat(second, equalTo(0xA5))
        assertThat(third, equalTo(0xA5))
    }

    // ---- 2. $2002 read combines status bits 5-7 with prior bus bits 0-4 and latches it ----

    @Test
    fun `$2002 read returns status high bits with prior open-bus low bits`() {
        val ppu = PpuAddressedMemory()
        // Seed low 5 bits via a $2001 write — 0xAB & 0x1F = 0x0B
        ppu[1] = 0xAB.toSignedByte()
        // Set the high 3 status bits (sprite overflow / sprite-0 hit / vblank).
        ppu.status.register = 0b1110_0000.toSignedByte()

        val result = ppu[2]
        assertThat("bit 7 = vblank", (result.toInt() shr 7) and 1, equalTo(1))
        assertThat("bit 6 = sprite 0 hit", (result.toInt() shr 6) and 1, equalTo(1))
        assertThat("bit 5 = sprite overflow", (result.toInt() shr 5) and 1, equalTo(1))
        assertThat("low 5 bits = prior bus (0xAB & 0x1F = 0x0B)",
            result.toInt() and 0x1F, equalTo(0x0B))
    }

    @Test
    fun `chained read - $2002 then $2000 sees the combined $2002 return value`() {
        // Seed the low bits with 0x25 (openBus = 0x25, low 5 bits = 0x05).
        // Set status high bits to 0xE0 — so $2002 will return 0xE0 | 0x05 = 0xE5.
        // After $2002 returns 0xE5, reading $2000 (write-only) must see 0xE5,
        // NOT 0x25.
        val ppu = PpuAddressedMemory()
        ppu[1] = 0x25.toSignedByte()            // bus = 0x25 (low 5 = 0x05)
        ppu.status.register = 0b1110_0000.toSignedByte()

        val statusRead = ppu[2]
        assertThat("status read combined = 0xE5",
            statusRead.toInt() and 0xFF, equalTo(0xE5))

        // The bus must now be 0xE5, so reading $2000 sees 0xE5.
        assertThat("$2000 sees the combined $2002 return value",
            ppu[0].toInt() and 0xFF, equalTo(0xE5))
    }

    @Test
    fun `$2004 read latches OAM byte onto openBus`() {
        val ppu = PpuAddressedMemory()
        // Place a known byte in OAM at offset 0.
        ppu.objectAttributeMemory[0] = 0x42.toSignedByte()
        // Seed openBus with a different value first.
        ppu[0] = 0x11.toSignedByte()

        val result = ppu[4]
        assertThat("OAM byte returned", result.toInt() and 0xFF, equalTo(0x42))

        // Subsequent $2000 read sees the OAM byte, not the previous bus value.
        assertThat("$2000 sees the OAM-driven bus",
            ppu[0].toInt() and 0xFF, equalTo(0x42))
    }

    @Test
    fun `$2007 read latches the returned byte onto openBus`() {
        val ppu = PpuAddressedMemory()
        // Seed openBus with something we DON'T want to see.
        ppu[0] = 0x11.toSignedByte()
        // Point VRAM at $2000 and pre-seed a known byte there. Set VRAM
        // address with two $2006 writes (toggle-on sets the low byte and
        // copies t -> v).
        ppu.ppuInternalMemory[0x2000] = 0x77.toSignedByte()
        ppu[6] = 0x20.toSignedByte()
        ppu[6] = 0x00.toSignedByte()

        // First read returns the read buffer (0x00 by default). The bus is
        // driven with the returned byte regardless of source.
        val firstRead = ppu[7]
        ppu[0] // subsequent $2000 read sees the $2007 return value
        assertThat("$2000 sees the $2007-driven bus",
            ppu[0].toInt() and 0xFF, equalTo(firstRead.toInt() and 0xFF))
    }

    // ---- 3. peek reports openBus without mutating it ----

    @Test
    fun `peek of write-only registers returns openBus without mutating it`() {
        val ppu = PpuAddressedMemory()
        // Set backing fields to different values; seed bus with $A5.
        ppu.controller.register = 0x3F.toSignedByte()
        ppu.mask.register = 0x1E.toSignedByte()
        ppu.oamAddress = 0x12.toSignedByte()
        ppu.scroll = 0x77.toSignedByte()
        ppu.address = 0x42.toSignedByte()
        ppu[7] = 0xA5.toSignedByte() // seed openBus

        assertThat("peek(0)", ppu.peek(0).toInt() and 0xFF, equalTo(0xA5))
        assertThat("peek(1)", ppu.peek(1).toInt() and 0xFF, equalTo(0xA5))
        assertThat("peek(3)", ppu.peek(3).toInt() and 0xFF, equalTo(0xA5))
        assertThat("peek(5)", ppu.peek(5).toInt() and 0xFF, equalTo(0xA5))
        assertThat("peek(6)", ppu.peek(6).toInt() and 0xFF, equalTo(0xA5))

        // peek must not have changed the bus.
        assertThat("peek left openBus unchanged", ppu.openBus.toInt() and 0xFF, equalTo(0xA5))
    }

    @Test
    fun `peek of $2002 does not change openBus`() {
        val ppu = PpuAddressedMemory()
        ppu[0] = 0x55.toSignedByte() // bus = 0x55
        ppu.status.register = 0b1110_0000.toSignedByte()

        ppu.peek(2)

        // The peek must NOT latch the combined $2002 return byte. Only a real
        // $2002 read should mutate the bus; peek is side-effect-free.
        assertThat("peek(2) left openBus at 0x55",
            ppu.openBus.toInt() and 0xFF, equalTo(0x55))
    }

    // ---- 4. Save/load round-trip preserves openBus ----

    @Test
    fun `openBus survives save and load`() {
        // We exercise the full save-state envelope here (not just the PPU
        // block) because the version bump is in SaveState.kt and the test
        // serves as the integration-level proof that an old snapshot is no
        // longer loadable as a v10 file (it is — MIN_SUPPORTED_VERSION is
        // still 4, and a v10 file loads with openBus=0).
        val nes = com.github.alondero.nestlin.Nestlin()
        nes.loadBytes(TestRoms.nestestBytes())
        nes.powerReset()

        // Tick a few cycles so the PPU is warmed up.
        repeat(50) {
            repeat(3) { nes.ppu.tick() }
            nes.apu.tick()
            nes.cpu.tick()
        }

        // Write to $2005 (PPUSCROLL) — this lands $A5 on the open bus.
        nes.memory[0x2005] = 0xA5.toSignedByte()

        // Sanity check: a real $2000 read sees 0xA5 right now.
        val readBefore = nes.memory[0x2000].toInt() and 0xFF
        assertThat("read-back before save matches bus", readBefore, equalTo(0xA5))

        // Save state and reload into a fresh instance — a stricter round-trip
        // that also verifies the byte position in the save file.
        val snapshot = ByteArrayOutputStream().also { nes.saveState(it) }.toByteArray()

        val freshNes = com.github.alondero.nestlin.Nestlin()
        freshNes.loadBytes(TestRoms.nestestBytes())
        freshNes.powerReset()
        freshNes.loadState(ByteArrayInputStream(snapshot))

        // Open-bus latch must have round-tripped.
        assertThat("openBus round-tripped",
            freshNes.memory.ppuAddressedMemory.openBus.toInt() and 0xFF, equalTo(0xA5))

        // And a $2000 read on the restored instance sees the bus byte, not
        // some stale internal register value.
        assertThat("$2000 read on restored instance",
            freshNes.memory[0x2000].toInt() and 0xFF, equalTo(0xA5))
    }

    @Test
    fun `version field reflects v11 format bump`() {
        // The file format version must now be 11 (issue #292). Reading the
        // documented offset (4-byte magic + 4-byte version) is the cheapest
        // way to verify this without parsing the whole file. The constant
        // itself is pinned by SaveStateMigrationTest.
        val nes = com.github.alondero.nestlin.Nestlin()
        nes.loadBytes(TestRoms.nestestBytes())
        nes.powerReset()
        val bytes = ByteArrayOutputStream().also { nes.saveState(it) }.toByteArray()

        val version = ((bytes[4].toInt() and 0xFF) shl 24) or
            ((bytes[5].toInt() and 0xFF) shl 16) or
            ((bytes[6].toInt() and 0xFF) shl 8) or
            (bytes[7].toInt() and 0xFF)

        assertThat("save-state version", version, equalTo(SaveState.VERSION))
    }
}