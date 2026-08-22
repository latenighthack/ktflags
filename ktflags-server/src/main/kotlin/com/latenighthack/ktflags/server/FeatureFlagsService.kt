package com.latenighthack.ktflags.server

import com.latenighthack.ktbuf.net.RpcResponseException
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktflags.FlagDefinition
import com.latenighthack.ktflags.FlagEvaluator
import com.latenighthack.ktflags.FlagOverrideRow
import com.latenighthack.ktflags.FlagSchema
import com.latenighthack.ktflags.FlagSchemaIndex
import com.latenighthack.ktflags.FlagScope
import com.latenighthack.ktflags.FlagSubject
import com.latenighthack.ktflags.FlagSubjectRef
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.FlagValues
import com.latenighthack.ktflags.FlagsRepository
import com.latenighthack.ktflags.ResolvedFlag
import com.latenighthack.ktflags.SubjectWriteResult
import com.latenighthack.ktflags.toFlagValues
import com.latenighthack.ktflags.sources
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Everything the server does with flags, behind one object.
 *
 * Four front-ends adapt to this and nothing else: the public protobuf service, the admin protobuf
 * service, the admin JSON routes, and the host application's own in-process calls. That is what
 * keeps the admin panel and the RPC API from disagreeing about what a write means.
 *
 * Construct it directly if your deployment already owns its listeners (mount with [mountPublic] /
 * [mountAdmin]); otherwise use `Application.installFeatureFlags`, which builds one for you and
 * returns it.
 */
