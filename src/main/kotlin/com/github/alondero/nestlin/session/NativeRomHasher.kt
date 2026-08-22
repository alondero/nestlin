package com.github.alondero.nestlin.session

/**
 * JNA-backed [RomHasher] that calls into the C façade's
 * `ra_facade_hash_nes_rom` symbol (issue #269).
 *
 * Production wiring uses this so the hash is byte-identical to what rcheevos
 * computes internally for the same bytes. Falls back to the SHA-256 hasher
 * when the native library isn't loaded — every existing test path keeps
 * working without rcheevos_facade on the classpath, which matches the project
 * convention from issue #267.
 */
internal class NativeRomHasher(
    private val bindings: RaFacadeBindings,
) : RomHasher {

    override fun hash(bytes: ByteArray): String {
        val outHash = ByteArray(RA_FACADE_HASH_LEN)
        val rc = try {
            bindings.ra_facade_hash_nes_rom(bytes, bytes.size, outHash)
        } catch (e: UnsatisfiedLinkError) {
            return Sha256RomHasher.hash(bytes)
        }
        if (rc != RaStatus.OK) {
            // The C side returns RA_ERR_INTERNAL when the hash routine can't
            // make sense of the bytes (missing NES magic, header-only dump).
            // Fall back to SHA-256 so the coordinator still has a stable
            // identifier to log; the boot placard treats "no hash" and
            // "unknown hash" identically (subtle unrecognized explanation).
            return Sha256RomHasher.hash(bytes)
        }
        // The C side writes a NUL-terminated 32-hex-char string; trim the
        // terminator so the value matches the documented "32 lowercase hex
        // chars" contract.
        val end = outHash.indexOf(0)
        val trimmed = if (end >= 0) outHash.copyOf(end) else outHash
        return String(trimmed, Charsets.US_ASCII)
    }

    companion object {
        /** Constant — must match the C macro RA_FACADE_HASH_LEN in ra_facade.h. */
        const val RA_FACADE_HASH_LEN = 33

        /**
         * Build a hasher backed by the C façade, or fall back to SHA-256
         * when the native library isn't loaded. Callers don't have to
         * branch on library presence.
         */
        fun loadOrFallback(): RomHasher {
            val bindings = RaFacadeBindings.load() ?: return Sha256RomHasher
            return NativeRomHasher(bindings)
        }
    }
}

