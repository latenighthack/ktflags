plugins {
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.maven.publish) apply false
}

// Published modules derive their coordinates from GROUP / VERSION_NAME in gradle.properties
// (consumed by the maven-publish plugin). The values below keep the non-published demo modules
// on the same group + version.
allprojects {
    group = providers.gradleProperty("GROUP").get()
    version = providers.gradleProperty("VERSION_NAME").get()

    // Gradle 9 strict validation: every sources jar reads the KSP metadata output directory
    // without declaring a dependency on the task that fills it. Wire it up (Gradle's own
    // recommended fix) so the publish and build task graphs validate.
    //
    // Note the two spellings: the KMP root task is `sourcesJar`, while the per-variant ones are
    // `jvmSourcesJar`, `androidReleaseSourcesJar` and friends -- matching only the lowercase form
    // silently misses every one of those, and the failure surfaces at publish time.
    tasks.matching { it.name == "sourcesJar" || it.name.endsWith("SourcesJar") }.configureEach {
        dependsOn(tasks.matching { it.name.startsWith("ksp") && it.name.endsWith("KotlinMetadata") })
    }

    // The demos exist to prove the codegen and the server end to end; nobody should depend on
    // them. They still apply the KMP convention (and so the publish plugin), so switch publishing
    // off explicitly rather than relying on nobody running the task.
    if (name.startsWith("demo-")) {
        tasks.matching { it.name.startsWith("publish") }.configureEach { enabled = false }
    }
}
