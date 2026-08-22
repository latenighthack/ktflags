plugins {
    id("ktflags.jvm-library")
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("KtflagsSqliteDb") {
            packageName.set("com.latenighthack.ktflags.store.sqlite.db")
            // sqlite-3-38 for ON CONFLICT ... DO UPDATE, which makes a binding write and its
            // revision bump one statement each. Same dialect choice as widgets' client SDK.
            dialect(libs.sqldelight.dialect.sqlite)
        }
    }
}

dependencies {
    api(project(":ktflags-core"))

    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.coroutines.core)
    runtimeOnly(libs.sqlite.jdbc)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(project(":ktflags-test"))
}
