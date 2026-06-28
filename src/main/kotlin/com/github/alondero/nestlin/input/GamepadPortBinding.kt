package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller as NesController
import net.java.games.input.Component
import net.java.games.input.Controller

/**
 * One JInput gamepad's wiring into a single NES controller port (issue: 2-player support).
 *
 * [GamepadPortBinding] is a per-port adapter that polls a single physical [Controller]
 * (the JInput `Controller`, not the NES one) and routes its button / axis / POV
 * events to a target [NesController] (the NES controller port). Multiple instances
 * live inside [MultiGamepadInput], one per [Player] slot — auto-assigned in
 * enumeration order (first connected GAMEPAD/STICK → P1, second → P2).
 *
 * The previous-state maps ([previousButtonStates], [previousAxisStates],
 * [previousPovStates]) are per-port so that pressing button 0 on the P1 gamepad
 * does not collide with pressing button 0 on the P2 gamepad — every poll sees
 * only its own gamepad's prior state.
 *
 * The pure classifier ([GamepadEventClassifier]) does not change; this class
 * delegates axis/POV classification to it.
 */
internal class GamepadPortBinding(
    val player: Player,
    val nesController: NesController,
    private var config: GamepadConfig,
) {
    /** The JInput controller currently bound to this port. Null when nothing is plugged in. */
    var jinputController: Controller? = null
        private set

    /**
     * When non-null, the next gamepad input that goes *active* is reported here as a
     * [GamepadBinding] instead of being routed to the NES pad. The Controller
     * Configuration screen sets this to capture a binding for [player].
     */
    var captureListener: ((GamepadBinding) -> Unit)? = null

    private val previousButtonStates = mutableMapOf<String, Boolean>()
    private val previousAxisStates = mutableMapOf<String, AxisDirection?>()
    private val previousPovStates = mutableMapOf<String, Set<PovDirection>>()

    fun attachController(controller: Controller) {
        jinputController = controller
        previousButtonStates.clear()
        previousAxisStates.clear()
        previousPovStates.clear()
    }

    fun detachController() {
        jinputController = null
        previousButtonStates.clear()
        previousAxisStates.clear()
        previousPovStates.clear()
        // Release all buttons on the NES side so a disconnect doesn't leave a
        // stuck-button state.
        NesController.Button.entries.forEach { nesController.setButton(it, false) }
    }

    fun updateConfig(newConfig: GamepadConfig) {
        config = newConfig
    }

    /**
     * Poll this port's gamepad. Returns true if the gamepad is still connected;
     * false if it was disconnected this frame (the caller should drop the binding).
     */
    fun poll(): Boolean {
        val gamepad = jinputController ?: return true
        try {
            if (!gamepad.poll()) {
                println("[GAMEPAD] Controller disconnected (${gamepad.name}, player=$player)")
                detachController()
                return false
            }
            for (component in gamepad.components) {
                val value = component.pollData
                val id = component.identifier.name.lowercase()
                when {
                    id.contains("pov") || id.contains("hat") -> processPov(component, value)
                    component.isAnalog -> processAxis(component, value)
                    else -> processButton(component, value)
                }
            }
        } catch (e: Exception) {
            // Ignore polling errors
        }
        return true
    }

    private fun processButton(component: Component, value: Float) {
        val pressed = value > 0.5f
        val componentId = component.identifier.name
        val wasPressed = previousButtonStates[componentId] ?: false

        if (pressed != wasPressed) {
            previousButtonStates[componentId] = pressed

            // Capture mode: report the first button to go down as a raw index,
            // do NOT route to the game pad.
            val listener = captureListener
            if (listener != null) {
                if (pressed) componentToIndex(componentId)?.let { listener(GamepadBinding.ButtonIndex(it)) }
                return
            }

            val nesButton = mapButtonToNes(componentId)
            if (nesButton != null) {
                nesController.setButton(nesButton, pressed)
            }
        }
    }

    private fun processAxis(component: Component, value: Float) {
        val componentId = component.identifier.name
        val deadzone = config.axisDeadzone

        val isX = componentId.contains('x') || componentId.contains('X')
        val isY = componentId.contains('y') || componentId.contains('Y')
        if (!isX && !isY) return

        val previous = previousAxisStates[componentId]
        val current = GamepadEventClassifier.axisDirection(value, deadzone)

        // Capture mode
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
        if (current == previous) return

        if (current != null) {
            config.getButtonForBinding(GamepadBinding.Axis(componentId, current))?.let { bound ->
                previous?.let { prevDir ->
                    config.getButtonForBinding(GamepadBinding.Axis(componentId, prevDir))
                        ?.let { nesController.setButton(it, false) }
                }
                nesController.setButton(bound, true)
                return
            }
        } else {
            previous?.let { prevDir ->
                config.getButtonForBinding(GamepadBinding.Axis(componentId, prevDir))
                    ?.let { nesController.setButton(it, false) }
            }
        }

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
        processPovByValue(componentId, value)
    }

    private fun processPovByValue(componentId: String, value: Float) {
        val current = GamepadEventClassifier.povDirection(value)
        val previous = previousPovStates[componentId] ?: emptySet()

        if (captureListener != null) {
            GamepadEventClassifier.povTransition(componentId, value, previous)
                ?.let { captureListener?.invoke(it) }
            previousPovStates[componentId] = setOf(current)
            return
        }

        previousPovStates[componentId] = setOf(current)
        if (setOf(current) == previous) return

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

    private fun nesFor(componentId: String, dir: PovDirection, defaultNes: NesController.Button): NesController.Button =
        config.getButtonForBinding(GamepadBinding.Pov(componentId, dir)) ?: defaultNes

    internal fun isCardinalOrDiagonalOf(current: PovDirection, nesButton: NesController.Button): Boolean = when (nesButton) {
        NesController.Button.UP -> current in setOf(PovDirection.N, PovDirection.NW, PovDirection.NE)
        NesController.Button.DOWN -> current in setOf(PovDirection.S, PovDirection.SW, PovDirection.SE)
        NesController.Button.LEFT -> current in setOf(PovDirection.W, PovDirection.NW, PovDirection.SW)
        NesController.Button.RIGHT -> current in setOf(PovDirection.E, PovDirection.NE, PovDirection.SE)
        else -> false
    }

    internal fun mapButtonToNes(buttonId: String): NesController.Button? {
        componentToIndex(buttonId)?.let { index ->
            config.getButtonForBinding(GamepadBinding.ButtonIndex(index))?.let { return it }
        }
        return when (buttonId.lowercase()) {
            "0", "button 0", "a" -> NesController.Button.A
            "1", "button 1", "b" -> NesController.Button.B
            "6", "button 6", "back", "select" -> NesController.Button.SELECT
            "7", "button 7", "start" -> NesController.Button.START
            "2", "button 2", "x" -> null
            "3", "button 3", "y" -> null
            "up", "dpad up" -> NesController.Button.UP
            "down", "dpad down" -> NesController.Button.DOWN
            "left", "dpad left" -> NesController.Button.LEFT
            "right", "dpad right" -> NesController.Button.RIGHT
            else -> null
        }
    }

    internal fun componentToIndex(componentId: String): Int? =
        componentId.filter { it.isDigit() }.toIntOrNull()

    private fun updateAxisButton(axisName: String, pressed: Boolean, nesButton: NesController.Button) {
        val key = "${axisName}_${nesButton.name}"
        val wasPressed = previousButtonStates[key] ?: false
        if (pressed != wasPressed) {
            previousButtonStates[key] = pressed
            nesController.setButton(nesButton, pressed)
        }
    }
}
