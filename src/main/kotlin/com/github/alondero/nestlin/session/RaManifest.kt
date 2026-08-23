package com.github.alondero.nestlin.session

import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.sun.jna.Platform
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

/**
 * Per-platform integrity + version manifest for the vendored native
 * RetroAchievements façade (issue #273 AC: "Runtime selection validates
 * platform/architecture, checksum, and pinned rcheevos version").
 *
 * The build pipeline emits a single `MANIFEST.json` at
 * `build/resources/main/native-ra/MANIFEST.json` containing one
 * [Entry] per supported platform (Windows/Linux/macOS, x86_64 for all
 * three plus the universal macOS arm64 slice). At runtime,
 * [RaManifest.loadForCurrentPlatform] picks the matching entry, verifies
 * the bundled library's SHA-256, and returns the validated manifest to
 * [RaFacadeBindings.load] for the version-pinning check.
 *
 * ## Why a manifest file (not embedded literals)?
 *
 * Embedding the SHA-256 in Kotlin code would force a rebuild every time
 * the façade is recompiled. Embedding it in the C source via a
 * `#define` would force a rebuild of the C library every time the
 * manifest changes. A sidecar JSON keeps the C build hermetic and
 * lets the Gradle task emit it from the same source-of-truth that
 * emits the shared library.
 *
 * ## Why not sign the library?
 *
 * Code-signing the `.dll`/`.so`/`.dylib` is the standard answer for
 * "this binary was built by us", but it adds a signing infrastructure
 * (cert, key, timestamp server) and the unsigned Nestlin JAR is already
 * signed at the JNLP level via the GitHub release attestation. For a
 * personal hobby project, a SHA-256 pinned at build time is the right
 * amount of integrity — a tampered-with library fails the check before
 * the JVM calls into it.
 *
 * ## Failure modes
 *
 * Every failure funnels through [LoadResult.Failure] with a [reason]
 * code + human-readable [message]. The factory logs the reason and
 * falls back to [NoOpRetroAchievementsService]. The reason enum is
 * kept stable so diagnostic tooling can group failures.
 */
