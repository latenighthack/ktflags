// A runnable Ktor server proving the plugin end-to-end: `./gradlew :demo-server:run`.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":ktflags-server"))
    implementation(project(":ktflags-store-sqlite"))
    implementation(project(":demo-flags"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.coroutines.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktbuf.test)
    testImplementation(libs.ktbuf.rpc)
    testImplementation(project(":ktflags-client"))
    testImplementation(project(":ktflags-test"))
}

application {
    mainClass.set("com.latenighthack.ktflags.demo.server.MainKt")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
