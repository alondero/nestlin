package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller.Button

/**
 * The pure, JavaFX-free editing model behind the Controller Configuration screen.
 *
 * The on-disk [InputConfig] is *reverse-keyed* for fast lookup during gameplay —
 * `keyboard` is `KeyCodeName -> ButtonName` and `gamepad.buttons` is `index -> ButtonName`.
 * A configuration UI, by contrast, iterates the eight NES [Button]s asking "what input
 * triggers this?". This class holds that *forward* view (one keyboard key and one gamepad
 * button index per NES button) plus the small "listening for the next press" state machine
 * the UI drives. It owns no JavaFX nodes, so it unit-tests without booting the toolkit —
 * mirroring [com.github.alondero.nestlin.ui.HexEditState].
 *
 * Round-tripping to [InputConfig] (see [toInputConfig] / [fromInputConfig]) inverts the maps.
 * The forward model is one-binding-per-button, so a hand-edited `input.json` that mapped two
 * keys to the same NES button collapses to a single key on the next load through this UI —
 * an accepted limitation of a one-key-per-button screen.
 */
class ControllerBindings private constructor(
    keyboard: Map<Button, String>,
    gamepad: Map<Button, Int>,
) {
    // NES button -> JavaFX KeyCode name (e.g. A -> "Z").
    private val keyboard = keyboard.toMutableMap()
    // NES button -> gamepad button index (e.g. A -> 0).
    private val gamepad = gamepad.toMutableMap()

    /** The button currently waiting for the next key/pad press, or null when idle. */
    var listeningFor: Button? = null
        private set

    /** The key bound to [button] (JavaFX KeyCode name), or null if unbound. */
    fun keyFor(button: Button): String? = keyboard[button]

    /** The gamepad button index bound to [button], or null if unbound. */
    fun padFor(button: Button): Int? = gamepad[button]

    /** Arm the state machine: the next [captureKey]/[capturePad] binds to [button]. */
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
     * Bind the pressed gamepad button [index] to the listening button and stop listening.
     * Steals the index from any other button, mirroring [captureKey]. Returns the bound
     * button, or null if nothing was listening.
     */
    fun capturePad(index: Int): Button? {
        val target = listeningFor ?: return null
        gamepad.entries.filter { it.value == index && it.key != target }
            .map { it.key }
            .forEach { gamepad.remove(it) }
        gamepad[target] = index
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
     * reverse-keyed form. Non-button fields of [base] (the gamepad axis/deadzone settings)
     * are preserved via [GamepadConfig.copy].
     */
    fun toInputConfig(base: InputConfig): InputConfig {
        val keyboardMap = keyboard.entries.associate { (button, key) -> key to button.name }
        val gamepadMap = gamepad.entries.associate { (button, index) -> index to button.name }
        return base.copy(
            keyboard = keyboardMap,
            gamepad = base.gamepad.copy(buttons = gamepadMap),
        )
    }

    companion object {
        private fun defaultKeyboard(): Map<Button, String> =
            InputConfig.defaultKeyboardMapping.entries
                .mapNotNull { (key, name) -> nesButton(name)?.let { it to key } }
                .toMap()

        private fun defaultGamepad(): Map<Button, Int> =
            GamepadConfig.defaultButtonMapping.entries
                .mapNotNull { (index, name) -> nesButton(name)?.let { it to index } }
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
         * Build the forward (button-keyed) model from a loaded [InputConfig], inverting both
         * reverse-keyed maps. Unknown button names are skipped defensively. If a hand-edited
         * config bound several inputs to one button, the last one wins (one binding per button).
         */
        fun fromInputConfig(cfg: InputConfig): ControllerBindings {
            val keyboard = cfg.keyboard.entries
                .mapNotNull { (key, name) -> nesButton(name)?.let { it to key } }
                .toMap()
            val gamepad = cfg.gamepad.buttons.entries
                .mapNotNull { (index, name) -> nesButton(name)?.let { it to index } }
                .toMap()
            return ControllerBindings(keyboard, gamepad)
        }
    }
}
