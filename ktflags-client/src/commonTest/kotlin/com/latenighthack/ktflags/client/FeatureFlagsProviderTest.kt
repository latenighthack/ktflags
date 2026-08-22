package com.latenighthack.ktflags.client

import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktflags.FlagValue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class FeatureFlagsProviderTest {

    @Test
    fun values_are_the_code_defaults_before_anything_loads() = runTest {
        val provider = FeatureFlagsProvider(
            TestFlagsSchema,
            testConfig(ScriptedTransport.returning()),
        )
        assertEquals(TestFlags(), provider.current())
        assertEquals(FlagsState.Defaults, provider.state.value)
    }

    @Test
    fun a_successful_refresh_materializes_the_typed_flags() = runTest {
        val transport = ScriptedTransport.returning(
            revision = 7L,
            "newCheckout" to FlagValue.of(true),
            "variant" to FlagValue.of("treatment"),
            "maxItems" to FlagValue.of(99),
        )
        val provider = FeatureFlagsProvider(TestFlagsSchema, testConfig(transport))

        val result = provider.refresh()

        assertEquals(RefreshResult.Updated(7L), result)
        assertEquals(
            TestFlags(newCheckout = true, variant = "treatment", maxItems = 99),
            provider.current(),
        )
        assertEquals(FlagsState.Fresh(7L, 1_000L), provider.state.value)
    }

    @Test
    fun flags_the_server_omits_keep_their_code_defaults() = runTest {
        val transport = ScriptedTransport.returning(1L, "newCheckout" to FlagValue.of(true))
        val provider = FeatureFlagsProvider(TestFlagsSchema, testConfig(transport))

        provider.refresh()

        assertEquals(TestFlags(newCheckout = true), provider.current())
        assertEquals("control", provider.current().variant)
    }

    @Test
    fun not_modified_leaves_the_values_untouched() = runTest {
        val transport = ScriptedTransport.returning(3L, "newCheckout" to FlagValue.of(true))
        val provider = FeatureFlagsProvider(TestFlagsSchema, testConfig(transport))
        provider.refresh()

        transport.respondWith { notModifiedResponse(3L) }
        val result = provider.refresh()

        assertEquals(RefreshResult.NotModified(3L), result)
        assertEquals(true, provider.current().newCheckout)
    }

    // The whole point of the conditional fetch: a second refresh tells the server what it already
    // has, so an unchanged answer costs no payload.
    @Test
    fun the_second_refresh_sends_the_known_revision() = runTest {
        val transport = ScriptedTransport.returning(5L, "newCheckout" to FlagValue.of(true))
        val provider = FeatureFlagsProvider(TestFlagsSchema, testConfig(transport))

        provider.refresh()
        provider.refresh()

        assertEquals(0L, transport.requests[0].knownRevision)
        assertEquals(5L, transport.requests[1].knownRevision)
    }

    // A flag client that throws forces every call site into a try/catch.
    @Test
    fun a_failed_refresh_reports_rather_than_throws_and_keeps_the_old_values() = runTest {
        val transport = ScriptedTransport.returning(1L, "newCheckout" to FlagValue.of(true))
        val provider = FeatureFlagsProvider(TestFlagsSchema, testConfig(transport))
        provider.refresh()

        transport.respondWith { throw com.latenighthack.ktbuf.net.RpcResponseException(
            "/api/x", "POST", Codes.UNAVAILABLE, "server is down",
        ) }
        val result = provider.refresh()

        val failed = assertIs<RefreshResult.Failed>(result)
        assertEquals(Codes.UNAVAILABLE, failed.error.code)
        assertTrue(failed.error.retriable)
        // Values survive: a stale flag beats a wrong one.
        assertEquals(true, provider.current().newCheckout)
        val state = assertIs<FlagsState.Stale>(provider.state.value)
        assertEquals(1L, state.revision)
    }

    @Test
    fun a_cold_start_with_no_network_stays_on_defaults() = runTest {
        val provider = FeatureFlagsProvider(
            TestFlagsSchema,
            testConfig(ScriptedTransport.failing(Codes.UNAVAILABLE)),
        )
        assertIs<RefreshResult.Failed>(provider.start())
        assertEquals(TestFlags(), provider.current())
    }

    @Test
    fun a_non_retriable_failure_is_not_retried() = runTest {
        val transport = ScriptedTransport.failing(Codes.NOT_FOUND)
        val provider = FeatureFlagsProvider(
            TestFlagsSchema,
            testConfig(transport, retryAttempts = 3),
        )
        provider.refresh()
        assertEquals(1, transport.callCount)
    }

    @Test
    fun a_retriable_failure_is_retried_up_to_the_configured_limit() = runTest {
        val transport = ScriptedTransport.failing(Codes.UNAVAILABLE)
        val provider = FeatureFlagsProvider(
            TestFlagsSchema,
            testConfig(transport, retryAttempts = 2),
        )
        provider.refresh()
        // The initial attempt plus two retries.
        assertEquals(3, transport.callCount)
    }

    @Test
    fun a_timeout_becomes_a_deadline_exceeded_failure_rather_than_propagating() = runTest {
        val transport = ScriptedTransport { awaitCancellation() }
        val provider = FeatureFlagsProvider(
            TestFlagsSchema,
            testConfig(transport, timeoutMillis = 50),
        )

        val failed = assertIs<RefreshResult.Failed>(provider.refresh())
        assertEquals(Codes.DEADLINE_EXCEEDED, failed.error.code)
        assertTrue(failed.error.retriable)
    }

    // A UI can trivially fire three refreshes on resume; they must not become three round trips.
    @Test
    fun concurrent_refreshes_coalesce_into_one_round_trip() = runTest {
        val gate = CompletableDeferred<Unit>()
        val transport = ScriptedTransport {
            gate.await()
            evaluateResponse(1L, "newCheckout" to FlagValue.of(true))
        }
        val provider = FeatureFlagsProvider(TestFlagsSchema, testConfig(transport))

        val dispatcher = UnconfinedTestDispatcher(testScheduler)
        val a = async(dispatcher) { provider.refresh() }
        val b = async(dispatcher) { provider.refresh() }
        val c = async(dispatcher) { provider.refresh() }

        gate.complete(Unit)
        val results = listOf(a.await(), b.await(), c.await())

        assertEquals(1, transport.callCount, "three callers issued more than one request")
        assertEquals(listOf(RefreshResult.Updated(1L)), results.distinct())
    }

    @Test
    fun a_refresh_after_one_completes_issues_a_new_request() = runTest {
        val transport = ScriptedTransport.returning(1L)
        val provider = FeatureFlagsProvider(TestFlagsSchema, testConfig(transport))
        provider.refresh()
        provider.refresh()
        assertEquals(2, transport.callCount)
    }

    @Test
    fun the_subject_is_sent_on_the_wire() = runTest {
        val transport = ScriptedTransport.returning(1L)
        val provider = FeatureFlagsProvider(
            TestFlagsSchema,
            testConfig(transport, userId = "u-42", context = mapOf("tenant" to "acme")),
        )
        provider.refresh()

        val request = transport.requests.single()
        assertEquals("TestFlags", request.schemaName)
        assertEquals("u-42", request.userId)
        assertEquals(listOf("tenant" to "acme"), request.context.map { it.dimension to it.key })
    }

    @Test
    fun setSubject_resets_the_revision_and_refetches() = runTest {
        val transport = ScriptedTransport.returning(4L, "darkMode" to FlagValue.of(true))
        val provider = FeatureFlagsProvider(
            TestFlagsSchema,
            testConfig(transport, userId = "u-1"),
        )
        provider.refresh()
        assertEquals(true, provider.current().darkMode)

        transport.respondWith { evaluateResponse(9L) }
        provider.setSubject("u-2")

        // A revision fetched for one subject means nothing for another.
        assertEquals(0L, transport.requests.last().knownRevision)
        assertEquals("u-2", transport.requests.last().userId)
        assertEquals(false, provider.current().darkMode)
    }

    @Test
    fun refreshOnStart_can_be_disabled() = runTest {
        val transport = ScriptedTransport.returning(1L)
        val provider = FeatureFlagsProvider(
            TestFlagsSchema,
            testConfig(transport, refreshOnStart = false),
        )
        provider.start()
        assertEquals(0, transport.callCount)
    }

    @Test
    fun watch_delivers_the_current_value_and_then_updates() = runTest {
        val transport = ScriptedTransport.returning(1L, "newCheckout" to FlagValue.of(true))
        val provider = FeatureFlagsProvider(TestFlagsSchema, testConfig(transport))

        val seen = mutableListOf<TestFlags>()
        val subscription = provider.watch(
            CoroutineScopeOf(UnconfinedTestDispatcher(testScheduler)),
        ) { seen.add(it) }

        provider.refresh()
        subscription.cancel()

        assertEquals(TestFlags(), seen.first())
        assertEquals(TestFlags(newCheckout = true), seen.last())
    }
}

/** A tiny scope helper so the watch test does not need a full TestScope plumbing dance. */
@Suppress("FunctionName")
private fun CoroutineScopeOf(
    context: kotlin.coroutines.CoroutineContext,
): kotlinx.coroutines.CoroutineScope = kotlinx.coroutines.CoroutineScope(context)
