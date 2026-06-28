package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller
import com.github.alondero.nestlin.Memory
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * Tests for the [InputDevice] abstraction and [Memory.setPortType] swap (Phase 1
 * of the 2-player support work). The canary for the whole refactor lives in
 * [com.github.alondero.nestlin.TwoControllerTest] — these tests pin the
 * NEW surface that the abstraction adds.
 */
class InputDeviceTest {

    @Test
    fun `StandardGamepad delegates read peek and writeStrobe to the wrapped Controller`() {
        // Build two parallel Controller instances in the same state; one wrapped
        // in StandardGamepad and one accessed directly. Each side gets a single
        // read/peek and the bytes must agree — proving StandardGamepad is a thin
        // pass-through with no extra state.
        fun fixture(): Pair<Byte, Byte> {
            val controllerA = Controller()
            val controllerB = Controller()
            controllerA.setButton(Controller.Button.A, true)
            controllerB.setButton(Controller.Button.A, true)
            val deviceA: InputDevice = StandardGamepad(controllerA)
            controllerB.write(1); controllerB.write(0)
            deviceA.writeStrobe(true); deviceA.writeStrobe(false)
            return deviceA.read() to controllerB.read()
        }

        val (viaDevice, direct) = fixture()
        assertThat(viaDevice, equalTo(direct))
    }

    @Test
    fun `Zapper returns open-bus-only byte on read and peek`() {
        val zapper: InputDevice = Zapper()

        // No buttons pressed — there's nothing to latch, but the open-bus mask
        // is the canonical "device idle" return value.
        assertThat(zapper.read(), equalTo(0x40.toByte()))
        assertThat(zapper.peek(), equalTo(0x40.toByte()))
    }

    @Test
    fun `Zapper writeStrobe is a no-op`() {
        // The Zapper ignores the shared strobe signal — there is no shift register
        // to reload. Pin that here so a future change that wires strobe into the
        // Zapper doesn't silently break the contract.
        val zapper: InputDevice = Zapper()
        zapper.writeStrobe(true)
        zapper.writeStrobe(false)

        // Reads still return open-bus-only.
        assertThat(zapper.read(), equalTo(0x40.toByte()))
    }

    @Test
    fun `NoDevice returns open-bus-only byte on read and peek`() {
        val empty: InputDevice = NoDevice()

        assertThat(empty.read(), equalTo(0x40.toByte()))
        assertThat(empty.peek(), equalTo(0x40.toByte()))
    }

    @Test
    fun `NoDevice writeStrobe is a no-op`() {
        val empty: InputDevice = NoDevice()
        empty.writeStrobe(true)
        empty.writeStrobe(false)

        assertThat(empty.read(), equalTo(0x40.toByte()))
    }

    @Test
    fun `Memory ports default to StandardGamepad`() {
        val memory = Memory()
        assertThat(memory.portType(0), equalTo(InputDevice.DeviceType.STANDARD_GAMEPAD))
        assertThat(memory.portType(1), equalTo(InputDevice.DeviceType.STANDARD_GAMEPAD))
        // Type-check: the port fields are actually StandardGamepad at construction.
        assertThat(memory.port1 is StandardGamepad, equalTo(true))
        assertThat(memory.port2 is StandardGamepad, equalTo(true))
    }

    @Test
    fun `setPortType swaps the device and portType reads back the new type`() {
        val memory = Memory()

        memory.setPortType(0, InputDevice.DeviceType.NONE)
        assertThat(memory.portType(0), equalTo(InputDevice.DeviceType.NONE))
        assertThat(memory.port1 is NoDevice, equalTo(true))

        memory.setPortType(0, InputDevice.DeviceType.ZAPPER)
        assertThat(memory.portType(0), equalTo(InputDevice.DeviceType.ZAPPER))
        assertThat(memory.port1 is Zapper, equalTo(true))

        memory.setPortType(0, InputDevice.DeviceType.STANDARD_GAMEPAD)
        assertThat(memory.portType(0), equalTo(InputDevice.DeviceType.STANDARD_GAMEPAD))
        assertThat(memory.port1 is StandardGamepad, equalTo(true))
    }

    @Test
    fun `setPortType preserves the underlying controller when swapping away from and back to StandardGamepad`() {
        // Pin that a standard→none→standard round-trip keeps the Controller
        // reference stable — that's what lets save state and movies reference
        // the same controller instance regardless of which device is plugged in.
        val memory = Memory()
        val original = memory.controller1

        memory.setPortType(0, InputDevice.DeviceType.NONE)
        memory.setPortType(0, InputDevice.DeviceType.STANDARD_GAMEPAD)

        assertThat(memory.controller1 === original, equalTo(true))
    }

    @Test
    fun `Memory 0x4016 read returns open-bus-only after a port is set to NoDevice`() {
        val memory = Memory()
        memory.setPortType(0, InputDevice.DeviceType.NONE)

        // Even with no buttons pressed on port 2, port 1 should now report
        // 0x40 on every $4016 read (open-bus mask only).
        repeat(8) {
            assertThat(memory[0x4016], equalTo(0x40.toByte()))
        }
    }

    @Test
    fun `Memory 0x4017 read returns open-bus-only after port 2 is set to Zapper`() {
        val memory = Memory()
        memory.setPortType(1, InputDevice.DeviceType.ZAPPER)

        repeat(8) {
            assertThat(memory[0x4017], equalTo(0x40.toByte()))
        }
    }

    @Test
    fun `Memory 0x4016 write still strobes the other port when one port is a stub device`() {
        // Hardware-accurate shared strobe must be preserved even when one port
        // is a no-op (Zapper/NoDevice). The StandardGamepad on the other port
        // must still see the strobe.
        val memory = Memory()
        memory.setPortType(0, InputDevice.DeviceType.NONE)
        memory.controller2.setButton(Controller.Button.A, true)

        memory[0x4016] = 1
        memory[0x4016] = 0

        // Port 2 (StandardGamepad) sees A pressed on its first read.
        assertThat(memory[0x4017].toInt() and 0x01, equalTo(1))
    }
}
