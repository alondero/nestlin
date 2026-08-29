import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import com.google.gson.GsonBuilder
import java.io.StringWriter
import java.net.URL
import java.nio.file.Files
import java.util.zip.ZipInputStream

plugins {
    kotlin("jvm") version "1.9.22"
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenCentral()
}

// Pull Gson into the buildscript classpath so mergeFragments can
// serialize JSON without fighting Groovy's overloaded prettyPrint
// overload-resolution bug (Kotlin only sees the String overload,
// calls with a Map<Any, Any?> arg fail with 'argument type mismatch').
buildscript {
    dependencies {
        classpath("com.google.code.gson:gson:2.10.1")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

javafx {
    version = "21.0.1"
    modules = listOf("javafx.controls", "javafx.graphics")
}

application {
    mainClass.set("com.github.alondero.nestlin.ui.ApplicationKt")
}

// The built-in shadowJar IS the runnable fat JAR. The application plugin makes
// the shadow plugin set Main-Class from application.mainClass automatically.
// archiveFileName pins the output to build/libs/nestlin-all.jar regardless of
// version/classifier, so the release workflow has a stable path to attach.
tasks.named<ShadowJar>("shadowJar") {
    archiveFileName.set("nestlin-all.jar")
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Plain `jar` reads the same resources tree as shadowJar (which copies the
// native library in via copyNativeRa). Declaring the explicit dependency
// here stops Gradle 8.5's "implicit dependency" validation from failing
// the basic `./gradlew build` path on worktrees that have the native
// resources directory in place. The dependency is a no-op when the native
// build is skipped — copyNativeRa itself is opt-in via `:buildNative`.
// writeNativeRaManifest writes MANIFEST.json into the same resources tree,
// so :jar must depend on it explicitly too — otherwise Gradle 8.5 strict
// mode fails the build when :jar picks up the file via processResources.
tasks.named("jar") {
    dependsOn(copyNativeRa, writeNativeRaManifest)
}

// Same dependency for `:test` — the test task transitively reads
// `processTestResources` which can pick up the native tree. Without this,
// `./gradlew build` (which runs `test`) fails the same validation.
// writeNativeRaManifest writes MANIFEST.json into the same tree, so
// :test must depend on it explicitly too — see the comment on :jar above.
tasks.named("test") {
    dependsOn(copyNativeRa, writeNativeRaManifest)
}

// Friendly alias so `./gradlew uberJar` still works for humans and the CI step.
tasks.register("uberJar") {
    group = "build"
    description = "Builds the standalone runnable fat JAR (build/libs/nestlin-all.jar)"
    dependsOn("shadowJar")
}

// ---------------------------------------------------------------------------
// :buildNative — compile the RetroAchievements façade + vendored rcheevos
// v12.4.0 into a per-platform shared library (librcheevos_facade.so /
// rcheevos_facade.dll / librcheevos_facade.dylib). Issue #267.
//
// The output is placed under native/build/<host>/ and copied into
// build/resources/main/native-ra/<host>/ so the runnable JAR picks it up
// on the classpath. When the build environment has no C compiler, the
// task no-ops (exit code 2 from the script) and the JNA service falls
// back to NoOp — a missing native lib never blocks a Gradle build.
//
// Tests that need a real native library tag themselves with @Tag("nativeRa")
// (excluded from `./gradlew test`); they run via `./gradlew testNativeRa`
// which depends on this task and re-includes the tag.
// ---------------------------------------------------------------------------
val nativeRaOutputDir = layout.buildDirectory.dir("native-ra")
val nativeRaHostDir = nativeRaOutputDir.map {
    when {
        org.gradle.internal.os.OperatingSystem.current().isWindows -> it.dir("windows")
        org.gradle.internal.os.OperatingSystem.current().isMacOsX -> it.dir("macos")
        else -> it.dir("linux")
    }
}

// Per-platform platformId emitted into MANIFEST.fragment.json by the
// build scripts and matched against RaManifest.currentPlatformId() at
// load time. Used by :fetchNativeRa to pick the right GitHub Release
// asset for the current host.
val nativeRaPlatformId: String = when {
    org.gradle.internal.os.OperatingSystem.current().isWindows -> "windows-x86_64"
    org.gradle.internal.os.OperatingSystem.current().isMacOsX -> "macos-universal"
    else -> "linux-x86_64"
}

// GitHub Release coordinates for fetching the pre-built native library
// when no local build exists. Override NESTLIN_RA_RELEASE_TAG to pin a
// specific release (default: latest). Override NESTLIN_RA_REPO for a
// fork / private mirror.
val nativeRaReleaseTag: String =
    (System.getenv("NESTLIN_RA_RELEASE_TAG") ?: "latest")
val nativeRaRepo: String =
    (System.getenv("NESTLIN_RA_REPO") ?: "alondero/nestlin")

tasks.register("buildNative") {
    group = "build"
    description = "Compiles the native RetroAchievements façade + vendored rcheevos v12.4.0"
    val rchDir = file("${project.projectDir}/native/rcheevos")
    val facadeDir = file("${project.projectDir}/native/ra_facade")

    outputs.dir(nativeRaHostDir)

    doLast {
        val outDir = nativeRaHostDir.get().asFile
        outDir.mkdirs()

        // Pick the platform-appropriate build script. The PowerShell
        // variant is for Windows; the bash variant is for Linux/macOS.
        // Both scripts are committed alongside the C sources so the
        // build is reproducible on a fresh checkout of either host.
        val isWindows = org.gradle.internal.os.OperatingSystem.current().isWindows
        val cmd = if (isWindows) {
            val script = file("${project.projectDir}/tools/build-native-ra.ps1")
            listOf(
                "powershell.exe", "-ExecutionPolicy", "Bypass",
                "-File", script.absolutePath,
                "-OutputDir", outDir.absolutePath,
                "-RchDir", rchDir.absolutePath,
                "-FacadeDir", facadeDir.absolutePath,
            )
        } else {
            val script = file("${project.projectDir}/tools/build-native-ra.sh")
            listOf(
                "bash", script.absolutePath,
                "-o", outDir.absolutePath,
                "-r", rchDir.absolutePath,
                "-f", facadeDir.absolutePath,
            )
        }
        val proc = ProcessBuilder(cmd)
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().forEachLine { println(it) }
        val rc = proc.waitFor()
        if (rc == 2) {
            // No compiler on PATH — graceful skip. The JNA side will see
            // a missing native library and degrade to NoOp. CRITICAL:
            // this must NOT fail the build — a CI runner without a C
            // compiler runs the JVM tests normally; only the native
            // contract tests are skipped (they're @Tag("nativeRa")).
            logger.warn("[buildNative] No C compiler on PATH; native RA lib not built. JNA will fall back to NoOp.")
        } else if (rc != 0) {
            throw GradleException("buildNative failed (exit=$rc). See output above.")
        }
    }
}

// Copy the freshly-built native library into the resources tree so the
// runnable JAR ships with it. A no-op when buildNative skipped (no
// compiler on the host).
//
// Note: deliberately NOT wired into processResources. The native library
// is opt-in — running `./gradlew test` on a CI runner without gcc must
// work without triggering the native build. Developers who want the JAR
// to ship the library invoke `:shadowJar` after `:buildNative` + `:copyNativeRa`.
val copyNativeRa = tasks.register("copyNativeRa") {
    group = "build"
    description = "Copies the built native RA library into the resources tree"
    dependsOn("buildNative", "fetchNativeRa")
    val resDir = layout.buildDirectory.dir("resources/main/native-ra")
    outputs.dir(resDir)
    doLast {
        val src = nativeRaHostDir.get().asFile
        if (!src.exists()) {
            logger.warn("[copyNativeRa] No built artifact at $src; skipping (JNA will fall back to NoOp).")
            return@doLast
        }
        // Copy into a per-host subdirectory so the resourcePath emitted
        // by the build scripts into MANIFEST.fragment.json (e.g.
        // "native-ra/linux/librcheevos_facade.so") resolves at runtime.
        // The host segment matches nativeRaPlatformId above.
        val hostSegment = when (nativeRaPlatformId) {
            "windows-x86_64" -> "windows"
            "linux-x86_64" -> "linux"
            "macos-universal" -> "macos"
            else -> error("Unknown platformId: $nativeRaPlatformId")
        }
        val dst = resDir.get().asFile.resolve(hostSegment)
        dst.mkdirs()
        src.listFiles()?.forEach { f ->
            if (f.isFile) f.copyTo(dst.resolve(f.name), overwrite = true)
        }
    }
}

// Download the pre-built native library for the current host from the
// GitHub Releases asset named `rcheevos_facade-<platformId>.zip`. Runs
// BEFORE copyNativeRa so a host with no working C toolchain (broken
// MinGW, missing Xcode CLT, etc.) still gets a library to bundle.
//
// Behaviour:
//  - If `:buildNative` already produced the lib for this host (e.g. a
//    developer with a working toolchain), this task is a no-op.
//  - Otherwise, hit the GitHub Releases API for the configured tag
//    (default: `latest`) and download the matching platform asset.
//  - The zip's MANIFEST.fragment.json is extracted alongside the lib
//    so :writeNativeRaManifest can merge it into the shipped MANIFEST.json.
//  - On any failure (no release, no asset for this platform, network
//    error), log a warning and skip — the JAR ships without the
//    native lib and the JNA-side loader falls back to NoOp. The build
//    does NOT fail; a release JAR without the native lib is still a
//    valid build (it just doesn't have RA).
//
// Set NESTLIN_RA_RELEASE_TAG to pin a specific release (default: latest).
// Set NESTLIN_RA_REPO for a fork / private mirror (default: alondero/nestlin).
val fetchNativeRa = tasks.register("fetchNativeRa") {
    group = "build"
    description = "Downloads the pre-built native RA library from GitHub Releases when no local build exists"
    val outDir = nativeRaHostDir
    outputs.dir(outDir)
    doLast {
        val hostDir = outDir.get().asFile
        // If :buildNative already produced the lib, nothing to do.
        val existingLib = hostDir.listFiles()?.firstOrNull { f ->
            f.isFile && (f.name.endsWith(".dll") || f.name.endsWith(".so") || f.name.endsWith(".dylib"))
        }
        if (existingLib != null) {
            logger.lifecycle("[fetchNativeRa] Local native lib already present at $existingLib; skipping download.")
            return@doLast
        }
        val releaseUrl = if (nativeRaReleaseTag == "latest") {
            "https://api.github.com/repos/$nativeRaRepo/releases/latest"
        } else {
            "https://api.github.com/repos/$nativeRaRepo/releases/tags/$nativeRaReleaseTag"
        }
        val assetName = "rcheevos_facade-$nativeRaPlatformId.zip"
        logger.lifecycle("[fetchNativeRa] Fetching release metadata from $releaseUrl")
        try {
            val releaseJson = URL(releaseUrl).openStream().use { stream ->
                stream.bufferedReader().readText()
            }
            // Use Groovy's JsonSlurper (built into Gradle's buildscript
            // classpath) rather than a hand-rolled regex. The regex
            // approach assumed "name" appears before "browser_download_url"
            // in the GitHub API JSON; if the API ever reorders fields
            // or reformats whitespace, the asset URL is silently missed
            // and the build ends up with no native lib. JsonSlurper
            // handles whatever the API emits.
            val slurper = groovy.json.JsonSlurper()
            val release = try {
                slurper.parseText(releaseJson) as? Map<*, *>
            } catch (e: Exception) {
                logger.warn("[fetchNativeRa] Release JSON not parseable: ${e.message}")
                return@doLast
            }
            if (release == null) return@doLast
            @Suppress("UNCHECKED_CAST")
            val assets = release["assets"] as? List<Map<*, *>> ?: emptyList()
            val assetUrl = assets.firstOrNull { (it["name"] as? String) == assetName }
                ?.get("browser_download_url") as? String
            if (assetUrl == null) {
                logger.warn("[fetchNativeRa] Release does not include asset '$assetName' " +
                    "(platform=$nativeRaPlatformId). JNA will fall back to NoOp.")
                return@doLast
            }
            logger.lifecycle("[fetchNativeRa] Downloading $assetUrl")
            val zipBytes = URL(assetUrl).openStream().use { stream ->
                stream.readAllBytes()
            }
            hostDir.mkdirs()
            val tmpZip = File.createTempFile("native-ra-", ".zip")
            tmpZip.writeBytes(zipBytes)
            try {
                ZipInputStream(tmpZip.inputStream()).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val target = File(hostDir, entry.name)
                            target.outputStream().use { out -> zis.copyTo(out) }
                        }
                        entry = zis.nextEntry
                    }
                }
            } finally {
                tmpZip.delete()
            }
            logger.lifecycle("[fetchNativeRa] Downloaded and extracted to $hostDir")
        } catch (e: Throwable) {
            // Defensive: a release-not-yet-published, network failure,
            // or unexpected JSON shape must NOT break the build. The
            // JAR ships without the native lib; the runtime falls back
            // to NoOp via RaManifest.LoadResult.Reason.LIBRARY_MISSING.
            logger.warn("[fetchNativeRa] Pre-built native lib unavailable " +
                "(${e.javaClass.simpleName}: ${e.message}). JNA will fall back to NoOp.")
        }
    }
}

// Aggregate the per-platform MANIFEST.fragment.json files emitted by the
// build scripts into a single MANIFEST.json that ships in the runnable
// JAR. The runtime loader (RaManifest) reads this file to validate the
// bundled library's SHA-256 + pinned rcheevos / façade versions before
// handing the binding to JNA. Without this task, the loader would see
// `MANIFEST_MISSING` and fall back to NoOp.
//
// A CI matrix that runs :buildNative on Windows + Linux + macOS produces
// three fragments which this task merges. A local single-host build
// produces one fragment; the merged file still satisfies the loader
// (the loader only needs the entry for the current OS+arch).
val writeNativeRaManifest = tasks.register("writeNativeRaManifest") {
    group = "build"
    description = "Aggregates per-platform MANIFEST.fragment.json into a single MANIFEST.json in resources"
    // Depends on both buildNative (local compile) and fetchNativeRa
    // (downloads pre-built artifacts from GitHub Releases). Without
    // the fetchNativeRa dependency, on a host with neither a working
    // C compiler nor a populated pre-built download, Gradle's DAG
    // could schedule this task before fetchNativeRa has finished
    // populating build/native-ra/<host>/MANIFEST.fragment.json —
    // leaving the merged MANIFEST.json empty and the runtime loading
    // fails with MANIFEST_MISSING.
    dependsOn("buildNative", "fetchNativeRa")
    val manifestOut = layout.buildDirectory.file("resources/main/native-ra/MANIFEST.json")
    outputs.file(manifestOut)
    doLast {
        val fragments = mutableListOf<java.io.File>()
        // Walk every host subdir under build/native-ra/ for a fragment file.
        val nativeRaRoot = layout.buildDirectory.dir("native-ra").get().asFile
        if (!nativeRaRoot.exists()) {
            logger.warn("[writeNativeRaManifest] No native-ra build dir at $nativeRaRoot; " +
                "skipping (RaManifest will report MANIFEST_MISSING at runtime).")
            return@doLast
        }
        nativeRaRoot.listFiles()?.forEach { hostDir ->
            if (hostDir.isDirectory) {
                val frag = hostDir.resolve("MANIFEST.fragment.json")
                if (frag.exists()) fragments += frag
            }
        }
        if (fragments.isEmpty()) {
            logger.warn("[writeNativeRaManifest] No MANIFEST.fragment.json files found under $nativeRaRoot; " +
                "skipping. (Older build scripts may not emit fragments; rebuild with the latest tools/build-native-ra.*)")
            return@doLast
        }
        val merged = mergeFragments(fragments)
        val dst = manifestOut.get().asFile
        dst.parentFile.mkdirs()
        // Force UTF-8 (no BOM) so the file round-trips byte-for-byte on
        // Windows runners. The default for File.writeText is the JVM
        // platform charset, which is Windows-1252 on a windows-latest
        // GitHub runner — that corrupts any non-ASCII byte in the
        // merged JSON and the runtime reads back � for the sha256Hex
        // field. Use Files.write with an explicit UTF-8 charset + no
        // BOM.
        Files.write(dst.toPath(), merged.toByteArray(Charsets.UTF_8))
        logger.lifecycle("[writeNativeRaManifest] Wrote $dst (${fragments.size} platform(s))")
    }
}

/**
 * Merge per-platform MANIFEST.fragment.json files into a single JSON
 * object whose schema is what RaManifest parses. The fragments each
 * have a `platforms: [...]` array; the merged result concatenates
 * the arrays, dedupes by `platformId`, and pins the rcheevos /
 * façade version to the value from the C source (currently embedded
 * in ra_facade.c — see RC_FACADE_VERSION_STRING and the literal in
 * ra_facade_rcheevos_version()).
 *
 * Uses Gson (already a dependency) rather than string-splitting the
 * JSON so we tolerate formatting differences (whitespace, key order,
 * indentation) between the bash and PowerShell build scripts.
 */
fun mergeFragments(fragments: List<java.io.File>): String {
    val slurper = groovy.json.JsonSlurper()
    // Use Map<String, Any?> rather than a data class so we tolerate
    // extra fields the upstream scripts may add.
    val merged: MutableMap<String, Any?> = LinkedHashMap()
    merged["schemaVersion"] = 1
    merged["rcheevosVersion"] = "12.4.0"
    merged["facadeVersion"] = "1.0.0"
    merged["platforms"] = mutableListOf<Map<String, Any?>>()
    @Suppress("UNCHECKED_CAST")
    val mergedPlatforms = merged["platforms"] as MutableList<Map<String, Any?>>

    fragments.forEach { frag ->
        // Read bytes explicitly as UTF-8 — default File.readText uses
        // the JVM platform charset (Windows-1252 on windows-latest).
        val text = Files.readString(frag.toPath(), Charsets.UTF_8)
        val parsed = try {
            slurper.parseText(text) as? Map<*, *>
        } catch (e: Exception) {
            logger.warn("[writeNativeRaManifest] Skipping malformed fragment $frag: ${e.message}")
            return@forEach
        }
        if (parsed == null) return@forEach
        @Suppress("UNCHECKED_CAST")
        val platforms = parsed["platforms"] as? List<Map<*, *>> ?: return@forEach
        platforms.forEach { rawEntry ->
            // Normalize to Map<String, Any?> — Groovy's slurper gives
            // us Map<Any, Any> for JSON objects.
            @Suppress("UNCHECKED_CAST")
            val entry = rawEntry as Map<String, Any?>
            // Dedupe by platformId — first occurrence wins.
            val id = entry["platformId"] as? String ?: return@forEach
            if (mergedPlatforms.none { (it as Map<*, *>)["platformId"] == id }) {
                mergedPlatforms += entry
            }
        }
    }
    // Groovy's JsonOutput.prettyPrint has specific overloads
    // (Map, List) — passing `Any` (Kotlin's erased parameter) hits
    // `NoSuchMethodException`. Use the Map overload directly.
    // Use Gson (added to the buildscript classpath above) rather
    // than Groovy's JsonOutput. Kotlin's view of Groovy's overloaded
    // prettyPrint rejects Map<Any, Any?> arguments with
    // "argument type mismatch" — every reflection workaround hits
    // the same root cause. Gson is a regular Kotlin/Java library, so
    // overload resolution works normally.
    return GsonBuilder().setPrettyPrinting().create().toJson(merged)
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    dependsOn(copyNativeRa, writeNativeRaManifest)
}

// Wire the native lib into the runnable JAR. `:shadowJar` is the canonical
// "build a redistributable" task; users who want the JAR to carry the
// native library chain `:buildNative → :copyNativeRa → :shadowJar`. The
// default test path never touches this chain.
tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    dependsOn(copyNativeRa)
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.apache.commons:commons-compress:1.25.0")
    implementation("org.tukaani:xz:1.9")

    // Gamepad/controller support (JInput)
    implementation("net.java.jinput:jinput:2.0.10")
    runtimeOnly("net.java.jinput:jinput:2.0.10:natives-all")

    // JSON parsing for config files
    implementation("com.google.code.gson:gson:2.10.1")

    // RetroAchievements native client (issue #267). The JNA binding is
    // resolved at runtime from a per-platform shared library built by
    // :buildNative (see native/ra_facade). When the native library is
    // unavailable, the JNA-side NativeRetroAchievementsService factory
    // falls back to NoOpRetroAchievementsService — every existing flow
    // works without lib loading.
    implementation("net.java.dev.jna:jna:5.14.0")

    // JUnit 5 (Jupiter). The aggregator pulls in api/params/engine. Hamkrest is
    // framework-agnostic and works with Jupiter unchanged. (Issue #28)
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
    testImplementation("com.natpryce:hamkrest:1.8.0.1")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "21"
    }
}

