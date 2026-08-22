plugins {
    id("ktflags.kmp-proto")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                // FlagProtoMapping converts between these and the generated wire types, and both
                // appear in this module's public API.
                api(project(":ktflags-core"))
            }
        }
    }
}
