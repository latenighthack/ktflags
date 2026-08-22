package com.latenighthack.ktflags.store.postgres

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.asJdbcDriver
import com.latenighthack.ktflags.FlagOverrideRow
import com.latenighthack.ktflags.FlagScope
import com.latenighthack.ktflags.FlagSubject
import com.latenighthack.ktflags.FlagSubjectRef
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.FlagsRepository
import com.latenighthack.ktflags.SubjectWriteResult
import com.latenighthack.ktflags.flagScopeFromStorageKey
import com.latenighthack.ktflags.likePrefixPattern
import com.latenighthack.ktflags.storageKey
import com.latenighthack.ktflags.store.postgres.db.Ktflags_flag_override
import com.latenighthack.ktflags.store.postgres.db.KtflagsPostgresDb
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import java.net.URI
import javax.sql.DataSource

/**
 * The multi-instance store: PostgreSQL, with Flyway migrations.
 *
 * Use this when more than one server instance serves the same flags. The SQLite store is fine for
 * a single node but two JVMs against one file will race the revision counter.
 *
 * ```
 * val repository = PostgresFlagsRepository.open(System.getenv("DATABASE_URL"))
 * ```
 *
 * ### Living in someone else's database
 *
 * These tables go into the *host application's* database, so two things are deliberately not the
 * defaults you would pick for a standalone service:
 *
 *  - Migrations ship under `classpath:ktflags/db/migration`, not the conventional `db/migration`.
 *    A host running Flyway with default locations would otherwise sweep ours up alongside its own
 *    and the version numbering would collide.
 *  - Flyway records history in `ktflags_schema_history`, not the shared `flyway_schema_history`.
 *    Sharing that table with the host is a guaranteed production incident the first time either
 *    side migrates.
 *  - Every table is prefixed `ktflags_`.
 */
