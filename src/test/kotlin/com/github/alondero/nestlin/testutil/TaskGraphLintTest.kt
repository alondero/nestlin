package com.github.alondero.nestlin.testutil

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Lint-as-test: enforces the explicit `dependsOn` declarations Nestlin needs so
 * Gradle 8.5's implicit-dependency validator stops failing the packaging DAG.
 *
 * Background: PRs #303 and #307 each landed a fix that patched the same
 * pre-existing build-script breakage -- `:jar`, `:test`, and `:shadowJar`
 * read files written by `:writeNativeRaManifest` / `:copyNativeRa` without
 * declaring an explicit dependency. `./gradlew test` happened to paper over
 * the gap (because the unit-test lane transitively pulls the manifest via
 * `processTestResources`), but `./gradlew build` and `./gradlew shadowJar`
 * failed Gradle 8.5's strict mode. Issue #312.
 *
 * Why a source-text lint instead of (or in addition to) a runtime
 * `validateTaskGraph` Gradle task:
 *  - Source text is the contract. A reviewer who edits `build.gradle.kts`
 *    and accidentally removes a `dependsOn` line sees a red CI from this
 *    test on the next `./gradlew test` -- they do not need to remember to
 *    run a separate verification command.
 *  - The runtime task (`./gradlew validateTaskGraph`) covers a different
 *    failure mode: the line exists but the dependency is wired to a
 *    no-op or the wrong task, or a refactor introduces an intermediate
 *    aggregator task that breaks a DIRECT-only check. Both layers
 *    together pin the DAG.
 *
 * Mirrors [HeaderConstructionLintTest]'s shape: walk the build script,
 * strip comments, regex-match the structural edges we require, and fail
 * the build loudly when a critical edge is missing.
 *
 * Known limitation: the brace-counting block extractor is hand-rolled
 * (a Kotlin lexer would be the right tool, but adding one for a single
 * build script is disproportionate -- see commit c8cf593 which removed a
 * similar regex lint for the same reason). It is hardened against string
 * literals: brace tracking skips over `"..."` and `${...}` interpolations,
 * and the line-comment stripper respects `"://"` inside string literals.
 * That is sufficient for the current `build.gradle.kts`; if a future edit
 * introduces a `"""..."""` raw-string containing braces, this lint may
 * miscount and the runtime `validateTaskGraph` task is the safety net.
 */
class TaskGraphLintTest {

    @Test
    fun `jar depends on writeNativeRaManifest so clean builds succeed`() {
        assertHasDependency("jar", "writeNativeRaManifest", REQUIRED_REASON)
    }

    @Test
    fun `jar depends on copyNativeRa so the plain JAR ships the native library`() {
        assertHasDependency("jar", "copyNativeRa", REQUIRED_REASON)
    }

    @Test
    fun `test depends on writeNativeRaManifest so clean builds succeed`() {
        assertHasDependency("test", "writeNativeRaManifest", REQUIRED_REASON)
    }

    @Test
    fun `test depends on copyNativeRa so the test classpath sees the native library`() {
        assertHasDependency("test", "copyNativeRa", REQUIRED_REASON)
    }

    @Test
    fun `shadowJar depends on writeNativeRaManifest so the fat JAR ships MANIFEST dot json`() {
        assertHasDependency("shadowJar", "writeNativeRaManifest", REQUIRED_REASON)
    }

    @Test
    fun `shadowJar depends on copyNativeRa so the fat JAR ships the native library`() {
        assertHasDependency("shadowJar", "copyNativeRa", REQUIRED_REASON)
    }

