plugins {
    id("ktflags.kmp-library")
}

kotlin {
    // JVM and Android share one FileFlagsCache: both have java.io.File and java.nio atomic moves,
    // and duplicating an atomic-write implementation across two source sets is exactly the kind of
    // thing that drifts. The default hierarchy template has no jvm+android group, so add one.
    applyDefaultHierarchyTemplate {
        common {
            group("jvmShared") {
                withJvm()
                withAndroidTarget()
            }
        }
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":ktflags-core"))
                api(project(":ktflags-proto"))
            }
        }
    }
}