public class FeatureFlagsService<T : Any>(
    override val schema: FlagSchema<T>,
    private val repository: FlagsRepository,
    internal val config: FeatureFlagsConfig = FeatureFlagsConfig(),
) : ServerFeatureFlags<T>, ServerFlagsAdmin {

    internal val index: FlagSchemaIndex = FlagSchemaIndex(schema)

    /**
     * This service as the generated ktbuf server interface.
     *
     * Public because it is the seam for the zero-network path: ktbuf generates
     * `LocalFlagsServiceRpc`, which implements the *client* interface by calling this one
     * directly, so a test (or an in-process consumer) gets the real client against the real server
     * with no socket and no port:
     *
     * ```
     * FlagsTransport.of(LocalFlagsServiceRpc(service.flagsServer))
     * ```
     */
    public val flagsServer: com.latenighthack.ktflags.proto.v1.FlagsServer by lazy {
        FlagsRpcHandler(this, config)
    }

    /** This service as the generated ktbuf admin server interface. See [flagsServer]. */
    public val adminServer: com.latenighthack.ktflags.proto.v1.FlagsAdminServer by lazy {
        FlagsAdminRpcHandler(this, config)
    }
    private val evaluator = FlagEvaluator(index)
    private val schemaName: String get() = schema.schemaName

    /**
     * Service rows are read on every single evaluation, so they are memoized against the revision
     * that produced them. Subject rows are never cached: correctness over latency, and an operator
     * flipping a user's flag expects the next request to see it.
     */
    private val serviceCacheMutex = Mutex()
    private var cachedServiceRevision: Long = -1L
    private var cachedServiceRows: List<FlagOverrideRow> = emptyList()

    // --- ServerFeatureFlags ---------------------------------------------------------------------

    override suspend fun evaluate(subject: FlagSubject): T =
        schema.materialize(evaluateValues(subject))

    override suspend fun evaluateValues(subject: FlagSubject): FlagValues =
        resolve(subject).toFlagValues()

    override suspend fun evaluateDetailed(subject: FlagSubject): FlagEvaluation<T> {
        val resolved = resolve(subject)
        return FlagEvaluation(
            flags = schema.materialize(resolved.toFlagValues()),
            sources = resolved.sources(),
            revision = repository.revision(schemaName),
        )
    }

    override suspend fun serviceFlags(): T =
        schema.materialize(
            evaluator.resolve(FlagSubject.Anonymous, serviceRows()).toFlagValues(),
        )

    override suspend fun revision(): Long = repository.revision(schemaName)

    /** Resolves for a subject. Used by the RPC handler and by [preview] alike. */
    internal suspend fun resolve(
        subject: FlagSubject,
        onlyKeys: Set<String> = emptySet(),
    ): List<ResolvedFlag> =
        evaluator.resolve(subject, repository.overridesFor(schemaName, subject), onlyKeys)

    private suspend fun serviceRows(): List<FlagOverrideRow> {
        if (!config.serviceCacheEnabled) return repository.serviceOverrides(schemaName)
        val revision = repository.revision(schemaName)
        return serviceCacheMutex.withLock {
            if (revision != cachedServiceRevision) {
                cachedServiceRows = repository.serviceOverrides(schemaName)
                cachedServiceRevision = revision
            }
            cachedServiceRows
        }
    }

    // --- ServerFlagsAdmin -----------------------------------------------------------------------

    override suspend fun listFlags(): FlagListView {
        val service = repository.serviceOverrides(schemaName).associateBy { it.flagKey }
        val counts = repository.overrideCounts(schemaName)
        return FlagListView(
            schemaName = schemaName,
            revision = repository.revision(schemaName),
            flags = schema.definitions.map { definition ->
                // A service row whose type no longer matches the flag is not shown as the current
                // value -- the evaluator ignores it too, so showing it would be a lie.
                val row = service[definition.key]?.takeIf { it.value.type == definition.type }
                FlagStatusView(
                    definition = definition,
                    serviceValue = row?.value,
                    serviceUpdatedAtMillis = row?.updatedAtMillis ?: 0L,
                    serviceUpdatedBy = row?.updatedBy.orEmpty(),
                    overrideCount = counts[definition.key] ?: 0,
                )
            },
            orphanCount = orphans().size,
        )
    }

    override suspend fun setOverride(
        flagKey: String,
        ref: FlagSubjectRef,
        value: FlagValue,
        updatedBy: String,
    ): Long {
        validateWrite(flagKey, ref, value)
        return repository.put(schemaName, flagKey, ref, value, config.clock(), updatedBy)
    }

    override suspend fun clearOverride(
        flagKey: String,
        ref: FlagSubjectRef,
    ): Pair<Long, Boolean> = repository.clear(schemaName, flagKey, ref)

    override suspend fun subject(ref: FlagSubjectRef): SubjectView {
        // The subject's own rows plus the service rows: everything the resolution chain can reach
        // for this subject, in two reads.
        val own = repository.overridesForSubject(schemaName, ref).associateBy { it.flagKey }
        val rows = own.values + repository.serviceOverrides(schemaName)
        val byRef = rows.associateBy { it.flagKey to it.ref }
        val asSubject = ref.asSubject()

        return SubjectView(
            ref = ref,
            revision = repository.revision(schemaName),
            flags = schema.definitions.map { definition ->
                val resolved = evaluator.resolveOne(definition, asSubject, byRef)
                val row = own[definition.key]
                SubjectFlagView(
                    definition = definition,
                    effective = resolved.value,
                    source = resolved.source,
                    overridden = row != null,
                    applicable = index.canOwn(ref, definition.key),
                    updatedAtMillis = row?.updatedAtMillis ?: 0L,
                    updatedBy = row?.updatedBy.orEmpty(),
                )
            },
        )
    }

    override suspend fun setSubject(
        ref: FlagSubjectRef,
        sets: Map<String, FlagValue>,
        clears: Set<String>,
        updatedBy: String,
    ): SubjectWriteResult {
        sets.forEach { (flagKey, value) -> validateWrite(flagKey, ref, value) }
        clears.forEach { flagKey ->
            if (index[flagKey] == null) throw unknownFlag(flagKey)
        }
        return repository.putSubject(schemaName, ref, sets, clears, config.clock(), updatedBy)
    }

    override suspend fun listSubjects(
        scope: FlagScope,
        dimension: String,
        keyPrefix: String,
        limit: Int,
    ): List<FlagSubjectRef> = repository.listSubjects(
        schemaName,
        scope,
        dimension,
        keyPrefix,
        if (limit > 0) minOf(limit, MAX_SUBJECT_PAGE) else DEFAULT_SUBJECT_PAGE,
    )

    override suspend fun orphans(): List<FlagOverrideRow> =
        repository.orphans(schemaName, index.keys)

    override suspend fun purgeOrphans(): Pair<Long, Int> =
        repository.purgeOrphans(schemaName, index.keys)

    override suspend fun preview(subject: FlagSubject): List<ResolvedFlag> = resolve(subject)

    // --- Validation -----------------------------------------------------------------------------

    /**
     * Rejects any write the schema forbids, so the store can never accumulate a row that the
     * evaluator would silently ignore.
     */
    private fun validateWrite(flagKey: String, ref: FlagSubjectRef, value: FlagValue) {
        val definition: FlagDefinition = index[flagKey] ?: throw unknownFlag(flagKey)

        if (value.type != definition.type) {
            throw invalid(
                "flag '$flagKey' is ${definition.type}, but the value supplied is ${value.type}",
            )
        }
        if (!index.canOwn(ref, flagKey)) {
            throw invalid(
                when (ref.scope) {
                    FlagScope.USER ->
                        "flag '$flagKey' is ${definition.scope}-scoped, so it cannot hold a " +
                            "per-user value. Set it service-wide instead."
                    FlagScope.CONTEXT ->
                        if (definition.scope == FlagScope.CONTEXT) {
                            "flag '$flagKey' is scoped to the '${definition.dimension}' dimension, " +
                                "not '${ref.dimension}'."
                        } else {
                            "flag '$flagKey' is ${definition.scope}-scoped, so it cannot hold a " +
                                "per-context value."
                        }
                    FlagScope.SERVICE -> "flag '$flagKey' cannot hold a service-wide value"
                },
            )
        }
    }

    private fun unknownFlag(flagKey: String) = RpcResponseException(
        ADMIN_PATH,
        "POST",
        Codes.NOT_FOUND,
        "no flag '$flagKey' in schema '$schemaName'. Known flags: ${index.keys.sorted()}",
    )

    private fun invalid(message: String) =
        RpcResponseException(ADMIN_PATH, "POST", Codes.INVALID_ARGUMENT, message)

    public suspend fun close() {
        repository.close()
    }

    internal companion object {
        private const val ADMIN_PATH = "/api/ktflags.v1.FlagsAdmin"
        const val DEFAULT_SUBJECT_PAGE = 100
        const val MAX_SUBJECT_PAGE = 1_000
    }
}

/**
 * Views a subject *reference* as a subject to resolve for.
 *
 * The admin panel asks "what does user u-42 see?", which is the same question the client asks,
 * just addressed by row rather than by identity.
 */
internal fun FlagSubjectRef.asSubject(): FlagSubject = when (scope) {
    FlagScope.SERVICE -> FlagSubject.Anonymous
    FlagScope.USER -> FlagSubject(userId = key)
    FlagScope.CONTEXT -> FlagSubject(context = mapOf(dimension to key))
}
