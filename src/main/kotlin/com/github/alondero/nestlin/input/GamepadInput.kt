package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller as NesController
import net.java.games.input.Controller
import net.java.games.input.ControllerEnvironment
import net.java.games.input.Component

/**
 * Handles gamepad input using JInput library.
 * Polls connected controllers and maps button presses to NES controller state.
 *
 * GH #182: the Controller Configuration screen needs to capture analog-stick axes and
 * POV/hat inputs as well as digital buttons. The capture listener callback now receives
 * a [GamepadBinding] (button index, axis direction, or POV direction) instead of just
 * an Int, and the matching logic in [GamepadEventClassifier] is pure and unit-tested.
 */
class GamepadInput(
    private val nesController: NesController,
    private var config: GamepadConfig = GamepadConfig()
) {
    private var currentController: Controller? = null
    private var initialized = false

    /**
     * When non-null, the next gamepad input that goes *active* is reported here as a
     * [GamepadBinding] instead of being routed to the NES pad. The Controller
     * Configuration screen sets this to capture a binding, and clears it (sets null)
     * when it closes, so pressing a button to bind a control doesn't also move the
     * in-game character. Fires for button presses, axis-direction engagements, and
     * POV-hat direction engagements (the three classes of JInput Component events).
     */
    var captureListener: ((GamepadBinding) -> Unit)? = null

    /**
     * Swap in a new gamepad mapping at runtime (after the user saves new controls), without
     * tearing down and re-initialising JInput. [poll] reads [config] fresh each frame.
     */
    fun updateConfig(newConfig: GamepadConfig) {
        config = newConfig
    }

    // Track previous states to detect changes. The button set is keyed by JInput
    // component identifier so the same component can fire repeatedly without confusion.
    // The axis and POV sets are per-direction (not per-component) because a single axis
    // component has two independent "active" states (POSITIVE / NEGATIVE) that the
    // capture listener must distinguish.
    private val previousButtonStates = mutableMapOf<String, Boolean>()
    private val previousAxisStates = mutableMapOf<String, AxisDirection?>()
    private val previousPovStates = mutableMapOf<String, Set<PovDirection>>()

    /**
     * Initialize controller support.
     * Call this once at application startup.
     */
    fun initialize() {
        try {
            JInputNatives.prepare()
            initialized = true
            println("[GAMEPAD] Controller manager initialized")
            refreshControllers()
        } catch (e: Exception) {
            println("[GAMEPAD] Failed to initialize: ${e.message}")
            initialized = false
        }
    }

    /**
     * Refresh the list of connected controllers.
     */
    fun refreshControllers() {
        if (!initialized) return

        try {
            val controllers = ControllerEnvironment.getDefaultEnvironment().controllers

            // Find first gamepad or stick controller
            val gamepad = controllers.firstOrNull { controller ->
                controller.type == Controller.Type.GAMEPAD ||
                controller.type == Controller.Type.STICK
            }

            if (gamepad != null && gamepad != currentController) {
                currentController = gamepad
                previousButtonStates.clear()
                previousAxisStates.clear()
                previousPovStates.clear()
                println("[GAMEPAD] Controller connected: ${gamepad.name}")
            } else if (gamepad == null && currentController != null) {
                println("[GAMEPAD] Controller disconnected")
                currentController = null
                previousButtonStates.clear()
                previousAxisStates.clear()
                previousPovStates.clear()
            }
        } catch (e: Exception) {
            println("[GAMEPAD] Error refreshing controllers: ${e.message}")
        }
    }

    /**
     * Poll the current controller state and update NES controller.
     * Call this every frame (e.g., from AnimationTimer).
     */
    fun poll() {
        if (!initialized) return

        val gamepad = currentController
        if (gamepad == null) {
            // Periodically check for new controllers (every ~60 frames)
            return
        }

        try {
            // Poll the controller for new data
            if (!gamepad.poll()) {
                // Controller disconnected
                println("[GAMEPAD] Controller disconnected: ${gamepad.name}")
                currentController = null
                // Release all buttons
                NesController.Button.entries.forEach { button ->
                    nesController.setButton(button, false)
                }
                previousButtonStates.clear()
                previousAxisStates.clear()
                previousPovStates.clear()
                return
            }

            // Process all components
            for (component in gamepad.components) {
                val value = component.pollData
                val id = component.identifier.name.lowercase()

                when {
                    // POV/hat components report their direction as a float; dispatch by
                    // identifier prefix so Xbox-style and DirectInput pads both work.
                    id.contains("pov") || id.contains("hat") -> processPov(component, value)
                    component.isAnalog -> processAxis(component, value)
                    else -> processButton(component, value)
                }
            }
        } catch (e: Exception) {
            // Ignore polling errors
        }
    }

    private fun processButton(component: Component, value: Float) {
        val pressed = value > 0.5f
        val componentId = component.identifier.name
        val wasPressed = previousButtonStates[componentId] ?: false

        if (pressed != wasPressed) {
            previousButtonStates[componentId] = pressed

            // Binding-capture mode (Controller Config screen): report the first button to
            // go down as a raw index and do NOT route it to the game pad.
            val listener = captureListener
            if (listener != null) {
                if (pressed) componentToIndex(componentId)?.let { listener(GamepadBinding.ButtonIndex(it)) }
                return
            }

            // Map common button identifiers to NES buttons
            val nesButton = mapButtonToNes(componentId)
            if (nesButton != null) {
                nesController.setButton(nesButton, pressed)
            }
        }
    }

    private fun processAxis(component: Component, value: Float) {
        val componentId = component.identifier.name
        val deadzone = config.axisDeadzone

        // Per-direction routing (and capture) for analog stick axes. JInput's component
        // identifier ("x" / "y" / "rx" / ...) tells us which physical axis we're on;
        // the sign of the value (vs [deadzone]) tells us which of two NES directions
        // gets pressed.
        val isX = componentId.contains('x') || componentId.contains('X')
        val isY = componentId.contains('y') || componentId.contains('Y')
        if (!isX && !isY) return

        val previous = previousAxisStates[componentId]
        val current = GamepadEventClassifier.axisDirection(value, deadzone)

        // Capture-mode fires on inactive→active transitions for either direction, then
        // SWALLOWS the routing — pressing a stick to bind a control must not also move
        // the in-game character (matches the button path's behaviour).
        if (captureListener != null) {
            val transition = GamepadEventClassifier.axisTransition(
                componentId, value, deadzone,
                wasPos = previous == AxisDirection.POSITIVE,
                wasNeg = previous == AxisDirection.NEGATIVE,
            )
            transition?.let { captureListener?.invoke(it) }
            previousAxisStates[componentId] = current
            return
        }

        previousAxisStates[componentId] = current
        if (current == previous) return // no change → no button toggle

        // If the user has explicitly bound this axis direction, route to that NES button
        // (overrides the dpad-style default below). This is what makes "I bound y_neg to
        // UP" actually take effect at runtime. `current` is non-null here (the early-return
        // on `current == previous` excludes the null-from-active case but we still need a
        // null guard so a stick release-to-center can clear the previously-pressed NES
        // button without NPE).
        if (current != null) {
            config.getButtonForBinding(GamepadBinding.Axis(componentId, current))?.let { bound ->
                // Release the OLD direction's NES button (if any), then press the new one.
                previous?.let { prevDir ->
                    config.getButtonForBinding(GamepadBinding.Axis(componentId, prevDir))
                        ?.let { nesController.setButton(it, false) }
                }
                nesController.setButton(bound, true)
                return
            }
        } else {
            // Stick returned to center while a previous direction had a user binding —
            // release it (the explicit-binding branch only runs when current is non-null).
            previous?.let { prevDir ->
                config.getButtonForBinding(GamepadBinding.Axis(componentId, prevDir))
                    ?.let { nesController.setButton(it, false) }
            }
        }

        // No user binding for this axis — fall back to the dpad-style routing so a
        // controller the user has never configured still works (left stick = dpad).
        // `updateAxisButton` does its own per-key release tracking, so a stick release
        // cleanly drops the NES button without a separate release path.
        if (isX) {
            updateAxisButton("${componentId}_left", current == AxisDirection.NEGATIVE, NesController.Button.LEFT)
            updateAxisButton("${componentId}_right", current == AxisDirection.POSITIVE, NesController.Button.RIGHT)
        }
        if (isY) {
            updateAxisButton("${componentId}_up", current == AxisDirection.NEGATIVE, NesController.Button.UP)
            updateAxisButton("${componentId}_down", current == AxisDirection.POSITIVE, NesController.Button.DOWN)
        }
    }

    private fun processPov(component: Component, value: Float) {
        val componentId = component.identifier.name
        // Reuse the routing in processPovByValue: a hat/POV's identifier carries the
        // "pov"/"hat" prefix; we don't need any of the component's other fields.
        processPovByValue(componentId, value)
    }

    private fun processPovByValue(componentId: String, value: Float) {
        val current = GamepadEventClassifier.povDirection(value)
        val previous = previousPovStates[componentId] ?: emptySet()

        // Capture mode: fire transition (if any), then SWALLOW the routing — pressing a
        // hat direction to bind a control must not also move the in-game character.
        if (captureListener != null) {
            GamepadEventClassifier.povTransition(componentId, value, previous)
                ?.let { captureListener?.invoke(it) }
            previousPovStates[componentId] = setOf(current)
            return
        }

        previousPovStates[componentId] = setOf(current)
        if (setOf(current) == previous) return

        // Decide which NES buttons should be pressed this poll, then route each through
        // [updateAxisButton] (which has its own per-key release tracking). For each of the
        // four NES cardinals, the "should press" boolean is the disjunction of the
        // cardinal direction and each diagonal that contains it (NW → UP+LEFT, etc.) —
        // restoring the pre-refactor behaviour where a D-pad diagonal moved both NES
        // buttons. The explicit user binding (if any) takes precedence over the NES
        // default for that cardinal.
        val pressed: Map<NesController.Button, Boolean> = mapOf(
            nesFor(componentId, PovDirection.N, NesController.Button.UP) to isCardinalOrDiagonalOf(current, NesController.Button.UP),
            nesFor(componentId, PovDirection.S, NesController.Button.DOWN) to isCardinalOrDiagonalOf(current, NesController.Button.DOWN),
            nesFor(componentId, PovDirection.W, NesController.Button.LEFT) to isCardinalOrDiagonalOf(current, NesController.Button.LEFT),
            nesFor(componentId, PovDirection.E, NesController.Button.RIGHT) to isCardinalOrDiagonalOf(current, NesController.Button.RIGHT),
        )
        pressed.forEach { (nesButton, isActive) ->
            updateAxisButton("${componentId}_${nesButton.name}", isActive, nesButton)
        }
    }

    /** NES button the user wants for this cardinal POV direction, or the NES default. */
    private fun nesFor(componentId: String, dir: PovDirection, defaultNes: NesController.Button): NesController.Button =
        config.getButtonForBinding(GamepadBinding.Pov(componentId, dir)) ?: defaultNes

    /** True if [current] is the cardinal NES direction or a diagonal containing it. */
    internal fun isCardinalOrDiagonalOf(current: PovDirection, nesButton: NesController.Button): Boolean = when (nesButton) {
        NesController.Button.UP -> current in setOf(PovDirection.N, PovDirection.NW, PovDirection.NE)
        NesController.Button.DOWN -> current in setOf(PovDirection.S, PovDirection.SW, PovDirection.SE)
        NesController.Button.LEFT -> current in setOf(PovDirection.W, PovDirection.NW, PovDirection.SW)
        NesController.Button.RIGHT -> current in setOf(PovDirection.E, PovDirection.NE, PovDirection.SE)
        else -> false
    }

    /**
     * Map a JInput component identifier to an NES button. **Config-first**: a user remap
     * of any binding (button index, axis, or POV) wins over the built-in defaults.
     * `internal` so the precedence is unit-testable without booting JInput.
     */
    internal fun mapButtonToNes(buttonId: String): NesController.Button? {
        // Config-first: an explicit remap of this index takes precedence.
        componentToIndex(buttonId)?.let { index ->
            config.getButtonForBinding(GamepadBinding.ButtonIndex(index))?.let { return it }
        }

        // Fallback: conventional Xbox-style identifiers for an unconfigured button.
        return when (buttonId.lowercase()) {
            "0", "button 0", "a" -> NesController.Button.A
            "1", "button 1", "b" -> NesController.Button.B
            "6", "button 6", "back", "select" -> NesController.Button.SELECT
            "7", "button 7", "start" -> NesController.Button.START

            // X / Y buttons - not mapped on a 2-button NES pad
            "2", "button 2", "x" -> null
            "3", "button 3", "y" -> null

            // D-pad buttons (if not using axes/POV)
            "up", "dpad up" -> NesController.Button.UP
            "down", "dpad down" -> NesController.Button.DOWN
            "left", "dpad left" -> NesController.Button.LEFT
            "right", "dpad right" -> NesController.Button.RIGHT

            else -> null
        }
    }

    /**
     * Extract the numeric button index from a JInput component identifier
     * (e.g. `"Button 11"` -> 11), or null if it carries no digits (e.g. `"x"`).
     * `internal` for unit testing.
     */
    internal fun componentToIndex(componentId: String): Int? =
        componentId.filter { it.isDigit() }.toIntOrNull()

    private fun updateAxisButton(axisName: String, pressed: Boolean, nesButton: NesController.Button) {
        // Was a single-entry set per componentId-direction pair before; the new
        // processAxis tracks per-direction direction directly and uses this helper only
        // for the dpad-fallback paths. Key includes the NES button name so the same
        // componentId never collides on the two NES buttons it could drive.
        val key = "${axisName}_${nesButton.name}"
        val wasPressed = previousButtonStates[key] ?: false
        if (pressed != wasPressed) {
            previousButtonStates[key] = pressed
            nesController.setButton(nesButton, pressed)
        }
    }

    /**
     * Check if a controller is currently connected.
     */
    fun isControllerConnected(): Boolean {
        return currentController != null
    }

    /**
     * Get the name of the connected controller, or null if none.
     */
    fun getControllerName(): String? {
        return currentController?.name
    }

    /**
     * Shutdown and cleanup resources.
     */
    fun shutdown() {
        if (initialized) {
            currentController = null
            initialized = false
            println("[GAMEPAD] Shutdown complete")
        }
    }
}
