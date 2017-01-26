buildscript {

    repositories {
        mavenCentral()
        jcenter()
//        maven {setUrl("https://repo.gradle.org/gradle/repo")}
//        maven {setUrl("http://dl.bintray.com/kotlin/kotlin-eap-1.1")}
    }

    dependencies {
        classpath(kotlinModule("gradle-plugin"))
    }
}

apply {
    plugin("idea")
    plugin("kotlin")
}

version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    compile(kotlinModule("stdlib"))
    compile("org.apache.commons:commons-compress:+")
    compile("org.tukaani:xz:+")
    compile("no.tornado:tornadofx:+")

    testCompile("junit:junit:+")
    testCompile("com.natpryce:hamkrest:+")
}

