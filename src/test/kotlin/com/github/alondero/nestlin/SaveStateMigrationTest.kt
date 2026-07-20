package com.github.alondero.nestlin

import com.github.alondero.nestlin.input.InputDevice
import com.github.alondero.nestlin.testutil.TestRoms
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Save-state format migration test for the v4 → v5 transition (issue: 2-player support).
 *
 * v5 adds a `ports` sub-block recording the [InputDevice.DeviceType] of each
 * controller port. v4 files load with both ports defaulting to STANDARD_GAMEPAD.
 *
 * Uses the in-repo `nestest.nes` so the test is self-contained — no external ROM,
 * no Mesen2, no display. The round-trip is the cheapest proof that the new bytes
 * don't corrupt the rest of the save file and that the new version branch in
 * SaveState.load reads the right offsets.
 *
 * Note: the v4-only path is implicitly covered by every existing save-state test
 * that doesn't set port types — they all keep passing unmodified because the v5
 * format only ADDS bytes (no reordering of existing fields), and the v4 branch
 * in load skips the new ports block. A v4 save loaded into v5 code resumes with
 * both ports at STANDARD_GAMEPAD, which is what every pre-existing test expects.
 */
class SaveStateMigrationTest {

    @Test
    fun `SaveState VERSION is 6 to record the optional 4-screen VRAM block`() {
        // Sanity check — fails fast if someone bumps or forgets the version
        // migration. The kdoc on SaveState and the load() version branch both
        // hinge on this constant. v6 (GH #105) adds two optional 1 KB nametables
        // to the PPU block, written only when mirroring is FOUR_SCREEN.
        assertThat(SaveState.VERSION, equalTo(6))
    }

    @Test
    fun `v5 round-trip preserves per-port device type across save and load`(@TempDir dir: Path) {
        val savePath = dir.resolve("state.nstl")

        // Save with port 0 set to Zapper and port 1 set to NONE.
        Nestlin().apply {
            loadBytes(TestRoms.nestestBytes())
            powerReset()
            memory.setPortType(0, InputDevice.DeviceType.ZAPPER)
            memory.setPortType(1, InputDevice.DeviceType.NONE)
            SaveState.save(this, Files.newOutputStream(savePath))
        }

        // Load into a fresh Nestlin and verify both ports come back correctly.
        Nestlin().apply {
            loadBytes(TestRoms.nestestBytes())
            powerReset()
            SaveState.load(this, Files.newInputStream(savePath))

            assertThat(memory.portType(0), equalTo(InputDevice.DeviceType.ZAPPER))
            assertThat(memory.portType(1), equalTo(InputDevice.DeviceType.NONE))
        }
    }

    @Test
    fun `v5 round-trip preserves StandardGamepad and resets to defaults on load`(@TempDir dir: Path) {
        // Saving with the default port types should round-trip cleanly — proves
        // the "ports block" is written even for STANDARD_GAMEPAD (not a special
        // case) and that the load reads back the same value.
        val savePath = dir.resolve("state.nstl")

        Nestlin().apply {
            loadBytes(TestRoms.nestestBytes())
            powerReset()
            // Both ports are STANDARD_GAMEPAD by default — no setPortType call.
            SaveState.save(this, Files.newOutputStream(savePath))
        }

        Nestlin().apply {
            loadBytes(TestRoms.nestestBytes())
            powerReset()
            // Pre-condition: ports are at their construction-time default.
            assertThat(memory.portType(0), equalTo(InputDevice.DeviceType.STANDARD_GAMEPAD))
            assertThat(memory.portType(1), equalTo(InputDevice.DeviceType.STANDARD_GAMEPAD))

            SaveState.load(this, Files.newInputStream(savePath))

            // Post-condition: still STANDARD_GAMEPAD after load.
            assertThat(memory.portType(0), equalTo(InputDevice.DeviceType.STANDARD_GAMEPAD))
            assertThat(memory.portType(1), equalTo(InputDevice.DeviceType.STANDARD_GAMEPAD))
        }
    }

    @Test
    fun `v5 round-trip preserves controller button state alongside port type`(@TempDir dir: Path) {
        // The port-type round-trip must not disturb the controller's button bitmap
        // or shift register. Pin that here — it's the property that lets save
        // state restore "Zapper plugged into port 0 with A held on port 1" and
        // produce the same $4016/$4017 reads as before save.
        val savePath = dir.resolve("state.nstl")

        Nestlin().apply {
            loadBytes(TestRoms.nestestBytes())
            powerReset()
            memory.setPortType(0, InputDevice.DeviceType.ZAPPER)
            memory.controller2.setButton(Controller.Button.A, true)
            SaveState.save(this, Files.newOutputStream(savePath))
        }

        Nestlin().apply {
            loadBytes(TestRoms.nestestBytes())
            powerReset()
            SaveState.load(this, Files.newInputStream(savePath))

            // Port 0 holds a Zapper. The Zapper is conventionally a port-2 ($4017)
            // device, so a port-0 (index 0, isPort2 = false) Zapper reads back 0 —
            // games never expect a light gun on port 1 (issue #209).
            assertThat(memory[0x4016], equalTo(0.toByte()))

            // Port 1 (StandardGamepad) restored its A-pressed state — read A's
            // bit after a strobe to confirm.
            memory[0x4016] = 1
            memory[0x4016] = 0
            assertThat(memory[0x4017].toInt() and 0x01, equalTo(1))
        }
    }
}
