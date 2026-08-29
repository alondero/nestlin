package com.github.alondero.nestlin

import com.github.alondero.nestlin.input.InputDevice
import com.github.alondero.nestlin.testutil.TestRoms
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * Save-state format migration tests for v4 → v5 (issue: 2-player support),
 * v6 → v7 (issue #271, RA progress trailer), and v10 → v11 (issue #297,
 * APU $4017 deferred-reset pending state).
 *
 * v5 adds a `ports` sub-block recording the [InputDevice.DeviceType] of each
 * controller port. v4 files load with both ports defaulting to STANDARD_GAMEPAD.
 *
 * v7 appends an optional length-prefixed RetroAchievements runtime-progress
 * trailer (issue #271). v4–v6 files load unchanged with the runtime reset
 * against the restored emulator memory; v7+ files round-trip the progress
 * bytes through the [SaveState.ProgressCapture] / [SaveState.ProgressRestore]
 * callbacks. The dedicated v7 behaviour lives in
 * [com.github.alondero.nestlin.session.SaveStateProgressTest].
 *
 * v11 appends an optional pending-$4017-write payload to the APU/frame-counter
 * block (issue #297). v10 and earlier files have no trailing bytes and load
 * cleanly with the frame counter treated as having no pending write — same
 * observable behaviour as a v10 save loaded into v10 code, which dropped
 * the in-flight reset.
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
    fun `SaveState VERSION is 11 for the deferred $4017 reset pending write`() {
        // Sanity check — fails fast if someone bumps or forgets the version
        // migration. The kdoc on SaveState and the load() version branch both
        // hinge on this constant. v7 (issue #271) appends an optional
        // length-prefixed RA runtime-progress block to every save; v10 appends
        // the in-flight power/soft reset sequencer payload to the CPU block;
        // v11 appends the deferred-$4017 pending write to the frame-counter
        // block.
        assertThat(SaveState.VERSION, equalTo(11))
    }

    @Test
    fun `v10 save loads into v11 code without offset desync`() {
        // Issue #297: a v10 save has 4 frame-counter fields (mode, irqInhibit,
        // step, cyclesSinceReset). v11 always appends a trailing
        // "hasPending" boolean to the frame-counter block. Loading a v10
        // byte stream into the v11 FrameCounter.loadState must NOT try to
        // read the 5th field from the next subsystem's bytes — that would
        // corrupt the stream offset and shift every subsequent APU field
        // (Pulse 1 timer, Pulse 2 timer, Triangle timer, Noise timer, DMC
        // DMA state). The `version < 11` branch in FrameCounter.loadState
        // leaves `pendingMode = null` and skips the trailing read.
        //
        // Synthesise a v10-format byte stream from a populated FrameCounter
        // (non-default mode + non-zero step/cyclesSinceReset so a partial
        // read would visibly mis-round-trip), then load it back with
        // version=10. The active fields must round-trip; pendingModeForTest
        // must report null (the v10 file carries no pending write).
        val source = com.github.alondero.nestlin.apu.FrameCounter().apply {
            region = com.github.alondero.nestlin.Region.NTSC
            mode = com.github.alondero.nestlin.apu.FrameCounter.Mode.FIVE_STEP
            irqInhibit = true
            step = 2
            cyclesSinceReset = 17000
        }

        val v10Bytes = ByteArrayOutputStream().also { baos ->
            val dos = java.io.DataOutputStream(baos)
            dos.writeInt(source.mode.ordinal)
            dos.writeBoolean(source.irqInhibit)
            dos.writeInt(source.step)
            dos.writeInt(source.cyclesSinceReset)
        }.toByteArray()

        val restored = com.github.alondero.nestlin.apu.FrameCounter()
        restored.loadState(
            java.io.DataInputStream(ByteArrayInputStream(v10Bytes)),
            version = 10
        )
        assertThat(restored.mode, equalTo(source.mode))
        assertThat(restored.irqInhibit, equalTo(source.irqInhibit))
        assertThat(restored.step, equalTo(source.step))
        assertThat(restored.cyclesSinceReset, equalTo(source.cyclesSinceReset))
        assertNull(restored.pendingModeForTest(), "v10 save must load with no pending write")
        assertThat(restored.cyclesToResetForTest(), equalTo(0))
    }

    @Test
    fun `v11 save round-trips a queued pending $4017 write`() {
        // Companion to the v10→v11 migration test above. v11 saves carry
        // the deferred-reset pending state; a savestate taken mid-delay
        // (3 or 4 cycles after a $4017 write) must restore both the active
        // mode AND the pending mode so the in-flight reset still fires on
        // load.
        val source = com.github.alondero.nestlin.apu.FrameCounter().apply {
            region = com.github.alondero.nestlin.Region.NTSC
            mode = com.github.alondero.nestlin.apu.FrameCounter.Mode.FOUR_STEP
            // Queue a 5-step reset 2 cycles out (cpuCycle=0 → delay=3, 1
            // tick has already elapsed).
            write4017(0x80.toByte(), cpuCycle = 0)
            repeat(1) { tick() }
        }
        assertThat(source.mode, equalTo(com.github.alondero.nestlin.apu.FrameCounter.Mode.FOUR_STEP))
        assertThat(source.pendingModeForTest(), equalTo(com.github.alondero.nestlin.apu.FrameCounter.Mode.FIVE_STEP))
        assertThat(source.cyclesToResetForTest(), equalTo(2))

        val bytes = ByteArrayOutputStream().also { baos ->
            source.saveState(java.io.DataOutputStream(baos))
        }.toByteArray()

        val restored = com.github.alondero.nestlin.apu.FrameCounter()
        restored.loadState(java.io.DataInputStream(ByteArrayInputStream(bytes)))
        assertThat(restored.mode, equalTo(com.github.alondero.nestlin.apu.FrameCounter.Mode.FOUR_STEP))
        assertThat(restored.pendingModeForTest(), equalTo(com.github.alondero.nestlin.apu.FrameCounter.Mode.FIVE_STEP))
        assertThat(restored.cyclesToResetForTest(), equalTo(2))

        // Drain the restored delay; the deferred 5-step reset must fire on
        // the tick that decrements cyclesToReset to 0 (which is tick 2 of
        // the post-load advance — cyclesToReset starts at 2, ticks 1 and 2
        // drain it).
        val t1 = restored.tick()
        assertThat(t1.resetClock, equalTo(false))
        val resetTick = restored.tick()
        assertThat(restored.mode, equalTo(com.github.alondero.nestlin.apu.FrameCounter.Mode.FIVE_STEP))
        assertThat(resetTick.resetClock, equalTo(true))
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
