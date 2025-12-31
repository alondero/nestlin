package fm2

import java.util.*

data class ButtonState(
    val a: Boolean = false,
    val b: Boolean = false,
    val select: Boolean = false,
    val start: Boolean = false,
    val up: Boolean = false,
    val down: Boolean = false,
    val left: Boolean = false,
    val right: Boolean = false
) {
    fun toByte(): Byte {
        var byte = 0
        if (a) byte = byte or 0x01       // bit0
        if (b) byte = byte or 0x02       // bit1
        if (select) byte = byte or 0x04  // bit2
        if (start) byte = byte or 0x08   // bit3
        if (down) byte = byte or 0x10    // bit4
        if (up) byte = byte or 0x20      // bit5
        if (left) byte = byte or 0x40    // bit6
        if (right) byte = byte or 0x80   // bit7
        return byte.toByte()
    }

    override fun toString(): String {
        val sb = StringBuilder(8)
        sb.append(if (right) 'R' else '.')
        sb.append(if (left) 'L' else '.')
        sb.append(if (down) 'D' else '.')
        sb.append(if (up) 'U' else '.')
        sb.append(if (start) 'T' else '.')
        sb.append(if (select) 'S' else '.')
        sb.append(if (b) 'B' else '.')
        sb.append(if (a) 'A' else '.')
        return sb.toString()
    }

    companion object {
        fun fromByte(byte: Byte): ButtonState {
            val b = byte.toInt() and 0xFF
            return ButtonState(
                a = (b and 0x01) != 0,
                b = (b and 0x02) != 0,
                select = (b and 0x04) != 0,
                start = (b and 0x08) != 0,
                down = (b and 0x10) != 0,
                up = (b and 0x20) != 0,
                left = (b and 0x40) != 0,
                right = (b and 0x80) != 0
            )
        }

        fun fromString(buttons: String): ButtonState {
            // "RLDUTSBA" format
            require(buttons.length == 8) { "Button string must be 8 chars" }
            return ButtonState(
                right = buttons[0] != '.' && buttons[0] != ' ',
                left = buttons[1] != '.' && buttons[1] != ' ',
                down = buttons[2] != '.' && buttons[2] != ' ',
                up = buttons[3] != '.' && buttons[3] != ' ',
                start = buttons[4] != '.' && buttons[4] != ' ',
                select = buttons[5] != '.' && buttons[5] != ' ',
                b = buttons[6] != '.' && buttons[6] != ' ',
                a = buttons[7] != '.' && buttons[7] != ' '
            )
        }
    }
}

data class InputFrame(
    val commands: Int = 0,  // Bit field: 1=soft reset, 2=hard reset, 4=FDS insert, 8=FDS select, 16=coin
    val port0: ButtonState = ButtonState(),  // First controller
    val port1: ButtonState = ButtonState(),  // Second controller (if supported)
    val port2: ButtonState = ButtonState()   // FCExp device (if supported)
)

data class Fm2Header(
    val version: Int = 3,
    val emuVersion: String = "",
    val romFilename: String = "",
    val guid: String = "",
    val romChecksum: String = "",
    val rerecordCount: Int = 0,
    val palFlag: Int = 0,
    val binary: Int = 0,
    val fourscore: Int = 0,
    val port0: Int = 1,      // 0=none, 1=gamepad, 2=zapper
    val port1: Int = 1,
    val port2: Int = 0,
    val length: Int? = null,
    val comment: String = "",
    val subtitle: String = ""
) {
    companion object {
        fun fromText(text: String): Fm2Header {
            val lines = text.split("\n")
            val values = mutableMapOf<String, String>()

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.isBlank() || trimmed.startsWith("|")) break

                val parts = trimmed.split("\\s+".toRegex(), limit = 2)
                if (parts.size == 2) {
                    values[parts[0]] = parts[1]
                }
            }

            return Fm2Header(
                version = values["version"]?.toIntOrNull() ?: 3,
                emuVersion = values["emuVersion"] ?: "",
                romFilename = values["romFilename"] ?: "",
                guid = values["guid"] ?: "",
                romChecksum = values["romChecksum"] ?: "",
                rerecordCount = values["rerecordCount"]?.toIntOrNull() ?: 0,
                palFlag = values["palFlag"]?.toIntOrNull() ?: 0,
                binary = values["binary"]?.toIntOrNull() ?: 0,
                fourscore = values["fourscore"]?.toIntOrNull() ?: 0,
                port0 = values["port0"]?.toIntOrNull() ?: 1,
                port1 = values["port1"]?.toIntOrNull() ?: 1,
                port2 = values["port2"]?.toIntOrNull() ?: 0,
                length = values["length"]?.toIntOrNull(),
                comment = values["comment"] ?: "",
                subtitle = values["subtitle"] ?: ""
            )
        }
    }
}

class Fm2Movie(
    val header: Fm2Header,
    val frames: List<InputFrame>
)
