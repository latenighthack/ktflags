package com.latenighthack.ktflags.server

import com.latenighthack.ktbuf.net.RpcResponseException
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktflags.FlagScope
import com.latenighthack.ktflags.FlagSubjectRef
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingContext
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.json.Json

private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    explicitNulls = false
}

/**
 * The admin JSON API and the HTML panel.
 *
 * Serialization is hand-rolled with [Json.encodeToString] plus `respondText` rather than
 * `install(ContentNegotiation)`: a library that installs an application-level plugin either fails
 * because the host already has one, or silently overrides the host's own configuration. Neither is
 * acceptable for a handful of endpoints.
 */
internal fun <T : Any> Route.ktflagsAdminRoutes(
    service: FeatureFlagsService<T>,
    config: FeatureFlagsConfig,
) {
    val prefix = config.adminPathPrefix

    // Deliberately outside the token gate: a liveness probe that needs a secret is not a liveness
    // probe.
    get("$prefix/healthz") { call.respondText("ok") }

    route(prefix) {
        get("") { serveIndex(call, config) }
        get("/") { serveIndex(call, config) }

        get("/api/flags") {
            guarded(config) {
                val view = service.listFlags()
                respond(
                    FlagListDto(
                        schemaName = view.schemaName,
                        revision = view.revision,
                        orphanCount = view.orphanCount,
                        flags = view.flags.map { status ->
                            FlagDto(
                                key = status.definition.key,
                                scope = status.definition.scope.toWire(),
                                type = status.definition.type.toWire(),
                                dimension = status.definition.dimension,
                                description = status.definition.description,
                                codeDefault = status.definition.defaultValue.toDto(),
                                serviceValue = status.serviceValue?.toDto(),
                                serviceUpdatedAtMillis = status.serviceUpdatedAtMillis,
                                serviceUpdatedBy = status.serviceUpdatedBy,
                                overrideCount = status.overrideCount,
                            )
                        },
                    ),
                )
            }
        }

        put("/api/flags/{key}/service") {
            guarded(config) {
                val key = call.parameters["key"].orEmpty()
                val body = json.decodeFromString<SetValueDto>(call.receiveText())
                val value = body.value.toDomainOrNull()
                    ?: throw badRequest("unrecognised value: ${body.value}")
                val revision =
                    service.setOverride(key, FlagSubjectRef.Service, value, body.updatedBy)
                respond(RevisionDto(revision))
            }
        }

        delete("/api/flags/{key}/service") {
            guarded(config) {
                val key = call.parameters["key"].orEmpty()
                val (revision, removed) = service.clearOverride(key, FlagSubjectRef.Service)
                respond(RevisionDto(revision, removed))
            }
        }

        get("/api/subject") {
            guarded(config) {
                val view = service.subject(call.subjectRef())
                respond(
                    SubjectDto(
                        subject = view.ref.toDto(),
                        revision = view.revision,
                        flags = view.flags.map { flag ->
                            SubjectFlagDto(
                                key = flag.definition.key,
                                scope = flag.definition.scope.toWire(),
                                type = flag.definition.type.toWire(),
                                dimension = flag.definition.dimension,
                                effective = flag.effective.toDto(),
                                source = flag.source.toWire(),
                                overridden = flag.overridden,
                                applicable = flag.applicable,
                                updatedAtMillis = flag.updatedAtMillis,
                                updatedBy = flag.updatedBy,
                            )
                        },
                    ),
                )
            }
        }

        put("/api/subject") {
            guarded(config) {
                val patch = json.decodeFromString<SubjectPatchDto>(call.receiveText())
                val ref = patch.subject.toDomainOrNull()
                    ?: throw badRequest("unrecognised subject: ${patch.subject}")
                val sets = patch.set.mapValues { (key, dto) ->
                    dto.toDomainOrNull() ?: throw badRequest("unrecognised value for '$key': $dto")
                }
                val result = service.setSubject(ref, sets, patch.clear.toSet(), patch.updatedBy)
                respond(RevisionDto(result.revision))
            }
        }

        get("/api/subjects") {
            guarded(config) {
                val scope = scopeFromWire(call.request.queryParameters["scope"].orEmpty())
                    ?: throw badRequest("scope must be one of service, user, context")
                val subjects = service.listSubjects(
                    scope = scope,
                    dimension = call.request.queryParameters["dim"].orEmpty(),
                    keyPrefix = call.request.queryParameters["prefix"].orEmpty(),
                    limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 0,
                )
                respond(SubjectListDto(subjects.map { it.toDto() }))
            }
        }

        // "Why is this user seeing the old checkout?" is the question an admin panel exists to
        // answer, and it is nearly free given the resolution engine already exists.
        post("/api/preview") {
            guarded(config) {
                val body = json.decodeFromString<SubjectRefDto>(call.receiveText())
                val ref = body.toDomainOrNull() ?: throw badRequest("unrecognised subject: $body")
                val resolved = service.preview(ref.asSubject())
                respond(
                    SubjectDto(
                        subject = ref.toDto(),
                        revision = service.revision(),
                        flags = resolved.map { flag ->
                            val definition = service.index[flag.key]
                            SubjectFlagDto(
                                key = flag.key,
                                scope = definition?.scope?.toWire().orEmpty(),
                                type = definition?.type?.toWire().orEmpty(),
                                dimension = definition?.dimension.orEmpty(),
                                effective = flag.value.toDto(),
                                source = flag.source.toWire(),
                                overridden = false,
                                applicable = false,
                            )
                        },
                    ),
                )
            }
        }

        get("/api/orphans") {
            guarded(config) {
                respond(
                    OverrideListDto(
                        service.orphans().map {
                            OverrideDto(
                                flagKey = it.flagKey,
                                subject = it.ref.toDto(),
                                value = it.value.toDto(),
                                updatedAtMillis = it.updatedAtMillis,
                                updatedBy = it.updatedBy,
                            )
                        },
                    ),
                )
            }
        }

        delete("/api/orphans") {
            guarded(config) {
                val (revision, purged) = service.purgeOrphans()
                respond(PurgeDto(revision, purged))
            }
        }
    }
}

