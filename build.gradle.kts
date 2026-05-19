plugins {
    kotlin("jvm") version "1.9.22"
    application
    id("org.openjfx.javafxplugin") version "0.1.0"
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

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.apache.commons:commons-compress:1.25.0")
    implementation("org.tukaani:xz:1.9")
    implementation("no.tornado:tornadofx:1.7.20") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }

    // Gamepad/controller support (JInput)
    implementation("net.java.jinput:jinput:2.0.10")
    runtimeOnly("net.java.jinput:jinput:2.0.10:natives-all")

    // JSON parsing for config files
    implementation("com.google.code.gson:gson:2.10.1")

    testImplementation("junit:junit:4.13.2")
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
    "com.github.alondero.nestlin.compare.DebugMesen2CaptureTest",
    "com.github.alondero.nestlin.compare.DebugStateCaptureTest",
    "com.github.alondero.nestlin.compare.NestlinMapper4CaptureTest"
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
}

// Separate task to run Mesen comparison tests only when explicitly invoked
tasks.register<Test>("testMesenComparison") {
    group = "verification"
    description = "Runs Mesen comparison tests that require Mesen2 to be installed"
    mesenTests.forEach { testClass ->
        include("**/${testClass.replace('.', '/')}.class")
    }
    useJUnit()
    // Forward MESEN2_PATH to the test JVM so the runner can locate Mesen2.
    // The Gradle daemon may not inherit shell env vars cleanly between invocations,
    // so we read it explicitly and pass it through.
    val mesen2Path = System.getenv("MESEN2_PATH")
    if (mesen2Path != null) {
        environment("MESEN2_PATH", mesen2Path)
    }
    // Show stdout/stderr from tests (e.g. the diff% println) so we can see results.
    testLogging {
        events("passed", "failed", "skipped", "standard_out", "standard_error")
        showStandardStreams = true
    }
}
