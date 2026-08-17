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
 *    substrate.** Just the ROM fingerprint, the display name, the region, and
 *    the byte length. Anything else is by design not on the boundary.
 *
 * Constructed by [GameSessionCoordinator] from a [LoadedRom] and passed to
 * [RetroAchievementsService.prepareGame] exactly once per game session.
 */
data class GameSessionInfo(
    /** Human-readable ROM name without extension. Used for UI labels and the RA game lookup. */
    val displayName: String,
    /** Absolute path to the source file, or null for bytes-only / in-memory loads. */
    val sourcePath: Path?,
    /** Full iNES image bytes. Defensively copied so the service owns a stable snapshot. */
    val romBytes: ByteArray,
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
        if (sourcePath != other.sourcePath) return false
        if (!romBytes.contentEquals(other.romBytes)) return false
        if (region != other.region) return false
        return true
    }

    override fun hashCode(): Int {
        var result = displayName.hashCode()
        result = 31 * result + (sourcePath?.hashCode() ?: 0)
        result = 31 * result + romBytes.contentHashCode()
        result = 31 * result + region.hashCode()
        return result
    }

    companion object {
        /**
         * Snapshot a [LoadedRom] into a [GameSessionInfo] with a fresh byte
         * copy. The [GamePak.rawBytes] array is shared with the parser for
         * CPU read performance, so handing the live reference to the service
         * would let an off-thread ROM reload mutate it under the service — a
         * defensive copy here closes that race.
         */
        fun from(loaded: LoadedRom, region: Region): GameSessionInfo = GameSessionInfo(
            displayName = loaded.gamePak.name,
            sourcePath = loaded.sourcePath,
            romBytes = loaded.gamePak.rawBytes.copyOf(),
            region = region,
        )
    }
}
