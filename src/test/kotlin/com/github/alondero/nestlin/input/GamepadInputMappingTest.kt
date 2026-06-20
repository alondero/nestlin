package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller
import com.natpryce.hamkrest.absent
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Exercises the pure mapping helpers on [GamepadInput] without booting JInput. Constructing
 * a [GamepadInput] only stores fields; JInput is only touched in `initialize()`/`poll()`,
 * which these tests never call.
 */
class GamepadInputMappingTest {

    private fun gamepad(config: GamepadConfig) = GamepadInput(Controller(), config)

    @Test
    fun `config remap overrides the built-in default for a conventional index`() {
        // Built-in default maps index 0 -> A. A config remap of btn:0 -> SELECT must win
        // (this is the wart the config-first change fixes).
        val gp = gamepad(GamepadConfig(bindings = mapOf("btn:0" to "SELECT")))

        assertThat(gp.mapButtonToNes("Button 0"), equalTo(Controller.Button.SELECT))
    }

    @Test
    fun `falls back to the built-in mapping when the index is absent from config`() {
        val gp = gamepad(GamepadConfig(bindings = emptyMap()))

        assertThat(gp.mapButtonToNes("Button 1"), equalTo(Controller.Button.B))
    }

    @Test
    fun `unmapped index with no built-in fallback yields null`() {
        val gp = gamepad(GamepadConfig(bindings = emptyMap()))

        assertThat(gp.mapButtonToNes("Button 9"), absent())
    }

    @Test
    fun `componentToIndex extracts the numeric index, null when none`() {
        val gp = gamepad(GamepadConfig())

        assertThat(gp.componentToIndex("Button 11"), equalTo(11))
        assertThat(gp.componentToIndex("x"), absent())
    }
}
