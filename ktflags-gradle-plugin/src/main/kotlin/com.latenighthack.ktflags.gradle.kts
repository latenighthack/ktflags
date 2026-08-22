import com.latenighthack.ktflags.gradle.plugin.ktflagsCore
import com.latenighthack.ktflags.gradle.plugin.ktflagsProcessor
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.tasks.KotlinCompilationTask

// Wires ktflags' KSP processor into a module holding an @FeatureFlagSet data class.
//
//   plugins {
//       kotlin("multiplatform")          // or kotlin("jvm")
//       id("com.latenighthack.ktflags")
//   }
//
// Apply a Kotlin plugin alongside this one; the wiring differs between Kotlin Multiplatform and
// plain JVM and there is nothing sensible to do without one.
plugins {
    id("com.google.devtools.ksp")
}

/** Guards against wiring the processor twice when both Kotlin plugin markers are present. */
var wired = false

plugins.withId("org.jetbrains.kotlin.multiplatform") {
    wired = true

    dependencies {
        // kspCommonMainMetadata ONLY. The processor emits pure commonMain Kotlin, so adding it to
        // kspJvm / kspIosArm64 / ... as well would emit the same object into two source roots and
        // fail with "Redeclaration: <YourFlags>Schema".
        add("kspCommonMainMetadata", ktflagsProcessor())
    }

    extensions.configure<KotlinMultiplatformExtension> {
        sourceSets.named("commonMain") {
            kotlin.srcDir(layout.buildDirectory.dir("generated/ksp/metadata/commonMain/kotlin"))
            dependencies {
                // The generated schema references FlagSchema, FlagDefinition and FlagValue, and it
                // is part of this module's public API.
                api(ktflagsCore())
            }
        }
    }

    afterEvaluate {
        // Generation must finish before anything compiles the generated commonMain sources.
        // Matched by name through a live collection so a target set that produces no metadata pass
        // simply has nothing to wait on, rather than failing on a missing task.
        tasks.withType(KotlinCompilationTask::class.java).configureEach {
            if (name != "kspCommonMainKotlinMetadata") {
                dependsOn(tasks.matching { it.name == "kspCommonMainKotlinMetadata" })
            }
        }
    }
}

plugins.withId("org.jetbrains.kotlin.jvm") {
    // Both callbacks can fire on a KMP module, and double-wiring emits the same object twice.
    if (!wired) {
        wired = true
        dependencies {
            add("ksp", ktflagsProcessor())
            add("api", ktflagsCore())
        }
        // No srcDir wiring: KSP's JVM integration already adds build/generated/ksp/main/kotlin.
    }
}

afterEvaluate {
    check(wired) {
        "The com.latenighthack.ktflags plugin needs a Kotlin plugin on the same project. Add " +
            "kotlin(\"multiplatform\") or kotlin(\"jvm\") to ${project.path}'s plugins { } block."
    }
}
