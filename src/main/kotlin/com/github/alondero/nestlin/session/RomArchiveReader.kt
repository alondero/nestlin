package com.github.alondero.nestlin.session

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.nio.file.Files
import java.nio.file.Path

/**
 * Reads NES ROMs from disk, handling both plain `.nes` files and `.7z`
 * archives (issue #269 AC #1, #3).
 *
 * ## Plain loads
 *
 * A `.nes` (or `.NES`) file is read in one shot. The virtual filename is the
 * on-disk filename; the archive entry name is null.
 *
 * ## 7z archive loads
 *
 * A `.7z` archive may contain one or more entries. The selection rule:
 *
 *  - **Exactly one entry**: select it. The virtual filename is
 *    `archive.7z#entry.nes` so log lines and the boot placard show both the
 *    archive and the entry.
 *  - **Multiple entries**: ambiguous — throw [RomArchiveException] with the
 *    list of entry names so the UI can prompt the user. (The CLI bootcheck /
 *    replay tools treat this as a usage error and exit EXIT_USAGE.)
 *  - **No entries**: throw [RomArchiveException] with a "no NES entries" message.
 *
 * A non-NES entry in a single-entry archive is also an error: the player
 * probably pointed Nestlin at the wrong archive.
 *
 * The selected NES entry's bytes become the `bytes` of the resulting
 * [RomContent]. The archive container bytes NEVER enter the hash — that
 * guarantees plain and archived forms of identical NES bytes identify
 * identically (issue #269 AC #2).
 */
object RomArchiveReader {

    /**
     * Read a plain `.nes` file from disk and return the bytes.
     *
     * @throws RomArchiveException when the file is empty or doesn't carry the
     * NES magic.
     */
    fun readPlain(romPath: Path): ByteArray {
        require(romPath.toString().lowercase().endsWith(".nes")) {
            "readPlain requires a .nes path, got '$romPath'"
        }
        val bytes = Files.readAllBytes(romPath)
        if (bytes.size < 16) {
            throw RomArchiveException(
                "ROM file is too small to be a valid iNES image: ${romPath.fileName} (${bytes.size} bytes)"
            )
        }
        validateNesMagic(bytes, romPath.fileName.toString())
        return bytes
    }

    /**
     * List every NES entry inside a `.7z` archive. Non-NES entries are
     * filtered out. Returns entries in archive order. An empty result means
     * "no NES entries" — the caller distinguishes that from the "ambiguous
     * multi-entry" case by inspecting the result size.
     */
    fun listNesEntries(archivePath: Path): List<String> {
        require(archivePath.toString().lowercase().endsWith(".7z")) {
            "listNesEntries requires a .7z path, got '$archivePath'"
        }
        val entries = mutableListOf<String>()
        SevenZFile(archivePath.toFile()).use { sz ->
            var entry = sz.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name != null && name.lowercase().endsWith(".nes") && !entry.isDirectory) {
                    entries += name
                }
                entry = sz.nextEntry
            }
        }
        return entries
    }

    /**
     * Read the selected NES entry from a `.7z` archive. Returns the bytes
     * and the entry name (used by [RomContentExtractor] to build the virtual
     * filename and the [RomContent.archiveEntryName]).
     *
     * @throws RomArchiveException when the archive is unreadable, contains
     * no NES entries, or has more than one NES entry (the caller can't
     * resolve ambiguity without UI).
     */
    fun readArchiveEntry(archivePath: Path, entryName: String? = null): ArchiveReadResult {
        require(archivePath.toString().lowercase().endsWith(".7z")) {
            "readArchiveEntry requires a .7z path, got '$archivePath'"
        }
        val entries = listNesEntries(archivePath)
        if (entries.isEmpty()) {
            throw RomArchiveException(
                "No NES entries found in archive: ${archivePath.fileName}"
            )
        }
        val chosen = if (entryName != null) {
            entries.firstOrNull { it == entryName || it.endsWith("/$entryName") }
                ?: throw RomArchiveException(
                    "Requested entry '$entryName' not found in archive '${archivePath.fileName}'. " +
                        "Available NES entries: ${entries.joinToString(", ")}"
                )
        } else if (entries.size == 1) {
            entries.first()
        } else {
            throw RomArchiveException(
                "Archive '${archivePath.fileName}' contains ${entries.size} NES entries " +
                    "(${entries.joinToString(", ")}). Specify which entry to load."
            )
        }
        val bytes = SevenZFile(archivePath.toFile()).use { sz ->
            var entry = sz.nextEntry
            var found: ByteArray? = null
            while (entry != null) {
                if (entry.name == chosen || entry.name?.endsWith("/$chosen") == true) {
                    val buf = ByteArray(entry.size.toInt())
                    val read = sz.read(buf)
                    found = if (read == buf.size) buf else buf.copyOf(read)
                    break
                }
                entry = sz.nextEntry
            }
            found ?: throw RomArchiveException(
                "Failed to extract entry '$chosen' from archive '${archivePath.fileName}'"
            )
        }
        validateNesMagic(bytes, chosen)
        return ArchiveReadResult(bytes = bytes, entryName = chosen)
    }

    private fun validateNesMagic(bytes: ByteArray, label: String) {
        if (bytes.size < 16) {
            throw RomArchiveException(
                "ROM '$label' is too small to be a valid iNES image (${bytes.size} bytes)"
            )
        }
        // "NES" + 0x1A. Match the validation in com.github.alondero.nestlin.file.load
        // so the seam stays consistent across both extraction paths.
        if (bytes[0] != 'N'.code.toByte() ||
            bytes[1] != 'E'.code.toByte() ||
            bytes[2] != 'S'.code.toByte() ||
            bytes[3] != 0x1A.toByte()
        ) {
            throw RomArchiveException(
                "ROM '$label' is missing the NES magic (expected 'NES' + 0x1A)"
            )
        }
    }

    /** Result of reading one NES entry from a 7z archive. */
    data class ArchiveReadResult(val bytes: ByteArray, val entryName: String) {
        init {
            require(bytes.isNotEmpty()) { "ArchiveReadResult bytes must not be empty" }
            require(entryName.isNotEmpty()) { "ArchiveReadResult entryName must not be empty" }
        }
    }
}

/**
 * Thrown by [RomArchiveReader] when an archive or plain file can't be loaded
 * to a valid ROM. The message is intentionally readable — the CLI tools
 * surface it directly in their `ERROR:` output, and the UI surfaces it in
 * the file-chooser's error dialog.
 */
class RomArchiveException(message: String) : RuntimeException(message)
