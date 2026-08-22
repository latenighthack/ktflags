package com.latenighthack.ktflags.store.sqlite

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import com.latenighthack.ktflags.FlagOverrideRow
import com.latenighthack.ktflags.FlagScope
import com.latenighthack.ktflags.FlagSubject
import com.latenighthack.ktflags.FlagSubjectRef
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.FlagsRepository
import com.latenighthack.ktflags.flagScopeFromStorageKey
import com.latenighthack.ktflags.likePrefixPattern
import com.latenighthack.ktflags.storageKey
import com.latenighthack.ktflags.SubjectWriteResult
import com.latenighthack.ktflags.store.sqlite.db.Flag_override
import com.latenighthack.ktflags.store.sqlite.db.KtflagsSqliteDb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The zero-config default store: one SQLite file.
 *
 * Right for a single node, a dev machine, or a container with a volume. For more than one server
 * instance use `ktflags-store-postgres` -- two JVMs against one SQLite file will race the revision
 * counter even though [writeMutex] makes a single JVM safe.
 *
 * ```
 * val repository = SqliteFlagsRepository.open("/var/lib/acme/flags.db")
 * ```
 */
public class SqliteFlagsRepository private constructor(
    private val driver: SqlDriver,
    private val database: KtflagsSqliteDb,
    private val clock: () -> Long,
) : FlagsRepository {

    /**
     * SQLite takes one writer at a time. Without this, concurrent admin writes under a Ktor
     * thread pool produce SQLITE_BUSY rather than waiting their turn.
     */
    private val writeMutex = Mutex()

    private val queries get() = database.flagOverrideQueries

    override suspend fun revision(schemaName: String): Long =
        io { queries.selectRevision(schemaName).executeAsOneOrNull() ?: 0L }

    override suspend fun overridesFor(
        schemaName: String,
        subject: FlagSubject,
    ): List<FlagOverrideRow> = io {
        val serviceAndUser = queries
            .selectServiceAndUser(schemaName, subject.normalizedUserId.orEmpty())
            .executeAsList()

        val dimensions = subject.context.filterValues { it.isNotEmpty() }.keys.filter { it.isNotEmpty() }
        if (dimensions.isEmpty()) return@io serviceAndUser.map { it.toDomain() }

        // The IN-list query over-fetches the cross product, so filter to the exact pairs asked for.
        val wanted = subject.context
            .filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
            .map { it.key to it.value }
            .toSet()
        val context = queries
            .selectContextCandidates(schemaName, dimensions, wanted.map { it.second })
            .executeAsList()
            .filter { (it.scope_dim to it.scope_key) in wanted }

        (serviceAndUser + context).map { it.toDomain() }
    }

    override suspend fun serviceOverrides(schemaName: String): List<FlagOverrideRow> =
        io { queries.selectService(schemaName).executeAsList().map { it.toDomain() } }

    override suspend fun overridesForSubject(
        schemaName: String,
        ref: FlagSubjectRef,
    ): List<FlagOverrideRow> = io {
        queries.selectSubject(schemaName, ref.scope.storageKey, ref.dimension, ref.key)
            .executeAsList()
            .map { it.toDomain() }
    }

    override suspend fun allOverrides(schemaName: String): List<FlagOverrideRow> =
        io { queries.selectAll(schemaName).executeAsList().map { it.toDomain() } }

    override suspend fun put(
        schemaName: String,
        flagKey: String,
        ref: FlagSubjectRef,
        value: FlagValue,
        updatedAtMillis: Long,
        updatedBy: String,
    ): Long = write {
        database.transactionWithResult {
            queries.upsertRow(schemaName, flagKey, ref, value, updatedAtMillis, updatedBy)
            queries.bumpRevision(schemaName, clock())
            queries.selectRevision(schemaName).executeAsOne()
        }
    }

    override suspend fun clear(
        schemaName: String,
        flagKey: String,
        ref: FlagSubjectRef,
    ): Pair<Long, Boolean> = write {
        database.transactionWithResult {
            queries.deleteOne(schemaName, flagKey, ref.scope.storageKey, ref.dimension, ref.key)
            val removed = queries.changes().executeAsOne() > 0L
            // Only a real deletion invalidates a client's cached revision.
            if (removed) queries.bumpRevision(schemaName, clock())
            (queries.selectRevision(schemaName).executeAsOneOrNull() ?: 0L) to removed
        }
    }

    override suspend fun putSubject(
        schemaName: String,
        ref: FlagSubjectRef,
        sets: Map<String, FlagValue>,
        clears: Set<String>,
        updatedAtMillis: Long,
        updatedBy: String,
    ): SubjectWriteResult = write {
        database.transactionWithResult {
            sets.forEach { (flagKey, value) ->
                queries.upsertRow(schemaName, flagKey, ref, value, updatedAtMillis, updatedBy)
            }
            var cleared = 0
            clears.forEach { flagKey ->
                queries.deleteOne(schemaName, flagKey, ref.scope.storageKey, ref.dimension, ref.key)
                if (queries.changes().executeAsOne() > 0L) cleared++
            }
            // One bump for the batch: it is a single logical change.
            if (sets.isNotEmpty() || cleared > 0) queries.bumpRevision(schemaName, clock())
            SubjectWriteResult(
                revision = queries.selectRevision(schemaName).executeAsOneOrNull() ?: 0L,
                written = sets.size,
                cleared = cleared,
            )
        }
    }

    override suspend fun listSubjects(
        schemaName: String,
        scope: FlagScope,
        dimension: String,
        keyPrefix: String,
        limit: Int,
    ): List<FlagSubjectRef> = io {
        queries
            .listSubjects(
                schemaName,
                scope.storageKey,
                dimension,
                likePrefixPattern(keyPrefix),
                if (limit > 0) limit.toLong() else Long.MAX_VALUE,
            )
            .executeAsList()
            .mapNotNull { it.scope_kind.let(::flagScopeFromStorageKey)?.let { s -> FlagSubjectRef(s, it.scope_dim, it.scope_key) } }
    }

    override suspend fun overrideCounts(schemaName: String): Map<String, Int> = io {
        queries.overrideCounts(schemaName).executeAsList()
            .associate { it.flag_key to it.override_count.toInt() }
    }

    override suspend fun orphans(
        schemaName: String,
        knownKeys: Set<String>,
    ): List<FlagOverrideRow> = io {
        // `NOT IN ()` is a syntax error, not "matches everything", so an empty schema has to take
        // the every-row path explicitly.
        if (knownKeys.isEmpty()) {
            queries.selectAll(schemaName).executeAsList().map { it.toDomain() }
        } else {
            queries.selectOrphans(schemaName, knownKeys).executeAsList().map { it.toDomain() }
        }
    }

    override suspend fun purgeOrphans(
        schemaName: String,
        knownKeys: Set<String>,
    ): Pair<Long, Int> = write {
        database.transactionWithResult {
            val doomed = if (knownKeys.isEmpty()) {
                queries.selectAll(schemaName).executeAsList().size
            } else {
                queries.selectOrphans(schemaName, knownKeys).executeAsList().size
            }
            if (doomed > 0) {
                if (knownKeys.isEmpty()) {
                    queries.deleteAllForSchema(schemaName)
                } else {
                    queries.deleteOrphans(schemaName, knownKeys)
                }
                queries.bumpRevision(schemaName, clock())
            }
            (queries.selectRevision(schemaName).executeAsOneOrNull() ?: 0L) to doomed
        }
    }

    override suspend fun close() {
        withContext(Dispatchers.IO) { driver.close() }
    }

    /**
     * Every generated SQLDelight query is blocking. Running one on Ktor's CIO event loop stalls
     * the server with no error and no warning, so every entry point goes through here rather than
     * relying on each method to remember.
     */
    private suspend fun <R> io(block: () -> R): R = withContext(Dispatchers.IO) { block() }

    private suspend fun <R> write(block: () -> R): R = writeMutex.withLock { io(block) }

    public companion object {
        /** Opens (and creates, if needed) a SQLite database at [path]. */
        public fun open(path: String, clock: () -> Long = System::currentTimeMillis): SqliteFlagsRepository {
            File(path).absoluteFile.parentFile?.mkdirs()
            return create(JdbcSqliteDriver("jdbc:sqlite:$path"), clock)
        }

        /** An ephemeral database. The default for tests and for a stateless dev server. */
        public fun inMemory(clock: () -> Long = System::currentTimeMillis): SqliteFlagsRepository =
            create(JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY), clock)

        /** Wraps a driver you own. The schema is created if it is not already present. */
        public fun create(
            driver: SqlDriver,
            clock: () -> Long = System::currentTimeMillis,
        ): SqliteFlagsRepository {
            // WAL lets readers proceed during a write, and busy_timeout turns the remaining
            // contention into a short wait instead of an immediate SQLITE_BUSY.
            runCatching {
                driver.execute(null, "PRAGMA journal_mode=WAL;", 0)
                driver.execute(null, "PRAGMA busy_timeout=5000;", 0)
            }
            if (!driver.hasFlagTables()) {
                KtflagsSqliteDb.Schema.create(driver)
            }
            return SqliteFlagsRepository(driver, KtflagsSqliteDb(driver), clock)
        }

        private fun SqlDriver.hasFlagTables(): Boolean = runCatching {
            executeQuery(
                null,
                "SELECT name FROM sqlite_master WHERE type='table' AND name='flag_override'",
                { cursor -> app.cash.sqldelight.db.QueryResult.Value(cursor.next().value) },
                0,
            ).value
        }.getOrDefault(false)
    }
}

