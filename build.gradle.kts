import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "1.9.22"
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

repositories {
    mavenCentral()
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

// Friendly alias so `./gradlew uberJar` still works for humans and the CI step.
tasks.register("uberJar") {
    group = "build"
    description = "Builds the standalone runnable fat JAR (build/libs/nestlin-all.jar)"
    dependsOn("shadowJar")
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
    // Fast suite: no Mesen2, no external ROMs. Tags do the exclusion - no class list to maintain.
    useJUnitPlatform {
        excludeTags("mesen", "externalRom")
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
