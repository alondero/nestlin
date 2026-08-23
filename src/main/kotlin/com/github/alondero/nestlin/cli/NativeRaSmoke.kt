package com.github.alondero.nestlin.cli

import com.github.alondero.nestlin.session.RaEvent
import com.github.alondero.nestlin.session.RaFacadeBindings
import com.github.alondero.nestlin.session.RaManifest
import com.github.alondero.nestlin.session.RaReadMemoryFn
import com.github.alondero.nestlin.session.RaStatus
import com.github.alondero.nestlin.util.Redactor
import com.sun.jna.Pointer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Static pre-registered JNA read-memory callback. JNA rejects freshly-built
 * SAM lambdas at set_memory_reader time with
 * `Unsupported argument type NativeRaSmoke$$Lambda@xxx` because the
 * Callback proxy has to be wired at first-call site, not at every
 * invocation. A static instance bypasses the issue: JNA wraps it once
 * at first use and reuses the native trampoline for subsequent calls.
 *
 * Returns zero bytes for every read — the smoke's contract is "the
 * reader was installed successfully and was NOT called on the no-game
 * path" (we never tick evaluate_frame / idle on a bare client).
 */
private val smokeMemoryReader = RaReadMemoryFn { _, _, _ -> 0 }

/**
 * Native RetroAchievements smoke runner (issue #273 AC: "Each release
 * platform runs a native smoke test covering client lifetime, version,
 * NES hashing, mock login/game load, memory/events, progress
 * serialization, and callback teardown").
 *
 * The runner is a single CLI that produces a structured PASS/FAIL
 * block per step. CI invokes it on each release platform via the
 * multi-OS matrix (see `.github/workflows/native-ra-smoke.yml`):
 *
 *     java -jar nestlin-all.jar nra-smoke [--rom <path>]
 *
 * Exit codes:
 *   0 — every step PASS
 *   1 — one or more steps FAIL
 *   2 — usage / bad args
 *   3 — native library missing / corrupt (JNA failed to load)
 *
 * The smoke runner deliberately uses ONLY the native façade
 * ([RaFacadeBindings]). It does NOT touch the network, the UI, the
 * Kotlin-side [com.github.alondero.nestlin.session.NativeRetroAchievementsService]
 * wrapper, or the emulator core. The "mock login / game load" steps
 * verify the documented contract (forced softcore + no-network shim)
 * rather than exercising a real auth flow — a CI smoke runner that
 * talked to retroachievements.org would violate the "CI never
 * contacts production RetroAchievements" AC.
 *
 * Each step prints one `STEP <n> <name>: PASS|FAIL — <reason>` line.
 * The runner is non-interactive; stdout is the only channel.
 *
 * On a machine without the build chain, [RaFacadeBindings.load]
 * returns null and the runner exits 3 with a one-line diagnostic.
 */
object NativeRaSmoke {

    enum class Verdict(val exitCode: Int) {
        PASS(0),
        FAIL(1),
        SKIPPED_LIB_MISSING(3),
    }

    data class StepResult(
        val index: Int,
        val name: String,
        val verdict: Verdict,
        val message: String,
    )

