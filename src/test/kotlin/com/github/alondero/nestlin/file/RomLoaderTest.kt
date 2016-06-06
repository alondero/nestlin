package com.github.alondero.nestlin.file

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Paths

class RomLoaderTest {

    val expectedData: ByteArray

    init {
        expectedData = Files.readAllBytes(Paths.get("testroms/nestest.nes"))
    }

    @Test
    fun loads7zRom() {
        val loadedFile = RomLoader().load(Paths.get("testroms/nestest.7z"))
        for ((idx, value) in loadedFile.withIndex()) {
            assertThat(value, equalTo(expectedData[idx]))
        }
    }

    @Test
    fun loadsNesRom() {
        val loadedFile = RomLoader().load(Paths.get("testroms/nestest.nes"))
        for ((idx, value) in loadedFile.withIndex()) {
            assertThat(value, equalTo(expectedData[idx]))
        }
    }

}