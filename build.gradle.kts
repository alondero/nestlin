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

// Exclude Mesen-dependent tests from the standard test pipeline.
// These tests require Mesen2 to be installed and configured, so they should
// only be run explicitly via: ./gradlew testMesenComparison
val mesenTests = listOf(
    "com.github.alondero.nestlin.compare.ScreenshotComparisonTest",
    "com.github.alondero.nestlin.compare.StateComparisonTest",
    "com.github.alondero.nestlin.compare.StateCaptureIntegrationTest",
    "com.github.alondero.nestlin.compare.Mesen2StateCapturerSmokeTest",
    "com.github.alondero.nestlin.compare.DebugMesen2CaptureTest",
    "com.github.alondero.nestlin.compare.DebugStateCaptureTest",
    "com.github.alondero.nestlin.compare.NestlinMapper4CaptureTest",
    "com.github.alondero.nestlin.compare.Mapper10RegressionTest",
    "com.github.alondero.nestlin.compare.Mapper33RegressionTest",
    "com.github.alondero.nestlin.compare.Mapper113RegressionTest",
    "com.github.alondero.nestlin.compare.GxRomStateComparisonTest",
    "com.github.alondero.nestlin.compare.MicroMachinesMapper71SmokeTest",
    "com.github.alondero.nestlin.compare.MicroMachinesMapper71StateComparisonTest",
    "com.github.alondero.nestlin.compare.MicroMachinesExtendedCaptureTest",
    "com.github.alondero.nestlin.compare.MicroMachinesDivergenceSweepTest",
    "com.github.alondero.nestlin.compare.MicroMachinesPcDivergenceTest",
    "com.github.alondero.nestlin.compare.MicroMachinesSplitTimingTest",
    "com.github.alondero.nestlin.compare.MicroMachinesAttractHangTest",
    "com.github.alondero.nestlin.compare.BigNoseHangTest"
)

// Also exclude debug/investigation tests that can hang or have pre-existing issues
// Also exclude tests that depend on ROMs not in the repository
val auxiliaryTests = listOf(
    // Debug/investigation tests with external ROM dependencies
    "com.github.alondero.nestlin.gamepak.Mapper4Verification",
    "com.github.alondero.nestlin.gamepak.Mapper4GamesTest",
    // Integration tests requiring external ROMs
    "com.github.alondero.nestlin.Mapper1IntegrationTest",
    // Compare tests that depend on external ROMs (kirby.nes, etc.)
    "com.github.alondero.nestlin.compare.KirbyInstructionTraceTest",
    "com.github.alondero.nestlin.compare.KirbyMesenVsNestlinOamTest",
    "com.github.alondero.nestlin.compare.KirbyOamSnapshotTest",
    "com.github.alondero.nestlin.compare.KirbyPpuCtrlTrackingTest",
    "com.github.alondero.nestlin.compare.KirbyScreenshotTest",
    "com.github.alondero.nestlin.compare.KirbyVBlankTest",
    "com.github.alondero.nestlin.compare.MinimalVBlankSetTest",
    "com.github.alondero.nestlin.compare.PpuCtrlNmiEdgeTest",
    "com.github.alondero.nestlin.compare.ScreenshotCapture",
    "com.github.alondero.nestlin.compare.VBlankPollingTest",
    "com.github.alondero.nestlin.compare.VBlankReadRaceTest",
    "com.github.alondero.nestlin.compare.VBlankTimingTest",
    "com.github.alondero.nestlin.compare.VBlankTimingTest2"
)

tasks.test {
    // Exclude Mesen-dependent tests from standard test run
    mesenTests.forEach { testClass ->
        exclude("**/${testClass.replace('.', '/')}.class")
    }
    auxiliaryTests.forEach { testClass ->
        exclude("**/${testClass.replace('.', '/')}.class")
    }
    useJUnitPlatform()
}

// Separate task to run Mesen comparison tests only when explicitly invoked
tasks.register<Test>("testMesenComparison") {
    group = "verification"
    description = "Runs Mesen comparison tests that require Mesen2 to be installed"
    mesenTests.forEach { testClass ->
        include("**/${testClass.replace('.', '/')}.class")
    }
    useJUnitPlatform()
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
    // Forward the optional Mind Blower Pak ROM override (Mapper 113 regression test).
    val mindBlowerPakRom = System.getenv("NESTLIN_MIND_BLOWER_PAK_ROM")
    if (mindBlowerPakRom != null) {
        environment("NESTLIN_MIND_BLOWER_PAK_ROM", mindBlowerPakRom)
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
    // Forward the optional Mind Blower Pak ROM override (Mapper 113 divergence).
    val mindBlowerPakRom = System.getenv("NESTLIN_MIND_BLOWER_PAK_ROM")
    if (mindBlowerPakRom != null) {
        environment("NESTLIN_MIND_BLOWER_PAK_ROM", mindBlowerPakRom)
    }
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
        println("testroms/nestest.nes:       ${file("testroms/nestest.nes").exists()}")
        println("Parent testroms dir:        ${file("X:/src/nestlin/testroms").exists()} (X:/src/nestlin/testroms)")
        println("NESTLIN_REQUIRE_MESEN2 set: ${!System.getenv("NESTLIN_REQUIRE_MESEN2").isNullOrBlank()}")
        println()
        println("Reminder: the Gradle daemon may hold STALE env vars from a previous shell.")
        println("If a value above looks wrong or a test SKIPs unexpectedly: ./gradlew --stop, then re-run.")
    }
}
