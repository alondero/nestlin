package fm2

object Fm2Writer {
    fun write(movie: Fm2Movie): String {
        val sb = StringBuilder()

        // Write header
        writeHeader(sb, movie.header)

        // Write frames
        for (frame in movie.frames) {
            writeFrame(sb, frame)
        }

        return sb.toString()
    }

    private fun writeHeader(sb: StringBuilder, header: Fm2Header) {
        sb.append("version ${header.version}\n")
        sb.append("emuVersion ${header.emuVersion}\n")
        sb.append("romFilename ${header.romFilename}\n")
        sb.append("guid ${header.guid}\n")
        sb.append("romChecksum ${header.romChecksum}\n")
        sb.append("rerecordCount ${header.rerecordCount}\n")
        sb.append("palFlag ${header.palFlag}\n")

        if (header.fourscore != 0) {
            sb.append("fourscore ${header.fourscore}\n")
        }
        if (header.binary != 0) {
            sb.append("binary ${header.binary}\n")
        }
        if (header.length != null) {
            sb.append("length ${header.length}\n")
        }
        if (header.comment.isNotBlank()) {
            sb.append("comment ${header.comment}\n")
        }
    }

    private fun writeFrame(sb: StringBuilder, frame: InputFrame) {
        sb.append("|${frame.commands}")
        sb.append("|${frame.port0}")
        sb.append("|${frame.port1}")
        sb.append("|${frame.port2}")
        sb.append("|\n")
    }
}
