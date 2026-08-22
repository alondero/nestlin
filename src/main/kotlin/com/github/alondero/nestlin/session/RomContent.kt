package com.github.alondero.nestlin.session

import java.nio.file.Path

/**
 * Canonical immutable value handed across every Nestlin boundary that cares
 * about ROM identity (issue #269).
 *
 * Two motivations:
 *
 *  - **Plain and archived forms of the same NES bytes identify identically.**
 *    The RetroAchievements hash is computed from the extracted iNES bytes;
 *    the container bytes (.7z header, .zip wrapper) never enter the hash.
 *    So `kirby.nes` and `kirby.7z` (when the archive contains a single NES
 *    entry named `kirby.nes`) produce the same `hash` and the same
 *    `virtualFilename`. There is no separate CRC mapping.
 *
 *  - **The display name + virtual filename + extracted bytes are owned by
 *    the boundary, not by the host file system.** A ROM loaded from a 7z
 *    archive gets a `virtualFilename` of `archive.7z#entry.nes` so log lines
 *    and UI labels read sensibly without dragging the archive container into
 *    every consumer. Plain loads use the on-disk filename as the virtual
 *    filename.
 *
 * Constructed by [RomContentExtractor] from a [Path] (plain or .7z) or by the
 * coordinator from a bytes-only test fixture. The hash, when present, is
 * computed by [RomHasher] against the canonical RC_CONSOLE_NINTENDO routine —
 * identical to the hash rcheevos would compute internally for the same bytes.
 *
 * The class is a plain Kotlin class with explicit equals/hashCode because the
 * `bytes` field is a [ByteArray] (reference equality by default). Tests rely
 * on structural equality to assert that two ROM contents with identical bytes
 * (and matching metadata) are equal.
 */
class RomContent(
    /**
     * The original extracted NES bytes (iNES / NES 2.0 image). Defensively
     * copied on construction so the host parser can mutate its own buffer
     * without disturbing this snapshot. Always non-empty for a valid ROM.
     */
    val bytes: ByteArray,
    /**
     * Human-readable ROM name (typically the iNES internal title, falling
     * back to the entry filename, falling back to the source path's stem).
     * Used for log lines and the "recognized game" placard.
     */
    val displayName: String,
    /**
     * Virtual filename for logging and for distinguishing plain-vs-archived
     * sources. Plain loads use the on-disk filename; archived loads use
     * `archive.7z#entry.nes` so the archive wrapper is visible in diagnostics.
     * Always ends with `.nes`.
     */
    val virtualFilename: String,
    /**
     * Disk path the ROM was loaded from, or null for bytes-only test fixtures.
     * Preserved here so the coordinator's battery-RAM autosave and the
     * "recent files" menu can find the original file without threading the
     * path through every call site.
     */
    val sourcePath: Path?,
    /**
     * Identifier of the entry inside a multi-entry archive, or null for
     * plain loads and single-entry archives. Stable for the lifetime of the
     * [RomContent] — extracting the same archive twice produces the same
     * identifier.
     */
    val archiveEntryName: String? = null,
    /**
     * Official RetroAchievements NES hash (32 hex chars + NUL). Null when
     * hashing hasn't run yet (e.g. the coordinator hasn't called
     * [RomHasher.hash]); populated synchronously by the coordinator before
     * the first frame is allowed.
     */
    val hash: String? = null,
) {
    init {
        require(bytes.isNotEmpty()) { "RomContent bytes must not be empty" }
        require(displayName.isNotEmpty()) { "RomContent displayName must not be empty" }
        require(virtualFilename.isNotEmpty()) { "RomContent virtualFilename must not be empty" }
        require(virtualFilename.endsWith(".nes", ignoreCase = true)) {
            "RomContent virtualFilename must end with .nes, got '$virtualFilename'"
        }
        if (hash != null) {
            require(hash.length == 32) { "RomContent hash must be 32 hex chars, got '$hash'" }
            require(hash.all { it.isDigit() || it in 'a'..'f' }) {
                "RomContent hash must be lowercase hex, got '$hash'"
            }
        }
    }

    /** Defensive copy accessor — callers may retain the bytes past our lifetime. */
    fun bytesCopy(): ByteArray = bytes.copyOf()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is RomContent) return false
        if (displayName != other.displayName) return false
        if (virtualFilename != other.virtualFilename) return false
        if (sourcePath != other.sourcePath) return false
        if (archiveEntryName != other.archiveEntryName) return false
        if (hash != other.hash) return false
        if (!bytes.contentEquals(other.bytes)) return false
        return true
    }

    override fun hashCode(): Int {
        var result = displayName.hashCode()
        result = 31 * result + virtualFilename.hashCode()
        result = 31 * result + (sourcePath?.hashCode() ?: 0)
        result = 31 * result + (archiveEntryName?.hashCode() ?: 0)
        result = 31 * result + (hash?.hashCode() ?: 0)
        result = 31 * result + bytes.contentHashCode()
        return result
    }

    override fun toString(): String =
        "RomContent(displayName='$displayName', virtualFilename='$virtualFilename', " +
            "bytes=${bytes.size}, hash=${hash ?: "<unhashed>"}, sourcePath=$sourcePath, " +
            "archiveEntryName=$archiveEntryName)"
}
