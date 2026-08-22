pluginManagement {
    // Internal build conventions (ktflags.kmp-library / ktflags.jvm-library / ktflags.kmp-proto).
    includeBuild("build-logic")
    // The published consumer plugin (com.latenighthack.ktflags). Including it here lets demo-flags
    // apply it by id and dogfood the exact plugin that ships, while
    // ktflags.useProjectDependencies=true makes it resolve the processor via project() rather than
    // the published coordinate.
    includeBuild("ktflags-gradle-plugin")
    repositories {
        // mavenLocal first: ktbuf 1.1.8 (the first version with macOS targets) is not on Central
        // yet, so it is resolved from a local `publishToMavenLocal` in ../ktbuf.
        mavenLocal()
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        mavenLocal()
        google()
        mavenCentral()
        // The Kotlin/JS toolchain downloads its Node distribution from nodejs.org.
        exclusiveContent {
            forRepository {
                ivy("https://nodejs.org/dist/") {
                    name = "Node Distributions at $url"
                    patternLayout { artifact("v[revision]/[artifact](-v[revision]-[classifier]).[ext]") }
                    metadataSources { artifact() }
                    content { includeModule("org.nodejs", "node") }
                }
            }
            filter { includeGroup("org.nodejs") }
        }
    }
}

rootProject.name = "ktflags"

// Runtime + codegen -------------------------------------------------------------------
// The domain model, annotations, evaluator and repository SPI. Deliberately has NO protobuf
// dependency: this is the only ktflags artifact a consumer's `flags` module needs.
include(":ktflags-core")
// KSP processor turning an @FeatureFlagSet data class into a FlagSchema<T>. Plain JVM.
include(":ktflags-ksp")

// Wire --------------------------------------------------------------------------------
// The .proto, the generated ktbuf types, and the single domain <-> wire mapping boundary.
include(":ktflags-proto")

// Consumers ---------------------------------------------------------------------------
include(":ktflags-client")   // FeatureFlagsProvider<T> + the flat-file protobuf cache (KMP)
include(":ktflags-server")   // Ktor plugin, admin JSON + HTML panel, in-process provider (JVM)

// Storage. The repository SPI lives in ktflags-core; these are the two implementations.
include(":ktflags-store-sqlite")     // zero-config default
include(":ktflags-store-postgres")   // opt-in, for multi-instance deployments

// Published test kit: fakes, harnesses and the abstract repository/cache contracts.
include(":ktflags-test")

// Demos proving the codegen and the server end-to-end. Not published.
include(":demo-flags")
include(":demo-server")