// Test lanes are TAG-DRIVEN, not hand-listed. There is deliberately no list of test classes here:
// the previous one silently went stale (it omitted the Mapper 24/26/64 regression tests for weeks,
// so testMesenComparison quietly never ran them). Instead each test declares its own lane:
//
//   @Tag("mesen")        - needs the Mesen2 reference emulator. @RequiresMesen2 implies this tag
//                          (it is meta-tagged), and MapperRegressionTestBase carries it so every
//                          mapper regression test inherits it for free.
//   @Tag("externalRom")  - needs a ROM not in git (kirby.nes etc.) or is a debug/investigation
//                          test that can hang; excluded from the fast suite, no dedicated task.
//
// ./gradlew test               -> everything EXCEPT those two tags (fast, hermetic, ROM-free)
// ./gradlew testMesenComparison -> only @Tag("mesen")
// MapperCoverageLintTest fails the build if a compare/Mapper*RegressionTest is not in the mesen
// lane, so "forgot to wire it up" is a red build, not a silent skip.

tasks.test {
    // Fast suite: no Mesen2, no external ROMs, no native RA library.
    // Tags do the exclusion - no class list to maintain. @Tag("nativeRa")
    // skips because the native façade may not be compiled on every host.
    useJUnitPlatform {
        excludeTags("mesen", "externalRom", "nativeRa")
    }
}