// --- row codec ----------------------------------------------------------------------------------

/**
 * Decodes a row.
 *
 * The CHECK constraints make a malformed row impossible to insert through this repository, so an
 * unreadable one means the file was edited by hand or written by an older version. Throwing here
 * is right: silently substituting a default would hide real data corruption, and this path is only
 * reachable when the database already disagrees with its own schema.
 */
private fun Flag_override.toDomain(): FlagOverrideRow {
    val scope = scope_kind.let(::flagScopeFromStorageKey)
        ?: error("flag_override row for '$flag_key' has an unknown scope_kind '$scope_kind'")
    val value = when (value_type) {
        "bool" -> bool_value?.let { FlagValue.BoolValue(it != 0L) }
        "string" -> string_value?.let(FlagValue::StringValue)
        "int" -> int_value?.let { FlagValue.IntValue(it.toInt()) }
        "double" -> double_value?.let(FlagValue::DoubleValue)
        else -> null
    } ?: error("flag_override row for '$flag_key' has value_type '$value_type' but no matching value")

    return FlagOverrideRow(
        flagKey = flag_key,
        ref = FlagSubjectRef(scope, scope_dim, scope_key),
        value = value,
        updatedAtMillis = updated_at_ms,
        updatedBy = updated_by,
    )
}

private fun com.latenighthack.ktflags.store.sqlite.db.FlagOverrideQueries.upsertRow(
    schemaName: String,
    flagKey: String,
    ref: FlagSubjectRef,
    value: FlagValue,
    updatedAtMillis: Long,
    updatedBy: String,
) {
    upsert(
        schemaName = schemaName,
        flagKey = flagKey,
        scopeKind = ref.scope.storageKey,
        scopeDim = ref.dimension,
        scopeKey = ref.key,
        valueType = value.storageKey,
        boolValue = (value as? FlagValue.BoolValue)?.let { if (it.value) 1L else 0L },
        stringValue = (value as? FlagValue.StringValue)?.value,
        intValue = (value as? FlagValue.IntValue)?.value?.toLong(),
        doubleValue = (value as? FlagValue.DoubleValue)?.value,
        nowMs = updatedAtMillis,
        updatedBy = updatedBy,
    )
}
