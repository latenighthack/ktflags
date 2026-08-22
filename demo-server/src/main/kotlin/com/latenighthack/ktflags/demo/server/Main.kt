package com.latenighthack.ktflags.demo.server

import com.latenighthack.ktflags.FlagSubject
import com.latenighthack.ktflags.demo.DemoFlagsSchema
import com.latenighthack.ktflags.server.installFeatureFlags
import com.latenighthack.ktflags.store.sqlite.SqliteFlagsRepository
import io.ktor.http.ContentType
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing

/**
 * A runnable end-to-end demonstration: `./gradlew :demo-server:run`.
 *
 * Public flag API on 8080, admin panel on http://localhost:8081/flags. Toggle a flag in the panel
 * and reload `/checkout` to see it take effect.
 */
public fun main() {
    val config = DemoConfig.fromEnv()

    // Constructed before install: the plugin requires a repository that is already migrated and
    // usable, which is why there is no "still starting up" state to guard against.
    val repository = config.databasePath
        ?.let { SqliteFlagsRepository.open(it) }
        ?: SqliteFlagsRepository.inMemory()

    val server = embeddedServer(CIO, port = config.httpPort, host = "0.0.0.0") {
        demoModule(config, repository)
    }

    Runtime.getRuntime().addShutdownHook(
        Thread {
            // Stopping the host application fires ApplicationStopping, which is what closes the
            // admin listener and the repository.
            server.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
        },
    )

    println("ktflags demo: http://localhost:${config.httpPort}/checkout")
    println("admin panel:  http://localhost:${config.adminPort}${"/flags"}")
    server.start(wait = true)
}

internal fun Application.demoModule(
    config: DemoConfig,
    repository: com.latenighthack.ktflags.FlagsRepository,
) {
    val flags = installFeatureFlags(DemoFlagsSchema, repository) {
        adminPort = config.adminPort
        adminHost = config.adminHost
        adminToken = config.adminToken
    }

    routing {
        get("/healthz") { call.respondText("ok") }

        // A request handler reading flags in-process: no network, no serialization, just two
        // indexed reads against the same repository the admin panel writes to.
        get("/checkout") {
            val subject = FlagSubject(
                userId = call.request.queryParameters["user"],
                context = buildMap {
                    call.request.queryParameters["tenant"]?.let { put("tenant", it) }
                },
            )
            val f = flags.evaluate(subject)
            call.respondText(
                buildString {
                    appendLine("subject      : ${subject.userId ?: "(anonymous)"} ${subject.context}")
                    appendLine("newCheckout  : ${f.newCheckout}")
                    appendLine("darkMode     : ${f.darkMode}")
                    appendLine("betaApi      : ${f.betaApi}")
                    appendLine("variant      : ${f.variant}")
                    appendLine("maxItems     : ${f.maxItems}")
                    appendLine("samplingRate : ${f.samplingRate}")
                    appendLine("banner       : ${f.endOfLifeBanner}")
                },
                ContentType.Text.Plain,
            )
        }

        // Shows provenance as well as values -- the "why is this user seeing that?" view.
        get("/checkout/why") {
            val subject = FlagSubject(
                userId = call.request.queryParameters["user"],
                context = buildMap {
                    call.request.queryParameters["tenant"]?.let { put("tenant", it) }
                },
            )
            val detail = flags.evaluateDetailed(subject)
            call.respondText(
                buildString {
                    appendLine("revision: ${detail.revision}")
                    detail.sources.toSortedMap().forEach { (key, source) ->
                        appendLine("$key -> $source")
                    }
                },
                ContentType.Text.Plain,
            )
        }
    }
}

/**
 * Config from the environment, with a `defaults()` for tests.
 *
 * Same shape as the other servers in this workspace: a data class with a `fromEnv` companion that
 * takes the lookup function, so a test can supply a fake environment without touching the process.
 */
public data class DemoConfig(
    val httpPort: Int,
    val adminPort: Int,
    val adminHost: String,
    val adminToken: String?,
    val databasePath: String?,
) {
    public companion object {
        public fun fromEnv(env: (String) -> String? = System::getenv): DemoConfig = DemoConfig(
            httpPort = env("PORT")?.toIntOrNull() ?: 8080,
            adminPort = env("FLAGS_ADMIN_PORT")?.toIntOrNull() ?: 8081,
            // Loopback by default: the admin surface can change any flag for any user.
            adminHost = env("FLAGS_ADMIN_HOST") ?: "127.0.0.1",
            adminToken = env("FLAGS_ADMIN_TOKEN"),
            // No path means an ephemeral in-memory database, which is what you want for a demo.
            databasePath = env("FLAGS_DB_PATH"),
        )

        public fun defaults(): DemoConfig = DemoConfig(
            httpPort = 0,
            adminPort = 0,
            adminHost = "127.0.0.1",
            adminToken = null,
            databasePath = null,
        )
    }
}