    /**
     * Validates that `build.gradle.kts` contains an explicit
     * `dependsOn(<provider>)` line inside a `tasks.named("<consumer>") { ... }`
     * (or `tasks.named<...>("<consumer>") { ... }`) block. Looks for the
     * consumer block first, then asserts the provider appears as a
     * `dependsOn(...)` argument inside it.
     *
     * This tolerates redundant re-declarations across multiple `tasks.named`
     * blocks for the same consumer (we already have two for `:jar` and two
     * for `:shadowJar`); the lint just requires the edge to exist at least
     * once across all blocks for that consumer.
     */
    private fun assertHasDependency(consumer: String, provider: String, reason: String) {
        val source = buildScriptSource()
        val consumerBlocks = consumerBlocks(source, consumer)
        assertTrue(
            consumerBlocks.isNotEmpty(),
            "Could not find any `tasks.named(\"$consumer\")` block in build.gradle.kts. " +
                "Has the task been renamed? $reason",
        )
        val providesEdge = consumerBlocks.any { block -> providesEdgeIn(block, provider) }
        assertTrue(
            providesEdge,
            "build.gradle.kts must declare an explicit `dependsOn($provider)` " +
                "inside at least one `tasks.named(\"$consumer\") { ... }` block. " +
                "Without it, Gradle 8.5's implicit-dependency validator fails " +
                "`./gradlew build` / `./gradlew shadowJar`. $reason",
        )
    }

    /**
     * Returns true when `block` contains a `dependsOn(<args>)` call whose
     * argument list mentions [provider] as a top-level identifier.
     *
     * We can't use a single regex that demands `dependsOn(<provider>...)`
     * because a multi-arg `dependsOn(foo, <provider>)` is the common shape
     * in `build.gradle.kts` (see e.g. `dependsOn(copyNativeRa, writeNativeRaManifest)`).
     * We scan the argument list as free text and assert `\bprovider\b` matches.
     *
     * Paren- and string-aware: nested `dependsOn(tasks.named("foo"))` and
     * `dependsOn(providerMap["key"])` (which contains `:` and `}` inside a
     * string) both parse correctly because the scan skips over string
     * literals while tracking depth.
     */
    private fun providesEdgeIn(block: String, provider: String): Boolean {
        val regex = Regex("""(?m)\bdependsOn\s*\(""")
        for (match in regex.findAll(block)) {
            val open = match.range.last // index of the `(` after dependsOn
            var depth = 1
            var i = open + 1
            while (i < block.length && depth > 0) {
                val c = block[i]
                // Skip string literals so an embedded '(' or ')' in a
                // description string does not unbalance the depth counter.
                if (c == '"') {
                    val end = block.indexOf('"', i + 1)
                    if (end < 0) return false // malformed string; bail
                    i = end + 1
                    continue
                }
                // Skip template-string interpolation `"${expr}"`. The nested
                // `expr` can contain anything including unbalanced braces,
                // so we locate the matching '}'.
                if (c == '$' && i + 1 < block.length && block[i + 1] == '{') {
                    var d = 1
                    var j = i + 2
                    while (j < block.length && d > 0) {
                        when (block[j]) {
                            '{' -> d++
                            '}' -> d--
                        }
                        j++
                    }
                    i = j
                    continue
                }
                when (c) {
                    '(' -> depth++
                    ')' -> depth--
                }
                if (depth == 0) break
                i++
            }
            if (depth != 0) continue // unbalanced; skip this call rather than mis-match
            val args = block.substring(open + 1, i)
            // Word-bound the provider so a sibling task named
            // `writeNativeRaManifestExt` doesn't false-positive.
            if (Regex("""\b${Regex.escape(provider)}\b""").containsMatchIn(args)) {
                return true
            }
        }
        return false
    }

