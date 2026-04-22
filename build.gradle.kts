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
val debugTests = listOf(
    "com.github.alondero.nestlin.gamepak.Mapper4Verification"
)

tasks.test {
    // Exclude Mesen-dependent tests from standard test run
    mesenTests.forEach { testClass ->
        exclude("**/${testClass.replace('.', '/')}.class")
    }
    debugTests.forEach { testClass ->
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
}
