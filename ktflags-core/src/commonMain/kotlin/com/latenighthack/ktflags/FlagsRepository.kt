package com.latenighthack.ktflags

/** Result of an atomic multi-flag write against one subject. */
public data class SubjectWriteResult(
    public val revision: Long,
    public val written: Int,
    public val cleared: Int,
)

/**
 * Storage for flag overrides.
 *
 * Implementations ship separately (`ktflags-store-sqlite`, `ktflags-store-postgres`); this
 * interface and [InMemoryFlagsRepository] live in commonMain so the whole server-side resolution
 * stack is testable on every Kotlin target, not just the JVM.
 *
 * ### Revisions
 * [revision] is a per-schema counter bumped on every write that actually changes something. It
 * drives conditional fetch: a client that replays its last revision and gets the same one back
 * needs no payload. A single counter per schema is coarse -- any write invalidates every client's
 * cached revision -- but it is correct, and refining it later needs no wire change.
 *
 * ### Threading
 * Every method is `suspend`. JDBC-backed implementations must dispatch to an IO dispatcher: the
 * generated SQLDelight queries are blocking, and running one on Ktor's CIO event loop stalls the
 * server with no error and no warning.
 */
public interface FlagsRepository {
    /** The current revision, or 0 if nothing has ever been written for this schema. */
    public suspend fun revision(schemaName: String): Long

    /**
     * Every row that could apply to [subject]: the service rows, plus this user's rows, plus the
     * rows for each supplied context dimension.
     *
     * Deliberately one call rather than one per flag -- a 50-flag schema must not be 50 queries.
     */
    public suspend fun overridesFor(schemaName: String, subject: FlagSubject): List<FlagOverrideRow>

    /** Just the service-wide rows. Read on every evaluation, so worth caching by revision. */
    public suspend fun serviceOverrides(schemaName: String): List<FlagOverrideRow>

    /** Every row addressed to exactly this subject. Backs the admin panel's subject view. */
    public suspend fun overridesForSubject(
        schemaName: String,
        ref: FlagSubjectRef,
    ): List<FlagOverrideRow>

    /** Every row for this schema, in flag-key order. */
    public suspend fun allOverrides(schemaName: String): List<FlagOverrideRow>

    /** Upsert one row and bump the revision, atomically. Returns the new revision. */
    public suspend fun put(
        schemaName: String,
        flagKey: String,
        ref: FlagSubjectRef,
        value: FlagValue,
        updatedAtMillis: Long,
        updatedBy: String = "",
    ): Long

    /**
     * Remove one row. Returns the revision and whether a row actually went away.
     *
     * Clearing an absent row does not bump the revision -- nothing changed, so no client's cached
     * snapshot needs invalidating.
     */
    public suspend fun clear(
        schemaName: String,
        flagKey: String,
        ref: FlagSubjectRef,
    ): Pair<Long, Boolean>

    /** Atomic batch against one subject: the admin panel's "save subject" write. */
    public suspend fun putSubject(
        schemaName: String,
        ref: FlagSubjectRef,
        sets: Map<String, FlagValue>,
        clears: Set<String>,
        updatedAtMillis: Long,
        updatedBy: String = "",
    ): SubjectWriteResult

    /** Subjects with at least one row, filtered by scope/dimension and a key prefix. */
    public suspend fun listSubjects(
        schemaName: String,
        scope: FlagScope,
        dimension: String,
        keyPrefix: String,
        limit: Int,
    ): List<FlagSubjectRef>

    /** flagKey -> number of non-service rows. Drives the admin list's "N overrides" column. */
    public suspend fun overrideCounts(schemaName: String): Map<String, Int>

    /**
     * Rows whose flag key is no longer in the schema, typically left by a renamed or deleted flag.
     *
     * Worth surfacing loudly: renaming a flag silently orphans every override for it and the flag
     * reverts to its code default across the fleet, which is the most likely operational incident
     * with this design.
     */
    public suspend fun orphans(schemaName: String, knownKeys: Set<String>): List<FlagOverrideRow>

    /** Deletes every orphan. Returns the revision and how many rows went away. */
    public suspend fun purgeOrphans(schemaName: String, knownKeys: Set<String>): Pair<Long, Int>

    /** Releases any underlying resources. Idempotent. */
    public suspend fun close()
}
