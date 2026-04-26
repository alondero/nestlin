package com.github.alondero.nestlin.compare

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.toUnsignedInt
import org.junit.Test
import java.nio.file.Paths
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo

/**
 * Test for VBlank flag race suppression on $2002 read.
 *
 * H4: Per NESdev: "Reading $2002 within a few cycles of when the VBL flag is set
 * causes the flag to be cleared and NMI to be suppressed."
 *
 * This is the race condition: game's poll happens to align with VBlank set,
 * and without suppression logic, both would conflict.
 */
class VBlankReadRaceTest {

    @Test
    fun readVBlankFlagDuringActiveVBlankClearsNmiForFrame() {
        val romPath = Paths.get("testroms/kirby.nes")
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }

        nestlin.powerReset()

        System.err.println("=== STEP 1: Enable NMI (write $80 to $2000) ===")
        nestlin.memory.ppuAddressedMemory.controller.register = 0x80.toByte()
        assertThat("generateNmi() should be true", nestlin.memory.ppuAddressedMemory.controller.generateNmi(), equalTo(true))

        System.err.println("=== STEP 2: Force VBlank to be set ===")
        nestlin.memory.ppuAddressedMemory.setVBlank()
        assertThat("nmiOccurred should be true after setVBlank()", nestlin.memory.ppuAddressedMemory.nmiOccurred, equalTo(true))

        System.err.println("=== STEP 3: Read $2002 (should clear VBlank and nmiOccurred) ===")
        val statusValue = nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt()
        System.err.println("Status value read: ${String.format("%02X", statusValue)}")
        System.err.println("bit 7 = ${(statusValue shr 7) and 1}")

        // Read $2002
        nestlin.memory.ppuAddressedMemory[2]

        System.err.println("After reading \$2002:")
        System.err.println("  nmiOccurred = ${nestlin.memory.ppuAddressedMemory.nmiOccurred}")
        System.err.println("  status bit 7 = ${(nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt() shr 7) and 1}")

        // === ASSERTIONS ===

        // Reading $2002 should clear VBlank flag (bit 7)
        assertThat("VBlank bit 7 should be cleared after reading $2002",
            (nestlin.memory.ppuAddressedMemory.status.register.toUnsignedInt() shr 7) and 1, equalTo(0))

        // Reading $2002 should clear nmiOccurred (acknowledges the interrupt)
        assertThat("nmiOccurred should be cleared after reading $2002",
            nestlin.memory.ppuAddressedMemory.nmiOccurred, equalTo(false))

        // === STEP 4: Tick CPU and verify no NMI is taken ===
        val pcBefore = nestlin.cpu.registers.programCounter.toUnsignedInt()
        System.err.println("PC before CPU tick = ${String.format("%04X", pcBefore)}")

        nestlin.cpu.tick()

        val pcAfter = nestlin.cpu.registers.programCounter.toUnsignedInt()
        System.err.println("PC after CPU tick = ${String.format("%04X", pcAfter)}")

        // Since nmiOccurred was cleared by the $2002 read, checkAndHandleNmi() should NOT trigger
        val nmiTaken = pcAfter >= 0x100 && pcAfter < 0x8000 && pcAfter != (pcBefore + 1)
        System.err.println("NMI taken: $nmiTaken")

        assertThat("No NMI should be taken after $2002 read cleared nmiOccurred",
            nmiTaken, equalTo(false))

        nestlin.stop()
    }
}