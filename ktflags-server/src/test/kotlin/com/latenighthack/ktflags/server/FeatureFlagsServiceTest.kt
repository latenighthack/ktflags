package com.latenighthack.ktflags.server

import com.latenighthack.ktbuf.net.RpcResponseException
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktflags.FlagScope
import com.latenighthack.ktflags.FlagSubject
import com.latenighthack.ktflags.FlagSubjectRef
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.InMemoryFlagsRepository
import com.latenighthack.ktflags.ValueSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServerFeatureFlagsTest {

    @Test
    fun an_empty_store_evaluates_to_the_code_defaults() = runTest {
        assertEquals(TestFlags(), testService().evaluate(FlagSubject("u-1")))
    }

    @Test
    fun the_resolution_chain_runs_end_to_end_against_the_repository() = runTest {
        val service = testService()
        service.setOverride("newCheckout", FlagSubjectRef.Service, FlagValue.of(true))
        service.setOverride("darkMode", FlagSubjectRef.Service, FlagValue.of(true))
        service.setOverride("darkMode", FlagSubjectRef.user("u-42"), FlagValue.of(false))
        service.setOverride("betaApi", FlagSubjectRef.context("tenant", "acme"), FlagValue.of(true))

        val flags = service.evaluate(FlagSubject("u-42", mapOf("tenant" to "acme")))

        assertEquals(true, flags.newCheckout, "service rollout")
        assertEquals(false, flags.darkMode, "the user's own row beats the rollout")
        assertEquals(true, flags.betaApi, "the tenant's row applies")
        assertEquals("control", flags.variant, "untouched flags keep their code default")
    }

    @Test
    fun evaluateDetailed_reports_provenance_per_key() = runTest {
        val service = testService()
        service.setOverride("newCheckout", FlagSubjectRef.Service, FlagValue.of(true))
        service.setOverride("darkMode", FlagSubjectRef.user("u-42"), FlagValue.of(true))

        val detail = service.evaluateDetailed(FlagSubject("u-42"))

        assertEquals(ValueSource.SERVICE_DEFAULT, detail.sources["newCheckout"])
        assertEquals(ValueSource.SUBJECT_OVERRIDE, detail.sources["darkMode"])
        assertEquals(ValueSource.CODE_DEFAULT, detail.sources["variant"])
        assertEquals(2L, detail.revision)
    }

    @Test
    fun serviceFlags_ignores_every_subject_row() = runTest {
        val service = testService()
        service.setOverride("darkMode", FlagSubjectRef.Service, FlagValue.of(true))
        service.setOverride("darkMode", FlagSubjectRef.user("u-42"), FlagValue.of(false))

        assertEquals(true, service.serviceFlags().darkMode)
    }

    // The service-row cache is keyed on the revision, so a write has to invalidate it. Getting
    // this wrong would serve a stale rollout indefinitely.
    @Test
    fun the_service_cache_is_invalidated_by_a_write() = runTest {
        val service = testService { serviceCacheEnabled = true }
        assertEquals(false, service.serviceFlags().newCheckout)

        service.setOverride("newCheckout", FlagSubjectRef.Service, FlagValue.of(true))
        assertEquals(true, service.serviceFlags().newCheckout, "the cache did not see the write")

        service.clearOverride("newCheckout", FlagSubjectRef.Service)
        assertEquals(false, service.serviceFlags().newCheckout, "the cache did not see the clear")
    }

    @Test
    fun a_type_mismatched_row_is_skipped_rather_than_crashing() = runTest {
        // Written straight to the repository, bypassing the validation that would reject it --
        // this is the shape a row left behind by a retyped flag has.
        val repository = InMemoryFlagsRepository()
        repository.put("TestFlags", "maxItems", FlagSubjectRef.Service, FlagValue.of("not an int"), 0L)
        val service = testService(repository)

        assertEquals(10, service.evaluate(FlagSubject("u-1")).maxItems)
        assertEquals(
            ValueSource.CODE_DEFAULT,
            service.evaluateDetailed(FlagSubject("u-1")).sources["maxItems"],
        )
    }
}

