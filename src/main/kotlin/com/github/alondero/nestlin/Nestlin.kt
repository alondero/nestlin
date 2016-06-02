package com.github.alondero.nestlin

import com.github.alondero.nestlin.gamepak.GamePak
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.nio.file.Path

class Nestlin {

    private var loadedGame: GamePak? = null

    fun load(rom: Path) {
        loadedGame = GamePak(validate(SevenZFile(rom.toFile()).use {
            val entry = it.nextEntry
            val data = ByteArray(entry.size.toInt())
            var readBytes = 0;

            it.read(data)

            data
        }))
    }

    private fun validate(data: ByteArray): ByteArray {
        val header = data.copyOfRange(0, 16)
        val nesToken = String(header.copyOfRange(0, 4))

        if (nesToken.equals("NES\n")) {
            throw BadHeaderException("Missing NES Token")
        }

        return data
    }
}

class BadHeaderException(message: String) : RuntimeException(message) {

}