package com.github.alondero.nestlin

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Paths

/**
 * Round-trip tests for the save state subsystem.
 *
 * Two distinct invariants verified:
 *  1. **Idempotence** — `save → load → save` produces a byte-identical state.
 *  2. **Determinism** — `save(T1); run K; save(T2)` then `load(T1); run K; save(T3)` gives `T2 == T3`.
 *     This is the strong correctness property: every piece of state that the next K ticks depend
 *     on must be captured. If we miss anything (e.g. a mid-fetch latch), T3 will diverge from T2.
 */
class SaveStateTest {

    private fun newNestlinAtReset(): Nestlin {
        val nes = Nestlin()
        nes.load(Paths.get("testroms/nestest.nes"))
        nes.powerReset()
        return nes
    }

    /** Drives the emulator like Nestlin.start() does, but for a fixed tick budget. */
    private fun runCpuSteps(nes: Nestlin, cpuTicks: Int) {
        repeat(cpuTicks) {
            repeat(3) { nes.ppu.tick() }
            nes.apu.tick()
            nes.cpu.tick()
        }
    }

    private fun snapshot(nes: Nestlin): ByteArray {
        val out = ByteArrayOutputStream()
        nes.saveState(out)
        return out.toByteArray()
    }

    private fun restore(nes: Nestlin, bytes: ByteArray) {
        nes.loadState(ByteArrayInputStream(bytes))
    }

    @Test
    fun `save then load then save is byte-identical (idempotence)`() {
        val nes = newNestlinAtReset()
        runCpuSteps(nes, 500)

        val saveA = snapshot(nes)
        restore(nes, saveA)
        val saveB = snapshot(nes)

        assertArrayEquals(
            "Save/load round-trip must preserve state byte-for-byte",
            saveA, saveB
        )
    }

    @Test
    fun `running K ticks from a restored state matches running K ticks from the original`() {
        // The strong determinism test: if any state were missing, T3 would diverge from T2.
        val nes = newNestlinAtReset()
        runCpuSteps(nes, 500)

        val checkpoint = snapshot(nes)

        // Path A: run K more ticks from the live instance.
        runCpuSteps(nes, 200)
        val expected = snapshot(nes)

        // Path B: revert to the checkpoint, run the same K ticks again.
        restore(nes, checkpoint)
        runCpuSteps(nes, 200)
        val actual = snapshot(nes)

        assertArrayEquals(
            "Restoring then running should produce the same state as running continuously",
            expected, actual
        )
    }

    @Test
    fun `state can be transplanted into a fresh Nestlin instance`() {
        val source = newNestlinAtReset()
        runCpuSteps(source, 500)
        val sourceBytes = snapshot(source)

        // Fresh instance, same ROM. Power-on resets CPU regs/RAM to known state.
        val target = newNestlinAtReset()
        restore(target, sourceBytes)
        val targetBytes = snapshot(target)

        assertArrayEquals(
            "A save loaded into a freshly-reset Nestlin must yield the same state",
            sourceBytes, targetBytes
        )
    }

    @Test
    fun `loading state without a loaded game throws`() {
        val nes = Nestlin()  // No ROM loaded
        try {
            nes.loadState(ByteArrayInputStream(ByteArray(0)))
            fail("Expected IllegalStateException when loading state with no ROM")
        } catch (e: IllegalStateException) {
            assertTrue(e.message?.contains("No game") == true)
        }
    }

    @Test
    fun `loading with bad magic header is rejected`() {
        val nes = newNestlinAtReset()
        val bogus = ByteArray(64) { 0xAA.toByte() }
        try {
            nes.loadState(ByteArrayInputStream(bogus))
            fail("Expected IncompatibleSaveStateException for bad magic")
        } catch (e: SaveState.IncompatibleSaveStateException) {
            assertTrue(
                "Expected magic mismatch message, got: ${e.message}",
                e.message?.contains("magic") == true
            )
        }
    }

    @Test
    fun `version field is at the documented format offset`() {
        val nes = newNestlinAtReset()
        val bytes = snapshot(nes)

        // Per SaveState format: 4-byte magic "NSTL" then 4-byte big-endian version.
        // Magic = 0x4E53544C
        val magic = ((bytes[0].toInt() and 0xFF) shl 24) or
                ((bytes[1].toInt() and 0xFF) shl 16) or
                ((bytes[2].toInt() and 0xFF) shl 8) or
                (bytes[3].toInt() and 0xFF)
        val version = ((bytes[4].toInt() and 0xFF) shl 24) or
                ((bytes[5].toInt() and 0xFF) shl 16) or
                ((bytes[6].toInt() and 0xFF) shl 8) or
                (bytes[7].toInt() and 0xFF)

        assertThat(magic, equalTo(0x4E53544C))
        assertThat(version, equalTo(SaveState.VERSION))
    }

    /**
     * Issue #100: the mapper block must carry a per-mapper version byte that
     * loadState rejects on mismatch. A save made by a future Mapper4 that
     * added a field (e.g. 3-cycle A12 filter state) would carry version 2;
     * the current code expects version 1, so it must refuse the load loudly
     * instead of silently reading garbage into PRG-bank registers.
     *
     * Test strategy: take a real round-trippable save, then mutate ONLY the
     * per-mapper version byte. The mapper block is the LAST length-prefixed
     * blob in the file, so its data bytes are the very last bytes of the
     * file. For Mapper0 (which has no other state), the data is exactly one
     * byte — the version stamp — which is the file's final byte.
     */
    @Test
    fun `mapper block with unknown version is rejected with a clear message`() {
        val nes = newNestlinAtReset()
        runCpuSteps(nes, 500)
        val bytes = snapshot(nes).copyOf()

        // The per-mapper version byte is the LAST byte of the file: for a
        // mapper with no other state (Mapper0), saveState writes only the
        // version stamp and nothing else.
        val versionByteOffset = bytes.size - 1
        assertThat(
            "Sanity check: the final byte should be the per-mapper version (1)",
            bytes[versionByteOffset].toInt() and 0xFF,
            equalTo(1)
        )
        bytes[versionByteOffset] = 99.toByte()

        try {
            restore(nes, bytes)
            fail("Expected IncompatibleSaveStateException for mapper version 99")
        } catch (e: SaveState.IncompatibleSaveStateException) {
            assertTrue(
                "Expected the message to mention the rejected version, got: ${e.message}",
                e.message?.contains("99") == true
            )
            assertTrue(
                "Expected the message to identify the mapper, got: ${e.message}",
                e.message?.contains("Mapper0") == true
            )
        }
    }
}
