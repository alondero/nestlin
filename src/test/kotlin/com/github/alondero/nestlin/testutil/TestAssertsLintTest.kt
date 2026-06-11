package com.github.alondero.nestlin.testutil

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.readText

/**
 * Lint-as-test: keeps NEW tests off the two Kotlin/JUnit-5 footguns that
 * [TestAsserts] exists to replace.
 *
 *  - `import kotlin.test.*` does not compile here (kotlin-test is
 *    intentionally not on the classpath; JUnit 5 + Hamkrest only). Failing
 *    THIS test points at [assertThrowsWithMessage] instead of letting the
 *    developer chase a confusing compiler error.
 *  - `Assertions.fail(<string>)` trips a Kotlin overload-resolution bug
 *    against JUnit 5's `fail(Supplier<String>): V`. Use [failTest] instead.
 *
 * Mirrors the [HeaderConstructionLintTest] shape: walk `src/test/kotlin`,
 * grandfather existing offenders in [BASELINE_FAIL], fail on anything new.
 * The baseline must only SHRINK — migrate a file to TestAsserts, then
 * delete its entry here.
 *
 * Comments (line and block) are stripped before matching so that
 * documentation explaining the bug — e.g. in [TestAsserts]'s KDoc or in
 * `StateComparisonTest`'s "why we throw AssertionFailedError directly" note —
 * does not trip the rule.
 */
class TestAssertsLintTest {

    @Test
    fun `no new kotlin-test imports`() {
        val offenders = scan(KOTLIN_TEST_PATTERN, EXCLUDED)
        // No baseline because there are currently zero offenders. If a
        // legitimate use ever appears, add a BASELINE_KOTLIN_TEST set
        // mirroring BASELINE_FAIL below.
        assertTrue(
            offenders.isEmpty(),
            "Do not import kotlin.test in this project — kotlin-test is not on " +
                "the classpath. Use testutil.assertThrowsWithMessage instead. " +
                "Offender(s): $offenders",
        )
    }

    @Test
    fun `no new Assertions_fail with non-lambda arg`() {
        val offenders = scan(ASSERTIONS_FAIL_PATTERN, EXCLUDED)
        val newOffenders = offenders.filter { it !in BASELINE_FAIL }
        assertTrue(
            newOffenders.isEmpty(),
            "JUnit 5's Assertions.fail(String) overload trips a Kotlin <V> " +
                "type-inference bug — use testutil.failTest(message) instead. " +
                "The lambda overload (Assertions.fail { \"...\" }) is fine. " +
                "New offender(s): $newOffenders",
        )

        val stale = BASELINE_FAIL.filter { it !in offenders }
        assertTrue(
            stale.isEmpty(),
            "Baselined files no longer match the Assertions.fail rule — remove " +
                "them from TestAssertsLintTest.BASELINE_FAIL so the list only " +
                "shrinks: $stale",
        )
    }

    /**
     * Regex-shape regression: the `Assertions.fail(<non-lambda>)` rule must
     * flag string and variable args while leaving all lambda-call forms
     * alone. An earlier draft used `\s*(?!\{)` (whitespace OUTSIDE the
     * lookahead), which let the regex engine backtrack the greedy `\s*` to
     * empty and then match against the space character — falsely flagging
     * `Assertions.fail( { ... })` (paren-space-lambda). The fix moves `\s*`
     * INSIDE the lookahead so the whitespace and the brace are checked as
     * one indivisible unit.
     */
    @Test
    fun `Assertions_fail regex shape — lambdas pass, string args fail`() {
        val pattern = ASSERTIONS_FAIL_PATTERN[0]

        // Forms that SHOULD NOT match (lambda overload — compiles cleanly).
        val allowed = listOf(
            """Assertions.fail { "msg" }""",                  // trailing lambda, no parens
            """Assertions.fail({ "msg" })""",                 // explicit parens, no space
            """Assertions.fail( { "msg" })""",                // paren, SPACE, then lambda — the regression
            """Assertions.fail(  { "msg" })""",               // multiple spaces before lambda
        )
        for (input in allowed) {
            assertTrue(
                !pattern.containsMatchIn(input),
                "Lambda-overload form should NOT trip ASSERTIONS_FAIL_PATTERN: $input",
            )
        }

        // Forms that SHOULD match (string / variable arg — the bug-prone overload).
        val banned = listOf(
            """Assertions.fail("msg")""",                     // string literal
            """Assertions.fail(failMessage)""",                // bare identifier
            """Assertions.fail( "msg")""",                    // leading space then string
            """org.junit.jupiter.api.Assertions.fail("msg")""", // FQN form
        )
        for (input in banned) {
            assertTrue(
                pattern.containsMatchIn(input),
                "Non-lambda string-arg form SHOULD trip ASSERTIONS_FAIL_PATTERN: $input",
            )
        }
    }

    private fun scan(patterns: List<Regex>, excluded: Set<String>): List<String> {
        val root = Paths.get("src/test/kotlin")
        assertTrue(Files.isDirectory(root), "expected to run from the project root; missing $root")
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.extension == "kt" }.toList()
        }
            .map { it to relativePath(root, it) }
            .filter { (_, rel) -> rel !in excluded }
            .filter { (file, _) ->
                val source = stripComments(file.readText())
                patterns.any { it.containsMatchIn(source) }
            }
            .map { (_, rel) -> rel }
            .sorted()
    }

    private fun relativePath(root: Path, file: Path): String =
        root.relativize(file).toString().replace('\\', '/')

    /**
     * Strips Kotlin block (`/* ... */`) and line (`// ...`) comments so the
     * lint patterns only see real code. Doesn't try to be string-literal-aware
     * — a `// Assertions.fail(` inside a string would slip through, but in
     * test code that's content worth flagging anyway.
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

        private val KOTLIN_TEST_PATTERN = listOf(
            Regex("""import\s+kotlin\.test"""),
        )

        /**
         * Matches either:
         *  - `Assertions.fail(<anything-not-whitespace-then-{>` — the bug-prone
         *    overload, but leaves `Assertions.fail { "..." }` AND
         *    `Assertions.fail( { "..." })` (paren-space-lambda) alone (the
         *    Supplier-via-lambda form resolves correctly). The `\s*` lives
         *    INSIDE the lookahead so the engine cannot backtrack the
         *    whitespace away from the brace check, or
         *  - `import org.junit.jupiter.api.Assertions.fail` — the import that
         *    enables bare `fail("...")` calls (which would otherwise be too
         *    generic to lint without false positives).
         */
        private val ASSERTIONS_FAIL_PATTERN = listOf(
            Regex("""Assertions\.fail\((?!\s*\{)"""),
            Regex("""import\s+org\.junit\.jupiter\.api\.Assertions\.fail(\b|$)"""),
        )

        /** Files that legitimately mention these patterns. */
        private val EXCLUDED = setOf(
            "com/github/alondero/nestlin/testutil/TestAsserts.kt",
            "com/github/alondero/nestlin/testutil/TestAssertsTest.kt",
            "com/github/alondero/nestlin/testutil/TestAssertsLintTest.kt",
        )

        /**
         * Grandfathered offenders for the Assertions.fail rule (snapshot
         * 2026-06-11). This list must ONLY SHRINK — never add to it. Migrate
         * the file to testutil.failTest and delete its entry here.
         */
        private val BASELINE_FAIL = setOf(
            "com/github/alondero/nestlin/SaveStateTest.kt",
            "com/github/alondero/nestlin/movie/MovieRoundTripTest.kt",
        )
    }
}
