package com.latenighthack.ktflags.store.sqlite

import com.latenighthack.ktflags.FlagSubjectRef
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.FlagsRepository
import com.latenighthack.ktflags.test.assertAdminContract
import com.latenighthack.ktflags.test.assertReadContract
import com.latenighthack.ktflags.test.assertWriteContract
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The SQLite store held to exactly the same contract as the in-memory reference. */
class SqliteFlagsRepositoryTest {
    private fun repo(): FlagsRepository = SqliteFlagsRepository.inMemory()

    @Test
    fun satisfies_the_write_contract() = runTest { assertWriteContract(::repo) }

    @Test
    fun satisfies_the_read_contract() = runTest { assertReadContract(::repo) }

    @Test
    fun satisfies_the_admin_contract() = runTest { assertAdminContract(::repo) }
}

/** The same contract again, but against a real file rather than `:memory:`. */
class SqliteFileFlagsRepositoryTest {
    private fun repo(): FlagsRepository {
        val dir = Files.createTempDirectory("ktflags-sqlite").toFile().apply { deleteOnExit() }
        return SqliteFlagsRepository.open("${dir.absolutePath}/flags.db")
    }

    @Test
    fun satisfies_the_write_contract() = runTest { assertWriteContract(::repo) }

    @Test
    fun satisfies_the_read_contract() = runTest { assertReadContract(::repo) }

    @Test
    fun satisfies_the_admin_contract() = runTest { assertAdminContract(::repo) }
}

class SqliteFlagsRepositoryBehaviourTest {

    @Test
    fun data_survives_a_reopen() = runTest {
        val dir = Files.createTempDirectory("ktflags-sqlite").toFile().apply { deleteOnExit() }
        val path = "${dir.absolutePath}/flags.db"

        SqliteFlagsRepository.open(path).use { repo ->
            repo.put("S", "darkMode", FlagSubjectRef.user("u-1"), FlagValue.of(true), 7L, "me")
        }

        SqliteFlagsRepository.open(path).use { repo ->
            val row = repo.overridesForSubject("S", FlagSubjectRef.user("u-1")).single()
            assertEquals(FlagValue.of(true), row.value)
            assertEquals(7L, row.updatedAtMillis)
            assertEquals(1L, repo.revision("S"), "the revision must survive a reopen too")
        }
    }

    @Test
    fun opening_an_existing_database_does_not_recreate_the_schema() = runTest {
        val dir = Files.createTempDirectory("ktflags-sqlite").toFile().apply { deleteOnExit() }
        val path = "${dir.absolutePath}/flags.db"
        SqliteFlagsRepository.open(path).use { it.put("S", "a", FlagSubjectRef.Service, FlagValue.of(1), 0L) }
        // A second open that ran CREATE TABLE again would throw rather than reach the assertion.
        SqliteFlagsRepository.open(path).use { assertEquals(1, it.allOverrides("S").size) }
    }

    @Test
    fun open_creates_missing_parent_directories() = runTest {
        val dir = Files.createTempDirectory("ktflags-sqlite").toFile().apply { deleteOnExit() }
        val path = "${dir.absolutePath}/nested/deeper/flags.db"
        SqliteFlagsRepository.open(path).use { it.put("S", "a", FlagSubjectRef.Service, FlagValue.of(1), 0L) }
        assertTrue(java.io.File(path).isFile)
    }

    /**
     * SQLite takes one writer at a time. Without the write mutex plus WAL and busy_timeout, this
     * produces SQLITE_BUSY under load rather than waiting its turn.
     */
    @Test
    fun concurrent_writers_get_distinct_monotonic_revisions() = runTest {
        SqliteFlagsRepository.inMemory().use { repo ->
            val revisions = (1..50)
                .map { n -> async { repo.put("S", "flag$n", FlagSubjectRef.Service, FlagValue.of(n), 0L) } }
                .awaitAll()

            assertEquals(50, revisions.toSet().size, "revisions collided under concurrent writes")
            assertEquals((1L..50L).toList(), revisions.sorted())
            assertEquals(50, repo.allOverrides("S").size)
        }
    }

    /**
     * The CHECK constraints are the store's own safety net; this proves they actually reached the
     * generated DDL rather than being silently dropped.
     */
    @Test
    fun the_one_value_check_constraint_is_enforced_by_the_database() = runTest {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver(
            app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver.IN_MEMORY,
        )
        SqliteFlagsRepository.create(driver).use {
            val twoValues = runCatching {
                driver.execute(
                    null,
                    """
                    INSERT INTO flag_override(schema_name, flag_key, scope_kind, scope_dim, scope_key,
                        value_type, bool_value, string_value, int_value, double_value,
                        updated_at_ms, updated_by)
                    VALUES ('S','k','service','','','bool',1,'also a string',NULL,NULL,0,'')
                    """.trimIndent(),
                    0,
                )
            }
            assertTrue(twoValues.isFailure, "a row with two populated value columns must be rejected")

            val badScope = runCatching {
                driver.execute(
                    null,
                    """
                    INSERT INTO flag_override(schema_name, flag_key, scope_kind, scope_dim, scope_key,
                        value_type, bool_value, string_value, int_value, double_value,
                        updated_at_ms, updated_by)
                    VALUES ('S','k','nonsense','','','bool',1,NULL,NULL,NULL,0,'')
                    """.trimIndent(),
                    0,
                )
            }
            assertTrue(badScope.isFailure, "an unknown scope_kind must be rejected")
        }
    }
}

/** Closes the repository after [block], mirroring the contract helper. */
private suspend inline fun <R> SqliteFlagsRepository.use(block: (SqliteFlagsRepository) -> R): R =
    try {
        block(this)
    } finally {
        close()
    }
