package fm2

import org.junit.Test
import kotlin.test.assertEquals

class Fm2ModelTest {
    @Test
    fun testButtonStateFromString() {
        // "RLDUTSBA" -> A=pressed, B=unpressed, Select=pressed, etc
        val buttons = ButtonState.fromString("....TS.A")
        assertEquals(true, buttons.a)
        assertEquals(false, buttons.b)
        assertEquals(true, buttons.select)
        assertEquals(true, buttons.start)
        assertEquals(false, buttons.up)
        assertEquals(false, buttons.down)
        assertEquals(false, buttons.left)
        assertEquals(false, buttons.right)
    }

    @Test
    fun testButtonStateToString() {
        val buttons = ButtonState(
            a = true, b = false, select = true, start = true,
            up = false, down = false, left = false, right = false
        )
        assertEquals("....TS.A", buttons.toString())
    }

    @Test
    fun testButtonStateFromByte() {
        // Bit encoding: bit0=A, bit1=B, bit2=Select, bit3=Start, bit4=Down, bit5=Up, bit6=Left, bit7=Right
        val buttons = ButtonState.fromByte(0b10010101.toByte()) // Right, Down, Select, A
        assertEquals(true, buttons.a)
        assertEquals(true, buttons.select)
        assertEquals(true, buttons.down)
        assertEquals(true, buttons.right)
    }

    @Test
    fun testButtonStateToByte() {
        val buttons = ButtonState(
            a = true, b = false, select = true, start = false,
            up = false, down = true, left = false, right = true
        )
        assertEquals(0b10010101.toByte(), buttons.toByte())
    }

    @Test
    fun testInputFrameConstructor() {
        val frame = InputFrame(
            commands = 0,
            port0 = ButtonState.fromString(".......A"),
            port1 = ButtonState.fromString("........")
        )
        assertEquals(0, frame.commands)
        assertEquals(true, frame.port0.a)
    }

    @Test
    fun testFm2HeaderParsing() {
        val headerText = """
            version 3
            emuVersion 2.2.3
            romFilename test.nes
            guid 12345678-1234-1234-1234-123456789ABC
            romChecksum QkQwMzVGNjM2RjBDMzQwMDAwMDAwMDAwMDAwMDAwMA==
            rerecordCount 0
            palFlag 0
        """.trimIndent()

        val header = Fm2Header.fromText(headerText)
        assertEquals(3, header.version)
        assertEquals("test.nes", header.romFilename)
        assertEquals("12345678-1234-1234-1234-123456789ABC", header.guid)
        assertEquals(0, header.rerecordCount)
    }
}
