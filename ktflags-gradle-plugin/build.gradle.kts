import com.vanniktech.maven.publish.GradlePlugin
import com.vanniktech.maven.publish.JavadocJar

plugins {
    `kotlin-dsl`
    alias(libs.plugins.maven.publish)
}

// An included build does not inherit the root gradle.properties, and duplicating the coordinates
// here would let them drift from the modules this plugin resolves. Read the one source of truth.
private fun rootProperty(name: String): String =
    file("../gradle.properties").readLines()
        .first { it.startsWith("$name=") }
        .substringAfter("=")
        .trim()

group = rootProperty("GROUP")
version = rootProperty("VERSION_NAME")

dependencies {
    implementation(libs.plugin.kotlin)
    implementation(libs.plugin.ksp)
}

// Bake the version into generated source, read from the one place it is declared. Without this the
// plugin would have to hardcode the coordinate of the processor it applies, and the two would
// drift the first time anyone forgot.
val ktflagsVersion = providers.provider { rootProperty("VERSION_NAME") }

val generateVersions = tasks.register("generateKtflagsVersions") {
    val outputDir = layout.buildDirectory.dir("generated/version/kotlin")
    val version = ktflagsVersion
    inputs.property("ktflagsVersion", version)
    outputs.dir(outputDir)
    doLast {
        val file = outputDir.get()
            .file("com/latenighthack/ktflags/gradle/plugin/KtflagsVersions.kt").asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            |package com.latenighthack.ktflags.gradle.plugin
            |
            |internal const val KTFLAGS_VERSION: String = "${version.get()}"
            |
            """.trimMargin(),
        )
    }
}

kotlin.sourceSets.named("main") {
    kotlin.srcDir(generateVersions)
}

// The consumer plugin is a precompiled script plugin under src/main/kotlin: `kotlin-dsl` registers
// it under an id matching the file name, so no gradlePlugin { } block is needed.

mavenPublishing {
    configure(GradlePlugin(javadocJar = JavadocJar.Empty(), sourcesJar = true))
}
