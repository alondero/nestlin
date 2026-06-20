package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller.Button
import com.natpryce.hamkrest.absent
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.present
import org.junit.jupiter.api.Test

class ControllerBindingsTest {

    @Test
    fun `defaults match the shipped InputConfig defaults`() {
        val bindings = ControllerBindings.defaults()

        // Default keyboard: Z->A, ENTER->START, etc. (inverted to button-keyed).
        assertThat(bindings.keyFor(Button.A), equalTo("Z"))
        assertThat(bindings.keyFor(Button.START), equalTo("ENTER"))
        assertThat(bindings.keyFor(Button.UP), equalTo("UP"))
        // Default gamepad: index 0 -> A, 7 -> START.
        assertThat(bindings.padFor(Button.A), equalTo(GamepadBinding.ButtonIndex(0)))
        assertThat(bindings.padFor(Button.START), equalTo(GamepadBinding.ButtonIndex(7)))
    }

    @Test
    fun `nothing is listening on a fresh model`() {
        assertThat(ControllerBindings.defaults().listeningFor, absent())
    }

    @Test
    fun `captureKey binds the listening button and stops listening`() {
        val bindings = ControllerBindings.defaults()
        bindings.startListening(Button.B)
        assertThat(bindings.listeningFor, equalTo(Button.B))

        val bound = bindings.captureKey("K")

        assertThat(bound, equalTo(Button.B))
        assertThat(bindings.keyFor(Button.B), equalTo("K"))
        assertThat(bindings.listeningFor, absent())
    }

    @Test
    fun `capture binds a gamepad button index to the listening button`() {
        val bindings = ControllerBindings.defaults()
        bindings.startListening(Button.SELECT)

        val bound = bindings.capture(GamepadBinding.ButtonIndex(9))

        assertThat(bound, equalTo(Button.SELECT))
        assertThat(bindings.padFor(Button.SELECT), equalTo(GamepadBinding.ButtonIndex(9)))
        assertThat(bindings.listeningFor, absent())
    }

    @Test
    fun `capture binds an analog stick axis to the listening button`() {
        // GH #182: pressing the left stick up (Y-axis negative) while listening for NES UP
        // must bind the axis and stop the listening state.
        val bindings = ControllerBindings.defaults()
        bindings.startListening(Button.UP)

        val bound = bindings.capture(GamepadBinding.Axis("y", AxisDirection.NEGATIVE))

        assertThat(bound, equalTo(Button.UP))
        assertThat(bindings.padFor(Button.UP), equalTo(GamepadBinding.Axis("y", AxisDirection.NEGATIVE)))
        assertThat(bindings.listeningFor, absent())
    }

    @Test
    fun `capture binds a POV hat direction to the listening button`() {
        // GH #182: pressing a D-pad hat north while listening for NES UP must bind the POV.
        val bindings = ControllerBindings.defaults()
        bindings.startListening(Button.UP)

        val bound = bindings.capture(GamepadBinding.Pov("pov", PovDirection.N))

        assertThat(bound, equalTo(Button.UP))
        assertThat(bindings.padFor(Button.UP), equalTo(GamepadBinding.Pov("pov", PovDirection.N)))
        assertThat(bindings.listeningFor, absent())
    }

    @Test
    fun `capture is ignored when nothing is listening`() {
        val bindings = ControllerBindings.defaults()
        val before = bindings.keyFor(Button.A)

        val bound = bindings.captureKey("Q")

        assertThat(bound, absent())
        assertThat(bindings.keyFor(Button.A), equalTo(before)) // unchanged
    }

    @Test
    fun `cancel disarms without binding`() {
        val bindings = ControllerBindings.defaults()
        bindings.startListening(Button.A)

        bindings.cancel()

        assertThat(bindings.listeningFor, absent())
        // A keeps its default; a subsequent press is ignored.
        assertThat(bindings.captureKey("M"), absent())
        assertThat(bindings.keyFor(Button.A), equalTo("Z"))
    }

    @Test
    fun `binding an already-used key steals it from the previous button`() {
        val bindings = ControllerBindings.defaults() // Z -> A by default
        bindings.startListening(Button.B)

        bindings.captureKey("Z") // steal Z from A and give it to B

        assertThat(bindings.keyFor(Button.B), equalTo("Z"))
        assertThat(bindings.keyFor(Button.A), absent()) // A no longer owns Z
    }

    @Test
    fun `binding an already-used pad index steals it from the previous button`() {
        val bindings = ControllerBindings.defaults() // index 0 -> A by default
        bindings.startListening(Button.B)

        bindings.capture(GamepadBinding.ButtonIndex(0))

        assertThat(bindings.padFor(Button.B), equalTo(GamepadBinding.ButtonIndex(0)))
        assertThat(bindings.padFor(Button.A), absent())
    }

