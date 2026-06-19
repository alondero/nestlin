package com.github.alondero.nestlin.input

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class InputConfigTest {

    @TempDir
    lateinit var tempDir: File

    @Test
    fun `save then load round-trips the config`() {
        val original = InputConfig(
            keyboard = mapOf("K" to "A", "L" to "B", "ENTER" to "START"),
            gamepad = GamepadConfig(buttons = mapOf(3 to "A"), axisDeadzone = 0.7f),
        )

        InputConfig.save(original, tempDir)
        val loaded = InputConfig.load(tempDir)

        assertThat(loaded, equalTo(original))
    }

    @Test
    fun `load returns defaults when no file exists`() {
        val loaded = InputConfig.load(tempDir)

        assertThat(loaded, equalTo(InputConfig()))
    }

    @Test
    fun `load returns defaults on malformed json`() {
        File(tempDir, "input.json").writeText("{ this is not valid json")

        val loaded = InputConfig.load(tempDir)

        assertThat(loaded, equalTo(InputConfig()))
    }

    @Test
    fun `load returns defaults on empty file`() {
        // gson.fromJson returns null (no exception) for empty/`null` content; load must not
        // hand back a null masquerading as a non-null InputConfig.
        File(tempDir, "input.json").writeText("")

        val loaded = InputConfig.load(tempDir)

        assertThat(loaded, equalTo(InputConfig()))
    }

    @Test
    fun `load returns defaults on literal null json`() {
        File(tempDir, "input.json").writeText("null")

        val loaded = InputConfig.load(tempDir)

        assertThat(loaded, equalTo(InputConfig()))
    }

    @Test
    fun `createDefaultIfMissing writes a loadable default file`() {
        InputConfig.createDefaultIfMissing(tempDir)

        assertThat(File(tempDir, "input.json").exists(), equalTo(true))
        assertThat(InputConfig.load(tempDir), equalTo(InputConfig()))
    }

    @Test
    fun `getButtonForKey resolves a configured key to its NES button`() {
        val config = InputConfig(keyboard = mapOf("K" to "A"))

        assertThat(config.getButtonForKey(javafx.scene.input.KeyCode.K), equalTo(com.github.alondero.nestlin.Controller.Button.A))
    }
}
