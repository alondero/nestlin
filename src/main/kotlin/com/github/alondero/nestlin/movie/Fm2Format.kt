package com.github.alondero.nestlin.movie

import com.github.alondero.nestlin.Controller.Button
import com.github.alondero.nestlin.toUnsignedInt
import java.security.MessageDigest
import java.util.Base64

/**
 * Reader/writer for the FCEUX `.fm2` movie format (text variant).
 *
 * FM2 is the de-facto NES TAS interchange format: a header of `key value` lines, then an input log
 * where every line begins with `|`. We target the *text* form (not `binary 1`) on purpose —
 * human-readability and diff-ability are the whole point for sharing a bug repro.
 *
 * Input-log line layout (non-fourscore, two standard pads):
 *
 *     |<commands>|<port0: 8 chars>|<port1: 8 chars>|<port2>|
 *     |0         |RLDUTSBA        |........        |       |
 *
 * Each pad is eight characters in the fixed mnemonic order **R L D U T S B A**
 * (Right, Left, Down, Up, sTart, Select, B, A). A pressed button shows its letter; an unpressed one
 * shows `.`. Crucially this is the *reverse* of Nestlin's internal bit order (A=bit0 … Right=bit7),
 * so the mapping is made explicit in [FM2_LAYOUT] rather than dumping the raw bitmap.
 */
object Fm2Format {

    // iNES file-layout constants. FCEUX `romChecksum` (verified from FCEUX src/ines.cpp:iNESLoad)
    // excludes the 16-byte header, then optionally excludes a 512-byte trainer block at the start
    // of the body when header byte 6 bit 2 is set. PRG and CHR sizes are multiples of 16 KB and
    // 8 KB respectively. These four numbers are the structural input to that algorithm.
    private const val INES_HEADER_SIZE = 16
    private const val TRAINER_SIZE = 512
    private const val PRG_BANK_BYTES = 16384
    private const val CHR_BANK_BYTES = 8192

    /** FM2 pad columns in written order (leftmost first), paired with the Nestlin button each maps to. */
    private val FM2_LAYOUT: List<Pair<Char, Button>> = listOf(
        'R' to Button.RIGHT,
        'L' to Button.LEFT,
        'D' to Button.DOWN,
        'U' to Button.UP,
        'T' to Button.START,
        'S' to Button.SELECT,
        'B' to Button.B,
        'A' to Button.A,
    )

    /**
     * Compute the FM2 `romChecksum`: `base64:` followed by Base64 of the raw 16-byte MD5.
     *
     * The hash input is the iNES image with (a) the 16-byte header excluded, and (b) the optional
     * 512-byte trainer block excluded when header byte 6 bit 2 is set. The result is two
     * `md5_update` calls: PRG first, then CHR (skipped if the ROM has zero CHR banks, e.g.
     * CHR-RAM mappers). PRG-RAM is never included.
     *
     * This matches FCEUX byte-for-byte (FCEUX `src/ines.cpp:iNESLoad`, `src/utils/xstring.cpp:
     * BytesToString`); a Nestlin `.fm2` will load in real FCEUX and an FCEUX-recorded `.fm2`
     * will replay in Nestlin. [romImage] is the byte array returned by `Path.load()`.
     */
    fun romChecksum(romImage: ByteArray): String {
        require(romImage.size >= INES_HEADER_SIZE) {
            "iNES header requires at least $INES_HEADER_SIZE bytes (got ${romImage.size})"
        }
        val prgBanks = romImage[4].toUnsignedInt()
        val chrBanks = romImage[5].toUnsignedInt()
        val hasTrainer = romImage[6].toUnsignedInt() and 0x04 != 0
        val trainerSkip = if (hasTrainer) TRAINER_SIZE else 0
        val prgStart = INES_HEADER_SIZE + trainerSkip
        val prgSize = prgBanks * PRG_BANK_BYTES
        val chrStart = prgStart + prgSize
        val chrSize = chrBanks * CHR_BANK_BYTES

        val md5 = MessageDigest.getInstance("MD5")
        if (prgSize > 0) md5.update(romImage, prgStart, prgSize)
        if (chrSize > 0) md5.update(romImage, chrStart, chrSize)
        return "base64:" + Base64.getEncoder().encodeToString(md5.digest())
    }

