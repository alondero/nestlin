package fm2

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class Fm2ParserTest {
    @Test
    fun testParseSimpleFm2File() {
        val resource = this::class.java.getResourceAsStream("/test.fm2")!!
        val content = resource.bufferedReader().use { it.readText() }

        val movie = Fm2Parser.parse(content)

        assertEquals(3, movie.header.version)
        assertEquals("nestest.nes", movie.header.romFilename)
        assertEquals(4, movie.frames.size)

        // First frame: all buttons released
        assertEquals(false, movie.frames[0].port0.a)

        // Second frame: A button pressed
        assertEquals(true, movie.frames[1].port0.a)

        // Third frame: A button released
        assertEquals(false, movie.frames[2].port0.a)
    }

    @Test
    fun testParseWithCommands() {
        val fm2Text = """
            version 3
            emuVersion 2.2.3
            romFilename test.nes
            guid 12345678-1234-1234-1234-123456789ABC
            romChecksum QkQwMzVGNjM2RjBDMzQwMDAwMDAwMDAwMDAwMDAwMA==
            |1|........|........|........|
            |0|.......A|........|........|
        """.trimIndent()

        val movie = Fm2Parser.parse(fm2Text)

        assertEquals(1, movie.frames[0].commands) // Soft reset
        assertEquals(0, movie.frames[1].commands)
    }

    @Test
    fun testParserIgnoresComments() {
        val fm2Text = """
            version 3
            emuVersion 2.2.3
            romFilename test.nes
            guid 12345678-1234-1234-1234-123456789ABC
            romChecksum QkQwMzVGNjM2RjBDMzQwMDAwMDAwMDAwMDAwMDAwMA==
            |0|........|........|........|
        """.trimIndent()

        val movie = Fm2Parser.parse(fm2Text)
        assertEquals(1, movie.frames.size)
    }
}
