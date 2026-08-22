package com.latenighthack.ktflags.gradle

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

/**
 * The Apple targets every ktflags KMP module builds for.
 *
 * Single source of truth: adding a target here adds it everywhere. Note that these require
 * ktbuf >= 1.1.8 — earlier versions publish no macOS slices, and the failure is an opaque
 * dependency-resolution error rather than a compile error.
 *
 * Calling a target function (e.g. `iosArm64()`) is idempotent in the Kotlin Multiplatform DSL: it
 * creates the target on first call and returns the existing one afterwards.
 */
fun KotlinMultiplatformExtension.appleTargets(): List<KotlinNativeTarget> = listOf(
    iosArm64(),
    iosX64(),
    iosSimulatorArm64(),
    macosArm64(),
    macosX64(),
)
