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
        for ((idx, value) in load("testroms/nestest.7z").withIndex()) {
            assertThat(value, equalTo(expectedData[idx]))
        }
    }

    @Test
    fun loadsNesRom() {
        for ((idx, value) in load("testroms/nestest.nes").withIndex()) {
            assertThat(value, equalTo(expectedData[idx]))
        }
    }

    private fun load(romFile: String) = RomLoader().load(Paths.get(romFile))
}