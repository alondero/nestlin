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
    // Show stdout/stderr from tests (e.g. the diff% println) so we can see results.
    testLogging {
        events("passed", "failed", "skipped", "standard_out", "standard_error")
        showStandardStreams = true
    }
}
