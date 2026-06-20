package com.github.alondero.nestlin.input

/**
 * Pure matching logic for gamepad button-press / axis-engagement / POV-engagement events,
 * extracted from [GamepadInput] so it can be unit-tested without booting JInput (GH #182).
 *
 * JInput reports three kinds of [net.java.games.input.Component] events:
 *
 *  - **Digital buttons**: value is 0.0 (up) or 1.0 (down). Single transition to capture.
 *  - **Analog axes**: value is a continuous -1..1 float. We compare against a per-axis
 *    deadzone and report which side crossed it on this poll. Two independent directions
 *    per component (POSITIVE / NEGATIVE) — they're not mutually exclusive in theory but
 *    the deadzone means they are in practice for a settled stick.
 *  - **POV hats**: value is one of the eight compass floats (0.125 / 0.25 / ... / 1.0)
 *    or 0.0 (off). Multiple directions can be "active" simultaneously on a diagonal
 *    press; we report the *newly-active* directions vs the previous poll.
 *
 * The matching rules (deadzone, N/E/S/W = 0.25/0.5/0.75/1.0) match the values [GamepadInput]
 * was already using for runtime routing, so behaviour is identical for unconfigured inputs.
 */
internal object GamepadEventClassifier {

    /**
     * Which side of an axis crossed the deadzone on this poll, or null if the value is
     * inside the deadzone. Pure value/deadzone comparison — the component identifier is
     * attached by the caller when the result is wrapped into a [GamepadBinding.Axis].
     */
    fun axisDirection(value: Float, deadzone: Float): AxisDirection? =
        when {
            value > deadzone -> AxisDirection.POSITIVE
            value < -deadzone -> AxisDirection.NEGATIVE
            else -> null
        }

    /**
     * For a single axis poll, return the [GamepadBinding.Axis] whose direction went
     * inactive→active on this poll, or null if no such edge occurred. Used by
     * [GamepadInput] to fire the capture listener.
     */
    fun axisTransition(
        componentId: String,
        value: Float,
        deadzone: Float,
        wasPos: Boolean,
        wasNeg: Boolean,
    ): GamepadBinding.Axis? {
        val now = axisDirection(value, deadzone) ?: return null
        val wasActive = if (now == AxisDirection.POSITIVE) wasPos else wasNeg
        return if (wasActive) null else GamepadBinding.Axis(componentId, now)
    }

    /**
     * Map a POV value float to the matching compass direction, or [PovDirection.CENTER]
     * if the value is 0.0 / [net.java.games.input.Component.POV.OFF].
     */
    fun povDirection(value: Float): PovDirection = when (value) {
        0.125f -> PovDirection.NW
        0.25f -> PovDirection.N
        0.375f -> PovDirection.NE
        0.5f -> PovDirection.E
        0.625f -> PovDirection.SE
        0.75f -> PovDirection.S
        0.875f -> PovDirection.SW
        1.0f -> PovDirection.W
        else -> PovDirection.CENTER
    }

    /**
     * For a single POV poll, return the [GamepadBinding.Pov] whose direction is newly
     * active this poll, or null if no change. JInput reports POV hats as a single
     * discrete float (0.0/0.125/0.25/.../1.0) — exactly one direction at a time — so
     * the active set has at most one element and there's no ordering to compute.
     */
    fun povTransition(
        componentId: String,
        value: Float,
        previousActive: Set<PovDirection>,
    ): GamepadBinding.Pov? {
        val current = povDirection(value)
        return if (current in previousActive) null else GamepadBinding.Pov(componentId, current)
    }
}
