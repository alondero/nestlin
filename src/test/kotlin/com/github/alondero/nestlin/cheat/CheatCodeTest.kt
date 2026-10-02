package com.github.alondero.nestlin.cheat

import com.github.alondero.nestlin.testutil.assertThrowsWithMessage
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test

class CheatCodeTest {
    @Test
    fun `Game Genie decodes published six and eight letter examples`() {
        val six = CheatCode.parse(" gossip ")
        assertThat(six.address, equalTo(0xD1DD))
        assertThat(six.value, equalTo(0x14))
        assertThat(six.compare, equalTo(null))
        val eight = CheatCode.parse("zexp-ygla")
        assertThat(eight.address, equalTo(0x94A7))
        assertThat(eight.value, equalTo(2))
        assertThat(eight.compare, equalTo(3))
        // The third letter's high bit is unused, even if it suggests a different length.
        assertThat(CheatCode.parse("GOISIP").address, equalTo(six.address))
        assertThat(CheatCode.parse("GOISIP").value, equalTo(six.value))
    }

    @Test
    fun `raw formats distinguish comparison from replacement`() {
        for (text in listOf("94a7:02:03", "94A7?03:02")) {
            val code = CheatCode.parse(text)
            assertThat(code.address, equalTo(0x94A7))
            assertThat(code.value, equalTo(2))
            assertThat(code.compare, equalTo(3))
        }
        assertThat(CheatCode.parse("075a=ff").value, equalTo(255))
        assertThat(CheatCode.parse("0000:00").compare, equalTo(null))
    }

    @Test
    fun `Rocky decodes independently published equivalent Game Genie code`() {
        val rocky = CheatCode.parse("15C93C0A", CheatFormat.PRO_ACTION_ROCKY)
        val genie = CheatCode.parse("SLXPLOVS")
        assertThat(rocky.address, equalTo(0x9123))
        assertThat(rocky.value, equalTo(0xBD))
        assertThat(rocky.compare, equalTo(0xDE))
        assertThat(listOf(rocky.address, rocky.value, rocky.compare), equalTo(listOf(genie.address, genie.value, genie.compare)))
    }

    @Test
    fun `Replay follows the legacy FCEUX NES encoding including low-byte carry`() {
        val code = CheatCode.parse("00002080", CheatFormat.PRO_ACTION_REPLAY)
        assertThat(code.address, equalTo(0x809F))
        assertThat(code.value, equalTo(0))
        assertThat(code.compare, equalTo(null))
        assertThat(CheatCode.parse("0000FF80", CheatFormat.PRO_ACTION_REPLAY).address, equalTo(0x817E))
    }

    @Test
    fun `invalid input and ambiguous device codes fail without truncation`() {
        for (text in listOf("", "SXIOP", "SXIOP1", "SXIOPOOOO", "SX--IOPO", "GG:SXIOPO",
            "12345:01", "075A:100", "075A:-1", "075A:09 garbage", "80000000 0009", "000001:09")) {
            assertThrowsWithMessage<IllegalArgumentException>("") { CheatCode.parse(text) }
        }
        assertThrowsWithMessage<IllegalArgumentException>("Select NES") { CheatCode.parse("15C93C0A") }
        assertThrowsWithMessage<IllegalArgumentException>("eight hexadecimal") {
            CheatCode.parse("ZZZZZZZZ", CheatFormat.PRO_ACTION_REPLAY)
        }
        for (address in listOf("2000", "3FFF", "4014", "4016", "5FFF")) {
            assertThrowsWithMessage<IllegalArgumentException>("Cheats must address") { CheatCode.parse("$address:FF") }
        }
    }

    @Test
    fun `Auto requires a format for eight hexadecimal characters including letters only`() {
        for (text in listOf("aaaaaaaa", "EEEEEEEE", "aEaEaEaE", "15C93C0A", "1234ABCD")) {
            assertThrowsWithMessage<IllegalArgumentException>("Select NES") { CheatCode.parse(text) }
        }
        val genie = CheatCode.parse("AAAAAAAA", CheatFormat.GAME_GENIE)
        assertThat(genie.address, equalTo(0x8000))
        assertThat(genie.value, equalTo(0))
        assertThat(genie.compare, equalTo(0))
        assertThat(CheatCode.parse("AAAA-AAAA"), equalTo(genie))
        assertThat(CheatCode.parse("AAAAAA").format, equalTo(CheatFormat.GAME_GENIE))
    }

    @Test
    fun `batch validation reports the original line number and accepts blank lines`() {
        assertThat(CheatCode.parseLines("\nSXIOPO\r\n\n075A:09\n").size, equalTo(2))
        assertThrowsWithMessage<IllegalArgumentException>("Line 3:") {
            CheatCode.parseLines("SXIOPO\n\nBAD")
        }
    }
}
