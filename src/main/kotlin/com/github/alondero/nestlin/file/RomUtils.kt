package com.github.alondero.nestlin.file

import com.github.alondero.nestlin.BadHeaderException
import org.apache.commons.compress.archivers.sevenz.SevenZFile
import java.nio.file.Files
import java.nio.file.Path

fun Path.load(): ByteArray? {
    return when {
        this.toString().toLowerCase().endsWith(".7z") -> SevenZFile(this.toFile()).use {
            ByteArray(it.nextEntry.size.toInt()).apply { it.read(this) }
        }
        else -> Files.readAllBytes(this)
    }.apply {
        validate(this)
    }
}

private fun validate(data: ByteArray) {
    if (!String(data.copyOfRange(0, 3)).equals("NES")) {
        throw BadHeaderException("Missing NES Token")
    }
}