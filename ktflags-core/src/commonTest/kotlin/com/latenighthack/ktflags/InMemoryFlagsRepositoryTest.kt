package com.latenighthack.ktflags

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Behaviour of the reference repository.
 *
 * These assertions are lifted into the abstract `FlagsRepositoryContract` in `ktflags-test` once
 * that module exists, so the two SQL stores are held to exactly the same spec. Until then they
 * live here, next to the implementation they define.
 */
class InMemoryFlagsRepositoryTest {

    private val schema = TestFlagsSchema.schemaName
    private val user = FlagSubjectRef.user("u-42")

    @Test
    fun an_empty_repository_is_at_revision_zero() = runTest {
        assertEquals(0L, InMemoryFlagsRepository().revision(schema))
    }

    @Test
    fun a_written_row_reads_back() = runTest {
        val repo = InMemoryFlagsRepository()
        repo.put(schema, "darkMode", user, FlagValue.of(true), updatedAtMillis = 5L, updatedBy = "me")

        val rows = repo.overridesForSubject(schema, user)
        assertEquals(1, rows.size)
        assertEquals(FlagValue.of(true), rows.single().value)
        assertEquals(5L, rows.single().updatedAtMillis)
        assertEquals("me", rows.single().updatedBy)
    }

    @Test
    fun upsert_replaces_rather_than_duplicating() = runTest {
        val repo = InMemoryFlagsRepository()
        repo.put(schema, "darkMode", user, FlagValue.of(true), 1L)
        repo.put(schema, "darkMode", user, FlagValue.of(false), 2L)

        val rows = repo.overridesForSubject(schema, user)
        assertEquals(1, rows.size)
        assertEquals(FlagValue.of(false), rows.single().value)
    }

    @Test
    fun the_revision_increases_strictly_on_every_write() = runTest {
        val repo = InMemoryFlagsRepository()
        val seen = (1..5).map { repo.put(schema, "darkMode", user, FlagValue.of(it % 2 == 0), 0L) }
        assertEquals(listOf(1L, 2L, 3L, 4L, 5L), seen)
        assertEquals(5L, repo.revision(schema))
    }

    // Nothing changed, so no client's cached snapshot needs invalidating.
    @Test
    fun clearing_an_absent_row_does_not_bump_the_revision() = runTest {
        val repo = InMemoryFlagsRepository()
        repo.put(schema, "darkMode", user, FlagValue.of(true), 0L)

        val (revision, removed) = repo.clear(schema, "notThere", user)
        assertFalse(removed)
        assertEquals(1L, revision)

        val (afterReal, reallyRemoved) = repo.clear(schema, "darkMode", user)
        assertTrue(reallyRemoved)
        assertEquals(2L, afterReal)
    }

    @Test
    fun schemas_are_isolated_from_one_another() = runTest {
        val repo = InMemoryFlagsRepository()
        repo.put("A", "darkMode", user, FlagValue.of(true), 0L)
        repo.put("B", "darkMode", user, FlagValue.of(false), 0L)

        assertEquals(FlagValue.of(true), repo.overridesForSubject("A", user).single().value)
        assertEquals(FlagValue.of(false), repo.overridesForSubject("B", user).single().value)
        assertEquals(1L, repo.revision("A"))
        assertEquals(1L, repo.revision("B"))
        assertEquals(0L, repo.revision("C"))
    }

    @Test
    fun overridesFor_returns_service_user_and_matching_context_rows_only() = runTest {
        val repo = InMemoryFlagsRepository()
        repo.put(schema, "newCheckout", FlagSubjectRef.Service, FlagValue.of(true), 0L)
        repo.put(schema, "darkMode", user, FlagValue.of(true), 0L)
        repo.put(schema, "darkMode", FlagSubjectRef.user("someone-else"), FlagValue.of(false), 0L)
        repo.put(schema, "betaApi", FlagSubjectRef.context("tenant", "acme"), FlagValue.of(true), 0L)
        repo.put(schema, "betaApi", FlagSubjectRef.context("tenant", "globex"), FlagValue.of(false), 0L)

        val rows = repo.overridesFor(schema, FlagSubject("u-42", mapOf("tenant" to "acme")))
        assertEquals(
            setOf(
                "newCheckout" to FlagSubjectRef.Service,
                "darkMode" to user,
                "betaApi" to FlagSubjectRef.context("tenant", "acme"),
            ),
            rows.map { it.flagKey to it.ref }.toSet(),
        )
    }

    @Test
    fun putSubject_writes_and_clears_in_one_revision() = runTest {
        val repo = InMemoryFlagsRepository()
        repo.put(schema, "variant", user, FlagValue.of("old"), 0L)

        val result = repo.putSubject(
            schema, user,
            sets = mapOf("darkMode" to FlagValue.of(true)),
            clears = setOf("variant", "neverExisted"),
            updatedAtMillis = 7L,
        )

        assertEquals(1, result.written)
        assertEquals(1, result.cleared)
        // One logical change -> one bump, not one per row.
        assertEquals(2L, result.revision)
        assertEquals(listOf("darkMode"), repo.overridesForSubject(schema, user).map { it.flagKey })
    }