    /**
     * Run the smoke test. Returns the worst verdict (FAIL beats PASS;
     * SKIPPED_LIB_MISSING is a special signal that the native library
     * wasn't loaded and no step was run).
     *
     * @param out where to write step results. Defaults to System.out;
     *   the CLI wrapper passes a StringBuilder for testability.
     * @param romPath optional ROM file to feed the hasher. When null
     *   a synthetic 16-byte iNES fixture is used so the smoke runner
     *   works on a fresh checkout without a ROM.
     */
    fun run(out: Appendable = System.out, romPath: Path? = null): Verdict {
        val results = mutableListOf<StepResult>()

        // Step 1: manifest presence + checksum.
        results += runStep(1, "manifest") {
            when (val pre = RaManifest.loadForCurrentPlatform()) {
                is RaManifest.LoadResult.Failure -> StepResult(
                    index = 0, name = "manifest", verdict = Verdict.FAIL,
                    message = pre.message,
                )
                is RaManifest.LoadResult.Success -> StepResult(
                    index = 0, name = "manifest", verdict = Verdict.PASS,
                    message = "platformId=${pre.entry.platformId} " +
                        "sha256=${pre.actualSha256Hex.take(12)}… " +
                        "size=${pre.entry.sizeBytes}B",
                )
            }
        }

        // Step 2: load + version. If load fails, return SKIPPED_LIB_MISSING.
        val lib: RaFacadeBindings = RaFacadeBindings.load() ?: run {
            results += StepResult(2, "load", Verdict.FAIL,
                "Native library unavailable — see prior diagnostic. (Smoke cannot continue.)")
            results.forEach { printStep(it, out) }
            return Verdict.SKIPPED_LIB_MISSING
        }
        results += StepResult(2, "load", Verdict.PASS,
            "Native library loaded; rcheevos=${lib.ra_facade_rcheevos_version()} " +
                "facade=${lib.ra_facade_version()}")

        results += runStep(3, "version") {
            val rcheevos = lib.ra_facade_rcheevos_version()
            val facade = lib.ra_facade_version()
            val ok = rcheevos == "12.4.0" && facade == "1.0.0"
            StepResult(0, "version", if (ok) Verdict.PASS else Verdict.FAIL,
                "rcheevos='$rcheevos' facade='$facade' (expected 12.4.0 / 1.0.0)")
        }

        // Step 4: client lifetime + idempotent destroy. We create a
        // single handle and pass it through the remaining steps so the
        // "callback teardown" step has a real event queue to drain.
        // The destroy contract is a release-blocking invariant; the
        // vendored rcheevos patch (see native/rcheevos/NOTICE) makes
        // rc_client_unload_game safe on bare clients, so this no
        // longer SIGABRTs.
        val handle: Pointer = lib.ra_facade_create(null, null) ?: run {
            results += StepResult(4, "client-lifetime", Verdict.FAIL,
                "ra_facade_create returned null")
            results.forEach { printStep(it, out) }
            return Verdict.FAIL
        }
        var firstDestroy = -1
        var secondDestroy = -1
        var postDestroyCreate: Pointer? = null
        val lifetimeOk = try {
            firstDestroy = lib.ra_facade_destroy(handle)
            secondDestroy = lib.ra_facade_destroy(handle)  // idempotency
            postDestroyCreate = lib.ra_facade_create(null, null)
            firstDestroy == RaStatus.OK && secondDestroy == RaStatus.OK &&
                postDestroyCreate != null
        } catch (t: Throwable) {
            // Catching here is defence-in-depth — a JNI fault from the
            // destroy path is a bug, not a test failure to surface as
            // PASS. The test's value is "no crash", not "throw".
            false
        }
        // Re-create the handle the remaining steps will use.
        val liveHandle: Pointer = postDestroyCreate ?: lib.ra_facade_create(null, null) ?: run {
            results += StepResult(4, "client-lifetime", Verdict.FAIL,
                "ra_facade_create (post-destroy) returned null; " +
                    "destroy1=$firstDestroy destroy2=$secondDestroy")
            results.forEach { printStep(it, out) }
            return Verdict.FAIL
        }
        results += StepResult(4, "client-lifetime", if (lifetimeOk) Verdict.PASS else Verdict.FAIL,
            "create=non-null destroy1=$firstDestroy destroy2=$secondDestroy (idempotent) " +
                "recreate=${if (postDestroyCreate != null) "ok" else "null"}")
        val h: Pointer = liveHandle

        results += runStep(5, "nes-hashing") {
            val fixture: ByteArray = try {
                romPath?.let { Files.readAllBytes(it).take(16384).toByteArray() }
                    ?: SYNTHETIC_INES_FIXTURE
            } catch (e: Throwable) {
                return@runStep StepResult(0, "nes-hashing", Verdict.FAIL,
                    "Could not read ROM: ${Redactor.redactMessage(e.message)}")
            }
            val out1 = ByteArray(33)
            val rc = lib.ra_facade_hash_nes_rom(fixture, fixture.size, out1)
            if (rc != RaStatus.OK) {
                StepResult(0, "nes-hashing", Verdict.FAIL,
                    "ra_facade_hash_nes_rom returned $rc")
            } else {
                // rcheevos's hash output is 32 lowercase hex chars NUL-terminated;
                // trim the trailing NUL before pattern-matching.
                val nulIdx = out1.indexOfFirst { it == 0.toByte() }
                val digest = if (nulIdx >= 0) out1.copyOf(nulIdx).toString(Charsets.US_ASCII)
                             else out1.toString(Charsets.US_ASCII).trim()
                val out2 = ByteArray(33)
                lib.ra_facade_hash_nes_rom(fixture, fixture.size, out2)
                val nulIdx2 = out2.indexOfFirst { it == 0.toByte() }
                val digest2 = if (nulIdx2 >= 0) out2.copyOf(nulIdx2).toString(Charsets.US_ASCII)
                              else out2.toString(Charsets.US_ASCII).trim()
                val ok = digest.matches(Regex("[0-9a-f]{32}")) && digest == digest2
                StepResult(0, "nes-hashing", if (ok) Verdict.PASS else Verdict.FAIL,
                    "digest='$digest' deterministic=${digest == digest2} (bytes=${fixture.size})")
            }
        }

        results += runStep(6, "mock-login") {
            // Each step creates + destroys its own handle (see note
            // above on why we don't share). Forced softcore: isSignedIn()
            // must be false right after create.
            val hStep6: Pointer? = lib.ra_facade_create(null, null)
            if (hStep6 == null) {
                return@runStep StepResult(0, "mock-login", Verdict.FAIL,
                    "ra_facade_create returned null")
            }
            val signedIn = lib.ra_facade_is_signed_in(hStep6) != 0
            val ok = !signedIn
            StepResult(0, "mock-login", if (ok) Verdict.PASS else Verdict.FAIL,
                if (ok) "isSignedIn=false after create (forced softcore honored)"
                else "isSignedIn=true after create (softcore should be forced off)")
        }

        results += runStep(7, "memory-events") {
            // Install the JNA read-memory callback via the static
            // `smokeMemoryReader` instance (see top-of-file comment on
            // why a fresh lambda fails). On a bare client the callback
            // must NOT be invoked (the façade short-circuits before
            // rcheevos is asked to read memory). We deliberately do NOT
            // tick evaluate_frame / idle here — those assume rcheevos's
            // internal scheduler is in a clean state, which only holds
            // after a prepare_game round-trip.
            val setRc = lib.ra_facade_set_memory_reader(h, smokeMemoryReader, null)
            val ev = RaEvent()
            val polled = lib.ra_facade_poll_event(h, ev)
            val ok = setRc == RaStatus.OK && polled == 0
            StepResult(0, "memory-events", if (ok) Verdict.PASS else Verdict.FAIL,
                "set_memory_reader=$setRc eventsPolled=$polled " +
                    "(expected 0 events on no-game path; no evaluate_frame / idle)")
        }

        results += runStep(8, "progress-serialization") {
            // No game loaded → progress_size returns 0, serialize_progress writes 0 bytes.
            val hStep8: Pointer? = lib.ra_facade_create(null, null)
            if (hStep8 == null) {
                return@runStep StepResult(0, "progress-serialization", Verdict.FAIL,
                    "ra_facade_create returned null")
            }
            val size = lib.ra_facade_progress_size(hStep8)
            val buf = ByteArray(64)
            val written = lib.ra_facade_serialize_progress(hStep8, buf, buf.size)
            val ok = size == 0 && written == 0
            StepResult(0, "progress-serialization", if (ok) Verdict.PASS else Verdict.FAIL,
                "progress_size=$size serialize_wrote=$written (expected 0/0 on the no-game path)")
        }

        results += runStep(9, "callback-teardown") {
            // Drain any pending events, then destroy + re-create to
            // prove no event from the previous session leaks into the
            // new one. The event queue is a per-handle resource; a
            // destroyed handle's events must not bleed across. The
            // vendored rcheevos patch makes destroy safe on a bare
            // client, so this can exercise the real teardown path
            // (instead of leaking the handle as a previous version
            // did — see issue #273 review notes).
            val ev = RaEvent()
            var drainedEvents = 0
            while (lib.ra_facade_poll_event(h, ev) != 0) drainedEvents++
            var destroyRc = -1
            var newHandle: Pointer? = null
            var newQueueHasEvent = -1
            val teardownOk = try {
                destroyRc = lib.ra_facade_destroy(h)
                newHandle = lib.ra_facade_create(null, null)
                if (newHandle != null) {
                    val newEv = RaEvent()
                    newQueueHasEvent = lib.ra_facade_poll_event(newHandle, newEv)
                }
                destroyRc == RaStatus.OK && newHandle != null && newQueueHasEvent == 0
            } catch (t: Throwable) {
                false
            }
            // Clean up the new handle too.
            if (newHandle != null) {
                try { lib.ra_facade_destroy(newHandle) } catch (_: Throwable) {}
            }
            StepResult(0, "callback-teardown", if (teardownOk) Verdict.PASS else Verdict.FAIL,
                "destroy=$destroyRc recreate=${if (newHandle != null) "ok" else "null"} " +
                    "drainedEvents=$drainedEvents newQueueEmpty=${newQueueHasEvent == 0}")
        }

        // Print + summarise.
        results.forEach { printStep(it, out) }
        val overall = if (results.all { it.verdict == Verdict.PASS }) Verdict.PASS else Verdict.FAIL
        out.appendLine("OVERALL: $overall " +
            "(${results.count { it.verdict == Verdict.PASS }}/${results.size} steps passed)")
        return overall
    }

