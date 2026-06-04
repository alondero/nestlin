package com.github.alondero.nestlin.ui

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class DisplayConfigTest {
    // JUnit 5's @TempDir replaces JUnit 4's @Rule TemporaryFolder. The field
    // is a File, matching the JUnit 4 `tempFolder.root` accessor pattern.
    @TempDir
    lateinit var tempFolder: File

    private fun dir(): File = tempFolder

    @Test
    fun `defaults to 3x scale and windowed`() {
        val config = DisplayConfig()
        assertThat(config.scale, equalTo(ScaleMode.X3))
        assertThat(config.fullscreen, equalTo(false))
    }

    @Test
    fun `load returns defaults when no file exists`() {
        val loaded = DisplayConfig.load(dir())
        assertThat(loaded, equalTo(DisplayConfig()))
    }

    @Test
    fun `save then load round-trips`() {
        val original = DisplayConfig(scale = ScaleMode.FIT, fullscreen = true)
        DisplayConfig.save(original, dir())

        val loaded = DisplayConfig.load(dir())
        assertThat(loaded, equalTo(original))
    }

    @Test
    fun `load falls back to defaults on malformed JSON`() {
        DisplayConfig.configFile(dir()).writeText("{not valid json")
        val loaded = DisplayConfig.load(dir())
        assertThat(loaded, equalTo(DisplayConfig()))
    }

    @Test
    fun `ScaleMode factor returns expected integer for fixed scales`() {
        assertThat(ScaleMode.X1.factor(), equalTo<Int?>(1))
        assertThat(ScaleMode.X2.factor(), equalTo<Int?>(2))
        assertThat(ScaleMode.X3.factor(), equalTo<Int?>(3))
        assertThat(ScaleMode.X4.factor(), equalTo<Int?>(4))
    }

    @Test
    fun `ScaleMode FIT has null factor (computed at render time)`() {
        assertThat(ScaleMode.FIT.factor(), equalTo<Int?>(null))
    }
}
