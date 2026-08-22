package com.latenighthack.ktflags

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A [FlagsRepository] held entirely in memory.
 *
 * This is the reference implementation, not a toy: the abstract `FlagsRepositoryContract` in
 * `ktflags-test` runs against it and against both SQL stores, so its behaviour *is* the spec. It
 * is also what a consumer should use to test their own server handlers, and what powers the
 * in-process harness that needs no database and no port.
 *
 * Multiplatform and safe for concurrent use via a [Mutex]; state is never exposed by reference.
 */
public class InMemoryFlagsRepository : FlagsRepository {
    /** Identifies a row within one schema. */
    private data class RowKey(val flagKey: String, val ref: FlagSubjectRef)

    /**
     * Total order for every multi-row read.
     *
     * Several rows can share a flag key (one per subject), so sorting on the key alone leaves ties
     * unordered -- and an admin listing that reshuffles itself between reads is a bug.
     *
     * Ordered by [storageKey] rather than the enum's declaration order: the SQL stores sort the
     * stored text, so `context` sorts before `service` before `user`, and a reference that
     * disagreed with the databases it defines would not be much of a reference.
     */
    private val rowOrder = compareBy<FlagOverrideRow>(
        { it.flagKey },
        { it.ref.scope.storageKey },
        { it.ref.dimension },
        { it.ref.key },
    )

    private val mutex = Mutex()

    /** schemaName -> rows. Nesting by schema keeps every read a map lookup, not a scan. */
    private val schemas = mutableMapOf<String, MutableMap<RowKey, FlagOverrideRow>>()
    private val revisions = mutableMapOf<String, Long>()

    override suspend fun revision(schemaName: String): Long =
        mutex.withLock { revisions[schemaName] ?: 0L }

    override suspend fun overridesFor(
        schemaName: String,
        subject: FlagSubject,
    ): List<FlagOverrideRow> = mutex.withLock {
        // The rows that could possibly win for this subject. Context entries whose dimension no
        // flag declares are never matched by the evaluator, so there is no need to filter here.
        val wanted = buildSet {
            add(FlagSubjectRef.Service)
            subject.normalizedUserId?.let { add(FlagSubjectRef.user(it)) }
            subject.context.forEach { (dimension, key) ->
                if (dimension.isNotEmpty() && key.isNotEmpty()) {
                    add(FlagSubjectRef.context(dimension, key))
                }
            }
        }
        rowsOf(schemaName).values.filter { it.ref in wanted }.sortedWith(rowOrder)
    }

    override suspend fun serviceOverrides(schemaName: String): List<FlagOverrideRow> =
        mutex.withLock {
            rowsOf(schemaName).values
                .filter { it.ref == FlagSubjectRef.Service }
                .sortedWith(rowOrder)
        }

    override suspend fun overridesForSubject(
        schemaName: String,
        ref: FlagSubjectRef,
    ): List<FlagOverrideRow> = mutex.withLock {
        rowsOf(schemaName).values.filter { it.ref == ref }.sortedWith(rowOrder)
    }

    override suspend fun allOverrides(schemaName: String): List<FlagOverrideRow> =
        mutex.withLock { rowsOf(schemaName).values.sortedWith(rowOrder) }

    override suspend fun put(
        schemaName: String,
        flagKey: String,
        ref: FlagSubjectRef,
        value: FlagValue,
        updatedAtMillis: Long,
        updatedBy: String,
    ): Long = mutex.withLock {
        mutableRowsOf(schemaName)[RowKey(flagKey, ref)] =
            FlagOverrideRow(flagKey, ref, value, updatedAtMillis, updatedBy)
        bump(schemaName)
    }

    override suspend fun clear(
        schemaName: String,
        flagKey: String,
        ref: FlagSubjectRef,
    ): Pair<Long, Boolean> = mutex.withLock {
        val removed = mutableRowsOf(schemaName).remove(RowKey(flagKey, ref)) != null
        // Only a real deletion is a change worth invalidating clients over.
        val revision = if (removed) bump(schemaName) else revisions[schemaName] ?: 0L
        revision to removed
    }

    override suspend fun putSubject(
        schemaName: String,
        ref: FlagSubjectRef,
        sets: Map<String, FlagValue>,
        clears: Set<String>,
        updatedAtMillis: Long,
        updatedBy: String,
    ): SubjectWriteResult = mutex.withLock {
        val rows = mutableRowsOf(schemaName)
        sets.forEach { (flagKey, value) ->
            rows[RowKey(flagKey, ref)] =
                FlagOverrideRow(flagKey, ref, value, updatedAtMillis, updatedBy)
        }
        val cleared = clears.count { rows.remove(RowKey(it, ref)) != null }
        // One bump for the whole batch: it is a single logical change.
        val revision =
            if (sets.isNotEmpty() || cleared > 0) bump(schemaName) else revisions[schemaName] ?: 0L
        SubjectWriteResult(revision, sets.size, cleared)
    }

    override suspend fun listSubjects(
        schemaName: String,
        scope: FlagScope,
        dimension: String,
        keyPrefix: String,
        limit: Int,
    ): List<FlagSubjectRef> = mutex.withLock {
        rowsOf(schemaName).values
            .map { it.ref }
            .filter { it.scope == scope && it.dimension == dimension && it.key.startsWith(keyPrefix) }
            .distinct()
            .sortedBy { it.key }
            .let { if (limit > 0) it.take(limit) else it }
    }

    override suspend fun overrideCounts(schemaName: String): Map<String, Int> = mutex.withLock {
        rowsOf(schemaName).values
            .filter { it.ref.scope != FlagScope.SERVICE }
            .groupingBy { it.flagKey }
            .eachCount()
    }

    override suspend fun orphans(
        schemaName: String,
        knownKeys: Set<String>,
    ): List<FlagOverrideRow> = mutex.withLock {
        rowsOf(schemaName).values.filter { it.flagKey !in knownKeys }.sortedWith(rowOrder)
    }

    override suspend fun purgeOrphans(
        schemaName: String,
        knownKeys: Set<String>,
    ): Pair<Long, Int> = mutex.withLock {
        val rows = mutableRowsOf(schemaName)
        val doomed = rows.keys.filter { it.flagKey !in knownKeys }
        doomed.forEach { rows.remove(it) }
        val revision = if (doomed.isNotEmpty()) bump(schemaName) else revisions[schemaName] ?: 0L
        revision to doomed.size
    }

    override suspend fun close() {
        // Nothing to release.
    }

    private fun rowsOf(schemaName: String): Map<RowKey, FlagOverrideRow> =
        schemas[schemaName] ?: emptyMap()

    private fun mutableRowsOf(schemaName: String): MutableMap<RowKey, FlagOverrideRow> =
        schemas.getOrPut(schemaName) { mutableMapOf() }

    /** Must be called with [mutex] held. */
    private fun bump(schemaName: String): Long {
        val next = (revisions[schemaName] ?: 0L) + 1L
        revisions[schemaName] = next
        return next
    }
}
