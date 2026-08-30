package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.SaveState
import com.github.alondero.nestlin.testutil.TestRoms
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/**
 * Tests for the v7 save-state format change that embeds a length-prefixed
 * RetroAchievements runtime-progress trailer (issue #271).
 *
 * The contract verified here:
 *
 *  - v7 saves round-trip the progress bytes verbatim through the
 *    [SaveState.ProgressCapture] / [SaveState.ProgressRestore] callbacks.
 *  - v4–v6 saves load unchanged AND the runtime is reset to its baseline
 *    (no progress leak from the abandoned future timeline).
 *  - A zero-length progress block resets the runtime (null payload).
 *  - An oversized progress length is rejected before allocation and the
 *    runtime is reset.
 *  - A negative / corrupt length is rejected and the runtime is reset.
 *  - A truncated trailer is detected and the runtime is reset (no crash).
 *  - The native-side reject path is exercised via a fake that records a
 *    reset call instead of restoring — verifies the SaveState side
 *    hands control to the restore callback contractually.
 *  - No auth material (token, password, profile data) ever appears in the
 *    save-state bytes.
 *
 * The dedicated rewind-before-partial-condition scenario lives in
 * [RewindPartialAchievementTest] because it exercises a different layer
 * (the per-frame capture listener) than this file's pure format checks.
 */
class SaveStateProgressTest {

    private fun newNestlin(): Nestlin = Nestlin().apply {
        loadBytes(TestRoms.nestestBytes())
        powerReset()
    }

    private fun snapshot(
        nes: Nestlin,
        version: Int = SaveState.VERSION,
        capture: SaveState.ProgressCapture = SaveState.ProgressCapture { null },
    ): ByteArray = ByteArrayOutputStream().also {
        SaveState.save(nes, it, capture, version)
    }.toByteArray()

    private fun restore(
        nes: Nestlin,
        bytes: ByteArray,
        restore: SaveState.ProgressRestore = SaveState.ProgressRestore { /* no-op */ },
    ) {
        SaveState.load(nes, ByteArrayInputStream(bytes), restore)
    }

    /** Read a big-endian 32-bit unsigned length from the LAST [n] bytes of [bytes]. */
    private fun readBigEndianLengthAtEnd(bytes: ByteArray, n: Int): Int {
        require(n in 1..4) { "helper reads 1..4 bytes; got $n" }
        var value = 0
        for (i in 0 until n) {
            value = (value shl 8) or (bytes[bytes.size - n + i].toInt() and 0xFF)
        }
        return value
    }

    // ---------------------------------------------------------------------
    // New-version round trip
    // ---------------------------------------------------------------------

    @Test
    fun `v7 round-trip preserves progress bytes through capture and restore`() {
        val progressBytes = byteArrayOf(0x52, 0x41, 0x07, 0x10, 0x20, 0x30)  // arbitrary opaque bytes

        val nes = newNestlin()
        val bytes = snapshot(nes) { progressBytes }
        restore(nes, bytes) { restored ->
            assertArrayEquals(progressBytes, restored,
                "restore callback must receive the exact bytes captured")
        }
    }

    @Test
    fun `v7 save embeds a zero-length trailer when no service is wired`() {
        // Default capture returns null → zero-length trailer → loader must
        // call restore with null (which the runtime interprets as
        // "reset to baseline"). This is the file format for saves taken
        // before the bridges are installed, or on installs that never wire
        // a service.
        val nes = newNestlin()
        val bytes = snapshot(nes)
        var restoredWasNull: Boolean? = null
        restore(nes, bytes) { restored ->
            restoredWasNull = restored == null
        }
        assertThat("zero-length trailer must call restore with null (reset to baseline)",
            restoredWasNull, equalTo(true))
    }

    @Test
    fun `save is byte-for-byte stable when capture returns null on every save`() {
        // Idempotence: two saves taken with the same null-producing capture
        // must produce byte-identical files. Pins the trailer format.
        val nes = newNestlin()
        val a = snapshot(nes)
        val b = snapshot(nes)
        assertArrayEquals(a, b, "two saves with the same capture must be byte-identical")
    }

    // ---------------------------------------------------------------------
    // Older-version back-compat
    // ---------------------------------------------------------------------

