package com.github.alondero.nestlin.session

import java.security.MessageDigest

/**
 * Computes the official RetroAchievements NES hash for a buffer of NES bytes
 * (issue #269 AC #2).
 *
 * The official hash is the routine rcheevos uses internally when it
 * identifies a loaded game. Nestlin calls this directly so the hash is
 * available BEFORE the first emulated frame — the boot placard and the
 * "is this ROM recognized?" UI both want to display the hash immediately,
 * and waiting for rcheevos's async identify round-trip would defeat the
 * "complete or fail before first frame" AC.
 *
 * ## Algorithm
 *
 * The official NES hash is NOT a simple SHA-256 over the bytes. It is a
 * multi-pass checksum that ignores the iNES header, walks the PRG-ROM
 * multiple times with different stride patterns, and folds the CHR-ROM in
 * at specific offsets. rcheevos documents the algorithm in `rc_hash_rom.c`
 * and exposes it through `rc_hash_generate_from_buffer` with
 * `RC_CONSOLE_NINTENDO`.
 *
 * ## Bridge to rcheevos
 *
 * Production wiring calls the C façade via [NativeRomHasher]. The JNA
 * binding is a one-call symbol (`ra_facade_hash_nes_rom`) added in
 * issue #269; it wraps `rc_hash_generate_from_buffer` so the output is
 * byte-identical to the hash rcheevos would compute internally for the
 * same bytes.
 *
 * ## Test wiring
 *
 * Tests use [Sha256RomHasher] (a deterministic SHA-256 over the bytes)
 * when the native library isn't loaded. The acceptance test against a
 * known-fixture byte pattern still passes against the C façade; the SHA-256
 * variant is purely for tests that don't have the native library on the
 * classpath (e.g. the @Tag("mesen") lane on a worktree without Mesen2).
 *
 * The interface is a single function so tests can substitute freely.
 */
fun interface RomHasher {
    /**
     * Compute the NES hash for [bytes]. Returns a 32-character lowercase
     * hex string (no NUL terminator). Throws on null input.
     */
    fun hash(bytes: ByteArray): String

    companion object {
        /**
         * Test-only hasher: SHA-256 over the NES bytes, truncated to the
         * 32-hex-char shape rcheevos uses. Two different ROMs always
         * produce different hashes; the same ROM always produces the same
         * hash; and the SHA-256 hash is structurally indistinguishable
         * from the official hash for assertion purposes (it's still a
         * stable, 32-hex-char, content-derived identifier).
         */
        fun sha256(): RomHasher = Sha256RomHasher
    }
}

/**
 * Deterministic SHA-256 over the raw NES bytes, truncated to the
 * documented 32-hex-char shape. Used by tests that don't have the
 * native library on the classpath — see [RomHasher.sha256].
 *
 * NOT used in production: production goes through the C façade to get the
 * official rcheevos hash. Substituting SHA-256 in tests keeps the test
 * surface independent of the native library's presence, which is the
 * project convention from issue #267.
 */
object Sha256RomHasher : RomHasher {
    override fun hash(bytes: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        val digest = md.digest(bytes)
        // Truncate to 32 hex chars (16 bytes) so the shape matches the
        // official RA NES hash. The leading 16 bytes of SHA-256 are
        // more than enough collision-resistance for the test-only
        // use case (the production hasher is the official one).
        return digest.joinToString("") { "%02x".format(it) }.substring(0, 32)
    }
}
