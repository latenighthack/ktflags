package com.latenighthack.ktflags.server

import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktflags.FlagSchema
import com.latenighthack.ktflags.FlagsRepository
import com.latenighthack.ktflags.proto.v1.FlagsAdminServer
import com.latenighthack.ktflags.proto.v1.FlagsServer
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.ApplicationStopping
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.MonitoringEvent
import io.ktor.server.application.install
import io.ktor.server.application.log
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.Routing
import io.ktor.server.routing.routing
import io.ktor.util.AttributeKey
import kotlinx.coroutines.runBlocking

private const val PLUGIN_NAME = "KtFlags"

/** Where the installed service is stashed for code that only has an [ApplicationCall]. */
public val FeatureFlagsServiceKey: AttributeKey<FeatureFlagsService<*>> =
    AttributeKey("com.latenighthack.ktflags.service")

/** Holds the internal admin listener so the plugin can stop it with the host application. */
internal class AdminListener(private val engine: EmbeddedServer<*, *>?) {
    fun stop() {
        engine?.stop(gracePeriodMillis = 1_000, timeoutMillis = 5_000)
    }

    /** Resolves the bound port, which matters when `adminPort = 0` asked for an ephemeral one. */
    suspend fun boundPort(): Int? =
        engine?.engine?.resolvedConnectors()?.firstOrNull()?.port
}

/**
 * Configuration for [installFeatureFlags]. Extends [FeatureFlagsConfig] with the two things only
 * the plugin supplies.
 */
public class FeatureFlagsPluginConfig : FeatureFlagsConfig() {
    internal var schema: FlagSchema<*>? = null
    internal var repository: FlagsRepository? = null
}

/**
 * Serves feature flags from a Ktor application.
 *
 * Prefer [installFeatureFlags], which keeps the schema's type parameter and hands back a typed
 * [FeatureFlagsService].
 *
 * ### Why the admin listener binds eagerly
 *
 * The second port is opened in the install body rather than on `ApplicationStarted`. ktbuf's own
 * `defaultServer` -- which `runTestWithServer` uses -- installs application modules from inside a
 * `launch { }`, and that coroutine can run *after* `ApplicationStarted` has already fired. A hook
 * registered from there would never run and the admin port would silently never open, in tests
 * only, which is the worst place for it to happen. Teardown uses `ApplicationStopping`, which does
 * fire reliably on `stop()`.
 *
 * There is no "not ready yet" gate for the same reason: the repository must be fully constructed
 * and migrated before install, so there is no window to guard.
 */
public val FeatureFlagsPlugin: ApplicationPlugin<FeatureFlagsPluginConfig> =
    createApplicationPlugin(PLUGIN_NAME, ::FeatureFlagsPluginConfig) {
        val config = pluginConfig
        val schema = requireNotNull(config.schema) {
            "ktflags: use Application.installFeatureFlags(schema, repository) { ... }"
        }
        val repository = requireNotNull(config.repository) {
            "ktflags: a FlagsRepository is required. Use SqliteFlagsRepository.open(path) for the " +
                "zero-config default, or InMemoryFlagsRepository() in tests."
        }

        val service = FeatureFlagsService(schema, repository, config)
        application.attributes.put(FeatureFlagsServiceKey, service)

        if (config.mountPublicRoutes) {
            application.routing { mountPublic(service) }
        }
        if (config.mountAdminOnPublicPort) {
            require(config.adminToken != null) {
                "ktflags: mountAdminOnPublicPort exposes flag mutation on the port your users " +
                    "reach, so it requires an adminToken."
            }
            application.routing { mountAdmin(service) }
        }

        val adminEngine = config.adminPort?.let { port ->
            embeddedServer(CIO, port = port, host = config.adminHost) {
                routing { mountAdmin(service) }
            }.also { it.start(wait = false) }
        }
        val listener = AdminListener(adminEngine)

        application.attributes.put(AdminListenerKey, listener)

        application.log.info(
            "ktflags: schema=${schema.schemaName} flags=${schema.definitions.size} " +
                "admin=" + (config.adminPort?.let { "${config.adminHost}:$it${config.adminPathPrefix}" }
                    ?: "not bound") +
                (if (config.adminToken == null) " (no admin token set)" else ""),
        )

        on(MonitoringEvent(ApplicationStopping)) { _ ->
            // Not a suspending hook, and both calls are blocking-fast.
            listener.stop()
            runBlocking { service.close() }
        }
    }

internal val AdminListenerKey: AttributeKey<AdminListener> =
    AttributeKey("com.latenighthack.ktflags.adminListener")

/**
 * Installs [FeatureFlagsPlugin] and returns the typed service.
 *
 * The returned handle is the one to wire into your DI graph -- it is the only place the schema's
 * type parameter is known, so reading it back out of the application attributes is unavoidably an
 * unchecked cast.
 *
 * ```
 * val flags = installFeatureFlags(AppFlagsSchema, SqliteFlagsRepository.open("/var/lib/app/flags.db")) {
 *     adminPort = 8081
 *     adminToken = System.getenv("FLAGS_ADMIN_TOKEN")
 * }
 * routing {
 *     get("/checkout") {
 *         if (flags.evaluate(FlagSubject(call.userId())).newCheckout) { ... }
 *     }
 * }
 * ```
 */
public fun <T : Any> Application.installFeatureFlags(
    schema: FlagSchema<T>,
    repository: FlagsRepository,
    configure: FeatureFlagsPluginConfig.() -> Unit = {},
): FeatureFlagsService<T> {
    install(FeatureFlagsPlugin) {
        this.schema = schema
        this.repository = repository
        configure()
    }
    @Suppress("UNCHECKED_CAST")
    return attributes[FeatureFlagsServiceKey] as FeatureFlagsService<T>
}

/**
 * The installed service, for interceptors and framework glue that only have the call.
 *
 * Prefer the handle [installFeatureFlags] returns: this one cannot be typed.
 */
public val ApplicationCall.featureFlags: FeatureFlagsService<*>
    get() = application.attributes[FeatureFlagsServiceKey]

/** The port the admin listener actually bound, useful when `adminPort = 0`. */
public suspend fun Application.featureFlagsAdminPort(): Int? =
    attributes.getOrNull(AdminListenerKey)?.boundPort()

/**
 * Mounts the read-only resolution service.
 *
 * `install(WebSockets)` is deliberately not required: every ktflags RPC is unary, and ktbuf's
 * `serveAll` only reaches its `webSocket()` branches for streaming methods.
 */
public fun Routing.mountPublic(service: FeatureFlagsService<*>) {
    // The `as Any` cast is required: serveAll is <Server : Any> and infers awkwardly from an
    // interface. Every consumer of ktbuf in this workspace does the same.
    serveAll(service.flagsServer as Any, FlagsServer.Descriptor)
}

/**
 * Mounts the admin protobuf service, the admin JSON API and the HTML panel.
 *
 * Call this yourself on an existing internal listener when your deployment already runs one, and
 * set `adminPort = null` so the plugin does not bind a third port.
 */
public fun Routing.mountAdmin(service: FeatureFlagsService<*>) {
    serveAll(service.adminServer as Any, FlagsAdminServer.Descriptor)
    ktflagsAdminRoutes(service, service.config)
}
