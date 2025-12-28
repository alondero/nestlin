plugins {
    kotlin("jvm") version "1.9.22"
    application
}

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

application {
    mainClass.set("com.kotcrab.nestlin.ui.ApplicationKt")
}

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.22")
    implementation("org.apache.commons:commons-compress:1.25.0")
    implementation("org.tukaani:xz:1.9")
    implementation("no.tornado:tornadofx:1.7.20") {
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
        exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
    }

    testImplementation("junit:junit:4.13.2")
    testImplementation("com.natpryce:hamkrest:1.8.0.1")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    kotlinOptions {
        jvmTarget = "21"
    }
}