// Separate task to run native-RA contract tests. These tests load JNA +
// the rcheevos_facade shared library; the library is built by :buildNative
// and copied into the test classpath. The system property the tests check
// is set explicitly here so the @EnabledIf gate activates.
tasks.register<Test>("testNativeRa") {
    group = "verification"
    description = "Runs native RetroAchievements contract tests (loads rcheevos_facade via JNA)"
    dependsOn("buildNative", "writeNativeRaManifest")
    useJUnitPlatform {
        includeTags("nativeRa")
    }
    // Activate the JNA-side gate. The tests also re-check
    // isNativeLibraryAvailable() at runtime, so the property alone
    // isn't sufficient — but without it the @EnabledIf returns false.
    systemProperty("nestlin.test.nativeRa", "true")
    // Point JNA at the freshly built library so `Native.load("rcheevos_facade")`
    // finds it without falling through to the classpath. The `build/native-ra/<host>`
    // directory is the canonical output of :buildNative.
    val nativeDir = nativeRaHostDir.get().asFile
    if (nativeDir.exists()) {
        systemProperty("jna.library.path", nativeDir.absolutePath)
    }
    testLogging {
        events("passed", "failed", "skipped", "standard_out", "standard_error")
        showStandardStreams = true
    }
}

// Separate task to run Mesen comparison tests only when explicitly invoked
tasks.register<Test>("testMesenComparison") {
    group = "verification"
    description = "Runs Mesen comparison tests that require Mesen2 to be installed"
    useJUnitPlatform {
        includeTags("mesen")
    }
    // Forward MESEN2_PATH to the test JVM so the runner can locate Mesen2.
    // The Gradle daemon may not inherit shell env vars cleanly between invocations,
    // so we read it explicitly and pass it through.
    val mesen2Path = System.getenv("MESEN2_PATH")
    if (mesen2Path != null) {
        environment("MESEN2_PATH", mesen2Path)
    }
    // Forward the optional Fire Emblem Gaiden ROM override (Mapper10RegressionTest)
    // for the same daemon-env-inheritance reason.
    val fireEmblemRom = System.getenv("NESTLIN_FIRE_EMBLEM_ROM")
    if (fireEmblemRom != null) {
        environment("NESTLIN_FIRE_EMBLEM_ROM", fireEmblemRom)
    }
    // Forward the optional Micro Machines ROM override (Mapper 71 compare tests).
    val microMachinesRom = System.getenv("NESTLIN_MICRO_MACHINES_ROM")
    if (microMachinesRom != null) {
        environment("NESTLIN_MICRO_MACHINES_ROM", microMachinesRom)
    }
    // Forward the optional Don Doko Don ROM override (Mapper 33 regression test).
    val donDokoDonRom = System.getenv("NESTLIN_DON_DOKO_DON_ROM")
    if (donDokoDonRom != null) {
        environment("NESTLIN_DON_DOKO_DON_ROM", donDokoDonRom)
    }
    // Strict mode for @RequiresMesen2: when set, a missing Mesen2 hard-fails the
    // comparison tests instead of skipping them (guards against a broken
    // MESEN2_PATH silently false-greening the suite).
    val requireMesen2 = System.getenv("NESTLIN_REQUIRE_MESEN2")
    if (requireMesen2 != null) {
        environment("NESTLIN_REQUIRE_MESEN2", requireMesen2)
    }
    // Show stdout/stderr from tests (e.g. the diff% println) so we can see results.
    testLogging {
        events("passed", "failed", "skipped", "standard_out", "standard_error")
        showStandardStreams = true
    }
}

