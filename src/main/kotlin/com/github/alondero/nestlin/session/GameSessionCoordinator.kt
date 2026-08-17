package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.Nestlin
import java.nio.file.Path

/**
 * Application-supplied callbacks the coordinator fires around every
 * ROM-lifecycle transition.
 *
 * The coordinator itself owns **emulator and service** state. Anything that
 * is host-specific (JavaFX scene graph, file chooser, input thread,
 * rewind-buffer UI, the movie recorder) lives in the application and
 * is reached through these hooks. Two of the hooks are essential for the
 * documented ordering; the others are convenience.
 *
 * The defaults are no-ops, so a CLI caller (which has no movie, no UI,
 * no emulation thread) can construct a coordinator with `GameSessionHooks.NONE`
 * and get the same correct service state machine without a single callback.
 */
data class GameSessionHooks(
    /**
     * Fires immediately before the coordinator unloads the current ROM.
     * The application MUST use this to cancel any active movie session
     * (a movie is per-ROM and meaningless once the ROM changes), and to
     * surface the "ROM changing" intent to anything else that owns
     * per-ROM state outside the coordinator's purview.
     *
     * Called once per [GameSessionCoordinator.loadRom] / `loadBytes` /
     * `powerReset` / `unloadRom` / `shutdown` call, BEFORE the
     * `service.unloadGame` step in the documented ordering.
     */
    val onBeforeRomChange: () -> Unit = {},

    /**
     * Fires immediately after the coordinator has installed the new ROM,
     * reset the machine, restored the new battery, and asked the service
     * to prepare. The application uses this to refresh the title bar, the
     * save-slot menu (CRC changed), the memory-editor flash, the recent-ROM
     * list, and any other UI that was tracking the old ROM's identity.
     *
     * Called once per successful load / power-reset / unload. The argument
     * is null on `unloadRom` (no ROM is loaded afterwards); non-null
     * otherwise. `shutdown` does NOT fire this hook — by the time
     * shutdown returns, the application is about to be torn down.
     */
    val onAfterRomChange: (LoadedRom?) -> Unit = {},

    /**
     * Fires whenever the coordinator calls into the service so the
     * application can pause/resume the emulation thread around the call.
     * A real RA `prepareGame` may need a network round-trip and the
     * application may want to keep the emulation thread alive while it
     * runs; a CLI driver has no thread and can leave this empty.
     *
     * The default is no-op; UI applications should typically call
     * `stopEmulation()` here and `startEmulation()` after.
     */
    val onServiceCallStart: () -> Unit = {},

    /**
     * Fires after every service call returns. Pairs with
     * [onServiceCallStart]. Same defaulting policy.
     */
    val onServiceCallEnd: () -> Unit = {},
) {
    companion object {
        /** No-op hooks. Suitable for CLI / tests / any non-UI caller. */
        val NONE: GameSessionHooks = GameSessionHooks()
    }
}

