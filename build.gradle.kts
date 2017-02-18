buildscript {
    repositories {
        mavenCentral()
        gradleScriptKotlin()
        maven { setUrl("http://dl.bintray.com/kotlin/kotlin-eap-1.1") }
    }

    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.1.0-rc-91")
    }
}

repositories {
    mavenCentral()
    gradleScriptKotlin()
    maven { setUrl("http://dl.bintray.com/kotlin/kotlin-eap-1.1") }
}

apply {
    plugin("kotlin")
}

dependencies {
    compile("org.jetbrains.kotlin:kotlin-stdlib:1.1.0-rc-91")
    compile("org.apache.commons:commons-compress:+")
    compile("org.tukaani:xz:+")
    compile("no.tornado:tornadofx:+") {
        exclude("org.jetbrains.kotlin:kotlin-stdlib")
        exclude("org.jetbrains.kotlin:kotlin-reflect")
    }

    testCompile("junit:junit:+")
    testCompile("com.natpryce:hamkrest:+")
}