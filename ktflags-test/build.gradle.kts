plugins {
    id("ktflags.kmp-library")
}

kotlin {
    sourceSets {
        val commonMain by getting {
            dependencies {
                api(project(":ktflags-core"))
                api(project(":ktflags-client"))
                api(project(":ktflags-proto"))
                // The contracts are plain assertion functions, so only kotlin-test's *functions*
                // are needed -- never its annotations. That is deliberate: kotlin.test.Test is a
                // per-framework typealias on JVM and Android, and depending on it from a main
                // source set would drag a test-runner choice into a published library.
                api(libs.kotlin.test)
                // A test-support library declares @Test in its *main* source set, and coroutines-test
                // is in the signature of the suspending contract helpers.
                api(libs.coroutines.test)
            }
        }
    }
}