data class RaManifest(
    /** Schema version of the JSON file (currently `1`). Bump on format changes. */
    val schemaVersion: Int,
    /** Pinned rcheevos version that every bundled library was compiled against. */
    val rcheevosVersion: String,
    /** Pinned façade version that every bundled library was compiled against. */
    val facadeVersion: String,
    /** One entry per platform/arch combination. */
    val platforms: List<Entry>,
) {
    /**
     * One platform's library entry.
     *
     * @property platformId `windows-x86_64`, `linux-x86_64`, or `macos-universal`.
     * @property libraryFilename File inside the JAR resources — e.g.
     *   `rcheevos_facade.dll`, `librcheevos_facade.so`, `librcheevos_facade.dylib`.
     * @property resourcePath Slash-separated path inside the JAR
     *   (`native-ra/windows/rcheevos_facade.dll`).
     * @property sha256Hex Lowercase 64-char hex digest of the library bytes.
     * @property sizeBytes Library size in bytes (sanity-check against truncation).
     */
    data class Entry(
        val platformId: String,
        val libraryFilename: String,
        val resourcePath: String,
        val sha256Hex: String,
        val sizeBytes: Long,
    )

    /**
     * Result of [loadForCurrentPlatform]. Success carries the chosen
     * entry + the verified SHA-256; Failure carries a [Reason] +
     * human-readable message.
     */
    sealed interface LoadResult {
        data class Success(
            val entry: Entry,
            val actualSha256Hex: String,
            /** Already-verified library bytes — passes through to
             *  RaFacadeBindings.extractBundledLibrary so the resource
             *  stream isn't reopened for the temp-file write. */
            val libraryBytes: ByteArray,
            val actualRcheevosVersion: String?,
            val actualFacadeVersion: String?,
        ) : LoadResult

        data class Failure(
            val reason: Reason,
            val message: String,
        ) : LoadResult

        enum class Reason {
            /** Manifest JSON not found in the JAR resources. */
            MANIFEST_MISSING,
            /** Manifest JSON present but malformed (Gson failed to parse). */
            MANIFEST_MALFORMED,
            /** No entry in the manifest matches the current OS+arch. */
            PLATFORM_UNSUPPORTED,
            /** Bundled library file for the matching entry is absent. */
            LIBRARY_MISSING,
            /** Library size doesn't match the manifest's `sizeBytes` (truncation / corruption). */
            SIZE_MISMATCH,
            /** Library SHA-256 doesn't match the manifest's `sha256Hex`. */
            CHECKSUM_MISMATCH,
            /** Library loaded but `ra_facade_rcheevos_version()` returned the wrong version. */
            RCHEEVOS_VERSION_MISMATCH,
            /** Library loaded but `ra_facade_version()` returned the wrong version. */
            FACADE_VERSION_MISMATCH,
            /** Native library failed to load (JNA raised UnsatisfiedLinkError, etc.). */
            NATIVE_LOAD_FAILED,
        }
    }

    companion object {
        /** Manifest resource path inside the JAR. */
        const val RESOURCE_PATH: String = "native-ra/MANIFEST.json"

        /**
         * Pick the manifest entry for the current OS+architecture.
         *
         * Mapping rules:
         *  - Windows + amd64 → `windows-x86_64`
         *  - Linux   + amd64 → `linux-x86_64`
         *  - macOS (any arch, since we ship universal) → `macos-universal`
         *  - anything else → unsupported
         *
         * The macOS bundle is intentionally universal: the build pipeline
         * produces a single `librcheevos_facade.dylib` containing both
         * x86_64 and arm64 slices. We don't differentiate at runtime; JNA
         * resolves the right slice for the host CPU via the standard
         * dyld search.
         */
        fun currentPlatformId(): String? = when {
            Platform.isWindows() && Platform.is64Bit() -> "windows-x86_64"
            Platform.isLinux() && Platform.is64Bit() -> "linux-x86_64"
            Platform.isMac() && Platform.is64Bit() -> "macos-universal"
            else -> null
        }

        /**
         * Load and parse the manifest JSON from the JAR resources.
         * Returns null on any IO/parse failure (the load path treats
         * null as "fall back to NoOp" with a [LoadResult.Failure]
         * reason of [LoadResult.Reason.MANIFEST_MISSING]).
         */
        fun loadManifest(): RaManifest? {
            val stream = RaManifest::class.java.classLoader
                .getResourceAsStream(RESOURCE_PATH) ?: return null
            return try {
                stream.use { s ->
                    Gson().fromJson(InputStreamReader(s, StandardCharsets.UTF_8), RaManifest::class.java)
                }
            } catch (e: JsonSyntaxException) {
                null
            }
        }

        /**
         * Load the manifest, find the current-platform entry, and verify
         * the bundled library's SHA-256. Does NOT load the native library
         * or call into rcheevos — that's [RaFacadeBindings.load]'s job.
         *
         * Returns a [LoadResult] — never throws. All failures are
         * reported via [LoadResult.Failure] with a stable [Reason] code.
         */
        fun loadForCurrentPlatform(): LoadResult {
            val manifest = loadManifest() ?: return LoadResult.Failure(
                reason = LoadResult.Reason.MANIFEST_MISSING,
                message = "Manifest not bundled in JAR (expected at $RESOURCE_PATH).",
            )

            val platformId = currentPlatformId() ?: return LoadResult.Failure(
                reason = LoadResult.Reason.PLATFORM_UNSUPPORTED,
                message = "Unsupported OS/architecture: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}.",
            )

            val entry = manifest.platforms.firstOrNull { it.platformId == platformId }
                ?: return LoadResult.Failure(
                    reason = LoadResult.Reason.PLATFORM_UNSUPPORTED,
                    message = "Manifest has no entry for platform '$platformId'.",
                )

            val resourceStream = RaManifest::class.java.classLoader
                .getResourceAsStream(entry.resourcePath) ?: return LoadResult.Failure(
                reason = LoadResult.Reason.LIBRARY_MISSING,
                message = "Bundled library missing for $platformId (expected at ${entry.resourcePath}).",
            )

            // Read the library bytes ONCE here; the returned
            // LoadResult.Success carries them so the caller (load() in
            // RaFacadeBindings) can extract to a temp file without
            // reopening the JAR resource stream. Avoids a redundant
            // resource read for every JNA callback install.
            val bytes = resourceStream.use { it.readAllBytes() }
            if (bytes.size.toLong() != entry.sizeBytes) {
                return LoadResult.Failure(
                    reason = LoadResult.Reason.SIZE_MISMATCH,
                    message = "Library size ${bytes.size}B != manifest ${entry.sizeBytes}B for $platformId " +
                        "(truncated or corrupt bundle).",
                )
            }

            val actualSha = sha256Hex(bytes)
            if (!actualSha.equals(entry.sha256Hex, ignoreCase = true)) {
                return LoadResult.Failure(
                    reason = LoadResult.Reason.CHECKSUM_MISMATCH,
                    message = "Library SHA-256 mismatch for $platformId " +
                        "(expected ${entry.sha256Hex.take(12)}…, got ${actualSha.take(12)}…).",
                )
            }

            return LoadResult.Success(
                entry = entry,
                actualSha256Hex = actualSha,
                // Carry the verified bytes through so RaFacadeBindings
                // can write them to a temp file without re-reading the
                // JAR resource. Eliminates one classloader.getResource
                // round-trip per load (issue #273 review).
                libraryBytes = bytes,
                actualRcheevosVersion = null,
                actualFacadeVersion = null,
            )
        }

        /**
         * Lowercase 64-char hex SHA-256 of [bytes]. Used by the manifest
         * verification path and by the build scripts when generating the
         * JSON sidecar. Allocates a [MessageDigest] per call — fine for
         * the load path (once per JVM lifetime) but do NOT call inside
         * a tight loop.
         */
        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(bytes)
            val sb = StringBuilder(64)
            for (b in hash) {
                sb.append(((b.toInt() ushr 4) and 0xF).toString(16))
                sb.append((b.toInt() and 0xF).toString(16))
            }
            return sb.toString()
        }

        /**
         * Internal test-only hook: parse a manifest JSON string into
         * a [RaManifest]. Returns null on parse failure. Used by the
         * synthetic-fixture tests in [RaManifestParseAndValidateTest]
         * to exercise the parsing path without depending on the JAR
         * classpath.
         */
        @JvmStatic
        fun fromJsonForTesting(json: String): RaManifest? {
            return try {
                Gson().fromJson(json, RaManifest::class.java)
            } catch (e: JsonSyntaxException) {
                null
            }
        }

        /**
         * Internal test-only hook: run the size + SHA + entry lookup
         * checks against an already-parsed [RaManifest]. Mirrors
         * [loadForCurrentPlatform]'s logic without touching the JAR
         * classpath. The production caller still goes through
         * [loadForCurrentPlatform]; this is for unit tests only.
         */
        @JvmStatic
        fun validateForTesting(
            manifest: RaManifest,
            platformId: String,
            libraryBytes: ByteArray,
        ): LoadResult {
            val entry = manifest.platforms.firstOrNull { it.platformId == platformId }
                ?: return LoadResult.Failure(
                    reason = LoadResult.Reason.PLATFORM_UNSUPPORTED,
                    message = "Manifest has no entry for platform '$platformId'.",
                )
            if (libraryBytes.size.toLong() != entry.sizeBytes) {
                return LoadResult.Failure(
                    reason = LoadResult.Reason.SIZE_MISMATCH,
                    message = "Library size ${libraryBytes.size}B != manifest ${entry.sizeBytes}B for $platformId " +
                        "(truncated or corrupt bundle).",
                )
            }
            val actualSha = sha256Hex(libraryBytes)
            if (!actualSha.equals(entry.sha256Hex, ignoreCase = true)) {
                return LoadResult.Failure(
                    reason = LoadResult.Reason.CHECKSUM_MISMATCH,
                    message = "Library SHA-256 mismatch for $platformId " +
                        "(expected ${entry.sha256Hex.take(12)}…, got ${actualSha.take(12)}…).",
                )
            }
            return LoadResult.Success(
                entry = entry,
                actualSha256Hex = actualSha,
                libraryBytes = libraryBytes,
                actualRcheevosVersion = null,
                actualFacadeVersion = null,
            )
        }
    }
}