// Divergence localizer: capture Nestlin + Mesen2 at a frame, print a side-by-side
// table of render-output state and "LIKELY CAUSE" classifications.
// Usage: ./gradlew diverge -Prom=X:/src/nestlin/testroms/kirby.nes -Pframe=120 [-Pout=DIR]
tasks.register<JavaExec>("diverge") {
    group = "verification"
    description = "Runs the DivergenceLocalizer against a ROM (use -Prom=, optional -Pframe=, -Pout=)"
    classpath = sourceSets["test"].runtimeClasspath
    mainClass.set("com.github.alondero.nestlin.compare.DivergeMainKt")

    val rom = project.findProperty("rom") as String?
    val frame = project.findProperty("frame") as String?
    val out = project.findProperty("out") as String?
    doFirst {
        if (rom == null) {
            throw GradleException("Missing -Prom=<path-to-rom>. Usage: ./gradlew diverge -Prom=rom.nes [-Pframe=120] [-Pout=DIR]")
        }
    }
    args = buildList {
        if (rom != null) add(rom)
        if (frame != null) { add("--frame"); add(frame) }
        if (out != null) { add("--out"); add(out) }
    }
    // Forward MESEN2_PATH like testMesenComparison does (the Gradle daemon may not
    // inherit shell env vars cleanly between invocations).
    val mesen2Path = System.getenv("MESEN2_PATH")
    if (mesen2Path != null) {
        environment("MESEN2_PATH", mesen2Path)
    }
}

