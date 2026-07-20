package com.github.alondero.nestlin.cli

import com.github.alondero.nestlin.movie.Fm2Format
import com.github.alondero.nestlin.movie.Movie
import com.github.alondero.nestlin.movie.MovieInput
import com.github.alondero.nestlin.testutil.TestRoms
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.containsSubstring
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * Exercises the headless `replay` command — the agentic "ROM + FM2 reproduces a bug" loop
 * (issue #62). Uses the in-git `nestest.nes` so the test is self-contained: no external ROM,
 * no Mesen2, no display. A synthetic all-buttons-released movie is enough to prove the
 * record/verify contract; the determinism guarantee is the same one a real bug repro relies on.
 */
class ReplayCommandTest {

    // ReplayCommand.Options takes a real Path (it must pass it on to BootCheck / checksum
    // hashing that opens the file). Use the classpath-derived temp path so the lookup
    // is hermetic from any working directory. See issue #21.
    private val nestest: Path = TestRoms.nestestPath()

    /** Write a {@code frames}-long FM2 of empty input next to {@code dir}. */
    private fun writeMovie(dir: Path, frames: Int, checksum: String = ""): Path {
        val movie = Movie(
            romFilename = "nestest.nes",
            romChecksum = checksum,
            inputs = (0 until frames).map { MovieInput(0, 0, 0) },
        )
        val path = dir.resolve("nestest.fm2")
        Files.writeString(path, Fm2Format.write(movie))
        return path
    }

    private fun options(
        dir: Path,
        moviePath: Path,
        expectState: String? = null,
        expectFrame: String? = null,
    ) = ReplayCommand.Options(
        romPath = nestest,
        moviePath = moviePath,
        pngPath = dir.resolve("out.png"),
        frameLimit = null,
        expectState = expectState,
        expectFrame = expectFrame,
        verifyChecksum = true,
    )

    @Test
    fun `record mode is deterministic across runs`(@TempDir dir: Path) {
        val movie = writeMovie(dir, 30)
        val a = ReplayCommand.run(options(dir, movie), StringBuilder())
        val b = ReplayCommand.run(options(dir, movie), StringBuilder())

        assertThat(a.exitCode, equalTo(0))
        assertThat(a.matched, equalTo<Boolean?>(null))          // record mode: nothing to match
        assertThat(a.frames, equalTo(30))
        assertThat(a.stateHash, equalTo(b.stateHash))
        assertThat(a.frameHash, equalTo(b.frameHash))
    }

    @Test
    fun `a PNG artefact is written for eyeballing`(@TempDir dir: Path) {
        val movie = writeMovie(dir, 10)
        val r = ReplayCommand.run(options(dir, movie), StringBuilder())
        assertThat(Files.exists(r.pngPath), equalTo(true))
    }

    @Test
    fun `verify mode exits 0 when the expected hashes match`(@TempDir dir: Path) {
        val movie = writeMovie(dir, 20)
        val recorded = ReplayCommand.run(options(dir, movie), StringBuilder())

        val verify = ReplayCommand.run(
            options(dir, movie, expectState = recorded.stateHash, expectFrame = recorded.frameHash),
            StringBuilder(),
        )
        assertThat(verify.exitCode, equalTo(0))
        assertThat(verify.matched, equalTo<Boolean?>(true))
    }

    @Test
    fun `verify mode exits 1 and names the mismatch on a wrong hash`(@TempDir dir: Path) {
        val movie = writeMovie(dir, 20)
        val out = StringBuilder()
        val verify = ReplayCommand.run(options(dir, movie, expectState = "deadbeef"), out)

        assertThat(verify.exitCode, equalTo(1))
        assertThat(verify.matched, equalTo<Boolean?>(false))
        assertThat(out.toString(), containsSubstring("state"))
    }

    @Test
    fun `frame limit captures a mid-movie frame`(@TempDir dir: Path) {
        val movie = writeMovie(dir, 60)
        val full = ReplayCommand.run(options(dir, movie), StringBuilder())
        val early = ReplayCommand.run(
            options(dir, movie).copy(frameLimit = 10),
            StringBuilder(),
        )
        assertThat(early.frames, equalTo(10))
        // Distinct execution points must hash differently, else the limit is being ignored.
        assertThat(early.stateHash != full.stateHash, equalTo(true))
    }

    @Test
    fun `an emulator throw becomes a clean EXIT_ERROR, not a stack trace`(@TempDir dir: Path) {
        // A minimal but header-valid iNES whose mapper (238) is unimplemented: createMapper throws
        // during powerReset. The command must catch it and exit cleanly — the same path a real
        // crash-on-replay bug takes. (An unimplemented mapper IS how Akira/#141 presents on a branch
        // without Mapper 33.)
        val rom = ByteArray(16 + 16384).apply {
            this[0] = 'N'.code.toByte(); this[1] = 'E'.code.toByte()
            this[2] = 'S'.code.toByte(); this[3] = 0x1A
            this[4] = 1            // 1 × 16KB PRG
            this[5] = 0            // 0 CHR (CHR-RAM)
            this[6] = 0xE0.toByte()  // mapper low nibble = E
            this[7] = 0xE0.toByte()  // mapper high nibble = E  → mapper 238
        }
        val romPath = dir.resolve("unsupported.nes")
        Files.write(romPath, rom)
        val movie = writeMovie(dir, 1)

        val out = StringBuilder()
        val r = ReplayCommand.run(
            options(dir, movie).copy(romPath = romPath),
            out,
        )
        assertThat(r.exitCode, equalTo(3))
        assertThat(out.toString(), containsSubstring("emulator threw"))
    }

    @Test
    fun `a wrong ROM checksum fails fast before replaying`(@TempDir dir: Path) {
        val movie = writeMovie(dir, 5, checksum = "base64:AAAAAAAAAAAAAAAAAAAAAA==")
        val out = StringBuilder()
        val r = ReplayCommand.run(options(dir, movie), out)

        assertThat(r.exitCode, equalTo(2))                      // distinct from a verify failure (1)
        assertThat(out.toString(), containsSubstring("checksum"))
    }
}
