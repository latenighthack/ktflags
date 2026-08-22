package com.latenighthack.ktflags.server

import com.latenighthack.ktbuf.net.GrpcRequestContext
import com.latenighthack.ktflags.FlagSubject
import com.latenighthack.ktflags.proto.subjectOf
import com.latenighthack.ktflags.proto.v1.EvaluateRequest

/**
 * Reads the caller's identity out of an incoming request.
 *
 * NOTE: `context.extensions` is **always empty** here and there is no hook to fill it. ktbuf's
 * `serveAll` drops its `contextProcessor` argument for unary methods, and `toGrpcRequestContext`
 * hardcodes extensions to an empty map. Read [GrpcRequestContext.headers] or
 * [GrpcRequestContext.query] instead -- both are populated.
 */
public fun interface SubjectExtractor {
    public fun extract(context: GrpcRequestContext, request: EvaluateRequest): FlagSubject
}

/** Trusts the identity in the request body. See [FeatureFlagsConfig.subjectExtractor]. */
public val TrustRequestBody: SubjectExtractor =
    SubjectExtractor { _, request -> subjectOf(request.userId, request.context) }

/** Knobs for [FeatureFlagsService] and the Ktor plugin. */
public open class FeatureFlagsConfig {
    /** Mount the read-only `Flags` service on the host application's own routing. */
    public var mountPublicRoutes: Boolean = true

    /**
     * Port for the internal admin listener.
     *
     * `null` binds nothing -- use it when your deployment already runs its own admin server and
     * you want to `mountAdmin(routing)` onto it. `0` picks an ephemeral port, which is what tests
     * want.
     */
    public var adminPort: Int? = 8081

    /**
     * Interface the admin listener binds to. Loopback by default: the admin surface can change
     * any flag for any user, and it must not be reachable from outside the host by accident.
     */
    public var adminHost: String = "127.0.0.1"

    /**
     * Shared secret for the admin surface, checked as the `x-admin-token` header, an
     * `HttpOnly` cookie, or a `?token=` query parameter.
     *
     * Defense in depth on a loopback-bound port, not an authentication system.
     */
    public var adminToken: String? = null

    /** Serve the HTML panel, not just the JSON and protobuf admin APIs. */
    public var adminPanelEnabled: Boolean = true

    /** Path prefix for the admin panel and its JSON API on the admin listener. */
    public var adminPathPrefix: String = "/flags"

    /**
     * Also mount the mutating `FlagsAdmin` service on the **public** listener.
     *
     * Off by default, and it requires [adminToken]: exposing flag mutation on the port your users
     * reach is a decision worth making explicitly.
     */
    public var mountAdminOnPublicPort: Boolean = false

    /**
     * SECURITY KNOB. The default, [TrustRequestBody], believes whatever `user_id` the client sends,
     * so any client can ask for any user's flags.
     *
     * That is fine when flags are not secrets and the client is the one who would be lying to
     * itself. When it is not fine, replace this with one that reads your authenticated principal:
     *
     * ```
     * subjectExtractor = SubjectExtractor { context, request ->
     *     FlagSubject(
     *         userId = context.headers["x-acme-user"],
     *         context = request.context.associate { it.dimension to it.key },
     *     )
     * }
     * ```
     */
    public var subjectExtractor: SubjectExtractor = TrustRequestBody

    /** Memoize the service-wide rows against the current revision. */
    public var serviceCacheEnabled: Boolean = true

    public var clock: () -> Long = System::currentTimeMillis
}
