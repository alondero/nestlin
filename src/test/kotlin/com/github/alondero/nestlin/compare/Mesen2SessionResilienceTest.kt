package com.github.alondero.nestlin.compare

import com.natpryce.hamkrest.assertion.assertThat
import com.natpryce.hamkrest.equalTo
import com.natpryce.hamkrest.startsWith
import org.junit.jupiter.api.Test
import java.nio.file.Paths

/**
 * Pure-unit coverage for the issue #214 Mesen2Session hardening that does NOT
 * need a Mesen2 binary — the process-level resilience (dead-instance eviction,
 * Lua error surfacing) is exercised by the `@RequiresMesen2` smoke test; these
 * cases pin the parts that are just string/path logic so they run in the plain
 * `test` lane on any CI runner.
 */
class Mesen2SessionResilienceTest {

    // --- Fix 2: per-ROM script-data folder ----------------------------------

    @Test
    fun `scriptBaseName is prefixed and derived from the rom filename`() {
        val name = Mesen2ProcessInstance.scriptBaseName(Paths.get("testroms/nestest.nes"))
        assertThat(name, equalTo("session_server_nestest_nes"))
    }

    @Test
    fun `scriptBaseName collapses every non-alphanumeric character to underscore`() {
        // NO-INTRO names are full of spaces, parens and punctuation; none of
        // them may leak into a filesystem path or two ROMs could still collide.
        val name = Mesen2ProcessInstance.scriptBaseName(
            Paths.get("S:/Media/Nintendo NES/Games/Micro Machines (USA) (Unl).nes")
        )
        assertThat(name, startsWith("session_server_"))
        val suffix = name.removePrefix("session_server_")
        assertThat(suffix, equalTo("Micro_Machines__USA___Unl__nes"))
        check(suffix.all { it == '_' || it.isLetterOrDigit() }) {
            "sanitised basename still contains path-unsafe characters: $suffix"
        }
    }

    @Test
    fun `different roms get different script-data folders`() {
        // The whole point of the fix: two concurrent instances must not share a
        // state.json under LuaScriptData/.
        val a = Mesen2ProcessInstance.scriptBaseName(Paths.get("roms/klax.nes"))
        val b = Mesen2ProcessInstance.scriptBaseName(Paths.get("roms/kirby.nes"))
        check(a != b) { "klax and kirby collapsed to the same folder: $a" }
    }

    // --- Fix 4: Oam runner path delegation ----------------------------------

    @Test
    fun `Mesen2OamDumpRunner delegates path resolution to Mesen2Session`() {
        // Force the property branch of the canonical resolution chain. If the
        // OAM runner still had its own env-only lookup it would ignore this and
        // fall back to the relative default, diverging from the session.
        val key = "mesen2.path"
        val previous = System.getProperty(key)
        try {
            System.setProperty(key, "Z:/some/where/Mesen.exe")
            assertThat(
                Mesen2OamDumpRunner.mesen2Path(),
                equalTo(Mesen2Session.mesen2Path())
            )
        } finally {
            if (previous == null) System.clearProperty(key) else System.setProperty(key, previous)
        }
    }
}
