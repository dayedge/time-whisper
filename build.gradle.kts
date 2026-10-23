@file:Suppress("DEPRECATION", "DEPRECATION_ERROR") // Intel macOS remains a required deliverable.
import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework
import java.util.Base64

plugins {
    kotlin("multiplatform") version "2.4.20"
    `maven-publish`
}
group = "org.github.dayedge"
version = "0.7.0"

val generateFixtures = tasks.register("generateFixtures") {
    inputs.files("fixtures/en-date-expressions.txt")
    val fixtureDirectory = layout.projectDirectory.dir("fixtures")
    val output = layout.buildDirectory.dir("generated/fixtures")
    outputs.dir(output)
    doLast {
        val data = Base64.getEncoder().encodeToString(fixtureDirectory.file("en-date-expressions.txt").asFile.readBytes())
        val source = "internal val dateExpressionFixture = kotlin.io.encoding.Base64.decode(\"$data\").decodeToString()"
        output.get().file("Fixtures.kt").asFile.apply { parentFile.mkdirs(); writeText("package org.github.dayedge.timewhisper\n$source\n") }
    }
}
kotlin {
    explicitApi()
    jvmToolchain(21)
    jvm()
    val xcf = XCFramework("TimeWhisperCore")
    listOf(macosArm64(), macosX64()).forEach { target ->
        target.binaries.framework {
            baseName = "TimeWhisperCore"
            isStatic = true
            binaryOption("bundleId", "org.github.dayedge.timewhisper.core")
            freeCompilerArgs += "-Xoverride-konan-properties=minVersion.macos=15.0"
            xcf.add(this)
        }
    }
    sourceSets {
        commonMain {
            dependencies {
                api("org.jetbrains.kotlinx:kotlinx-datetime:0.8.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
            }
        }
        commonTest {
            kotlin.srcDir(generateFixtures.map { layout.buildDirectory.dir("generated/fixtures").get() })
            dependencies { implementation(kotlin("test")); implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0") }
        }
    }
}
dependencyLocking { lockAllConfigurations() }
publishing.publications.withType<MavenPublication>().configureEach {
    pom {
        name.set("time-whisper")
        description.set("Turns a line of typed text into a task or an event: dates, times, repeats, reminders and more, on device, for Kotlin and Swift.")
        licenses { license { name.set("Mozilla Public License 2.0"); url.set("https://www.mozilla.org/MPL/2.0/") } }
    }
}

val jvmExample = kotlin.targets.getByName("jvm").compilations.create("example") {
    associateWith(kotlin.targets.getByName("jvm").compilations.getByName("main"))
    defaultSourceSet.kotlin.srcDir("examples/jvm")
    // The examples read and write JSON reports; the library itself does not.
    defaultSourceSet.dependencies { implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0") }
}
fun exampleTask(name: String, main: String, vararg arguments: String) = tasks.register<JavaExec>(name) {
    dependsOn(jvmExample.compileTaskProvider)
    classpath = files(jvmExample.output.allOutputs, jvmExample.runtimeDependencyFiles)
    mainClass.set("org.github.dayedge.timewhisper.example.$main")
    args(*arguments)
}
exampleTask("evaluateCorpus", "Evaluate")
exampleTask("readSpans", "ReadSpans")
