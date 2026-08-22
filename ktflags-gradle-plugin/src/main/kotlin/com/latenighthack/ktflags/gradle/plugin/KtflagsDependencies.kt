package com.latenighthack.ktflags.gradle.plugin

import org.gradle.api.Project

/**
 * Resolves a ktflags artifact.
 *
 * Inside the ktflags build itself (`ktflags.useProjectDependencies=true`) the demo modules use
 * `project(...)` so they dogfood the working tree rather than whatever was last published.
 * Everyone else gets the coordinate at this plugin's own version.
 */
internal fun Project.ktflagsDependency(projectPath: String, artifactId: String): Any =
    if (findProperty("ktflags.useProjectDependencies") == "true") {
        dependencies.project(mapOf("path" to projectPath))
    } else {
        "com.latenighthack.ktflags:$artifactId:$KTFLAGS_VERSION"
    }

internal fun Project.ktflagsProcessor(): Any = ktflagsDependency(":ktflags-ksp", "ktflags-ksp")

internal fun Project.ktflagsCore(): Any = ktflagsDependency(":ktflags-core", "ktflags-core")