/**
 * Single boundary for every ROM/install/reset/unload/shutdown operation in
 * Nestlin.
 *
 * ## Why this exists (issue #266)
 *
 * Before the coordinator, the application directly sequenced the ROM
 * lifecycle: `nestlin.saveBatteryRam(old); nestlin.load(new);
 * nestlin.powerReset(); nestlin.loadBatteryRam(new); …`. That sequence was
 * duplicated in the file chooser, the recent-ROM menu, the CLI startup
 * path, the hard-reset menu, the movie record/play reset, the application
 * shutdown handler, and the test seams. Forgetting any step in any path
 * produced silent corruption (a stale battery file, a half-prepared
 * service session, a rewind buffer from the previous ROM, etc.).
 *
 * The coordinator is the one place the ordering is correct, and every
 * production path goes through it. The interface to the optional
 * RetroAchievements service is the only seam; the rest of the application
 * never sees native pointers, JNA, the rcheevos client, or networking.
 *
 * ## Documented ordering (issue #266, mirrored exactly here)
 *
 * For **ROM replacement** (`loadRom` / `loadBytes` / `powerReset`):
 *
 * ```
 *   hooks.onBeforeRomChange()     // cancel movie, per-ROM UI teardown
 *   flush old battery RAM         // nestlin.saveBatteryRam(oldPath)
 *   service.unloadGame()          // release the previous game's runtime
 *   install new ROM               // nestlin.load / loadBytes
 *   reset the machine             // nestlin.powerReset
 *   load new battery RAM          // nestlin.loadBatteryRam(newPath)
 *   service.prepareGame(info)     // may fail; coordinator swallows
 *   hooks.onAfterRomChange(rom)   // UI refresh against the new identity
 * ```
 *
 * For **soft reset** (`softReset`): one `service.resetRuntime()` call —
 * the same game stays loaded, the RAM persists, the runtime condition
 * progress is reset to its post-`prepareGame` baseline.
 *
 * For **unload** (`unloadRom`): one `service.unloadGame()` after clearing
 * the emulator state. Idempotent.
 *
 * For **shutdown** (`shutdown`): `service.unloadGame()` (if active) then
 * `service.shutdown()`. Idempotent.
 *
 * The coordinator does NOT stop/start the emulation thread — that is the
 * application's responsibility. The hooks exist so the application can
 * observe the moments that matter (before/after ROM change, around
 * service calls), but the application owns the thread.
 *
 * ## Deferred to later issues
 *
 * Three of the AC items in issue #266 land in this slice; the rest are
 * pinned here as forward references so future issues (#267, #268) can
 * pick them up without rediscovering the design:
 *
 *  - **AC #4 — "await signed-in service before first frame."** The
 *    current `prepareGame` returns `Boolean` synchronously and the
 *    coordinator calls it inline. An async / future-based return shape
 *    (so a real RA client can spend seconds on a network round-trip
 *    while the emulation thread stays paused) is the natural extension.
 *    Lives behind the same `RetroAchievementsService.prepareGame`
 *    seam; production callers already bracket with
 *    `stopEmulation / startEmulation` so they can hold the thread
 *    without changes.
 *  - **AC #2 (per-frame wiring) + `evaluateFrame` plumbing.** The
 *    per-frame wiring lands in #268 alongside the
 *    `captureProgress` / `restoreProgress` save-state slot manager
 *    hooks. [evaluateFrame] and [resetServiceRuntime] are the seams;
 *    [MovieInput.applyCommands] now passes the latter so FM2 row-level
 *    resets already fire `service.resetRuntime` in the UI live
 *    playback path.
 *  - **AC #10 — central service construction.** Production callers
 *    construct `GameSessionCoordinator` themselves (Application owns
 *    one as a `lazy` field; CLIs construct per-boot). #267 will land
 *    a real RA client behind a central factory so the no-op vs real
 *    switch is one-line.
 *
 * ## Service failure policy
 *
 * `service.prepareGame` is contractually non-throwing and returns a
 * boolean. The coordinator calls it after the new ROM is installed and
 * unconditionally proceeds — a `false` return or an exception is logged
 * and treated as "no achievements for this session". The application
 * sees a normal playable session either way. This is the rule from the
 * parent PRD: a failing achievements service MUST NOT prevent gameplay.
 */
