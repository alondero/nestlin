package com.github.alondero.nestlin.movie

/**
 * In-memory representation of an FM2 movie: header metadata plus the per-frame input log.
 *
 * Row N of [inputs] is the controller state visible to the game during frame N. FM2 semantics latch
 * input once per frame, *before* the frame is emulated — and that frame-quantisation is precisely
 * what makes replay deterministic, because it removes the real-time jitter of *when* a key event
 * lands relative to the emulation thread.
 */
data class Movie(
    val romFilename: String,
    val romChecksum: String,
    val palFlag: Boolean = false,
    val rerecordCount: Int = 0,
    val guid: String = ZERO_GUID,
    val fourscore: Boolean = false,
    val port0: Int = PORT_GAMEPAD,
    val port1: Int = PORT_GAMEPAD,
    val port2: Int = PORT_NONE,
    val emuVersion: Int = NESTLIN_EMU_VERSION,
    val inputs: List<MovieInput> = emptyList(),
) {
    val length: Int get() = inputs.size

    companion object {
        /** FM2 file-format version; "for now it is always 3" per the FCEUX spec. */
        const val FM2_VERSION = 3
        /** Reported in the `emuVersion` header so movies are traceable to a Nestlin build. */
        const val NESTLIN_EMU_VERSION = 1
        const val ZERO_GUID = "00000000-0000-0000-0000-000000000000"
        /** FM2 port device codes: 1 = standard gamepad, 0 = nothing connected. */
        const val PORT_GAMEPAD = 1
        const val PORT_NONE = 0
    }
}
