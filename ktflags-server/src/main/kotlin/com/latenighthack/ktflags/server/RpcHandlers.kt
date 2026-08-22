package com.latenighthack.ktflags.server

import com.latenighthack.ktbuf.net.GrpcRequestContext
import com.latenighthack.ktbuf.net.RpcResponseException
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktflags.FlagSubjectRef
import com.latenighthack.ktflags.proto.toDomainOrNull
import com.latenighthack.ktflags.proto.toProto
import com.latenighthack.ktflags.proto.v1.ClearOverrideRequest
import com.latenighthack.ktflags.proto.v1.ClearOverrideResponse
import com.latenighthack.ktflags.proto.v1.EvaluateRequest
import com.latenighthack.ktflags.proto.v1.EvaluateResponse
import com.latenighthack.ktflags.proto.v1.FlagStatus
import com.latenighthack.ktflags.proto.v1.FlagsAdminServer
import com.latenighthack.ktflags.proto.v1.FlagsServer
import com.latenighthack.ktflags.proto.v1.GetSchemaRequest
import com.latenighthack.ktflags.proto.v1.GetSchemaResponse
import com.latenighthack.ktflags.proto.v1.GetSubjectRequest
import com.latenighthack.ktflags.proto.v1.GetSubjectResponse
import com.latenighthack.ktflags.proto.v1.ListFlagsRequest
import com.latenighthack.ktflags.proto.v1.ListFlagsResponse
import com.latenighthack.ktflags.proto.v1.ListOrphansRequest
import com.latenighthack.ktflags.proto.v1.ListOrphansResponse
import com.latenighthack.ktflags.proto.v1.ListSubjectsRequest
import com.latenighthack.ktflags.proto.v1.ListSubjectsResponse
import com.latenighthack.ktflags.proto.v1.PurgeOrphansRequest
import com.latenighthack.ktflags.proto.v1.PurgeOrphansResponse
import com.latenighthack.ktflags.proto.v1.SetOverrideRequest
import com.latenighthack.ktflags.proto.v1.SetOverrideResponse
import com.latenighthack.ktflags.proto.v1.SetSubjectOverridesRequest
import com.latenighthack.ktflags.proto.v1.SetSubjectOverridesResponse
import com.latenighthack.ktflags.proto.v1.SubjectFlag
import com.latenighthack.ktflags.proto.v1.SubjectRef

private const val PUBLIC_PATH = "/api/ktflags.v1.Flags"
private const val ADMIN_PATH = "/api/ktflags.v1.FlagsAdmin"

internal fun invalid(path: String, message: String): RpcResponseException =
    RpcResponseException(path, "POST", Codes.INVALID_ARGUMENT, message)

/**
 * The read-only public service.
 *
 * Every message field that is a singular message is nullable in generated Kotlin, so a hand-built
 * or truncated request can arrive with them unset. Each is checked rather than dereferenced: a
 * malformed request must be INVALID_ARGUMENT, never a 500 from an NPE.
 */
internal class FlagsRpcHandler<T : Any>(
    private val service: FeatureFlagsService<T>,
    private val config: FeatureFlagsConfig,
) : FlagsServer {

    override suspend fun evaluate(
        context: GrpcRequestContext,
        request: EvaluateRequest,
    ): EvaluateResponse {
        requireSchema(request.schemaName, PUBLIC_PATH)

        val subject = config.subjectExtractor.extract(context, request)
        val revision = service.revision()

        // Conditional fetch: an unchanged answer costs no payload. This is the reason ktflags
        // needs no streaming RPC at all. Presence is carried by has_known_revision, not by a
        // magic 0, because 0 is a legitimate revision for a store nobody has written to.
        if (request.hasKnownRevision && request.knownRevision == revision) {
            return EvaluateResponse {
                notModified = true
                this.revision = revision
                evaluatedAtMillis = config.clock()
            }
        }

        val onlyKeys = request.keys.filter { it.isNotEmpty() }.toSet()
        val resolved = service.resolve(subject, onlyKeys)

        return EvaluateResponse {
            this.revision = revision
            evaluatedAtMillis = config.clock()
            assignments = resolved.map { it.toProto() }
        }
    }

    override suspend fun getSchema(
        context: GrpcRequestContext,
        request: GetSchemaRequest,
    ): GetSchemaResponse {
        requireSchema(request.schemaName, PUBLIC_PATH)
        return GetSchemaResponse {
            schemaName = service.schema.schemaName
            definitions = service.schema.definitions.map { it.toProto() }
            revision = service.revision()
        }
    }

    /** An empty schema name means "whatever you serve", which keeps simple clients simple. */
    private fun requireSchema(requested: String, path: String) {
        if (requested.isNotEmpty() && requested != service.schema.schemaName) {
            throw RpcResponseException(
                path,
                "POST",
                Codes.NOT_FOUND,
                "this server serves schema '${service.schema.schemaName}', not '$requested'",
            )
        }
    }
}

