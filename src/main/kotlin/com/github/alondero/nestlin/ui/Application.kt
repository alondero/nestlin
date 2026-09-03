package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.EmulatorConfig
import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.Controller
import com.github.alondero.nestlin.SaveState
import com.github.alondero.nestlin.apu.AudioResampler
import com.github.alondero.nestlin.file.load
import com.github.alondero.nestlin.input.GamepadInput
import com.github.alondero.nestlin.input.InputConfig
import com.github.alondero.nestlin.input.InputDevice
import com.github.alondero.nestlin.movie.Fm2Format
import com.github.alondero.nestlin.movie.Movie
import com.github.alondero.nestlin.movie.MovieLivePlayer
import com.github.alondero.nestlin.movie.MovieLiveRecorder
import com.github.alondero.nestlin.session.GameSessionCoordinator
import com.github.alondero.nestlin.session.GameSessionHooks
import com.github.alondero.nestlin.session.NoOpRetroAchievementsService
import com.github.alondero.nestlin.session.SystemNotification
import com.github.alondero.nestlin.session.UnlockNotification
import com.github.alondero.nestlin.movie.MovieState
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ppu.RESOLUTION_HEIGHT
import com.github.alondero.nestlin.ppu.RESOLUTION_WIDTH
import javafx.animation.AnimationTimer
import javafx.application.Application
import javafx.application.Platform
import javafx.scene.Scene
import javafx.scene.Group
import javafx.scene.control.Menu
import javafx.scene.control.MenuBar
import javafx.scene.control.MenuItem
import com.github.alondero.nestlin.Region
import javafx.scene.canvas.Canvas
import javafx.scene.image.PixelFormat
import javafx.scene.image.WritableImage
import javafx.scene.image.Image
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.stage.FileChooser
import javafx.stage.Stage
import java.io.IOException
import java.nio.file.Path
import java.nio.file.Paths
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

// Periodic SRAM flush interval. 10s matches RetroArch's default and means a crash
// loses at most ~10s of in-game saves. Skipped when batteryDirty is false (no cost).
private const val BATTERY_FLUSH_INTERVAL_MS = 10_000L

fun main(args: Array<String>) {
    // Headless `replay` subcommand (issue #62): deterministically replay an FM2 against a ROM and
    // emit a state/frame fingerprint + PNG. Dispatched before Application.launch so it never starts
    // the JavaFX toolkit — it must run on a CI box or worktree with no display, and exit with a
    // status code an agent can branch on.
    if (args.isNotEmpty() && args[0] == "replay") {
        kotlin.system.exitProcess(com.github.alondero.nestlin.cli.ReplayCli.main(args.drop(1)))
    }
    // Headless `bootcheck` subcommand: boot a ROM N frames with no display and no reference
    // emulator, print a PASS|WARN|FAIL verdict (loaded / rendered / non-blank / banks-moved).
    // The oracle-free "did this mapper actually boot a real game?" gate for delegated work.
    if (args.isNotEmpty() && args[0] == "bootcheck") {
        kotlin.system.exitProcess(com.github.alondero.nestlin.cli.BootCheckCli.main(args.drop(1)))
    }
    // Headless `nra-smoke` subcommand (issue #273): exercise the native RA façade
    // contract on a CI runner that has no display. Without this dispatch the
    // JAR's Main-Class would launch JavaFX, which throws
    // `UnsupportedOperationException: Unable to open DISPLAY` on headless hosts.
    if (args.isNotEmpty() && args[0] == "nra-smoke") {
        kotlin.system.exitProcess(com.github.alondero.nestlin.cli.NativeRaSmokeCli.main(args.drop(1)))
    }
    // Headless `ra-bench` subcommand (issue #273): measure p95 per-frame RA
    // evaluation latency + audio health. Same display-less requirement.
    if (args.isNotEmpty() && args[0] == "ra-bench") {
        kotlin.system.exitProcess(com.github.alondero.nestlin.cli.RaBenchCli.main(args.drop(1)))
    }
    Application.launch(NestlinApplication::class.java, *args)
}

class NestlinApplication : FrameListener, Application() {
    private lateinit var stage: Stage
    // Native-resolution backing image written to by the PPU each frame.
    private val frameImage = WritableImage(RESOLUTION_WIDTH, RESOLUTION_HEIGHT)
    // Canvas + GraphicsContext for pixel-perfect nearest-neighbor upscaling.
    // JavaFX's ImageView.isSmooth is unreliable on the Windows D3D pipeline (bilinear
    // filtering is applied regardless). Canvas.GraphicsContext.isImageSmoothing is
    // reliably honored across all pipelines since JavaFX 12.
    private val canvas = Canvas((RESOLUTION_WIDTH * 3).toDouble(), (RESOLUTION_HEIGHT * 3).toDouble())
    private val gc = canvas.graphicsContext2D.apply { isImageSmoothing = false }
    // Group wraps the canvas so its bounds participate normally in StackPane sizing.
    private val canvasGroup = Group(canvas)
    private val canvasHolder = StackPane(canvasGroup)
    private var nestlin = Nestlin().also { it.addFrameListener(this) }

    // Game-session coordinator (issue #266): every ROM-load / reset / unload /
    // shutdown path goes through this single orchestration point so the
    // battery-flush / service-unload / install-and-reset / battery-restore /
    // service-prepare sequence is correct in exactly one place. The hooks
    // observe the boundary; the application still owns the emulation thread
    // (per the coordinator's contract — its `onBeforeRomChange` /
    // `onAfterRomChange` are notification points, not thread-management
    // hooks).
    //
    // Issue #267: the coordinator's service is now sourced through
    // [RetroAchievementsServiceFactory], which picks the native rcheevos
    // client when the façade library is available on the search path and
    // falls back to NoOp when it isn't. The factory call is wrapped in a
    // try/catch so a corrupt library can't crash the UI at startup — the
    // worst case is the menu's "RetroAchievements" item shows disabled
    // with a tooltip explaining why.
    //
    // **Why `clearPauseState` is NOT in the hook:** it must run SYNCHRONOUSLY
    // before the caller invokes `startEmulation()` (the emulation thread
    // reads `config.paused` on its first iteration; if it's still true the
    // game boots into a paused state). The hook is fired via Platform.runLater
    // to be safe across caller threads (the boot path's worker thread, the
    // movie record/play path inside performWithEmulationPaused, or a
    // MenuItem handler on the JavaFX thread) — but that deferral races
    // startEmulation. Production call sites that need the pause-clear must
    // do it themselves, between the coordinator call and startEmulation().
    private val sessionCoordinator: GameSessionCoordinator by lazy {
        val raService = try {
            com.github.alondero.nestlin.session.RetroAchievementsServiceFactory.create()
        } catch (t: Throwable) {
            // Defensive: any factory failure (UnsatisfiedLinkError from a
            // half-built library, NoClassDefFoundError on a JNA mismatch,
            // etc.) must NOT prevent the UI from launching. The fallback
            // is the no-op; the menu indicator will reflect the absence.
            println("[APP] RetroAchievements service init failed: ${t.javaClass.simpleName}: ${t.message}")
            NoOpRetroAchievementsService
        }
        // Issue #271: wire the RA progress trailer into Nestlin's save-state
        // path. Every Nestlin.saveState() (and every per-frame rewind
        // snapshot, since the buffer stores opaque blobs) now embeds the
        // current runtime progress; every Nestlin.loadState() — including
        // back-compat v4–v6 loads — resets the runtime against the
        // restored memory through the coordinator's restoreProgress hook.
        // The bridges live on Nestlin rather than on the Application so
        // the file-format coupling stays in the same module as the file
        // format itself (SaveState.kt). The coordinator is the only knob
        // exposed to the application.
        nestlin.raProgressCapture = SaveState.ProgressCapture { sessionCoordinator.captureProgress() }
        nestlin.raProgressRestore = SaveState.ProgressRestore { progress -> sessionCoordinator.restoreProgress(progress) }
        val coord = GameSessionCoordinator(
            nestlin = nestlin,
            service = raService,
            hooks = GameSessionHooks(
                onBeforeRomChange = {
                    // Per-ROM UI teardown. cancelMovieSession must run
                    // BEFORE any emulator state mutates — a recording
                    // against ROM A is meaningless once ROM B's mapper
                    // is installed, and playback must restart from a
                    // freshly-cold-booted game.
                    cancelMovieSession()
                    // Bump the achievements controller's generation
                    // alongside the placard — the coordinator bumps its
                    // own, but the application-owned controller needs an
                    // explicit bump here so a stale image-fetch or
                    // sign-in completion cannot leak into the new ROM's
                    // window.
                    achievementsControllerLazy.bumpGeneration()
                },
                onAfterRomChange = { _ ->
                    // UI refresh against the new ROM identity. Hopped to
                    // the JavaFX thread because the coordinator's hooks
                    // may fire from any caller thread, and these mutations
                    // touch scene-graph nodes. clearPauseState is
                    // deliberately NOT here — see the comment above.
                    Platform.runLater {
                        // Drop any stale "Saved/Loaded slot N" toast from
                        // the previous ROM — the slot CRC changes
                        // underneath the user.
                        toastController.clear()
                        updateTitle()
                        // Refresh the slot menu: new ROM = new CRC =
                        // different slot files.
                        updateSlotMenu()
                        updateDebugMenu()
                        // Flash the Memory Editor grid (issue #169) so
                        // the user sees a full-tick highlight on every
                        // visible cell — confirms the new ROM is
                        // actually being observed.
                        flashMemoryEditorIfOpen()
                        // Refresh the achievements window view-model
                        // against the new ROM identity. The controller
                        // is the single funnel that maps (ROM, sign-in,
                        // service snapshot) to a view-model — the
                        // application drives it from this hook so the
                        // window always reflects the most recent state.
                        achievementsControllerLazy.refresh()
                        updateRaAchievementsMenuForViewModel()
                    }
                },
                onServiceCallStart = { /* emulation thread is managed
                    around the entire coordinator call, not per service
                    call — see stopEmulation() / startEmulation() at the
                    production call sites. */ },
                onServiceCallEnd = { /* see onServiceCallStart. */ },
            ),
            achievementsController = achievementsControllerLazy,
            // Issue #288: surface unlock / challenge / progress
            // events from the runtime to the achievements window's
            // refresh path. The listener hops to the JavaFX
            // Application Thread because [drainEvents] fires
            // synchronously on the emulation thread (the façade's
            // `evaluate_frame` path). The controller's generation
            // guard catches stale events — a late unlock for game A
            // cannot publish a view-model under game B's generation
            // because [RaAchievementsController.refresh] reads the
            // controller's currentGeneration at publish time.
            //
            // PR #290 review: short-circuit on
            // [achievementsWindowShowing] — otherwise progress events
            // (dozens per second in games with measured counters)
            // would post Platform.runLater tasks onto the FX thread
            // even when the user has never opened the window.
            onAchievementEvent = { _ ->
                if (achievementsWindowShowing) {
                    Platform.runLater {
                        achievementsControllerLazy.refresh()
                        updateRaAchievementsMenuForViewModel()
                    }
                }
            },
        )
        coord
    }
    // Hold-Tab fast-forward: disables throttling while held, restores it on release.
    private val fastForward = FastForwardController(nestlin.config)

    /**
     * Lazy achievements controller (issue #272). Constructed once the
     * service is ready; feeds the achievements window's view-model. The
     * lambdas capture live references so the controller always sees
     * the current sign-in / ROM state, not a snapshot from when it was
     * constructed.
     *
     * PR #290 review: the controller MUST share the coordinator's
     * service instance (`sessionCoordinator.service`) — not call
     * [RetroAchievementsServiceFactory.create] again. The factory
     * returns a fresh native handle on every call; the previous
     * implementation constructed a SECOND native service and fed it
     * to the controller, which then queried an idle handle for
     * every `achievementListSnapshot()` call (the coordinator's
     * service is the one that receives `prepareGame`). The result
     * was a perpetually-unrecognized window plus a leaked native C
     * handle on shutdown. The lazy accessor here runs AFTER the
     * `sessionCoordinator` field is initialized (every code path
     * reaches it through a `loadRom` hook first), so reading the
     * coordinator's service field is safe.
     */
    private val achievementsControllerLazy: com.github.alondero.nestlin.session.RaAchievementsController by lazy {
        com.github.alondero.nestlin.session.RaAchievementsController(
            service = sessionCoordinator.service,
            signInState = { raSignInManagerRef?.state ?: com.github.alondero.nestlin.session.RaSignInState.SignedOut },
            loadedRomInfo = {
                val rom = nestlin.loadedRom ?: return@RaAchievementsController null
                com.github.alondero.nestlin.session.RaAchievementsController.LoadedRomSnapshot(
                    displayName = rom.gamePak.name,
                    virtualFilename = rom.gamePak.name,
                )
            },
        )
    }
    // On-screen fast-forward indicator. A scene-graph node (not pixels drawn into frameImage)
    // so it stays a crisp 16px regardless of the canvas upscale factor. Gold glyph with a
    // black outline stays legible over both light and dark scenes. Toggled by the render loop.
    private val fastForwardIndicator = javafx.scene.text.Text(">>").apply {
        font = javafx.scene.text.Font.font("Monospaced", javafx.scene.text.FontWeight.BOLD, 16.0)
        fill = javafx.scene.paint.Color.web("#FFD700")
        stroke = javafx.scene.paint.Color.BLACK
        strokeWidth = 1.0
        isVisible = false
    }
    // Rewind scrub indicator (issue #52). Same crisp scene-node treatment as the fast-forward
    // glyph; cyan "<<" pinned top-centre (fast-forward sits top-right, REC/PLAY top-left, so the
    // three never collide). Toggled by the render loop while Backspace-scrubbing is active.
    private val rewindIndicator = javafx.scene.text.Text("<<").apply {
        font = javafx.scene.text.Font.font("Monospaced", javafx.scene.text.FontWeight.BOLD, 16.0)
        fill = javafx.scene.paint.Color.web("#40E0FF")
        stroke = javafx.scene.paint.Color.BLACK
        strokeWidth = 1.0
        isVisible = false
    }
    // Save-state feedback toast (issue #129). Same scene-graph approach as
    // the fast-forward indicator — overlaid on canvasHolder rather than
    // pixels poked into frameImage, so it stays a crisp 18px no matter the
    // canvas upscale or fullscreen state. Anchored bottom-centre per the
    // issue. The pill is semi-transparent so the game pixels it partially
    // covers stay visible during the brief display window.
    //
    // Why Label instead of Text: Label gets a CSS-backed pill background +
    // internal padding for free. Outlined Text was legible on dark scenes
    // but disappeared on bright NES backdrops (Kirby's pink title screen);
    // a 72%-opacity black pill is the standard "transient overlay" treatment.
    private val toastController = ToastController()
    private val toastIndicator = javafx.scene.control.Label("").apply {
        font = javafx.scene.text.Font.font("Monospaced", javafx.scene.text.FontWeight.BOLD, 18.0)
        // Pill: semi-transparent black background, rounded ends, generous
        // horizontal padding so short messages still look like a pill not
        // a square. Background-radius matches the height so the corners
        // form true semicircles, mobile-toast style.
        style = "-fx-background-color: rgba(0, 0, 0, 0.72);" +
                "-fx-background-radius: 14;" +
                "-fx-padding: 6 14 6 14;"
        // Cap the width and wrap long error messages (e.g. multi-line
        // IncompatibleSaveStateException diagnostics) so the pill never
        // extends past the canvas at small upscale factors.
        maxWidth = (RESOLUTION_WIDTH * 2).toDouble()
        isWrapText = true
        textAlignment = javafx.scene.text.TextAlignment.CENTER
        isVisible = false
    }

