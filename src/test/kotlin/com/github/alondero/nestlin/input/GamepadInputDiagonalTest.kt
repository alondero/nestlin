package com.github.alondero.nestlin.input

import com.github.alondero.nestlin.Controller
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/**
 * GH #182 regression: the Controller Configuration fix for POV/hat capture initially
 * dropped diagonal presses — a D-pad NW press used to fire BOTH NES UP and NES LEFT
 * (via two `updateAxisButton` calls in the old `processPov`). The first fix moved that
 * to a single-direction `when (dir) { ... else -> ignored }` and silently dropped
 * diagonals. The current fix routes each NES cardinal through `isCardinalOrDiagonalOf`,
 * so a diagonal press fires both component cardinals again. These tests pin that
 * behaviour without booting JInput — they call the internal helper directly.
 */
class GamepadInputDiagonalTest {

    private val gp = GamepadInput(Controller())

    @Test
    fun `NW press lights both UP and LEFT`() {
        // D-pad up-left (0.125) should press UP and LEFT (the two cardinals that compose NW).
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.NW, Controller.Button.UP), equalTo(true))
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.NW, Controller.Button.LEFT), equalTo(true))
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.NW, Controller.Button.DOWN), equalTo(false))
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.NW, Controller.Button.RIGHT), equalTo(false))
    }

    @Test
    fun `NE press lights both UP and RIGHT`() {
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.NE, Controller.Button.UP), equalTo(true))
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.NE, Controller.Button.RIGHT), equalTo(true))
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.NE, Controller.Button.LEFT), equalTo(false))
    }

    @Test
    fun `SW press lights both DOWN and LEFT`() {
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.SW, Controller.Button.DOWN), equalTo(true))
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.SW, Controller.Button.LEFT), equalTo(true))
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.SW, Controller.Button.UP), equalTo(false))
    }

    @Test
    fun `SE press lights both DOWN and RIGHT`() {
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.SE, Controller.Button.DOWN), equalTo(true))
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.SE, Controller.Button.RIGHT), equalTo(true))
    }

    @Test
    fun `cardinal presses light exactly one NES button`() {
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.N, Controller.Button.UP), equalTo(true))
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.N, Controller.Button.DOWN), equalTo(false))
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.S, Controller.Button.DOWN), equalTo(true))
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.W, Controller.Button.LEFT), equalTo(true))
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.E, Controller.Button.RIGHT), equalTo(true))
    }

    @Test
    fun `CENTER lights no NES button`() {
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.CENTER, Controller.Button.UP), equalTo(false))
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.CENTER, Controller.Button.DOWN), equalTo(false))
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.CENTER, Controller.Button.LEFT), equalTo(false))
        assertThat(gp.isCardinalOrDiagonalOf(PovDirection.CENTER, Controller.Button.RIGHT), equalTo(false))
    }
}