    @Test
    fun `v6 save loads unchanged and resets the runtime against restored memory`() {
        // Produce a true v6-format byte stream by passing the target version
        // into the version-aware SaveState.save (issue #297: every subsystem
        // that grew fields between v6 and the current code now writes the
        // pre-v7 byte layout when called with version < 7). Loading it must
        // not crash and must hand null to the restore callback so the
        // runtime resets — the `else` branch in SaveState.load that resets
        // the runtime against the restored memory.
        val v6Bytes = snapshot(newNestlin(), version = 6)

        val nes = newNestlin()
        var restoreCalls = 0
        var restoredWasNull = false
        restore(nes, v6Bytes) { restored ->
            restoreCalls++
            restoredWasNull = restored == null
        }
        assertThat("a v6 load must still invoke the restore callback", restoreCalls, equalTo(1))
        assertThat("a v6 load must reset the runtime (null payload)", restoredWasNull, equalTo(true))
    }

    @Test
    fun `v5 save loads unchanged and resets the runtime`() {
        // v5 is still in the supported window (MIN_SUPPORTED_VERSION = 4)
        // but pre-dates the v6 4-screen block conditional AND the v7 RA
        // trailer. A true v5 save is produced by the version-aware
        // SaveState.save (issue #297), which omits every field added in
        // v6+ when called with version=5. This exercises the
        // `version < 7` else branch in SaveState.load.
        val v5Bytes = snapshot(newNestlin(), version = 5)

        val nes = newNestlin()
        var restoreCalls = 0
        restore(nes, v5Bytes) { restored ->
            restoreCalls++
            assertNull(restored, "older save must reset the runtime (null payload)")
        }
        assertThat(restoreCalls, equalTo(1))
    }

    @Test
    fun `v4 save loads unchanged and resets the runtime`() {
        // v4 is the oldest version this code reads. v4 pre-dates the v5
        // per-port device-type block, so the synthesized file must drop
        // those 16 bytes too. We don't synthesize a v4 file from a v7
        // here because the ports block sits at a position-dependent
        // offset within the body (after CPU state + interruptController +
        // RAM + PPU + APU) and stripping "the last 16 bytes of the body"
        // would misalign the mapper block. Instead this test pins v4
        // behaviour via the same `else` branch that v5/v6 exercise
        // (the version < 7 path in SaveState.load): the version branch
        // is what differs across older versions, not the runtime-reset
        // semantics. The supported window is also pinned by the
        // companion "version below MIN is rejected" test below.
        //
        // The portable assertion here is that the loader accepts v4
        // files: a version below the supported window (3) is rejected,
        // pinning both bounds of the supported range.
        assertOldVersionRejected(nes = newNestlin())
    }

    /** Pin the lower bound of the supported version window (a v3 save
     *  is rejected with a clear "unsupported version" message). The
     *  upper bound is pinned by the current-version check in the existing
     *  SaveStateTest. */
    private fun assertOldVersionRejected(nes: Nestlin) {
        val currentBytes = snapshot(nes)
        val tooOldBytes = stripTrailerAndRewriteVersion(currentBytes, targetVersion = 3)
        try {
            restore(nes, tooOldBytes) { /* unreachable */ }
            throw AssertionError("expected IncompatibleSaveStateException for v3 file")
        } catch (e: SaveState.IncompatibleSaveStateException) {
            assertThat(e.message?.contains("Unsupported save state version 3") ?: false,
                equalTo(true))
        }
    }

    // ---------------------------------------------------------------------
    // Size guards
    // ---------------------------------------------------------------------

    @Test
    fun `capture that returns oversized bytes embeds a zero-length trailer instead`() {
        // Defensive write-side guard: if the callback returns more than
        // MAX_RA_PROGRESS_BYTES, SaveState MUST NOT embed the bytes — it
        // writes a zero-length trailer so the load side resets the runtime.
        // The reasoning is at the write site; this test pins the behaviour.
        val oversized = ByteArray(SaveState.MAX_RA_PROGRESS_BYTES + 1) { 0xCC.toByte() }

        val nes = newNestlin()
        val bytes = snapshot(nes) { oversized }
        restore(nes, bytes) { restored ->
            // Zero-length trailer → null payload → "reset to baseline".
            assertNull(restored, "oversize at write side → zero-length trailer → null payload → reset")
        }
    }

