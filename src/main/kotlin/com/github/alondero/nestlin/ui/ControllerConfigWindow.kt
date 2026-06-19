package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.Controller.Button
import com.github.alondero.nestlin.input.ControllerBindings
import com.github.alondero.nestlin.input.GamepadInput
import com.github.alondero.nestlin.input.InputConfig
import javafx.event.EventHandler
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Label
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
 * The Controller Configuration screen (Settings → Configure Controls…). Shows a picture of an
 * NES pad with a clickable hotspot over each of the eight buttons. Click a hotspot, then press a
 * keyboard key **or** a gamepad button — whichever fires first becomes that NES button's binding.
 * **Save** writes `input.json` and applies the new mapping live; **Reset to Default** restores the
 * shipped defaults (committed on the next Save). Player 1 only.
 *
 * This class is a thin JavaFX shell — all the editing/listening/steal logic lives in the pure,
 * unit-tested [ControllerBindings] (mirroring how [MemoryEditorWindow] sits over [HexEditState]).
 *
 * @param loadConfig supplies the current live [InputConfig] (its gamepad axis/deadzone fields are
 *   preserved across a Save), called fresh each time the window opens.
 * @param applyAndSave persists + applies a new config (write file, swap the live config, push the
 *   new gamepad mapping). Runs on the JavaFX thread.
 * @param gamepad the live [GamepadInput], used to capture the "next gamepad button" while listening.
 *   Null only if gamepad support failed to initialise — keyboard capture still works.
 */
