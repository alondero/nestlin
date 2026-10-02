package com.github.alondero.nestlin.cheat

import java.util.Locale

enum class CheatFormat(private val label: String) {
    AUTO("Auto (Game Genie / raw)"),
    GAME_GENIE("NES Game Genie"),
    PRO_ACTION_REPLAY("NES Pro Action Replay (FCEUX)"),
    PRO_ACTION_ROCKY("Famicom Pro Action Rocky"),
    RAW("Raw NES address/value"),
    ;

    override fun toString(): String = label
}

/** A decoded CPU-bus read substitution; values and optional comparisons are unsigned bytes. */
data class CheatCode(
    val text: String,
    val format: CheatFormat,
    val address: Int,
    val value: Int,
    val compare: Int? = null,
) {
    init {
        require(address in 0..0x1FFF || address in 0x6000..0xFFFF) {
            "Cheats must address NES RAM (0000-1FFF) or cartridge memory (6000-FFFF)."
        }
        require(value in 0..0xFF && (compare == null || compare in 0..0xFF)) {
            "Cheat values and comparisons must be bytes (00-FF)."
        }
    }

    companion object {
        private const val GENIE_ALPHABET = "APZLGITYEOXUKSVN"
        private val rawPattern = Regex("([0-9A-F]{4})(?:[:=]([0-9A-F]{2})(?::([0-9A-F]{2}))?|\\?([0-9A-F]{2}):([0-9A-F]{2}))")
        private val hexPattern = Regex("[0-9A-F]{8}")
        private val geniePattern = Regex(
            "[$GENIE_ALPHABET]{6}|[$GENIE_ALPHABET]{8}|" +
                "[$GENIE_ALPHABET]{3}-[$GENIE_ALPHABET]{3}|[$GENIE_ALPHABET]{4}-[$GENIE_ALPHABET]{4}"
        )
        // Bit positions in the decoded DDCCAAAA Pro Action Rocky payload.
        private val rockyBits = intArrayOf(
            15, 3, 13, 14, 1, 6, 9, 5, 0, 12, 7, 2, 8, 10, 11, 4,
            19, 21, 23, 22, 20, 17, 16, 18, 29, 31, 24, 26, 25, 30, 27, 28,
        )

        /** Strict, case-insensitive parsing. Eight-digit device codes require an explicit format. */
        fun parse(input: String, format: CheatFormat = CheatFormat.AUTO): CheatCode {
            val text = input.trim().uppercase(Locale.ROOT)
            require(text.isNotEmpty()) { "Enter a cheat code." }
            val selected = if (format == CheatFormat.AUTO) {
                when {
                    rawPattern.matches(text) -> CheatFormat.RAW
                    text.all { it in GENIE_ALPHABET || it == '-' } -> CheatFormat.GAME_GENIE
                    hexPattern.matches(text) -> throw IllegalArgumentException(
                        "Select NES Pro Action Replay or Pro Action Rocky for an eight-digit code. " +
                            "GameShark codes for other consoles are not NES codes."
                    )
                    else -> throw IllegalArgumentException("Expected a NES Game Genie code or a raw code such as 075A:09.")
                }
            } else format
            return when (selected) {
                CheatFormat.GAME_GENIE -> decodeGenie(text)
                CheatFormat.PRO_ACTION_REPLAY -> decodeReplay(text)
                CheatFormat.PRO_ACTION_ROCKY -> decodeRocky(text)
                CheatFormat.RAW -> decodeRaw(text)
                CheatFormat.AUTO -> error("Auto format must be resolved before decoding")
            }
        }

        /** Validate a pasted batch completely before its caller changes the active list. */
        fun parseLines(input: String, format: CheatFormat = CheatFormat.AUTO): List<CheatCode> =
            input.lineSequence().mapIndexedNotNull { index, line ->
                if (line.isBlank()) null else try {
                    parse(line, format)
                } catch (error: IllegalArgumentException) {
                    throw IllegalArgumentException("Line ${index + 1}: ${error.message}", error)
                }
            }.toList()

        private fun decodeGenie(text: String): CheatCode {
            require(geniePattern.matches(text)) {
                "NES Game Genie codes contain six or eight letters from $GENIE_ALPHABET."
            }
            val code = text.replace("-", "")
            val n = code.map { GENIE_ALPHABET.indexOf(it) }
            val address = 0x8000 or ((n[3] and 7) shl 12) or ((n[5] and 7) shl 8) or
                ((n[4] and 8) shl 8) or ((n[2] and 7) shl 4) or ((n[1] and 8) shl 4) or
                (n[4] and 7) or (n[3] and 8)
            val value = ((n[1] and 7) shl 4) or ((n[0] and 8) shl 4) or (n[0] and 7) or
                (n[if (n.size == 8) 7 else 5] and 8)
            val compare = if (n.size == 8) {
                ((n[7] and 7) shl 4) or ((n[6] and 8) shl 4) or (n[6] and 7) or (n[5] and 8)
            } else null
            return CheatCode(code, CheatFormat.GAME_GENIE, address, value, compare)
        }

        private fun decodeRaw(text: String): CheatCode {
            val match = rawPattern.matchEntire(text)
                ?: throw IllegalArgumentException("Use AAAA:VV, AAAA=VV, AAAA:VV:CC or AAAA?CC:VV (hexadecimal).")
            val groups = match.groupValues
            val value = (groups[2].ifEmpty { groups[5] }).toInt(16)
            val compare = (groups[3].ifEmpty { groups[4] }).takeIf { it.isNotEmpty() }?.toInt(16)
            return CheatCode(text, CheatFormat.RAW, groups[1].toInt(16), value, compare)
        }

        private fun decodeReplay(text: String): CheatCode {
            require(hexPattern.matches(text)) { "NES Pro Action Replay codes contain eight hexadecimal digits." }
            // Compatibility with FCEUX's legacy NES PAR decoder: its implemented
            // encoding substitutes zero; the first two bytes are not payload.
            // See docs/CHEATS.md for the source and the distinction from Rocky.
            val address = (text.substring(6, 8).toInt(16) shl 8) or (text.substring(4, 6).toInt(16) + 0x7F)
            return CheatCode(text, CheatFormat.PRO_ACTION_REPLAY, address, 0)
        }

        private fun decodeRocky(text: String): CheatCode {
            require(hexPattern.matches(text)) { "Pro Action Rocky codes contain eight hexadecimal digits." }
            var encrypted = text.toLong(16).toInt() xor 0xFCBDD275.toInt()
            var payload = 0
            for (bit in 31 downTo 0) {
                if (encrypted < 0) {
                    payload = payload or (1 shl rockyBits[bit])
                    encrypted = encrypted xor 0xB8309722.toInt()
                }
                encrypted = encrypted shl 1
            }
            return CheatCode(text, CheatFormat.PRO_ACTION_ROCKY, (payload and 0x7FFF) or 0x8000,
                (payload ushr 24) and 0xFF, (payload ushr 16) and 0xFF)
        }
    }
}

data class Cheat(val code: CheatCode, val enabled: Boolean = true)
