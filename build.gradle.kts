import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

buildscript {
    var kotlin_version: String by extra
    kotlin_version = "1.2.51"

    repositories {
        mavenCentral()
    }
    dependencies {
        classpath(kotlinModule("gradle-plugin", kotlin_version))
    }
}

plugins {
    java
    id("com.github.johnrengelman.shadow") version "4.0.1"
}

group = "net.boreeas"
version = "1.0.2"

apply {
    plugin("kotlin")
    plugin("com.github.johnrengelman.shadow")
}

val kotlin_version: String by extra

repositories {
    mavenCentral()
    jcenter()
    maven { setUrl("https://jitpack.io") }
}

dependencies {
    compile(kotlinModule("stdlib-jdk8", kotlin_version))
    compile("org.kohsuke:akuma:1.10")
    compile("org.apache.commons:commons-lang3:3.7")
    compile("com.xenomachina:kotlin-argparser:2.0.7")
    compile("io.github.seik.kotlin-telegram-bot:telegram:0.3.2")
    compile("net.sf.biweekly:biweekly:0.6.2")
    compile("org.jetbrains.exposed:exposed:0.10.5")
    compile("org.xerial:sqlite-jdbc:3.23.1")
    testCompile("junit", "junit", "4.12")
}

configure<JavaPluginConvention> {
    sourceCompatibility = JavaVersion.VERSION_1_8
}
tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

val shadowJar: ShadowJar by tasks
shadowJar.apply {
    manifest.attributes.apply {
        put("Implementation-Title", "WG Daemon")
        put("Implementation-Version", version)
        put("Main-Class", "MainKt")
    }

    baseName = project.name + "-all"
}