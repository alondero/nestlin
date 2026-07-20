package com.github.alondero.nestlin.file

import com.github.alondero.nestlin.testutil.TestRoms
import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Paths

class RomLoaderTest {

    // Paths.get(...).load() is the SUT — the file-loading extension is the thing under test,
    // not the relative-path lookup, so the source path is incidental. Issue #21 pre-fix
    // used `testroms/nestest.nes` (CWD-relative); we now derive a path from `src/test/resources/`
    // via TestRoms so the lookup is hermetic regardless of where the JVM was started.
    private val romPath = TestRoms.nestestPath()
    val expectedData = Files.readAllBytes(romPath)!!

    @Test
    fun loadsNesRom() {
        for ((idx, value) in load(romPath.toString()).withIndex()) {
            assertThat(value, equalTo(expectedData[idx]))
        }
    }

    private fun load(romFile: String): ByteArray = Paths.get(romFile).load()!!
}