    @Test
    fun a_no_op_putSubject_does_not_bump_the_revision() = runTest {
        val repo = InMemoryFlagsRepository()
        repo.put(schema, "darkMode", user, FlagValue.of(true), 0L)
        val result = repo.putSubject(schema, user, emptyMap(), setOf("nope"), 0L)
        assertEquals(1L, result.revision)
    }

    @Test
    fun listSubjects_filters_by_scope_dimension_and_prefix() = runTest {
        val repo = InMemoryFlagsRepository()
        repo.put(schema, "darkMode", FlagSubjectRef.user("u-1"), FlagValue.of(true), 0L)
        repo.put(schema, "variant", FlagSubjectRef.user("u-1"), FlagValue.of("x"), 0L)
        repo.put(schema, "darkMode", FlagSubjectRef.user("u-2"), FlagValue.of(true), 0L)
        repo.put(schema, "darkMode", FlagSubjectRef.user("other"), FlagValue.of(true), 0L)
        repo.put(schema, "betaApi", FlagSubjectRef.context("tenant", "acme"), FlagValue.of(true), 0L)

        // Distinct, ordered, prefix-filtered -- u-1 has two rows but is one subject.
        assertEquals(
            listOf("u-1", "u-2"),
            repo.listSubjects(schema, FlagScope.USER, "", "u-", limit = 0).map { it.key },
        )
        assertEquals(
            listOf("u-1"),
            repo.listSubjects(schema, FlagScope.USER, "", "u-", limit = 1).map { it.key },
        )
        assertEquals(
            listOf("acme"),
            repo.listSubjects(schema, FlagScope.CONTEXT, "tenant", "", limit = 0).map { it.key },
        )
        assertTrue(repo.listSubjects(schema, FlagScope.CONTEXT, "region", "", 0).isEmpty())
    }

    @Test
    fun overrideCounts_excludes_service_rows() = runTest {
        val repo = InMemoryFlagsRepository()
        repo.put(schema, "darkMode", FlagSubjectRef.Service, FlagValue.of(true), 0L)
        repo.put(schema, "darkMode", FlagSubjectRef.user("u-1"), FlagValue.of(false), 0L)
        repo.put(schema, "darkMode", FlagSubjectRef.user("u-2"), FlagValue.of(false), 0L)
        repo.put(schema, "newCheckout", FlagSubjectRef.Service, FlagValue.of(true), 0L)

        assertEquals(mapOf("darkMode" to 2), repo.overrideCounts(schema))
    }

    @Test
    fun orphans_are_rows_for_keys_the_schema_no_longer_declares() = runTest {
        val repo = InMemoryFlagsRepository()
        repo.put(schema, "darkMode", user, FlagValue.of(true), 0L)
        repo.put(schema, "renamedAway", user, FlagValue.of(true), 0L)
        val known = TestFlagsSchema.definitions.map { it.key }.toSet()

        assertEquals(listOf("renamedAway"), repo.orphans(schema, known).map { it.flagKey })

        val (revision, purged) = repo.purgeOrphans(schema, known)
        assertEquals(1, purged)
        assertEquals(3L, revision)
        assertTrue(repo.orphans(schema, known).isEmpty())
        assertEquals(listOf("darkMode"), repo.overridesForSubject(schema, user).map { it.flagKey })
    }

    // An empty known-key set means "the schema declares nothing", so every row is an orphan. The
    // SQL stores must short-circuit this rather than emitting `NOT IN ()`, which is a syntax error.
    @Test
    fun an_empty_known_key_set_makes_every_row_an_orphan() = runTest {
        val repo = InMemoryFlagsRepository()
        repo.put(schema, "darkMode", user, FlagValue.of(true), 0L)
        repo.put(schema, "variant", user, FlagValue.of("x"), 0L)

        assertEquals(2, repo.orphans(schema, emptySet()).size)
        assertEquals(2, repo.purgeOrphans(schema, emptySet()).second)
    }

    @Test
    fun purging_nothing_does_not_bump_the_revision() = runTest {
        val repo = InMemoryFlagsRepository()
        repo.put(schema, "darkMode", user, FlagValue.of(true), 0L)
        val known = TestFlagsSchema.definitions.map { it.key }.toSet()
        assertEquals(1L to 0, repo.purgeOrphans(schema, known))
    }

    @Test
    fun the_evaluator_reads_straight_from_the_repository() = runTest {
        val repo = InMemoryFlagsRepository()
        repo.put(schema, "darkMode", FlagSubjectRef.Service, FlagValue.of(true), 0L)
        repo.put(schema, "darkMode", user, FlagValue.of(false), 0L)

        val subject = FlagSubject("u-42")
        val resolved = FlagEvaluator(TestFlagsSchema)
            .resolve(subject, repo.overridesFor(schema, subject))

        assertEquals(false, TestFlagsSchema.materialize(resolved.toFlagValues()).darkMode)
        assertEquals(ValueSource.SUBJECT_OVERRIDE, resolved.sources()["darkMode"])
    }
}