// RA performance benchmark (issue #273 AC: "A repeatable benchmark
// uses a real-sized achievement set and records p95 evaluation latency
// and audio health"). Boots a ROM headless, ticks N frames through
// the production per-frame evaluateFrame path, and prints the p95 +
// throughput + silent-reads count.
//
// Usage: ./gradlew raBench -Prom=src/test/resources/nestest.nes [-Pframes=1000] [-Pwarmup=120]
tasks.register<JavaExec>("raBench") {
    group = "verification"
    description = "Runs the RA performance benchmark against a ROM (use -Prom=, optional -Pframes=, -Pwarmup=)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.github.alondero.nestlin.cli.RaBenchKt")

    // Args are evaluated lazily at execution time via doFirst so a
    // missing -Prom= errors with a usage message instead of failing
    // task configuration.
    doFirst {
        val rom = project.findProperty("rom") as String?
            ?: throw GradleException("Missing -Prom=<path-to-rom>. Usage: ./gradlew raBench -Prom=rom.nes [-Pframes=1000] [-Pwarmup=120]")
        val frames = project.findProperty("frames") as String?
        val warmup = project.findProperty("warmup") as String?
        args = buildList {
            add("--rom"); add(rom)
            if (frames != null) { add("--frames"); add(frames) }
            if (warmup != null) { add("--warmup"); add(warmup) }
        }
    }
}

