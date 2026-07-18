package com.github.alondero.nestlin.movie

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.file.load
import java.nio.file.Path

/**
 * Deterministically replays a [Movie] against a cold-booted ROM.
 *
 * This is the core of the "ROM + input log reproduces a bug" workflow: given the same ROM and the
 * same movie, [replay] always lands the machine in byte-identical state, because every source of
 * non-determinism is removed — throttling off, input quantised to frame boundaries, cold boot.
 */
class MoviePlayer {

    /**
     * Boot [romPath] cold and replay [movie] to completion, returning the [Nestlin] at the final
     * frame for inspection (save-state fingerprinting, or reading RAM at the bug site).
     *
     * @param verifyChecksum when true (default), throws if the ROM's FM2 checksum doesn't match the
     *   movie's `romChecksum` — catching the "replaying against the wrong ROM" mistake up front.
     */
    fun replay(romPath: Path, movie: Movie, verifyChecksum: Boolean = true): Nestlin {
        if (verifyChecksum && movie.romChecksum.isNotEmpty()) {
            val image = romPath.load() ?: error("Could not load ROM: $romPath")
            val actual = Fm2Format.romChecksum(image)
            require(actual == movie.romChecksum) {
                "ROM checksum mismatch: movie expects ${movie.romChecksum}, but $romPath hashes to $actual"
            }
        }
        val nestlin = Nestlin().apply {
            config.speedThrottlingEnabled = false
            load(romPath)
        }
        nestlin.powerReset()
        replayInto(nestlin, movie)
        return nestlin
    }

    /**
     * Replay [movie] into an already-booted [nestlin]. Each row's input is applied *before* its
     * frame is stepped, matching FM2's "input latched once per frame" model.
     *
     * Per-row ordering (issue #125):
     *
     *   1. Apply any `commands` bits the row carries via [MovieInput.applyCommands] —
     *      soft reset (bit 0) → [Nestlin.softReset], hard reset (bit 1) → [Nestlin.powerReset].
     *      Doing this BEFORE latching the input matches FCEUX's "reset fires, then the
     *      game's first NMI polls the new pad" semantics.
     *   2. Latch the row's input onto the two controller ports.
     *   3. Step one frame.
     */
    fun replayInto(nestlin: Nestlin, movie: Movie) {
        for (input in movie.inputs) {
            input.applyCommands(nestlin)
            nestlin.getController1().setButtonBitmap(input.controller1)
            nestlin.getController2().setButtonBitmap(input.controller2)
            nestlin.runOneFrame()
        }
    }
}