class GameSessionCoordinator(
    /**
     * The emulator whose lifecycle the coordinator drives. Public so tests
     * can assert on the same Nestlin state the coordinator mutated.
     */
    val nestlin: Nestlin,
    /**
     * The optional RA service seam. The default for production is
     * [NoOpRetroAchievementsService]. Tests inject a recording fake to
     * assert on the call order.
     */
    val service: RetroAchievementsService,
    /**
     * Application-supplied callbacks. Default [GameSessionHooks.NONE] is
     * the right choice for any non-UI caller.
     */
    val hooks: GameSessionHooks = GameSessionHooks.NONE,
) {

    /**
     * Install [path] as the new active ROM.
     *
     * Implements the full ROM-replacement ordering. The current ROM (if
     * any) has its battery RAM flushed and its service session unloaded
     * before the new ROM is installed; the new ROM's battery is restored
     * after reset and the service is asked to prepare the new game.
     *
     * @param path iNES / NES 2.0 file on disk.
     */
    fun loadRom(path: Path) {
        val oldPath = nestlin.loadedRom?.sourcePath
        hooks.onBeforeRomChange()
        if (oldPath != null) {
            // Flush the outgoing battery before the mapper is replaced. After
            // nestlin.load(path) the mapper may have entirely different PRG-RAM
            // (and a different path), so any unflushed write from the previous
            // game would be lost anyway — but explicitly saving here also
            // handles the "load new ROM then crash" half-open case.
            nestlin.saveBatteryRam(oldPath)
        }
        runService { service.unloadGame() }
        nestlin.load(path)
        nestlin.powerReset()
        nestlin.loadBatteryRam(path)
        prepareServiceForCurrent()
        hooks.onAfterRomChange(nestlin.loadedRom)
    }

    /**
     * Install an in-memory ROM as the new active ROM. Same ordering as
     * [loadRom] except there is no disk battery to flush or restore (a
     * bytes-only load has no `.sav` file). Used by test fixtures and the
     * FM2 replay tool that synthesises ROMs from disk + patch arrays.
     */
    fun loadBytes(romData: ByteArray, displayName: String = "nestest") {
        val oldPath = nestlin.loadedRom?.sourcePath
        hooks.onBeforeRomChange()
        if (oldPath != null) nestlin.saveBatteryRam(oldPath)
        runService { service.unloadGame() }
        nestlin.loadBytes(romData, displayName)
        nestlin.powerReset()
        prepareServiceForCurrent()
        hooks.onAfterRomChange(nestlin.loadedRom)
    }

    /**
     * Power-cycle the emulator with the current ROM. Battery RAM is
     * preserved across the cycle (real cartridges keep their battery
     * alive when the console is off). The service is unloaded and
     * re-prepared so the runtime is on a fresh post-boot timeline.
     *
     * No-op if no ROM is loaded — the user is sitting on the empty
     * boot screen, there is nothing to power-cycle.
     */
    fun powerReset() {
        val rom = nestlin.loadedRom ?: return  // No ROM is loaded — nothing to power-cycle.
        // For path-based loads: save, reload, reset, restore — the reload
        // is what gives us a fully zeroed mapper (some boards' internal
        // registers persist across a plain `cpu.reset` and need the full
        // reconstructor to come back to power-on defaults).
        // For bytes-only loads: there is no path to re-read, so we
        // accept that the mapper state is "as reset" rather than
        // "as brand-new" — still correct, just cheaper. The service
        // still gets the full UnloadGame + PrepareGame pair so its
        // runtime is rebuilt on a fresh post-prepareGame baseline,
        // matching the path-based branch and the "Reset... produce the
        // documented service lifecycle events exactly once" AC.
        val path = rom.sourcePath
        if (path != null) {
            hooks.onBeforeRomChange()
            nestlin.saveBatteryRam(path)
            runService { service.unloadGame() }
            nestlin.load(path)
            nestlin.powerReset()
            nestlin.loadBatteryRam(path)
            prepareServiceForCurrent()
            hooks.onAfterRomChange(nestlin.loadedRom)
        } else {
            // Bytes-only: no battery to flush, no mapper to reconstruct
            // from disk. The service still sees the canonical
            // UnloadGame + PrepareGame sequence so its state machine is
            // in sync with the just-power-cycled engine.
            hooks.onBeforeRomChange()
            runService { service.unloadGame() }
            nestlin.powerReset()
            prepareServiceForCurrent()
            hooks.onAfterRomChange(nestlin.loadedRom)
        }
    }

    /**
     * Soft reset (RESET button on the console): the CPU vectors to RESET
     * with RAM preserved. The service runtime is reset so condition
     * progress is back to its post-`prepareGame` baseline.
     */
    fun softReset() {
        nestlin.softReset()
        runService { service.resetRuntime() }
    }

    /**
     * Clear the active ROM entirely. Used by the "ROM unload" entry
     * point — the application wants the emulator to be back at the
     * empty boot screen. Idempotent: a second call is a no-op.
     */
    fun unloadRom() {
        hooks.onBeforeRomChange()
        nestlin.loadedRom?.sourcePath?.let { nestlin.saveBatteryRam(it) }
        runService { service.unloadGame() }
        nestlin.unload()
        hooks.onAfterRomChange(null)
    }

    /**
     * Permanent shutdown. Unloads the current game (if any) and tears the
     * service down. Idempotent — calling shutdown twice is a no-op.
     *
     * The application is responsible for stopping the emulation thread
     * before calling this; the coordinator does not own the thread.
     */
    fun shutdown() {
        nestlin.loadedRom?.sourcePath?.let { nestlin.saveBatteryRam(it) }
        runService { service.unloadGame() }
        runService { service.shutdown() }
    }

    /**
     * Capture the current service-side condition progress, or null if the
     * service is idle. Used by the save-state slot manager to embed
     * achievement progress in a `.nstl` (issue #268 will extend the
     * on-disk format; this method is the seam).
     */
    fun captureProgress(): ByteArray? {
        return if (nestlin.loadedRom == null) null
        else runService { service.serializeProgress() }
    }

    /**
     * Restore service-side condition progress. A `null` or empty buffer
     * resets the runtime to its post-`prepareGame` state. Used by the
     * save-state slot manager to pull achievement progress back from a
     * `.nstl`. Safe to call when no game is loaded — the next
     * `prepareGame` re-establishes the runtime baseline.
     */
    fun restoreProgress(progress: ByteArray?) {
        if (nestlin.loadedRom == null) return
        runService { service.restoreProgress(progress) }
    }

    /**
     * Forward one emulated frame's worth of state into the service's
     * runtime. Called by the per-frame wiring (issue #268) once per
     * completed frame; the [frameIndex] is the monotonic frame counter
     * for the active `prepareGame` / `unloadGame` cycle (0 immediately
     * after a prepare or reset, increasing by 1 each call).
     *
     * No-op when no game is currently loaded — the coordinator does not
     * own the frame counter, so the wiring around it is responsible for
     * skipping frames across a load/unload transition.
     */
    fun evaluateFrame(frameIndex: Long) {
        if (nestlin.loadedRom == null) return
        runService { service.evaluateFrame(frameIndex) }
    }

    /**
     * Service-only runtime reset — does NOT touch the CPU, the mapper, or
     * the loaded ROM, and does NOT fire `onBeforeRomChange` /
     * `onAfterRomChange`. Used by the FM2 row-level reset commands
     * (issue #125), which fire inside `MovieInput.applyCommands` after
     * the in-place `nestlin.softReset()` / `nestlin.powerReset()` that
     * `applyCommands` already issued. Distinct from [softReset] /
     * [powerReset], which couple CPU + service resets.
     *
     * No-op when no ROM is currently loaded — the service has no active
     * game session to reset.
     */
    fun resetServiceRuntime() {
        if (nestlin.loadedRom == null) return
        runService { service.resetRuntime() }
    }

    /**
     * Build a fresh [GameSessionInfo] for the current ROM and ask the
     * service to prepare it. A failure (returning `false` or throwing)
     * is logged and swallowed — gameplay proceeds with the no-op fallback.
     */
    private fun prepareServiceForCurrent() {
        val rom = nestlin.loadedRom ?: return
        val info = GameSessionInfo.from(rom, nestlin.regionConfig.region)
        runService {
            try {
                service.prepareGame(info)
            } catch (t: Throwable) {
                // Parent PRD: "a failing achievements service MUST NOT
                // prevent gameplay". A throw inside the service is the
                // same failure mode as a `false` return — log once,
                // continue with an idle service for this session.
                System.err.println("[GAME-SESSION] prepareGame threw ${t.javaClass.simpleName}: ${t.message}")
            }
        }
    }

    /**
     * Run a service call under the start/end hooks. Centralises the
     * `onServiceCallStart` / `onServiceCallEnd` bookends so every
     * service call (and only service calls) gets the same UI
     * opportunity to pause / resume the emulation thread.
     *
     * Returns whatever the [block] returns; the start/end hooks fire
     * around the call regardless of the return type. A failure inside
     * the block is still allowed to propagate (the no-op never throws;
     * a real impl must catch its own failures internally per the
     * [RetroAchievementsService] contract).
     */
    private inline fun <T> runService(block: () -> T): T {
        hooks.onServiceCallStart()
        try {
            return block()
        } finally {
            hooks.onServiceCallEnd()
        }
    }
}
