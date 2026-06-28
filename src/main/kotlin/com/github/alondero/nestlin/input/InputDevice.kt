package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller
import com.github.alondero.nestlin.StrobeRegister
import java.io.DataInput
import java.io.DataOutput

/**
 * The thing plugged into a controller port on the NES console (issue: 2-player support).
 *
 * Vocabulary: in NES terminology, the **port** is the physical socket on the front of
 * the console; an **input device** is the thing that plugs into that socket. The NES
 * shipped with a standard gamepad, but the port protocol is general enough to support
 * other devices (Zapper light gun, R.O.B. robot, Four-Score / Satellite multi-adapter,
 * etc.). Each device speaks a different read protocol when the CPU polls `$4016`/`$4017`,
 * which is what this interface abstracts.
 *
 * The name deliberately mirrors nothing in this codebase. `InputSource` (which already
 * exists in `input/`) is a different layer: `InputSource` answers "who feeds the
 * controller *data* this frame" (live keyboard, live gamepad, pending buffer, etc.)
 * while `InputDevice` answers "what physical thing is plugged into the console". The
 * two coexist cleanly — a [StandardGamepad] still holds an [InputSource] for its
 * data path.
 *
 * Contract for implementors:
 *  - [read] and [peek] return the open-bus-OR'd byte (`0x40` mask from [StrobeRegister.OPEN_BUS_MASK]).
 *    Memory does not add the open-bus mask; each device applies its own convention.
 *  - [writeStrobe] receives a single resolved bit (the LSB of the byte written to
 *    `$4016`). Devices that ignore strobe (e.g. Zapper) should no-op.
 *  - [saveState] / [loadState] persist enough state for the device to round-trip
 *    through a save-state save/load cycle.
 */
sealed interface InputDevice {

    /**
     * Read the next data byte from `$4016`/`$4017` (advances shift register for
     * standard pads). Returns the data bit OR'd with the open-bus mask.
     */
    fun read(): Byte

    /**
     * Side-effect-free counterpart of [read]: returns the same byte [read] would
     * return now without advancing any internal state. Used by the Memory Editor
     * (issue #168) so a debug viewer can poll `$4016`/`$4017` without desyncing
     * the game's input polling.
     */
    fun peek(): Byte

    /**
     * Notify the device that the shared `$4016` strobe signal is now [bit] (high
     * or low). For a [StandardGamepad] this reloads the shift register from the
     * current button bitmap (the "transparent latch" rule); for a [Zapper] it is
     * a no-op.
     */
    fun writeStrobe(bit: Boolean)

    /** Persist the device's per-port state through a save-state save. */
    fun saveState(out: DataOutput)

    /** Restore the device's per-port state from a save-state load. */
    fun loadState(input: DataInput)

    /**
     * What kind of device is plugged into the port. Persisted in the save-state
     * format (v5+). Adding a new device type means adding a variant here and a
     * case in [Memory.setPortType]; no other files need to change.
     */
    enum class DeviceType(val storageKey: String) {
        NONE("none"),
        STANDARD_GAMEPAD("standard"),
        ZAPPER("zapper"),
    }
}

/**
 * The standard NES controller: an 8-button digital pad with a serial shift
 * register / strobe protocol. This is a thin wrapper around the existing
 * [Controller] class — that class already encapsulates [StrobeRegister] +
 * [InputSource] + the pending-buffer dance the movie recorder depends on.
 *
 * Wrapping rather than re-implementing keeps all existing call sites of
 * `Memory.controller1` / `Memory.controller2` (save state, movie recorder,
 * movie player, replay CLI, Application) working without change.
 */
class StandardGamepad(val controller: Controller) : InputDevice {

    override fun read(): Byte = controller.read()

    override fun peek(): Byte = controller.peek()

    override fun writeStrobe(bit: Boolean) {
        // Controller.write takes the raw byte; the LSB is the strobe signal.
        // We pass through a fully-formed byte so the Controller's own logging
        // and contract are unchanged.
        controller.write(if (bit) 1.toByte() else 0.toByte())
    }

    override fun saveState(out: DataOutput) = controller.saveState(out)

    override fun loadState(input: DataInput) = controller.loadState(input)
}

/**
 * Shared implementation for devices that don't speak the standard NES pad
 * protocol: every read/peek returns the open-bus mask (`0x40`); the strobe
 * signal is a no-op; save/load have no per-device state yet.
 *
 * Both [Zapper] (light gun stub) and [NoDevice] (nothing plugged in) extend
 * this — they currently behave identically from the CPU's perspective. When
 * the Zapper semantics land (trigger bit on `$4017` D4, light sense on `$4017`
 * D3), only [Zapper] will override `read`/`peek`; [NoDevice] stays as-is.
 */
/**
 * Shared implementation for devices that don't speak the standard NES pad
 * protocol: every read/peek returns the open-bus mask (`0x40`); the strobe
 * signal is a no-op; save/load have no per-device state yet.
 *
 * Both [Zapper] (light gun stub) and [NoDevice] (nothing plugged in) extend
 * this — they currently behave identically from the CPU's perspective. When
 * the Zapper semantics land (trigger bit on `$4017` D4, light sense on `$4017`
 * D3), only [Zapper] will override `read`/`peek`; [NoDevice] stays as-is.
 *
 * Marked abstract so callers can't instantiate it directly — the only valid
 * concrete types are [Zapper] and [NoDevice].
 */
abstract class OpenBusOnlyDevice : InputDevice {
    override fun read(): Byte = StrobeRegister.OPEN_BUS_MASK.toByte()
    override fun peek(): Byte = StrobeRegister.OPEN_BUS_MASK.toByte()
    override fun writeStrobe(bit: Boolean) {
        // Strobe is a no-op for non-standard-pad devices (the Zapper is polled,
        // not strobed; NoDevice has nothing to latch).
    }
    override fun saveState(out: DataOutput) {
        // No state yet.
    }
    override fun loadState(input: DataInput) {
        // No state yet.
    }
}

/**
 * Zapper light gun — stub. The actual trigger-bit / light-sense semantics
 * (trigger = `$4017` D4; light sense = `$4017` D3; `$4016` reads always return
 * 0 for the zapper bits) are a future PR. For now, reads return the open-bus
 * mask (`0x40`) so a Zapper-plugged port behaves like an empty port from the
 * game's perspective.
 */
class Zapper : OpenBusOnlyDevice()

/**
 * Nothing is plugged into this port. Reads return the open-bus mask so the
 * game sees a permanently-unpressed pad. Useful for games that detect a
 * disconnected controller (e.g. via an all-`0x40` read at boot).
 */
class NoDevice : OpenBusOnlyDevice()
