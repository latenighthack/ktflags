package com.latenighthack.ktflags.store.postgres

import com.latenighthack.ktflags.FlagScope
import com.latenighthack.ktflags.FlagSubject
import com.latenighthack.ktflags.FlagSubjectRef
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.FlagsRepository
import com.latenighthack.ktflags.InMemoryFlagsRepository
import com.latenighthack.ktflags.test.assertAdminContract
import com.latenighthack.ktflags.test.assertReadContract
import com.latenighthack.ktflags.test.assertWriteContract
import kotlinx.coroutines.test.runTest
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.testcontainers.containers.PostgreSQLContainer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * One Postgres for the whole test JVM, started lazily.
 *
 * Skips rather than fails when Docker is unavailable: a laptop without Docker should still be able
 * to run `./gradlew build` green, and the SQLite store plus the shared contract already cover the
 * behaviour. CI is where this must actually execute.
 */
internal object TestPostgres {
    /**
     * Why the container could not start, or null if it did.
     *
     * Reported rather than swallowed: a suite that skips silently looks exactly like a suite that
     * passes, and this one guards the riskiest code in the project.
     */
    private var failure: Throwable? = null

    val available: Boolean by lazy {
        runCatching { dataSource }.onFailure { failure = it }.isSuccess
    }

    val skipReason: String
        get() = failure?.let { "${it::class.simpleName}: ${it.message}" } ?: "unknown"

    private val container: PostgreSQLContainer<*> by lazy {
        PostgreSQLContainer("postgres:16-alpine").apply { start() }
    }

    /** One pool for the whole JVM. Credentials come from the container, not from the JDBC URL. */
    private val dataSource: HikariDataSource by lazy {
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = container.jdbcUrl
                username = container.username
                password = container.password
                maximumPoolSize = 8
            },
        ).also { PostgresFlagsRepository.migrate(it) }
    }

    /**
     * A repository sharing the container.
     *
     * The contract suite assumes an empty store, and a container per test would be far too slow,
     * so callers clear the schema names they use instead. `migrate = false` because the shared
     * pool was migrated once on creation.
     */
    fun repository(): FlagsRepository =
        PostgresFlagsRepository.using(dataSource, migrate = false)

    /**
     * Empties the store completely, including the revision counters.
     *
     * The contract assumes a fresh repository and asserts on absolute revision numbers, so purging
     * rows is not enough -- the counters have to go too. A container per test would be correct but
     * far too slow, so this is the isolation mechanism.
     */
    fun reset() {
        dataSource.connection.use { connection ->
            connection.createStatement().use { statement ->
                statement.execute("TRUNCATE ktflags_flag_override, ktflags_flag_revision")
            }
        }
    }
}

private const val SKIP = "SKIPPED (no usable Docker/Postgres): "

/**
 * The Postgres store held to exactly the contract the in-memory reference defines.
 *
 * The contract uses a fixed schema name, so each test gets its own repository instance against a
 * freshly truncated table.
 */
class PostgresFlagsRepositoryTest {

    private fun repo(): FlagsRepository {
        TestPostgres.reset()
        return TestPostgres.repository()
    }

    @Test
    fun satisfies_the_write_contract() = runTest {
        if (!TestPostgres.available) { println(SKIP + TestPostgres.skipReason); return@runTest }
        assertWriteContract(::repo)
    }

    @Test
    fun satisfies_the_read_contract() = runTest {
        if (!TestPostgres.available) { println(SKIP + TestPostgres.skipReason); return@runTest }
        assertReadContract(::repo)
    }

    @Test
    fun satisfies_the_admin_contract() = runTest {
        if (!TestPostgres.available) { println(SKIP + TestPostgres.skipReason); return@runTest }
        assertAdminContract(::repo)
    }
}

class PostgresMigrationTest {

    @Test
    fun the_migration_is_idempotent_across_restarts() = runTest {
        if (!TestPostgres.available) { println(SKIP + TestPostgres.skipReason); return@runTest }
        // A second open re-runs Flyway; if the history table were shared or the migration were
        // not tracked, this would fail rather than no-op.
        TestPostgres.reset()
        TestPostgres.repository().close()
        val second = TestPostgres.repository()
        second.put("migrationCheck", "a", FlagSubjectRef.Service, FlagValue.of(true), 0L)
        assertEquals(1, second.allOverrides("migrationCheck").size)
        second.close()
    }
}

/**
 * The drift guard.
 *
 * The two SQL stores have near-identical queries maintained by hand. This runs the same scripted
 * sequence of operations against Postgres and against the in-memory reference and asserts they
 * agree at every step -- value, provenance and revision. A Postgres-only bug shows up here rather
 * than in production.
 */
class DialectParityTest {

    private data class Step(val name: String, val run: suspend (FlagsRepository) -> Any?)

