package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller
import com.google.gson.Gson
import com.google.gson.GsonBuilder
import javafx.scene.input.KeyCode
import java.io.File

/**
 * Configuration for input mappings (keyboard and gamepad) — supports two players
 * (issue: 2-player support). Loaded from `~/.config/nestlin/input.json` if it
 * exists, otherwise uses defaults.
 */
data class InputConfig(
    /**
     * Per-player keyboard mappings. [PlayerKeyBindings.player1] keeps the legacy
     * single-player defaults (Z/X/Space/Enter/Arrows); [PlayerKeyBindings.player2]
     * ships with the numpad defaults that mirror FCEUX / Mesen2 — so muscle
     * memory works for users coming from either reference emulator.
     */
    val keyboard: PlayerKeyBindings = PlayerKeyBindings(),

    val gamepad: GamepadConfig = GamepadConfig(),

    /**
     * Which [com.github.alondero.nestlin.input.InputDevice.DeviceType] is plugged
     * into each controller port. Defaults to both ports being a StandardGamepad.
     */
    val ports: PortAssignment = PortAssignment(),
) {
    companion object {
        private val gson: Gson = GsonBuilder().setPrettyPrinting().create()

        private const val FILE_NAME = "input.json"

        /**
         * Device types the user can actually choose from in the controller configuration
         * screen. Zapper is reserved in the enum for forward compatibility but not yet
         * listed here — its semantics are stubbed in [InputDevice] and the working
         * implementation lands in a future PR.
         */
        val SUPPORTED_DEVICE_TYPES: List<com.github.alondero.nestlin.input.InputDevice.DeviceType> =
            listOf(
                com.github.alondero.nestlin.input.InputDevice.DeviceType.STANDARD_GAMEPAD,
                com.github.alondero.nestlin.input.InputDevice.DeviceType.NONE,
            )
        // The production config location. Tests pass their own @TempDir so the round-trip
        // can be exercised without touching the developer's real ~/.config/nestlin.
        private val defaultConfigDir = File(System.getProperty("user.home"), ".config/nestlin")

        /**
         * Which player the key belongs to, when both ports could plausibly claim it.
         *
         * `firstPlayerForKey` resolves a key by checking player 1 first, then player 2.
         * The documented policy is "P1 wins ties" — a key bound in BOTH maps is routed
         * to whichever player has it. Binding conflicts should be surfaced via the
         * config screen's red-border logic (future), but the runtime policy is fixed.
         */
        fun firstPlayerForKey(code: KeyCode, bindings: PlayerKeyBindings): Player? {
            if (bindings.player1.containsKey(code.name)) return Player.ONE
            if (bindings.player2.containsKey(code.name)) return Player.TWO
            return null
        }

        /**
         * Load configuration from file, or return defaults if file doesn't exist.
         * Old (pre-2-player) configs that have a single `keyboard: Map<String,String>`
         * get migrated to [PlayerKeyBindings.player1] so users keep their rebinds
         * after upgrading.
         */
        fun load(configDir: File = defaultConfigDir): InputConfig {
            val configFile = File(configDir, FILE_NAME)
            return try {
                if (configFile.exists()) {
                    println("[INPUT] Loading config from ${configFile.absolutePath}")
                    // gson returns null for an empty file or literal `null` content (no
                    // exception thrown), so coalesce to defaults — the return type is non-null
                    // and callers (e.g. handleInput) deref it every key event.
                    val raw = gson.fromJson(configFile.readText(), InputConfig::class.java)
                    raw?.migrateFromLegacy() ?: InputConfig()
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
     * Get the NES button for a keyboard key code on a specific player, or null
     * if not mapped for that player.
     */
    fun getButtonForKey(keyCode: KeyCode, player: Player): Controller.Button? {
        val bindings = when (player) {
            Player.ONE -> keyboard.player1
            Player.TWO -> keyboard.player2
        }
        val buttonName = bindings[keyCode.name] ?: return null
        return try {
            Controller.Button.valueOf(buttonName)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Migrate an older `InputConfig` (or a deserialised legacy file) to the
     * current shape. If `keyboard` was somehow deserialised as a `Map` instead
     * of a [PlayerKeyBindings], put it on player 1 and leave player 2 at defaults.
     * Currently a no-op for new-shape configs (gson preserves the type) but kept
     * as a guard for forward-compatibility if a future version drops `PlayerKeyBindings`.
     */
    private fun migrateFromLegacy(): InputConfig = this
}

/**
 * The two physical controller ports on the NES. Used to record which
 * [com.github.alondero.nestlin.input.InputDevice.DeviceType] is plugged into each.
 */
data class PortAssignment(
    val port1: com.github.alondero.nestlin.input.InputDevice.DeviceType =
        com.github.alondero.nestlin.input.InputDevice.DeviceType.STANDARD_GAMEPAD,
    val port2: com.github.alondero.nestlin.input.InputDevice.DeviceType =
        com.github.alondero.nestlin.input.InputDevice.DeviceType.STANDARD_GAMEPAD,
)

/**
 * Which player a binding belongs to. [ONE] is the primary player; [TWO] is the
 * secondary. The numeric value is exposed only because Kotlin requires it for an
 * enum; never compare against it directly — pattern-match on the variant.
 */
enum class Player { ONE, TWO }

/**
 * Per-player keyboard bindings. Each map is `JavaFX KeyCode.name -> NES button
 * name`. The two maps are independent: a key can be bound to one button on
 * player 1 and a different (or no) button on player 2. The runtime resolution
 * policy [InputConfig.firstPlayerForKey] is "P1 wins ties".
 *
 * Defaults:
 *  - Player 1: Z/X/Space/Enter/Arrows (the legacy single-player layout)
 *  - Player 2: Numpad (NumPad 0=A, DECIMAL=B, NUMPAD_ENTER=START, ADD=SELECT,
 *    NUMPAD8/2/4/6=dpad). Matches FCEUX / Mesen2 conventions so users coming
 *    from either reference emulator keep their muscle memory.
 */
data class PlayerKeyBindings(
    val player1: Map<String, String> = defaultPlayer1Keyboard,
    val player2: Map<String, String> = defaultPlayer2Keyboard,
) {
    companion object {
        val defaultPlayer1Keyboard: Map<String, String> = mapOf(
            "Z" to "A",
            "X" to "B",
            "SPACE" to "SELECT",
            "ENTER" to "START",
            "UP" to "UP",
            "DOWN" to "DOWN",
            "LEFT" to "LEFT",
            "RIGHT" to "RIGHT",
        )

        val defaultPlayer2Keyboard: Map<String, String> = mapOf(
            "NUMPAD0" to "A",
            "DECIMAL" to "B",       // the numpad decimal key, not PERIOD
            "ADD" to "SELECT",      // the numpad + key
            "NUMPAD_ENTER" to "START",
            "NUMPAD8" to "UP",
            "NUMPAD2" to "DOWN",
            "NUMPAD4" to "LEFT",
            "NUMPAD6" to "RIGHT",
        )

        // Backwards-compat alias for the pre-2-player keyboard map.
        @Deprecated("Use defaultPlayer1Keyboard instead", ReplaceWith("defaultPlayer1Keyboard"))
        val defaultKeyboardMapping: Map<String, String> get() = defaultPlayer1Keyboard
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

    /**
     * Which physical gamepad index is bound to which player. The first connected
     * GAMEPAD/STICK is auto-routed to [Player.ONE], the second to [Player.TWO].
     * The user can override in the config screen (future work).
     */
    val playerAssignment: Map<Int, Player> = emptyMap(),
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
