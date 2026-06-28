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
            keyboard = PlayerKeyBindings(
                player1 = mapOf("K" to "A", "L" to "B", "ENTER" to "START"),
                player2 = mapOf("NUMPAD0" to "A"),
            ),
            gamepad = GamepadConfig(bindings = mapOf("btn:3" to "A"), axisDeadzone = 0.7f),
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
    fun `getButtonForKey resolves a configured key to its NES button for a specific player`() {
        val config = InputConfig(keyboard = PlayerKeyBindings(player1 = mapOf("K" to "A")))

        assertThat(config.getButtonForKey(javafx.scene.input.KeyCode.K, Player.ONE),
            equalTo(com.github.alondero.nestlin.Controller.Button.A))
    }

    @Test
    fun `getButtonForKey returns null for a key bound to the other player`() {
        val config = InputConfig(
            keyboard = PlayerKeyBindings(
                player1 = mapOf("K" to "A"),
                player2 = mapOf("L" to "B"),
            ),
        )

        // K is bound on P1 only — P2 lookup returns null.
        assertThat(config.getButtonForKey(javafx.scene.input.KeyCode.K, Player.TWO), equalTo(null))
        // L is bound on P2 only — P1 lookup returns null.
        assertThat(config.getButtonForKey(javafx.scene.input.KeyCode.L, Player.ONE), equalTo(null))
    }

    @Test
    fun `firstPlayerForKey returns P1 when both players bind the same key (P1 wins ties)`() {
        val bindings = PlayerKeyBindings(
            player1 = mapOf("Z" to "A"),
            player2 = mapOf("Z" to "B"),
        )

        assertThat(InputConfig.firstPlayerForKey(javafx.scene.input.KeyCode.Z, bindings),
            equalTo(Player.ONE))
    }

    @Test
    fun `firstPlayerForKey returns P2 when only P2 binds the key`() {
        val bindings = PlayerKeyBindings(
            player1 = mapOf("K" to "A"),
            player2 = mapOf("NUMPAD0" to "A"),
        )

        assertThat(InputConfig.firstPlayerForKey(javafx.scene.input.KeyCode.NUMPAD0, bindings),
            equalTo(Player.TWO))
    }

    @Test
    fun `firstPlayerForKey returns null when neither player binds the key`() {
        val bindings = PlayerKeyBindings()

        assertThat(InputConfig.firstPlayerForKey(javafx.scene.input.KeyCode.UNDEFINED, bindings),
            equalTo(null))
    }

    @Test
    fun `default P2 keyboard map uses numpad (FCEUX_Mesen2 convention)`() {
        // Pin the shipped defaults so a future change to numpad keys is intentional.
        assertThat(PlayerKeyBindings.defaultPlayer2Keyboard["NUMPAD0"], equalTo("A"))
        assertThat(PlayerKeyBindings.defaultPlayer2Keyboard["DECIMAL"], equalTo("B"))
        assertThat(PlayerKeyBindings.defaultPlayer2Keyboard["NUMPAD_ENTER"], equalTo("START"))
        assertThat(PlayerKeyBindings.defaultPlayer2Keyboard["ADD"], equalTo("SELECT"))
        assertThat(PlayerKeyBindings.defaultPlayer2Keyboard["NUMPAD8"], equalTo("UP"))
        assertThat(PlayerKeyBindings.defaultPlayer2Keyboard["NUMPAD2"], equalTo("DOWN"))
        assertThat(PlayerKeyBindings.defaultPlayer2Keyboard["NUMPAD4"], equalTo("LEFT"))
        assertThat(PlayerKeyBindings.defaultPlayer2Keyboard["NUMPAD6"], equalTo("RIGHT"))
    }

    @Test
    fun `default P1 keyboard map preserves legacy single-player defaults`() {
        // Pin the shipped defaults so a regression on the legacy layout is caught.
        assertThat(PlayerKeyBindings.defaultPlayer1Keyboard["Z"], equalTo("A"))
        assertThat(PlayerKeyBindings.defaultPlayer1Keyboard["X"], equalTo("B"))
        assertThat(PlayerKeyBindings.defaultPlayer1Keyboard["SPACE"], equalTo("SELECT"))
        assertThat(PlayerKeyBindings.defaultPlayer1Keyboard["ENTER"], equalTo("START"))
        assertThat(PlayerKeyBindings.defaultPlayer1Keyboard["UP"], equalTo("UP"))
    }

    @Test
    fun `ControllerBindings toInputConfig preserves a non-empty gamepad bindings map`() {
        // Regression for the code-review bug: ControllerConfigWindow.onSave() must
        // round-trip the user's gamepad rebinds through toInputConfig. Previously
        // the save flow only took the keyboard slot and discarded the gamepad map.
        val cfg = InputConfig()
        // Start from defaults, then bind the A hotspot to gamepad button 7 — this
        // mimics the user clicking a hotspot and pressing a gamepad button.
        val bindings = ControllerBindings.defaults()
        bindings.startListening(com.github.alondero.nestlin.Controller.Button.A)
        bindings.capture(GamepadBinding.ButtonIndex(7))

        val result = bindings.toInputConfig(cfg, Player.ONE)

        // Gamepad binding is preserved (btn:7 → A).
        assertThat(result.gamepad.bindings["btn:7"], equalTo("A"))
        // Default keyboard layout for P1 is still present (the rebind was only on the gamepad map).
        assertThat(result.keyboard.player1["Z"], equalTo("A"))
        // P2's slot is untouched.
        assertThat(result.keyboard.player2, equalTo(cfg.keyboard.player2))
    }
}
