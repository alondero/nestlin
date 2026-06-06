package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.Nestlin
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.NoSuchFileException
import javax.imageio.ImageIO

/**
 * Tests for the slot-based save state UI (GitHub issue #45).
 *
 * The slot manager is a thin file-I/O layer over the existing SaveState codec:
 * it owns the `savestates/<rom-crc>.slot-N.nstl` + `savestates/<rom-crc>.slot-N.png`
 * pair and the directory layout. The SaveState determinism property
 * (see SaveStateTest) means that a byte-equal .nstl round-trip guarantees a
 * byte-equal next-frame, so the "frame hash matches pre-save" promise in the
 * issue is exercised transitively by the .nstl byte-equality assertions.
 */
class SaveStateSlotManagerTest {

    @TempDir
    lateinit var tempFolder: Path

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

    /** A unique deterministic frame buffer: each byte = (its index modulo 256). */
    private fun syntheticFrame(): ByteArray =
        ByteArray(SaveStateSlotManager.FRAME_BYTES) { (it and 0xFF).toByte() }

    @Test
    fun `slot path encodes CRC and slot number`() {
        val nes = newNestlinAtReset()
        val slot = SaveStateSlotManager(nes, tempFolder)

        val expectedCrc = "%08X".format(nes.cpu.currentGame!!.crc.value)
        assertTrue(
            slot.statePath(3).toString().endsWith("$expectedCrc.slot-3.nstl"),
            "state path should encode CRC and slot, got: ${slot.statePath(3)}"
        )
        assertTrue(
            slot.thumbnailPath(3).toString().endsWith("$expectedCrc.slot-3.png"),
            "thumbnail path should encode CRC and slot, got: ${slot.thumbnailPath(3)}"
        )
        // State and thumbnail must be sibling files (same directory, different extension).
        assertTrue(
            slot.statePath(3).parent == slot.thumbnailPath(3).parent,
            "state and thumbnail must live in the same directory"
        )
    }

    @Test
    fun `exists returns false for an empty slot`() {
        val nes = newNestlinAtReset()
        val slot = SaveStateSlotManager(nes, tempFolder)

        for (n in 1..9) {
            assertTrue(!slot.exists(n), "slot $n should not exist in a fresh tempdir")
        }
    }

    @Test
    fun `save writes both nstl state and png thumbnail`() {
        val nes = newNestlinAtReset()
        runCpuSteps(nes, 500)
        val slot = SaveStateSlotManager(nes, tempFolder)
        val state = snapshot(nes)
        val frame = syntheticFrame()

        slot.save(2, state, frame)

        // Both files exist
        assertTrue(Files.exists(slot.statePath(2)), "state file must exist after save")
        assertTrue(Files.exists(slot.thumbnailPath(2)), "thumbnail must exist after save")
        // exists() reports true
        assertTrue(slot.exists(2), "exists(2) must be true after save")
        // NSTL bytes round-trip exactly
        assertArrayEquals(state, Files.readAllBytes(slot.statePath(2))
        , "nstl file on disk must equal the bytes we passed to save()")
        // Thumbnail is a valid PNG of 256x240 (NES native resolution)
        val png = ImageIO.read(slot.thumbnailPath(2).toFile())
        assertNotNull(png, "thumbnail must be a valid PNG")
        assertTrue(png!!.width == 256, "PNG width must be 256, got ${png.width}")
        assertTrue(png.height == 240, "PNG height must be 240, got ${png.height}")
    }

    @Test
    fun `save then hard reset then load round-trips state (frame-hash regression)`() {
        // This is the issue's headline acceptance test: save, hard-reset, load,
        // and the resulting state must match the pre-save state. Because the
        // existing SaveStateTest proves that an identical state produces an
        // identical next frame, byte-equal state after restore guarantees the
        // "frame hash matches" promise.
        val nes = newNestlinAtReset()
        val slot = SaveStateSlotManager(nes, tempFolder)
        runCpuSteps(nes, 500)
        val stateBeforeSave = snapshot(nes)
        slot.save(3, stateBeforeSave, syntheticFrame())

        // Hard-reset: throw away the in-memory state and re-load the ROM.
        val fresh = newNestlinAtReset()
        val loadedBytes = slot.loadStateBytes(3)
        restore(fresh, loadedBytes)
        val stateAfterLoad = snapshot(fresh)

        assertArrayEquals(stateBeforeSave, stateAfterLoad
        , "Slot save+hard-reset+load must reproduce the original state byte-for-byte")
    }

    @Test
    fun `loadStateBytes throws NoSuchFileException for an empty slot`() {
        val nes = newNestlinAtReset()
        val slot = SaveStateSlotManager(nes, tempFolder)
        assertThrows<NoSuchFileException> { slot.loadStateBytes(5) }
    }

    @Test
    fun `slot range is 1 to 9 — zero is rejected`() {
        val nes = newNestlinAtReset()
        val slot = SaveStateSlotManager(nes, tempFolder)
        assertThrows<IllegalArgumentException> {
            slot.save(0, ByteArray(0), ByteArray(0))
        }
    }

    @Test
    fun `slot range is 1 to 9 — ten is rejected`() {
        val nes = newNestlinAtReset()
        val slot = SaveStateSlotManager(nes, tempFolder)
        assertThrows<IllegalArgumentException> {
            slot.save(10, ByteArray(0), ByteArray(0))
        }
    }

    @Test
    fun `lastModified returns a recent instant for a populated slot`() {
        val nes = newNestlinAtReset()
        val slot = SaveStateSlotManager(nes, tempFolder)
        // Anchor "earlier than this is impossible" far enough back to be safe
        // across file systems with coarse mtime resolution (FAT32 = 2s,
        // some Linux tmpfs = 1s, NTFS = 100ns). The looseness doesn't weaken
        // the test: the slot didn't exist a moment ago, so its mtime cannot
        // predate this anchor unless something is very wrong.
        val anchor = System.currentTimeMillis() - 10_000L
        slot.save(1, snapshot(nes), syntheticFrame())
        val lm = slot.lastModifiedMillis(1)
        assertNotNull(lm, "lastModifiedMillis must be non-null for a saved slot")
        assertTrue(lm!! >= anchor
        , "lastModifiedMillis ($lm) should be at or after the pre-save anchor ($anchor)")
    }

    @Test
    fun `lastModified returns null for an empty slot`() {
        val nes = newNestlinAtReset()
        val slot = SaveStateSlotManager(nes, tempFolder)
        assertTrue(slot.lastModifiedMillis(4) == null
        , "lastModifiedMillis on an empty slot should be null")
    }

    @Test
    fun `different slots produce different files for the same ROM`() {
        val nes = newNestlinAtReset()
        val slot = SaveStateSlotManager(nes, tempFolder)
        slot.save(1, snapshot(nes), syntheticFrame())
        slot.save(9, snapshot(nes), syntheticFrame())
        assertTrue(slot.statePath(1) != slot.statePath(9)
        , "slots 1 and 9 must be different files")
        assertTrue(Files.exists(slot.statePath(1)))
        assertTrue(Files.exists(slot.statePath(9)))
    }
}