class ControllerConfigWindow(
    private val loadConfig: () -> InputConfig,
    private val applyAndSave: (InputConfig) -> Unit,
    private val gamepad: GamepadInput?,
) {
    val stage = Stage()

    // The on-screen size of the (square) controller image. Hotspots are positioned as
    // fractions of this so the layout stays correct if the constant changes.
    private val displaySize = 500.0

    /** One clickable region: which NES button, where on the image (centre + size as 0..1 fractions), and its glyph. */
    private data class Spot(
        val button: Button,
        val cx: Double, val cy: Double, val w: Double, val h: Double,
        val glyph: String,
    )

    // Fractions measured against the generated nes-controller.jpg (square). The D-pad is on the
    // left, the two grey Select/Start ovals in the middle, the red B (left) and A (right) on the right.
    private val spots = listOf(
        Spot(Button.UP,     0.270, 0.489, 0.060, 0.075, "↑"),
        Spot(Button.DOWN,   0.270, 0.604, 0.060, 0.075, "↓"),
        Spot(Button.LEFT,   0.216, 0.546, 0.072, 0.060, "←"),
        Spot(Button.RIGHT,  0.324, 0.546, 0.072, 0.060, "→"),
        Spot(Button.SELECT, 0.448, 0.573, 0.075, 0.052, "SEL"),
        Spot(Button.START,  0.528, 0.573, 0.075, 0.052, "STA"),
        Spot(Button.B,      0.675, 0.564, 0.085, 0.085, "B"),
        Spot(Button.A,      0.770, 0.564, 0.085, 0.085, "A"),
    )

    // Legend order reads like a controller spec, independent of the picture layout.
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

    private var bindings = ControllerBindings.fromInputConfig(loadConfig())
    private val controllerImage: Image? = loadControllerImage()

    private val promptLabel = Label().apply {
        font = Font.font(13.0)
        isWrapText = true
        padding = Insets(2.0, 6.0, 6.0, 6.0)
    }

    private val hotspotNodes = HashMap<Button, StackPane>()
    private val legendLabels = HashMap<Button, Label>()

    init {
        stage.title = "Configure Controls"
        val root = VBox(promptLabel, buildControllerPane(), buildLegend(), buildButtonBar())
        root.padding = Insets(6.0)
        val scene = Scene(root)
        // Capture-phase filter: a key pressed while listening must be intercepted before a
        // focused Save/Reset button can consume Space/Enter as its own activation.
        scene.addEventFilter(KeyEvent.KEY_PRESSED, ::onKeyPressed)
        stage.scene = scene
        stage.isResizable = false
        stage.onHidden = EventHandler {
            // Always hand the pad back to the game and drop any half-finished listen.
            bindings.cancel()
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
        bindings = ControllerBindings.fromInputConfig(loadConfig())
        refresh()
        stage.show()
    }

    // ---- UI construction ----------------------------------------------------

    private fun buildControllerPane(): Pane {
        val pane = Pane().apply {
            prefWidth = displaySize; prefHeight = displaySize
            minWidth = displaySize; minHeight = displaySize
        }
        if (controllerImage != null) {
            pane.children.add(ImageView(controllerImage).apply {
                fitWidth = displaySize
                fitHeight = displaySize
                isPreserveRatio = true
            })
        } else {
            // Asset missing: still show the hotspots over a neutral background.
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
        val w = spot.w * displaySize
        val h = spot.h * displaySize
        val glyph = Label(spot.glyph).apply {
            font = Font.font("System", 10.0)
            style = "-fx-font-weight: bold; -fx-text-fill: white;"
        }
        return StackPane(glyph).apply {
            prefWidth = w; prefHeight = h
            minWidth = w; minHeight = h
            maxWidth = w; maxHeight = h
            layoutX = spot.cx * displaySize - w / 2
            layoutY = spot.cy * displaySize - h / 2
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
            grid.add(label, i / 4, i % 4) // two columns of four
        }
        return grid
    }

    private fun buildButtonBar(): HBox {
        val save = FxButton("Save").apply { setOnAction { onSave() } }
        val reset = FxButton("Reset to Default").apply { setOnAction { onReset() } }
        val close = FxButton("Close").apply { setOnAction { stage.hide() } }
        return HBox(8.0, save, reset, close).apply {
            alignment = Pos.CENTER_RIGHT
            padding = Insets(6.0)
        }
    }

    // ---- Listening / capture ------------------------------------------------

    private fun startListening(button: Button) {
        bindings.startListening(button)
        // gamepad.poll() runs inside the JavaFX AnimationTimer, so this listener fires on the
        // FX thread — safe to touch nodes directly, no Platform.runLater needed.
        gamepad?.captureListener = { index -> onCaptured { bindings.capturePad(index) } }
        refresh()
    }

    private fun onKeyPressed(event: KeyEvent) {
        if (bindings.listeningFor == null) return // not capturing: let keys reach buttons normally
        event.consume()
        if (event.code == KeyCode.ESCAPE) {
            bindings.cancel()
            stopGamepadCapture()
            refresh()
            return
        }
        // A bare modifier (Shift/Ctrl/Alt/Meta) fires its own KEY_PRESSED before the real
        // key — binding it would shadow the key the user is actually reaching for and make
        // the control unusable. Ignore it and keep listening for a non-modifier key.
        if (event.code.isModifierKey) return
        onCaptured { bindings.captureKey(event.code.name) }
    }

    /** Apply a capture (key or pad), and if it bound something, stop listening and repaint. */
    private fun onCaptured(bind: () -> Button?) {
        if (bind() == null) return
        stopGamepadCapture()
        refresh()
    }

    private fun stopGamepadCapture() {
        gamepad?.captureListener = null
    }

    // ---- Save / reset -------------------------------------------------------

    private fun onSave() {
        bindings.cancel()
        stopGamepadCapture()
        applyAndSave(bindings.toInputConfig(loadConfig()))
        refresh()
        promptLabel.text = "Saved to input.json."
    }

    private fun onReset() {
        bindings.resetToDefaults()
        stopGamepadCapture()
        refresh()
        promptLabel.text = "Reset to defaults — press Save to keep."
    }

    // ---- Rendering ----------------------------------------------------------

    private fun refresh() {
        legendOrder.forEach { legendLabels[it]?.text = legendText(it) }
        val listening = bindings.listeningFor
        hotspotNodes.forEach { (button, node) ->
            node.style = if (button == listening) listeningHotspotStyle else idleHotspotStyle
        }
        promptLabel.text = if (listening != null) {
            "Press a key or gamepad button for ${displayName(listening)}…  (Esc to cancel)"
        } else {
            "Click a button on the controller, then press a key or gamepad button to bind it."
        }
    }

    private fun legendText(button: Button): String = "${displayName(button).padEnd(6)} → ${bindingText(button)}"

    private fun bindingText(button: Button): String {
        val parts = mutableListOf<String>()
        bindings.keyFor(button)?.let { parts.add(it) }
        bindings.padFor(button)?.let { parts.add("pad $it") }
        return if (parts.isEmpty()) "—" else parts.joinToString(" / ")
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
