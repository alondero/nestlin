package com.github.alondero.nestlin.session

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.util.Redactor
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
 * ## Issue #269: achievement loading before first frame
 *
 * `service.prepareGame` is a **blocking-with-timeout** call. The
 * coordinator hands the service a budget (default
 * [DEFAULT_PREPARE_TIMEOUT_MS]) and the service either settles within
 * the budget or returns `false`. The first emulated frame is therefore
 * never blocked past the budget — issue #269 AC #4 ("signed-in game
 * activation completes or fails before the first emulated frame") and
 * AC #5 ("bounded by explicit timeouts").
 *
 * The coordinator owns the [placardController]. After `prepareGame`
 * settles, the coordinator calls [RetroAchievementsService.gameSummary] to
 * learn whether the ROM was recognized, has core achievements, etc., and
 * publishes the appropriate [BootPlacardEvent]. The JavaFX-side boot-placard
 * binds a listener on `placardController` and renders whatever the latest
 * event implies. The generation guard ensures rapid ROM / sign-in switches
 * discard stale completions (AC #10).
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
    /**
     * Boot-placard controller (issue #269). Owns the generation counter
     * that guards against rapid ROM / sign-in switches. UI consumers bind
     * a listener to observe the latest [BootPlacardEvent].
     *
     * The default is a fresh [RaBootPlacardController] so a CLI / test
     * caller that doesn't care about the placard can construct a
     * coordinator without supplying one.
     */
    val placardController: RaBootPlacardController = RaBootPlacardController(),
    /**
     * Queued notification controller (issue #270). Owns the FIFO unlock
     * queue and the single-slot system banner. The coordinator calls
     * [RaNotificationController.markRomChange] on every ROM lifecycle
     * transition so the UI doesn't display stale "Reconnected — syncing
     * pending unlocks" messages for the previous game.
     *
     * Default: a fresh [RaNotificationController] so a CLI / test caller
     * can construct a coordinator without supplying one. Production wires
     * a shared instance from `Application` so the render pump and the
     * coordinator publish / observe on the same controller.
     */
    val notificationController: RaNotificationController = RaNotificationController(),
    /**
     * Achievements-window controller (issue #272). Bumped in lock-step
     * with [placardController] so the achievements window's generation
     * guard stays in sync with the boot placard. UI consumers bind a
     * listener to receive the latest [RaAchievementsWindowViewModel].
     *
     * The default is `null` so a CLI / test caller that doesn't care
     * about the window can construct a coordinator without supplying
     * one. The application wires the controller after construction
     * (Application.kt); the coordinator bumps its generation in lock-
     * step with the placard but does NOT auto-refresh the snapshot
     * — the application drives [RaAchievementsController.refresh]
     * from its hook surface (ROM change, sign-in transition).
     */
    val achievementsController: RaAchievementsController? = null,
    /**
     * Application-supplied callback invoked on every native
     * `ACHIEVEMENT_TRIGGERED` / `ACHIEVEMENT_CHALLENGE_*` /
     * `ACHIEVEMENT_PROGRESS_*` event the façade drains
     * (issue #288). The coordinator wires it onto
     * [NativeRetroAchievementsService.achievementEventListener] in
     * [init] (mirrors [installServiceNotificationListener] for the
     * unlock/system banner path).
     *
     * Null by default — CLI / test callers that don't surface the
     * achievements window pass nothing and the drain path's
     * `?.let { dispatchAchievementEvent(it, ...) }` becomes a no-op.
     *
     * PR #290 review replaced the original `achievementEventBus`
     * bus-class coupling with this single-listener pattern that
     * mirrors `notificationListener`. Reason: a sealed
     * `RaAchievementEvent` + `RetroAchievementsEventBus` +
     * `ListenerToken` surface was over-engineered for the single
     * subscriber (the achievements window's refresh path) — every
     * variant carried the same `achievementId: Int` payload the
     * listener discarded.
     */
    val onAchievementEvent: ((RaAchievementEvent) -> Unit)? = null,
    /**
     * Hasher used to compute the canonical NES hash for [RomContent]
     * instances loaded from disk. Production uses [NativeRomHasher]; tests
     * and CLI paths without the native library fall back to
     * [Sha256RomHasher].
     */
    private val romHasher: RomHasher = Sha256RomHasher,
    /**
     * Maximum time (ms) to block on `service.prepareGame` before the first
     * emulated frame is allowed (issue #269 AC #5). Real RA achievement
     * fetches typically settle in <2s on warm caches; 10s gives plenty
     * of headroom for cold caches and slow networks.
     */
    val prepareTimeoutMillis: Long = DEFAULT_PREPARE_TIMEOUT_MS,
) {

    init {
        // Issue #270: the coordinator owns the per-frame evaluation hook
        // that runs on the emulation thread inside the PPU's
        // frame-completion listener. Installing it here means every
        // GameSessionCoordinator (production, CLI, tests) gets the same
        // ordering guarantee — evaluateFrame fires BEFORE the rewind
        // snapshot is captured.
        nestlin.preFrameCaptureHook = { evaluateFrameNext() }
        // Wire the native service's notification listener to this
        // controller. For NoOpRetroAchievementsService this is a no-op
        // (nothing emits notifications); for the native service it
        // forwards unlock + system banners into the controller's FIFO
        // queue. The cast is safe because the listener field is internal
        // to the native service; the coordinator only sets it when the
        // service exposes the field — but exposing it as a method would
        // pollute the public interface.
        installServiceNotificationListener()
        // Issue #288: wire the application-supplied
        // [onAchievementEvent] callback onto the native service's
        // [NativeRetroAchievementsService.achievementEventListener].
        // Same package-private cast pattern as
        // [installServiceNotificationListener] — the listener field
        // is internal to the native service.
        installServiceAchievementEventListener()
    }

    // Issue #270: monotonic frame index for [evaluateFrame]. Reset on
    // every successful prepareGame so a new ROM starts at frame 0 — the
    // runtime's "this is a fresh timeline" semantic aligns with the
    // coordinator's "new ROM, new frame index" semantic.
    @Volatile
    private var frameCounter: Long = 0L

    /**
     * Install the side-effect-free memory reader (issue #270 AC). Called
     * automatically after every successful [prepareServiceForCurrent] so
     * rcheevos's read_memory callback resolves to [Memory.peek] rather
     * than the (non-existent) live [Memory.get] path.
     *
     * For [NoOpRetroAchievementsService] this is a no-op (the default
     * interface implementation).
     */
    private fun installMemoryReader() {
        val reader = peekReader(nestlin.memory)
        runService { service.installMemoryReader(reader) }
    }

    /**
     * Wire [NativeRetroAchievementsService.notificationListener] to this
     * controller's [publishNotification]. The cast is safe — the
     * notificationListener field is package-private and only the native
     * service has it; [NoOpRetroAchievementsService] doesn't expose it.
     */
    private fun installServiceNotificationListener() {
        val native = service as? NativeRetroAchievementsService ?: return
        native.notificationListener = { n -> publishNotification(n) }
    }

    /**
     * Wire the application-supplied [onAchievementEvent] callback onto
     * [NativeRetroAchievementsService.achievementEventListener]
     * (issue #288). The cast is safe — the listener field is
     * package-private and only the native service has it.
     *
     * No-op when the service is the no-op (nothing emits events) or
     * when [onAchievementEvent] is null (a CLI / test caller that
     * doesn't care about the achievements window).
     */
    private fun installServiceAchievementEventListener() {
        val native = service as? NativeRetroAchievementsService ?: return
        val listener = onAchievementEvent ?: return
        native.achievementEventListener = listener
    }

    /**
     * Publish a [RaNotification] into the controller with a fresh
     * [System.currentTimeMillis] timestamp. Called from the native
     * service's event drain — on the emulation thread, synchronously.
     */
    private fun publishNotification(n: RaNotification) {
        when (n) {
            is UnlockNotification -> notificationController.publishUnlock(
                achievementId = n.achievementId,
                title = n.title,
                description = n.description,
                points = n.points,
                badgeUrl = n.badgeUrl,
                nowMillis = System.currentTimeMillis(),
            )
            is SystemNotification -> notificationController.publishSystem(
                severity = n.severity,
                message = n.message,
                nowMillis = System.currentTimeMillis(),
            )
        }
    }

    /**
     * Per-frame seam: bumps the frame counter and pushes one frame's
     * state into the active runtime. Called from
     * [com.github.alondero.nestlin.Nestlin.preFrameCaptureHook] — the
     * hook is registered in [init] so every coordinator gets the same
     * ordering vs the rewind snapshot.
     *
     * The frame counter resets to 0 on every successful prepareGame —
     * "this ROM's frame 0" means the same thing across a fresh boot and
     * a rewind.
     */
    fun evaluateFrameNext() {
        if (nestlin.loadedRom == null) return
        val idx = frameCounter
        frameCounter = idx + 1
        try {
            service.evaluateFrame(idx)
        } catch (t: Throwable) {
            // Defensive: a service-side bug must not propagate into the
            // emulation thread's frame-completion listener. The message
            // is run through the Redactor so a misbehaving service that
            // echoes credentials / tokens in its exception can't leak.
            System.err.println(
                "[GAME-SESSION] evaluateFrame threw ${t.javaClass.simpleName}: " +
                    Redactor.redactMessage(t.message)
            )
        }
    }

    /**
     * Install [path] as the new active ROM.
     *
     * Implements the full ROM-replacement ordering. The current ROM (if
     * any) has its battery RAM flushed and its service session unloaded
     * before the new ROM is installed; the new ROM's battery is restored
     * after reset and the service is asked to prepare the new game.
     *
     * The path is run through [RomContentExtractor] so plain and archived
     * forms of identical NES bytes identify identically (issue #269 AC #1-3).
     * The resulting [RomContent] becomes the [GameSessionInfo] handed to
     * `service.prepareGame`.
     *
     * @param path iNES / NES 2.0 file on disk (.nes or .7z).
     * @param archiveEntryName explicit NES entry name for multi-entry archives;
     *   null = pick the only entry (single-entry archives) or fail (multi-entry).
     */
    fun loadRom(path: Path, archiveEntryName: String? = null) {
        val oldPath = nestlin.loadedRom?.sourcePath
        hooks.onBeforeRomChange()
        bumpGenerations()
        // Issue #270: clear queued unlock + system banners from the
        // previous game. Same generation-bump policy as the placard —
        // a slow notification publish from the previous game can't
        // reach the new game's UI.
        notificationController.markRomChange()
        if (oldPath != null) {
            // Flush the outgoing battery before the mapper is replaced.
            nestlin.saveBatteryRam(oldPath)
        }
        runService { service.unloadGame() }
        val content = try {
            if (path.toString().lowercase().endsWith(".7z")) {
                RomContentExtractor.extract(path, romHasher, archiveEntryName)
            } else {
                // Plain .nes: read the file, compute the canonical identity,
                // then hand the SAME bytes to nestlin.loadBytes so the
                // mapper sees the exact bytes we hashed (avoids a TOCTOU
                // window where the file could change between hash + load).
                val bytes = RomArchiveReader.readPlain(path)
                RomContentExtractor.fromBytes(
                    bytes = bytes,
                    displayName = path.fileName.toString().substringBeforeLast('.'),
                    sourcePath = path,
                    hasher = romHasher,
                )
            }
        } catch (e: RomArchiveException) {
            publishServiceUnavailable(e.message ?: "Could not load ROM")
            throw e
        }
        // Install the ROM. Plain .nes loads re-read from disk via
        // nestlin.load(path) — the bytes Nestlin loads are guaranteed
        // identical to the ones we hashed because we just read the same
        // file. 7z loads install the extracted bytes directly.
        if (path.toString().lowercase().endsWith(".7z")) {
            nestlin.loadBytes(content.bytes, displayName = content.displayName)
        } else {
            nestlin.load(path)
        }
        nestlin.powerReset()
        nestlin.loadBatteryRam(path)
        prepareServiceForCurrent(content)
        hooks.onAfterRomChange(nestlin.loadedRom)
    }

    /**
     * Install an in-memory ROM as the new active ROM. Same ordering as
     * [loadRom] except there is no disk battery to flush or restore (a
     * bytes-only load has no `.sav` file). Used by test fixtures and the
     * FM2 replay tool that synthesises ROMs from disk + patch arrays.
     *
     * The bytes are run through [RomContentExtractor.fromBytes] so the
     * resulting [RomContent] has the same shape (hash, virtual filename)
     * as a disk-loaded ROM.
     */
    fun loadBytes(romData: ByteArray, displayName: String = "nestest") {
        val oldPath = nestlin.loadedRom?.sourcePath
        hooks.onBeforeRomChange()
        bumpGenerations()
        notificationController.markRomChange()
        if (oldPath != null) nestlin.saveBatteryRam(oldPath)
        runService { service.unloadGame() }
        val content = RomContentExtractor.fromBytes(romData, displayName, hasher = romHasher)
        nestlin.loadBytes(content.bytes, displayName)
        nestlin.powerReset()
        prepareServiceForCurrent(content)
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
        val rom = nestlin.loadedRom ?: return
        val path = rom.sourcePath
        if (path != null) {
            hooks.onBeforeRomChange()
            bumpGenerations()
            notificationController.markRomChange()
            nestlin.saveBatteryRam(path)
            runService { service.unloadGame() }
            val content = RomContentExtractor.extract(path, romHasher)
            nestlin.load(path)
            nestlin.powerReset()
            nestlin.loadBatteryRam(path)
            prepareServiceForCurrent(content)
            hooks.onAfterRomChange(nestlin.loadedRom)
        } else {
            // Bytes-only: no battery to flush, no mapper to reconstruct
            // from disk. The service still sees the canonical
            // UnloadGame + PrepareGame sequence so its state machine is
            // in sync with the just-power-cycled engine.
            hooks.onBeforeRomChange()
            bumpGenerations()
            notificationController.markRomChange()
            runService { service.unloadGame() }
            nestlin.powerReset()
            val info = GameSessionInfo.fromLegacy(rom, nestlin.regionConfig.region)
            prepareServiceForCurrentFromInfo(info)
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
        bumpGenerations()
        // Issue #270: no game = nothing to unlock. Clear queued unlocks
        // + the system banner so the UI isn't showing stale state after
        // the user picks "Close ROM" from the menu.
        notificationController.markRomChange()
        nestlin.loadedRom?.sourcePath?.let { nestlin.saveBatteryRam(it) }
        runService { service.unloadGame() }
        nestlin.unload()
        placardController.clear()
        achievementsController?.clear()
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
     * achievement progress in a `.nstl`.
     */
    fun captureProgress(): ByteArray? {
        return if (nestlin.loadedRom == null) null
        else runService { service.serializeProgress() }
    }

    /**
     * Restore service-side condition progress. A `null` or empty buffer
     * resets the runtime to its post-`prepareGame` state.
     */
    fun restoreProgress(progress: ByteArray?) {
        if (nestlin.loadedRom == null) return
        runService { service.restoreProgress(progress) }
    }

    /**
     * Forward one emulated frame's worth of state into the service's
     * runtime.
     */
    fun evaluateFrame(frameIndex: Long) {
        if (nestlin.loadedRom == null) return
        runService { service.evaluateFrame(frameIndex) }
    }

    /**
     * Service-only runtime reset — does NOT touch the CPU, the mapper, or
     * the loaded ROM, and does NOT fire `onBeforeRomChange` /
     * `onAfterRomChange`. Used by FM2 row-level reset commands.
     */
    fun resetServiceRuntime() {
        if (nestlin.loadedRom == null) return
        runService { service.resetRuntime() }
    }

    /**
     * Build a fresh [GameSessionInfo] for [content] and ask the service
     * to prepare it. A failure (returning `false` or throwing) is logged
     * and swallowed — gameplay proceeds with the no-op fallback.
     *
     * After the prepare round-trip settles, the coordinator pulls a
     * [RaGameSummary] from the service and publishes the appropriate
     * [BootPlacardEvent] — recognized / recognized-no-core /
     * unrecognized / signed-out / service-unavailable — for the boot
     * placard UI to render.
     */
    private fun prepareServiceForCurrent(content: RomContent) {
        val info = GameSessionInfo.from(content, nestlin.regionConfig.region)
        prepareServiceForCurrentFromInfo(info)
    }

    /**
     * Prepare path for the bytes-only `powerReset` branch (where we don't
     * have a freshly-loaded `RomContent` to extract, only the existing
     * `LoadedRom` left in `nestlin.loadedRom`).
     */
    private fun prepareServiceForCurrentFromInfo(info: GameSessionInfo) {
        // Issue #270: every successful prepare resets the frame counter to
        // 0 — "this ROM's frame 0" means the same thing across a fresh
        // boot, a rewind, and a power cycle. Failed prepares don't reset
        // because the runtime is idle and no frames will be evaluated.
        frameCounter = 0L
        // Install the side-effect-free memory reader BEFORE prepareGame
        // so the runtime can begin evaluating against live memory the
        // moment the load settles. Doing it after prepareGame would mean
        // the first frame's triggers see zeros — a common footgun in
        // emulator achievement integrations.
        installMemoryReader()
        val signedIn = service.isSignedIn()
        if (!signedIn) {
            // AC #8: signed-out loads show NO placard and NO nag.
            runService { service.prepareGame(info, prepareTimeoutMillis) }
            placardController.publish(BootPlacardEvent.SignedOut(placardController.generation))
            return
        }
        val prepared = runService {
            try {
                service.prepareGame(info, prepareTimeoutMillis)
            } catch (t: Throwable) {
                // Route exception message through Redactor so a misbehaving
                // service that echoes credentials / tokens can't leak via
                // its own exception message (issue #273 AC).
                System.err.println(
                    "[GAME-SESSION] prepareGame threw ${t.javaClass.simpleName}: " +
                        Redactor.redactMessage(t.message)
                )
                false
            }
        }
        if (!prepared) {
            publishServiceUnavailable("Achievement service did not respond within ${prepareTimeoutMillis}ms")
            return
        }
        val summary = runService { service.gameSummary() }
        if (summary == null) {
            placardController.publish(BootPlacardEvent.Unrecognized(
                generation = placardController.generation,
                displayName = info.displayName,
                virtualFilename = info.virtualFilename,
            ))
            return
        }
        if (summary.hasCoreAchievements) {
            placardController.publish(BootPlacardEvent.Recognized(
                generation = placardController.generation,
                summary = summary,
                badgeImage = null,
            ))
        } else {
            placardController.publish(BootPlacardEvent.RecognizedNoCore(
                generation = placardController.generation,
                summary = summary,
            ))
        }
    }

    /**
     * Publish a [BootPlacardEvent.ServiceUnavailable] event. Never throws.
     */
    private fun publishServiceUnavailable(cause: String) {
        try {
            placardController.publish(BootPlacardEvent.ServiceUnavailable(
                generation = placardController.generation,
                cause = cause,
            ))
        } catch (_: Exception) {
            // Defensive: a buggy listener must not propagate into the
            // coordinator's hot path.
        }
    }

    /**
     * Restart for the sign-in-mid-game path (issue #269 AC #9).
     *
     * Performs the documented restart: flush battery RAM, reload the
     * same ROM, restore battery. The emulator state IS reset (CPU
     * registers, RAM, mapper registers) — only the battery RAM file
     * survives.
     */
    fun restartForAchievements() {
        val rom = nestlin.loadedRom ?: run {
            bumpGenerations()
            return
        }
        val path = rom.sourcePath
        if (path != null) {
            loadRom(path)
        } else {
            loadBytes(rom.gamePak.rawBytes, rom.gamePak.name)
        }
    }

    /**
     * Run a service call under the start/end hooks.
     */
    private inline fun <T> runService(block: () -> T): T {
        hooks.onServiceCallStart()
        try {
            return block()
        } finally {
            hooks.onServiceCallEnd()
        }
    }

    /**
     * Bump both the placard controller's generation and the
     * achievements controller's generation in lock-step. The two
     * controllers observe distinct events but share the same
     * generation boundary — a ROM or sign-in transition invalidates
     * every in-flight completion for both surfaces.
     */
    private fun bumpGenerations() {
        placardController.bumpGeneration()
        achievementsController?.bumpGeneration()
    }

    companion object {
        /**
         * Default upper bound on `service.prepareGame` (issue #269 AC #5).
         * Real RA achievement fetches typically settle in <2s on warm
         * caches; 10s gives plenty of headroom for cold caches and slow
         * networks while still bounding the wait before the first
         * emulated frame.
         */
        const val DEFAULT_PREPARE_TIMEOUT_MS: Long = 10_000L
    }
}
