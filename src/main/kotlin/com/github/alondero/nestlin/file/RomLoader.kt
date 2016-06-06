package com.github.alondero.nestlin.file

import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.nio.file.Files
import java.nio.file.Path

class RomLoader {

    fun load(path: Path): ByteArray {
        if (path.toString().toLowerCase().endsWith(".7z")) {
            return SevenZFile(path.toFile()).use {
                ByteArray(it.nextEntry.size.toInt()).apply {it.read(this)}
            }
        } else {
            return Files.readAllBytes(path)
        }
    }
}