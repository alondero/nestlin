package com.github.alondero.nestlin.testutil

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.name
import kotlin.io.path.readText

/**
 * Lint-as-test: enforces the manual steps a new mapper must complete, so they fail the build
 * loudly instead of being silently forgotten.
 *
 * Why this exists: every one of these steps has been skipped at least once by a contributor
 * (human or a delegated model) who then declared the mapper "done". The most damaging was the
 * Mesen2-comparison wiring — Mapper 24, 26 and 64's regression tests were written but never added
 * to the `mesenTests` list in `build.gradle.kts`, so `./gradlew testMesenComparison` quietly never
 * ran them. Nothing went red; the gap sat for weeks. Prose checklists (the new-mapper skill) don't
 * hold against that; a red build does.
 *
 * Two checks:
 *
 *  - **Registration + docs** — every `gamepak/MapperN.kt` has a dispatch arm in [GamePak] and a
 *    section in `MAPPER_SUPPORT.md`. A missing arm is at least loud at load time
 *    (`UnsupportedOperationException`); a missing doc section is silent, and a missing dispatch arm
 *    on a renamed/half-wired mapper is caught here before it ships.
 *  - **Oracle wiring** — every `compare/Mapper*RegressionTest.kt` (the Mesen2 byte-compare tests)
 *    is referenced in `build.gradle.kts`, i.e. actually included by `testMesenComparison`. This is
 *    the check that would have caught the 24/26/64 omission the day it happened.
 */
class MapperCoverageLintTest {

    private val gamepakDir: Path = Paths.get("src/main/kotlin/com/github/alondero/nestlin/gamepak")
    private val compareDir: Path = Paths.get("src/test/kotlin/com/github/alondero/nestlin/compare")
    private val gamePakSource: Path = gamepakDir.resolve("GamePak.kt")
    private val mapperSupport: Path = Paths.get("MAPPER_SUPPORT.md")

    @Test
    fun `every mapper is wired into GamePak dispatch and documented in MAPPER_SUPPORT`() {
        assertTrue(Files.isDirectory(gamepakDir), "expected to run from the project root; missing $gamepakDir")
        val gamePakText = gamePakSource.readText()
        val supportText = mapperSupport.readText()

        // Numbers that appear in any "## Mapper(s) ..." heading. Handles grouped headings like
        // "## Mappers 21, 23, 25 (Konami VRC4)" — but only harvests the mapper-number list, which
        // is the text BEFORE the first "(". Harvesting the whole line would scoop board-name digits
        // ("## Mapper 3 (NINA-006)" -> 6, "## Mapper 206 (Namcot 108)" -> 108) and silently mark an
        // undocumented Mapper6/Mapper108 as documented — the very false-green this lint exists to stop.
        val documentedNumbers: Set<Int> = supportText.lineSequence()
            .filter { it.startsWith("## Mapper") }
            .map { it.substringBefore('(') }
            .flatMap { Regex("""\d+""").findAll(it).map { m -> m.value.toInt() } }
            .toSet()

        val problems = mutableListOf<String>()
        for (n in mapperNumbers()) {
            // A dispatch arm like `33 -> Mapper33(this)`. `\s*->` after the exact number rejects a
            // longer number's prefix (so `16` does not match `160 ->`).
            if (!Regex("""(?m)^\s*$n\s*->""").containsMatchIn(gamePakText)) {
                problems += "Mapper$n has no `$n -> Mapper$n(...)` dispatch arm in GamePak.createMapper()"
            }
            if (n !in documentedNumbers) {
                problems += "Mapper$n has no `## Mapper $n` section in MAPPER_SUPPORT.md"
            }
        }

        assertTrue(
            problems.isEmpty(),
            "New mapper bookkeeping is incomplete (see the new-mapper skill, Step 2/Step 4):\n  " +
                problems.joinToString("\n  "),
        )
    }

    @Test
    fun `every Mesen2 mapper regression test is in the mesen lane`() {
        assertTrue(Files.isDirectory(compareDir), "missing $compareDir")

        // The Mesen2 byte-compare tests follow the MapperNNN...RegressionTest naming convention and
        // live in the compare package. Each must be discoverable by `./gradlew testMesenComparison`
        // (includeTags("mesen")) and excluded from the fast suite (excludeTags). A test joins that
        // lane in one of three ways — any is sufficient:
        //   - subclasses MapperRegressionTestBase (which carries @Tag("mesen"), inherited by JUnit),
        //   - has @RequiresMesen2 (meta-tagged @Tag("mesen")), or
        //   - has an explicit @Tag("mesen").
        // There is intentionally no build.gradle.kts list to check anymore — the list is exactly
        // what silently went stale and dropped 24/26/64. The tag IS the wiring.
        val regressionFiles = Files.list(compareDir).use { stream ->
            stream.filter { it.name.matches(Regex("""Mapper\d+.*RegressionTest\.kt""")) }
                .sorted()
                .toList()
        }
        assertTrue(
            regressionFiles.isNotEmpty(),
            "expected to find compare/Mapper*RegressionTest.kt files — has the package moved?",
        )

        val notInLane = regressionFiles.filter { file ->
            val text = file.readText()
            val subclassesBase = text.contains(": MapperRegressionTestBase")
            val requiresMesen2 = text.contains("@RequiresMesen2")
            val taggedMesen = Regex("""@(org\.junit\.jupiter\.api\.)?Tag\("mesen"\)""").containsMatchIn(text)
            !(subclassesBase || requiresMesen2 || taggedMesen)
        }.map { it.name }

        assertTrue(
            notInLane.isEmpty(),
            "These Mesen2 mapper regression tests are not in the 'mesen' lane, so " +
                "`./gradlew testMesenComparison` will not run them and `./gradlew test` may run them " +
                "against an absent oracle (the 24/26/64 bug). Subclass MapperRegressionTestBase, or " +
                "add @RequiresMesen2 / @Tag(\"mesen\"):\n  " + notInLane.joinToString("\n  "),
        )
    }

    /** Mapper numbers implemented in `gamepak/` (from `MapperN.kt` filenames; ignores base/util files). */
    private fun mapperNumbers(): List<Int> =
        Files.list(gamepakDir).use { stream ->
            stream.map { it.name }
                .map { Regex("""^Mapper(\d+)\.kt$""").find(it)?.groupValues?.get(1)?.toInt() }
                .filter { it != null }
                .map { it!! }
                .sorted()
                .toList()
        }
}