public class PostgresFlagsRepository private constructor(
    private val driver: SqlDriver,
    private val database: KtflagsPostgresDb,
    private val ownedDataSource: HikariDataSource?,
    private val clock: () -> Long,
) : FlagsRepository {

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
    ): Long = io {
        // No write mutex here, unlike SQLite: Postgres handles concurrent writers, and the
        // transaction plus the revision row's own lock serialise the counter correctly.
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
    ): Pair<Long, Boolean> = io {
        database.transactionWithResult {
            // Counted inside the transaction because this dialect will not parse
            // DELETE ... RETURNING and Postgres has no changes().
            val existed = queries
                .countOne(schemaName, flagKey, ref.scope.storageKey, ref.dimension, ref.key)
                .executeAsOne() > 0L
            if (existed) {
                queries.deleteOne(schemaName, flagKey, ref.scope.storageKey, ref.dimension, ref.key)
                queries.bumpRevision(schemaName, clock())
            }
            (queries.selectRevision(schemaName).executeAsOneOrNull() ?: 0L) to existed
        }
    }

    override suspend fun putSubject(
        schemaName: String,
        ref: FlagSubjectRef,
        sets: Map<String, FlagValue>,
        clears: Set<String>,
        updatedAtMillis: Long,
        updatedBy: String,
    ): SubjectWriteResult = io {
        database.transactionWithResult {
            sets.forEach { (flagKey, value) ->
                queries.upsertRow(schemaName, flagKey, ref, value, updatedAtMillis, updatedBy)
            }
            var cleared = 0
            clears.forEach { flagKey ->
                val existed = queries
                    .countOne(schemaName, flagKey, ref.scope.storageKey, ref.dimension, ref.key)
                    .executeAsOne() > 0L
                if (existed) {
                    queries.deleteOne(schemaName, flagKey, ref.scope.storageKey, ref.dimension, ref.key)
                    cleared++
                }
            }
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
            .mapNotNull { row ->
                row.scope_kind.let(::flagScopeFromStorageKey)?.let { FlagSubjectRef(it, row.scope_dim, row.scope_key) }
            }
    }

    override suspend fun overrideCounts(schemaName: String): Map<String, Int> = io {
        queries.overrideCounts(schemaName).executeAsList()
            .associate { it.flag_key to it.override_count.toInt() }
    }

    override suspend fun orphans(
        schemaName: String,
        knownKeys: Set<String>,
    ): List<FlagOverrideRow> = io {
        // `NOT IN ()` is a syntax error, not "matches everything".
        if (knownKeys.isEmpty()) {
            queries.selectAll(schemaName).executeAsList().map { it.toDomain() }
        } else {
            queries.selectOrphans(schemaName, knownKeys).executeAsList().map { it.toDomain() }
        }
    }

    override suspend fun purgeOrphans(
        schemaName: String,
        knownKeys: Set<String>,
    ): Pair<Long, Int> = io {
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
        withContext(Dispatchers.IO) {
            driver.close()
            // Only a pool this repository created is ours to shut down.
            ownedDataSource?.close()
        }
    }

    /**
     * Every generated SQLDelight query is blocking. Running one on Ktor's CIO event loop stalls
     * the server with no error and no warning, so every entry point goes through here rather than
     * relying on each method to remember.
     */
    private suspend fun <R> io(block: () -> R): R = withContext(Dispatchers.IO) { block() }

    public companion object {
        /**
         * Opens a pool against [databaseUrl] and migrates.
         *
         * Accepts a `postgresql://user:pass@host/db` URL (what Railway, Heroku and friends set) as
         * well as a plain `jdbc:postgresql://...` one.
         */
        public fun open(
            databaseUrl: String,
            maxPoolSize: Int = 10,
            clock: () -> Long = System::currentTimeMillis,
        ): PostgresFlagsRepository {
            val dataSource = createDataSource(databaseUrl, maxPoolSize)
            migrate(dataSource)
            return PostgresFlagsRepository(
                driver = dataSource.asJdbcDriver(),
                database = KtflagsPostgresDb(dataSource.asJdbcDriver()),
                ownedDataSource = dataSource,
                clock = clock,
            )
        }

        /**
         * Uses a [DataSource] you already own, e.g. your application's existing pool.
         *
         * Runs the migration but does not close the pool.
         */
        public fun using(
            dataSource: DataSource,
            migrate: Boolean = true,
            clock: () -> Long = System::currentTimeMillis,
        ): PostgresFlagsRepository {
            if (migrate) migrate(dataSource)
            val driver = dataSource.asJdbcDriver()
            return PostgresFlagsRepository(driver, KtflagsPostgresDb(driver), null, clock)
        }

        /**
         * Applies ktflags' migrations.
         *
         * Namespaced away from the host's own Flyway setup on both axes -- see the class docs.
         */
        public fun migrate(dataSource: DataSource) {
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:ktflags/db/migration")
                .table("ktflags_schema_history")
                .load()
                .migrate()
        }

        public fun createDataSource(databaseUrl: String, maxPoolSize: Int = 10): HikariDataSource {
            val config = HikariConfig()
            if (databaseUrl.startsWith("jdbc:")) {
                config.jdbcUrl = databaseUrl
            } else {
                val uri = URI(databaseUrl)
                val credentials = uri.userInfo?.split(':', limit = 2)
                config.jdbcUrl = buildString {
                    append("jdbc:postgresql://").append(uri.host)
                    if (uri.port != -1) append(':').append(uri.port)
                    append(uri.path)
                    uri.query?.let { append('?').append(it) }
                }
                credentials?.getOrNull(0)?.let { config.username = it }
                credentials?.getOrNull(1)?.let { config.password = it }
            }
            config.maximumPoolSize = maxPoolSize
            return HikariDataSource(config)
        }
    }
}

// --- row codec ----------------------------------------------------------------------------------
// Deliberately the same shape as the SQLite store's, so the two can be diffed against each other.

/**
 * Decodes a row.
 *
 * The CHECK constraints make a malformed row impossible to insert through this repository, so an
 * unreadable one means the table was edited by hand or written by an older version. Throwing is
 * right: substituting a default would hide real corruption.
 */
private fun Ktflags_flag_override.toDomain(): FlagOverrideRow {
    val scope = scope_kind.let(::flagScopeFromStorageKey)
        ?: error("ktflags_flag_override row for '$flag_key' has unknown scope_kind '$scope_kind'")
    val value = when (value_type) {
        "bool" -> bool_value?.let(FlagValue::BoolValue)
        "string" -> string_value?.let(FlagValue::StringValue)
        "int" -> int_value?.let { FlagValue.IntValue(it.toInt()) }
        "double" -> double_value?.let(FlagValue::DoubleValue)
        else -> null
    } ?: error("ktflags_flag_override row for '$flag_key' has value_type '$value_type' but no value")

    return FlagOverrideRow(
        flagKey = flag_key,
        ref = FlagSubjectRef(scope, scope_dim, scope_key),
        value = value,
        updatedAtMillis = updated_at_ms,
        updatedBy = updated_by,
    )
}

private fun com.latenighthack.ktflags.store.postgres.db.FlagOverrideQueries.upsertRow(
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
        boolValue = (value as? FlagValue.BoolValue)?.value,
        stringValue = (value as? FlagValue.StringValue)?.value,
        intValue = (value as? FlagValue.IntValue)?.value?.toLong(),
        doubleValue = (value as? FlagValue.DoubleValue)?.value,
        nowMs = updatedAtMillis,
        updatedBy = updatedBy,
    )
}
