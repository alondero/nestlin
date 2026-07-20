package com.github.alondero.nestlin.testutil

import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import kotlin.io.path.createTempDirectory

/**
 * Bundled-test-ROM accessors for the only in-tree ROM, `nestest.nes`.
 *
 * Why this exists (GitHub issue #21): pre-fix, every test that wanted `nestest.nes` reached for
 * it via `Paths.get("testroms/nestest.nes")` — a working-directory-relative lookup. That
 * degrades silently in any JVM that doesn't start with the project root as `user.dir`
 * (IDE run-configurations, single-test debuggers, agent harnesses, anything orchestrated by a
 * subprocess that `cd`'d elsewhere). The failure mode is an opaque `NoSuchFileException` deep
 * inside `Nestlin.load()` once the test has already been "discovered" by JUnit, which costs
 * more time to track down than to write the fix.
 *
 * Resolution now goes through the standard `src/test/resources/` classpath, the same place the
 * golden CPU log has lived for years (`GoldenLogTest`). Two accessors cover the two real
 * consumption shapes:
 *
 *  - [nestestBytes] — for tests that just need the ROM image to feed `GamePak` /
 *    `Nestlin.loadBytes`. No filesystem side effects, no temp files.
 *  - [nestestPath] — for tests that must hand a `Path` to a real subprocess (CLI commands
 *    like `ReplayCommand`/`BootCheck`, Mesen2 compare tests where Mesen2 opens the file
 *    itself). The resource is copied to a fresh, uniquely-named file under the JVM temp dir
 *    so concurrent test classes don't trip over each other.
 *
 * A failure to find the resource throws [IllegalStateException] with the searched locations
 * listed — better than letting a `null`/empty-array NPE bake for three compile-time skips
 * down the road.
 */
object TestRoms {

    /** Classpath path to the bundled `nestest.nes` (relative to `src/test/resources`). */
    private const val NESTEST_RESOURCE = "nestest.nes"

    /**
     * Returns the raw classpath URL of `nestest.nes`.
     *
     * Intended primarily for assertions and for code that wants to confirm the resource
     * actually got bundled into the test classpath (a missing resource here is usually a
     * `build/` cache staleness problem rather than the code at fault).
     */
    fun nestestResource(): URL {
        return TestRoms::class.java.classLoader.getResource(NESTEST_RESOURCE)
            ?: raiseMissing()
    }

    /**
     * Returns the iNES bytes of the bundled `nestest.nes`.
     *
     * The default shape for tests: `Nestlin.loadBytes(TestRoms.nestestBytes())` /
     * `GamePak(TestRoms.nestestBytes(), "nestest")`. No temp files, no working-directory
     * assumption — bytes come straight out of the test classpath.
     */
    fun nestestBytes(): ByteArray {
        val stream: InputStream = TestRoms::class.java.classLoader.getResourceAsStream(NESTEST_RESOURCE)
            ?: raiseMissing()
        return stream.use { it.readAllBytes() }
    }

    /**
     * Returns a fresh filesystem [Path] whose contents are the bundled `nestest.nes`.
     *
     * Required by the few tests that cannot operate on bytes alone:
     *  - CLI tests that pass the path to [com.github.alondero.nestlin.cli.ReplayCommand] /
     *    [com.github.alondero.nestlin.cli.BootCheck] — the production code opens the file
     *    directly and rewriting it to take `ByteArray` is out of scope.
     *  - Mesen2 compare tests — Mesen2 runs in a separate process and the only way to give
     *    it the ROM is a real disk path it can `open()`.
     *
     * The file is copied (not symlinked) to a unique path under `java.io.tmpdir` so multiple
     * parallel test classes can't collide, and so the path is independent of the project's
     * working directory. The file is NOT deleted on exit — a leakage of ~25 KB in the OS
     * temp dir is preferable to a flaky test that loses its file between fork boundaries.
     * Use [cleanupNestestTempFiles] in teardown if your test class is sensitive.
     */
    fun nestestPath(): Path {
        val source: URL = nestestResource()
        val dir: Path = createTempDirectory("nestlin-test-rom-")
        val target: Path = dir.resolve("nestest.nes")
        source.openStream().use { input ->
            Files.copy(input, target, StandardCopyOption.REPLACE_EXISTING)
        }
        return target
    }

    /**
     * Deletes anything `nestestPath()` ever produced. Safe to call when nothing has been
     * created — intended for use from test-suite `@AfterAll` callbacks that want to be tidy.
     */
    fun cleanupNestestTempFiles() {
        val tmp = Path.of(System.getProperty("java.io.tmpdir"))
        if (!Files.exists(tmp)) return
        Files.newDirectoryStream(tmp, "nestlin-test-rom-*").use { stream ->
            stream.forEach { dir ->
                runCatching { dir.toFile().deleteRecursively() }
            }
        }
    }

    private fun raiseMissing(): Nothing = error(
        "Test resource '$NESTEST_RESOURCE' was not on the test classpath. " +
            "Searched from classloader of ${TestRoms::class.java.name}. " +
            "Expected it at 'src/test/resources/$NESTEST_RESOURCE'. " +
            "If you just added the file, try `./gradlew --stop` then re-run — the Gradle " +
            "daemon sometimes holds a stale classpath snapshot."
    )
}
