package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ppu.Frame
import org.junit.Test
import java.nio.file.Paths
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo

/**
 * Test for PPUCTRL bit-7 edge-triggered NMI.
 *
 * H2: Game writes $A8 to $2000 *after* VBlank flag is already set, expecting
 * immediate NMI. Without bit-7 edge re-trigger, first NMI is delayed by up to
 * a frame, and downstream init code times out / corrupts state.
 */
class PpuCtrlNmiEdgeTest {

    @Test
    fun writingBit7HighWhileVBlankSetTriggersNmi() {
        val romPath = Paths.get("testroms/kirby.nes")
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        nestlin.powerReset()

        System.err.println("=== STEP 1: Initial state ===")
        System.err.println("PPUCTRL = ${String.format("%02X", nestlin.memory.ppuAddressedMemory.controller.register.toUnsignedInt())}")
        System.err.println("nmiOccurred = ${nestlin.memory.ppuAddressedMemory.nmiOccurred}")
        System.err.println("generateNmi() = ${nestlin.memory.ppuAddressedMemory.controller.generateNmi()}")

        System.err.println("\n=== STEP 2: Force VBlank to be set (simulate scanline 241) ===")
        nestlin.memory.ppuAddressedMemory.setVBlank()
        System.err.println("After setVBlank():")
        System.err.println("  status.register bit 7 = ${(nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt() shr 7) and 1}")
        System.err.println("  nmiOccurred = ${nestlin.memory.ppuAddressedMemory.nmiOccurred}")
        System.err.println("  generateNmi() = ${nestlin.memory.ppuAddressedMemory.controller.generateNmi()}")

        assertThat("VBlank bit 7 should be set after setVBlank()",
            (nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt() shr 7) and 1, equalTo(1))
        assertThat("nmiOccurred should be true after setVBlank()",
            nestlin.memory.ppuAddressedMemory.nmiOccurred, equalTo(true))

        System.err.println("\n=== STEP 3: Write $00 to $2000 (disable NMI) ===")
        nestlin.memory.ppuAddressedMemory.controller.register = 0x00.toByte()
        System.err.println("After writing $00:")
        System.err.println("  generateNmi() = ${nestlin.memory.ppuAddressedMemory.controller.generateNmi()}")
        System.err.println("  nmiOccurred = ${nestlin.memory.ppuAddressedMemory.nmiOccurred}")

        assertThat("generateNmi() should be false after writing $00",
            nestlin.memory.ppuAddressedMemory.controller.generateNmi(), equalTo(false))

        System.err.println("\n=== STEP 4: Write $80 to $2000 (enable NMI while VBlank is set) ===")
        nestlin.memory.ppuAddressedMemory.controller.register = 0x80.toByte()

        System.err.println("After writing \$80:")
        System.err.println("  generateNmi() = ${nestlin.memory.ppuAddressedMemory.controller.generateNmi()}")
        System.err.println("  nmiOccurred = ${nestlin.memory.ppuAddressedMemory.nmiOccurred}")

        assertThat("generateNmi() should be true after writing $80",
            nestlin.memory.ppuAddressedMemory.controller.generateNmi(), equalTo(true))

        // === ASSERTION: Edge re-trigger should set nmiOccurred = true ===
        // If edge re-trigger works: nmiOccurred should be true after writing $80
        // (because VBlank was already set when bit-7 transitioned 0→1)
        // If edge re-trigger doesn't work: nmiOccurred stays false
        assertThat("nmiOccurred should be re-triggered when bit-7 goes high while VBlank set",
            nestlin.memory.ppuAddressedMemory.nmiOccurred, equalTo(true))

        nestlin.stop()
    }
}