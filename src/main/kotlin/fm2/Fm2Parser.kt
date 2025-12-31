package fm2

object Fm2Parser {
    fun parse(content: String): Fm2Movie {
        val lines = content.split("\n")

        var headerEndIndex = 0
        for ((i, line) in lines.withIndex()) {
            if (line.trim().startsWith("|")) {
                headerEndIndex = i
                break
            }
        }

        // Parse header
        val headerText = lines.subList(0, headerEndIndex).joinToString("\n")
        val header = Fm2Header.fromText(headerText)

        // Parse frames
        val frames = mutableListOf<InputFrame>()
        for (i in headerEndIndex until lines.size) {
            val line = lines[i].trim()
            if (line.startsWith("|")) {
                val frame = parseFrame(line)
                if (frame != null) {
                    frames.add(frame)
                }
            }
        }

        return Fm2Movie(header, frames)
    }

    private fun parseFrame(line: String): InputFrame? {
        // Format: |commands|port0|port1|port2|
        val parts = line.split("|").filter { it.isNotEmpty() }
        if (parts.size < 2) return null

        val commands = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val port0Str = parts.getOrNull(1) ?: "........"
        val port1Str = parts.getOrNull(2) ?: "........"
        val port2Str = parts.getOrNull(3) ?: "........"

        return InputFrame(
            commands = commands,
            port0 = ButtonState.fromString(port0Str.padEnd(8, '.')),
            port1 = ButtonState.fromString(port1Str.padEnd(8, '.')),
            port2 = ButtonState.fromString(port2Str.padEnd(8, '.'))
        )
    }
}
