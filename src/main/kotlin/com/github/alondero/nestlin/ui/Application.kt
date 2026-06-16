package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.EmulatorConfig
import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.Controller
import com.github.alondero.nestlin.SaveState
import com.github.alondero.nestlin.apu.AudioResampler
import com.github.alondero.nestlin.file.load
import com.github.alondero.nestlin.input.GamepadInput
import com.github.alondero.nestlin.input.InputConfig
import com.github.alondero.nestlin.movie.Fm2Format
import com.github.alondero.nestlin.movie.Movie
import com.github.alondero.nestlin.movie.MovieLivePlayer
import com.github.alondero.nestlin.movie.MovieLiveRecorder
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
    // Hold-Tab fast-forward: disables throttling while held, restores it on release.
    private val fastForward = FastForwardController(nestlin.config)
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
    private var running = false
    // Frame buffer synchronization for thread-safe screenshot capture
    private val frameBufferLock = Any()
    // Native-resolution RGB buffer. Upscaling is handled by ImageView.fitWidth/Height, not by replication.
    private var nextFrame = ByteArray(RESOLUTION_HEIGHT * RESOLUTION_WIDTH * 3)

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

    // Current ROM path for Hard Reset functionality
    private var currentRomPath: Path? = null
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

    // Input configuration and gamepad support
    private val inputConfig = InputConfig.load()
    private lateinit var gamepadInput: GamepadInput

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
            ScaleMode.values().forEach { mode ->
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

            settingsMenu.items.addAll(throttleMenuItem, scaleMenu, fullscreenItem)
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
            memoryEditorItem.isDisable = currentRomPath == null
            memoryEditorMenuItem = memoryEditorItem
            debugMenu.items.add(memoryEditorItem)
            menuBar.menus.add(debugMenu)

            // Create layout with menu bar and the canvas holder. VBox.setVgrow lets the
            // holder expand to fill remaining vertical space in fullscreen / Fit mode.
            val root = VBox()
            root.children.addAll(menuBar, canvasHolder)
            VBox.setVgrow(canvasHolder, javafx.scene.layout.Priority.ALWAYS)

            // Overlay the fast-forward indicator on top of the game image, pinned top-right.
            canvasHolder.children.add(fastForwardIndicator)
            StackPane.setAlignment(fastForwardIndicator, javafx.geometry.Pos.TOP_RIGHT)
            StackPane.setMargin(fastForwardIndicator, javafx.geometry.Insets(4.0, 6.0, 0.0, 0.0))

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
                    // F11 is handled by the Fullscreen menu accelerator (see Settings menu).
                    else -> handleInput(event.code, true)
                }
            }
            scene.setOnKeyReleased { event -> handleInput(event.code, false) }

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
                if (!focused) fastForward.release()
            }

            // Window close (X button) goes through handleExit so battery RAM gets flushed.
            // Without this the JavaFX runtime would jump straight to stop() and bypass the flush.
            setOnCloseRequest { handleExit() }

            show()
        }

        startBatteryFlushTimer()

        // Initialize gamepad input
        gamepadInput = GamepadInput(nestlin.getController1(), inputConfig.gamepad)
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
                // Same cheap-toggle pattern for the REC/PLAY movie indicator.
                refreshMovieIndicator()

                // Save-state toast: pull from the controller each frame. We're
                // already on the JavaFX Application Thread (AnimationTimer.handle
                // runs here), so direct scene-node mutation is safe.
                refreshToastIndicator(System.currentTimeMillis())
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
                    currentRomPath = Paths.get(romPathArg)
                    load(currentRomPath!!)
                    powerReset()
                    loadBatteryRam(currentRomPath!!)
                }
                // Both calls mutate JavaFX UI nodes, so they need to hop back
                // to the JavaFX thread (we're inside a `thread { ... }` here). When no
                // ROM is loaded, updateTitle() shows "Nestlin - No Game Loaded" and
                // updateSlotMenu() disables every slot with a "(no ROM loaded)" label.
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
                val rom = currentRomPath ?: return
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
            // Drop any active movie session — a recording against ROM A is meaningless
            // once ROM B loads, and playback must restart from a freshly-cold-booted game.
            cancelMovieSession()
            stopEmulation()
            // Flush the previous ROM's SRAM before its mapper is replaced.
            currentRomPath?.let { nestlin.saveBatteryRam(it) }
            currentRomPath = romPath
            nestlin.load(romPath)
            nestlin.powerReset()
            nestlin.loadBatteryRam(romPath)
            clearPauseState()
            // Drop any stale "Saved/Loaded slot N" message from the previous ROM
            // before its slot CRC changes underneath the user.
            toastController.clear()
            updateTitle()
            // Refresh the slot menu: new ROM = new CRC = different slot files.
            updateSlotMenu()
            updateDebugMenu()
            EmulatorConfig.addRecentRom(romPath)
            updateRecentMenu(EmulatorConfig.getRecentRoms())
            // Flash the Memory Editor grid (issue #169) so the user sees a
            // full-tick highlight on every visible cell — confirms the new
            // ROM is actually being observed.
            flashMemoryEditorIfOpen()
            startEmulation()
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
    private fun handleOpenMemoryEditor() {
        if (currentRomPath == null) return
        val existing = memoryEditorWindow
        if (existing != null) {
            existing.show()
            return
        }
        val window = MemoryEditorWindow { addr -> nestlin.peekMemory(addr) }
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
     * if the editor is not open (the user can't see it flash anyway), and
     * cheap enough to call from the emulation thread or the JavaFX thread
     * (just touches a flag the editor's own refresh timer reads).
     */
    private fun flashMemoryEditorIfOpen() {
        memoryEditorWindow?.markAllChanged()
    }

    /** Grey out the Debug → Memory Editor item when no ROM is loaded. */
    private fun updateDebugMenu() {
        memoryEditorMenuItem?.isDisable = currentRomPath == null
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
        // Drop any active movie session — playback/recording is per-ROM.
        cancelMovieSession()
        stopEmulation()
        currentRomPath = path
        nestlin.load(path)
        nestlin.powerReset()
        clearPauseState()
        // Drop any stale "Saved/Loaded slot N" toast from the previous ROM —
        // the slot CRC changes underneath the user (parallel to handleLoadGame).
        toastController.clear()
        updateTitle()
        // Refresh slot menu so each slot's label reflects disk state for
        // the new ROM's CRC. Without this, a user loading ROM B would see
        // ROM A's slot timestamps until they saved into a slot of their own.
        updateSlotMenu()
        updateDebugMenu()
        // Flash the Memory Editor (issue #169) — same rationale as
        // handleLoadGame: the user just transitioned to a different game and
        // deserves visual confirmation in the editor window.
        flashMemoryEditorIfOpen()
        startEmulation()
    }

    private fun handleSaveState() {
        if (currentRomPath == null) {
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
        if (currentRomPath == null) {
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
        if (currentRomPath == null) return
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
        if (currentRomPath == null) return
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
        if (currentRomPath == null) {
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
        val romName = currentRomPath?.fileName?.toString()
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
        if (currentRomPath == null) {
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

        val rom = currentRomPath!!
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
        if (currentRomPath == null) {
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
        val rom = currentRomPath!!
        performWithEmulationPaused {
            resetRomForMovieSession(rom)
        }

        val player = MovieLivePlayer(nestlin, movie)
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
        val romName = currentRomPath?.fileName?.toString()
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
        // Real cartridges keep their battery alive across a power-cycle. Mirror that:
        // persist current SRAM, then reload it post-reset so the boot state matches
        // what a real player would see after flicking the power switch.
        nestlin.saveBatteryRam(romPath)
        nestlin.load(romPath)
        nestlin.powerReset()
        nestlin.loadBatteryRam(romPath)
        // powerReset() leaves the controllers untouched — the user may still have been
        // holding a button when they hit the hotkey. For a movie session we want the
        // game to see a "no buttons held" state on frame 0, otherwise the very first
        // captured row of a recording (or the first latched row of a playback) would
        // include a phantom press that wasn't part of the user's input. Clear both the
        // live pad and the keyboard buffer.
        nestlin.getController1().setButtonBitmap(0)
        nestlin.getController1().pendingButtons = 0
        nestlin.getController2().setButtonBitmap(0)
        nestlin.getController2().pendingButtons = 0
        // Flash the Memory Editor (issue #169). The movie session just rewound
        // to a known boot state — without the flash, the editor would show
        // whatever the diff against the pre-reset state happened to produce,
        // which is rarely a clean "everything is new" because a power-cycle
        // leaves most static bytes (ROM, mapper regs) unchanged.
        flashMemoryEditorIfOpen()
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

    private fun handleHardReset() {
        if (currentRomPath == null) {
            val alert = javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR)
            alert.title = "No ROM Loaded"
            alert.contentText = "Please load a game first."
            alert.showAndWait()
            return
        }
        // Hard reset = same ROM, but a fresh boot. Drop any active movie session so
        // playback doesn't get out of sync with the new boot state.
        cancelMovieSession()
        stopEmulation()
        resetRomForMovieSession(currentRomPath!!)
        clearPauseState()
        updateTitle()
        // CRC is unchanged by a hard reset, but refresh the slot menu
        // defensively in case future changes alter the GamePak identity.
        updateSlotMenu()
        // Flash the Memory Editor (issue #169) so the user sees the grid
        // briefly light up — useful for a hard reset because the visible
        // cells will often be re-zeroed by the boot code.
        flashMemoryEditorIfOpen()
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
        currentRomPath?.let { nestlin.saveBatteryRam(it) }
        Platform.exit()
    }

    override fun stop() {
        nestlin.stop()
        running = false

        // Clean up screenshot timer
        screenshotTimer?.cancel()
        screenshotTimer = null

        // Clean up battery-flush timer; defensive final flush in case handleExit()
        // wasn't the entry point (e.g. JavaFX runtime tears us down through stop() directly).
        batteryFlushTimer?.cancel()
        batteryFlushTimer = null
        currentRomPath?.let { nestlin.saveBatteryRam(it) }

        // Clean up gamepad
        gamepadInput.shutdown()

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

        // Use configurable keyboard mapping. Routing depends on movie state (issue #123):
        //   - NONE:       write directly to the live pad. Game sees updates within the
        //                 same frame they're typed — the standard NES-accurate behavior.
        //   - RECORDING:  write to pendingButtons; the frame-end latch will commit once
        //                 per frame so the game sees a per-frame-latched value.
        //   - PLAYING:    drop the input — the latch hook is writing the next movie row
        //                 to the controller, and we don't want a stray keypress to land
        //                 in controller.buttons between latch commits.
        val controller = nestlin.getController1()
        val button = inputConfig.getButtonForKey(code) ?: return
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