    @Test
    fun `binding an axis steals it from any other button that owned it`() {
        // Mirror the steal semantics across gamepad input kinds: an axis is bound to one
        // NES button, so handing it to B should clear any prior owner.
        val bindings = ControllerBindings.defaults()
        bindings.startListening(Button.UP)
        bindings.capture(GamepadBinding.Axis("y", AxisDirection.NEGATIVE))
        bindings.startListening(Button.DOWN)

        bindings.capture(GamepadBinding.Axis("y", AxisDirection.NEGATIVE))

        assertThat(bindings.padFor(Button.DOWN), equalTo(GamepadBinding.Axis("y", AxisDirection.NEGATIVE)))
        assertThat(bindings.padFor(Button.UP), absent())
    }

    @Test
    fun `resetToDefaults restores shipped mappings and clears listening`() {
        val bindings = ControllerBindings.defaults()
        bindings.startListening(Button.A)
        bindings.captureKey("P")
        assertThat(bindings.keyFor(Button.A), equalTo("P"))

        bindings.resetToDefaults()

        assertThat(bindings.keyFor(Button.A), equalTo("Z"))
        assertThat(bindings.listeningFor, absent())
    }

    @Test
    fun `round-trips through InputConfig preserving keyboard and gamepad`() {
        val original = ControllerBindings.defaults()
        original.startListening(Button.A)
        original.captureKey("K") // remap A to K
        original.startListening(Button.B)
        original.capture(GamepadBinding.ButtonIndex(3)) // remap B's pad button to index 3

        val config = original.toInputConfig(InputConfig())
        val reloaded = ControllerBindings.fromInputConfig(config)

        assertThat(reloaded.keyFor(Button.A), equalTo("K"))
        assertThat(reloaded.padFor(Button.B), equalTo(GamepadBinding.ButtonIndex(3)))
        assertThat(reloaded.keyFor(Button.START), equalTo("ENTER")) // untouched default survives
    }

    @Test
    fun `round-trips an axis binding through InputConfig`() {
        val original = ControllerBindings.defaults()
        original.startListening(Button.UP)
        original.capture(GamepadBinding.Axis("y", AxisDirection.NEGATIVE))

        val config = original.toInputConfig(InputConfig())
        val reloaded = ControllerBindings.fromInputConfig(config)

        assertThat(reloaded.padFor(Button.UP), equalTo(GamepadBinding.Axis("y", AxisDirection.NEGATIVE)))
    }

    @Test
    fun `round-trips a POV binding through InputConfig`() {
        val original = ControllerBindings.defaults()
        original.startListening(Button.DOWN)
        original.capture(GamepadBinding.Pov("pov", PovDirection.S))

        val config = original.toInputConfig(InputConfig())
        val reloaded = ControllerBindings.fromInputConfig(config)

        assertThat(reloaded.padFor(Button.DOWN), equalTo(GamepadBinding.Pov("pov", PovDirection.S)))
    }

    @Test
    fun `toInputConfig preserves gamepad axis deadzone`() {
        val base = InputConfig(gamepad = GamepadConfig(axisDeadzone = 0.8f))
        val bindings = ControllerBindings.fromInputConfig(base)

        val result = bindings.toInputConfig(base)

        assertThat(result.gamepad.axisDeadzone, equalTo(0.8f))
    }

    @Test
    fun `toInputConfig stores axis and pov bindings on GamepadConfig`() {
        val original = ControllerBindings.defaults()
        original.startListening(Button.UP)
        original.capture(GamepadBinding.Axis("y", AxisDirection.NEGATIVE))
        original.startListening(Button.LEFT)
        original.capture(GamepadBinding.Pov("pov", PovDirection.W))

        val result = original.toInputConfig(InputConfig())

        // One unified bindings map: axis and POV share storageKey shape with button indices.
        assertThat(result.gamepad.bindings["axis:y:neg"], equalTo("UP"))
        assertThat(result.gamepad.bindings["pov:pov:w"], equalTo("LEFT"))
        // Unrelated defaults survive (standard Xbox button 0 still maps to NES A).
        assertThat(result.gamepad.bindings["btn:0"], equalTo("A"))
    }

    @Test
    fun `fromInputConfig skips unknown button names`() {
        val config = InputConfig(keyboard = mapOf("Z" to "A", "Q" to "NONSENSE"))

        val bindings = ControllerBindings.fromInputConfig(config)

        assertThat(bindings.keyFor(Button.A), equalTo("Z"))
        // "NONSENSE" is not a real Button, so no binding leaks in.
        assertThat(bindings.keyFor(Button.B), absent())
    }
}