    // Issue #270: RA unlock overlay. Bigger pill than the save-state toast
    // (we need room for the title + points + description), pinned to the
    // upper-right corner where it doesn't collide with the fast-forward
    // indicator or the rewind indicator. The pill auto-hides when the
    // notification's display window expires (see refreshUnlockOverlay).
    private val unlockOverlay = javafx.scene.control.Label("").apply {
        font = javafx.scene.text.Font.font("Monospaced", javafx.scene.text.FontWeight.BOLD, 16.0)
        style = "-fx-background-color: rgba(0, 0, 0, 0.78);" +
                "-fx-background-radius: 14;" +
                "-fx-padding: 8 18 8 18;" +
                "-fx-text-fill: #FFD700;" +
                "-fx-border-color: #FFA500;" +
                "-fx-border-radius: 14;" +
                "-fx-border-width: 2;"
        maxWidth = (RESOLUTION_WIDTH * 2).toDouble()
        isWrapText = true
        textAlignment = javafx.scene.text.TextAlignment.CENTER
        isVisible = false
    }
    // Issue #270: RA offline / sync-pending banner. Smaller, neutral
    // gray pill pinned top-centre-above-rewind so it doesn't collide
    // with the unlock overlay (top-right) or the save-state toast
    // (bottom-centre).
    private val systemBanner = javafx.scene.control.Label("").apply {
        font = javafx.scene.text.Font.font("Monospaced", javafx.scene.text.FontWeight.NORMAL, 14.0)
        style = "-fx-background-color: rgba(0, 0, 0, 0.78);" +
                "-fx-background-radius: 12;" +
                "-fx-padding: 4 12 4 12;" +
                "-fx-text-fill: #E0E0E0;"
        isWrapText = true
        textAlignment = javafx.scene.text.TextAlignment.CENTER
        isVisible = false
    }

    private var running = false
    // Frame buffer synchronization for thread-safe screenshot capture
    private val frameBufferLock = Any()
    // Native-resolution RGB buffer. Upscaling is handled by ImageView.fitWidth/Height, not by replication.
    private var nextFrame = ByteArray(RESOLUTION_HEIGHT * RESOLUTION_WIDTH * 3)

    // --- Zapper light-gun input state ---
    // Written on the JavaFX thread (mouse handlers / focus listener), read on the
    // emulation thread when a plugged Zapper polls $4017 — hence @Volatile.
    @Volatile private var zapperTriggerDown = false
    @Volatile private var windowFocused = true
    // Aim, already mapped to a PPU pixel (mapping needs the live canvas size, only
    // valid to read on the FX thread). Packed as (x shl 8) or y into ONE volatile so
    // the light sampler never reads a torn half-updated coordinate; -1 = off-canvas.
    @Volatile private var zapperAim = -1
    // Last canvas-local cursor position (FX thread only), kept so the reticle can be
    // re-placed when the canvas moves under it (window resize fires no mouse event).
    private var lastPointerCanvasX = -1.0
    private var lastPointerCanvasY = -1.0

    // Whether a Zapper is plugged into either port. Drives the aim reticle and the
    // "hide the OS cursor over the game so the reticle is the only pointer" behaviour.
    // UI-thread only.
    private var zapperActive = false
    // Aim reticle overlay: a crisp scene-graph node — like the other indicators —
    // pinned to the canvas at the current aim pixel. Its local origin is the top-left
    // of a [ZAPPER_CROSSHAIR_SIZE]-box whose centre is the aim point.
    private val zapperCrosshair: javafx.scene.Group = buildZapperCrosshair()

    /**
     * True when the pixel the cursor is over is "bright" — a lit target sprite.
     * Threshold is R+G+B > 384 (of 765), which cleanly separates a game's white
     * detection boxes from the blanked/dark backdrop it flashes them against.
     *
     * Reads the PPU's LIVE frame (via [Ppu.aimBrightness]) rather than the published
     * [nextFrame]: the light gun must see the frame currently being drawn, or hit
     * detection lags a frame behind the aim during the fast blank-then-flash sequence.
     * This runs on the emulation thread (the $4017 read path), same as the PPU, so it
     * needs no frame-buffer lock.
     */
    private fun zapperLightSample(): Boolean {
        val aim = zapperAim
        if (aim < 0) return false
        val px = (aim shr 8) and 0xFF
        val py = aim and 0xFF
        return nestlin.ppu.aimBrightness(px, py) > ZAPPER_BRIGHTNESS_THRESHOLD
    }

    /**
     * Point the Zapper at a canvas-local mouse coordinate: map it to a PPU pixel for
     * the light sampler AND move the reticle. Both are driven from the same coordinate
     * so the visible aim and the sampled pixel can never disagree. Must run on the FX
     * thread ([canvas] size is read live). Off-canvas coordinates clear the aim (-1)
     * and hide the reticle.
     */
    private fun updateZapperPointer(canvasX: Double, canvasY: Double) {
        val w = canvas.width
        val h = canvas.height
        val onCanvas = w > 0.0 && h > 0.0 &&
            canvasX >= 0.0 && canvasY >= 0.0 && canvasX < w && canvasY < h
        if (!onCanvas) {
            zapperAim = -1
            lastPointerCanvasX = -1.0
            lastPointerCanvasY = -1.0
            zapperCrosshair.isVisible = false
            return
        }
        val px = ((canvasX / w) * RESOLUTION_WIDTH).toInt().coerceIn(0, RESOLUTION_WIDTH - 1)
        val py = ((canvasY / h) * RESOLUTION_HEIGHT).toInt().coerceIn(0, RESOLUTION_HEIGHT - 1)
        zapperAim = (px shl 8) or py
        lastPointerCanvasX = canvasX
        lastPointerCanvasY = canvasY
        positionZapperCrosshair(canvasX, canvasY)
    }

    /**
     * Build the aim reticle: a ring with four ticks forming a gapped `+`. Drawn in a
     * local coordinate box [0, [ZAPPER_CROSSHAIR_SIZE]] with the centre at
     * ([ZAPPER_CROSSHAIR_CENTER], [ZAPPER_CROSSHAIR_CENTER]) so positioning is just
     * "translate to (aim − centre)". Mouse-transparent so it never steals the canvas
     * mouse handlers; starts hidden.
     */
    private fun buildZapperCrosshair(): javafx.scene.Group {
        val red = javafx.scene.paint.Color.web("#FF3030")
        val c = ZAPPER_CROSSHAIR_CENTER
        fun tick(sx: Double, sy: Double, ex: Double, ey: Double) =
            javafx.scene.shape.Line(sx, sy, ex, ey).apply {
                stroke = red
                strokeWidth = 1.5
            }
        val ring = javafx.scene.shape.Circle(c, c, 7.0).apply {
            fill = javafx.scene.paint.Color.TRANSPARENT
            stroke = red
            strokeWidth = 1.5
        }
        return javafx.scene.Group(
            ring,
            tick(c, c - 11.0, c, c - 5.0),
            tick(c, c + 5.0, c, c + 11.0),
            tick(c - 11.0, c, c - 5.0, c),
            tick(c + 5.0, c, c + 11.0, c),
        ).apply {
            isMouseTransparent = true
            isVisible = false
        }
    }

    /**
     * Place the reticle over the canvas at a canvas-local coordinate. Maps canvas-local
     * → holder coordinates via the canvas group's bounds (which already account for
     * letterboxing/centring at any scale), then offsets by the reticle centre so the
     * ring lands on the aim point. Hidden when no Zapper is plugged.
     */
    private fun positionZapperCrosshair(canvasX: Double, canvasY: Double) {
        if (!zapperActive) {
            zapperCrosshair.isVisible = false
            return
        }
        val b = canvasGroup.boundsInParent
        zapperCrosshair.translateX = b.minX + canvasX - ZAPPER_CROSSHAIR_CENTER
        zapperCrosshair.translateY = b.minY + canvasY - ZAPPER_CROSSHAIR_CENTER
        zapperCrosshair.isVisible = true
    }

    /**
     * Re-place the reticle after the canvas has moved beneath a stationary cursor
     * (a window resize/scale change fires no mouse event). Uses the last canvas-local
     * cursor position; a no-op when the cursor is off-canvas or no Zapper is plugged.
     */
    private fun refreshZapperCrosshair() {
        if (!zapperActive || lastPointerCanvasX < 0.0) return
        positionZapperCrosshair(lastPointerCanvasX, lastPointerCanvasY)
    }

    /**
     * Recompute [zapperActive] from the current port selection and apply the
     * "hide the OS cursor over the game" behaviour. Called at startup and whenever
     * the controller configuration is applied.
     */
    private fun updateZapperActive() {
        zapperActive = inputConfig.ports.port1 == InputDevice.DeviceType.ZAPPER ||
            inputConfig.ports.port2 == InputDevice.DeviceType.ZAPPER
        canvas.cursor = if (zapperActive) javafx.scene.Cursor.NONE else javafx.scene.Cursor.DEFAULT
        if (!zapperActive) zapperCrosshair.isVisible = false
    }

    private var displayConfig = DisplayConfig.load()
    private val scaleMenuItems = mutableMapOf<ScaleMode, javafx.scene.control.RadioMenuItem>()
    // Slot menu items (File → Save State → Slot 1..9). Filled during start() and
    // refreshed on ROM change and after every save. The map is keyed by slot
    // number so handlers can find the item to update its label/disable state.
    private val slotMenuItems = mutableMapOf<Int, javafx.scene.control.MenuItem>()

    // Cached windowed dimensions so we can restore the stage explicitly on fullscreen exit
    // (JavaFX on Windows doesn't always restore prior size reliably, especially in Fit mode
    // where the canvas scale is bound reactively to the holder width).
    private var windowedWidth: Double = 0.0
    private var windowedHeight: Double = 0.0

    private val mouseNearTopProperty = javafx.beans.property.SimpleBooleanProperty(false)

    // Current ROM path: read from `nestlin.loadedRom?.sourcePath` (issue #189). The path
    // lives with the GamePak in Nestlin now, so we don't keep a separate cached field.
    // Emulation thread reference for stop/start control
    private var emulationThread: Thread? = null

    // Audio playback
    private var audioLine: SourceDataLine? = null
    private var audioEnabled = true
    private var audioThread: Thread? = null

    // Screenshot management
    private val screenshotManager = ScreenshotManager(Paths.get("screenshots"))

    // Slot-based save states (issue #45). Owns the savestates/<rom-crc>.slot-N
    // directory layout. Initialised once on first access (`by lazy`) so the
    // constructor's `Files.createDirectories` runs at most once per session,
    // not once per save / per menu refresh / per F-key.
    private val saveStateSlotManager by lazy {
        SaveStateSlotManager(nestlin, Paths.get("savestates"))
    }

    // Automated screenshot interval mode (for validation)
    private var screenshotIntervalSeconds: Int = 0
    private var screenshotDurationSeconds: Int = 0
    private var screenshotElapsedSeconds: Int = 0
    private var screenshotTimer: java.util.Timer? = null

    // Periodic battery-backed SRAM flush. Writes <rom>.sav every 10s if dirty,
    // so a crash or force-kill costs at most ~10s of in-game saves.
    private var batteryFlushTimer: java.util.Timer? = null

    // Input configuration and gamepad support. `var` because the Controller Config screen
    // can swap in a freshly-saved mapping at runtime; handleInput() reads it per key event.
    private var inputConfig = InputConfig.load()
    private lateinit var gamepadInput: GamepadInput

    // Settings → Configure Controls… window (lazily created, recreated after close).
    private var controllerConfigWindow: ControllerConfigWindow? = null

    // Held to keep the menu's check state in sync with keyboard shortcuts and
    // to clear pause when starting a fresh game via Load / Hard Reset.
    private var pauseMenuItem: javafx.scene.control.CheckMenuItem? = null

    // Load Recent submenu
    private val recentRomsMenu = Menu("Load Recent")

    // Debug → Memory Editor (issue #168). The menu item is greyed out until a ROM
    // is loaded; the window is created lazily on first open and reused thereafter.
    // The same MemoryEditorWindow keeps refreshing across ROM loads/resets because
    // it peeks through the long-lived Nestlin.memory instance.
    private var memoryEditorMenuItem: MenuItem? = null
    private var memoryEditorWindow: MemoryEditorWindow? = null

    // RetroAchievements status item (issue #267). The label is updated on
    // every refresh; the item is disabled because the menu's only job
    // right now is to surface whether the native façade library is
    // available. Login + token restoration actions land in issue #268.
    //
    // We hold a reference to the inner [Label] (not the CustomMenuItem)
    // because Label has a [Tooltip] property; MenuItem doesn't.
    private var raStatusLabelRef: javafx.scene.control.Label? = null

    // Sign-in actions (issue #268). The status label already exists above;
    // these are the actionable items: Sign In, View Profile, Sign Out.
    // Each is wired to a manager listener so the disable / text state
    // tracks the documented Unavailable / SignedOut / Authenticating /
    // SignedIn / Offline states.
    private var raSignInItem: javafx.scene.control.MenuItem? = null
    private var raProfileItem: javafx.scene.control.MenuItem? = null
    private var raSignOutItem: javafx.scene.control.MenuItem? = null
    private var raSignInManagerRef: com.github.alondero.nestlin.session.RaSignInManager? = null
    private var raSignInListenerToken: com.github.alondero.nestlin.session.RaSignInManager.ListenerToken? = null
    private var raProfileWindow: RaProfileWindow? = null

    // Achievements window (issue #272). Non-modal; lazily opened from the
    // RetroAchievements menu. The achievements controller is constructed
    // in [initializeRaAchievements] once the coordinator is wired, and
    // refreshed from the same hook surface that publishes boot-placard
    // events so the window's view-model stays in sync with the placard.
    private var raAchievementsController: com.github.alondero.nestlin.session.RaAchievementsController? = null
    private var raAchievementsItem: javafx.scene.control.MenuItem? = null
    private var raAchievementsWindow: RaAchievementsWindow? = null

