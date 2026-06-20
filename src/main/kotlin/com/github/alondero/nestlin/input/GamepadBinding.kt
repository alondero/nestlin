package com.github.alondero.nestlin.input

/**
 * A single input source on a gamepad, as JInput reports them: a button (digital 0/1), an
 * analog stick axis (continuous -1..1), or a POV hat (one of eight compass directions).
 * The Controller Configuration screen binds any of these to a single NES button.
 *
 * The sealed shape gives the editor + runtime a single type to pass around (no parallel
 * `axisFor(button)` / `povFor(button)` accessors). [storageKey] gives a gson-friendly
 * string for `input.json`; [displayName] is the human label shown in the legend.
 */
sealed class GamepadBinding {
    /** Canonical string for JSON storage. Round-trips through [fromStorageKey]. */
    abstract val storageKey: String

    /** Short, human label suitable for the binding legend. */
    abstract val displayName: String

    /** A JInput digital button index (0-based, matches the SDL2 / Xbox layout). */
    data class ButtonIndex(val index: Int) : GamepadBinding() {
        override val storageKey: String get() = "btn:$index"
        override val displayName: String get() = "pad $index"
    }

    /**
     * One direction of an analog stick axis. POSITIVE = value > deadzone;
     * NEGATIVE = value < -deadzone. The JInput component identifier is preserved
     * verbatim so a captured binding reproduces the exact stick axis the user pressed.
     */
    data class Axis(val componentId: String, val direction: AxisDirection) : GamepadBinding() {
        override val storageKey: String get() = "axis:${componentId.lowercase()}:${direction.token}"
        override val displayName: String get() = "axis ${componentId.lowercase()}${direction.sign}"
    }

    /** One compass direction of a POV/hat switch. CENTER is "off". */
    data class Pov(val componentId: String, val direction: PovDirection) : GamepadBinding() {
        override val storageKey: String get() = "pov:${componentId.lowercase()}:${direction.token}"
        override val displayName: String get() = "${componentId.lowercase()} ${direction.label}"
    }

    companion object {
        /**
         * Parse a [storageKey] back into the binding it encodes. Returns null for keys
         * that don't match the format (defensive — old `input.json` may carry keys we
         * don't recognise yet, and they'd otherwise NPE on read).
         */
        fun fromStorageKey(key: String): GamepadBinding? {
            val parts = key.split(":")
            return when {
                parts.size == 2 && parts[0] == "btn" ->
                    parts[1].toIntOrNull()?.let(::ButtonIndex)
                parts.size == 3 && parts[0] == "axis" ->
                    AxisDirection.fromToken(parts[2])?.let { Axis(parts[1], it) }
                parts.size == 3 && parts[0] == "pov" ->
                    PovDirection.fromToken(parts[2])?.let { Pov(parts[1], it) }
                else -> null
            }
        }
    }
}

/** Which side of an analog axis crossed the deadzone. */
enum class AxisDirection(val token: String, val sign: String) {
    POSITIVE("pos", "+"),
    NEGATIVE("neg", "-");

    companion object {
        fun fromToken(token: String): AxisDirection? =
            values().firstOrNull { it.token == token }
    }
}

/** One of the eight compass directions reported by a JInput POV/hat component. */
enum class PovDirection(val token: String, val label: String) {
    N("n", "N"), NE("ne", "NE"), E("e", "E"), SE("se", "SE"),
    S("s", "S"), SW("sw", "SW"), W("w", "W"), NW("nw", "NW"),
    CENTER("center", "center");

    companion object {
        fun fromToken(token: String): PovDirection? =
            values().firstOrNull { it.token == token }
    }
}
