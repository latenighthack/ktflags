package com.latenighthack.ktflags.server

import com.latenighthack.ktflags.FlagOverrideRow
import com.latenighthack.ktflags.FlagSchema
import com.latenighthack.ktflags.FlagScope
import com.latenighthack.ktflags.FlagSubject
import com.latenighthack.ktflags.FlagSubjectRef
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.FlagValues
import com.latenighthack.ktflags.ResolvedFlag
import com.latenighthack.ktflags.SubjectWriteResult
import com.latenighthack.ktflags.ValueSource

/**
 * In-process flag reads for the host application's own services.
 *
 * Request-oriented rather than snapshot-oriented, unlike the client's `FeatureFlags<T>`: a server
 * answers for thousands of subjects a second and must not hold per-subject state. The two
 * deliberately share no supertype -- unifying them would force either a `StateFlow` onto a
 * multi-tenant server or a subject parameter onto every client read.
 *
 * What they *do* share is `T`. Business logic that takes `AppFlags` as a parameter is
 * provider-agnostic and testable with a plain data class on either side.
 */
public interface ServerFeatureFlags<T : Any> {
    public val schema: FlagSchema<T>

    /** The common call: one or two indexed queries, no network, no serialization. */
    public suspend fun evaluate(subject: FlagSubject): T

    public suspend fun evaluateValues(subject: FlagSubject): FlagValues

    /** Adds per-key provenance and the revision, for request logging and debug endpoints. */
    public suspend fun evaluateDetailed(subject: FlagSubject): FlagEvaluation<T>

    /** Service-scoped values only -- no subject, one cached read. For process-wide switches. */
    public suspend fun serviceFlags(): T

    public suspend fun revision(): Long
}

public data class FlagEvaluation<T : Any>(
    public val flags: T,
    public val sources: Map<String, ValueSource>,
    public val revision: Long,
)

/** One flag as the admin surface sees it: its definition plus the service-wide row, if any. */
public data class FlagStatusView(
    public val definition: com.latenighthack.ktflags.FlagDefinition,
    public val serviceValue: FlagValue?,
    public val serviceUpdatedAtMillis: Long,
    public val serviceUpdatedBy: String,
    public val overrideCount: Int,
)

public data class FlagListView(
    public val schemaName: String,
    public val revision: Long,
    public val flags: List<FlagStatusView>,
    public val orphanCount: Int,
)

/** One flag as the admin surface sees it *for a particular subject*. */
public data class SubjectFlagView(
    public val definition: com.latenighthack.ktflags.FlagDefinition,
    public val effective: FlagValue,
    public val source: ValueSource,
    /** Whether a row addressed to exactly this subject exists. */
    public val overridden: Boolean,
    /**
     * Whether this subject may own this flag at all. Inapplicable flags are still listed, with
     * their effective value, so an operator can answer "what does this user actually see?" -- but
     * they are read-only.
     */
    public val applicable: Boolean,
    public val updatedAtMillis: Long,
    public val updatedBy: String,
)

public data class SubjectView(
    public val ref: FlagSubjectRef,
    public val revision: Long,
    public val flags: List<SubjectFlagView>,
)

/**
 * The programmatic equivalent of the admin panel.
 *
 * The protobuf `FlagsAdmin` service, the JSON routes and the HTML page are all thin adapters over
 * this one interface, which is what stops them drifting apart.
 */
public interface ServerFlagsAdmin {
    public suspend fun listFlags(): FlagListView

    public suspend fun setOverride(
        flagKey: String,
        ref: FlagSubjectRef,
        value: FlagValue,
        updatedBy: String = "",
    ): Long

    public suspend fun clearOverride(flagKey: String, ref: FlagSubjectRef): Pair<Long, Boolean>

    public suspend fun subject(ref: FlagSubjectRef): SubjectView

    public suspend fun setSubject(
        ref: FlagSubjectRef,
        sets: Map<String, FlagValue>,
        clears: Set<String>,
        updatedBy: String = "",
    ): SubjectWriteResult

    public suspend fun listSubjects(
        scope: FlagScope,
        dimension: String,
        keyPrefix: String,
        limit: Int,
    ): List<FlagSubjectRef>

    /** Rows for flag keys the schema no longer declares, left behind by a rename or a deletion. */
    public suspend fun orphans(): List<FlagOverrideRow>

    public suspend fun purgeOrphans(): Pair<Long, Int>

    /** What [subject] would resolve to -- the "why is this user seeing that?" answer. */
    public suspend fun preview(subject: FlagSubject): List<ResolvedFlag>
}
