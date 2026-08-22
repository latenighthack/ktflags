plugins {
    id("ktflags.jvm-library")
    alias(libs.plugins.sqldelight)
}

sqldelight {
    databases {
        create("KtflagsPostgresDb") {
            packageName.set("com.latenighthack.ktflags.store.postgres.db")
            dialect(libs.sqldelight.dialect.postgresql)
            // Schema comes ONLY from migrations (.sqm); SQLDelight derives table types from them
            // and emits plain .sql that Flyway applies at boot. Same shape as widgets/service.
            deriveSchemaFromMigrations.set(true)
            migrationOutputDirectory.set(layout.buildDirectory.dir("generated/migrations"))
            migrationOutputFileFormat.set(".sql")
        }
    }
}

// Bundle the SQLDelight-emitted migrations for Flyway. NOTE the ktflags/ prefix: a library that
// shipped these at the conventional `db/migration` would be swept up by any host application
// running Flyway with default locations, and the numbering would collide.
tasks.processResources {
    dependsOn(tasks.matching { it.name.contains("Migrations") && it.name.startsWith("generate") })
    from(layout.buildDirectory.dir("generated/migrations")) { into("ktflags/db/migration") }
}

dependencies {
    api(project(":ktflags-core"))

    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.jdbc.driver)
    implementation(libs.coroutines.core)
    implementation(libs.hikaricp)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.postgresql)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(project(":ktflags-test"))
    testRuntimeOnly(libs.slf4j.simple)
}

// Testcontainers probes a fixed set of well-known Docker socket paths. Docker Desktop on macOS
// listens on a per-user socket instead, and the Gradle daemon does not pick up a DOCKER_HOST
// exported after it started -- so point the test JVM at whatever this machine actually has.
// Without this the Postgres suite skips itself, which looks identical to passing.
tasks.withType<Test>().configureEach {
    val userHome = System.getProperty("user.home")
    val candidates = listOf(
        "$userHome/.docker/run/docker.sock",
        "$userHome/.colima/default/docker.sock",
        "/var/run/docker.sock",
    )
    candidates.firstOrNull { File(it).exists() }?.let { socket ->
        environment("DOCKER_HOST", "unix://$socket")
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    }
    // docker-java defaults to an API version BELOW Docker Desktop's current minimum (1.44), and
    // the daemon answers such requests with an HTTP 400 carrying an empty /info payload -- which
    // Testcontainers reports only as "could not find a valid Docker environment". Pin the version.
    // The env var alone is not enough; docker-java reads this system property.
    systemProperty("api.version", "1.44")
    environment("DOCKER_API_VERSION", "1.44")
    // Reuse and ryuk churn add seconds per run for no benefit on a single-container suite.
    environment("TESTCONTAINERS_RYUK_DISABLED", "true")
}
