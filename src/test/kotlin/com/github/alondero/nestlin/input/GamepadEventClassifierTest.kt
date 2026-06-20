package com.github.alondero.nestlin.input

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * GH #182: the Controller Configuration screen needs to capture gamepad axis and POV events
 * when the user is in "listening" mode. The capture hook in `GamepadInput` calls into this
 * pure classifier so the matching logic is unit-testable without booting JInput.
 *
 * Two questions per poll, both fed by the same primitives:
 *  - **Axis**: did any direction of this stick component cross the deadzone on this poll?
 *    (The previous-state bits tell us which side(s) were active.)
 *  - **POV**: which (if any) compass direction did this hat just become active in?
 *    (PovDirection maps to the standard 0.25 / 0.375 / ... / 1.0 floats.)
 */
class GamepadEventClassifierTest {

    @Test
    fun `axis returns null when value is inside the deadzone`() {
        assertThat(
            GamepadEventClassifier.axisTransition("x", 0.2f, deadzone = 0.5f, wasPos = false, wasNeg = false),
            equalTo(null),
        )
    }

    @Test
    fun `axis fires when positive direction goes inactive to active`() {
        // Stick pushed right while it was centered: this is the moment the UI should capture.
        val binding = GamepadEventClassifier.axisTransition(
            componentId = "x",
            value = 0.8f,
            deadzone = 0.5f,
            wasPos = false, wasNeg = false,
        )
        assertThat(binding, equalTo(GamepadBinding.Axis("x", AxisDirection.POSITIVE)))
    }

    @Test
    fun `axis fires when negative direction goes inactive to active`() {
        // Stick pushed up (Y-axis negative): this is the moment the UI should capture for NES UP.
        val binding = GamepadEventClassifier.axisTransition(
            componentId = "y",
            value = -0.8f,
            deadzone = 0.5f,
            wasPos = false, wasNeg = false,
        )
        assertThat(binding, equalTo(GamepadBinding.Axis("y", AxisDirection.NEGATIVE)))
    }

    @Test
    fun `axis does not fire if the same direction was already active`() {
        // Stick held right on a subsequent poll: nothing new to capture.
        val binding = GamepadEventClassifier.axisTransition(
            componentId = "x",
            value = 0.8f,
            deadzone = 0.5f,
            wasPos = true, wasNeg = false,
        )
        assertThat(binding, equalTo(null))
    }

    @Test
    fun `pov returns CENTER when value is off`() {
        assertThat(GamepadEventClassifier.povDirection(0f), equalTo(PovDirection.CENTER))
    }

    @Test
    fun `pov maps each cardinal float to the matching compass direction`() {
        assertThat(GamepadEventClassifier.povDirection(0.25f), equalTo(PovDirection.N))
        assertThat(GamepadEventClassifier.povDirection(0.5f), equalTo(PovDirection.E))
        assertThat(GamepadEventClassifier.povDirection(0.75f), equalTo(PovDirection.S))
        assertThat(GamepadEventClassifier.povDirection(1.0f), equalTo(PovDirection.W))
    }

    @Test
    fun `pov maps each diagonal float to the matching intercardinal direction`() {
        assertThat(GamepadEventClassifier.povDirection(0.125f), equalTo(PovDirection.NW))
        assertThat(GamepadEventClassifier.povDirection(0.375f), equalTo(PovDirection.NE))
        assertThat(GamepadEventClassifier.povDirection(0.625f), equalTo(PovDirection.SE))
        assertThat(GamepadEventClassifier.povDirection(0.875f), equalTo(PovDirection.SW))
    }

    @Test
    fun `pov transition fires when a new direction becomes active`() {
        // User pushes the hat up while it was centered: capture UP.
        val binding = GamepadEventClassifier.povTransition(
            componentId = "pov",
            value = 0.25f,
            previousActive = emptySet(),
        )
        assertThat(binding, equalTo(GamepadBinding.Pov("pov", PovDirection.N)))
    }

    @Test
    fun `pov transition is null when the active set is unchanged`() {
        // User holds the hat up on a subsequent poll: nothing new to capture.
        val binding = GamepadEventClassifier.povTransition(
            componentId = "pov",
            value = 0.25f,
            previousActive = setOf(PovDirection.N),
        )
        assertThat(binding, equalTo(null))
    }

    @Test
    fun `pov transition returns the new direction when it differs from the previous one`() {
        // Held NW (0.125), user nudges further to N (0.25): a single-element set has no
        // ordering complication, so the new direction is captured directly.
        val binding = GamepadEventClassifier.povTransition(
            componentId = "pov",
            value = 0.25f,
            previousActive = setOf(PovDirection.NW),
        )
        assertThat(binding, equalTo(GamepadBinding.Pov("pov", PovDirection.N)))
    }
}
