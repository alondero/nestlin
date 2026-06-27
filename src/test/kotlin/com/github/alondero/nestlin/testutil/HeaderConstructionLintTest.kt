package com.github.alondero.nestlin.testutil

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Lint-as-test: forbids NEW hand-built 16-byte iNES headers in test fixtures.
 *
 * Hand-rolled headers have caused 7+ bug incidents (missing 4-byte magic,
 * 16KB-PRG vs 8KB-CHR unit confusion, NES 2.0 submapper nibble mistakes,
 * signed-byte multiplies). [TestRomBuilder] owns those encodings now; any test
 * that needs a ROM image should use it.
 *
 * Existing offenders are grandfathered in [BASELINE]. The baseline must only
 * SHRINK: migrate a file to TestRomBuilder, then delete its entry here.
 */
class HeaderConstructionLintTest {

    @Test
    fun `no new raw iNES header construction outside the baseline`() {
        val root = Paths.get("src/test/kotlin")
        assertTrue(Files.isDirectory(root), "expected to run from the project root; missing $root")

        val offenders = Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.extension == "kt" }
                .toList()
        }
            .map { it to relativePath(root, it) }
            .filter { (_, rel) -> rel !in EXCLUDED }
            .filter { (file, _) -> RAW_HEADER_PATTERNS.any { it.containsMatchIn(file.readText()) } }
            .map { (_, rel) -> rel }
            .sorted()

        val newOffenders = offenders.filter { it !in BASELINE }
        assertTrue(
            newOffenders.isEmpty(),
            "Build test ROMs with testutil.TestRomBuilder instead of raw ByteArray(16) headers — " +
                "see TestRomBuilder.kt. If migrating an old file, remove it from the baseline. " +
                "New offender(s): $newOffenders"
        )

        // The baseline may only shrink: a baselined file that no longer exists or no
        // longer matches has been migrated — delete its entry to lock in the progress.
        val stale = BASELINE.filter { it !in offenders }
        assertTrue(
            stale.isEmpty(),
            "Baseline entries no longer match raw-header patterns — remove them from " +
                "HeaderConstructionLintTest.BASELINE so the list only shrinks: $stale"
        )
    }

    private fun relativePath(root: Path, file: Path): String =
        root.relativize(file).toString().replace('\\', '/')

    companion object {
        /**
         * Signatures of hand-built iNES headers, tuned against the existing corpus:
         * a bare 16-byte array allocation, and byte-wise writing of the "NES" magic.
         */
        private val RAW_HEADER_PATTERNS = listOf(
            Regex("""ByteArray\(16\)"""),
            Regex("""'N'\.code\.toByte\(\)"""),
        )

        /** This builder and this lint legitimately mention the patterns. */
        private val EXCLUDED = setOf(
            "com/github/alondero/nestlin/testutil/TestRomBuilder.kt",
            "com/github/alondero/nestlin/testutil/HeaderConstructionLintTest.kt",
        )

        /**
         * Grandfathered files that pre-date TestRomBuilder (snapshot 2026-06-11).
         * This list must ONLY SHRINK — never add to it. Migrate a file to
         * TestRomBuilder and delete its entry.
         */
        private val BASELINE = setOf(
            "com/github/alondero/nestlin/SaveRamTest.kt",
            "com/github/alondero/nestlin/cli/ReplayCommandTest.kt",
            "com/github/alondero/nestlin/movie/MovieRoundTripTest.kt",
            "com/github/alondero/nestlin/gamepak/GamePakTest.kt",
            "com/github/alondero/nestlin/gamepak/Mapper10Test.kt",
            "com/github/alondero/nestlin/gamepak/Mapper153Test.kt",
            "com/github/alondero/nestlin/gamepak/Mapper16Test.kt",
            "com/github/alondero/nestlin/gamepak/Mapper1Test.kt",
            "com/github/alondero/nestlin/gamepak/Mapper206Test.kt",
            "com/github/alondero/nestlin/gamepak/Mapper33Test.kt",
            "com/github/alondero/nestlin/gamepak/Mapper3Test.kt",
            "com/github/alondero/nestlin/gamepak/Mapper4ChrBankingTest.kt",
            "com/github/alondero/nestlin/gamepak/Mapper4IrqTest.kt",
            "com/github/alondero/nestlin/gamepak/Mapper64IrqDisarmTest.kt",
            "com/github/alondero/nestlin/gamepak/Mapper64Test.kt",
            "com/github/alondero/nestlin/gamepak/Mapper66Test.kt",
            "com/github/alondero/nestlin/gamepak/Mapper69Test.kt",
            "com/github/alondero/nestlin/gamepak/Mapper71Test.kt",
            "com/github/alondero/nestlin/gamepak/Mapper9Test.kt",
            "com/github/alondero/nestlin/gamepak/RegionDetectionTest.kt",
            "com/github/alondero/nestlin/gamepak/Vrc4Test.kt",
            "com/github/alondero/nestlin/gamepak/Vrc4VariantDecodeTest.kt",
        )
    }
}
