package com.github.alondero.nestlin.cli

import com.github.alondero.nestlin.Nestlin
import com.github.alondero.nestlin.file.load
import com.github.alondero.nestlin.movie.Fm2Format
import com.github.alondero.nestlin.movie.Movie
import com.github.alondero.nestlin.movie.runOneFrame
import com.github.alondero.nestlin.ppu.Frame
import com.github.alondero.nestlin.ppu.RESOLUTION_HEIGHT
import com.github.alondero.nestlin.ppu.RESOLUTION_WIDTH
import com.github.alondero.nestlin.ui.FrameListener
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.imageio.ImageIO

/**
 * Headless `replay` command: boot a ROM cold, replay an FM2 input log deterministically, and emit
 * a reproducible fingerprint of the resulting machine state plus a PNG of the frame reached.
 *
 * This is the agentic core of issue #62 ("replay-as-test"). The whole point is that an agent — or
 * CI — can run *one* command with a ROM and a shared `.fm2` and either:
 *
 *  - **record** the state the bug lands in (no `--expect-*`): prints the hashes and writes a PNG so
 *    a human can confirm "yes, that's the freeze the user reported", then commits the hash; or
 *  - **verify** against a previously-recorded hash (`--expect-state` / `--expect-frame`): exits 0 on
 *    a byte-identical reproduction, 1 with a diff line otherwise — a regression gate.
 *
 * Determinism comes entirely from [MoviePlayer]/[runOneFrame]: throttling off, cold boot, input
 * latched once per frame. No wall clock, no threads, no display — so the same (ROM, FM2) pair lands
 * in byte-identical state on every machine, which is what makes the printed hash a durable contract.
 */
object ReplayCommand {

    /** Exit codes are part of the CLI contract — CI and agents branch on them. */
    const val EXIT_OK = 0
    const val EXIT_MISMATCH = 1
    const val EXIT_USAGE = 2
    const val EXIT_ERROR = 3   // the emulator threw mid-replay (crash, unimplemented mapper, …)

    data class Options(
        val romPath: Path,
        val moviePath: Path,
        /** Where to write the captured frame. Null → `<movie>.png` beside the movie. */
        val pngPath: Path?,
        /** Replay only the first N input rows (capture a mid-movie frame). Null → whole movie. */
        val frameLimit: Int?,
        /** Expected `state=` hash; presence switches the command into verify mode. */
        val expectState: String?,
        /** Expected `frame=` hash; presence switches the command into verify mode. */
        val expectFrame: String?,
        /** Verify the ROM's FM2 checksum matches the movie's `romChecksum` before replaying. */
        val verifyChecksum: Boolean,
    )

    data class Outcome(
        val exitCode: Int,
        val frames: Int,
        val stateHash: String,
        val frameHash: String,
        val pngPath: Path,
        /** null in record mode; true/false in verify mode. */
        val matched: Boolean?,
    )

    /**
     * Run the command, writing human-readable progress/result lines to [out]. Returns an [Outcome]
     * carrying the exit code so the caller (`main`) can `System.exit` and tests can assert on it
     * without parsing stdout.
     */
    fun run(opts: Options, out: Appendable): Outcome {
        val movie = Fm2Format.read(Files.readString(opts.moviePath))

        // Fail fast — and distinctly (EXIT_USAGE, not EXIT_MISMATCH) — if the ROM on disk isn't the
        // one the movie was recorded against. Replaying the wrong ROM produces a meaningless hash.
        if (opts.verifyChecksum && movie.romChecksum.isNotEmpty()) {
            val image = opts.romPath.load() ?: return usage(out, "Could not load ROM: ${opts.romPath}")
            val actual = Fm2Format.romChecksum(image)
            if (actual != movie.romChecksum) {
                return usage(
                    out,
                    "ROM checksum mismatch: movie expects ${movie.romChecksum}, " +
                        "but ${opts.romPath} hashes to $actual",
                )
            }
        }

        val playedFrames = opts.frameLimit?.coerceAtMost(movie.length) ?: movie.length

        // A throw here (unimplemented mapper, a crash partway through replay) is itself a
        // reproduction signal — surface it as a clean EXIT_ERROR with a one-line message rather
        // than letting a stack trace escape `main`. A *hang*, by contrast, does not throw: it
        // replays to the end and lands in a frozen frame, which is a normal EXIT_OK whose PNG/hash
        // reveal the bug. That asymmetry is intentional.
        val frame: Frame
        val stateHash: String
        try {
            frame = replay(opts, movie)
            stateHash = stateHash(opts, movie)
        } catch (t: Throwable) {
            out.appendLine("ERROR: emulator threw while replaying ${opts.moviePath}: ${t.message ?: t.javaClass.simpleName}")
            return Outcome(EXIT_ERROR, playedFrames, "", "", Path.of("."), null)
        }

        val pngPath = opts.pngPath ?: defaultPngPath(opts.moviePath)
        writePng(frame, pngPath)
        val frameHash = frameHash(frame)

        out.appendLine("rom=${opts.romPath}")
        out.appendLine("movie=${opts.moviePath}")
        out.appendLine("frames=$playedFrames")
        out.appendLine("state=$stateHash")
        out.appendLine("frame=$frameHash")
        out.appendLine("png=$pngPath")

        val matched = verify(opts, stateHash, frameHash, out)
        val exit = when (matched) {
            null -> EXIT_OK
            true -> EXIT_OK
            false -> EXIT_MISMATCH
        }
        return Outcome(exit, playedFrames, stateHash, frameHash, pngPath, matched)
    }

