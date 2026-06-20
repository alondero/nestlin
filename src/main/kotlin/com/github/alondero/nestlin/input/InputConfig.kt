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

        private const val FILE_NAME = "input.json"
        // The production config location. Tests pass their own @TempDir so the round-trip
        // can be exercised without touching the developer's real ~/.config/nestlin.
        private val defaultConfigDir = File(System.getProperty("user.home"), ".config/nestlin")

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
        fun load(configDir: File = defaultConfigDir): InputConfig {
            val configFile = File(configDir, FILE_NAME)
            return try {
                if (configFile.exists()) {
                    println("[INPUT] Loading config from ${configFile.absolutePath}")
                    // gson returns null for an empty file or literal `null` content (no
                    // exception thrown), so coalesce to defaults — the return type is non-null
                    // and callers (e.g. handleInput) deref it every key event.
                    gson.fromJson(configFile.readText(), InputConfig::class.java) ?: InputConfig()
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
        fun save(config: InputConfig, configDir: File = defaultConfigDir) {
            val configFile = File(configDir, FILE_NAME)
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
        fun createDefaultIfMissing(configDir: File = defaultConfigDir) {
            if (!File(configDir, FILE_NAME).exists()) {
                save(InputConfig(), configDir)
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
 * Gamepad input mapping configuration. Every binding — digital button, analog-stick
 * axis direction, or POV-hat direction — is keyed in a single map by its
 * [GamepadBinding.storageKey]. The runtime and editor look up by constructing the
 * matching [GamepadBinding] and calling [getButtonForBinding].
 *
 * SDL2 button indices are standard across most controllers:
 * - Button 0: A (bottom)   - Button 1: B (right)
 * - Button 2: X (left)     - Button 3: Y (top)
 * - Button 6: Back/Select  - Button 7: Start
 * - Buttons 11..14: D-pad (some controllers report it as buttons, not axes)
 */
data class GamepadConfig(
    /**
     * GamepadBinding.storageKey -> NES button name. Keys look like
     * `"btn:0"` (A button), `"axis:y:neg"` (left stick pushed up), or
     * `"pov:pov:n"` (D-pad north). Defaults to the standard Xbox layout; the editor
     * rewrites this map on Save.
     */
    val bindings: Map<String, String> = defaultBindings,

    /** Threshold above which an axis value registers as pressed in either direction. */
    val axisDeadzone: Float = 0.5f,
) {
    companion object {
        // Standard Xbox-style bindings (storage keys → NES button names).
        val defaultBindings = mapOf(
            "btn:0" to "A",
            "btn:1" to "B",
            "btn:6" to "SELECT",
            "btn:7" to "START",
            // D-pad buttons (some controllers report D-pad as digital buttons, not axes).
            "btn:11" to "UP",
            "btn:12" to "DOWN",
            "btn:13" to "LEFT",
            "btn:14" to "RIGHT",
        )
    }

    /**
     * Resolve a [GamepadBinding] to its NES button, or null if the user hasn't bound
     * (or has explicitly cleared) this source. Single source of truth for runtime
     * dispatch — covers button indices, analog axes, and POV hats uniformly.
     */
    fun getButtonForBinding(binding: GamepadBinding): Controller.Button? {
        val name = bindings[binding.storageKey] ?: return null
        return try {
            Controller.Button.valueOf(name)
        } catch (e: IllegalArgumentException) {
            null
        }
    }
}
