package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import javafx.scene.input.KeyCode
import java.io.File

/**
 * Configuration for input mappings (keyboard and gamepad).
 * Loaded from ~/.config/nestlin/input.json if it exists, otherwise uses defaults.
 */
data class InputConfig(
    val keyboard: Map<String, String> = defaultKeyboardMapping,
    val gamepad: GamepadConfig = GamepadConfig()
) {
    companion object {
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        private val configDir = File(System.getProperty("user.home"), ".config/nestlin")
        private val configFile = File(configDir, "input.json")

        // Default keyboard mapping: JavaFX KeyCode name -> NES button name
        val defaultKeyboardMapping = mapOf(
            "Z" to "A",
            "X" to "B",
            "SPACE" to "SELECT",
            "ENTER" to "START",
            "UP" to "UP",
            "DOWN" to "DOWN",
            "LEFT" to "LEFT",
            "RIGHT" to "RIGHT"
        )

        /**
         * Load configuration from file, or return defaults if file doesn't exist.
         */
        fun load(): InputConfig {
            return try {
                if (configFile.exists()) {
                    println("[INPUT] Loading config from ${configFile.absolutePath}")
                    gson.fromJson(configFile.readText(), InputConfig::class.java)
                } else {
                    println("[INPUT] No config file found, using defaults")
                    InputConfig()
                }
            } catch (e: Exception) {
                println("[INPUT] Error loading config: ${e.message}, using defaults")
                InputConfig()
            }
        }

        /**
         * Save configuration to file (creates directory if needed).
         */
        fun save(config: InputConfig) {
            try {
                configDir.mkdirs()
                configFile.writeText(gson.toJson(config))
                println("[INPUT] Saved config to ${configFile.absolutePath}")
            } catch (e: Exception) {
                println("[INPUT] Error saving config: ${e.message}")
            }
        }

        /**
         * Create default config file if it doesn't exist.
         */
        fun createDefaultIfMissing() {
            if (!configFile.exists()) {
                save(InputConfig())
            }
        }
    }

    /**
     * Get the NES button for a keyboard key code, or null if not mapped.
     */
    fun getButtonForKey(keyCode: KeyCode): Controller.Button? {
        val buttonName = keyboard[keyCode.name] ?: return null
        return try {
            Controller.Button.valueOf(buttonName)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}

/**
 * Gamepad button mapping configuration.
 * Uses SDL2 button indices which are standard across most controllers.
 *
 * Standard Xbox-style layout:
 * - Button 0: A (bottom)
 * - Button 1: B (right)
 * - Button 2: X (left)
 * - Button 3: Y (top)
 * - Button 4: Left Bumper
 * - Button 5: Right Bumper
 * - Button 6: Back/Select
 * - Button 7: Start
 * - Button 8: Left Stick Click
 * - Button 9: Right Stick Click
 * - Button 10: Guide/Home
 */
data class GamepadConfig(
    // Button index -> NES button name
    val buttons: Map<Int, String> = defaultButtonMapping,

    // Axis configuration for D-pad (some controllers use axes for D-pad)
    val useAxisForDpad: Boolean = true,
    val dpadAxisX: Int = 0,  // Left stick X axis (or D-pad axis on some controllers)
    val dpadAxisY: Int = 1,  // Left stick Y axis
    val axisDeadzone: Float = 0.5f  // Threshold for axis to register as pressed
) {
    companion object {
        // Standard Xbox-style button mapping
        val defaultButtonMapping = mapOf(
            0 to "A",       // A button -> NES A
            1 to "B",       // B button -> NES B
            6 to "SELECT",  // Back button -> NES Select
            7 to "START",   // Start button -> NES Start
            // D-pad buttons (if controller reports D-pad as buttons, not axes)
            11 to "UP",
            12 to "DOWN",
            13 to "LEFT",
            14 to "RIGHT"
        )
    }

    /**
     * Get the NES button for a gamepad button index, or null if not mapped.
     */
    fun getButtonForIndex(buttonIndex: Int): Controller.Button? {
        val buttonName = buttons[buttonIndex] ?: return null
        return try {
            Controller.Button.valueOf(buttonName)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