    // Issue #288: window-visibility gate for the achievement event
    // listener. The native façade emits `ACHIEVEMENT_PROGRESS_UPDATE`
    // events potentially dozens of times per second in games with
    // measured counters; without this gate, every event would
    // hop to the JavaFX thread and call
    // [RaAchievementsController.refresh] which runs JNA calls +
    // allocates memory buffers + builds a fresh snapshot — even
    // when the user has never opened the achievements window.
    //
    // PR #290 review: set on `handleRaViewAchievements` (when the
    // window opens) and cleared on the window's `showingProperty`
    // listener (when it closes). Read on the emulation thread from
    // the achievement event listener body (see `sessionCoordinator`
    // below); @Volatile for the cross-thread visibility.
    //
    // The coordinator wires a single listener field on the native
    // service ([NativeRetroAchievementsService.achievementEventListener])
    // for the lifetime of the coordinator — there's no per-window
    // attach/detach needed because the listener body short-circuits
    // to no-op when the window isn't visible.
    @Volatile
    private var achievementsWindowShowing: Boolean = false

    // --- Movie record/playback state (issue #123) ---
    //
    // Exactly one of [liveRecorder] / [livePlayer] is non-null when a movie session is
    // active. The keyboard handler routes input differently based on [movieState]:
    //   - NONE:       keyboard writes directly to controller.buttons (normal play)
    //   - RECORDING:  keyboard writes to controller.pendingButtons; the frame-end latch
    //                 hook (MovieLiveRecorder) commits pending -> buttons once per frame
    //                 and captures the previous value as the recorded row.
    //   - PLAYING:    keyboard writes are dropped; the frame-end latch hook
    //                 (MovieLivePlayer) writes the next movie row to controller.buttons.
    //
    // @Volatile because the JavaFX thread writes and the emulation thread reads via the
    // keyboard handler. The latch hooks are installed/removed on the JavaFX thread.
    @Volatile
    private var movieState: MovieState = MovieState.NONE
    private var liveRecorder: MovieLiveRecorder? = null
    private var livePlayer: MovieLivePlayer? = null
    // Current FM2 file path (set when recording stops with "Save" or when playback starts).
    // Surfaced in the REC/PLAY indicator as the "what are we recording" hint.
    private var activeMoviePath: java.nio.file.Path? = null
    // The on-screen REC/PLAY indicator. Same scene-graph-Text pattern as the fast-forward
    // indicator: top-left corner (fast-forward is top-right so they don't collide), gold/red
    // glyph with a black stroke, hidden when no movie is active.
    private val movieIndicator = javafx.scene.text.Text("").apply {
        font = javafx.scene.text.Font.font("Monospaced", javafx.scene.text.FontWeight.BOLD, 16.0)
        fill = javafx.scene.paint.Color.web("#FF4040")
        stroke = javafx.scene.paint.Color.BLACK
        strokeWidth = 1.0
        isVisible = false
    }
    // Cached so the AnimationTimer's per-frame refresh can decide whether to repaint.
    private var movieIndicatorText: String = ""

