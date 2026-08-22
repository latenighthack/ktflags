plugins {
    id("ktflags.jvm-library")
}

dependencies {
    implementation(libs.ksp.api)
    testImplementation(kotlin("test-junit5"))
}
