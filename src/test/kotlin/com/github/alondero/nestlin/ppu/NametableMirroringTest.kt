package com.github.alondero.nestlin.ppu

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test

class NametableMirroringTest {

    // Horizontal mirroring tests (used by Donkey Kong)
    @Test
    fun `horizontal mirroring - 0x2000 and 0x2800 write to same location in nameTable0`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.HORIZONTAL

        // Write to 0x2000 (direct NT0 access)
        memory[0x2000] = 0x42

        // Write to 0x2800 (mirror of 0x2000 to NT0)
        memory[0x2800] = 0x99.toByte()

        // Reading from 0x2000 should get the value written to 0x2800 (same location)
        assertThat(memory[0x2000], equalTo(0x99.toByte()))
        assertThat(memory[0x2800], equalTo(0x99.toByte()))
    }

    @Test
    fun `horizontal mirroring - 0x2400 and 0x2C00 write to same location in nameTable1`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.HORIZONTAL

        // Write to 0x2400 (direct NT1 access)
        memory[0x2400] = 0x55

        // Write to 0x2C00 (mirror of 0x2400 to NT1)
        memory[0x2C00] = 0xAA.toByte()

        // Reading from 0x2400 should get the value written to 0x2C00 (same location)
        assertThat(memory[0x2400], equalTo(0xAA.toByte()))
        assertThat(memory[0x2C00], equalTo(0xAA.toByte()))
    }

    @Test
    fun `horizontal mirroring - different offsets within mirrored regions work correctly`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.HORIZONTAL

        // Write different bytes to corresponding addresses
        memory[0x2010] = 0x11.toByte()
        memory[0x2810] = 0x22.toByte()
        memory[0x2020] = 0x33.toByte()
        memory[0x2820] = 0x44.toByte()

        // Verify they map to same locations (mirrors)
        assertThat(memory[0x2010], equalTo(0x22.toByte()))
        assertThat(memory[0x2020], equalTo(0x44.toByte()))
    }

    @Test
    fun `horizontal mirroring - NT0 and NT1 are independent`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.HORIZONTAL

        memory[0x2000] = 0x10.toByte()
        memory[0x2400] = 0x20.toByte()

        // NT0 should have 0x10 at offset 0
        assertThat(memory[0x2000], equalTo(0x10.toByte()))
        // NT1 should have 0x20 at offset 0
        assertThat(memory[0x2400], equalTo(0x20.toByte()))
    }

    // Vertical mirroring tests
    @Test
    fun `vertical mirroring - 0x2000 and 0x2400 write to same location in nameTable0`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.VERTICAL

        memory[0x2000] = 0x42.toByte()
        memory[0x2400] = 0x99.toByte()

        // In vertical mirroring, 0x2000 and 0x2400 are the same (both NT0)
        assertThat(memory[0x2000], equalTo(0x99.toByte()))
        assertThat(memory[0x2400], equalTo(0x99.toByte()))
    }

    @Test
    fun `vertical mirroring - 0x2800 and 0x2C00 write to same location in nameTable1`() {
        val memory = PpuInternalMemory()
        memory.mirroring = PpuInternalMemory.Mirroring.VERTICAL

        memory[0x2800] = 0x55.toByte()
        memory[0x2C00] = 0xAA.toByte()

        // In vertical mirroring, 0x2800 and 0x2C00 are the same (both NT1)
        assertThat(memory[0x2800], equalTo(0xAA.toByte()))
        assertThat(memory[0x2C00], equalTo(0xAA.toByte()))
    }
}