    private inline fun runStep(idx: Int, name: String, block: () -> StepResult): StepResult {
        return try {
            block().copy(index = idx)
        } catch (e: Throwable) {
            StepResult(idx, name, Verdict.FAIL,
                "threw ${e.javaClass.simpleName}: ${Redactor.redactMessage(e.message)}")
        }
    }

    private fun printStep(step: StepResult, out: Appendable) {
        out.appendLine("STEP ${step.index} ${step.name}: ${step.verdict} — ${step.message}")
    }

    /**
     * Synthetic 16-byte iNES fixture for the hashing step. The hash
     * itself isn't checked against a known value (the rcheevos hash
     * algorithm is version-sensitive); we just assert determinism.
     */
    private val SYNTHETIC_INES_FIXTURE: ByteArray = byteArrayOf(
        0x4E, 0x45, 0x53, 0x1A,  // iNES magic
        0x01, 0x01,              // 1 × 16KB PRG, 1 × 8KB CHR
        0x00, 0x00,              // flags 6/7
        0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    )

    val USAGE = """
        usage: nestlin nra-smoke [--rom <path-to-nes>]

          --rom <path>    Optional ROM to feed the hashing step. When
                          omitted a 16-byte iNES fixture is used.

        Prints one 'STEP N name: PASS|FAIL — reason' line per step,
        then an 'OVERALL:' summary. Exits 0 on full pass, 1 on any
        step fail, 3 if the native library is missing.
    """.trimIndent()
}

object NativeRaSmokeCli {
    fun main(args: List<String>, out: Appendable = System.out): Int {
        var romPath: Path? = null
        var i = 0
        while (i < args.size) {
            when (val arg = args[i]) {
                "--rom" -> {
                    val v = args.getOrNull(++i) ?: return usage(out, "--rom requires a value")
                    romPath = Paths.get(v)
                }
                else -> {
                    if (arg.startsWith("--")) return usage(out, "unknown option '$arg'")
                    return usage(out, "unexpected positional argument '$arg'")
                }
            }
            i++
        }
        val verdict = NativeRaSmoke.run(out, romPath)
        return verdict.exitCode
    }

    private fun usage(out: Appendable, message: String): Int {
        out.appendLine("ERROR: $message")
        out.appendLine(NativeRaSmoke.USAGE)
        return 2
    }
}

/*
 * Entry point for the `nra-smoke` JavaExec task
 * (or `java -jar nestlin-all.jar nra-smoke`).
 */
fun main(args: Array<String>) {
    kotlin.system.exitProcess(NativeRaSmokeCli.main(args.toList()))
}

