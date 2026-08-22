plugins {
    id("ktflags.jvm-library")
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":ktflags-core"))
    api(project(":ktflags-proto"))
    // Routing appears in this module's public API (Routing.mountPublic / mountAdmin) and
    // ktbuf-server declares ktor as `implementation`, so it is not supplied transitively.
    api(libs.ktor.server.core)

    implementation(libs.ktbuf.server)
    implementation(libs.ktor.server.cio)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktbuf.test)
    // Drives the admin JSON routes and the plugin in-process, with no port bound.
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.ktbuf.rpc)
    testImplementation(project(":ktflags-client"))
    testImplementation(project(":ktflags-test"))
}
