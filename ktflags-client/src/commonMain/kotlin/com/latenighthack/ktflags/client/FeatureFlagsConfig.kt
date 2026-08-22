package com.latenighthack.ktflags.client

import com.latenighthack.ktbuf.rpc.HttpRpcClient

/** Wall-clock milliseconds. `expect` because there is no multiplatform clock in this stack. */
internal expect fun epochMillis(): Long

/**
 * How a [FeatureFlagsProvider] reaches its server, who it identifies as, and where it caches.
 *
 * Built with the [FeatureFlagsConfig] function:
 * ```
 * FeatureFlagsConfig {
 *     serverAddress = "flags.example.com:8080"
 *     userIdProvider = { session.userId }
 *     contextProvider = { mapOf("tenant" to session.tenant) }
 * }
 * ```
 */
public class FeatureFlagsConfig internal constructor(
    internal val transport: FlagsTransport,
    internal val cache: FlagsCache,
    internal val userIdProvider: suspend () -> String?,
    internal val contextProvider: suspend () -> Map<String, String>,
    internal val requestTimeoutMillis: Long,
    internal val retryAttempts: Int,
    internal val refreshOnStart: Boolean,
    internal val clock: () -> Long,
    internal val logger: (String, Throwable?) -> Unit,
) {
    public class Builder {
        /**
         * Where the server is.
         *
         * ktbuf's contract is unusual: pass a **schemeless** `host:port` for plain HTTP, or a full
         * `https://host:port` for TLS. A leading `http://` is prepended for you and must not be
         * written explicitly.
         *
         * Ignored when [transport] is set.
         */
        public var serverAddress: String = ""

        /**
         * Bring your own transport. Wins over [serverAddress].
         *
         * This is the test seam and the extension point:
         * `FlagsTransport.of(LocalFlagsServiceRpc(server))` runs the real client against the real
         * server with no network at all, and `FlagsTransport.rpc(myDecoratedClient)` lets you add
         * interceptors.
         */
        public var transport: FlagsTransport? = null

        /** Who we are resolving for. Called on every refresh, so a login can change it. */
        public var userIdProvider: suspend () -> String? = { null }

        /** The context dimensions to resolve against, e.g. `mapOf("tenant" to "acme")`. */
        public var contextProvider: suspend () -> Map<String, String> = { emptyMap() }

        /**
         * Headers to attach to every call, typically an auth token.
         *
         * Only applied when the provider builds its own transport from [serverAddress]; a
         * caller-supplied [transport] is used exactly as given.
         */
        public var headersProvider: suspend () -> Map<String, String> = { emptyMap() }

        /** Base name for the cache file or storage key. */
        public var cacheName: String = "ktflags"

        /**
         * Where the cache lives. **Required on Android** -- pass `context.filesDir.absolutePath`.
         * Every other platform has a sensible default.
         */
        public var cacheDirectory: String? = null

        /** Replaces the platform cache entirely. `InMemoryFlagsCache()` in tests. */
        public var persistence: FlagsCache? = null

        /**
         * Bounds the whole refresh, including [userIdProvider] and [headersProvider].
         *
         * Deliberately not just the network call: a token refresh that hangs would otherwise hang
         * the provider forever.
         */
        public var requestTimeoutMillis: Long = 5_000

        /** Extra attempts after a retriable failure. 0 disables retrying. */
        public var retryAttempts: Int = 2

        /** Whether [FeatureFlagsProvider.start] fetches after loading the cache. */
        public var refreshOnStart: Boolean = true

        public var clock: () -> Long = ::epochMillis

        /** Called for cache and refresh failures. Defaults to swallowing them. */
        public var logger: (String, Throwable?) -> Unit = { _, _ -> }

        internal fun build(): FeatureFlagsConfig {
            val resolved = transport ?: run {
                require(serverAddress.isNotBlank()) {
                    "ktflags: set FeatureFlagsConfig.serverAddress (a schemeless \"host:port\", or " +
                        "a full \"https://host:port\" for TLS) or supply a FlagsTransport."
                }
                val base = HttpRpcClient(serverAddress)
                val headers = headersProvider
                FlagsTransport.rpc(HeaderProvidingRpcClient(base, headers))
            }
            return FeatureFlagsConfig(
                transport = resolved,
                cache = persistence ?: defaultFlagsCache(cacheName, cacheDirectory),
                userIdProvider = userIdProvider,
                contextProvider = contextProvider,
                requestTimeoutMillis = requestTimeoutMillis,
                retryAttempts = retryAttempts.coerceAtLeast(0),
                refreshOnStart = refreshOnStart,
                clock = clock,
                logger = logger,
            )
        }
    }
}

public fun FeatureFlagsConfig(
    build: FeatureFlagsConfig.Builder.() -> Unit,
): FeatureFlagsConfig = FeatureFlagsConfig.Builder().apply(build).build()