/** The mutating admin service. Mounted on the internal listener unless explicitly opted out. */
internal class FlagsAdminRpcHandler<T : Any>(
    private val service: FeatureFlagsService<T>,
    private val config: FeatureFlagsConfig,
) : FlagsAdminServer {

    override suspend fun listFlags(
        context: GrpcRequestContext,
        request: ListFlagsRequest,
    ): ListFlagsResponse {
        authorize(context)
        val view = service.listFlags()
        return ListFlagsResponse {
            schemaName = view.schemaName
            revision = view.revision
            orphanCount = view.orphanCount
            flags = view.flags.map { status ->
                FlagStatus {
                    definition = status.definition.toProto()
                    status.serviceValue?.let { serviceValue = it.toProto() }
                    serviceUpdatedAtMillis = status.serviceUpdatedAtMillis
                    serviceUpdatedBy = status.serviceUpdatedBy
                    subjectOverrideCount = status.overrideCount
                }
            }
        }
    }

    override suspend fun setOverride(
        context: GrpcRequestContext,
        request: SetOverrideRequest,
    ): SetOverrideResponse {
        authorize(context)
        val ref = request.subject.requireRef()
        val value = request.value?.toDomainOrNull()
            ?: throw invalid(ADMIN_PATH, "the value is missing, unset, or outside the Int range")
        return SetOverrideResponse {
            revision = service.setOverride(request.flagKey, ref, value, request.updatedBy)
        }
    }

    override suspend fun clearOverride(
        context: GrpcRequestContext,
        request: ClearOverrideRequest,
    ): ClearOverrideResponse {
        authorize(context)
        val (revision, wasRemoved) = service.clearOverride(request.flagKey, request.subject.requireRef())
        return ClearOverrideResponse {
            this.revision = revision
            removed = wasRemoved
        }
    }

    override suspend fun getSubject(
        context: GrpcRequestContext,
        request: GetSubjectRequest,
    ): GetSubjectResponse {
        authorize(context)
        val ref = request.subject.requireRef()
        val view = service.subject(ref)
        return GetSubjectResponse {
            subject = ref.toProto()
            revision = view.revision
            flags = view.flags.map { flag ->
                SubjectFlag {
                    definition = flag.definition.toProto()
                    effectiveValue = flag.effective.toProto()
                    source = flag.source.toProto()
                    overridden = flag.overridden
                    applicable = flag.applicable
                    updatedAtMillis = flag.updatedAtMillis
                    updatedBy = flag.updatedBy
                }
            }
        }
    }

    override suspend fun setSubjectOverrides(
        context: GrpcRequestContext,
        request: SetSubjectOverridesRequest,
    ): SetSubjectOverridesResponse {
        authorize(context)
        val ref = request.subject.requireRef()
        val sets = request.set.associate { entry ->
            val value = entry.value?.toDomainOrNull() ?: throw invalid(
                ADMIN_PATH,
                "the value for '${entry.flagKey}' is missing, unset, or outside the Int range",
            )
            entry.flagKey to value
        }
        val result = service.setSubject(
            ref,
            sets,
            request.clear.filter { it.isNotEmpty() }.toSet(),
            request.updatedBy,
        )
        return SetSubjectOverridesResponse {
            revision = result.revision
            written = result.written
            cleared = result.cleared
        }
    }

    override suspend fun listSubjects(
        context: GrpcRequestContext,
        request: ListSubjectsRequest,
    ): ListSubjectsResponse {
        authorize(context)
        val scope = request.scope.toDomainOrNull()
            ?: throw invalid(ADMIN_PATH, "listSubjects needs a scope of USER or CONTEXT")
        val subjects = service.listSubjects(scope, request.dimension, request.keyPrefix, request.limit)
        return ListSubjectsResponse { this.subjects = subjects.map { it.toProto() } }
    }

    override suspend fun listOrphans(
        context: GrpcRequestContext,
        request: ListOrphansRequest,
    ): ListOrphansResponse {
        authorize(context)
        return ListOrphansResponse { overrides = service.orphans().map { it.toProto() } }
    }

    override suspend fun purgeOrphans(
        context: GrpcRequestContext,
        request: PurgeOrphansRequest,
    ): PurgeOrphansResponse {
        authorize(context)
        val (revision, count) = service.purgeOrphans()
        return PurgeOrphansResponse {
            this.revision = revision
            purged = count
        }
    }

    /**
     * Checks the admin token when one is configured.
     *
     * Read from headers, never from `context.extensions` -- see [SubjectExtractor] for why that
     * map is always empty here.
     */
    private fun authorize(context: GrpcRequestContext) {
        val required = config.adminToken ?: return
        val presented = context.headers.entries
            .firstOrNull { it.key.equals(ADMIN_TOKEN_HEADER, ignoreCase = true) }
            ?.value
        if (presented == null || !constantTimeEquals(presented, required)) {
            throw RpcResponseException(
                ADMIN_PATH,
                "POST",
                Codes.PERMISSION_DENIED,
                "missing or invalid $ADMIN_TOKEN_HEADER",
            )
        }
    }
}

/** A null or malformed [SubjectRef] is a client error, not a server crash. */
private fun SubjectRef?.requireRef(): FlagSubjectRef =
    this?.toDomainOrNull() ?: throw invalid(
        ADMIN_PATH,
        "the subject is missing or malformed. Use {SERVICE,\"\",\"\"} for the service-wide value, " +
            "{USER,\"\",\"<user id>\"} for one user, or {CONTEXT,\"<dimension>\",\"<key>\"}.",
    )