private suspend fun serveIndex(call: ApplicationCall, config: FeatureFlagsConfig) {
    if (!config.adminPanelEnabled) {
        call.respond(HttpStatusCode.NotFound, ErrorDto("NOT_FOUND", "the admin panel is disabled"))
        return
    }
    if (!call.adminAuthorized(config.adminToken)) {
        call.respond(
            HttpStatusCode.Forbidden,
            ErrorDto("PERMISSION_DENIED", "missing or invalid admin token"),
        )
        return
    }
    // A browser cannot set a header when you navigate to a URL, so `?token=` is accepted once and
    // exchanged for a cookie that the page's own fetch calls then carry.
    call.request.queryParameters["token"]?.let { token ->
        call.response.headers.append(
            "Set-Cookie",
            "$ADMIN_COOKIE=$token; Path=${config.adminPathPrefix}; HttpOnly; SameSite=Strict",
        )
    }
    call.respondBytes(adminHtml(), ContentType.Text.Html)
}

/**
 * Runs a handler behind the token check, translating thrown [RpcResponseException]s into JSON.
 *
 * The admin JSON API and the protobuf admin service share one implementation, so they also share
 * its errors; this maps ktbuf status codes onto HTTP for the browser.
 */
private suspend fun RoutingContext.guarded(
    config: FeatureFlagsConfig,
    block: suspend RoutingContext.() -> Unit,
) {
    if (!call.adminAuthorized(config.adminToken)) {
        call.respond(
            HttpStatusCode.Forbidden,
            ErrorDto("PERMISSION_DENIED", "missing or invalid $ADMIN_TOKEN_HEADER"),
        )
        return
    }
    try {
        block()
    } catch (e: RpcResponseException) {
        call.respond(e.code.toHttpStatus(), ErrorDto(e.code.name, e.errorMessage))
    } catch (e: IllegalArgumentException) {
        // Covers a malformed JSON body as well as our own badRequest().
        call.respond(
            HttpStatusCode.BadRequest,
            ErrorDto("INVALID_ARGUMENT", e.message ?: "malformed request"),
        )
    }
}

private fun Codes.toHttpStatus(): HttpStatusCode = when (this) {
    Codes.NOT_FOUND -> HttpStatusCode.NotFound
    Codes.INVALID_ARGUMENT, Codes.FAILED_PRECONDITION, Codes.OUT_OF_RANGE -> HttpStatusCode.BadRequest
    Codes.PERMISSION_DENIED -> HttpStatusCode.Forbidden
    Codes.UNAUTHENTICATED -> HttpStatusCode.Unauthorized
    Codes.ALREADY_EXISTS, Codes.ABORTED -> HttpStatusCode.Conflict
    Codes.UNIMPLEMENTED -> HttpStatusCode.NotImplemented
    Codes.UNAVAILABLE -> HttpStatusCode.ServiceUnavailable
    else -> HttpStatusCode.InternalServerError
}

private fun badRequest(message: String) = IllegalArgumentException(message)

private suspend inline fun <reified T> RoutingContext.respond(body: T) {
    call.respondText(json.encodeToString(body), ContentType.Application.Json)
}

private suspend inline fun <reified T> ApplicationCall.respond(status: HttpStatusCode, body: T) {
    respondText(json.encodeToString(body), ContentType.Application.Json, status)
}

private fun ApplicationCall.subjectRef(): FlagSubjectRef {
    val scope = scopeFromWire(request.queryParameters["scope"].orEmpty())
        ?: throw badRequest("scope must be one of service, user, context")
    val dimension = request.queryParameters["dim"].orEmpty()
    val key = request.queryParameters["key"].orEmpty()
    return when (scope) {
        FlagScope.SERVICE -> FlagSubjectRef.Service
        FlagScope.USER -> {
            if (key.isEmpty()) throw badRequest("a user subject needs a key")
            FlagSubjectRef.user(key)
        }
        FlagScope.CONTEXT -> {
            if (dimension.isEmpty() || key.isEmpty()) {
                throw badRequest("a context subject needs both dim and key")
            }
            FlagSubjectRef.context(dimension, key)
        }
    }
}

private fun SubjectRefDto.toDomainOrNull(): FlagSubjectRef? {
    val parsed = scopeFromWire(scope) ?: return null
    return when (parsed) {
        FlagScope.SERVICE -> FlagSubjectRef.Service
        FlagScope.USER -> key.takeIf { it.isNotEmpty() }?.let(FlagSubjectRef::user)
        FlagScope.CONTEXT ->
            if (dimension.isNotEmpty() && key.isNotEmpty()) {
                FlagSubjectRef.context(dimension, key)
            } else {
                null
            }
    }
}

/**
 * Accepts the token as a header, a cookie, or a query parameter.
 *
 * Three ways because three callers need different ones: a CLI sets a header, the panel's fetch
 * calls carry the cookie, and a human pasting a URL into a browser has only the query string.
 */
internal fun ApplicationCall.adminAuthorized(required: String?): Boolean {
    if (required == null) return true
    val candidates = listOfNotNull(
        request.header(ADMIN_TOKEN_HEADER),
        request.cookies[ADMIN_COOKIE],
        request.queryParameters["token"],
    )
    return candidates.any { constantTimeEquals(it, required) }
}
