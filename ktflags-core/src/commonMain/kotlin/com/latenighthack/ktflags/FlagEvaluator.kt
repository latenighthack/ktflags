package com.latenighthack.ktflags

/** One stored row: a value bound to a flag key for a particular subject. */
public data class FlagOverrideRow(
    public val flagKey: String,
    public val ref: FlagSubjectRef,
    public val value: FlagValue,
    public val updatedAtMillis: Long = 0L,
    public val updatedBy: String = "",
)

/** A flag's resolved value plus which layer produced it. */
public data class ResolvedFlag(
    public val key: String,
    public val value: FlagValue,
    public val source: ValueSource,
)

/**
 * Resolves a schema's flags for one subject against a set of stored rows.
 *
 * Every flag, regardless of scope, resolves through the same three-layer chain -- subject row,
 * then service row, then the compile-time default. The scope only decides *which* subject row is
 * looked up, or whether one is looked up at all:
 *
 * | scope        | layer 1 (SUBJECT_OVERRIDE)      | layer 2 (SERVICE_DEFAULT) | layer 3       |
 * |--------------|---------------------------------|---------------------------|---------------|
 * | `SERVICE`    | skipped                         | the service row           | code default  |
 * | `USER`       | this user's row, if a user id   | the service row           | code default  |
 * | `CONTEXT(d)` | this key's row in dimension `d` | the service row           | code default  |
 *
 * The service layer sitting under *every* scope is the point, not an accident: it is the rollout
 * knob. Without it a `@UserScoped` flag could only ever be changed one user at a time.
 *
 * A layer is skipped when its row is absent or when the row's type does not match the declared
 * type (a stale row left by a retyped flag). A type mismatch is never an error here -- it degrades
 * to the next layer and is reported separately by the admin panel's orphan view.
 *
 * This lives in commonMain, not on the server, so the whole resolution stack is testable on every
 * Kotlin target.
 */
public class FlagEvaluator(public val index: FlagSchemaIndex) {

    public constructor(schema: FlagSchema<*>) : this(FlagSchemaIndex(schema))

    /**
     * @param onlyKeys when non-empty, resolve just these keys. Unknown keys are ignored.
     */
    public fun resolve(
        subject: FlagSubject,
        overrides: List<FlagOverrideRow>,
        onlyKeys: Set<String> = emptySet(),
    ): List<ResolvedFlag> {
        // Rows arrive as a flat list from one or two queries; index them once rather than scanning
        // per flag.
        val byRef: Map<Pair<String, FlagSubjectRef>, FlagOverrideRow> =
            overrides.associateBy { it.flagKey to it.ref }

        return index.schema.definitions
            .filter { onlyKeys.isEmpty() || it.key in onlyKeys }
            .map { definition -> resolveOne(definition, subject, byRef) }
    }

    /** Resolves a single flag. Exposed for the admin panel's per-subject view. */
    public fun resolveOne(
        definition: FlagDefinition,
        subject: FlagSubject,
        byRef: Map<Pair<String, FlagSubjectRef>, FlagOverrideRow>,
    ): ResolvedFlag {
        subjectRef(definition, subject)
            ?.let { byRef[definition.key to it] }
            ?.takeIf { it.value.type == definition.type }
            ?.let { return ResolvedFlag(definition.key, it.value, ValueSource.SUBJECT_OVERRIDE) }

        byRef[definition.key to FlagSubjectRef.Service]
            ?.takeIf { it.value.type == definition.type }
            ?.let { return ResolvedFlag(definition.key, it.value, ValueSource.SERVICE_DEFAULT) }

        return ResolvedFlag(definition.key, definition.defaultValue, ValueSource.CODE_DEFAULT)
    }

    /**
     * Which subject row could hold this flag for this subject, or null to skip layer 1.
     *
     * A user-scoped flag evaluated with no user id returns null here -- that is deliberate and not
     * an error. A logged-out app must still boot with sane flags.
     */
    public fun subjectRef(definition: FlagDefinition, subject: FlagSubject): FlagSubjectRef? =
        when (definition.scope) {
            FlagScope.SERVICE -> null
            FlagScope.USER -> subject.normalizedUserId?.let(FlagSubjectRef::user)
            FlagScope.CONTEXT ->
                subject.contextKey(definition.dimension)
                    ?.let { FlagSubjectRef.context(definition.dimension, it) }
        }
}

/** Collapses resolved flags into the map [FlagSchema.materialize] consumes. */
public fun List<ResolvedFlag>.toFlagValues(): FlagValues =
    FlagValues.from(associate { it.key to it.value })

/** The per-key provenance of a resolution, for request logging and the admin panel. */
public fun List<ResolvedFlag>.sources(): Map<String, ValueSource> =
    associate { it.key to it.source }