    override fun start(stage: Stage) {
        // Assign lateinit *before* the apply block — applyScale (called inside) reads this.stage.
        this.stage = stage
        stage.apply {
            title = "Nestlin"

            // Set the application icon
            try {
                val iconStream = NestlinApplication::class.java.getResourceAsStream("/images/app-icon.png")
                if (iconStream != null) {
                    val iconImage = Image(iconStream)
                    icons.add(iconImage)
                    iconStream.close()
                }
            } catch (e: Exception) {
                println("[APP] Warning: Could not load application icon: ${e.message}")
            }

            // Create menu bar
            val menuBar = javafx.scene.control.MenuBar()

            // File menu
            val fileMenu = Menu("File")

            val loadGameItem = MenuItem("Load Game...")
            loadGameItem.setOnAction { handleLoadGame() }

            val hardResetItem = MenuItem("Hard Reset Game")
            hardResetItem.setOnAction { handleHardReset() }

            val saveStateItem = MenuItem("Save State...")
            saveStateItem.setOnAction { handleSaveState() }

            val loadStateItem = MenuItem("Load State...")
            loadStateItem.setOnAction { handleLoadState() }

            val exitItem = MenuItem("Exit")
            exitItem.setOnAction { handleExit() }

            // Save State submenu: nine numbered slots that share a CRC-keyed
            // directory with their PNG thumbnails. Each item is clickable to
            // load the slot (issue #45 acceptance criteria), and disabled when
            // the slot is empty. The slot submenu is refreshed on every ROM
            // change and after every save so the labels always reflect disk
            // state. F1..F9 is also set as the MenuItem accelerator so the
            // hotkey shows up in the open menu as a right-aligned hint; the
            // scene key filter is still the source of truth because
            // MenuItem accelerators don't fire while the menu is hidden.
            val slotMenu = Menu("Save State")
            slotMenuItems.clear()
            for (n in 1..9) {
                val item = MenuItem("Slot $n (empty)")
                item.accelerator = javafx.scene.input.KeyCombination.keyCombination("F$n")
                item.setOnAction { handleSlotLoad(n) }
                slotMenuItems[n] = item
                slotMenu.items.add(item)
            }
            // Separator + escape hatches for users who still want arbitrary
            // .nstl files outside the slot system (cross-emulator shares, etc.).
            slotMenu.items.addAll(
                javafx.scene.control.SeparatorMenuItem(),
                saveStateItem,
                loadStateItem
            )

            fileMenu.items.addAll(
                loadGameItem,
                recentRomsMenu,
                hardResetItem,
                slotMenu,
                exitItem
            )
            menuBar.menus.add(fileMenu)
            updateRecentMenu(EmulatorConfig.getRecentRoms())
            updateSlotMenu()

            // Settings menu
            val settingsMenu = javafx.scene.control.Menu("Settings")

            val throttleMenuItem = javafx.scene.control.CheckMenuItem("Speed Throttling (60 FPS)")
            throttleMenuItem.isSelected = nestlin.config.speedThrottlingEnabled
            throttleMenuItem.setOnAction {
                nestlin.config.speedThrottlingEnabled = throttleMenuItem.isSelected
                println("[APP] Speed throttling ${if (throttleMenuItem.isSelected) "enabled" else "disabled"}")
            }

            // Scale submenu (1x / 2x / 3x / 4x / Fit) as a mutually-exclusive radio group.
            val scaleMenu = javafx.scene.control.Menu("Scale")
            val scaleGroup = javafx.scene.control.ToggleGroup()
            for (mode in ScaleMode.entries) {
                val item = javafx.scene.control.RadioMenuItem(mode.label())
                item.toggleGroup = scaleGroup
                item.isSelected = displayConfig.scale == mode
                item.setOnAction {
                    setScaleMode(mode)
                }
                scaleMenu.items.add(item)
                scaleMenuItems[mode] = item
            }

            val fullscreenItem = javafx.scene.control.CheckMenuItem("Fullscreen")
            fullscreenItem.isSelected = displayConfig.fullscreen
            fullscreenItem.accelerator = javafx.scene.input.KeyCombination.keyCombination("F11")
            fullscreenItem.setOnAction { setFullscreen(fullscreenItem.isSelected) }

            val configureControlsItem = MenuItem("Configure Controls...")
            configureControlsItem.setOnAction { handleOpenControllerConfig() }

            settingsMenu.items.addAll(
                throttleMenuItem, scaleMenu, fullscreenItem,
                javafx.scene.control.SeparatorMenuItem(), configureControlsItem,
            )
            menuBar.menus.add(settingsMenu)

            // Emulation menu
            val emulationMenu = javafx.scene.control.Menu("Emulation")

            val pauseItem = javafx.scene.control.CheckMenuItem("Pause")
            pauseItem.isSelected = nestlin.config.paused
            pauseItem.accelerator = javafx.scene.input.KeyCombination.keyCombination("Ctrl+P")
            pauseItem.setOnAction {
                nestlin.config.paused = pauseItem.isSelected
                updateTitle()
                println("[APP] Emulation ${if (nestlin.config.paused) "paused" else "resumed"}")
            }
            pauseMenuItem = pauseItem

            emulationMenu.items.add(pauseItem)
            menuBar.menus.add(emulationMenu)

            // Movie menu (issue #123). Three actions: toggle recording, load + play a movie,
            // stop whatever session is active. Hotkeys: Ctrl+Shift+R (record), Ctrl+Shift+P
            // (play), Esc (stop). The "Stop" item is always enabled — when no session is
            // active it's a no-op, which is harmless and lets the Esc hotkey work uniformly.
            val movieMenu = javafx.scene.control.Menu("Movie")
            val startRecordItem = MenuItem("Start Recording")
            startRecordItem.accelerator = javafx.scene.input.KeyCombination.keyCombination("Ctrl+Shift+R")
            startRecordItem.setOnAction { handleStartRecording() }
            val playMovieItem = MenuItem("Play Movie...")
            playMovieItem.accelerator = javafx.scene.input.KeyCombination.keyCombination("Ctrl+Shift+P")
            playMovieItem.setOnAction { handlePlayMovie() }
            val stopMovieItem = MenuItem("Stop Movie")
            stopMovieItem.accelerator = javafx.scene.input.KeyCombination.keyCombination("Esc")
            stopMovieItem.setOnAction { handleStopMovie() }
            movieMenu.items.addAll(startRecordItem, playMovieItem, stopMovieItem)
            menuBar.menus.add(movieMenu)

            // Debug menu (issue #168). A single "Memory Editor" item (Ctrl+M) that
            // opens the live hex viewer. Disabled until a ROM is loaded — peeking an
            // empty bus is useless. updateDebugMenu() keeps the disable state in sync
            // on every ROM change (alongside updateSlotMenu / updateTitle).
            val debugMenu = javafx.scene.control.Menu("Debug")
            val memoryEditorItem = MenuItem("Memory Editor")
            memoryEditorItem.accelerator = javafx.scene.input.KeyCombination.keyCombination("Ctrl+M")
            memoryEditorItem.setOnAction { handleOpenMemoryEditor() }
            memoryEditorItem.isDisable = nestlin.loadedRom == null
            memoryEditorMenuItem = memoryEditorItem
            debugMenu.items.add(memoryEditorItem)
            menuBar.menus.add(debugMenu)

            // RetroAchievements menu (issue #267 + #268). Status item reflects the
            // availability of the native façade library; the Sign In /
            // Profile / Sign Out actions land in issue #268 and operate
            // against the [RaSignInManager] tied to the same
            // [GameSessionCoordinator].
            //
            // The label is updated on every refresh to reflect whether the
            // native library was successfully loaded. When it wasn't, the
            // item is disabled and the tooltip explains why, so a developer
            // running the UI can immediately see whether their build of
            // rcheevos_facade.dll/so/dylib made it onto the classpath.
            //
            // JavaFX's [MenuItem] doesn't expose a `tooltip` property, so
            // we use a [CustomMenuItem] wrapping a [Label] — the Label
            // supports tooltips. The item is hideOnClick = false because
            // the menu is informational only.
            val retroAchievementsMenu = javafx.scene.control.Menu("RetroAchievements")
            val raStatusLabel = javafx.scene.control.Label("Status: checking…")
            raStatusLabel.tooltip = javafx.scene.control.Tooltip("Native library availability unknown.")
            val raStatusItem = javafx.scene.control.CustomMenuItem(raStatusLabel)
            raStatusItem.isDisable = true
            // `hideOnClick` is a BooleanProperty in JavaFX; setting via
            // the property setter avoids the boolean literal mismatch.
            raStatusItem.hideOnClickProperty().set(false)
            raStatusLabelRef = raStatusLabel

            // Sign In: opens the modal username/password dialog (issue #268).
            val raSignInMenuItem = javafx.scene.control.MenuItem("Sign In...")
            raSignInMenuItem.setOnAction { handleRaSignIn() }
            raSignInItem = raSignInMenuItem

            // View Profile: opens the non-modal profile window. Disabled
            // until we're actually signed in (the window would just show a
            // placeholder otherwise, which is a poor first impression).
            val raProfileMenuItem = javafx.scene.control.MenuItem("View Profile...")
            raProfileMenuItem.setOnAction { handleRaViewProfile() }
            raProfileMenuItem.isDisable = true
            raProfileItem = raProfileMenuItem

            // Sign Out: tears down the session without stopping gameplay.
            // Disabled unless we're signed in.
            val raSignOutMenuItem = javafx.scene.control.MenuItem("Sign Out")
            raSignOutMenuItem.setOnAction { handleRaSignOut() }
            raSignOutMenuItem.isDisable = true
            raSignOutItem = raSignOutMenuItem

            // Current Game Achievements... (issue #272). Opens the non-modal
            // achievements window for the currently-loaded game. The item is
            // disabled by default; the achievements controller's listener
            // enables / disables it based on whether a recognized core
            // achievement set is available.
            val raAchievementsMenuItem = javafx.scene.control.MenuItem("Current Game Achievements...")
            raAchievementsMenuItem.setOnAction { handleRaViewAchievements() }
            raAchievementsMenuItem.isDisable = true
            raAchievementsItem = raAchievementsMenuItem

            retroAchievementsMenu.items.addAll(
                raStatusItem,
                javafx.scene.control.SeparatorMenuItem(),
                raSignInMenuItem,
                raProfileMenuItem,
                raSignOutMenuItem,
                javafx.scene.control.SeparatorMenuItem(),
                raAchievementsMenuItem,
            )
            menuBar.menus.add(retroAchievementsMenu)
            // Populate the status label now that the label has been attached.
            updateRetroAchievementsStatus()
            // Wire the manager (lazy: the factory call must NOT throw — see
            // the try/catch above on the coordinator's service).
            initializeRaSignInManager()

            // Create layout with menu bar and the canvas holder. VBox.setVgrow lets the
            // holder expand to fill remaining vertical space in fullscreen / Fit mode.
            val root = VBox()
            root.children.addAll(menuBar, canvasHolder)
            VBox.setVgrow(canvasHolder, javafx.scene.layout.Priority.ALWAYS)

            // Overlay the fast-forward indicator on top of the game image, pinned top-right.
            canvasHolder.children.add(fastForwardIndicator)
            StackPane.setAlignment(fastForwardIndicator, javafx.geometry.Pos.TOP_RIGHT)
            StackPane.setMargin(fastForwardIndicator, javafx.geometry.Insets(4.0, 6.0, 0.0, 0.0))

            // Rewind indicator pinned top-centre (issue #52) — clear of the other two corners.
            canvasHolder.children.add(rewindIndicator)
            StackPane.setAlignment(rewindIndicator, javafx.geometry.Pos.TOP_CENTER)
            StackPane.setMargin(rewindIndicator, javafx.geometry.Insets(4.0, 0.0, 0.0, 0.0))

            // Overlay the REC/PLAY indicator on top-LEFT so it doesn't collide with the
            // fast-forward indicator. Same Text-node pattern — crisp at any scale, hidden
            // when no movie session is active.
            canvasHolder.children.add(movieIndicator)
            StackPane.setAlignment(movieIndicator, javafx.geometry.Pos.TOP_LEFT)
            StackPane.setMargin(movieIndicator, javafx.geometry.Insets(4.0, 6.0, 0.0, 0.0))

            // Save-state toast: pinned bottom-centre with a 28px inset. The
            // pill is semi-transparent (0.72 alpha) so the game pixels behind
            // it remain visible — important because at integer scales the
            // canvas fills the holder bottom-up and there is no letterbox
            // gap to sit in. Toast duration is brief (~1s success / ~2.5s
            // error) so the partial occlusion is tolerable.
            canvasHolder.children.add(toastIndicator)
            StackPane.setAlignment(toastIndicator, javafx.geometry.Pos.BOTTOM_CENTER)
            StackPane.setMargin(toastIndicator, javafx.geometry.Insets(0.0, 0.0, 28.0, 0.0))

            // Issue #270: unlock overlay (top-right) and offline/system
            // banner (top-centre, above the rewind indicator). Both
            // children are added unconditionally; their visibility is
            // toggled per-frame in refreshUnlockOverlay.
            canvasHolder.children.add(unlockOverlay)
            StackPane.setAlignment(unlockOverlay, javafx.geometry.Pos.TOP_RIGHT)
            StackPane.setMargin(unlockOverlay, javafx.geometry.Insets(28.0, 28.0, 0.0, 0.0))
            canvasHolder.children.add(systemBanner)
            StackPane.setAlignment(systemBanner, javafx.geometry.Pos.TOP_CENTER)
            StackPane.setMargin(systemBanner, javafx.geometry.Insets(28.0, 0.0, 0.0, 0.0))

            // Letterbox area outside the scaled canvas paints black.
            canvasHolder.style = "-fx-background-color: black;"
            // Decouple holder's min size from the Group's bounds. Without this, the scaled
            // canvas's visual extent propagates upward through the StackPane as a min-size
            // constraint, preventing the stage from shrinking back after fullscreen exit
            // and trapping Fit mode at the fullscreen scale value.
            canvasHolder.minWidth = 0.0
            canvasHolder.minHeight = 0.0

            scene = Scene(root)
            scene.fill = javafx.scene.paint.Color.BLACK

            // Apply persisted scale + fullscreen now that the scene exists.
            applyScale(displayConfig.scale)
            isFullScreen = displayConfig.fullscreen
            fullScreenExitHint = ""
            fullScreenExitKeyCombination = javafx.scene.input.KeyCombination.NO_MATCH

            // Menu reveals when (a) not in fullscreen, (b) mouse is near the top edge, or
            // (c) any submenu is already open (so dropdowns don't vanish mid-click).
            // Accelerator F11 lives on the Scene and still fires while menu is hidden.
            val anyMenuShowing = javafx.beans.binding.Bindings.createBooleanBinding(
                { menuBar.menus.any { it.isShowing } },
                *menuBar.menus.map { it.showingProperty() }.toTypedArray()
            )
            val menuRevealed = fullScreenProperty().not()
                .or(mouseNearTopProperty)
                .or(anyMenuShowing)
            menuBar.visibleProperty().bind(menuRevealed)
            menuBar.managedProperty().bind(menuRevealed)

            // Reveal threshold: top 4 logical pixels. Tight enough not to interfere with
            // gameplay; generous enough that a fast mouse-to-top motion still triggers.
            scene.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_MOVED) { e ->
                mouseNearTopProperty.set(stage.isFullScreen && e.sceneY < 4.0)
            }

            fullScreenProperty().addListener { _, wasFs, nowFs ->
                if (nowFs && !wasFs) {
                    // Capture pre-fullscreen size for explicit restore on exit.
                    if (width > 0) windowedWidth = width
                    if (height > 0) windowedHeight = height
                } else if (wasFs && !nowFs && windowedWidth > 0) {
                    // Explicitly restore — JavaFX's auto-restore isn't reliable on Windows,
                    // and in Fit mode the canvas scale is bound to the holder so a stuck-large
                    // stage means a stuck-large canvas that overflows the window frame.
                    width = windowedWidth
                    height = windowedHeight
                }
                if (displayConfig.fullscreen != nowFs) {
                    displayConfig = displayConfig.copy(fullscreen = nowFs)
                    DisplayConfig.save(displayConfig)
                }
                fullscreenItem.isSelected = nowFs
                // Reset hover state on transition so the menu isn't stuck visible.
                if (!nowFs) mouseNearTopProperty.set(false)
            }

            // --- Zapper light-gun wiring ---
            // The trigger is mouse-left-held while the game window is focused; the light
            // sense reads the frame buffer at the cursor. Mouse handlers live on the canvas
            // so event.x/event.y are already canvas-local (pre-scale), which is what
            // updateZapperPointer maps to a PPU pixel. These run whether or not a Zapper is
            // actually plugged — the providers are only consulted when Memory holds one.
            canvas.setOnMousePressed { e ->
                // Update the aim before raising the trigger so a reader on the emulation
                // thread never sees "trigger high" paired with the previous aim pixel.
                updateZapperPointer(e.x, e.y)
                if (e.button == javafx.scene.input.MouseButton.PRIMARY) zapperTriggerDown = true
            }
            canvas.setOnMouseReleased { e ->
                if (e.button == javafx.scene.input.MouseButton.PRIMARY) zapperTriggerDown = false
            }
            canvas.setOnMouseMoved { e -> updateZapperPointer(e.x, e.y) }
            canvas.setOnMouseDragged { e -> updateZapperPointer(e.x, e.y) }
            canvas.setOnMouseExited {
                zapperAim = -1
                lastPointerCanvasX = -1.0
                lastPointerCanvasY = -1.0
                zapperCrosshair.isVisible = false
            }
            // Reticle overlay, pinned top-left so its translateX/Y are plain holder
            // coordinates (positionZapperCrosshair does the canvas→holder mapping).
            canvasHolder.children.add(zapperCrosshair)
            StackPane.setAlignment(zapperCrosshair, javafx.geometry.Pos.TOP_LEFT)
            // Re-place the reticle when the canvas moves under a stationary cursor
            // (window resize / scale change fires no mouse event).
            canvasGroup.boundsInParentProperty().addListener { _, _, _ -> refreshZapperCrosshair() }
            stage.focusedProperty().addListener { _, _, focused ->
                windowFocused = focused
                // Dropping focus releases the trigger so a click that moved focus away
                // (e.g. to the config window) doesn't leave the trigger stuck high.
                if (!focused) zapperTriggerDown = false
            }
            // Install the providers once; the plugged Zapper (if any) reads them live.
            nestlin.memory.setZapperProviders(
                trigger = { zapperTriggerDown && windowFocused },
                light = { zapperLightSample() },
            )
            // Apply the saved per-port device selection at boot. The config-window save
            // is the only other path that calls setPortType, so without this a saved
            // Zapper selection wouldn't take effect until the user re-opened the config.
            nestlin.memory.setPortType(0, inputConfig.ports.port1)
            nestlin.memory.setPortType(1, inputConfig.ports.port2)
            updateZapperActive()

            scene.setOnKeyPressed { event ->
                when {
                    // Ctrl+T: toggle throttling
                    event.code == javafx.scene.input.KeyCode.T && event.isControlDown -> {
                        nestlin.config.speedThrottlingEnabled = !nestlin.config.speedThrottlingEnabled
                        throttleMenuItem.isSelected = nestlin.config.speedThrottlingEnabled
                        println("[APP] Speed throttling ${if (nestlin.config.speedThrottlingEnabled) "enabled" else "disabled"}")
                        event.consume()
                    }
                    // Ctrl+P: toggle pause. The `!isShiftDown` guard is load-bearing — the
                    // Ctrl+Shift+P "Play Movie" hotkey must NOT be swallowed by this branch
                    // (per [[function-key-modifier-ordering]], modifier-specific branches
                    // come BEFORE the bare-modifier branch, or the bare branch wins).
                    event.code == javafx.scene.input.KeyCode.P
                        && event.isControlDown && !event.isShiftDown -> {
                        nestlin.config.paused = !nestlin.config.paused
                        pauseMenuItem?.isSelected = nestlin.config.paused
                        updateTitle()
                        println("[APP] Emulation ${if (nestlin.config.paused) "paused" else "resumed"}")
                        event.consume()
                    }
                    // Ctrl+Shift+R: toggle recording (issue #123). If a session is already
                    // active, the toggle becomes a stop. We check the modifier EXPLICITLY
                    // (per [[function-key-modifier-ordering]]) so a bare R keypress still
                    // routes to handleInput for the game to see.
                    event.code == javafx.scene.input.KeyCode.R
                        && event.isControlDown && event.isShiftDown -> {
                        if (movieState == MovieState.RECORDING) {
                            handleStopMovie()
                        } else if (movieState == MovieState.NONE) {
                            handleStartRecording()
                        }
                        event.consume()
                    }
                    // Ctrl+Shift+P: load + play a movie. No-op if a session is already active.
                    event.code == javafx.scene.input.KeyCode.P
                        && event.isControlDown && event.isShiftDown -> {
                        if (movieState == MovieState.NONE) {
                            handlePlayMovie()
                        }
                        event.consume()
                    }
                    // Esc: stop any active movie session (record or playback). Always
                    // captured — Esc is a natural "cancel" gesture and we want it to win
                    // over game input regardless of focus.
                    event.code == javafx.scene.input.KeyCode.ESCAPE -> {
                        if (movieState != MovieState.NONE) {
                            handleStopMovie()
                            event.consume()
                        }
                    }
                    // F1..F9: load slot N (F1=slot 1, F2=slot 2, etc.)
                    // Shift+F1..F9: save into slot N. Shift+save mirrors FCEUX's
                    // "shift = write, no-shift = read" muscle memory for the
                    // existing quick-save convention.
                    isSlotKey(event.code) && !event.isControlDown && !event.isAltDown -> {
                        val slot = slotNumberFromKey(event.code)
                        if (event.isShiftDown) handleSlotSave(slot) else handleSlotLoad(slot)
                        event.consume()
                    }
                    // Backspace: hold to scrub backward through the rewind buffer (issue #52).
                    // KEY_PRESSED auto-repeats while held; setRewindActive is idempotent so the
                    // repeats are harmless. Released below in setOnKeyReleased.
                    event.code == javafx.scene.input.KeyCode.BACK_SPACE -> {
                        nestlin.setRewindActive(true)
                        event.consume()
                    }
                    // F11 is handled by the Fullscreen menu accelerator (see Settings menu).
                    else -> handleInput(event.code, true)
                }
            }
            scene.setOnKeyReleased { event ->
                // Release Backspace: stop scrubbing and resume play from the rewound point.
                if (event.code == javafx.scene.input.KeyCode.BACK_SPACE) {
                    nestlin.setRewindActive(false)
                    event.consume()
                } else {
                    handleInput(event.code, false)
                }
            }

            // Tab is a focus-traversal key in JavaFX, so it must be intercepted in the
            // capturing phase (event filter) and consumed before the traversal engine
            // sees it — otherwise holding Tab would walk focus into the menu bar instead
            // of fast-forwarding. KEY_PRESSED auto-repeats while held; engage() is idempotent.
            scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED) { event ->
                if (event.code == javafx.scene.input.KeyCode.TAB) {
                    fastForward.engage()
                    event.consume()
                }
            }
            scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_RELEASED) { event ->
                if (event.code == javafx.scene.input.KeyCode.TAB) {
                    fastForward.release()
                    event.consume()
                }
            }

            // Safety net for the "stuck turbo" problem: if the window loses focus while
            // Tab is held, the OS stops delivering key events so KEY_RELEASED never fires.
            // Force-release on focus loss so fast-forward can't latch on indefinitely.
            focusedProperty().addListener { _, _, focused ->
                if (!focused) {
                    fastForward.release()
                    // Same "stuck key" guard for rewind: if focus is lost while Backspace is
                    // held, KEY_RELEASED never fires, so force the scrub off (issue #52).
                    nestlin.setRewindActive(false)
                }
            }

            // Window close (X button) goes through handleExit so battery RAM gets flushed.
            // Without this the JavaFX runtime would jump straight to stop() and bypass the flush.
            setOnCloseRequest { handleExit() }

            show()
        }

        startBatteryFlushTimer()

        // Initialize gamepad input — both controllers so two connected gamepads
        // auto-route to P1 and P2 respectively (issue: 2-player support).
        gamepadInput = GamepadInput(
            listOf(nestlin.getController1(), nestlin.getController2()),
            inputConfig.gamepad,
        )
        gamepadInput.initialize()

        // Create default config file for user reference
        InputConfig.createDefaultIfMissing()

        object: AnimationTimer() {
            override fun handle(now: Long) {
                // Poll gamepad input
                gamepadInput.poll()

                val pixelWriter = frameImage.pixelWriter
                val pixelFormat = PixelFormat.getByteRgbInstance()

                // Thread-safe read of frame buffer (native NES resolution)
                synchronized(frameBufferLock) {
                    pixelWriter.setPixels(0, 0, RESOLUTION_WIDTH, RESOLUTION_HEIGHT, pixelFormat, nextFrame, 0, RESOLUTION_WIDTH * 3)
                }

                // Draw the native-resolution image onto the canvas at scaled size.
                // gc.isImageSmoothing = false guarantees nearest-neighbor interpolation.
                gc.drawImage(frameImage, 0.0, 0.0, canvas.width, canvas.height)

                // Show/hide the fast-forward indicator. It's a StackPane-overlaid scene node,
                // so toggling visibility is all that's needed — no per-frame drawing.
                if (fastForwardIndicator.isVisible != fastForward.active) {
                    fastForwardIndicator.isVisible = fastForward.active
                }
                // Cheap-toggle the rewind indicator from the engine's live scrub state (issue #52).
                val rewinding = nestlin.isRewinding()
                if (rewindIndicator.isVisible != rewinding) {
                    rewindIndicator.isVisible = rewinding
                }
                // Same cheap-toggle pattern for the REC/PLAY movie indicator.
                refreshMovieIndicator()

                // Save-state toast: pull from the controller each frame. We're
                // already on the JavaFX Application Thread (AnimationTimer.handle
                // runs here), so direct scene-node mutation is safe.
                refreshToastIndicator(System.currentTimeMillis())

                // Issue #270: RA unlock overlay + system banner. Same per-frame
                // pull-from-controller pattern as the save-state toast.
                refreshUnlockOverlay(System.currentTimeMillis())
            }

        }.start()

        running = true

        if (!parameters.named["no-audio"].isNullOrEmpty()) {
            audioEnabled = false
            println("[APP] Audio disabled")
        }

        // Initialize audio playback
        initAudio()

        // Parse screenshot interval parameters for automated validation
        // Note: JavaFX doesn't support --name value syntax, only --name=value
        // So we parse from unnamed parameters
        fun getNamedParam(name: String): String? {
            val index = parameters.unnamed.indexOf(name)
            return if (index >= 0 && index + 1 < parameters.unnamed.size) {
                parameters.unnamed[index + 1]
            } else null
        }
        val intervalStr = getNamedParam("--screenshot-interval") ?: parameters.named["screenshot-interval"]
        val durationStr = getNamedParam("--screenshot-duration") ?: parameters.named["screenshot-duration"]
        if (intervalStr != null && durationStr != null) {
            screenshotIntervalSeconds = intervalStr.toIntOrNull() ?: 0
            screenshotDurationSeconds = durationStr.toIntOrNull() ?: 15
            if (screenshotIntervalSeconds > 0 && screenshotDurationSeconds > 0) {
                println("[APP] Automated screenshot mode: every ${screenshotIntervalSeconds}s for ${screenshotDurationSeconds}s")
                // Wait before starting screenshots to let emulation stabilize
                screenshotTimer = java.util.Timer(true)
                screenshotTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
                    private var nextCapture = 5  // First capture at 5 seconds
                    override fun run() {
                        if (nextCapture >= screenshotDurationSeconds) {
                            println("[APP] Screenshot duration reached (${nextCapture}s >= ${screenshotDurationSeconds}s), shutting down...")
                            Platform.runLater {
                                handleExit()
                            }
                            cancel()
                        } else {
                            println("[APP] Capturing screenshot at elapsed=${nextCapture}s...")
                            captureScreenshot()
                            nextCapture += screenshotIntervalSeconds
                        }
                    }
                }, screenshotIntervalSeconds * 1000L, screenshotIntervalSeconds * 1000L)
            }
        }

        // Load and start emulation from command line argument
        thread {
            with(nestlin) {
                println("[APP] Parameters: named=${parameters.named}, unnamed=${parameters.unnamed}")
                // Reconstruct the ROM path from parameters. The ROM path is the first non-flag parameter.
                // Filter out any parameter that looks like a named flag (starts with --).
                val nonFlagParams = parameters.unnamed.filter { !it.startsWith("--") }
                val debugEnabled = !parameters.named["debug"].isNullOrEmpty() ||
                        parameters.unnamed.any { it.startsWith("--debug") }
                if (debugEnabled) enableLogging()
                // Optional region override: --region=pal | --region=ntsc (default: auto-detect).
                parameters.named["region"]?.lowercase()?.let {
                    nestlin.config.regionOverride = when (it) {
                        "pal" -> Region.PAL
                        "ntsc" -> Region.NTSC
                        else -> null
                    }
                }
                // Take only the first non-flag parameter as the ROM path (rest are other arguments).
                // A ROM is optional at launch: the user can now start the emulator with no game
                // and use File → Load Game... (or Load Recent) once the UI is up. We still spin
                // up the emulation thread so the canvas/UI stay responsive while idle, and the
                // pre-existing null-safety in cpu.reset() / loadBatteryRam() / nestlin.start()
                // means no special-casing is needed in the engine.
                val romPathArg = nonFlagParams.firstOrNull()
                if (romPathArg != null) {
                    val romPath = Paths.get(romPathArg)
                    // Issue #266: route through the coordinator so the
                    // battery-flush / service-unload / install-and-reset
                    // / battery-restore / service-prepare sequence is
                    // canonical. No "previous ROM" exists at boot, so the
                    // outgoing-flush step inside the coordinator is a
                    // no-op for this call.
                    sessionCoordinator.loadRom(romPath)
                }
                // Always hop back to JavaFX for the UI refresh — even
                // when no ROM was loaded, updateTitle() shows
                // "Nestlin - No Game Loaded" and updateSlotMenu()
                // disables every slot with a "(no ROM loaded)" label.
                // When the coordinator's onAfterRomChange fires for a
                // successful load, it also schedules a Platform.runLater
                // UI refresh — running it again here is idempotent.
                Platform.runLater {
                    updateTitle()
                    updateSlotMenu()
                    updateDebugMenu()
                }
                startEmulation()
            }
        }.also { emulationThread = it }
    }

    private fun stopEmulation() {
        nestlin.stop()
        // Unbounded join: save state correctness requires the emulation thread to be fully stopped
        // before mutable CPU/PPU/APU state is serialised. The loop has no blocking IO; only brief
        // throttling sleeps (<=16ms), so this should return promptly.
        emulationThread?.join()
        emulationThread = null
    }

    private fun startEmulation() {
        emulationThread = thread {
            nestlin.start()
        }
    }

    private fun startBatteryFlushTimer() {
        batteryFlushTimer = java.util.Timer("nestlin-sram-flush", true)
        batteryFlushTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                val rom = nestlin.loadedRom?.sourcePath ?: return
                try {
                    nestlin.flushBatteryRamIfDirty(rom)
                } catch (e: Exception) {
                    System.err.println("[SRAM] Periodic flush failed: ${e.message}")
                }
            }
        }, BATTERY_FLUSH_INTERVAL_MS, BATTERY_FLUSH_INTERVAL_MS)
    }

    private fun startScreenshotTimer() {
        screenshotTimer = java.util.Timer(true)
        // First screenshot after interval, then every intervalSeconds
        screenshotTimer?.scheduleAtFixedRate(object : java.util.TimerTask() {
            override fun run() {
                if (screenshotElapsedSeconds >= screenshotDurationSeconds) {
                    // Duration reached - stop emulation and exit
                    println("[APP] Screenshot duration reached (${screenshotElapsedSeconds}s >= ${screenshotDurationSeconds}s), shutting down...")
                    Platform.runLater {
                        handleExit()
                    }
                    cancel()
                } else {
                    // Capture screenshot at interval
                    println("[APP] Capturing screenshot at elapsed=${screenshotElapsedSeconds}s...")
                    captureScreenshot()
                    screenshotElapsedSeconds += screenshotIntervalSeconds
                }
            }
        }, screenshotIntervalSeconds * 1000L, screenshotIntervalSeconds * 1000L)
    }

    private fun handleLoadGame() {
        val chooser = FileChooser()
        chooser.title = "Load NES ROM"
        chooser.extensionFilters.addAll(
            FileChooser.ExtensionFilter("NES ROMs (*.nes)", "*.nes"),
            FileChooser.ExtensionFilter("7z Archives (*.7z)", "*.7z"),
            FileChooser.ExtensionFilter("All Files", "*.*")
        )
        val file = chooser.showOpenDialog(stage)
        if (file != null) {
            val romPath = file.toPath()
            // Issue #266: route through the coordinator so battery-flush /
            // service-unload / install-and-reset / battery-restore /
            // service-prepare runs in the canonical order. The coordinator's
            // onBeforeRomChange hook cancels the active movie session; its
            // onAfterRomChange hook hops to the JavaFX thread to refresh
            // the title/slot/debug UI and flash the Memory Editor. We
            // bracket the call with stopEmulation / startEmulation because
            // the coordinator does not manage the emulation thread — the
            // application owns it (per the GameSessionCoordinator contract).
            stopEmulation()
            sessionCoordinator.loadRom(romPath)
            // Pause-clear must run SYNCHRONOUSLY before startEmulation;
            // see the comment on sessionCoordinator above.
            clearPauseState()
            startEmulation()
            EmulatorConfig.addRecentRom(romPath)
            updateRecentMenu(EmulatorConfig.getRecentRoms())
        }
    }

    // Reset pause so a new game session always begins running.
    private fun clearPauseState() {
        nestlin.config.paused = false
        pauseMenuItem?.isSelected = false
    }

    /**
     * Open the Memory Editor (issue #168), or focus it if already open. Lazily
     * creates one [MemoryEditorWindow] and reuses it: the window peeks through the
     * long-lived [Nestlin.memory], so it keeps refreshing across ROM loads and
     * resets without needing to be recreated. The showing-property listener nulls
     * our reference when the user closes the window so the next open builds fresh.
     */
    /**
     * Open (or focus) the Controller Configuration screen. Mirrors [handleOpenMemoryEditor]'s
     * show/recreate pattern. Saving from the window calls back into [applyInputConfig], which
     * persists the file and applies the new mapping live this session.
     */
    private fun handleOpenControllerConfig() {
        val existing = controllerConfigWindow
        if (existing != null) {
            existing.show()
            return
        }
        val window = ControllerConfigWindow(
            loadConfig = { inputConfig },
            applyAndSave = { cfg -> applyInputConfig(cfg) },
            gamepad = if (::gamepadInput.isInitialized) gamepadInput else null,
        )
        controllerConfigWindow = window
        window.stage.showingProperty().addListener { _, _, showing ->
            if (!showing) controllerConfigWindow = null
        }
        window.show()
    }

    /**
     * Persist a new input configuration and apply it live. Keyboard remaps take effect
     * immediately because [handleInput] reads [inputConfig] per key event; gamepad remaps are
     * pushed into the running [GamepadInput] via [GamepadInput.updateConfig] (no re-init).
     */
    private fun applyInputConfig(cfg: InputConfig) {
        InputConfig.save(cfg)
        inputConfig = cfg
        if (::gamepadInput.isInitialized) {
            gamepadInput.updateConfig(cfg.gamepad)
        }
        // Apply the per-port device type — a Zapper plug-in changes which $4016/$4017
        // reads return open-bus vs standard pad bytes (issue: 2-player support).
        nestlin.memory.setPortType(0, cfg.ports.port1)
        nestlin.memory.setPortType(1, cfg.ports.port2)
        // Refresh the aim reticle / cursor-hide state for the new port selection.
        updateZapperActive()
        println("[APP] Applied new controller configuration")
    }

    private fun handleOpenMemoryEditor() {
        if (nestlin.loadedRom == null) return
        val existing = memoryEditorWindow
        if (existing != null) {
            existing.show()
            return
        }
        val window = MemoryEditorWindow(
            peek = { addr -> nestlin.peekMemory(addr) },
            poke = { addr, value -> nestlin.pokeMemory(addr, value) },
        )
        memoryEditorWindow = window
        window.stage.showingProperty().addListener { _, _, showing ->
            if (!showing) memoryEditorWindow = null
        }
        window.show()
    }

    /**
     * Trigger a full-grid flash in the open Memory Editor (issue #169). Called
     * whenever the underlying bus state changes wholesale — ROM load, hard reset,
     * movie session reset — so the user gets a single-tick visual confirmation
     * that the data they're now looking at is genuinely the new state. No-op
     * if the editor is not open (the user can't see it flash anyway).
     *
     * **JavaFX thread only.** This writes `forceFullFlash` and the
     * `previousVisibleBytes` baseline on the editor, which are also touched by
     * the editor's 10 Hz [Timeline] on the JavaFX Application Thread. Calling
     * from any other thread races the Timeline and can produce a missed flash
     * or a [ConcurrentModificationException] on the editor's highlight maps.
     * All current call sites are JavaFX-thread handlers (MenuItem.setOnAction,
     * performWithEmulationPaused which runs on the JavaFX thread).
     */
    private fun flashMemoryEditorIfOpen() {
        memoryEditorWindow?.markAllChanged()
    }

    /** Grey out the Debug → Memory Editor item when no ROM is loaded. */
    private fun updateDebugMenu() {
        memoryEditorMenuItem?.isDisable = nestlin.loadedRom == null
    }

    /**
     * Refresh the RetroAchievements status menu item to reflect the
     * current state of the native façade library. Called once at menu
     * construction and again on every [handleResetMenuState] (so a
     * user who drops a different native library on disk and restarts
     * gets an accurate menu without app exit).
     *
     * The label is a short, single-line description so the menu doesn't
     * resize when the user clicks it. The tooltip carries the longer
     * "how to fix this" message for the degraded state.
     */
    private fun updateRetroAchievementsStatus() {
        val label = raStatusLabelRef ?: return
        val available = com.github.alondero.nestlin.session.RetroAchievementsServiceFactory.isNativeLibraryAvailable()
        if (available) {
            val version = com.github.alondero.nestlin.session.RetroAchievementsServiceFactory.rcheevosVersion() ?: "unknown"
            label.text = "Status: native library available (rcheevos $version)"
            label.tooltip = javafx.scene.control.Tooltip(
                "The RetroAchievements native façade was loaded successfully."
            )
        } else {
            label.text = "Status: native library unavailable"
            label.tooltip = javafx.scene.control.Tooltip(
                "The native rcheevos_facade shared library was not found on the\n" +
                "classpath. Achievement tracking is disabled for this session.\n\n" +
                "How to enable:\n" +
                "  1. Run './gradlew buildNative' to compile the façade.\n" +
                "  2. The library is auto-copied into the runnable JAR.\n\n" +
                "Headless tools (replay, bootcheck) deliberately skip the library."
            )
        }
    }

    /**
     * Lazily construct the [RaSignInManager] tied to the coordinator's
     * service. Called once from the menu construction path so the
     * factory's catch-all (any UnsatisfiedLinkError / NoClassDefFoundError
     * etc.) keeps the UI alive even if the manager init throws.
     *
     * On construction the manager attempts a token-restore login against
     * any persisted credentials; the menu state updates via the listener.
     */
    private fun initializeRaSignInManager() {
        val service = sessionCoordinator.service
        val manager = try {
            com.github.alondero.nestlin.session.RaSignInManager.from(service)
        } catch (t: Throwable) {
            System.err.println("[APP] RA sign-in manager init failed: ${t.javaClass.simpleName}")
            return
        }
        raSignInManagerRef = manager
        raSignInListenerToken = manager.addListener { state ->
            // The listener may fire from any thread (the manager's bridge
            // completes on its own executor). Hop to JavaFX for menu updates.
            javafx.application.Platform.runLater {
                updateRaMenuForState(state)
                // Sign-in / sign-out / offline transitions are ROM-account
                // generation changes (issue #272 AC: "When the ROM/account
                // generation changes, the window switches to the new state
                // or closes cleanly"). Bump the achievements controller's
                // generation alongside the menu refresh so a stale refresh
                // can't poison the new sign-in's view-model, then re-publish.
                achievementsControllerLazy.bumpGeneration()
                achievementsControllerLazy.refresh()
                updateRaAchievementsMenuForViewModel()
            }
        }
        // Kick off the persisted-credentials restore. The listener above
        // will drive menu state as the manager transitions through
        // Authenticating → SignedIn / SignedOut / Offline.
        manager.start()
        updateRaMenuForState(manager.state)
    }

    /**
     * The sign-in state we observed on the previous menu update. Used
     * by [updateRaMenuForState] to detect a SignedIn transition that
     * wasn't followed by a sign-out — the exact trigger for the
     * mid-game "Restart for achievements" dialog (issue #272 AC #11).
     *
     * Reads from this field happen BEFORE we mutate the menu state, so
     * a comparison between [previousSignInState] and the new [state]
     * correctly identifies the transition.
     */
    private var previousSignInState: com.github.alondero.nestlin.session.RaSignInState? = null

    /**
     * Apply the menu's per-state enable/disable + text rules. Called from
     * the manager's state listener and at menu init.
     */
    private fun updateRaMenuForState(state: com.github.alondero.nestlin.session.RaSignInState) {
        val signIn = raSignInItem ?: return
        val profile = raProfileItem ?: return
        val signOut = raSignOutItem ?: return
        // The previous state is captured BEFORE the menu updates so we
        // can detect a transition into SignedIn while a ROM is loaded
        // (issue #272 AC #11). Reading from raSignInManagerRef would
        // see the new state (the listener fires AFTER state mutation)
        // — that's the bug we're avoiding.
        val prev = previousSignInState
        previousSignInState = state
        val wasSignedIn = prev is com.github.alondero.nestlin.session.RaSignInState.SignedIn
        when (state) {
            is com.github.alondero.nestlin.session.RaSignInState.Unavailable -> {
                signIn.text = "Sign In (unavailable)"
                signIn.isDisable = true
                profile.isDisable = true
                signOut.isDisable = true
            }
            is com.github.alondero.nestlin.session.RaSignInState.SignedOut -> {
                signIn.text = "Sign In..."
                signIn.isDisable = false
                profile.isDisable = true
                signOut.isDisable = true
            }
            is com.github.alondero.nestlin.session.RaSignInState.Authenticating -> {
                signIn.text = "Signing In..."
                signIn.isDisable = true
                profile.isDisable = true
                signOut.isDisable = true
            }
            is com.github.alondero.nestlin.session.RaSignInState.SignedIn -> {
                signIn.text = "Signed In as ${state.account.displayName.ifEmpty { state.account.username }}"
                signIn.isDisable = true
                profile.isDisable = false
                signOut.isDisable = false
                // Mid-game sign-in: if a ROM is loaded and we weren't
                // already signed in, surface the explicit battery-safe
                // restart dialog (issue #272 AC #11). The dialog wires
                // its positive button to coordinator.restartForAchievements
                // which preserves battery RAM by construction.
                val romLoaded = nestlin.loadedRom != null
                if (romLoaded && !wasSignedIn) {
                    RaAchievementRestartDialog.show(raSignInManagerRef, sessionCoordinator)
                }
            }
            is com.github.alondero.nestlin.session.RaSignInState.Offline -> {
                signIn.text = "Sign In (offline — retry)"
                signIn.isDisable = false
                profile.isDisable = true
                signOut.isDisable = true
            }
        }
    }

    /** Open the sign-in dialog. Handler for the Sign In menu item. */
    private fun handleRaSignIn() {
        val manager = raSignInManagerRef ?: return
        if (manager.state is com.github.alondero.nestlin.session.RaSignInState.Authenticating) return
        // Already on the JavaFX thread (MenuItem.setOnAction); showAndWait
        // is modal so the user can't interact with the rest of the UI.
        RaLoginDialog(manager).showAndWait()
    }

    /** Open the non-modal profile window. Handler for View Profile menu item. */
    private fun handleRaViewProfile() {
        val manager = raSignInManagerRef ?: return
        val existing = raProfileWindow
        if (existing != null) {
            existing.show()
            return
        }
        val window = RaProfileWindow(manager)
        window.stage.showingProperty().addListener { _, _, showing ->
            if (!showing) {
                window.dispose()
                raProfileWindow = null
            }
        }
        raProfileWindow = window
        window.show()
    }

    /** Sign out (preserves gameplay). Handler for Sign Out menu item. */
    private fun handleRaSignOut() {
        val manager = raSignInManagerRef ?: return
        manager.signOut()
    }

    /**
     * Open (or focus) the loaded-game achievements window (issue #272).
     * Idempotent — a second call focuses the existing window so the user
     * doesn't end up with multiple stacked instances.
     */
    private fun handleRaViewAchievements() {
        val existing = raAchievementsWindow
        if (existing != null) {
            existing.show()
            return
        }
        val window = RaAchievementsWindow(
            controller = achievementsControllerLazy,
            signInState = { raSignInManagerRef?.state ?: com.github.alondero.nestlin.session.RaSignInState.SignedOut },
        )
        window.stage.showingProperty().addListener { _, _, showing ->
            // Issue #288: the achievement event listener short-circuits
            // when this flag is false, so progress events that fire
            // dozens of times per second don't queue Platform.runLater
            // tasks onto the JavaFX thread while the user has the
            // window closed. Cleared on stop() too (defensive).
            achievementsWindowShowing = showing
            if (!showing) {
                window.dispose()
                if (raAchievementsWindow === window) raAchievementsWindow = null
            }
        }
        raAchievementsWindow = window
        // Set the flag BEFORE window.show() so the initial
        // showingProperty transition (showing = true) doesn't race a
        // listener body that reads false.
        achievementsWindowShowing = true
        window.show()
    }

    /**
     * Refresh the RetroAchievements menu's enablement / label state
     * based on the latest achievements view-model. The "Current Game
     * Achievements..." item is enabled iff a recognized game with core
     * achievements is loaded (issue #272 AC #1).
     */
    private fun updateRaAchievementsMenuForViewModel() {
        val item = raAchievementsItem ?: return
        val viewModel = achievementsControllerLazy.currentViewModel
        val enabled = viewModel is com.github.alondero.nestlin.session.RaAchievementsWindowViewModel.Recognized
        item.isDisable = !enabled
    }

    private fun setScaleMode(mode: ScaleMode) {
        if (displayConfig.scale != mode) {
            displayConfig = displayConfig.copy(scale = mode)
            DisplayConfig.save(displayConfig)
        }
        scaleMenuItems[mode]?.isSelected = true
        applyScale(mode)
        println("[APP] Display scale set to ${mode.label()}")
    }

    private fun setFullscreen(enable: Boolean) {
        stage.isFullScreen = enable
        // fullScreenProperty listener persists the change.
    }

    private fun applyScale(mode: ScaleMode) {
        val factor = mode.factor()
        // Drop any previous binding before swapping mode to avoid leaking listeners.
        canvas.widthProperty().unbind()
        canvas.heightProperty().unbind()
        if (factor != null) {
            canvas.width = (RESOLUTION_WIDTH * factor).toDouble()
            canvas.height = (RESOLUTION_HEIGHT * factor).toDouble()
            // Resize the window to the canvas's natural extent unless fullscreen owns it.
            if (!stage.isFullScreen) {
                stage.sizeToScene()
            }
        } else {
            // Fit: seed the window at 3x when entering Fit while windowed, so the user
            // always gets a usable starting size before the binding takes over.
            if (!stage.isFullScreen) {
                canvas.width = (RESOLUTION_WIDTH * 3).toDouble()
                canvas.height = (RESOLUTION_HEIGHT * 3).toDouble()
                stage.sizeToScene()
            }
            // Then bind canvas size to live holder dimensions, preserving aspect ratio.
            // Computed as an integer-or-fractional scale factor, then multiplied back out to
            // pixel dimensions — keeps the aspect ratio locked to 256:240 regardless of
            // window letterboxing.
            val widthFactor = javafx.beans.binding.Bindings.createDoubleBinding(
                { (canvasHolder.width / RESOLUTION_WIDTH).coerceAtLeast(1.0) },
                canvasHolder.widthProperty()
            )
            val heightFactor = javafx.beans.binding.Bindings.createDoubleBinding(
                { (canvasHolder.height / RESOLUTION_HEIGHT).coerceAtLeast(1.0) },
                canvasHolder.heightProperty()
            )
            val fitFactor = javafx.beans.binding.Bindings.min(widthFactor, heightFactor)
            canvas.widthProperty().bind(fitFactor.multiply(RESOLUTION_WIDTH.toDouble()))
            canvas.heightProperty().bind(fitFactor.multiply(RESOLUTION_HEIGHT.toDouble()))
        }
    }

    private fun updateTitle() {
        val gameName = nestlin.currentGameName()
        val base = if (gameName.isNotEmpty()) "Nestlin - $gameName" else "Nestlin"
        stage.title = if (nestlin.config.paused) "$base (Paused)" else base
    }

    private fun updateRecentMenu(recentRoms: List<Path>) {
        recentRomsMenu.items.clear()
        if (recentRoms.isEmpty()) {
            val emptyItem = MenuItem("(empty)")
            emptyItem.isDisable = true
            recentRomsMenu.items.add(emptyItem)
        } else {
            for (path in recentRoms) {
                val item = MenuItem(path.fileName.toString())
                item.setOnAction { loadRom(path) }
                recentRomsMenu.items.add(item)
            }
        }
    }

    private fun loadRom(path: Path) {
        // Issue #266: route through the coordinator. The coordinator's
        // onBeforeRomChange hook cancels the active movie session; its
        // onAfterRomChange hook refreshes the title / slot / debug UI
        // and flashes the Memory Editor. Bracket the call with
        // stopEmulation / startEmulation because the coordinator does
        // not manage the emulation thread (the application owns it).
        // Note: a recent-ROM load always reloads battery RAM (the
        // coordinator's loadRom does loadBatteryRam internally), so
        // this entry point matches handleLoadGame's semantics exactly.
        stopEmulation()
        sessionCoordinator.loadRom(path)
        // Pause-clear must run SYNCHRONOUSLY before startEmulation;
        // see the comment on sessionCoordinator above.
        clearPauseState()
        startEmulation()
    }

    private fun handleSaveState() {
        if (nestlin.loadedRom == null) {
            showError("No ROM Loaded", "Load a game before saving state.")
            return
        }
        val chooser = FileChooser()
        chooser.title = "Save Nestlin State"
        chooser.extensionFilters.add(FileChooser.ExtensionFilter("Nestlin Save (*.nstl)", "*.nstl"))
        chooser.initialFileName = defaultSaveFileName()
        val file = chooser.showSaveDialog(stage) ?: return
        performWithEmulationPaused {
            try {
                nestlin.saveState(file.toPath())
                println("[STATE] Saved to: ${file.absolutePath}")
                showToast("Saved ${file.name}")
            } catch (e: Exception) {
                println("[STATE] Save failed: ${e.message}")
                e.printStackTrace()
                showToast("Save failed: ${e.message ?: "unknown error"}", ToastSeverity.ERROR)
                Platform.runLater { showError("Save Failed", e.message ?: "Unknown error") }
            }
        }
    }

    private fun handleLoadState() {
        if (nestlin.loadedRom == null) {
            showError("No ROM Loaded", "Load a game before loading state.")
            return
        }
        val chooser = FileChooser()
        chooser.title = "Load Nestlin State"
        chooser.extensionFilters.add(FileChooser.ExtensionFilter("Nestlin Save (*.nstl)", "*.nstl"))
        val file = chooser.showOpenDialog(stage) ?: return
        performWithEmulationPaused {
            try {
                nestlin.loadState(file.toPath())
                println("[STATE] Loaded from: ${file.absolutePath}")
                showToast("Loaded ${file.name}")
            } catch (e: SaveState.IncompatibleSaveStateException) {
                // The toast itself is the user feedback; the legacy modal
                // Alert was the only feedback before issue #129 and is now
                // redundant with the red pill (and intrusive — it pauses
                // gameplay until the user clicks OK). Keep the println for
                // log diagnostics.
                println("[STATE] Incompatible save: ${e.message}")
                showToast("Incompatible: ${e.message ?: "unknown reason"}", ToastSeverity.ERROR)
            } catch (e: Exception) {
                println("[STATE] Load failed: ${e.message}")
                e.printStackTrace()
                showToast("Load failed: ${e.message ?: "unknown error"}", ToastSeverity.ERROR)
                Platform.runLater { showError("Load Failed", e.message ?: "Unknown error") }
            }
        }
    }

    /**
     * Save current state + frame buffer into [slot] (1..9). Pauses emulation
     * to serialise CPU/PPU/APU state safely (the same pattern as the legacy
     * quick-save / single-file-save). The frame is captured under the same
     * lock that protects it for the on-screen canvas, so the saved thumbnail
     * matches the pixels the user just saw.
     */
    private fun handleSlotSave(slot: Int) {
        if (nestlin.loadedRom == null) return
        // Snapshot the frame BEFORE pausing emulation: pausing the emulation
        // thread doesn't stop the render thread, but it does mean the
        // animation timer is still pulling from `nextFrame` under
        // frameBufferLock — so capture here, while the lock is uncontested.
        val frameRgb = synchronized(frameBufferLock) { nextFrame.copyOf() }
        performWithEmulationPaused {
            try {
                val stateOut = java.io.ByteArrayOutputStream()
                nestlin.saveState(stateOut)
                saveStateSlotManager.save(slot, stateOut.toByteArray(), frameRgb)
                println("[STATE] Saved slot $slot: ${saveStateSlotManager.statePath(slot)}")
                showToast("Saved to slot $slot")
                Platform.runLater { updateSlotMenu() }
            } catch (e: Exception) {
                println("[STATE] Slot $slot save failed: ${e.message}")
                e.printStackTrace()
                showToast("Slot $slot save failed: ${e.message ?: "unknown error"}", ToastSeverity.ERROR)
            }
        }
    }

    /**
     * Load state from [slot] (1..9). Pauses emulation while the state bytes
     * are deserialised into CPU/PPU/APU (same race-avoidance as save). Missing
     * slot is a normal flow (user typed F3 before saving into 3) — just log
     * and move on, no error dialog.
     */
    private fun handleSlotLoad(slot: Int) {
        if (nestlin.loadedRom == null) return
        performWithEmulationPaused {
            try {
                val stateBytes = saveStateSlotManager.loadStateBytes(slot)
                nestlin.loadState(java.io.ByteArrayInputStream(stateBytes))
                println("[STATE] Loaded slot $slot: ${saveStateSlotManager.statePath(slot)}")
                showToast("Loaded slot $slot")
            } catch (e: java.nio.file.NoSuchFileException) {
                println("[STATE] Slot $slot is empty")
                showToast("Slot $slot is empty", ToastSeverity.SUBTLE)
            } catch (e: SaveState.IncompatibleSaveStateException) {
                // Toast alone — see handleLoadState's parallel branch for the
                // rationale on dropping the modal Alert for issue #129.
                println("[STATE] Slot $slot is incompatible: ${e.message}")
                showToast("Slot $slot incompatible: ${e.message ?: "unknown reason"}", ToastSeverity.ERROR)
            } catch (e: Exception) {
                println("[STATE] Slot $slot load failed: ${e.message}")
                e.printStackTrace()
                showToast("Slot $slot load failed: ${e.message ?: "unknown error"}", ToastSeverity.ERROR)
            }
        }
    }

    /**
     * Walk all 9 slot menu items and refresh label/disable state from the
     * current disk contents. Called on ROM change (CRC changes) and after
     * every save. Format: "Slot N - 2026-06-06 14:32:15" or "Slot N (empty)".
     */
    private fun updateSlotMenu() {
        if (nestlin.loadedRom == null) {
            for (n in 1..9) {
                slotMenuItems[n]?.let {
                    it.text = "Slot $n (no ROM loaded)"
                    it.isDisable = true
                }
            }
            return
        }
        for (n in 1..9) {
            val item = slotMenuItems[n] ?: continue
            val lm = try { saveStateSlotManager.lastModifiedMillis(n) } catch (e: Exception) { null }
            if (lm == null) {
                item.text = "Slot $n (empty)"
                item.isDisable = true
            } else {
                val stamp = java.time.Instant.ofEpochMilli(lm)
                    .atZone(java.time.ZoneId.systemDefault())
                    .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                item.text = "Slot $n  -  $stamp"
                item.isDisable = false
            }
        }
    }

    /** True iff [code] is one of the F1..F9 keys that map to a save slot. */
    private fun isSlotKey(code: javafx.scene.input.KeyCode): Boolean =
        code in SLOT_KEYS

    /** Map an F-key to its slot number. Precondition: isSlotKey(code) is true. */
    private fun slotNumberFromKey(code: javafx.scene.input.KeyCode): Int =
        SLOT_KEYS.indexOf(code) + 1

    private fun defaultSaveFileName(): String {
        val romName = nestlin.loadedRom?.sourcePath?.fileName?.toString()
            ?.removeSuffix(".nes")?.removeSuffix(".7z") ?: "state"
        return "$romName.nstl"
    }

    /**
     * Pause the emulation thread, run [action] synchronously, then resume.
     * Avoids racing mutable CPU/PPU/APU state with the emulation loop.
     */
    private fun performWithEmulationPaused(action: () -> Unit) {
        stopEmulation()
        action()
        startEmulation()
    }

    private fun showError(title: String, message: String) {
        val alert = javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR)
        alert.title = title
        alert.headerText = null
        alert.contentText = message
        alert.showAndWait()
    }

    // ---------------------------------------------------------------------------------------
    // Movie record / playback (issue #123)
    //
    // The state machine has three states (MovieState). All transitions are owned by the
    // JavaFX thread; the emulation thread only ever invokes the latch hook that's installed
    // by MovieLiveRecorder / MovieLivePlayer. We deliberately do NOT call startEmulation /
    // stopEmulation here — the latch hooks run in the existing emulation loop, so adding
    // movie support is purely additive on top of the normal play flow.
    // ---------------------------------------------------------------------------------------

    /**
     * Begin recording the current ROM. Prompts for a destination `.fm2` file (defaults to
     * `<rom-name>.fm2` next to the ROM). On success: starts a [MovieLiveRecorder], flips
     * [movieState] to RECORDING, and seeds the controller's pending pad with the current
     * buttons so the first captured row matches what the game just saw.
     */
    private fun handleStartRecording() {
        if (nestlin.loadedRom == null) {
            showError("No ROM Loaded", "Load a game before recording.")
            return
        }
        if (movieState != MovieState.NONE) return

        val chooser = FileChooser()
        chooser.title = "Record Movie"
        chooser.extensionFilters.add(
            FileChooser.ExtensionFilter("FCEUX Movie (*.fm2)", "*.fm2")
        )
        chooser.initialFileName = defaultMovieFileName()
        val file = chooser.showSaveDialog(stage) ?: return

        val rom = nestlin.loadedRom!!.sourcePath ?: run {
            // Bytes-only loads (test fixtures, NSTL replay tooling) have no on-disk path,
            // and recording a movie needs to re-open the ROM to checksum it and reset
            // cleanly. Refuse up front with a clear message rather than NPE later.
            showError("No ROM File",
                "Recording requires a ROM loaded from a file (the current ROM was " +
                    "loaded from in-memory bytes and has no path).")
            return
        }
        val romImage = rom.load() ?: run {
            showError("Recording Failed", "Could not load ROM: $rom")
            return
        }
        val checksum = Fm2Format.romChecksum(romImage)
        val palFlag = nestlin.currentRegion() == Region.PAL

        // Power-cycle the machine so the recording starts from a known boot state
        // (mirrors FCEUX / Mesen "Movie → Record from Power-on" semantics). Without
        // this, a recording that started mid-game would carry whatever transient RAM
        // / mapper state the user happened to be in, and replaying from a different
        // boot would diverge on frame 1. performWithEmulationPaused guarantees the
        // reset sees a quiescent CPU/PPU/APU; the recorder installs its hook AFTER
        // the reset, so the first captured row reflects the post-reset state.
        performWithEmulationPaused {
            resetRomForMovieSession(rom)
        }

        // The reset zeroed the controllers; seed pending with the (now 0) buttons so
        // the latch's first commit is a no-op and the very first captured row matches
        // the freshly-booted pad.
        nestlin.getController1().pendingButtons = nestlin.getController1().buttons
        nestlin.getController2().pendingButtons = nestlin.getController2().buttons

        val recorder = MovieLiveRecorder(
            nestlin = nestlin,
            romFilename = rom.fileName.toString(),
            romChecksum = checksum,
            palFlag = palFlag,
        )
        recorder.start()
        liveRecorder = recorder
        activeMoviePath = file.toPath()
        movieState = MovieState.RECORDING
        println("[MOVIE] Recording started (from power-on) → ${file.absolutePath}")
    }

    /**
     * Stop any active movie session (record OR playback) and clean up the latch hook.
     * For recordings, prompts the user to save the captured FM2 (defaults to the file
     * chosen at record-start; if the user cancels, the recording is discarded).
     */
    private fun handleStopMovie() {
        when (movieState) {
            MovieState.NONE -> return
            MovieState.RECORDING -> {
                val recorder = liveRecorder ?: return
                val movie = recorder.stopAndSnapshot()
                liveRecorder = null
                movieState = MovieState.NONE

                val target = activeMoviePath
                if (target == null) {
                    println("[MOVIE] Recording stopped (${movie.length} frames); no file to save to")
                } else {
                    try {
                        java.nio.file.Files.newOutputStream(target).use { out ->
                            out.write(Fm2Format.write(movie).toByteArray(Charsets.UTF_8))
                        }
                        println("[MOVIE] Recording saved (${movie.length} frames) → $target")
                    } catch (e: Exception) {
                        showError("Save Failed", "Could not write FM2: ${e.message}")
                    }
                }
                activeMoviePath = null
            }
            MovieState.PLAYING -> {
                val player = livePlayer ?: return
                val played = player.framesDrivenCount
                player.stop()
                livePlayer = null
                movieState = MovieState.NONE
                activeMoviePath = null
                println("[MOVIE] Playback stopped after $played / ${player.totalFrames} frames")
            }
        }
        // Restore the controller's pending pad to the current buttons so the next key
        // event after a stop has a sensible baseline. Otherwise a stale pending value
        // would get committed on the next frame.
        nestlin.getController1().pendingButtons = nestlin.getController1().buttons
        nestlin.getController2().pendingButtons = nestlin.getController2().buttons
    }

    /**
     * Open a file dialog for an `.fm2`, load it, and start real-time playback. The
     * MovieLivePlayer installs its own latch hook that writes each row to
     * controller.buttons at every frame boundary, so the game sees the movie input
     * without any further UI plumbing.
     */
    private fun handlePlayMovie() {
        if (nestlin.loadedRom == null) {
            showError("No ROM Loaded", "Load a game before playing a movie.")
            return
        }
        if (movieState != MovieState.NONE) return

        val chooser = FileChooser()
        chooser.title = "Play Movie"
        chooser.extensionFilters.add(
            FileChooser.ExtensionFilter("FCEUX Movie (*.fm2)", "*.fm2")
        )
        val file = chooser.showOpenDialog(stage) ?: return

        val movie = try {
            java.nio.file.Files.newInputStream(file.toPath()).use { input ->
                Fm2Format.read(input.readBytes().toString(Charsets.UTF_8))
            }
        } catch (e: Exception) {
            showError("Load Failed", "Could not parse FM2: ${e.message}")
            return
        }
        if (movie.inputs.isEmpty()) {
            showError("Empty Movie", "The selected .fm2 file has no input rows.")
            return
        }

        // Same "boot from power-on" reset as recording: a movie file only encodes the
        // input log, not the boot state. If we don't reset, the first frame of playback
        // sees whatever state the user happened to be in when they hit Ctrl+Shift+P —
        // which won't match the boot state the recording started from, so the replay
        // diverges on frame 1.
        val rom = nestlin.loadedRom!!.sourcePath ?: run {
            showError("No ROM File",
                "Playing a movie requires the ROM to be loaded from a file " +
                    "(the current ROM has no on-disk path).")
            return
        }
        performWithEmulationPaused {
            resetRomForMovieSession(rom)
        }

        val player = MovieLivePlayer(
            nestlin = nestlin,
            movie = movie,
            // Issue #266: route the FM2 row-level reset commands through
            // the coordinator's serviceRuntime reset so the achievements
            // service sees a `resetRuntime` event paired with each
            // CPU-level reset the FM2 row carries. Without this seam,
            // MovieLivePlayer's latch hook would fire nestlin.powerReset
            // / softReset directly and bypass the service entirely.
            serviceReset = { sessionCoordinator.resetServiceRuntime() },
        )
        player.start()
        livePlayer = player
        activeMoviePath = file.toPath()
        movieState = MovieState.PLAYING
        println("[MOVIE] Playing ${movie.inputs.size}-frame movie from power-on → ${file.absolutePath}")
    }

    /**
     * Tear down the active movie session WITHOUT saving. Used when the ROM is reloaded
     * (load / hard reset / file watcher) or when the application is exiting.
     */
    private fun cancelMovieSession() {
        if (movieState == MovieState.NONE) return
        liveRecorder?.cancel()
        liveRecorder = null
        livePlayer?.stop()
        livePlayer = null
        movieState = MovieState.NONE
        activeMoviePath = null
    }

    /**
     * Refresh the REC/PLAY indicator. Called every frame from the AnimationTimer. Uses
     * the cheap-toggle pattern (only mutate the scene node when something actually
     * changed), same approach as the fast-forward indicator refresh.
     */
    private fun refreshMovieIndicator() {
        // End-of-movie auto-stop: the player reports isFinished once the last row's input
        // has been written. We clean up here (JavaFX thread) rather than from inside the
        // latch hook (emulation thread) so the on-screen indicator and any menu state can
        // be touched without crossing threads. Idempotent — handleStopMovie is a no-op
        // when movieState != PLAYING.
        if (movieState == MovieState.PLAYING && livePlayer?.isFinished == true) {
            handleStopMovie()
        }

        val text = when (movieState) {
            MovieState.NONE -> ""
            MovieState.RECORDING -> {
                val n = liveRecorder?.frameCount ?: 0
                "● REC $n"
            }
            MovieState.PLAYING -> {
                val n = (livePlayer?.currentRow ?: -1) + 1
                val total = livePlayer?.totalFrames ?: 0
                if (livePlayer?.isFinished == true) "▶ END" else "▶ PLAY $n/$total"
            }
        }
        if (text != movieIndicatorText) {
            movieIndicatorText = text
            movieIndicator.text = text
        }
        val shouldShow = movieState != MovieState.NONE
        if (movieIndicator.isVisible != shouldShow) {
            movieIndicator.isVisible = shouldShow
        }
        val color = when (movieState) {
            MovieState.RECORDING -> javafx.scene.paint.Color.web("#FF4040")
            MovieState.PLAYING -> javafx.scene.paint.Color.web("#40FF40")
            else -> javafx.scene.paint.Color.web("#FF4040")
        }
        if (movieIndicator.fill != color) {
            movieIndicator.fill = color
        }
    }

    private fun defaultMovieFileName(): String {
        val romName = nestlin.loadedRom?.sourcePath?.fileName?.toString()
            ?.removeSuffix(".nes")?.removeSuffix(".7z") ?: "movie"
        return "$romName.fm2"
    }

    /**
     * Reload [romPath] from disk and power-cycle the machine, preserving battery RAM.
     *
     * Used to put the emulator in a known boot state before a movie record or playback
     * session — without this, the captured/played movie would inherit whatever transient
     * state the user happened to be in when they hit the hotkey, and the recording
     * couldn't be reproduced deterministically (replaying from a different boot would
     * diverge immediately).
     *
     * **Must be called from the JavaFX thread with emulation paused** — directly mutates
     * CPU/PPU/APU/mapper state and the GamePak. Callers handle the stop/start around
     * this method (see [performWithEmulationPaused] for the canonical wrapper).
     */
    private fun resetRomForMovieSession(romPath: Path) {
        // Issue #266: route through the coordinator for the canonical
        // battery-flush + service-unload + install-and-reset +
        // battery-restore + service-prepare sequence. Calling loadRom
        // here is semantically identical to the previous direct
        // `nestlin.saveBatteryRam + load + powerReset + loadBatteryRam`
        // chain — the coordinator does the same work in the same order,
        // with the extra step of telling the (currently no-op) RA
        // service to unload and re-prepare. The coordinator's
        // onBeforeRomChange hook is harmless here: by the time the
        // caller reaches this method, cancelMovieSession has already
        // fired (handleStartRecording / handlePlayMovie haven't installed
        // the recorder/player yet; handleHardReset cancels first). The
        // onAfterRomChange hook hops to JavaFX to refresh the UI and
        // flash the Memory Editor (issue #169) — that flash is the one
        // this method used to do inline, and the user-visible effect is
        // the same.
        sessionCoordinator.loadRom(romPath)
        // powerReset() (which the coordinator called inside loadRom) leaves
        // the controllers untouched — the user may still have been holding a
        // button when they hit the hotkey. For a movie session we want the
        // game to see a "no buttons held" state on frame 0, otherwise the
        // very first captured row of a recording (or the first latched row
        // of a playback) would include a phantom press that wasn't part of
        // the user's input. Clear both the live pad and the keyboard buffer.
        nestlin.getController1().setButtonBitmap(0)
        nestlin.getController1().pendingButtons = 0
        nestlin.getController2().setButtonBitmap(0)
        nestlin.getController2().pendingButtons = 0
    }

    /**
     * Show a save-state toast (issue #129) at the current wall-clock. Thread-safe:
     * only mutates the controller's volatile field, never a scene-graph node. The
     * AnimationTimer picks it up on the next frame via [refreshToastIndicator].
     */
    private fun showToast(text: String, severity: ToastSeverity = ToastSeverity.INFO) {
        toastController.show(text, severity, System.currentTimeMillis())
    }

    /**
     * Reflect the controller's current toast (or its absence) onto the JavaFX
     * Label. Called every frame from the AnimationTimer. The text fill is
     * looked up from a per-severity constant (TOAST_FILLS) to avoid allocating
     * a new Color every frame — Paint.equals is reference-based on parsed
     * colours, so a fresh Color.web() would force textFill = ... on every
     * frame even though the visible colour is unchanged.
     */
    private fun refreshToastIndicator(nowMillis: Long) {
        val toast = toastController.currentToast(nowMillis)
        if (toast == null) {
            if (toastIndicator.isVisible) toastIndicator.isVisible = false
            return
        }
        // Only mutate the scene node when content actually changes, mirroring
        // the fast-forward indicator's cheap-toggle pattern.
        if (toastIndicator.text != toast.text) toastIndicator.text = toast.text
        val targetFill = TOAST_FILLS.getValue(toast.severity)
        if (toastIndicator.textFill != targetFill) toastIndicator.textFill = targetFill
        if (!toastIndicator.isVisible) toastIndicator.isVisible = true
    }

    /**
     * Issue #270: reflect the RA notification controller's current state
     * onto the unlock overlay (top-right) and the system banner
     * (top-centre).
     *
     * The [RaNotificationController] is the source of truth — its
     * `visibleAt(nowMillis)` returns the priority-resolved notification
     * (system > unlock > null). The two pills never display at the same
     * time: when `visibleAt` returns a `SystemNotification`, the unlock
     * pill is hidden, and vice versa.
     */
    private fun refreshUnlockOverlay(nowMillis: Long) {
        val notif = sessionCoordinator.notificationController.visibleAt(nowMillis)
        when (notif) {
            null -> {
                if (unlockOverlay.isVisible) unlockOverlay.isVisible = false
                if (systemBanner.isVisible) systemBanner.isVisible = false
            }
            is SystemNotification -> {
                if (unlockOverlay.isVisible) unlockOverlay.isVisible = false
                val text = notif.message
                if (systemBanner.text != text) systemBanner.text = text
                if (!systemBanner.isVisible) systemBanner.isVisible = true
            }
            is UnlockNotification -> {
                if (systemBanner.isVisible) systemBanner.isVisible = false
                val titleLine = "${notif.title}  (${notif.points} pts)"
                val descLine = if (notif.description.isNotEmpty()) notif.description else null
                val combined = if (descLine != null) "$titleLine\n$descLine" else titleLine
                if (unlockOverlay.text != combined) unlockOverlay.text = combined
                if (!unlockOverlay.isVisible) unlockOverlay.isVisible = true
            }
        }
    }

    private fun handleHardReset() {
        if (nestlin.loadedRom == null) {
            val alert = javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR)
            alert.title = "No ROM Loaded"
            alert.contentText = "Please load a game first."
            alert.showAndWait()
            return
        }
        val path = nestlin.loadedRom!!.sourcePath ?: run {
            val alert = javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR)
            alert.title = "No ROM File"
            alert.contentText = "Hard-reset needs to re-load the ROM from disk; " +
                "the current ROM has no on-disk path."
            alert.showAndWait()
            return
        }
        // Hard reset = same ROM, but a fresh boot. Drop any active movie
        // session so playback doesn't get out of sync with the new boot
        // state. Issue #266: the coordinator's onBeforeRomChange hook
        // also cancels the movie session — we keep the explicit call here
        // so the ordering reads naturally ("cancel movie first, then
        // stop the thread"); it's idempotent.
        cancelMovieSession()
        stopEmulation()
        resetRomForMovieSession(path)
        // Pause-clear must run SYNCHRONOUSLY before startEmulation;
        // see the comment on sessionCoordinator above.
        clearPauseState()
        startEmulation()
    }

    private fun handleExit() {
        // If we're recording, save the partial movie before tearing down — losing the
        // last few seconds of input is annoying but better than losing the whole run.
        // We reuse the regular stop-and-save path, which also clears the latch hook.
        if (movieState == MovieState.RECORDING) {
            handleStopMovie()
        } else {
            cancelMovieSession()
        }
        stopEmulation()
        // Issue #266: route shutdown through the coordinator so the
        // battery flush + service unload + service shutdown sequence is
        // canonical (and future RA teardown is wired in one place). The
        // coordinator's shutdown is idempotent — safe to call here even
        // if handleStopMovie already touched some of the same state.
        sessionCoordinator.shutdown()
        Platform.exit()
    }

    override fun stop() {
        // Cancel any active movie session BEFORE the shutdown sequence
        // runs. The recorder / player own background work (the latch hook
        // for the recorder, the playback engine for the player) that the
        // emulation-thread stop below would orphan without this — and the
        // coordinator's shutdown is a no-op for any active movie session,
        // so this is the only place to cancel it.
        //
        // handleExit() does the same thing explicitly above; this branch
        // covers the JavaFX-runtime-initiated shutdown path (window
        // close without going through handleExit, JVM tear-down).
        cancelMovieSession()

        nestlin.stop()
        running = false

        // Clean up screenshot timer
        screenshotTimer?.cancel()
        screenshotTimer = null

        // Clean up battery-flush timer; defensive final flush in case handleExit()
        // wasn't the entry point (e.g. JavaFX runtime tears us down through stop() directly).
        batteryFlushTimer?.cancel()
        batteryFlushTimer = null
        // Issue #266: same canonical shutdown as handleExit — flush
        // battery, unload the game, tear down the service. Idempotent.
        sessionCoordinator.shutdown()

        // Clean up gamepad
        gamepadInput.shutdown()

        // Tear down the RA sign-in manager before the service handle is
        // destroyed — the manager's HTTP bridge binds to the same native
        // handle and would race the shutdown otherwise. Idempotent.
        raSignInListenerToken?.let { token ->
            raSignInManagerRef?.removeListener(token)
        }
        raSignInListenerToken = null
        raSignInManagerRef?.shutdown()
        raProfileWindow?.dispose()
        raProfileWindow = null

        // Issue #288: clear the achievements-window visibility gate.
        // The achievement event listener field on the native service
        // is already nulled by sessionCoordinator.shutdown() above;
        // this drop is the application-side visibility flag.
        achievementsWindowShowing = false

        // Clean up audio
        audioLine?.stop()
        audioLine?.close()
        audioThread?.join(1000)  // Wait up to 1 second for audio thread to finish
    }

    private fun initAudio() {
        if (!audioEnabled) return

        try {
            // Try multiple audio format configurations (most to least compatible)
            val formats = listOf(
                // Try big-endian first (more common on many systems)
                AudioFormat(44100f, 16, 1, true, true),
                // Try little-endian
                AudioFormat(44100f, 16, 1, true, false),
                // Try 48kHz (common on modern systems)
                AudioFormat(48000f, 16, 1, true, true),
                AudioFormat(48000f, 16, 1, true, false),
                // Try 8-bit mono as fallback
                AudioFormat(44100f, 8, 1, true, true),
                AudioFormat(44100f, 8, 1, true, false)
            )

            var selectedFormat: AudioFormat? = null
            var bestMatch: SourceDataLine? = null

            for (format in formats) {
                try {
                    val info = DataLine.Info(SourceDataLine::class.java, format)
                    val line = AudioSystem.getLine(info) as? SourceDataLine
                    if (line != null) {
                        // ~93 ms headroom at 44.1 kHz 16-bit mono (~46 ms stereo) to absorb
                        // emulation-thread throttle jitter.
                        line.open(format, 8192)
                        line.start()
                        selectedFormat = format
                        bestMatch = line
                        println("[AUDIO] Found compatible format: ${format.sampleRate} Hz, ${format.sampleSizeInBits}-bit, ${if (format.isBigEndian) "big" else "little"}-endian")
                        break
                    }
                } catch (e: Exception) {
                    // Continue to next format
                    continue
                }
            }

            if (bestMatch != null && selectedFormat != null) {
                audioLine = bestMatch
                // Start audio playback thread
                audioThread = thread(isDaemon = true) {
                    audioPlaybackLoop(selectedFormat)
                }
                println("[AUDIO] Audio initialized successfully")
            } else {
                println("[AUDIO] No compatible audio formats found")
                audioEnabled = false
            }
        } catch (e: Exception) {
            println("[AUDIO] Failed to initialize audio: ${e.message}")
            e.printStackTrace()
            audioEnabled = false
        }
    }

    private fun audioPlaybackLoop(format: AudioFormat) {
        val buffer = ByteArray(2048)
        val bytesPerSample = if (format.sampleSizeInBits == 16) 2 else 1
        val maxSamplesPerWrite = buffer.size / bytesPerSample
        val resampler = AudioResampler(nestlin.getAudioSampleRateHz(), format.sampleRate.toDouble())
        val outputSamples = ShortArray(maxSamplesPerWrite)
        var exitReason = "stopped"

        // Debug: track underrun events
        var totalUnderrunEvents = 0
        var totalSilentReads = 0

        while (running && audioEnabled) {
            try {
                val inputSamples = nestlin.getAudioSamples()
                if (inputSamples.isNotEmpty()) {
                    resampler.push(inputSamples)
                } else {
                    totalSilentReads++
                }

                var produced = resampler.resample(outputSamples, maxSamplesPerWrite)
                var wrote = false
                while (produced > 0) {
                    wrote = true
                    when {
                        format.sampleSizeInBits == 16 -> {
                            // Convert shorts to bytes
                            if (format.isBigEndian) {
                                // Big-endian: MSB first
                                for (i in 0 until produced) {
                                    val sample = outputSamples[i].toInt()
                                    buffer[i * 2] = (sample shr 8).toByte()
                                    buffer[i * 2 + 1] = (sample and 0xFF).toByte()
                                }
                            } else {
                                // Little-endian: LSB first
                                for (i in 0 until produced) {
                                    val sample = outputSamples[i].toInt()
                                    buffer[i * 2] = (sample and 0xFF).toByte()
                                    buffer[i * 2 + 1] = (sample shr 8).toByte()
                                }
                            }
                            audioLine?.write(buffer, 0, produced * 2)
                        }
                        format.sampleSizeInBits == 8 -> {
                            // Convert shorts to 8-bit unsigned
                            for (i in 0 until produced) {
                                // Scale from -32768..32767 to 0..255
                                val scaledValue = ((outputSamples[i].toInt() + 32768) shr 8).toByte()
                                buffer[i] = scaledValue
                            }
                            audioLine?.write(buffer, 0, produced)
                        }
                    }
                    produced = resampler.resample(outputSamples, maxSamplesPerWrite)
                }

                if (!wrote) {
                    totalUnderrunEvents++
                    Thread.sleep(1)  // Avoid busy-waiting
                }
            } catch (e: Exception) {
                if (running && audioEnabled) {
                    println("[AUDIO] Error in audio playback: ${e.message}")
                }
                exitReason = "error"
                break
            }
        }

        println("[AUDIO] Audio thread terminated (${exitReason})")
        println("[AUDIO] Debug: silent reads=${totalSilentReads}, underrun events=${totalUnderrunEvents}")
    }

    override fun frameUpdated(frame: Frame) {
        synchronized(frameBufferLock) {
            // Write directly at native 256x240 — upscaling is the Canvas's job.
            frame.scanlines.withIndex().forEach { (y, scanline) ->
                val rowBase = y * RESOLUTION_WIDTH * 3
                scanline.withIndex().forEach { (x, pixel) ->
                    val idx = rowBase + x * 3
                    nextFrame[idx] = (pixel shr 16).toByte()
                    nextFrame[idx + 1] = (pixel shr 8).toByte()
                    nextFrame[idx + 2] = pixel.toByte()
                }
            }
        }
    }

    private fun handleInput(code: javafx.scene.input.KeyCode, pressed: Boolean) {
        // Handle screenshot separately (always S key)
        if (code == javafx.scene.input.KeyCode.S && pressed) {
            captureScreenshot()
            return
        }

        // Resolve which player this keypress targets. P1 wins ties (a key bound in
        // both maps is routed to P1). Fixes the pre-2-player bug where P2 keystrokes
        // (numpad, etc.) were silently written to controller1.pendingButtons and
        // recorded as P1 column in the FM2 movie file.
        val player = com.github.alondero.nestlin.input.InputConfig.firstPlayerForKey(
            code, inputConfig.keyboard
        ) ?: return
        val controller = when (player) {
            com.github.alondero.nestlin.input.Player.ONE -> nestlin.getController1()
            com.github.alondero.nestlin.input.Player.TWO -> nestlin.getController2()
        }
        val button = inputConfig.getButtonForKey(code, player) ?: return

        // Use configurable keyboard mapping. Routing depends on movie state (issue #123):
        //   - NONE:       write directly to the live pad. Game sees updates within the
        //                 same frame they're typed — the standard NES-accurate behavior.
        //   - RECORDING:  write to pendingButtons; the frame-end latch will commit once
        //                 per frame so the game sees a per-frame-latched value.
        //   - PLAYING:    drop the input — the latch hook is writing the next movie row
        //                 to the controller, and we don't want a stray keypress to land
        //                 in controller.buttons between latch commits.
        when (movieState) {
            MovieState.NONE ->
                controller.setButton(button, pressed)
            MovieState.RECORDING -> {
                val current = controller.pendingButtons
                controller.pendingButtons = if (pressed)
                    current or button.mask
                else
                    current and button.mask.inv()
            }
            MovieState.PLAYING -> {
                // intentionally drop — the movie owns the input
            }
        }
    }

    /**
     * Captures the current screen buffer and saves it as a PNG file.
     * Thread-safe: Creates a copy of the frame buffer before file I/O.
     * Non-blocking: File write happens on background thread.
     * Triggered by pressing the 'S' key.
     */
    private fun captureScreenshot() {
        // Get a thread-safe copy of the frame buffer
        val frameData = synchronized(frameBufferLock) {
            nextFrame.copyOf()
        }

        // Run file I/O on background thread to avoid blocking the UI
        thread {
            try {
                val path = screenshotManager.saveScreenshot(frameData, RESOLUTION_WIDTH, RESOLUTION_HEIGHT)
                println("[SCREENSHOT] Saved to: $path")
            } catch (e: IOException) {
                println("[SCREENSHOT] File I/O error: ${e.message}")
            } catch (e: IllegalArgumentException) {
                println("[SCREENSHOT] Invalid parameters: ${e.message}")
            } catch (e: Exception) {
                println("[SCREENSHOT] Unexpected error: ${e.message}")
                e.printStackTrace()
            }
        }
    }

    companion object {
        // Zapper light-sense brightness cutoff: a pixel counts as "bright"
        // (lit target) when R+G+B exceeds this, out of a 765 max. 384 (~half) cleanly
        // separates a white target from the dark backdrop games flash it against.
        private const val ZAPPER_BRIGHTNESS_THRESHOLD = 384

        // Aim reticle geometry: a SIZE-box whose centre is the aim point.
        private const val ZAPPER_CROSSHAIR_SIZE = 24.0
        private const val ZAPPER_CROSSHAIR_CENTER = ZAPPER_CROSSHAIR_SIZE / 2.0

        // The 9 F-keys that map to save state slots. Order matters: index 0
        // corresponds to slot 1 (F1), index 8 to slot 9 (F9). Used by
        // isSlotKey() / slotNumberFromKey() to keep the F1..F9 → 1..9 mapping
        // in a single place.
        private val SLOT_KEYS = listOf(
            javafx.scene.input.KeyCode.F1, javafx.scene.input.KeyCode.F2,
            javafx.scene.input.KeyCode.F3, javafx.scene.input.KeyCode.F4,
            javafx.scene.input.KeyCode.F5, javafx.scene.input.KeyCode.F6,
            javafx.scene.input.KeyCode.F7, javafx.scene.input.KeyCode.F8,
            javafx.scene.input.KeyCode.F9
        )

        // Per-severity text fill for the save-state toast (issue #129).
        // Hoisted to a constant map so refreshToastIndicator doesn't allocate
        // a new Color per AnimationTimer frame (Paint comparison is
        // reference-based for Color.web-parsed colours).
        private val TOAST_FILLS: Map<ToastSeverity, javafx.scene.paint.Color> = mapOf(
            ToastSeverity.INFO   to javafx.scene.paint.Color.web("#FFFFFF"),
            ToastSeverity.SUBTLE to javafx.scene.paint.Color.web("#CCCCCC"),
            ToastSeverity.ERROR  to javafx.scene.paint.Color.web("#FF6B6B"),
        )
    }
}
