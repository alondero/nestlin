package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.Region
import com.github.alondero.nestlin.gamepak.GamePak
import java.nio.file.Path

/**
 * Immutable copy of the canonical ROM identity handed across the
 * [RetroAchievementsService] boundary.
 *
 * Two motivations for a fresh, copied value type:
 *
 *  - **The service does not get a reference to the live [GamePak] or [Path].**
 *    Nestlin holds the parser/loader state behind a long-lived [GamePak]; the
 *    service should not mutate it or retain pointers into it. We copy the ROM
 *    bytes here so the service owns a stable snapshot for the lifetime of one
 *    `prepareGame` / `unloadGame` cycle — even if the user changes ROMs
 *    mid-evaluation, an in-flight service call still has its original bytes.
 *  - **The service does not see JavaFX, AWT, file handles, or any other host
 *    substrate.** Just the ROM fingerprint, the display name, the virtual
 *    filename, the official NES hash, the region, and the byte length.
 *    Anything else is by design not on the boundary.
 *
 * Constructed by [GameSessionCoordinator] from a [RomContent] and passed to
 * [RetroAchievementsService.prepareGame] exactly once per game session.
 *
 * The [virtualFilename] + [nesHash] fields (issue #269) are what allow plain
 * and archived forms of identical NES bytes to identify identically — the
 * hash is computed by [RomHasher] against the extracted NES bytes (never
 * the archive container), so two paths that load the same NES bytes
 * produce structurally-equal GameSessionInfo values.
 */
data class GameSessionInfo(
    /** Human-readable ROM name without extension. Used for UI labels and the RA game lookup. */
    val displayName: String,
    /**
     * Virtual filename for logging and for distinguishing plain-vs-archived
     * sources. Plain loads: `<rom>.nes`. Archived loads: `<archive>.7z#<entry>.nes`.
     * Always ends with `.nes`.
     */
    val virtualFilename: String,
    /** Absolute path to the source file, or null for bytes-only / in-memory loads. */
    val sourcePath: Path?,
    /**
     * Full iNES image bytes. Defensively copied so the service owns a stable snapshot.
     */
    val romBytes: ByteArray,
    /**
     * Official RetroAchievements NES hash (32 hex chars), or null when hashing
     * hasn't run yet. Computed by [RomHasher] before the coordinator calls
     * `prepareGame` so the value is always populated on the production path.
     */
    val nesHash: String?,
    /** Region the ROM is expected to boot in (post-override resolution). */
    val region: Region,
) {
    /**
     * The [romBytes] array is defensively copied on the way in, so two info
     * objects with byte-identical payloads are equal under structural equality
     * — without an overridden equals this would fall back to [Any.equals]
     * (reference equality) and silently break fake-service assertions that
     * compare records.
     */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GameSessionInfo) return false
        if (displayName != other.displayName) return false
        if (virtualFilename != other.virtualFilename) return false
        if (sourcePath != other.sourcePath) return false
        if (nesHash != other.nesHash) return false
        if (!romBytes.contentEquals(other.romBytes)) return false
        if (region != other.region) return false
        return true
    }

    override fun hashCode(): Int {
        var result = displayName.hashCode()
        result = 31 * result + virtualFilename.hashCode()
        result = 31 * result + (sourcePath?.hashCode() ?: 0)
        result = 31 * result + (nesHash?.hashCode() ?: 0)
        result = 31 * result + romBytes.contentHashCode()
        result = 31 * result + region.hashCode()
        return result
    }

    companion object {
        /**
         * Snapshot a [RomContent] into a [GameSessionInfo] with a fresh byte
         * copy. The source ROM bytes are defensively copied so the service
         * owns a stable snapshot for the lifetime of one `prepareGame` /
         * `unloadGame` cycle — even if the user changes ROMs mid-evaluation,
         * an in-flight service call still has its original bytes.
         */
        fun from(content: RomContent, region: Region): GameSessionInfo = GameSessionInfo(
            displayName = content.displayName,
            virtualFilename = content.virtualFilename,
            sourcePath = content.sourcePath,
            romBytes = content.bytes.copyOf(),
            nesHash = content.hash,
            region = region,
        )

        /**
         * Build a [GameSessionInfo] directly from a [LoadedRom] (legacy
         * seam used by tests that build a [GamePak] directly rather than
         * going through [RomContentExtractor]). The [virtualFilename] is
         * derived from the source path's stem + `.nes`, and the hash is
         * computed via the test-only SHA-256 fallback so the seam stays
         * usable without the native library.
         */
        fun fromLegacy(loaded: LoadedRom, region: Region): GameSessionInfo {
            val virtualFilename = loaded.sourcePath?.fileName?.toString()
                ?: "${loaded.gamePak.name}.nes"
            val hash = if (loaded.gamePak.rawBytes.size >= 16) {
                Sha256RomHasher.hash(loaded.gamePak.rawBytes)
            } else null
            return GameSessionInfo(
                displayName = loaded.gamePak.name,
                virtualFilename = if (virtualFilename.lowercase().endsWith(".nes")) virtualFilename
                else "$virtualFilename.nes",
                sourcePath = loaded.sourcePath,
                romBytes = loaded.gamePak.rawBytes.copyOf(),
                nesHash = hash,
                region = region,
            )
        }
    }
}