class ServerFlagsAdminTest {

    @Test
    fun listFlags_reports_the_service_value_and_override_count() = runTest {
        val service = testService()
        service.setOverride("newCheckout", FlagSubjectRef.Service, FlagValue.of(true), "alice")
        service.setOverride("darkMode", FlagSubjectRef.user("u-1"), FlagValue.of(true))
        service.setOverride("darkMode", FlagSubjectRef.user("u-2"), FlagValue.of(true))

        val view = service.listFlags()
        val byKey = view.flags.associateBy { it.definition.key }

        assertEquals("TestFlags", view.schemaName)
        assertEquals(FlagValue.of(true), byKey.getValue("newCheckout").serviceValue)
        assertEquals("alice", byKey.getValue("newCheckout").serviceUpdatedBy)
        assertEquals(2, byKey.getValue("darkMode").overrideCount)
        // No service row means "the code default is in effect", which is not the same thing as a
        // stored value that happens to equal it.
        assertNull(byKey.getValue("darkMode").serviceValue)
        assertEquals(0, byKey.getValue("variant").overrideCount)
    }

    @Test
    fun the_subject_view_shows_every_flag_with_its_provenance() = runTest {
        val service = testService()
        service.setOverride("newCheckout", FlagSubjectRef.Service, FlagValue.of(true))
        service.setOverride("darkMode", FlagSubjectRef.user("u-42"), FlagValue.of(true))

        val view = service.subject(FlagSubjectRef.user("u-42"))
        val byKey = view.flags.associateBy { it.definition.key }

        assertEquals(TestFlagsSchema.definitions.size, view.flags.size, "every flag is listed")

        val darkMode = byKey.getValue("darkMode")
        assertEquals(FlagValue.of(true), darkMode.effective)
        assertEquals(ValueSource.SUBJECT_OVERRIDE, darkMode.source)
        assertTrue(darkMode.overridden)
        assertTrue(darkMode.applicable)

        // A service-scoped flag is still shown to a user subject -- an operator needs to know what
        // the user actually sees -- but it is read-only for them.
        val newCheckout = byKey.getValue("newCheckout")
        assertEquals(FlagValue.of(true), newCheckout.effective)
        assertEquals(ValueSource.SERVICE_DEFAULT, newCheckout.source)
        assertFalse(newCheckout.overridden)
        assertFalse(newCheckout.applicable, "a user cannot own a service-scoped flag")
    }

    @Test
    fun setSubject_writes_and_clears_atomically() = runTest {
        val service = testService()
        val ref = FlagSubjectRef.user("u-42")
        service.setOverride("variant", ref, FlagValue.of("old"))

        val result = service.setSubject(
            ref,
            sets = mapOf("darkMode" to FlagValue.of(true)),
            clears = setOf("variant"),
        )

        assertEquals(1, result.written)
        assertEquals(1, result.cleared)
        assertEquals(true, service.evaluate(FlagSubject("u-42")).darkMode)
        assertEquals("control", service.evaluate(FlagSubject("u-42")).variant)
    }

    @Test
    fun listSubjects_finds_subjects_that_have_rows() = runTest {
        val service = testService()
        service.setOverride("darkMode", FlagSubjectRef.user("u-1"), FlagValue.of(true))
        service.setOverride("darkMode", FlagSubjectRef.user("u-2"), FlagValue.of(true))
        service.setOverride("betaApi", FlagSubjectRef.context("tenant", "acme"), FlagValue.of(true))

        assertEquals(listOf("u-1", "u-2"), service.listSubjects(FlagScope.USER, "", "u", 0).map { it.key })
        assertEquals(
            listOf("acme"),
            service.listSubjects(FlagScope.CONTEXT, "tenant", "", 0).map { it.key },
        )
    }