    /**
     * Boot cold and replay, returning the [Frame] reached. The frame object is mutated in place by
     * the PPU and never reallocated, so holding the reference and reading it once replay has stopped
     * yields the final frame's pixels. We re-derive the booted machine in [stateHash] rather than
     * threading the [Nestlin] out, keeping this method's single responsibility "produce the frame".
     */
    private fun replay(opts: Options, movie: Movie): Frame {
        val nestlin = boot(opts.romPath)
        val captured = FrameCapture().also { nestlin.addFrameListener(it) }
        replayInto(nestlin, movie, opts.frameLimit)
        return captured.last ?: Frame() // a 0-frame movie yields a blank frame rather than crashing
    }

    /**
     * Re-run the replay to serialise the final save state. A second cold boot is cheap relative to
     * the clarity of not carrying mutable emulator state across the frame-capture and hashing steps;
     * determinism guarantees both runs land identically.
     */
    private fun stateHash(opts: Options, movie: Movie): String {
        val nestlin = boot(opts.romPath)
        replayInto(nestlin, movie, opts.frameLimit)
        val buf = ByteArrayOutputStream()
        nestlin.saveState(buf)
        return sha256Hex(buf.toByteArray())
    }

    private fun boot(romPath: Path): Nestlin = Nestlin().apply {
        config.speedThrottlingEnabled = false
        load(romPath)
        powerReset()
    }

    /** Replay [movie], honouring an optional [frameLimit] (first N input rows). */
    private fun replayInto(nestlin: Nestlin, movie: Movie, frameLimit: Int?) {
        val rows = if (frameLimit != null) movie.inputs.take(frameLimit) else movie.inputs
        for (input in rows) {
            nestlin.getController1().setButtonBitmap(input.controller1)
            nestlin.getController2().setButtonBitmap(input.controller2)
            nestlin.runOneFrame()
        }
    }

    /** Compare against the expected hashes; null when no expectation was supplied (record mode). */
    private fun verify(opts: Options, stateHash: String, frameHash: String, out: Appendable): Boolean? {
        if (opts.expectState == null && opts.expectFrame == null) return null
        var ok = true
        if (opts.expectState != null && opts.expectState != stateHash) {
            out.appendLine("MISMATCH state: expected ${opts.expectState} got $stateHash")
            ok = false
        }
        if (opts.expectFrame != null && opts.expectFrame != frameHash) {
            out.appendLine("MISMATCH frame: expected ${opts.expectFrame} got $frameHash")
            ok = false
        }
        if (ok) out.appendLine("OK: replay matches expected hashes")
        return ok
    }

    private fun usage(out: Appendable, message: String): Outcome {
        out.appendLine("ERROR: $message")
        return Outcome(EXIT_USAGE, 0, "", "", Path.of("."), null)
    }

    private fun defaultPngPath(moviePath: Path): Path {
        val name = moviePath.fileName.toString().substringBeforeLast('.')
        val parent = moviePath.parent ?: Path.of(".")
        return parent.resolve("$name.png")
    }

    /** Single-slot frame listener that keeps the most recently completed frame. */
    private class FrameCapture : FrameListener {
        var last: Frame? = null
        override fun frameUpdated(frame: Frame) { last = frame }
    }

    private fun frameHash(frame: Frame): String {
        val md = MessageDigest.getInstance("SHA-256")
        val row = ByteArray(RESOLUTION_WIDTH * 3)
        for (y in 0 until RESOLUTION_HEIGHT) {
            var i = 0
            val line = frame.scanlines[y]
            for (x in 0 until RESOLUTION_WIDTH) {
                val rgb = line[x]
                row[i++] = (rgb ushr 16).toByte()
                row[i++] = (rgb ushr 8).toByte()
                row[i++] = rgb.toByte()
            }
            md.update(row)
        }
        return md.digest().toHex()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = buildString(size * 2) {
        for (b in this@toHex) {
            val v = b.toInt() and 0xFF
            append(HEX[v ushr 4])
            append(HEX[v and 0x0F])
        }
    }

    private fun writePng(frame: Frame, path: Path) {
        val image = BufferedImage(RESOLUTION_WIDTH, RESOLUTION_HEIGHT, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until RESOLUTION_HEIGHT) {
            for (x in 0 until RESOLUTION_WIDTH) {
                image.setRGB(x, y, frame.scanlines[y][x])
            }
        }
        path.parent?.let { Files.createDirectories(it) }
        ImageIO.write(image, "PNG", path.toFile())
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
