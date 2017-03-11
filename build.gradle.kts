import org.gradle.script.lang.kotlin.*

buildscript {
    repositories {
        mavenCentral()
        gradleScriptKotlin()
    }

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.1.0")
    }
}

repositories {
    mavenCentral()
    gradleScriptKotlin()
}

apply {
    plugin("kotlin")
}

dependencies {
    compile("org.jetbrains.kotlin:kotlin-stdlib:1.1.0")
    compile("org.apache.commons:commons-compress:+")
    compile("org.tukaani:xz:+")
    compile("no.tornado:tornadofx:+") {
        exclude("org.jetbrains.kotlin:kotlin-stdlib")
        exclude("org.jetbrains.kotlin:kotlin-reflect")
    }

    testCompile("junit:junit:+")
    testCompile("com.natpryce:hamkrest:+")
}