package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller.Button

/**
 * The pure, JavaFX-free editing model behind the Controller Configuration screen.
 *
 * The on-disk [InputConfig] is *reverse-keyed* for fast lookup during gameplay —
 * `keyboard` is `KeyCodeName -> ButtonName` and `gamepad.buttons` is `index -> ButtonName`.
 * A configuration UI, by contrast, iterates the eight NES [Button]s asking "what input
 * triggers this?". This class holds that *forward* view (one keyboard key and one gamepad
 * binding per NES button) plus the small "listening for the next press" state machine
 * the UI drives. It owns no JavaFX nodes, so it unit-tests without booting the toolkit —
 * mirroring [com.github.alondero.nestlin.ui.HexEditState].
 *
 * Round-tripping to [InputConfig] (see [toInputConfig] / [fromInputConfig]) inverts the
 * keyboard map and the single [GamepadConfig.bindings] storage map. The forward model
 * is one-binding-per-button, so a hand-edited `input.json` that mapped two inputs to the
 * same NES button collapses to a single binding on the next load through this UI — an
 * accepted limitation of a one-binding-per-button screen.
 */
class ControllerBindings private constructor(
    keyboard: Map<Button, String>,
    gamepad: Map<Button, GamepadBinding>,
) {
    // NES button -> JavaFX KeyCode name (e.g. A -> "Z").
    private val keyboard = keyboard.toMutableMap()
    // NES button -> gamepad binding (button index / axis / POV).
    private val gamepad = gamepad.toMutableMap()

    /** The button currently waiting for the next key/pad press, or null when idle. */
    var listeningFor: Button? = null
        private set

    /** The key bound to [button] (JavaFX KeyCode name), or null if unbound. */
    fun keyFor(button: Button): String? = keyboard[button]

    /** The gamepad binding bound to [button], or null if unbound. */
    fun padFor(button: Button): GamepadBinding? = gamepad[button]

    /**
     * The current forward-keyed gamepad map. Exposed so the Controller Configuration
     * screen's Save flow can merge both player tabs' gamepad rebinds into a single
     * `GamepadConfig.bindings` map. Read-only — callers must go through [capture] /
     * [resetToDefaults] to mutate.
     */
    val gamepadBindings: Map<Button, GamepadBinding> get() = gamepad

    /** Arm the state machine: the next [captureKey]/[capture] binds to [button]. */
    fun startListening(button: Button) {
        listeningFor = button
    }

    /** Disarm without binding (the UI's Escape / click-away path). */
    fun cancel() {
        listeningFor = null
    }

    /**
     * Bind the pressed key to the currently-listening button and stop listening.
     * If [keyName] is already bound to a *different* button it is stolen from that button,
     * keeping the underlying `key -> button` map 1:1. Returns the button that was bound,
     * or null if nothing was listening (the press is then ignored).
     */
    fun captureKey(keyName: String): Button? {
        val target = listeningFor ?: return null
        keyboard.entries.filter { it.value == keyName && it.key != target }
            .map { it.key }
            .forEach { keyboard.remove(it) }
        keyboard[target] = keyName
        listeningFor = null
        return target
    }

    /**
     * Bind a gamepad input (button index, axis direction, or POV hat direction) to the
     * currently-listening NES button and stop listening. Steals the binding from any
     * other button — mirrors [captureKey]. Returns the bound button, or null if nothing
     * was listening.
     */
    fun capture(binding: GamepadBinding): Button? {
        val target = listeningFor ?: return null
        gamepad.entries.filter { it.value == binding && it.key != target }
            .map { it.key }
            .forEach { gamepad.remove(it) }
        gamepad[target] = binding
        listeningFor = null
        return target
    }

    /** Replace the working state with the shipped defaults (does not persist — Save does that). */
    fun resetToDefaults() {
        keyboard.clear()
        keyboard.putAll(defaultKeyboard())
        gamepad.clear()
        gamepad.putAll(defaultGamepad())
        listeningFor = null
    }

    /**
     * Produce an [InputConfig] from the current working state, re-inverting to the on-disk
     * reverse-keyed form. The forward gamepad map collapses to a single
     * `GamepadConfig.bindings: Map<String, String>` keyed by [GamepadBinding.storageKey]
     * — button indices, axes, and POVs all live in one map. Non-binding fields of [base]
     * (axis deadzone) are preserved via [GamepadConfig.copy]. Empty result preserves
     * [GamepadConfig.defaultBindings] so a freshly-saved default file stays readable.
     *
     * [player] selects which slot in [PlayerKeyBindings] the working keyboard map lands
     * in. The other slot is preserved from [base]. Phase 5's per-player config UI uses
     * this to save each tab's bindings into the right slot without disturbing the other.
     */
    fun toInputConfig(base: InputConfig, player: Player = Player.ONE): InputConfig {
        val keyboardMap = keyboard.entries.associate { (button, key) -> key to button.name }
        val bindings = gamepad.entries.associate { (button, binding) -> binding.storageKey to button.name }
        val finalBindings = if (bindings.isEmpty()) base.gamepad.bindings else bindings
        val newKeyboard = when (player) {
            Player.ONE -> PlayerKeyBindings(player1 = keyboardMap, player2 = base.keyboard.player2)
            Player.TWO -> PlayerKeyBindings(player1 = base.keyboard.player1, player2 = keyboardMap)
        }
        return base.copy(
            keyboard = newKeyboard,
            gamepad = base.gamepad.copy(bindings = finalBindings),
        )
    }

    companion object {
        private fun defaultKeyboard(): Map<Button, String> =
            PlayerKeyBindings.defaultPlayer1Keyboard.entries
                .mapNotNull { (key, name) -> nesButton(name)?.let { it to key } }
                .toMap()

        private fun defaultGamepad(): Map<Button, GamepadBinding> =
            GamepadConfig.defaultBindings.entries
                .mapNotNull { (key, name) ->
                    val binding = GamepadBinding.fromStorageKey(key) ?: return@mapNotNull null
                    nesButton(name)?.let { it to binding }
                }
                .toMap()

        private fun nesButton(name: String): Button? =
            try {
                Button.valueOf(name)
            } catch (e: IllegalArgumentException) {
                null
            }

        /** A fresh model populated from the shipped default mappings. */
        fun defaults(): ControllerBindings = ControllerBindings(defaultKeyboard(), defaultGamepad())

        /**
         * Build the forward (button-keyed) model from a loaded [InputConfig], inverting the
         * keyboard map and reading the single `gamepad.bindings` map. Unknown button names
         * and malformed binding storage keys are skipped defensively. If a hand-edited
         * config bound several inputs to one button, the last one wins (one binding per button).
         *
         * [player] selects which player's keyboard slot is inverted into the forward model.
         * Defaults to [Player.ONE] so the legacy single-tab UI keeps working unchanged.
         */
        fun fromInputConfig(cfg: InputConfig, player: Player = Player.ONE): ControllerBindings {
            val keyboardMap = when (player) {
                Player.ONE -> cfg.keyboard.player1
                Player.TWO -> cfg.keyboard.player2
            }
            val keyboard = keyboardMap.entries
                .mapNotNull { (key, name) -> nesButton(name)?.let { it to key } }
                .toMap()
            val gamepad = cfg.gamepad.bindings.entries
                .mapNotNull { (key, name) ->
                    val binding = GamepadBinding.fromStorageKey(key) ?: return@mapNotNull null
                    val button = nesButton(name) ?: return@mapNotNull null
                    button to binding
                }
                .toMap()
            return ControllerBindings(keyboard, gamepad)
        }
    }
}
