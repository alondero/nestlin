package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller as NesController
import net.java.games.input.Controller
import net.java.games.input.ControllerEnvironment
import net.java.games.input.Component

/**
 * Handles gamepad input using JInput library.
 * Polls connected controllers and maps button presses to NES controller state.
 */
class GamepadInput(
    private val nesController: NesController,
    private val config: GamepadConfig = GamepadConfig()
) {
    private var currentController: Controller? = null
    private var initialized = false

    // Track previous button states to detect changes
    private val previousButtonStates = mutableMapOf<String, Boolean>()
    private var previousAxisStates = mutableMapOf<String, Boolean>()

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
                println("[GAMEPAD] Controller connected: ${gamepad.name}")
            } else if (gamepad == null && currentController != null) {
                println("[GAMEPAD] Controller disconnected")
                currentController = null
                previousButtonStates.clear()
                previousAxisStates.clear()
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
                return
            }

            // Process all components
            for (component in gamepad.components) {
                val value = component.pollData

                when {
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

        // Handle X axis (left stick or D-pad)
        if (componentId == "x" || componentId == "X" || componentId.contains("X")) {
            val leftPressed = value < -deadzone
            val rightPressed = value > deadzone

            updateAxisButton("${componentId}_left", leftPressed, NesController.Button.LEFT)
            updateAxisButton("${componentId}_right", rightPressed, NesController.Button.RIGHT)
        }

        // Handle Y axis
        if (componentId == "y" || componentId == "Y" || componentId.contains("Y")) {
            val upPressed = value < -deadzone
            val downPressed = value > deadzone

            updateAxisButton("${componentId}_up", upPressed, NesController.Button.UP)
            updateAxisButton("${componentId}_down", downPressed, NesController.Button.DOWN)
        }

        // Handle POV/Hat switch (D-pad on many controllers)
        if (componentId.lowercase().contains("pov") || componentId.lowercase().contains("hat")) {
            processPov(componentId, value)
        }
    }

    @Suppress("UNUSED_PARAMETER")
    private fun processPov(componentId: String, value: Float) {
        // POV values: 0.0 = centered
        // Standard POV: 0.25=N, 0.375=NE, 0.5=E, 0.625=SE, 0.75=S, 0.875=SW, 1.0=W, 0.125=NW
        val centered = value == 0f || value == Component.POV.OFF

        updateAxisButton("pov_up", !centered && (value == 0.25f || value == 0.125f || value == 0.375f), NesController.Button.UP)
        updateAxisButton("pov_down", !centered && (value == 0.75f || value == 0.625f || value == 0.875f), NesController.Button.DOWN)
        updateAxisButton("pov_left", !centered && (value == 1.0f || value == 0.875f || value == 0.125f), NesController.Button.LEFT)
        updateAxisButton("pov_right", !centered && (value == 0.5f || value == 0.375f || value == 0.625f), NesController.Button.RIGHT)
    }

    private fun mapButtonToNes(buttonId: String): NesController.Button? {
        // Common button mappings for various controllers
        // Xbox-style: 0=A, 1=B, 2=X, 3=Y, 6=Back, 7=Start
        // Many controllers use similar numbering
        return when (buttonId.lowercase()) {
            // Standard numbered buttons
            "0", "button 0", "a" -> NesController.Button.A
            "1", "button 1", "b" -> NesController.Button.B
            "6", "button 6", "back", "select" -> NesController.Button.SELECT
            "7", "button 7", "start" -> NesController.Button.START

            // Some controllers use these names
            "2", "button 2", "x" -> null  // X button - not mapped
            "3", "button 3", "y" -> null  // Y button - not mapped

            // D-pad buttons (if not using axes/POV)
            "up", "dpad up" -> NesController.Button.UP
            "down", "dpad down" -> NesController.Button.DOWN
            "left", "dpad left" -> NesController.Button.LEFT
            "right", "dpad right" -> NesController.Button.RIGHT

            else -> {
                // Try to match button indices from config
                val buttonIndex = buttonId.filter { it.isDigit() }.toIntOrNull()
                if (buttonIndex != null) {
                    config.getButtonForIndex(buttonIndex)
                } else {
                    null
                }
            }
        }
    }

    private fun updateAxisButton(axisName: String, pressed: Boolean, nesButton: NesController.Button) {
        val wasPressed = previousAxisStates[axisName] ?: false
        if (pressed != wasPressed) {
            previousAxisStates[axisName] = pressed
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
