package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.Controller.Button
import com.github.alondero.nestlin.input.ControllerBindings
import com.github.alondero.nestlin.input.GamepadBinding
import com.github.alondero.nestlin.input.GamepadInput
import com.github.alondero.nestlin.input.InputConfig
import com.github.alondero.nestlin.input.InputDevice
import com.github.alondero.nestlin.input.Player
import com.github.alondero.nestlin.input.PlayerKeyBindings
import com.github.alondero.nestlin.input.PortAssignment
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.ChoiceBox
import javafx.scene.control.Label
import javafx.scene.control.Tab
import javafx.scene.control.TabPane
import javafx.scene.image.Image
import javafx.scene.image.ImageView
import javafx.scene.input.KeyCode
import javafx.scene.input.KeyEvent
import javafx.scene.layout.GridPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Pane
import javafx.scene.layout.StackPane
import javafx.scene.layout.VBox
import javafx.scene.text.Font
import javafx.stage.Stage
import javafx.scene.control.Button as FxButton

/**
 * The Controller Configuration screen (Settings → Configure Controls…) — issue: 2-player support.
 *
 * Single window with a tab pane containing one [PlayerConfigPane] per player (P1, P2).
 * Each pane shows a picture of an NES pad with clickable hotspots over each of the eight
 * buttons. Click a hotspot, then press a keyboard key **or** a gamepad button — whichever
 * fires first becomes that NES button's binding. A device-type dropdown at the top of
 * each pane selects what's plugged into that player's controller port (None / Standard
 * Gamepad / Zapper — Zapper is currently a placeholder for a future PR).
 *
 * The bottom button bar (shared across both tabs):
 *   - **Save** writes `input.json`, applies the new mapping live, and closes the window.
 *   - **Reset to Default** restores the shipped defaults on-screen only.
 *   - **Cancel** closes the window without writing.
 *
 * Per-player state is held by two [ControllerBindings] instances — one for P1, one for P2.
 * Each tab listens independently; the gamepad capture listener is multiplexed across both
 * tabs by routing captured bindings to whichever tab's "listening" state machine is armed.
 *
 * This class is a thin JavaFX shell — all the editing/listening/steal logic lives in
 * the pure, unit-tested [ControllerBindings].
 */
