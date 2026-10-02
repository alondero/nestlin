package com.github.alondero.nestlin.ui

import com.github.alondero.nestlin.cheat.CheatCode
import com.github.alondero.nestlin.cheat.CheatFormat
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

/** Decoded labels can be checked without starting the JavaFX toolkit. */
class CheatsDialogTest {
    @Test
    fun `raw labels pad addresses and unsigned bytes`() {
        assertThat(CheatsDialog.formatDetails(CheatCode.parse("0000:00")),
            equalTo("Raw NES address/value: 0000:00"))
        assertThat(CheatsDialog.formatDetails(CheatCode.parse("FFFF:FF")),
            equalTo("Raw NES address/value: FFFF:FF"))
    }

    @Test
    fun `conditional labels distinguish replacement from comparison`() {
        assertThat(CheatsDialog.formatDetails(CheatCode.parse("ZEXPYGLA")),
            equalTo("NES Game Genie: 94A7:02 (if 03)"))
        assertThat(CheatsDialog.formatDetails(CheatCode.parse("15C93C0A", CheatFormat.PRO_ACTION_ROCKY)),
            equalTo("Famicom Pro Action Rocky: 9123:BD (if DE)"))
    }
}
