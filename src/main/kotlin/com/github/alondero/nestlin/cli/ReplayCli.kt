package com.github.alondero.nestlin.cli

import java.nio.file.Path
import java.nio.file.Paths

/**
 * Argument parsing + stdout wiring for the headless `replay` subcommand. Kept separate from
 * [ReplayCommand] (the emulation/hashing logic) so the latter stays trivially unit-testable with
 * fabricated [ReplayCommand.Options] and an in-memory [Appendable], and separate from
 * `Application.main` so the JavaFX file never grows CLI-parsing responsibilities.
 *
 * Grammar:
 *
 *     nestlin replay <rom.nes> <movie.fm2>
 *         [--frame N]              replay only the first N input frames (capture a mid-movie frame)
 *         [--png PATH]             where to write the captured frame (default: <movie>.png)
 *         [--expect-state HEX]     assert the state hash; verify mode
 *         [--expect-frame HEX]     assert the frame hash; verify mode
 *         [--no-verify-checksum]   skip the ROM-vs-movie checksum guard
 */
object ReplayCli {

    val USAGE = """
        usage: nestlin replay <rom.nes> <movie.fm2> [options]

          --frame N             replay only the first N input frames
          --png PATH            output PNG path (default: <movie>.png)
          --expect-state HEX    assert state hash matches (verify mode; exit 1 on mismatch)
          --expect-frame HEX    assert frame hash matches (verify mode; exit 1 on mismatch)
          --no-verify-checksum  skip the ROM/movie checksum guard

        With no --expect-* the command records: it prints state=/frame= hashes and writes a PNG.
        With --expect-* it verifies: exit 0 on a byte-identical reproduction, 1 otherwise.
    """.trimIndent()

    /** A parsed argument vector: either runnable [Options] or a usage [error]. */
    sealed interface Parsed {
        data class Ok(val options: ReplayCommand.Options) : Parsed
        data class Error(val message: String) : Parsed
    }

    /** Entry point used by `main`. [args] excludes the leading `replay` token. */
    fun main(args: List<String>, out: Appendable = System.out): Int =
        when (val parsed = parse(args)) {
            is Parsed.Error -> {
                out.appendLine("ERROR: ${parsed.message}")
                out.appendLine(USAGE)
                ReplayCommand.EXIT_USAGE
            }
            is Parsed.Ok -> ReplayCommand.run(parsed.options, out).exitCode
        }

    fun parse(args: List<String>): Parsed {
        val positional = mutableListOf<String>()
        var frame: Int? = null
        var png: Path? = null
        var expectState: String? = null
        var expectFrame: String? = null
        var verifyChecksum = true

        var i = 0
        fun value(): String? {
            if (i + 1 >= args.size) return null
            return args[++i]
        }
        while (i < args.size) {
            val arg = args[i]
            when (arg) {
                "--frame" -> {
                    val v = value() ?: return Parsed.Error("$arg requires a value")
                    frame = v.toIntOrNull() ?: return Parsed.Error("$arg must be an integer, got '$v'")
                    if (frame < 0) return Parsed.Error("$arg must be >= 0")
                }
                "--png" -> png = (value() ?: return Parsed.Error("$arg requires a value")).let(Paths::get)
                "--expect-state" -> expectState = value() ?: return Parsed.Error("$arg requires a value")
                "--expect-frame" -> expectFrame = value() ?: return Parsed.Error("$arg requires a value")
                "--no-verify-checksum" -> verifyChecksum = false
                else -> {
                    if (arg.startsWith("--")) return Parsed.Error("unknown option '$arg'")
                    positional.add(arg)
                }
            }
            i++
        }

        if (positional.size != 2) {
            return Parsed.Error("expected <rom.nes> <movie.fm2>, got ${positional.size} positional argument(s)")
        }
        return Parsed.Ok(
            ReplayCommand.Options(
                romPath = Paths.get(positional[0]),
                moviePath = Paths.get(positional[1]),
                pngPath = png,
                frameLimit = frame,
                expectState = expectState,
                expectFrame = expectFrame,
                verifyChecksum = verifyChecksum,
            ),
        )
    }
}