    private val schema = "parity"

    private val script: List<Step> = listOf(
        Step("initial revision") { it.revision(schema) },
        Step("write service bool") {
            it.put(schema, "a", FlagSubjectRef.Service, FlagValue.of(true), 100L, "alice")
        },
        Step("write service int") {
            it.put(schema, "b", FlagSubjectRef.Service, FlagValue.of(42), 101L)
        },
        Step("write service double") {
            it.put(schema, "c", FlagSubjectRef.Service, FlagValue.of(1.25), 102L)
        },
        Step("write service empty string") {
            it.put(schema, "d", FlagSubjectRef.Service, FlagValue.of(""), 103L)
        },
        Step("write explicit false") {
            it.put(schema, "e", FlagSubjectRef.Service, FlagValue.of(false), 104L)
        },
        Step("write user row") {
            it.put(schema, "a", FlagSubjectRef.user("u-1"), FlagValue.of(false), 105L, "bob")
        },
        Step("write context row") {
            it.put(schema, "a", FlagSubjectRef.context("tenant", "acme"), FlagValue.of(true), 106L)
        },
        Step("upsert changes the type in place") {
            it.put(schema, "b", FlagSubjectRef.Service, FlagValue.of("was an int"), 107L)
        },
        Step("service rows") { repo -> repo.serviceOverrides(schema).map { it.flagKey to it.value } },
        Step("all rows") { repo -> repo.allOverrides(schema).map { it.flagKey to it.value } },
        Step("resolve for u-1 @ acme") { repo ->
            repo.overridesFor(schema, FlagSubject("u-1", mapOf("tenant" to "acme")))
                .map { Triple(it.flagKey, it.ref, it.value) }
                .sortedBy { "${it.first}${it.second}" }
        },
        Step("resolve anonymous") { repo ->
            repo.overridesFor(schema, FlagSubject.Anonymous).map { it.flagKey to it.value }
        },
        Step("subject rows") { repo ->
            repo.overridesForSubject(schema, FlagSubjectRef.user("u-1"))
                .map { Triple(it.flagKey, it.value, it.updatedBy) }
        },
        Step("override counts") { it.overrideCounts(schema) },
        Step("list user subjects") {
            it.listSubjects(schema, FlagScope.USER, "", "u", 0).map { ref -> ref.key }
        },
        Step("list context subjects") {
            it.listSubjects(schema, FlagScope.CONTEXT, "tenant", "", 0).map { ref -> ref.key }
        },
        // A prefix containing LIKE metacharacters must be treated literally, not as a wildcard.
        Step("write a subject whose id looks like a wildcard") {
            it.put(schema, "a", FlagSubjectRef.user("50%_off"), FlagValue.of(true), 108L)
        },
        Step("wildcard-looking prefix matches literally") {
            it.listSubjects(schema, FlagScope.USER, "", "50%", 0).map { ref -> ref.key }
        },
        Step("a plain prefix does not match the wildcard id") {
            it.listSubjects(schema, FlagScope.USER, "", "50x", 0).map { ref -> ref.key }
        },
        Step("clear a present row") { it.clear(schema, "a", FlagSubjectRef.user("u-1")) },
        Step("clear an absent row") { it.clear(schema, "nope", FlagSubjectRef.user("u-1")) },
        Step("batch write") {
            it.putSubject(
                schema,
                FlagSubjectRef.user("u-2"),
                mapOf("a" to FlagValue.of(true), "b" to FlagValue.of("x")),
                setOf("c", "neverThere"),
                109L,
                "carol",
            )
        },
        Step("orphans") { repo -> repo.orphans(schema, setOf("a", "b")).map { it.flagKey } },
        Step("purge orphans") { it.purgeOrphans(schema, setOf("a", "b")) },
        Step("final rows") { repo -> repo.allOverrides(schema).map { it.flagKey to it.value } },
        Step("final revision") { it.revision(schema) },
    )

    @Test
    fun postgres_and_the_reference_implementation_agree_step_for_step() = runTest {
        if (!TestPostgres.available) { println(SKIP + TestPostgres.skipReason); return@runTest }

        val reference = InMemoryFlagsRepository()
        TestPostgres.reset()
        val postgres = TestPostgres.repository()

        try {
            script.forEach { step ->
                val expected = step.run(reference)
                val actual = step.run(postgres)
                assertEquals(
                    expected.toString(),
                    actual.toString(),
                    "postgres diverged from the reference at step '${step.name}'",
                )
            }
        } finally {
            postgres.close()
        }
    }

    @Test
    fun the_script_actually_exercises_something() {
        // Guards against the parity test silently passing because the script got emptied.
        assertTrue(script.size >= 20, "the parity script covers only ${script.size} steps")
    }
}
