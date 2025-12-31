package fm2

import org.junit.Test
import kotlin.test.assertTrue

class Fm2WriterTest {
    @Test
    fun testWriteFm2File() {
        val header = Fm2Header(
            version = 3,
            emuVersion = "Nestlin 0.1.0",
            romFilename = "test.nes",
            guid = "12345678-1234-1234-1234-123456789ABC",
            romChecksum = "QkQwMzVGNjM2RjBDMzQwMDAwMDAwMDAwMDAwMDAwMA==",
            rerecordCount = 0,
            palFlag = 0
        )

        val frames = listOf(
            InputFrame(port0 = ButtonState.fromString(".......A"), port1 = ButtonState()),
            InputFrame(port0 = ButtonState.fromString("........"), port1 = ButtonState())
        )

        val movie = Fm2Movie(header, frames)
        val output = Fm2Writer.write(movie)

        assertTrue(output.contains("version 3"))
        assertTrue(output.contains("romFilename test.nes"))
        assertTrue(output.contains("|0|.......A|"))
        assertTrue(output.contains("|0|........|"))
    }

    @Test
    fun testWritePreservesCommands() {
        val header = Fm2Header(
            version = 3,
            emuVersion = "Nestlin 0.1.0",
            romFilename = "test.nes",
            guid = "12345678-1234-1234-1234-123456789ABC",
            romChecksum = "QkQwMzVGNjM2RjBDMzQwMDAwMDAwMDAwMDAwMDAwMA=="
        )

        val frames = listOf(
            InputFrame(commands = 1, port0 = ButtonState(), port1 = ButtonState()),
            InputFrame(commands = 0, port0 = ButtonState.fromString(".......A"), port1 = ButtonState())
        )

        val movie = Fm2Movie(header, frames)
        val output = Fm2Writer.write(movie)

        assertTrue(output.contains("|1|"))
        assertTrue(output.contains("|0|.......A"))
    }
}