    @Test
    fun orphans_are_surfaced_and_purgeable() = runTest {
        val repository = InMemoryFlagsRepository()
        repository.put("TestFlags", "deletedFlag", FlagSubjectRef.user("u-1"), FlagValue.of(true), 0L)
        val service = testService(repository)
        service.setOverride("darkMode", FlagSubjectRef.user("u-1"), FlagValue.of(true))

        assertEquals(listOf("deletedFlag"), service.orphans().map { it.flagKey })
        assertEquals(1, service.listFlags().orphanCount)

        assertEquals(1, service.purgeOrphans().second)
        assertTrue(service.orphans().isEmpty())
        assertEquals(true, service.evaluate(FlagSubject("u-1")).darkMode, "real rows survive")
    }
}

/**
 * Write validation.
 *
 * Every rejection here exists so the store can never hold a row the evaluator would silently
 * ignore. A flag that looks set in the database but has no effect is the worst outcome available.
 */
class AdminWriteValidationTest {

    private suspend fun expectCode(code: Codes, block: suspend () -> Unit) {
        val e = assertFailsWith<RpcResponseException> { block() }
        assertEquals(code, e.code, "wrong status for: ${e.errorMessage}")
    }

    @Test
    fun an_unknown_flag_is_not_found() = runTest {
        expectCode(Codes.NOT_FOUND) {
            testService().setOverride("nope", FlagSubjectRef.Service, FlagValue.of(true))
        }
    }

    @Test
    fun a_value_of_the_wrong_type_is_rejected() = runTest {
        expectCode(Codes.INVALID_ARGUMENT) {
            testService().setOverride("maxItems", FlagSubjectRef.Service, FlagValue.of(true))
        }
    }

    @Test
    fun a_user_row_on_a_service_scoped_flag_is_rejected() = runTest {
        expectCode(Codes.INVALID_ARGUMENT) {
            testService().setOverride("newCheckout", FlagSubjectRef.user("u-1"), FlagValue.of(true))
        }
    }

    @Test
    fun a_context_row_in_the_wrong_dimension_is_rejected() = runTest {
        expectCode(Codes.INVALID_ARGUMENT) {
            testService()
                .setOverride("betaApi", FlagSubjectRef.context("region", "eu"), FlagValue.of(true))
        }
    }

    @Test
    fun a_user_row_on_a_context_scoped_flag_is_rejected() = runTest {
        expectCode(Codes.INVALID_ARGUMENT) {
            testService().setOverride("betaApi", FlagSubjectRef.user("u-1"), FlagValue.of(true))
        }
    }

    // Any flag can carry a service-wide value; that is the rollout knob.
    @Test
    fun a_service_row_is_allowed_for_every_scope() = runTest {
        val service = testService()
        service.setOverride("newCheckout", FlagSubjectRef.Service, FlagValue.of(true))
        service.setOverride("darkMode", FlagSubjectRef.Service, FlagValue.of(true))
        service.setOverride("betaApi", FlagSubjectRef.Service, FlagValue.of(true))
        assertEquals(3, service.listFlags().flags.count { it.serviceValue != null })
    }

    @Test
    fun a_batch_write_rejects_the_whole_batch_when_one_entry_is_invalid() = runTest {
        val service = testService()
        expectCode(Codes.INVALID_ARGUMENT) {
            service.setSubject(
                FlagSubjectRef.user("u-1"),
                sets = mapOf(
                    "darkMode" to FlagValue.of(true),
                    // Service-scoped, so a user cannot own it.
                    "newCheckout" to FlagValue.of(true),
                ),
                clears = emptySet(),
            )
        }
        // Nothing was written, so the valid half did not sneak through either.
        assertEquals(false, service.evaluate(FlagSubject("u-1")).darkMode)
    }

    @Test
    fun clearing_an_unknown_flag_in_a_batch_is_rejected() = runTest {
        expectCode(Codes.NOT_FOUND) {
            testService().setSubject(FlagSubjectRef.user("u-1"), emptyMap(), setOf("nope"))
        }
    }
}
