package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.toUnsignedInt
import com.github.alondero.nestlin.ui.FrameListener
import com.github.alondero.nestlin.ppu.Frame
import org.junit.Test
import java.nio.file.Paths
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo

/**
 * Simple test: verify setVBlank() sets bit 7 of status and nmiOccurred.
 * More complex VBlank timing tests require cycle-accurate PPU state tracking
 * which is difficult to test in isolation.
 */
class MinimalVBlankSetTest {

    @Test
    fun verifyVBlankBit7AndNmiOccurredIsSet() {
        val romPath = Paths.get("testroms/kirby.nes")
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        nestlin.powerReset()

        // Verify initial state: VBlank bit 7 is NOT set
        val initialStatus = nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt()
        assertThat("VBlank bit 7 should initially be 0",
            (initialStatus shr 7) and 1, equalTo(0))
        assertThat("nmiOccurred should initially be false",
            nestlin.memory.ppuAddressedMemory.nmiOccurred, equalTo(false))

        // Call setVBlank() (simulates what PPU does at scanline 241 cycle 1)
        nestlin.memory.ppuAddressedMemory.setVBlank()

        val statusAfter = nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt()
        System.err.println("After setVBlank(): status.register = ${String.format("%02X", statusAfter)}")
        System.err.println("  bit 7 = ${(statusAfter shr 7) and 1}")
        System.err.println("  nmiOccurred = ${nestlin.memory.ppuAddressedMemory.nmiOccurred}")

        // Assert bit 7 is set after setVBlank()
        assertThat("VBlank bit 7 should be set after setVBlank()",
            (statusAfter shr 7) and 1, equalTo(1))

        // Assert nmiOccurred is true
        assertThat("nmiOccurred should be true after setVBlank()",
            nestlin.memory.ppuAddressedMemory.nmiOccurred, equalTo(true))

        nestlin.stop()
    }
}