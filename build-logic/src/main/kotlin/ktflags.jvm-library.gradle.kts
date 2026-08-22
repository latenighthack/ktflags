// Convention for a plain JVM library published to Maven Central (the KSP processor, the server,
// the two store implementations). Publishing is property-driven — see ktflags.kmp-library.
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("com.vanniktech.maven.publish")
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
