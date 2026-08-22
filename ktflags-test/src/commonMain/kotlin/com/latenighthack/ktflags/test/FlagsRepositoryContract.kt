package com.latenighthack.ktflags.test

import com.latenighthack.ktflags.FlagScope
import com.latenighthack.ktflags.FlagSubject
import com.latenighthack.ktflags.FlagSubjectRef
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.FlagsRepository
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The behaviour every [FlagsRepository] must have.
 *
 * There are three implementations -- in-memory, SQLite and PostgreSQL -- and the two SQL ones have
 * near-identical queries. This suite is the only thing standing between that and a Postgres-only
 * bug found in production, so running it is not optional for a new store.
 *
 * These are suspend *functions* rather than an abstract class of `@Test` methods on purpose:
 * `kotlin.test.Test` is a per-framework typealias on JVM and Android, so declaring it in a
 * published library's main source set would force a test-runner choice on every consumer. Call
 * them from your own tests, which also keeps failures attributable to a named test:
 *
 * ```
 * class SqliteFlagsRepositoryTest {
 *     private fun repo(): FlagsRepository = SqliteFlagsRepository.inMemory()
 *
 *     @Test fun writes() = runTest { assertWriteContract(::repo) }
 *     @Test fun reads()  = runTest { assertReadContract(::repo) }
 *     @Test fun admin()  = runTest { assertAdminContract(::repo) }
 * }
 * ```
 */
public typealias RepositoryFactory = () -> FlagsRepository

private const val SCHEMA = "TestFlags"
private val USER = FlagSubjectRef.user("u-42")

/** Each check gets a fresh repository, and closes it even when an assertion fails. */
private suspend fun RepositoryFactory.use(block: suspend (FlagsRepository) -> Unit) {
    val repository = this()
    try {
        block(repository)
    } finally {
        runCatching { repository.close() }
    }
}

/** Every check below, for a store that wants a single call. */
public suspend fun assertFlagsRepositoryContract(factory: RepositoryFactory) {
    assertWriteContract(factory)
    assertReadContract(factory)
    assertAdminContract(factory)
}

/** Writes, upserts, deletes, and how each affects the revision counter. */
public suspend fun assertWriteContract(factory: RepositoryFactory) {
    factory.use { repo ->
        assertEquals(0L, repo.revision(SCHEMA), "an empty repository starts at revision 0")
    }

    factory.use { repo ->
        repo.put(SCHEMA, "darkMode", USER, FlagValue.of(true), updatedAtMillis = 5L, updatedBy = "me")
        val row = repo.overridesForSubject(SCHEMA, USER).single()
        assertEquals("darkMode", row.flagKey)
        assertEquals(USER, row.ref)
        assertEquals(FlagValue.of(true), row.value)
        assertEquals(5L, row.updatedAtMillis, "updatedAtMillis must round trip")
        assertEquals("me", row.updatedBy, "updatedBy must round trip")
    }

    factory.use { repo ->
        // Zero values matter as much as any other: a flag that cannot be stored as explicitly
        // false is broken.
        val cases = mapOf(
            "b" to FlagValue.of(true),
            "bFalse" to FlagValue.of(false),
            "s" to FlagValue.of("hello"),
            "sEmpty" to FlagValue.of(""),
            "i" to FlagValue.of(42),
            "iZero" to FlagValue.of(0),
            "iMin" to FlagValue.of(Int.MIN_VALUE),
            "iMax" to FlagValue.of(Int.MAX_VALUE),
            "d" to FlagValue.of(2.5),
            "dZero" to FlagValue.of(0.0),
            "dNeg" to FlagValue.of(-1.25),
        )
        cases.forEach { (key, value) -> repo.put(SCHEMA, key, USER, value, 0L) }
        val stored = repo.overridesForSubject(SCHEMA, USER).associate { it.flagKey to it.value }
        cases.forEach { (key, value) -> assertEquals(value, stored[key], "round trip of $key") }
    }

    factory.use { repo ->
        repo.put(SCHEMA, "darkMode", USER, FlagValue.of(true), 1L)
        repo.put(SCHEMA, "darkMode", USER, FlagValue.of(false), 2L)
        val rows = repo.overridesForSubject(SCHEMA, USER)
        assertEquals(1, rows.size, "upsert must replace, not duplicate")
        assertEquals(FlagValue.of(false), rows.single().value)
        assertEquals(2L, rows.single().updatedAtMillis)
    }

    factory.use { repo ->
        // A retyped flag has to be able to overwrite its own stale row.
        repo.put(SCHEMA, "thing", USER, FlagValue.of(true), 0L)
        repo.put(SCHEMA, "thing", USER, FlagValue.of("now a string"), 1L)
        assertEquals(
            FlagValue.of("now a string"),
            repo.overridesForSubject(SCHEMA, USER).single().value,
            "an upsert must be able to change the value type",
        )
    }

    factory.use { repo ->
        val seen = (1..5).map { repo.put(SCHEMA, "darkMode", USER, FlagValue.of(it % 2 == 0), 0L) }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), seen, "the revision must increase on every write")
        assertEquals(5L, repo.revision(SCHEMA))
    }

    factory.use { repo ->
        repo.put(SCHEMA, "darkMode", USER, FlagValue.of(true), 0L)

        val (revision, removed) = repo.clear(SCHEMA, "notThere", USER)
        assertFalse(removed, "clearing an absent row reports nothing removed")
        assertEquals(1L, revision, "clearing an absent row must not bump the revision")

        val (after, reallyRemoved) = repo.clear(SCHEMA, "darkMode", USER)
        assertTrue(reallyRemoved)
        assertEquals(2L, after)
        assertTrue(repo.overridesForSubject(SCHEMA, USER).isEmpty())
    }

    factory.use { repo ->
        repo.put(SCHEMA, "variant", USER, FlagValue.of("old"), 0L)
        val result = repo.putSubject(
            SCHEMA,
            USER,
            sets = mapOf("darkMode" to FlagValue.of(true), "extra" to FlagValue.of(1)),
            clears = setOf("variant", "neverExisted"),
            updatedAtMillis = 7L,
        )
        assertEquals(2, result.written)
        assertEquals(1, result.cleared)
        assertEquals(2L, result.revision, "a batch write is one logical change, so one bump")
        assertEquals(
            setOf("darkMode", "extra"),
            repo.overridesForSubject(SCHEMA, USER).map { it.flagKey }.toSet(),
        )
    }

    factory.use { repo ->
        repo.put(SCHEMA, "darkMode", USER, FlagValue.of(true), 0L)
        assertEquals(
            1L,
            repo.putSubject(SCHEMA, USER, emptyMap(), setOf("nope"), 0L).revision,
            "a putSubject that changes nothing must not bump the revision",
        )
    }

    factory.use { repo ->
        repo.put("A", "darkMode", USER, FlagValue.of(true), 0L)
        repo.put("B", "darkMode", USER, FlagValue.of(false), 0L)
        assertEquals(FlagValue.of(true), repo.overridesForSubject("A", USER).single().value)
        assertEquals(FlagValue.of(false), repo.overridesForSubject("B", USER).single().value)
        assertEquals(1L, repo.revision("A"))
        assertEquals(1L, repo.revision("B"))
        assertEquals(0L, repo.revision("C"), "an untouched schema is at revision 0")
    }
}

