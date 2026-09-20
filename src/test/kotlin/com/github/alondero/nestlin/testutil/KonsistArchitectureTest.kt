package com.github.alondero.nestlin.testutil

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import com.lemonappdev.konsist.api.verify.assertTrue
import org.junit.jupiter.api.Test

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
 * Adding a new rule: copy one of the existing `@Test fun`s, pick the Konsist
 * scope you need (`Konsist.scopeFromProject()`, `scopeFromProduction()`, or
 * `scopeFromTest()`), filter to the declaration kind you care about, and assert.
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
     * match is on the file text — but the scope (`Konsist.scopeFromProject()`)
     * is Konsist's, and the comment-stripping lives here, not in a separate
     * regex walker. The Konsist-specific win is that the file list is the
     * compiler's view of the project, not whatever a `Files.walk` happens to
     * enumerate, so generated/build/config sources stay out of the rule's way.
     */
    @Test
    fun `no production code calls Enum values() — use Enum entries instead`() {
        Konsist.scopeFromProduction()
            .files
            .assertFalse(
                additionalMessage = "Use EnumClass.entries instead of EnumClass.values() " +
                    "(Kotlin 1.9+ EnumEntries, zero allocation). " +
                    "See CLAUDE.md 'Conventions' and SaveState.kt's readEnum helper.",
            ) { file ->
                // Strip KDoc / block / line comments so the rule's own explanation
                // doesn't trip it. Strings are left alone — "values()" inside a
                // string literal is harmless and rare; the false-positive cost
                // outweighs the rare true-positive inside a message constant.
                stripComments(file.text).contains(".values()")
            }
    }

    /**
     * Every mapper class lives in the gamepak subpackage. Pairs with
     * `MapperCoverageLintTest` (which checks GamePak dispatch + MAPPER_SUPPORT.md),
     * this is the AST-half of the same invariant — the file-system half has to
     * use a regex because Konsist's `KoScope.files` won't tell us whether the
     * class is reached from `GamePak.createMapper()`.
     */
    @Test
    fun `Mapper classes reside in the gamepak package`() {
        Konsist.scopeFromProduction()
            .classes()
            .filter { isMapperClass(it.name) }
            .assertTrue(
                additionalMessage = "Mapper classes (MapperNN : ...) must live in " +
                    "com.github.alondero.nestlin.gamepak — they are dispatched by " +
                    "GamePak.createMapper().",
            ) { it.resideInPackage("..gamepak..") }
    }

    /**
     * Sanity check that the public subsystem packages own no cross-package
     * dependencies on each other. `ui/` may import anything; `cli/`, `input/`,
     * `movie/`, `rewind/` are leaves and should not be transitively depending on
     * each other (they collaborate via Nestlin + SaveState, not direct calls).
     */
    @Test
    fun `leaf subsystem packages do not cross-import each other`() {
        val leaves = listOf("cli", "input", "movie", "rewind")
        Konsist.scopeFromProduction()
            .files
            .filter { file -> leaves.any { file.path.contains("/$it/") } }
            .assertFalse(
                additionalMessage = "Leaf subsystem packages (cli, input, movie, rewind) " +
                    "must not import each other directly. Collaborate via Nestlin, " +
                    "SaveState, or a small shared interface in the parent package.",
            ) { file ->
                val origin = leaves.first { file.path.contains("/$it/") }
                val imports = file.imports.map { it.name }
                leaves.any { other -> other != origin && imports.any { it.startsWith("com.github.alondero.nestlin.$other.") } }
            }
    }

    private fun isMapperClass(name: String): Boolean =
        name.startsWith("Mapper") &&
            name.length > 6 &&
            name[6].isDigit()

    /**
     * Strip KDoc / block / line comments. Mirrors the comment-stripping the
     * deleted `KotlinIdiomsLintTest` used, kept narrow on purpose: block
     * comments first, then per-line `//`. Doesn't try to be string-literal
     * aware — `// values()` inside a string literal is content the rule should
     * flag anyway.
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
    }
}