    /**
     * Strips block + line comments from the build script before regex-matching,
     * with string-literal awareness so URLs like `https://example.com/foo`
     * (whose `://` would otherwise look like a line comment opener) are
     * preserved verbatim.
     */
    private fun buildScriptSource(): String {
        val root: Path = Paths.get("build.gradle.kts")
        assertTrue(Files.isRegularFile(root), "expected to run from the project root; missing $root")
        val raw = root.toFile().readText(Charsets.UTF_8)
        val noBlock = BLOCK_COMMENT_RE.replace(raw, "")
        val sb = StringBuilder(noBlock.length)
        var i = 0
        while (i < noBlock.length) {
            val c = noBlock[i]
            // Block-style string """...""" — preserve verbatim.
            if (c == '"' && i + 2 < noBlock.length && noBlock[i + 1] == '"' && noBlock[i + 2] == '"') {
                val end = noBlock.indexOf("\"\"\"", i + 3)
                val stop = if (end < 0) noBlock.length else end + 3
                sb.append(noBlock, i, stop)
                i = stop
                continue
            }
            // Regular string literal — copy through, skipping embedded '//'
            // and any line-comment heuristic so the line-by-line stripper
            // below doesn't slice URLs.
            if (c == '"') {
                sb.append('"')
                var j = i + 1
                while (j < noBlock.length && noBlock[j] != '"') {
                    // Skip Kotlin escape sequences so a \" inside a string
                    // doesn't terminate it prematurely.
                    if (noBlock[j] == '\\' && j + 1 < noBlock.length) {
                        sb.append(noBlock, j, j + 2)
                        j += 2
                    } else {
                        sb.append(noBlock[j])
                        j++
                    }
                }
                if (j < noBlock.length) {
                    sb.append('"')
                    j++
                }
                i = j
                continue
            }
            // Block comment opener.
            if (c == '/' && i + 1 < noBlock.length && noBlock[i + 1] == '*') {
                val end = noBlock.indexOf("*/", i + 2)
                i = if (end < 0) noBlock.length else end + 2
                continue
            }
            // Line comment opener — copy everything up to (but not including)
            // the newline so the rest of the source is preserved. The newline
            // itself is appended below so the rest of the scanner keeps its
            // line-aware shape.
            if (c == '/' && i + 1 < noBlock.length && noBlock[i + 1] == '/') {
                val nl = noBlock.indexOf('\n', i)
                val stop = if (nl < 0) noBlock.length else nl
                i = stop
                continue
            }
            sb.append(c)
            i++
        }
        return sb.toString()
    }

    /**
     * Returns the substring of [source] for each `tasks.named("<consumer>") { ... }`
     * (or generic-typed variant) block, scoped to the consumer's matching
     * brace pair. Brace counting is depth-based and string-aware: an opening
     * `{` inside `"..."` or inside `"${...}"` interpolation is skipped so a
     * URL or description string with braces does not unbalance the counter.
     */
    private fun consumerBlocks(source: String, consumer: String): List<String> {
        val regex = Regex(
            """tasks\.named\s*(?:<[^>]*>)?\s*\(\s*"${Regex.escape(consumer)}"\s*\)\s*\{"""
        )
        val out = mutableListOf<String>()
        for (match in regex.findAll(source)) {
            val openBrace = match.range.last
            var depth = 1
            var i = openBrace + 1
            while (i < source.length && depth > 0) {
                val c = source[i]
                if (c == '"') {
                    val end = source.indexOf('"', i + 1)
                    if (end < 0) break
                    i = end + 1
                    continue
                }
                if (c == '$' && i + 1 < source.length && source[i + 1] == '{') {
                    var d = 1
                    var j = i + 2
                    while (j < source.length && d > 0) {
                        when (source[j]) {
                            '{' -> d++
                            '}' -> d--
                        }
                        j++
                    }
                    i = j
                    continue
                }
                when (c) {
                    '{' -> depth++
                    '}' -> depth--
                }
                i++
            }
            if (depth == 0) {
                out += source.substring(match.range.first, i)
            }
        }
        return out
    }

    companion object {
        private val BLOCK_COMMENT_RE = Regex("""/\*[\s\S]*?\*/""")

        /**
         * Shared failure-context string for the implicit-dependency validator
         * story. Every assertion message appends this so an unfamiliar
         * reviewer can trace the failure back to issue #312 (and from there
         * to the original regressions in PRs #303, #307) without grepping
         * the issue tracker.
         */
        private const val REQUIRED_REASON: String =
            "See issue #312 (ci(build): add task-graph and shadowJar dependency validation). " +
                "The original regressions are PRs #303 (triangle DAC) and #307 (OAM DMA)."
    }
}
