package com.github.alondero.nestlin.testutil

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.declaration.KoFileDeclaration
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Kotlin AST-based architectural linter built on Konsist (issue #314).
 *
 * Why Konsist and not Detekt/ktlint:
 *  - Runs as a JUnit test in the fast `./gradlew test` lane — no separate Gradle
 *    task to remember to wire up, no second Gradle plugin to keep in sync with
 *    the project's Kotlin 1.9.22 toolchain.
 *  - Assertions are Kotlin code, so contributors can add new rules in ~10 lines
 *    next to existing tests (compare to a YAML config or a separate `.detekt.yml`).
 *  - Konsist's API is Kotlin-source-driven, so the lint reads the same files the
 *    Kotlin compiler reads — no risk of a regex drifting from real syntax.
 *
 * What lives here vs. the existing regex-based lint-as-tests:
 *  - AST-friendly rules (declarations, modifiers, package paths, annotations) go
 *    here.
 *  - Source-text pattern rules (e.g. "no raw hand-built iNES headers") stay
 *    in the regex tests because their signal is in the literal source bytes, not
 *    the AST. Konsist can re-host them via `KoFileDeclaration.text`, but doing so
 *    buys nothing while losing the grandfathered-baseline machinery those tests
 *    use to migrate legacy files one-at-a-time.
 *
 * The `KotlinIdiomsLintTest` that this file replaces (removed in #311) used a
 * homegrown regex stripper that broke on block comments and character literals.
 * The rule that motivated it — "use `Enum.entries` instead of `Enum.values()`" —
 * is restored here as the first @Test, with comments and KDoc stripped before
 * matching so the rule's own documentation does not trip it.
 *
 * Cross-platform note: Konsist 0.13.0's `KoFileDeclaration.path` returns an
 * OS-native path string (backslashes on Windows, forward slashes elsewhere).
 * Any rule that wants to compare against a path must normalize first, but the
 * Konsist ways to express "is this file in package X" — `file.packagee` and
 * `declaration.resideInPackage(...)` — operate on the Kotlin source's `package`
 * directive (always dotted, no separator ambiguity) and so are platform-
 * independent. The leaf-package rule below deliberately uses those instead of
 * the path string.
 *
 * Adding a new rule: copy one of the existing `@Test fun`s, start from
 * `productionFiles()` — or `scopeFromTest()` / `scopeFromProject()` if you
 * genuinely need those scopes — filter the declaration kind you care about,
 * and assert. Prefer `productionFiles()` over a bare `scopeFromProduction()`:
 * the unfiltered production scope also lints sibling git worktrees parked
 * under `.claude/worktrees/`, which see their KDoc for the full story.
 * Run `./gradlew test --tests *KonsistArchitectureTest` to iterate quickly.
 */
class KonsistArchitectureTest {

    /**
     * Restore the rule the deleted `KotlinIdiomsLintTest` (issue #311) used to
     * enforce: prefer `EnumClass.entries` (Kotlin 1.9+, zero-allocation `EnumEntries`)
     * over `EnumClass.values()` (allocates a fresh `Array` on every call — see
     * CLAUDE.md "Conventions" / "Enum access").
     *
     * Konsist's AST does not see synthesized properties like `.values()`, so the
     * match is on the file text — but the file list is scoped by
     * [productionFiles], and the comment-stripping lives here, not in a separate
     * regex walker. The Konsist-specific win is that the file list is the
     * compiler's view of the project, not whatever a `Files.walk` happens to
     * enumerate, so generated/build/config sources stay out of the rule's way.
     *
     * Self-tripping caveat (load-bearing assumption): the rule's `additionalMessage`
     * *contains* the literal substring ".values()". The current implementation
     * is safe because [stripComments] does not strip string literals and the
     * `assertFalse` call feeds a stripped view of the file to Konsist — but the
     * load-bearing fact is that this test file lives in `src/test/kotlin/` and
     * is therefore excluded from `Konsist.scopeFromProduction()`. If a future
     * contributor copies this rule into a production source file, or moves
     * the test into `scopeFromProject()`, the lint will self-fail.
     *
     * Nested block comments are also not handled: the [BLOCK_COMMENT_RE] lazy
     * quantifier stops at the first close-marker, so trailing text remains. In
     * practice no Kotlin source uses nested block comments; if one ever does,
     * the stripper will under-strip rather than over-strip (false-positive
     * biased), which is the safer direction for an architectural lint.
     */
    @Test
    fun `no production code calls Enum values() — use Enum entries instead`() {
        productionFiles()
            .assertFalse(
                additionalMessage = "Use EnumClass.entries instead of EnumClass.values() " +
                    "(Kotlin 1.9+ EnumEntries, zero allocation). " +
                    "See CLAUDE.md 'Conventions' and SaveState.kt's readEnum helper.",
            ) { file ->
                stripComments(file.text).contains(".values()")
            }
    }

    /**
     * Every mapper class (Mapper0..Mapper228) lives in `com.github.alondero.nestlin.gamepak`.
     *
     * Pairs with [MapperCoverageLintTest], but each test covers a different
     * observable. `MapperCoverageLintTest` walks the regex-built 16-byte iNES
     * header list and the source-tree file names — it verifies (a) `MapperNN.kt`
     * files exist, (b) the `MapperNN ->` arm appears in `GamePak.createMapper()`,
     * and (c) `MAPPER_SUPPORT.md` lists the mapper. This rule verifies the
     * Kotlin declaration's package, which the regex test cannot reach because
     * there is no way to map a `class MapperNN : Mapper` declaration back to a
     * file path from outside the Kotlin AST.
     *
     * Filter rationale: the regex [MAPPER_CLASS_NAME_RE] anchors on `^Mapper\d+$`
     * to avoid false-positives like `MapperState` (cpu/InterruptController.kt),
     * and future helpers that happen to start with "Mapper" — `Mapper1Internal`,
     * `Mapper42_test`, etc.
     */
    @Test
    fun `Mapper classes reside in the gamepak package`() {
        productionFiles()
            .flatMap { it.classes() }
            .filter { it.name.matches(MAPPER_CLASS_NAME_RE) }
            .assertTrue(
                additionalMessage = "Mapper classes (Mapper0..Mapper228) must live in " +
                    "com.github.alondero.nestlin.gamepak — they are dispatched by " +
                    "GamePak.createMapper().",
            ) { it.resideInPackage("com.github.alondero.nestlin.gamepak..") }
    }

    /**
     * The internal subsystem packages (`input/`, `movie/`, `rewind/`) collaborate
     * via `Nestlin`, `SaveState`, or a small shared interface — they do not
     * import each other directly. `cli/` is the CLI aggregator and is the one
     * documented exception; entry-point code wires features together, so direct
     * imports of `movie.runOneFrame`, `movie.Fm2Format`, etc. are expected.
     *
     * Implementation note: the filter uses [KoFileDeclaration.packagee] rather
     * than `file.path` because the latter is OS-native (backslashes on Windows)
     * and the rule must work identically on every contributor's machine. The
     * package directive is always dotted, so no normalization is needed.
     *
     * Grandfathering: [LEAF_CROSS_IMPORT_BASELINE] lists one file that
     * legitimately cross-imports today (input/InputSource.kt -> movie.PendingInputBuffer
     * for live recording). New violations are bugs; the baseline entry must be
     * deleted once the offending file is migrated.
     */
    @Test
    fun `internal subsystem packages do not cross-import each other`() {
        val leafPackages = LEAF_PACKAGES.map { "com.github.alondero.nestlin.$it" }.toSet()

        data class CrossImport(val fileId: String, val importedLeaves: Set<String>)

        val offenders: List<CrossImport> = productionFiles()
            .filter { file -> file.packagee?.fullyQualifiedName in leafPackages }
            .mapNotNull { file ->
                val pkg = file.packagee?.fullyQualifiedName ?: return@mapNotNull null
                val fileId = "${pkg.substringAfterLast('.')}/${file.nameWithExtension}"
                val origin = LEAF_PACKAGES.first { "com.github.alondero.nestlin.$it" == pkg }
                val importedLeaves = file.imports
                    .map { it.name }
                    .filter { it.startsWith("com.github.alondero.nestlin.") }
                    .mapNotNull { path ->
                        LEAF_PACKAGES.firstOrNull { other ->
                            other != origin && path.startsWith("com.github.alondero.nestlin.$other.")
                        }
                    }
                    .toSet()
                if (importedLeaves.isEmpty()) null else CrossImport(fileId, importedLeaves)
            }
            .toList()

        val newOffenders = offenders.filter { it.fileId !in LEAF_CROSS_IMPORT_BASELINE }

        org.junit.jupiter.api.Assertions.assertTrue(
            newOffenders.isEmpty(),
            "Internal subsystem packages (input, movie, rewind) must not cross-import " +
                "each other directly. cli/ is the CLI aggregator and is the documented " +
                "exception; everything else should collaborate via Nestlin, SaveState, " +
                "or a small shared interface in the parent package. " +
                "New offender(s): $newOffenders",
        )

        // The baseline may only shrink: a baselined file that no longer triggers
        // the rule has been migrated — delete its entry so the list keeps only the
        // genuine remaining legacy offenders.
        val stillOffending = offenders.map { it.fileId }.toSet()
        val stale = LEAF_CROSS_IMPORT_BASELINE - stillOffending
        org.junit.jupiter.api.Assertions.assertTrue(
            stale.isEmpty(),
            "Baseline entries no longer match the leaf-cross-import rule — remove them " +
                "from KonsistArchitectureTest.LEAF_CROSS_IMPORT_BASELINE so the list only " +
                "shrinks: $stale",
        )
    }

    /**
     * The production Kotlin files these rules should actually judge: everything
     * under *this* project's `src/main/kotlin`, and nothing else.
     *
     * Why the filter is load-bearing: `Konsist.scopeFromProduction()` was
     * observed returning Kotlin files that are emphatically not this branch's —
     * it resolves the production source root by looking for a
     * `src/main/kotlin` directory anywhere under the project tree rather than
     * only at the root. This repo parks git worktrees under
     * `.claude/worktrees/<name>/src/main/kotlin/...` (see AGENTS.md, "Worktree
     * scope"), so with any worktree present `./gradlew test` failed with 448
     * phantom violations raised against stale copies of other branches'
     * sources. CI checks out no worktrees, which is why master stayed green
     * there while going red on every developer machine.
     *
     * Filtering on absolute path keeps every rule honest regardless of how many
     * worktrees happen to be checked out.
     *
     * Vacuous-green guard: a filter that matches nothing would let all three
     * rules above pass while inspecting zero files. The non-empty assertion
     * turns that into a loud failure, so the lint can never silently stop
     * linting.
     */
    private fun productionFiles(): List<KoFileDeclaration> {
        val files = Konsist.scopeFromProduction()
            .files
            .filter { it.residesUnder(PRODUCTION_SOURCE_ROOT) }

        org.junit.jupiter.api.Assertions.assertTrue(
            files.isNotEmpty(),
            "Production scope resolved to zero files under $PRODUCTION_SOURCE_ROOT — the " +
                "Konsist rules would pass vacuously. Check that Gradle's test working " +
                "directory is still the project root and that src/main/kotlin exists.",
        )

        return files
    }

    /**
     * True when this file sits at or below [root].
     *
     * `startsWith` on a Windows [Path] compares case-insensitively, so a drive
     * letter or directory recorded with different casing still matches. Malformed
     * or non-absolute paths are treated as "not ours" rather than crashing the
     * lint — a false positive bias that keeps a weird path visible as a skipped
     * file rather than an exception.
     */
    private fun KoFileDeclaration.residesUnder(root: Path): Boolean =
        runCatching { Paths.get(path).toAbsolutePath().normalize().startsWith(root) }
            .getOrDefault(false)

    /**
     * Strip KDoc / block / line comments. Mirrors the comment-stripping the
     * deleted `KotlinIdiomsLintTest` used, kept narrow on purpose: block
     * comments first, then per-line `//`. Doesn't try to be string-literal
     * aware — see the rule's own `additionalMessage` caveat for why the test
     * file staying out of `scopeFromProduction()` is load-bearing.
     */
    private fun stripComments(source: String): String {
        val noBlock = BLOCK_COMMENT_RE.replace(source, "")
        return noBlock.lineSequence().joinToString("\n") { line ->
            val idx = line.indexOf("//")
            if (idx >= 0) line.substring(0, idx) else line
        }
    }

    companion object {
        private val BLOCK_COMMENT_RE = Regex("""/\*[\s\S]*?\*/""")
        private val MAPPER_CLASS_NAME_RE = Regex("""^Mapper\d+$""")

        /**
         * `<project root>/src/main/kotlin`, resolved from the JVM working
         * directory — which Gradle's `Test` task sets to the project directory.
         * Every rule narrows Konsist's production scope to files under this
         * prefix, so nested git-worktree copies under `.claude/worktrees/` are
         * never linted as if they were this branch's sources.
         *
         * Keep in sync with the `main` source set's Kotlin directory. If that
         * ever moves, [productionFiles] fails its non-empty guard rather than
         * passing silently.
         */
        private val PRODUCTION_SOURCE_ROOT: Path =
            Paths.get("").toAbsolutePath().normalize()
                .resolve("src").resolve("main").resolve("kotlin").normalize()

        /** Internal subsystem leaves. cli/ is the CLI aggregator and is excluded. */
        private val LEAF_PACKAGES = listOf("input", "movie", "rewind")

        /**
         * Grandfathered files that legitimately cross-import across the internal
         * subsystem leaves. This list must ONLY SHRINK — never add to it.
         * Migrate the file to use a shared interface or move the dependency, then
         * delete its entry.
         */
        private val LEAF_CROSS_IMPORT_BASELINE = setOf(
            // [FromPendingBuffer] references [PendingInputBuffer] to plumb live
            // recording (the keyboard writes into the movie's per-frame buffer).
            // TODO: expose a small interface in input/ that movie/ implements, then
            // drop the movie import and delete this baseline entry.
            "input/InputSource.kt",
        )
    }
}
