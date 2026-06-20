package com.github.alondero.nestlin.input

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * The Controller Configuration screen needs to bind gamepad inputs that JInput reports
 * as buttons, axes (analog sticks), or POV hats. [GamepadBinding] is the sealed type
 * that unifies those three sources so the editor and runtime can treat them uniformly.
 *
 * Storage is a single canonical string so [GamepadConfig] can keep its JSON shape friendly
 * to gson (a `Map<String, String>` of `storageKey -> NES button name`) and to round-trip
 * through `input.json` without bespoke serializers.
 */
class GamepadBindingTest {

    @Test
    fun `button index storage key is btn-prefixed numeric`() {
        assertThat(GamepadBinding.ButtonIndex(0).storageKey, equalTo("btn:0"))
        assertThat(GamepadBinding.ButtonIndex(11).storageKey, equalTo("btn:11"))
    }

    @Test
    fun `axis storage key encodes component and sign`() {
        // X-axis positive (right), Y-axis negative (up), and the lowercase-normalised form
        // — the matching layer in GamepadInput normalises both before lookup.
        val right = GamepadBinding.Axis("x", AxisDirection.POSITIVE)
        val up = GamepadBinding.Axis("y", AxisDirection.NEGATIVE)
        assertThat(right.storageKey, equalTo("axis:x:pos"))
        assertThat(up.storageKey, equalTo("axis:y:neg"))
    }

    @Test
    fun `pov storage key encodes component and direction name`() {
        // The 8 cardinal+intercardinal compass directions, plus center (off).
        val n = GamepadBinding.Pov("pov", PovDirection.N)
        val ne = GamepadBinding.Pov("pov", PovDirection.NE)
        val center = GamepadBinding.Pov("pov", PovDirection.CENTER)
        assertThat(n.storageKey, equalTo("pov:pov:n"))
        assertThat(ne.storageKey, equalTo("pov:pov:ne"))
        assertThat(center.storageKey, equalTo("pov:pov:center"))
    }

    @Test
    fun `parse round-trips every binding variant`() {
        listOf(
            GamepadBinding.ButtonIndex(0),
            GamepadBinding.ButtonIndex(11),
            GamepadBinding.Axis("x", AxisDirection.POSITIVE),
            GamepadBinding.Axis("rx", AxisDirection.NEGATIVE),
            GamepadBinding.Pov("pov", PovDirection.N),
            GamepadBinding.Pov("hat 0", PovDirection.SE),
        ).forEach { binding ->
            val parsed = GamepadBinding.fromStorageKey(binding.storageKey)
            assertThat("round-trip failed for $binding", parsed, equalTo(binding))
        }
    }

    @Test
    fun `parse returns null on unknown storage key`() {
        assertThat(GamepadBinding.fromStorageKey("garbage"), equalTo(null))
        assertThat(GamepadBinding.fromStorageKey(""), equalTo(null))
        assertThat(GamepadBinding.fromStorageKey("btn:notanumber"), equalTo(null))
    }

    @Test
    fun `display names describe the binding for the config UI legend`() {
        assertThat(GamepadBinding.ButtonIndex(7).displayName, equalTo("pad 7"))
        assertThat(GamepadBinding.Axis("x", AxisDirection.POSITIVE).displayName, equalTo("axis x+"))
        assertThat(GamepadBinding.Axis("y", AxisDirection.NEGATIVE).displayName, equalTo("axis y-"))
        assertThat(GamepadBinding.Pov("pov", PovDirection.N).displayName, equalTo("pov N"))
        assertThat(GamepadBinding.Pov("hat 0", PovDirection.SE).displayName, equalTo("hat 0 SE"))
    }
}
