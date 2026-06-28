package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import net.java.games.input.Component
import net.java.games.input.Controller as JInputController
import net.java.games.input.EventQueue
import net.java.games.input.Rumbler
import org.junit.jupiter.api.Test

/**
 * Multi-gamepad auto-routing test (issue: 2-player support).
 *
 * Verifies that [MultiGamepadInput] assigns connected gamepads to Player slots
 * in enumeration order — first gamepad → [Player.ONE], second → [Player.TWO].
 *
 * Uses a [FakeController] that implements JInput's abstract [JInputController]
 * with no-op overrides — none of the polled methods are exercised by
 * `assignGamepads`, so the stub never reaches native code.
 */
class GamepadAutoRouteTest {

    /** Minimal JInput [JInputController] stub. Controller is an interface, so we
     *  implement the abstract methods with no-ops. Only [name] and [type] are real. */
    private class FakeController(
        private val controllerName: String,
        private val controllerType: JInputController.Type,
    ) : JInputController {
        override fun getName(): String = controllerName
        override fun getType(): JInputController.Type = controllerType
        override fun getControllers(): Array<JInputController> = emptyArray()
        override fun getComponents(): Array<Component> = emptyArray()
        override fun getComponent(id: Component.Identifier?): Component? = null
        override fun getRumblers(): Array<Rumbler> = emptyArray()
        override fun poll(): Boolean = true
        override fun setEventQueueSize(size: Int) {}
        override fun getEventQueue(): EventQueue? = null
        override fun getPortType(): JInputController.PortType = JInputController.PortType.UNKNOWN
        override fun getPortNumber(): Int = 0
    }

    @Test
    fun `two gamepads are routed to P1 and P2 in enumeration order`() {
        val hub = MultiGamepadInput(listOf(Controller(), Controller()), GamepadConfig())
        val gamepads = listOf<JInputController>(
            FakeController("pad-A", JInputController.Type.GAMEPAD),
            FakeController("pad-B", JInputController.Type.STICK),
        )

        hub.assignGamepads(gamepads)

        assertThat(hub.bindings.getValue(Player.ONE).jinputController?.name, equalTo("pad-A"))
        assertThat(hub.bindings.getValue(Player.TWO).jinputController?.name, equalTo("pad-B"))
    }

    @Test
    fun `one gamepad only fills P1`() {
        val hub = MultiGamepadInput(listOf(Controller(), Controller()), GamepadConfig())
        val gamepads = listOf<JInputController>(
            FakeController("pad-A", JInputController.Type.GAMEPAD),
        )

        hub.assignGamepads(gamepads)

        assertThat(hub.bindings.getValue(Player.ONE).jinputController?.name, equalTo("pad-A"))
        assertThat(hub.bindings.getValue(Player.TWO).jinputController, equalTo(null))
    }

    @Test
    fun `three gamepads — the third is dropped (only P1 and P2 slots exist)`() {
        val hub = MultiGamepadInput(listOf(Controller(), Controller()), GamepadConfig())
        val gamepads = listOf<JInputController>(
            FakeController("pad-A", JInputController.Type.GAMEPAD),
            FakeController("pad-B", JInputController.Type.GAMEPAD),
            FakeController("pad-C", JInputController.Type.GAMEPAD),
        )

        hub.assignGamepads(gamepads)

        assertThat(hub.bindings.getValue(Player.ONE).jinputController?.name, equalTo("pad-A"))
        assertThat(hub.bindings.getValue(Player.TWO).jinputController?.name, equalTo("pad-B"))
    }

    @Test
    fun `re-enumeration with the same gamepads does not reshuffle assignments`() {
        val hub = MultiGamepadInput(listOf(Controller(), Controller()), GamepadConfig())
        val padA = FakeController("pad-A", JInputController.Type.GAMEPAD)
        val padB = FakeController("pad-B", JInputController.Type.GAMEPAD)

        hub.assignGamepads(listOf(padA, padB))
        assertThat(hub.bindings.getValue(Player.ONE).jinputController?.name, equalTo("pad-A"))
        assertThat(hub.bindings.getValue(Player.TWO).jinputController?.name, equalTo("pad-B"))

        // Detach P1 and re-enumerate — pad-B is filtered (already attached),
        // pad-A fills the free P1 slot. The route is stable across re-enumerations.
        hub.bindings.getValue(Player.ONE).detachController()
        hub.assignGamepads(listOf(padA, padB))

        assertThat(hub.bindings.getValue(Player.ONE).jinputController?.name, equalTo("pad-A"))
        assertThat(hub.bindings.getValue(Player.TWO).jinputController?.name, equalTo("pad-B"))
    }
}
