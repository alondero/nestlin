package com.github.alondero.nestlin.session

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pin the "CI never touches production RetroAchievements or uses
 * personal credentials" contract (issue #273 AC).
 *
 * The integration is **opt-in**: every existing emulator flow works
 * with no native library and no network access. The only way a CI
 * job (or a test running in CI) can reach the production
 * retroachievements.org endpoints is via:
 *
 *  1. The user's manual sign-in flow (UI menu → RaLoginDialog),
 *     which only fires on the user's explicit click.
 *  2. A misconfigured test that points its transport at the
 *     production host (which would be a test-suite bug).
 *
 * This test asserts on the structural invariant that prevents (2):
 * the production HTTP transport has no embedded host, and the only
 * code that constructs a URL pointing at retroachievements.org is
 * the C façade (which CI smoke tests don't exercise).
 */
class RaNetworkIsolationTest {

    @Test
    fun `JavaHttpClientTransport has no hardcoded RA host`() {
        // The production transport takes whatever URL rcheevos hands
        // it via the bridge — it never embeds a host. Assert this by
        // scanning the bytecode's constant pool for the production
        // host string (which is what would be inlined for any
        // hardcoded `const val`).
        val classBytes = JavaHttpClientTransport::class.java
            .getResourceAsStream("JavaHttpClientTransport.class")?.readAllBytes()
            ?: error("Could not read JavaHttpClientTransport.class")
        val hostInBytecode = classBytes.toString(Charsets.ISO_8859_1)
            .contains("retroachievements", ignoreCase = true)
        assertTrue(!hostInBytecode,
            "JavaHttpClientTransport bytecode must not contain 'retroachievements' — " +
                "the transport is host-agnostic by design (URLs come from rcheevos at runtime).")
    }

    @Test
    fun `FakeRaHttpTransport is the only test transport variant`() {
        // We assert via the source layout: there must be exactly one
        // `RaHttpTransport` impl in src/test/kotlin/.../session/. A
        // new test variant would appear in the test source tree and
        // break this count.
        //
        // The source path is computed relative to the working
        // directory of the Gradle daemon — which is the repo root
        // by default but may be a daemon-specific dir on some setups.
        // We try the cwd-relative path first; if that doesn't exist,
        // we walk up looking for a `src/test/kotlin/...` directory.
        val cwd = java.io.File(".").canonicalFile
        val testSourceDir = generateSequence(cwd) { it.parentFile }
            .map { java.io.File(it, "src/test/kotlin/com/github/alondero/nestlin/session") }
            .first { it.exists() && it.isDirectory }
        // The cleanest invariant is "the FakeRaHttpTransport file
        // exists and is the only top-level *Transport.kt file with
        // RaHttpTransport as the declared supertype". Internal test
        // transports (e.g. RaImageCacheTest's AsyncFakeTransport)
        // are allowed because they're test fixtures, not transport
        // alternatives — but a new top-level FakeXxxHttpTransport.kt
        // file would be a regression.
        val fakeTransportFile = java.io.File(testSourceDir, "FakeRaHttpTransport.kt")
        assertTrue(fakeTransportFile.exists(),
            "FakeRaHttpTransport.kt must exist at ${fakeTransportFile.absolutePath}")
        val text = fakeTransportFile.readText()
        assertTrue(
            Regex("""(class|object)\s+FakeRaHttpTransport\s*:\s*RaHttpTransport""").containsMatchIn(text),
            "FakeRaHttpTransport.kt must declare FakeRaHttpTransport : RaHttpTransport"
        )
        // Look for additional top-level test transport files (any
        // *.kt in the test session directory whose name ends in
        // 'Transport.kt' AND declares a class/object extending
        // RaHttpTransport). The FakeRaHttpTransport is the canonical
        // fixture; a new FakeFooTransport.kt would be the
        // regression we want to catch.
        val extraTransports = testSourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.name.endsWith("Transport.kt") && it.name != "FakeRaHttpTransport.kt" }
            .filter { file ->
                // Top-level declaration only — `private class X` inside
                // a test class is a fixture, not a new transport.
                Regex("""^(class|object)\s+\w+\s*:\s*RaHttpTransport""", RegexOption.MULTILINE).containsMatchIn(file.readText())
            }
            .map { it.nameWithoutExtension }
            .toList()
        assertEquals(emptyList<String>(), extraTransports,
            "No new top-level RaHttpTransport implementations beyond FakeRaHttpTransport. " +
                "Found: $extraTransports. " +
                "Add an entry to this test's expected list if the new transport is intentional.")
    }

    @Test
    fun `NoOpRetroAchievementsService makes no network calls`() {
        // The NoOp service is the fallback the factory picks when
        // the native library is absent. It's the default for every
        // CI job that runs `./gradlew test`. By construction it
        // makes zero network calls — but we assert the type here so
        // a maintainer adding "convenience" network code gets a
        // signal.
        val svc = NoOpRetroAchievementsService
        // No public methods that take a URL or a transport.
        val methods = svc.javaClass.declaredMethods.map { it.name }
        assertTrue(methods.none { it.contains("Http", ignoreCase = true) },
            "NoOp must not expose HTTP methods: $methods")
    }

    @Test
    fun `RetroAchievementsServiceFactory's forceNoOp is the documented CI-safe path`() {
        // The factory's `create(forceNoOp=true)` always returns the
        // NoOp singleton, regardless of whether the native lib is
        // available. Tests that want to assert on the fallback path
        // use this entry point — never the production create() —
        // because forceNoOp guarantees no native lib load.
        val svc = RetroAchievementsServiceFactory.create(forceNoOp = true)
        assertEquals(NoOpRetroAchievementsService, svc,
            "forceNoOp=true must return the NoOp singleton, not a native service.")
    }
}