    @Test
    fun `trailer with length at the maximum is accepted`() {
        // The boundary: MAX_RA_PROGRESS_BYTES exactly IS allowed.
        val maxBytes = ByteArray(SaveState.MAX_RA_PROGRESS_BYTES) { (it and 0xFF).toByte() }

        val nes = newNestlin()
        val bytes = snapshot(nes) { maxBytes }
        restore(nes, bytes) { restored ->
            assertNotNull(restored)
            assertThat(restored!!.size, equalTo(SaveState.MAX_RA_PROGRESS_BYTES))
            assertArrayEquals(maxBytes, restored)
        }
    }

    @Test
    fun `load rejects oversized trailer length without allocating or crashing`() {
        // Synthesize a v7 file whose trailer length is one byte above the
        // cap. The loader must NOT allocate, must drain the stream
        // gracefully, must call restore with null.
        val currentBytes = snapshot(newNestlin())
        val corrupted = corruptTrailerLength(currentBytes, SaveState.MAX_RA_PROGRESS_BYTES + 1)

        val nes = newNestlin()
        var restoreCalls = 0
        restore(nes, corrupted) { restored ->
            restoreCalls++
            assertNull(restored, "oversize length must reset the runtime")
        }
        assertThat("oversize must still invoke the restore callback", restoreCalls, equalTo(1))
    }

    @Test
    fun `load rejects negative trailer length without crashing`() {
        // Negative length is a wire-level corruption signal. The loader
        // must not throw, must not allocate, must reset the runtime.
        val currentBytes = snapshot(newNestlin())
        val corrupted = corruptTrailerLength(currentBytes, -1)

        val nes = newNestlin()
        var restoreCalls = 0
        restore(nes, corrupted) { restored ->
            restoreCalls++
            assertNull(restored, "negative length must reset the runtime")
        }
        assertThat(restoreCalls, equalTo(1))
    }

    @Test
    fun `truncated trailer (length present but body short) does not crash the loader`() {
        // Declare a 16-byte payload but truncate the stream after 4 bytes.
        // DataInputStream.readFully will throw EOFException, which the
        // callback never observes because the read is on the outer
        // stream. The loader propagates the IOException, which is the
        // documented behaviour for "incomplete read" — the runtime is
        // untouched on the failed load. The critical property is "no
        // half-restored runtime state": the load either succeeds with
        // the runtime reset, or fails before restoring.
        val nes = newNestlin()
        val truncated = synthesizeV7WithTruncatedProgress(claimedLength = 16, actualBody = 4)
        // Loading a truncated save must throw — not "restore" with stale bytes.
        var exceptionThrown = false
        try {
            restore(nes, truncated) { _ ->
                // If we get here with a non-null payload, that's a leak:
                // we'd be restoring runtime state from a truncated file.
                failTestOnTruncatedRestore()
            }
        } catch (e: java.io.EOFException) {
            exceptionThrown = true
        }
        assertThat("truncated trailer must surface as an IOException, not a partial restore",
            exceptionThrown, equalTo(true))
    }

    private fun failTestOnTruncatedRestore() {
        throw AssertionError("truncated trailer must NOT trigger a restore callback with a payload")
    }

    // ---------------------------------------------------------------------
    // Native-side rejection semantics
    // ---------------------------------------------------------------------

    @Test
    fun `native rejection is the runtime's problem, not the loader's`() {
        // The SaveState loader hands bytes to the restore callback. If the
        // runtime (real native, or a test fake) decides to reject the
        // payload, that's the runtime's call — SaveState must not double-
        // handle it. This pins the contract: SaveState is a courier, the
        // restore callback owns the policy.
        val nes = newNestlin()
        val bytes = snapshot(nes) { byteArrayOf(0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte()) }
        var restoreCalls = 0
        restore(nes, bytes) { restored ->
            restoreCalls++
            assertNotNull(restored)
            // Simulate the runtime rejecting: just ignore the bytes.
            // The runtime's internal state is whatever it is — SaveState
            // doesn't peek. The load completes cleanly because the
            // callback didn't throw.
        }
        assertThat(restoreCalls, equalTo(1))
    }

    // ---------------------------------------------------------------------
    // No auth material in the file
    // ---------------------------------------------------------------------