/** What [FlagsRepository.overridesFor] and its narrower siblings return. */
public suspend fun assertReadContract(factory: RepositoryFactory) {
    factory.use { repo ->
        repo.put(SCHEMA, "newCheckout", FlagSubjectRef.Service, FlagValue.of(true), 0L)
        repo.put(SCHEMA, "darkMode", USER, FlagValue.of(true), 0L)
        repo.put(SCHEMA, "darkMode", FlagSubjectRef.user("someone-else"), FlagValue.of(false), 0L)
        repo.put(SCHEMA, "betaApi", FlagSubjectRef.context("tenant", "acme"), FlagValue.of(true), 0L)
        repo.put(SCHEMA, "betaApi", FlagSubjectRef.context("tenant", "globex"), FlagValue.of(false), 0L)

        assertEquals(
            setOf(
                "newCheckout" to FlagSubjectRef.Service,
                "darkMode" to USER,
                "betaApi" to FlagSubjectRef.context("tenant", "acme"),
            ),
            repo.overridesFor(SCHEMA, FlagSubject("u-42", mapOf("tenant" to "acme")))
                .map { it.flagKey to it.ref }.toSet(),
            "overridesFor must return service, this user's, and matching context rows only",
        )
    }

    factory.use { repo ->
        // The SQL stores fetch a cross product of dimensions and keys and filter in Kotlin, so a
        // mismatched (dimension, key) pair is exactly where a leak would appear.
        repo.put(SCHEMA, "a", FlagSubjectRef.context("tenant", "acme"), FlagValue.of(true), 0L)
        repo.put(SCHEMA, "b", FlagSubjectRef.context("region", "eu"), FlagValue.of(true), 0L)
        repo.put(SCHEMA, "c", FlagSubjectRef.context("region", "acme"), FlagValue.of(true), 0L)

        assertEquals(
            setOf("a", "b"),
            repo.overridesFor(SCHEMA, FlagSubject(null, mapOf("tenant" to "acme", "region" to "eu")))
                .map { it.flagKey }.toSet(),
            "a context row must match on both dimension and key",
        )
    }

    factory.use { repo ->
        repo.put(SCHEMA, "a", FlagSubjectRef.Service, FlagValue.of(true), 0L)
        repo.put(SCHEMA, "b", USER, FlagValue.of(true), 0L)
        assertEquals(
            listOf("a"),
            repo.overridesFor(SCHEMA, FlagSubject.Anonymous).map { it.flagKey },
            "an anonymous subject sees only service rows",
        )
    }

    factory.use { repo ->
        repo.put(SCHEMA, "a", FlagSubjectRef.Service, FlagValue.of(true), 0L)
        repo.put(SCHEMA, "b", USER, FlagValue.of(true), 0L)
        repo.put(SCHEMA, "c", FlagSubjectRef.context("tenant", "acme"), FlagValue.of(true), 0L)
        assertEquals(listOf("a"), repo.serviceOverrides(SCHEMA).map { it.flagKey })
        assertEquals(setOf("a", "b", "c"), repo.allOverrides(SCHEMA).map { it.flagKey }.toSet())
    }

    factory.use { repo ->
        repo.put(SCHEMA, "a", FlagSubjectRef.Service, FlagValue.of(true), 0L)
        repo.put("other", "b", FlagSubjectRef.Service, FlagValue.of(true), 0L)
        assertEquals(
            listOf("a"),
            repo.allOverrides(SCHEMA).map { it.flagKey },
            "allOverrides must not cross schemas",
        )
    }
}

