package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Memory
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Truth-table tests for the NES [Zapper] light gun (issue #209).
 *
 * The `$4017` read layout is `OB OB OB T L 0 0 0`: D4 (0x10) is the trigger,
 * D3 (0x08) is the light sense, and D7-D5 float to the open-bus mask (0x40).
 *
 * **Light-sense polarity is hardware-correct, not the issue prose.** The issue
 * described a lit target as setting D3; real hardware is the opposite — nesdev
 * ("Light sensed: 0 detected, 1 not detected") and Mesen2's `Zapper.h`
 * (`IsLightFound() ? 0 : 0x08`) both clear D3 on a bright pixel and set it in the
 * dark. That is the polarity Duck Hunt's hit detection actually depends on, so
 * the four rows below encode it:
 *
 * | trigger | pixel  | $4017 |
 * |---------|--------|-------|
 * | off     | dark   | 0x48  |
 * | off     | bright | 0x40  |
 * | on      | dark   | 0x58  |
 * | on      | bright | 0x50  |
 */
class ZapperTest {

    private fun zapper(trigger: Boolean, bright: Boolean, isPort2: Boolean = true) =
        Zapper(triggerProvider = { trigger }, lightSensor = { bright }, isPort2 = isPort2)

    @Test
    fun `trigger off, dark - open-bus plus light-off bit (0x48)`() {
        assertThat(zapper(trigger = false, bright = false).read(), equalTo(0x48.toByte()))
    }

    @Test
    fun `trigger off, bright - open-bus only, light bit cleared (0x40)`() {
        assertThat(zapper(trigger = false, bright = true).read(), equalTo(0x40.toByte()))
    }

    @Test
    fun `trigger on, dark - open-bus plus trigger plus light-off (0x58)`() {
        assertThat(zapper(trigger = true, bright = false).read(), equalTo(0x58.toByte()))
    }

    @Test
    fun `trigger on, bright - open-bus plus trigger, light bit cleared (0x50)`() {
        assertThat(zapper(trigger = true, bright = true).read(), equalTo(0x50.toByte()))
    }

    @Test
    fun `peek matches read for every trigger and light combination`() {
        // The Zapper has no latched state, so the Memory Editor's side-effect-free
        // peek must return exactly what read would.
        for (trigger in listOf(false, true)) {
            for (bright in listOf(false, true)) {
                val z = zapper(trigger, bright)
                assertThat(z.peek(), equalTo(z.read()))
            }
        }
    }

    @Test
    fun `a port-1 Zapper reads 0 regardless of trigger and light`() {
        // The Zapper is conventionally a port-2 ($4017) device. A port-1 Zapper
        // (isPort2 = false) reads 0 so a mis-configured UI selection stays inert.
        assertThat(zapper(trigger = true, bright = true, isPort2 = false).read(), equalTo(0.toByte()))
        assertThat(zapper(trigger = false, bright = false, isPort2 = false).read(), equalTo(0.toByte()))
        assertThat(zapper(trigger = true, bright = true, isPort2 = false).peek(), equalTo(0.toByte()))
    }

    @Test
    fun `writeStrobe does not affect the next read - no shift register`() {
        // Toggling the shared $4016 strobe must not latch or shift anything: the
        // Zapper is polled, not clocked. Reads stay live samples of the providers.
        val z = zapper(trigger = true, bright = true)
        z.writeStrobe(true)
        z.writeStrobe(false)
        assertThat(z.read(), equalTo(0x50.toByte()))
    }

    @Test
    fun `read reflects live provider changes between polls`() {
        // A game polls the Zapper several times per shot; the reading must track
        // the provider state at each poll, not a construction-time snapshot.
        var trigger = false
        var bright = false
        val z = Zapper(triggerProvider = { trigger }, lightSensor = { bright })

        assertThat(z.read(), equalTo(0x48.toByte()))   // idle: dark, no trigger
        trigger = true
        assertThat(z.read(), equalTo(0x58.toByte()))   // trigger pulled, still dark
        bright = true
        assertThat(z.read(), equalTo(0x50.toByte()))   // now aimed at a lit target
    }

    @Test
    fun `Memory forwards providers to a Zapper plugged into port 2`() {
        // The end-to-end path: Application installs providers on Memory, then the
        // config screen swaps port 2 to a Zapper. $4017 reads must reflect the
        // providers Memory holds.
        var trigger = false
        var bright = false
        val memory = Memory()
        memory.setZapperProviders(trigger = { trigger }, light = { bright })
        memory.setPortType(1, InputDevice.DeviceType.ZAPPER)

        assertThat(memory[0x4017], equalTo(0x48.toByte()))
        trigger = true
        bright = true
        assertThat(memory[0x4017], equalTo(0x50.toByte()))
    }

    @Test
    fun `Memory picks up providers installed after the Zapper is plugged in`() {
        // Providers forward through Memory's fields on every read, so installing
        // them after the port swap still works — no re-plug required.
        var bright = false
        val memory = Memory()
        memory.setPortType(1, InputDevice.DeviceType.ZAPPER)   // plug first
        memory.setZapperProviders(trigger = { false }, light = { bright })  // install after

        assertThat(memory[0x4017], equalTo(0x48.toByte()))     // dark
        bright = true
        assertThat(memory[0x4017], equalTo(0x40.toByte()))     // bright: light bit cleared
    }
}
