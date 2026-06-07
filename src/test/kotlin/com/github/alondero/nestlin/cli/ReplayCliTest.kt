package com.github.alondero.nestlin.cli

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.containsSubstring
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.nio.file.Paths

class ReplayCliTest {

    private fun ok(args: List<String>): ReplayCommand.Options {
        val parsed = ReplayCli.parse(args)
        assertThat("expected Ok but got $parsed", parsed is ReplayCli.Parsed.Ok, equalTo(true))
        return (parsed as ReplayCli.Parsed.Ok).options
    }

    private fun err(args: List<String>): String {
        val parsed = ReplayCli.parse(args)
        assertThat("expected Error but got $parsed", parsed is ReplayCli.Parsed.Error, equalTo(true))
        return (parsed as ReplayCli.Parsed.Error).message
    }

    @Test
    fun `parses the two positional paths and defaults`() {
        val o = ok(listOf("rom.nes", "movie.fm2"))
        assertThat(o.romPath, equalTo(Paths.get("rom.nes")))
        assertThat(o.moviePath, equalTo(Paths.get("movie.fm2")))
        assertThat(o.pngPath, equalTo<java.nio.file.Path?>(null))
        assertThat(o.frameLimit, equalTo<Int?>(null))
        assertThat(o.expectState, equalTo<String?>(null))
        assertThat(o.verifyChecksum, equalTo(true))
    }

    @Test
    fun `parses every option`() {
        val o = ok(
            listOf(
                "rom.nes", "movie.fm2",
                "--frame", "120",
                "--png", "out.png",
                "--expect-state", "abc",
                "--expect-frame", "def",
                "--no-verify-checksum",
            ),
        )
        assertThat(o.frameLimit, equalTo<Int?>(120))
        assertThat(o.pngPath, equalTo<java.nio.file.Path?>(Paths.get("out.png")))
        assertThat(o.expectState, equalTo<String?>("abc"))
        assertThat(o.expectFrame, equalTo<String?>("def"))
        assertThat(o.verifyChecksum, equalTo(false))
    }

    @Test
    fun `options before positionals also parse`() {
        val o = ok(listOf("--frame", "5", "rom.nes", "movie.fm2"))
        assertThat(o.frameLimit, equalTo<Int?>(5))
        assertThat(o.romPath, equalTo(Paths.get("rom.nes")))
    }

    @Test
    fun `missing movie path is an error`() {
        assertThat(err(listOf("rom.nes")), containsSubstring("positional"))
    }

    @Test
    fun `non-integer frame is an error`() {
        assertThat(err(listOf("rom.nes", "movie.fm2", "--frame", "soon")), containsSubstring("integer"))
    }

    @Test
    fun `unknown option is an error`() {
        assertThat(err(listOf("rom.nes", "movie.fm2", "--turbo")), containsSubstring("unknown option"))
    }

    @Test
    fun `dangling flag value is an error`() {
        assertThat(err(listOf("rom.nes", "movie.fm2", "--expect-state")), containsSubstring("requires a value"))
    }
}