/** Subject listing, override counts, and orphan handling. */
public suspend fun assertAdminContract(factory: RepositoryFactory) {
    factory.use { repo ->
        repo.put(SCHEMA, "darkMode", FlagSubjectRef.user("u-1"), FlagValue.of(true), 0L)
        repo.put(SCHEMA, "variant", FlagSubjectRef.user("u-1"), FlagValue.of("x"), 0L)
        repo.put(SCHEMA, "darkMode", FlagSubjectRef.user("u-2"), FlagValue.of(true), 0L)
        repo.put(SCHEMA, "darkMode", FlagSubjectRef.user("other"), FlagValue.of(true), 0L)
        repo.put(SCHEMA, "betaApi", FlagSubjectRef.context("tenant", "acme"), FlagValue.of(true), 0L)

        // u-1 has two rows but is one subject.
        assertEquals(
            listOf("u-1", "u-2"),
            repo.listSubjects(SCHEMA, FlagScope.USER, "", "u-", 0).map { it.key },
            "listSubjects must be distinct, ordered and prefix-filtered",
        )
        assertEquals(
            listOf("u-1"),
            repo.listSubjects(SCHEMA, FlagScope.USER, "", "u-", 1).map { it.key },
            "listSubjects must honour the limit",
        )
        assertEquals(
            listOf("acme"),
            repo.listSubjects(SCHEMA, FlagScope.CONTEXT, "tenant", "", 0).map { it.key },
        )
        assertTrue(
            repo.listSubjects(SCHEMA, FlagScope.CONTEXT, "region", "", 0).isEmpty(),
            "listSubjects must filter by dimension",
        )
    }

    factory.use { repo ->
        repo.put(SCHEMA, "darkMode", FlagSubjectRef.Service, FlagValue.of(true), 0L)
        repo.put(SCHEMA, "darkMode", FlagSubjectRef.user("u-1"), FlagValue.of(false), 0L)
        repo.put(SCHEMA, "darkMode", FlagSubjectRef.user("u-2"), FlagValue.of(false), 0L)
        repo.put(SCHEMA, "newCheckout", FlagSubjectRef.Service, FlagValue.of(true), 0L)
        assertEquals(
            mapOf("darkMode" to 2),
            repo.overrideCounts(SCHEMA),
            "overrideCounts must exclude service rows",
        )
    }

    factory.use { repo ->
        repo.put(SCHEMA, "darkMode", USER, FlagValue.of(true), 0L)
        repo.put(SCHEMA, "renamedAway", USER, FlagValue.of(true), 0L)
        val known = setOf("darkMode")

        assertEquals(listOf("renamedAway"), repo.orphans(SCHEMA, known).map { it.flagKey })

        val (revision, purged) = repo.purgeOrphans(SCHEMA, known)
        assertEquals(1, purged)
        assertEquals(3L, revision)
        assertTrue(repo.orphans(SCHEMA, known).isEmpty())
        assertEquals(listOf("darkMode"), repo.overridesForSubject(SCHEMA, USER).map { it.flagKey })
    }

    factory.use { repo ->
        // A SQL store must special-case this: `NOT IN ()` is a syntax error, not "match nothing".
        repo.put(SCHEMA, "darkMode", USER, FlagValue.of(true), 0L)
        repo.put(SCHEMA, "variant", USER, FlagValue.of("x"), 0L)
        assertEquals(
            2,
            repo.orphans(SCHEMA, emptySet()).size,
            "with no known keys, every row is an orphan",
        )
        assertEquals(2, repo.purgeOrphans(SCHEMA, emptySet()).second)
        assertTrue(repo.allOverrides(SCHEMA).isEmpty())
    }

    factory.use { repo ->
        repo.put(SCHEMA, "darkMode", USER, FlagValue.of(true), 0L)
        assertEquals(
            1L to 0,
            repo.purgeOrphans(SCHEMA, setOf("darkMode")),
            "purging nothing must not bump the revision",
        )
    }
}
