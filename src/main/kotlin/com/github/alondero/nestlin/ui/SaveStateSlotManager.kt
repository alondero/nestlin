package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.ppu.RESOLUTION_HEIGHT
import com.github.alondero.nestlin.ppu.RESOLUTION_WIDTH
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import javax.imageio.ImageIO

/**
 * Filesystem layout for slot-based save states (GitHub issue #45).
 *
 * For each loaded ROM (identified by its CRC32), up to nine slots are kept in
 * a single directory:
 *
 *   <savestatesDir>/<rom-crc>.slot-1.nstl
 *   <savestatesDir>/<rom-crc>.slot-1.png   (companion thumbnail, same stem)
 *   <savestatesDir>/<rom-crc>.slot-2.nstl
 *   <savestatesDir>/<rom-crc>.slot-2.png
 *   ...
 *   <savestatesDir>/<rom-crc>.slot-9.nstl
 *   <savestatesDir>/<rom-crc>.slot-9.png
 *
 * Using the CRC rather than the ROM filename means two copies of the same ROM
 * (e.g. `kirby.nes` and `Kirby's Adventure (USA).nes`) share their slots, which
 * is what every other emulator with slot UIs does. The CRC is the only stable
 * identity across dumps; filenames and display names are not.
 *
 * The class is decoupled from `Nestlin.saveState` / `loadState` so the save
 * bytes can be produced by any source (live emulator, test fixture, etc.) and
 * so the unit tests can drive it without spinning up a JavaFX application.
 */
class SaveStateSlotManager(
    private val nestlin: Nestlin,
    private val savestatesDir: Path = Paths.get("savestates")
) {

    init {
        // Idempotent: throws FileAlreadyExistsException? No — Files.createDirectories
        // is a no-op when the directory already exists. Safe to call on every start.
        Files.createDirectories(savestatesDir)
    }

    /**
     * The CRC32 of the currently loaded ROM, formatted as 8 uppercase hex
     * digits (matching the convention used in SaveState error messages). This
     * is the stem for all slot files for the current ROM. Calling without a
     * loaded ROM throws because there is no meaningful identity to key on.
     */
    val romCrcString: String
        get() {
            val game = nestlin.cpu.currentGame
                ?: throw IllegalStateException("No ROM loaded; cannot compute slot CRC")
            return "%08X".format(game.crc.value)
        }

    /**
     * Filesystem path of the `.nstl` state file for [slot]. Stable for the
     * lifetime of a single ROM load, so it's safe to cache on the UI side.
     */
    fun statePath(slot: Int): Path =
        requireValidSlot(slot).let { savestatesDir.resolve("$romCrcString.slot-$it.nstl") }

    /**
     * Filesystem path of the companion `.png` thumbnail for [slot]. The
     * thumbnail is the same frame buffer that was visible on screen at the
     * moment the slot was saved, so the menu can render a preview without
     * having to load the (much larger) `.nstl` blob.
     */
    fun thumbnailPath(slot: Int): Path =
        requireValidSlot(slot).let { savestatesDir.resolve("$romCrcString.slot-$it.png") }

    /** True iff the `.nstl` state file for [slot] exists. */
    fun exists(slot: Int): Boolean = Files.exists(statePath(slot))

    /**
     * Last-modified wall-clock time of the `.nstl` state file, in milliseconds
     * since the epoch (the unit `java.io.File.lastModified` and friends use).
     * Returns `null` if the slot is empty — the menu uses this to decide
     * whether to show a timestamp or an "(empty)" placeholder.
     */
    fun lastModifiedMillis(slot: Int): Long? {
        val path = statePath(slot)
        // Files.getLastModifiedTime throws NoSuchFileException on a missing
        // file. We model the empty case as null rather than propagating the
        // exception, because "this slot has never been used" is a normal flow
        // in the UI (the menu just shows "(empty)"). Other IOException variants
        // (permission denied, disk error) propagate to the caller so they can
        // surface a real diagnostic — masking them as "empty" would make a
        // broken-filesystem scenario look like a benign one.
        return try {
            Files.getLastModifiedTime(path).toMillis()
        } catch (e: java.nio.file.NoSuchFileException) {
            null
        }
    }

    /**
     * Write both the `.nstl` state and the `.png` thumbnail for [slot]. The
     * state bytes are written verbatim — this class doesn't interpret them,
     * so it never has to learn about the SaveState format version. The frame
     * is encoded as a 256x240 native-resolution PNG; the menu renders these
     * at 1:1 or scaled depending on the menu item's space budget.
     *
     * @param slot 1..9 inclusive
     * @param stateBytes the SaveState .nstl payload (caller writes via Nestlin.saveState)
     * @param frameRgb 256*240*3 = 184320 bytes, RGB byte order (matches what
     *                 Application.kt's frame buffer already produces)
     */
    fun save(slot: Int, stateBytes: ByteArray, frameRgb: ByteArray) {
        requireValidSlot(slot)
        require(frameRgb.size == FRAME_BYTES) {
            "Frame buffer must be $FRAME_BYTES bytes (256*240*3 RGB), got ${frameRgb.size}"
        }

        // Write the .nstl verbatim. Atomic-replace would be nicer but the
        // existing single-slot save in Application.kt also writes in place,
        // and a partial write here just means a corrupt slot the user can
        // re-save — not a hard failure.
        val stateFile = statePath(slot)
        Files.write(stateFile, stateBytes)

        // Render the PNG via a tiny BufferedImage. We avoid going through
        // ScreenshotManager because that class generates timestamped filenames
        // and writes to a fixed `screenshots/` directory; here we need an
        // exact path. The pixel-encoding logic is identical.
        val image = BufferedImage(RESOLUTION_WIDTH, RESOLUTION_HEIGHT, BufferedImage.TYPE_INT_RGB)
        var byteIndex = 0
        for (y in 0 until RESOLUTION_HEIGHT) {
            for (x in 0 until RESOLUTION_WIDTH) {
                val r = frameRgb[byteIndex++].toInt() and 0xFF
                val g = frameRgb[byteIndex++].toInt() and 0xFF
                val b = frameRgb[byteIndex++].toInt() and 0xFF
                image.setRGB(x, y, (r shl 16) or (g shl 8) or b)
            }
        }
        ImageIO.write(image, "PNG", thumbnailPath(slot).toFile()).also { ok ->
            // ImageIO.write returns false when no suitable writer was found
            // (e.g. slim JREs with com.sun.imageio.plugins.png stripped) or
            // when the encoder fails. Without this check the slot would
            // have a valid .nstl but no .png and the caller would never
            // know — the next menu refresh would happily show "Slot N -
            // <timestamp>" as if the save was complete.
            if (!ok) throw java.io.IOException(
                "PNG writer not available or failed for ${thumbnailPath(slot)}"
            )
        }
    }

    /**
     * Read the `.nstl` state bytes for [slot]. Throws [NoSuchFileException]
     * when the slot is empty — the UI catches this and shows a toast / status
     * line rather than an error dialog, so missing slots are a normal flow
     * (the user just hasn't saved into that slot yet) rather than an error.
     */
    fun loadStateBytes(slot: Int): ByteArray {
        requireValidSlot(slot)
        return Files.readAllBytes(statePath(slot))
    }

    private fun requireValidSlot(slot: Int): Int {
        require(slot in 1..9) { "Slot must be in 1..9, got $slot" }
        return slot
    }

    companion object {
        /** 256 * 240 * 3 bytes (RGB), the size of the native NES frame buffer. */
        const val FRAME_BYTES: Int = RESOLUTION_WIDTH * RESOLUTION_HEIGHT * 3
    }
}