class ControllerConfigWindow(
    private val loadConfig: () -> InputConfig,
    private val applyAndSave: (InputConfig) -> Unit,
    private val gamepad: GamepadInput?,
) {
    val stage = Stage()

    /** One clickable region: which NES button, where on the image (centre + size as 0..1 fractions), and its glyph. */
    private data class Spot(
        val button: Button,
        val cx: Double, val cy: Double, val w: Double, val h: Double,
        val glyph: String,
    )

    // Image-derived layout (shared across both tabs).
    private val controllerImage: Image? = loadControllerImage()
    /** The longer edge of the pane in screen pixels; the shorter edge is derived from the image aspect. */
    private val displayLongEdge = 500.0
    private val aspect: Double = (controllerImage?.let { it.width.toDouble() / it.height } ?: 1.0)
        .takeIf { it.isFinite() && it > 0 } ?: 1.0
    private val displayWidth: Double = displayLongEdge * minOf(1.0, aspect)
    private val displayHeight: Double = displayLongEdge / maxOf(1.0, aspect)

    // Fractions measured against the loaded image (shared across both tabs).
    private val spots = listOf(
        Spot(Button.UP,     0.180, 0.455, 0.090, 0.140, "↑"),
        Spot(Button.DOWN,   0.180, 0.745, 0.090, 0.140, "↓"),
        Spot(Button.LEFT,   0.120, 0.595, 0.090, 0.140, "←"),
        Spot(Button.RIGHT,  0.240, 0.595, 0.090, 0.140, "→"),
        Spot(Button.SELECT, 0.410, 0.710, 0.100, 0.070, "SEL"),
        Spot(Button.START,  0.540, 0.710, 0.100, 0.070, "STA"),
        Spot(Button.B,      0.713, 0.710, 0.100, 0.210, "B"),
        Spot(Button.A,      0.843, 0.710, 0.100, 0.210, "A"),
    )

    private val legendOrder = listOf(
        Button.A, Button.B, Button.SELECT, Button.START,
        Button.UP, Button.DOWN, Button.LEFT, Button.RIGHT,
    )

    private val idleHotspotStyle =
        "-fx-background-color: rgba(74,144,217,0.18); -fx-border-color: #4a90d9; " +
            "-fx-border-width: 1.5; -fx-background-radius: 6; -fx-border-radius: 6;"
    private val listeningHotspotStyle =
        "-fx-background-color: rgba(255,200,0,0.5); -fx-border-color: #d4a000; " +
            "-fx-border-width: 2; -fx-background-radius: 6; -fx-border-radius: 6;"

    /** Working state: a ControllerBindings per player. The tab pane swaps which one is active. */
    private var p1Bindings = ControllerBindings.fromInputConfig(loadConfig(), Player.ONE)
    private var p2Bindings = ControllerBindings.fromInputConfig(loadConfig(), Player.TWO)
    /** Working state: per-port device type. */
    private var ports = loadConfig().ports

    /** Tab content (one PlayerConfigPane per player). */
    private val player1Pane: PlayerConfigPane
    private val player2Pane: PlayerConfigPane

    init {
        stage.title = "Configure Controls"
        player1Pane = PlayerConfigPane(Player.ONE, p1Bindings, ports.port1)
        player2Pane = PlayerConfigPane(Player.TWO, p2Bindings, ports.port2)

        val tabs = TabPane().apply {
            tabs.add(Tab("Player 1", player1Pane.root).apply { isClosable = false })
            tabs.add(Tab("Player 2", player2Pane.root).apply { isClosable = false })
        }
        val root = VBox(tabs, buildButtonBar())
        root.padding = Insets(6.0)
        val scene = Scene(root)
        // Capture-phase filter: a key pressed while listening must be intercepted before a
        // focused Save/Reset button can consume Space/Enter as its own activation.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, ::onKeyPressed)
        stage.scene = scene
        stage.isResizable = false
        stage.onHidden = EventHandler {
            // Always hand the pad back to the game and drop any half-finished listen.
            player1Pane.cancel()
            player2Pane.cancel()
            stopGamepadCapture()
        }
        refresh()
    }

    /** Show the window (or bring it to front), reloading the working state from the live config. */
    fun show() {
        if (stage.isShowing) {
            stage.toFront()
            return
        }
        val cfg = loadConfig()
        p1Bindings = ControllerBindings.fromInputConfig(cfg, Player.ONE)
        p2Bindings = ControllerBindings.fromInputConfig(cfg, Player.TWO)
        ports = cfg.ports
        player1Pane.reload(p1Bindings, ports.port1)
        player2Pane.reload(p2Bindings, ports.port2)
        refresh()
        stage.show()
    }

    // ---- UI construction ----------------------------------------------------

    private fun buildButtonBar(): HBox {
        val save = FxButton("Save").apply { setOnAction { onSave() } }
        val reset = FxButton("Reset to Default").apply { setOnAction { onReset() } }
        val cancel = FxButton("Cancel").apply { setOnAction { stage.hide() } }
        return HBox(8.0, save, reset, cancel).apply {
            alignment = Pos.CENTER_RIGHT
            padding = Insets(6.0)
        }
    }

    // ---- Listening / capture ------------------------------------------------

    /**
     * One player's tab content: a device-type dropdown, the NES-pad image with hotspots,
     * and the legend showing the current key + gamepad binding per NES button. Holds a
     * mutable reference to its [ControllerBindings] working state — callers (the parent
     * window) swap the instance via [reload] on `show()`.
     */
    inner class PlayerConfigPane(
        val player: Player,
        initialBindings: ControllerBindings,
        initialDeviceType: InputDevice.DeviceType,
    ) {
        val root: Pane
        private var bindings = initialBindings
        private val deviceTypeChoice: ChoiceBox<InputDevice.DeviceType>

        private val hotspotNodes = HashMap<Button, StackPane>()
        private val legendLabels = HashMap<Button, Label>()

        init {
            // Device-type dropdown: NONE / STANDARD_GAMEPAD (ZAPPER reserved in the
            // enum but not yet listed — its working semantics land in a future PR).
            val supportedTypes = InputConfig.SUPPORTED_DEVICE_TYPES
            deviceTypeChoice = ChoiceBox<InputDevice.DeviceType>().apply {
                items = javafx.collections.FXCollections.observableArrayList(supportedTypes)
                value = initialDeviceType
                selectionModel.selectedItemProperty().addListener { _, _, newType ->
                    if (newType != null) ports = when (player) {
                        Player.ONE -> PortAssignment(port1 = newType, port2 = ports.port2)
                        Player.TWO -> PortAssignment(port1 = ports.port1, port2 = newType)
                    }
                }
            }

            root = VBox(
                buildDeviceTypeRow(),
                buildControllerPane(),
                buildLegend(),
            ).apply { padding = Insets(6.0) }
            refresh()
        }

        private fun buildDeviceTypeRow(): HBox {
            val label = Label("Device plugged into port ${player.ordinal + 1}:").apply {
                font = Font.font(12.0)
            }
            return HBox(8.0, label, deviceTypeChoice).apply {
                alignment = Pos.CENTER_LEFT
                padding = Insets(2.0, 6.0, 6.0, 6.0)
            }
        }

        private fun buildControllerPane(): Pane {
            val pane = Pane().apply {
                prefWidth = displayWidth; prefHeight = displayHeight
                minWidth = displayWidth; minHeight = displayHeight
            }
            if (controllerImage != null) {
                pane.children.add(ImageView(controllerImage).apply {
                    fitWidth = displayWidth
                    fitHeight = displayHeight
                    isPreserveRatio = false
                })
            } else {
                pane.style = "-fx-background-color: #c9c9c9;"
            }
            spots.forEach { spot ->
                val node = createHotspot(spot)
                hotspotNodes[spot.button] = node
                pane.children.add(node)
            }
            return pane
        }

        private fun createHotspot(spot: Spot): StackPane {
            val w = spot.w * displayWidth
            val h = spot.h * displayHeight
            val glyph = Label(spot.glyph).apply {
                font = Font.font("System", 10.0)
                style = "-fx-font-weight: bold; -fx-text-fill: white;"
            }
            return StackPane(glyph).apply {
                prefWidth = w; prefHeight = h
                minWidth = w; minHeight = h
                maxWidth = w; maxHeight = h
                layoutX = spot.cx * displayWidth - w / 2
                layoutY = spot.cy * displayHeight - h / 2
                style = idleHotspotStyle
                onMouseClicked = EventHandler { startListening(spot.button) }
            }
        }

        private fun buildLegend(): Pane {
            val grid = GridPane().apply {
                hgap = 22.0; vgap = 2.0
                padding = Insets(6.0, 8.0, 4.0, 8.0)
            }
            legendOrder.forEachIndexed { i, button ->
                val label = Label().apply { font = Font.font("Monospaced", 12.0) }
                legendLabels[button] = label
                grid.add(label, i / 4, i % 4)
            }
            return grid
        }

        fun cancel() = bindings.cancel()

        /** Currently-listening button, or null when idle. */
        val listeningFor: Button? get() = bindings.listeningFor

        /** The pane's working bindings — read by the outer window on Save. */
        fun currentBindings(): ControllerBindings = bindings

        fun reload(newBindings: ControllerBindings, newDeviceType: InputDevice.DeviceType) {
            bindings = newBindings
            deviceTypeChoice.value = newDeviceType
            refresh()
        }

        private fun startListening(button: Button) {
            bindings.startListening(button)
            gamepad?.captureListenerWithPlayer = { capturedPlayer, binding ->
                if (capturedPlayer == player) {
                    if (bindings.capture(binding) != null) {
                        stopGamepadCapture()
                        refresh()
                    }
                }
            }
            refresh()
        }

        fun onKeyPressed(event: KeyEvent) {
            if (bindings.listeningFor == null) return
            event.consume()
            if (event.code == KeyCode.ESCAPE) {
                bindings.cancel()
                stopGamepadCapture()
                refresh()
                return
            }
            if (event.code.isModifierKey) return
            if (bindings.captureKey(event.code.name) != null) {
                stopGamepadCapture()
                refresh()
            }
        }

        fun refresh() {
            legendOrder.forEach { legendLabels[it]?.text = legendText(it) }
            val listening = bindings.listeningFor
            hotspotNodes.forEach { (button, node) ->
                node.style = if (button == listening) listeningHotspotStyle else idleHotspotStyle
            }
        }

        private fun legendText(button: Button): String = "${displayName(button).padEnd(6)} → ${bindingText(button)}"

        private fun bindingText(button: Button): String {
            val parts = mutableListOf<String>()
            bindings.keyFor(button)?.let { parts.add(it) }
            bindings.padFor(button)?.let { parts.add(it.displayName) }
            return if (parts.isEmpty()) "—" else parts.joinToString(" / ")
        }
    }

    private fun stopGamepadCapture() {
        gamepad?.captureListenerWithPlayer = null
    }

    private fun onKeyPressed(event: KeyEvent) {
        // Route the key to whichever tab is currently listening.
        if (player1Pane.listeningFor != null) {
            player1Pane.onKeyPressed(event)
            return
        }
        if (player2Pane.listeningFor != null) {
            player2Pane.onKeyPressed(event)
            return
        }
    }

    // ---- Save / reset -------------------------------------------------------

    private fun onSave() {
        // Cancel any in-progress listen before we serialise.
        player1Pane.cancel()
        player2Pane.cancel()
        stopGamepadCapture()

        val base = loadConfig()
        // Merge each tab's bindings into the right slot of the new keyboard map,
        // preserving the OTHER slot from the live config so a Save on P1 doesn't
        // wipe P2's settings (or vice versa).
        val newKeyboard = PlayerKeyBindings(
            player1 = player1Pane.currentBindings().toInputConfig(base, Player.ONE).keyboard.player1,
            player2 = player2Pane.currentBindings().toInputConfig(base, Player.TWO).keyboard.player2,
        )
        // Merge both panes' gamepad bindings into a single GamepadConfig.bindings map.
        // Each pane's toInputConfig produces the full bindings map for ITS pane; we
        // OR them together (later panes win on conflicts). Without this, gamepad
        // rebinds the user made in either tab would silently revert to the base
        // config on Save (issue: 2-player save bug found by code review).
        val p1Bindings = player1Pane.currentBindings().gamepadBindings
        val p2Bindings = player2Pane.currentBindings().gamepadBindings
        val mergedGamepad = base.gamepad.copy(
            bindings = if (p1Bindings.isEmpty() && p2Bindings.isEmpty()) {
                base.gamepad.bindings
            } else {
                val merged = mutableMapOf<String, String>()
                // Start from P1's bindings; let P2 override on conflicts.
                p1Bindings.forEach { (button, binding) -> merged[binding.storageKey] = button.name }
                p2Bindings.forEach { (button, binding) -> merged[binding.storageKey] = button.name }
                merged.toMap()
            },
        )
        val merged = base.copy(
            keyboard = newKeyboard,
            gamepad = mergedGamepad,
            ports = ports,
        )
        applyAndSave(merged)
        stage.hide()
    }

    private fun onReset() {
        // Reset both panes' bindings to defaults; preserve the device-type selections
        // (a user who set port 2 to Zapper probably wants to keep that choice).
        player1Pane.reload(ControllerBindings.defaults(), ports.port1)
        player2Pane.reload(ControllerBindings.defaults(), ports.port2)
    }

    private fun refresh() {
        player1Pane.refresh()
        player2Pane.refresh()
    }

    private fun displayName(button: Button): String = when (button) {
        Button.A -> "A"
        Button.B -> "B"
        Button.SELECT -> "Select"
        Button.START -> "Start"
        Button.UP -> "Up"
        Button.DOWN -> "Down"
        Button.LEFT -> "Left"
        Button.RIGHT -> "Right"
    }

    private fun loadControllerImage(): Image? =
        ControllerConfigWindow::class.java.getResourceAsStream("/images/nes-controller.jpg")
            ?.use { Image(it) }
}