// Native RA smoke runner (issue #273 AC: "Each release platform runs
// a native smoke test"). Boots no ROM; just exercises the C façade.
//
// Usage: ./gradlew nraSmoke [-Prom=path/to/rom.nes]
tasks.register<JavaExec>("nraSmoke") {
    group = "verification"
    description = "Runs the native RetroAchievements smoke runner (optional -Prom=)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.github.alondero.nestlin.cli.NativeRaSmokeKt")

    val rom = project.findProperty("rom") as String?
    args = buildList {
        if (rom != null) { add("--rom"); add(rom) }
    }
}

// Oracle-free boot smoke: boot a ROM headless for N frames and print a PASS|WARN|FAIL verdict
// (loaded / rendered / non-blank / banks-moved / NMI+IRQ counts). Needs NO Mesen2 and NO ROM
// library — point it at any .nes. This is the self-check a delegated mapper task must run and
// cite before claiming success (the strong Mesen2 gates skip when the oracle/ROM is absent).
// Usage: ./gradlew bootcheck -Prom=X:/src/nestlin/testroms/kirby.nes [-Pframes=120]
tasks.register<JavaExec>("bootcheck") {
    group = "verification"
    description = "Headless oracle-free boot verdict for a ROM (use -Prom=, optional -Pframes=)"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.github.alondero.nestlin.cli.BootCheckKt")

    val rom = project.findProperty("rom") as String?
    val frames = project.findProperty("frames") as String?
    doFirst {
        if (rom == null) {
            throw GradleException("Missing -Prom=<path-to-rom>. Usage: ./gradlew bootcheck -Prom=rom.nes [-Pframes=120]")
        }
    }
    args = buildList {
        if (rom != null) add(rom)
        if (frames != null) { add("--frames"); add(frames) }
    }
    // A FAIL verdict exits non-zero; surface it as a build failure so CI / the agent notices.
    isIgnoreExitValue = false
}

