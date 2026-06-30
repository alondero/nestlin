package com.github.alondero.nestlin.compare

import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.ConcurrentHashMap

/**
 * Singleton pool of long-running Mesen2 subprocesses, one per ROM path.
 *
 * The motivation is GitHub issue #61: the cross-emulator test suite
 * (`src/test/kotlin/.../compare/`) was booting a fresh Mesen2 process per
 * state/screenshot capture, paying ~0.5–1s of `--testRunner` cold-boot cost
 * each time. With ~30+ Mesen2-backed assertions per full suite, that
 * dominated wall time. This pool reduces it to one Mesen2 process per
 * unique ROM, lazy-initialised on first call.
 *
 * ## Why per-ROM and not per-suite
 *
 * `docs/TESTING_STRATEGY.md:193-195` (Phase 2) sketched a single Mesen2
 * process per suite with a Lua "test server" that swaps ROMs. That required
 * `emu.loadRom(path)`, which **does not exist in Mesen2 v2.1.1** (verified
 * 2026-06-29 against the upstream `LuaDocumentation.json`). The only way
 * to change the running ROM is exit + relaunch — so the suite can't share
 * one process across GxROM/Gaiden/Klax/MicroMachines etc. (different ROMs).
 *
 * Per-ROM keying gives:
 * - Multi-frame same-ROM suites collapse to one process (Klax frames
 *   30/60/240; MicroMachines 13-frame sweep). The per-process boot cost
 *   is paid once.
 * - Different-ROM tests still cost a boot each — but the test inventory
 *   in `Mesen2Session` shows only ~10–15 unique ROMs across the full
 *   suite, so the total is bounded.
 *
 * ## Lifecycle
 *
 * - `forRom(rom)` lazy-boots a Mesen2 instance if none cached for that path.
 *   Subsequent calls return the cached instance. Thread-safe via
 *   `ConcurrentHashMap.computeIfAbsent`.
 * - A JVM shutdown hook calls `closeAll()` on exit. Graceful exit drains
 *   in-flight sessions; an OS kill leaks processes (accepted follow-up).
 *
 * ## Strict mode
 *
 * When `NESTLIN_REQUIRE_MESEN2` is set in the environment, a missing
 * Mesen2 must be a hard FAIL, not a skip (matches `RequiresMesen2Condition`
 * semantics). Tests that gate on `assumeTrue(Mesen2StateCapturer.isMesen2Available(), ...)`
 * should switch to `Mesen2Session.isAvailable()` for the same behaviour —
 * or, more simply, use the `@RequiresMesen2` annotation which honours
 * strict mode automatically.
 */
object Mesen2Session {

    private val sessions = ConcurrentHashMap<Path, Mesen2ProcessInstance>()

    private val strict: Boolean
        get() = !System.getenv("NESTLIN_REQUIRE_MESEN2").isNullOrBlank()

    init {
        // Best-effort cleanup on JVM exit. If the Gradle worker is killed
        // (Ctrl+C, daemon recycle), this hook fires before the JVM terminates
        // and Mesen2 processes get a chance to quit cleanly. `close()` itself
        // forces termination if the QUIT command doesn't return within 5s.
        Runtime.getRuntime().addShutdownHook(Thread {
            closeAll()
        })
    }

    /**
     * Returns the canonical Mesen2 executable path. Tries, in order:
     *
     *  1. `MESEN2_PATH` environment variable (the user-facing knob).
     *  2. `mesen2.path` system property (for IDE / Gradle property overrides).
     *  3. The absolute parent path `X:/src/nestlin/tools/Mesen2/Mesen.exe`
     *     if it exists — makes worktrees zero-config on Adam's machine.
     *  4. The relative fallback `tools/Mesen2/Mesen.exe`.
     *
     * This unifies the previously-duplicated resolution logic in
     * [Mesen2Process.mesen2Path] and [Mesen2StateCapturer.getMesen2Path]
     * (the latter incorrectly defaulted to `tools/Mesen/Mesen.exe` — Mesen v1,
     * which has no Lua support).
     */
    fun mesen2Path(): Path {
        System.getenv("MESEN2_PATH")?.let { return Paths.get(it) }
        System.getProperty("mesen2.path")?.let { return Paths.get(it) }
        val absolute = Paths.get("X:/src/nestlin/tools/Mesen2/Mesen.exe")
        if (absolute.toFile().exists()) return absolute
        return Paths.get("tools/Mesen2/Mesen.exe")
    }

    /**
     * True if the resolved Mesen2 executable exists on disk.
     *
     * Note: this does NOT check that Mesen2 will actually run (no display,
     * I/O permissions, etc.) — `Mesen2ProcessInstance` does that on boot.
     */
    fun isAvailable(): Boolean = mesen2Path().toFile().exists()

    /**
     * Returns the cached Mesen2 process instance for [rom], booting a fresh
     * one if no cached entry exists.
     *
     * Throws `Mesen2NotFoundException` if Mesen2 is unavailable (callers
     * that want skip-not-fail semantics should `assumeTrue(isAvailable(), ...)`
     * first, exactly as today's tests do). The wrappers
     * ([Mesen2StateCapturer.captureState], [Mesen2ReferenceRunner.captureFrame])
     * do NOT call this method directly — they call the per-instance methods
     * on the returned instance.
     */
    fun forRom(rom: Path): Mesen2ProcessInstance {
        if (!isAvailable()) {
            val resolved = mesen2Path().toAbsolutePath()
            val message = "Mesen2 executable not found at $resolved. " +
                "Set MESEN2_PATH (or -Dmesen2.path) to your Mesen2.exe, " +
                "or unset NESTLIN_REQUIRE_MESEN2 to skip. " +
                "Remember the Gradle daemon may hold stale env: ./gradlew --stop, then re-run."
            if (strict) throw IllegalStateException(message)
            throw Mesen2NotFoundException(message)
        }
        val abs = rom.toAbsolutePath()
        return sessions.computeIfAbsent(abs) { Mesen2ProcessInstance(it) }
    }

    /**
     * Tears down all live Mesen2 sessions. Idempotent. Called automatically
     * on JVM exit via the shutdown hook; safe to invoke manually from
     * `@AfterAll` lifecycle methods if a test wants eager cleanup.
     */
    fun closeAll() {
        val snapshot = sessions.values.toList()
        sessions.clear()
        for (instance in snapshot) {
            runCatching { instance.close() }
        }
    }
}
