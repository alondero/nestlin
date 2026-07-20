package com.github.alondero.nestlin.ppu

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream

class NametableMirroringTest {

    // Horizontal mirroring tests (used by Donkey Kong)
    // With horizontal mirroring: $2000/$2400 -> NT0, $2800/$2C00 -> NT1
    @Test
    fun `horizontal mirroring - 0x2000 and 0x2400 write to same location in nameTable0`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.HORIZONTAL

        // Write to 0x2000 (direct NT0 access)
        memory[0x2000] = 0x42

        // Write to 0x2400 (mirror of 0x2000 to NT0)
        memory[0x2400] = 0x99.toByte()

        // Reading from 0x2000 should get the value written to 0x2400 (same location)
        assertThat(memory[0x2000], equalTo(0x99.toByte()))
        assertThat(memory[0x2400], equalTo(0x99.toByte()))
    }

    @Test
    fun `horizontal mirroring - 0x2800 and 0x2C00 write to same location in nameTable1`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.HORIZONTAL

        // Write to 0x2800 (direct NT1 access)
        memory[0x2800] = 0x55

        // Write to 0x2C00 (mirror of 0x2800 to NT1)
        memory[0x2C00] = 0xAA.toByte()

        // Reading from 0x2800 should get the value written to 0x2C00 (same location)
        assertThat(memory[0x2800], equalTo(0xAA.toByte()))
        assertThat(memory[0x2C00], equalTo(0xAA.toByte()))
    }

    @Test
    fun `horizontal mirroring - different offsets within mirrored regions work correctly`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.HORIZONTAL

        // With horizontal mirroring, 0x2000 mirrors 0x2400 (both NT0)
        memory[0x2010] = 0x11.toByte()
        memory[0x2410] = 0x22.toByte()
        // NT0[0x10] should have 0x22 (last write wins)

        // With horizontal mirroring, 0x2800 mirrors 0x2C00 (both NT1)
        memory[0x2820] = 0x33.toByte()
        memory[0x2C20] = 0x44.toByte()
        // NT1[0x20] should have 0x44 (last write wins)

        // Verify they map to same locations (mirrors)
        assertThat(memory[0x2010], equalTo(0x22.toByte()))
        assertThat(memory[0x2410], equalTo(0x22.toByte()))
        assertThat(memory[0x2820], equalTo(0x44.toByte()))
        assertThat(memory[0x2C20], equalTo(0x44.toByte()))
    }

    @Test
    fun `horizontal mirroring - NT0 and NT1 are independent`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.HORIZONTAL

        memory[0x2000] = 0x10.toByte()  // Write to NT0
        memory[0x2800] = 0x20.toByte()  // Write to NT1

        // NT0 should have 0x10 at offset 0 (accessed via 0x2000 or 0x2400)
        assertThat(memory[0x2000], equalTo(0x10.toByte()))
        assertThat(memory[0x2400], equalTo(0x10.toByte()))
        // NT1 should have 0x20 at offset 0 (accessed via 0x2800 or 0x2C00)
        assertThat(memory[0x2800], equalTo(0x20.toByte()))
        assertThat(memory[0x2C00], equalTo(0x20.toByte()))
    }

    // Vertical mirroring tests
    @Test
    fun `vertical mirroring - 0x2000 and 0x2800 write to same location in nameTable0`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.VERTICAL

        memory[0x2000] = 0x42.toByte()
        memory[0x2800] = 0x99.toByte()

        // In vertical mirroring, 0x2000 and 0x2800 are the same (both NT0)
        assertThat(memory[0x2000], equalTo(0x99.toByte()))
        assertThat(memory[0x2800], equalTo(0x99.toByte()))
    }

    @Test
    fun `vertical mirroring - 0x2400 and 0x2C00 write to same location in nameTable1`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.VERTICAL

        memory[0x2400] = 0x55.toByte()
        memory[0x2C00] = 0xAA.toByte()

        // In vertical mirroring, 0x2400 and 0x2C00 are the same (both NT1)
        assertThat(memory[0x2400], equalTo(0xAA.toByte()))
        assertThat(memory[0x2C00], equalTo(0xAA.toByte()))
    }

    @Test
    fun `vertical mirroring - NT0 and NT1 are independent`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.VERTICAL

        memory[0x2000] = 0x10.toByte()  // Write to NT0
        memory[0x2400] = 0x20.toByte()  // Write to NT1

        // NT0 should have 0x10 at offset 0 (accessed via 0x2000 or 0x2800)
        assertThat(memory[0x2000], equalTo(0x10.toByte()))
        assertThat(memory[0x2800], equalTo(0x10.toByte()))
        // NT1 should have 0x20 at offset 0 (accessed via 0x2400 or 0x2C00)
        assertThat(memory[0x2400], equalTo(0x20.toByte()))
        assertThat(memory[0x2C00], equalTo(0x20.toByte()))
    }

    // Four-screen mirroring tests (used by DRROM Gauntlet / Mapper 206, GH #105).
    // With 4-screen VRAM there is NO mirroring: each of the four 1 KB windows
    // ($2000/$2400/$2800/$2C00) is its own independent nametable.
    @Test
    fun `four-screen mirroring - all four nametables are independent`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.FOUR_SCREEN

        memory[0x2000] = 0x11.toByte()  // NT0
        memory[0x2400] = 0x22.toByte()  // NT1
        memory[0x2800] = 0x33.toByte()  // NT2
        memory[0x2C00] = 0x44.toByte()  // NT3

        // No cross-aliasing: each window keeps its own value.
        assertThat(memory[0x2000], equalTo(0x11.toByte()))
        assertThat(memory[0x2400], equalTo(0x22.toByte()))
        assertThat(memory[0x2800], equalTo(0x33.toByte()))
        assertThat(memory[0x2C00], equalTo(0x44.toByte()))
    }

    @Test
    fun `four-screen mirroring - 0x2000 and 0x2400 do NOT alias (unlike horizontal)`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.FOUR_SCREEN

        memory[0x2000] = 0x42.toByte()
        memory[0x2400] = 0x99.toByte()

        // Under horizontal mirroring these two share NT0; under 4-screen they don't.
        assertThat(memory[0x2000], equalTo(0x42.toByte()))
        assertThat(memory[0x2400], equalTo(0x99.toByte()))
    }

    @Test
    fun `four-screen mirroring - 0x2000 and 0x2800 do NOT alias (unlike vertical)`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.FOUR_SCREEN

        memory[0x2000] = 0x42.toByte()
        memory[0x2800] = 0x99.toByte()

        // Under vertical mirroring these two share NT0; under 4-screen they don't.
        assertThat(memory[0x2000], equalTo(0x42.toByte()))
        assertThat(memory[0x2800], equalTo(0x99.toByte()))
    }

    @Test
    fun `four-screen mirroring - offsets within a table are addressed correctly`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.FOUR_SCREEN

        // Last byte of each 1 KB window is distinct and within its own table.
        memory[0x23FF] = 0x1F.toByte()  // NT0 tail
        memory[0x27FF] = 0x2F.toByte()  // NT1 tail
        memory[0x2BFF] = 0x3F.toByte()  // NT2 tail
        memory[0x2FFF] = 0x4F.toByte()  // NT3 tail

        assertThat(memory[0x23FF], equalTo(0x1F.toByte()))
        assertThat(memory[0x27FF], equalTo(0x2F.toByte()))
        assertThat(memory[0x2BFF], equalTo(0x3F.toByte()))
        assertThat(memory[0x2FFF], equalTo(0x4F.toByte()))
    }

    // Save-state format tests (GH #105). The two extra 4-screen nametables are
    // written ONLY when mirroring == FOUR_SCREEN, so non-4-screen state stays
    // byte-for-byte identical to the pre-4-screen format.
    @Test
    fun `four-screen state round-trips all four nametables`() {
        val original = PpuInternalMemory()
        original.mirroring = PpuInternalMemory.Mirroring.FOUR_SCREEN
        original[0x2000] = 0xA1.toByte()
        original[0x2400] = 0xB2.toByte()
        original[0x2800] = 0xC3.toByte()
        original[0x2C00] = 0xD4.toByte()

        val bytes = ByteArrayOutputStream().also { original.saveState(DataOutputStream(it)) }.toByteArray()

        val restored = PpuInternalMemory()
        restored.loadState(DataInputStream(ByteArrayInputStream(bytes)))

        assertThat(restored.mirroring, equalTo(PpuInternalMemory.Mirroring.FOUR_SCREEN))
        assertThat(restored[0x2000], equalTo(0xA1.toByte()))
        assertThat(restored[0x2400], equalTo(0xB2.toByte()))
        assertThat(restored[0x2800], equalTo(0xC3.toByte()))
        assertThat(restored[0x2C00], equalTo(0xD4.toByte()))
    }

    @Test
    fun `non-four-screen state does not carry the extra nametables`() {
        // A horizontal-mirroring save must be smaller than a 4-screen one by
        // exactly the two extra 1 KB tables, proving they are omitted.
        val horizontal = PpuInternalMemory().apply { mirroring = PpuInternalMemory.Mirroring.HORIZONTAL }
        val fourScreen = PpuInternalMemory().apply { mirroring = PpuInternalMemory.Mirroring.FOUR_SCREEN }

        val hBytes = ByteArrayOutputStream().also { horizontal.saveState(DataOutputStream(it)) }.toByteArray()
        val fBytes = ByteArrayOutputStream().also { fourScreen.saveState(DataOutputStream(it)) }.toByteArray()

        assertThat(fBytes.size - hBytes.size, equalTo(0x800))
    }
}