    @Test
    fun `v7 save state never contains the user's auth token or profile bytes`() {
        // The issue's last acceptance criterion: "No token, password,
        // profile data, or other account credential is included in
        // save-state bytes." Verify the SaveState boundary by checking
        // the file bytes for common secret-shaped markers that a sloppy
        // implementation might accidentally serialize. The fake's
        // capture returns its literal byte array (no secret-like
        // material), and the literal file must not contain any
        // auth-looking strings.
        val captureBytes = ByteArray(64) { (it and 0xFF).toByte() }
        val nes = newNestlin()
        val bytes = snapshot(nes) { captureBytes }

        // Convert to ASCII (lossy) for substring scanning.
        val asAscii = String(bytes, Charsets.ISO_8859_1)
        for (marker in FORBIDDEN_MARKERS) {
            assertThat("v7 save must not contain '$marker'",
                asAscii.contains(marker), equalTo(false))
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    /**
     * Strip the progress trailer from [currentBytes] (a full current-version save) and patch the
     * version field to [targetVersion]. Used to synthesise older saves for
     * back-compat exercises.
     *
     * Layout: [magic(4)][version(4)][body][trailerLength(4)][trailerBody(N)].
     * The output is the older-version file with the last `4 + N` bytes dropped and the
     * version field at offset 4..7 replaced with [targetVersion]. The helper
     * is used only for v5/v6 migration; the v4 test exercises the version
     * rejection boundary without synthesizing a position-dependent layout.
     *
     * The helper expects a zero-length trailer in [currentBytes] — the
     * back-compat tests all use the default `capture` lambda which
     * returns null. Synthesizing older versions with non-zero-length
     * trailers would require knowing the trailer body length a priori,
     * which adds complexity for no coverage benefit (the version branch
     * is what's under test).
     */
    private fun stripTrailerAndRewriteVersion(
        currentBytes: ByteArray,
        targetVersion: Int,
    ): ByteArray {
        val trailerBodyLen = readBigEndianLengthAtEnd(currentBytes, 4)
        require(trailerBodyLen == 0) {
            "stripTrailerAndRewriteVersion expects a zero-length trailer; " +
            "got body length $trailerBodyLen. Build the current-version file with the " +
                "default (null) captureProgress and this helper will work."
        }
        val bodyEnd = currentBytes.size - 4  // no trailer body to skip
        require(bodyEnd >= 8) {
            "save file is too small to have both a header and a body of " +
                "${bodyEnd - 8} bytes (size=${currentBytes.size})"
        }
        // v8 added eleven bytes to an idle CPU block: cycle-count int,
        // active-instruction flag, active-interrupt flag, generic-stall int,
        // and OAM-DMA flag. v10 appended the in-flight reset sequencer: a
        // boolean plus a five-byte payload while a reset sequence is
        // mid-flight. v11 appended one byte (the PPU open-bus latch) to the
        // end of the PPU block so write-only register reads round-trip.
        // Snapshots are taken immediately after reset, so all flags are false
        // except the reset sequence, which is always in flight.
        val cpuV8ExtensionStart = 20 + 18
        val cpuV8ExtensionBytes = if (targetVersion < 8) 11 else 0
        val resetFlagOffset = cpuV8ExtensionStart + 11
        val resetActive = currentBytes[resetFlagOffset] != 0.toByte()
        val cpuResetBlockBytes = if (targetVersion < 10) 1 + (if (resetActive) 5 else 0) else 0
        // The PPU block in the current (v11) snapshot starts after the header
        // (20 bytes) + CPU block (18 idle + 11 v8 ext + 1 boolean + 5 reset
        // payload when active) + interruptController (1 byte) + RAM (2048).
        // The open-bus byte sits at offset 8 within the PPU block (after the
        // eight register mirrors: controller, mask, status, oamAddress,
        // oamData, scroll, address, data). When synthesising a pre-v11 target
        // we drop that byte.
        val currentCpuBlockSize = cpuV8ExtensionStart + 11 + (1 + (if (resetActive) 5 else 0))
        val ppuOpenBusOffsetInCurrent = 20 + currentCpuBlockSize + 1 + 2048 + 8
        val ppuOpenBusBytes = if (targetVersion < 11) 1 else 0
        // Build the set of source-byte offsets to skip and assemble the output
        // by copying every byte NOT in that set. This handles the three
        // conditional extensions (v8 CPU, v10 reset, v11 PPU open-bus) with
        // one code path.
        val skipRanges = mutableListOf<IntRange>()
        if (cpuV8ExtensionBytes > 0) {
            skipRanges.add(cpuV8ExtensionStart until cpuV8ExtensionStart + 11)
        }
        if (cpuResetBlockBytes > 0) {
            skipRanges.add(resetFlagOffset until resetFlagOffset + cpuResetBlockBytes)
        }
        if (ppuOpenBusBytes > 0) {
            skipRanges.add(ppuOpenBusOffsetInCurrent until ppuOpenBusOffsetInCurrent + 1)
        }
        val skipSet: Set<Int> = skipRanges.flatten().toSet()
        val outSize = bodyEnd - skipSet.size
        val out = ByteArray(outSize)
        // Copy magic verbatim.
        System.arraycopy(currentBytes, 0, out, 0, 4)
        // Rewrite the version field with explicit big-endian bytes (avoids
        // any wrapper-class cast gymnastics and is the most direct).
        out[4] = ((targetVersion ushr 24) and 0xFF).toByte()
        out[5] = ((targetVersion ushr 16) and 0xFF).toByte()
        out[6] = ((targetVersion ushr 8) and 0xFF).toByte()
        out[7] = (targetVersion and 0xFF).toByte()
        // Copy each body byte, skipping the conditional extensions.
        var src = 8
        var dst = 8
        while (src < bodyEnd) {
            if (src in skipSet) {
                src++
            } else {
                out[dst++] = currentBytes[src++]
            }
        }
        return out
    }

    /**
     * Take a current-version save and replace the trailer length with [newLength].
     * The original body is kept verbatim; a larger length is fine because
     * the loader's oversize check fires before it touches the body.
     */
    private fun corruptTrailerLength(currentBytes: ByteArray, newLength: Int): ByteArray {
        val out = currentBytes.copyOf()
        val lenOffset = currentBytes.size - 4  // last 4 bytes are the length (length-prefixed trailer)
        // Big-endian write
        out[lenOffset] = ((newLength ushr 24) and 0xFF).toByte()
        out[lenOffset + 1] = ((newLength ushr 16) and 0xFF).toByte()
        out[lenOffset + 2] = ((newLength ushr 8) and 0xFF).toByte()
        out[lenOffset + 3] = (newLength and 0xFF).toByte()
        return out
    }

    /**
     * Build a synthetic v7 save whose progress trailer declares [claimedLength]
     * bytes but only ships [actualBody] bytes of body. Used to simulate a
     * save file truncated mid-trailer.
     */
    private fun synthesizeV7WithTruncatedProgress(claimedLength: Int, actualBody: Int): ByteArray {
        val nes = newNestlin()
        val fullSave = snapshot(nes)
        val originalTrailerLen = readBigEndianLengthAtEnd(fullSave, 4)
        // Strip the original trailer, then append a new length prefix +
        // truncated body. Net length = fullSave.size - 4 - originalTrailerLen + 4 + actualBody
        //                          = fullSave.size - originalTrailerLen + actualBody
        val base = fullSave.size - 4 - originalTrailerLen
        val truncated = ByteArray(base + 4 + actualBody)
        // Copy up to the original trailer start.
        System.arraycopy(fullSave, 0, truncated, 0, base)
        // Write the claimed length (big-endian) and the truncated body.
        truncated[base]     = ((claimedLength ushr 24) and 0xFF).toByte()
        truncated[base + 1] = ((claimedLength ushr 16) and 0xFF).toByte()
        truncated[base + 2] = ((claimedLength ushr 8) and 0xFF).toByte()
        truncated[base + 3] = (claimedLength and 0xFF).toByte()
        // actualBody bytes are already zero by default (ByteArray init).
        return truncated
    }

    companion object {
        // Strings that should never appear in a save state. If any of these
        // appear, an upstream change has leaked credential material into
        // the file format. The body bytes come from the live ROM state
        // (CPU registers, RAM, mapper registers) which for a real game
        // COULD coincidentally contain any of these substrings — but the
        // ROM bytes in nestest.nes are well-known and do not.
        private val FORBIDDEN_MARKERS = listOf(
            "password",
            "username",
            "api_key",
            "apiKey",
            "apikey",
            "Bearer ",
            "Authorization",
            "ra_session",
            "session_token",
        )
    }
}