    /** Serialise [movie] to FM2 text. Lines are `\n`-terminated; `.gitattributes` normalises to CRLF. */
    fun write(movie: Movie): String {
        val sb = StringBuilder()
        sb.appendLine("version ${Movie.FM2_VERSION}")
        sb.appendLine("emuVersion ${movie.emuVersion}")
        sb.appendLine("rerecordCount ${movie.rerecordCount}")
        if (movie.palFlag) sb.appendLine("palFlag 1")
        sb.appendLine("romFilename ${movie.romFilename}")
        sb.appendLine("romChecksum ${movie.romChecksum}")
        sb.appendLine("guid ${movie.guid}")
        sb.appendLine("fourscore ${movie.fourscore.toFm2()}")
        sb.appendLine("port0 ${movie.port0}")
        sb.appendLine("port1 ${movie.port1}")
        sb.appendLine("port2 ${movie.port2}")
        movie.inputs.forEach { sb.appendLine(encodeLine(it)) }
        return sb.toString()
    }

    /** Parse FM2 text back into a [Movie]. Unknown header keys (comment, subtitle, …) are ignored. */
    fun read(text: String): Movie {
        var emuVersion = Movie.NESTLIN_EMU_VERSION
        var rerecordCount = 0
        var palFlag = false
        var romFilename = ""
        var romChecksum = ""
        var guid = Movie.ZERO_GUID
        var fourscore = false
        var port0 = Movie.PORT_GAMEPAD
        var port1 = Movie.PORT_GAMEPAD
        var port2 = Movie.PORT_NONE
        val inputs = mutableListOf<MovieInput>()

        text.lineSequence().forEach { raw ->
            val line = raw.trimEnd('\r', '\n')
            when {
                line.isEmpty() -> Unit
                line.startsWith("|") -> inputs.add(decodeLine(line))
                else -> {
                    val key = line.substringBefore(' ')
                    val value = line.substringAfter(' ', "")
                    when (key) {
                        "emuVersion" -> emuVersion = value.toIntOrNull() ?: emuVersion
                        "rerecordCount" -> rerecordCount = value.toIntOrNull() ?: 0
                        "palFlag" -> palFlag = value.trim() == "1"
                        "romFilename" -> romFilename = value
                        "romChecksum" -> romChecksum = value
                        "guid" -> guid = value
                        "fourscore" -> fourscore = value.trim() == "1"
                        "port0" -> port0 = value.toIntOrNull() ?: port0
                        "port1" -> port1 = value.toIntOrNull() ?: port1
                        "port2" -> port2 = value.toIntOrNull() ?: port2
                        // "version", "comment", "subtitle", "binary", … intentionally ignored.
                    }
                }
            }
        }
        return Movie(
            romFilename, romChecksum, palFlag, rerecordCount, guid,
            fourscore, port0, port1, port2, emuVersion, inputs,
        )
    }

    private fun encodeLine(input: MovieInput): String =
        "|${input.commands}|${encodePad(input.controller1)}|${encodePad(input.controller2)}||"

    private fun encodePad(bitmap: Int): String =
        FM2_LAYOUT.joinToString("") { (ch, btn) -> if (bitmap and btn.mask != 0) ch.toString() else "." }

    private fun decodeLine(line: String): MovieInput {
        // split('|') on "|cmd|p0|p1|p2|" yields: ["", cmd, p0, p1, p2, ""].
        val fields = line.split('|')
        val commands = fields.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        val p1 = decodePad(fields.getOrNull(2) ?: "")
        val p2 = decodePad(fields.getOrNull(3) ?: "")
        return MovieInput(p1, p2, commands)
    }

    private fun decodePad(pad: String): Int {
        var bitmap = 0
        FM2_LAYOUT.forEachIndexed { i, (_, btn) ->
            val ch = pad.getOrNull(i) ?: '.'
            // FM2 treats '.' and ' ' as released; any other glyph means pressed.
            if (ch != '.' && ch != ' ') bitmap = bitmap or btn.mask
        }
        return bitmap
    }

    private fun Boolean.toFm2(): String = if (this) "1" else "0"
}