// Sanity-check the local test environment (Mesen2, ROMs, strict mode) before
// chasing phantom SKIPPED/green results.
tasks.register("verifyTestEnv") {
    group = "verification"
    description = "Prints the resolved Mesen2 path, test ROM availability, and related env vars"
    doLast {
        val mesen2Env = System.getenv("MESEN2_PATH")
        val mesen2Prop = System.getProperty("mesen2.path")
        val resolved = mesen2Env ?: mesen2Prop ?: "tools/Mesen2/Mesen.exe"
        val resolvedFile = file(resolved)
        println("MESEN2_PATH env:            ${mesen2Env ?: "(not set)"}")
        println("mesen2.path property:       ${mesen2Prop ?: "(not set)"}")
        println("Resolved Mesen2 path:       ${resolvedFile.absolutePath}")
        println("Mesen2 exists:              ${resolvedFile.exists()}")
        // The bundled nestest.nes now ships from src/test/resources/ via the standard Gradle
        // test-classpath contract (GH #21). The earlier path was CWD-relative and broke any
        // JVM that didn't start with the repo as user.dir.
        println("nestest.nes bundled ROM:    ${file("src/test/resources/nestest.nes").exists()} (src/test/resources/nestest.nes)")
        println("External ROMs dir:          ${file("X:/src/nestlin/testroms").exists()} (X:/src/nestlin/testroms — parent-only, not in git)")
        println("NESTLIN_REQUIRE_MESEN2 set: ${!System.getenv("NESTLIN_REQUIRE_MESEN2").isNullOrBlank()}")
        println()
        println("Reminder: the Gradle daemon may hold STALE env vars from a previous shell.")
        println("If a value above looks wrong or a test SKIPs unexpectedly: ./gradlew --stop, then re-run.")
    }
}
