package com.latenighthack.ktflags.server

import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktbuf.rpc.HttpRpcClient
import com.latenighthack.ktbuf.server.serveAll
import com.latenighthack.ktbuf.test.server.runTestWithServer
import com.latenighthack.ktflags.FlagSubjectRef
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.InMemoryFlagsRepository
import com.latenighthack.ktflags.client.FeatureFlagsConfig
import com.latenighthack.ktflags.client.FeatureFlagsProvider
import com.latenighthack.ktflags.client.FlagsTransport
import com.latenighthack.ktflags.client.InMemoryFlagsCache
import com.latenighthack.ktflags.client.RefreshResult
import com.latenighthack.ktflags.proto.toProto
import com.latenighthack.ktflags.proto.v1.LocalFlagsServiceRpc
import io.ktor.server.routing.routing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The flagship test: a real client against a real server with no network at all.
 *
 * ktbuf generates `LocalFlagsServiceRpc`, which satisfies the *client* service interface by
 * calling the *server* interface directly. Wrapping it in a [FlagsTransport] gives the genuine
 * `FeatureFlagsProvider` -- its cache, its coalescing, its conditional fetch, its materialization
 * -- driven by the genuine server resolution chain, with no port to bind and no socket to flake.
 *
 * This is the shape a consumer should copy for their own integration tests, and it is what
 * `inProcessFlags` packages up.
 */
class InProcessEndToEndTest {

    private fun harness(): Pair<FeatureFlagsService<TestFlags>, FeatureFlagsProvider<TestFlags>> {
        val service = testService(InMemoryFlagsRepository())
        val provider = FeatureFlagsProvider(
            TestFlagsSchema,
            FeatureFlagsConfig {
                transport = FlagsTransport.of(LocalFlagsServiceRpc(service.flagsServer))
                persistence = InMemoryFlagsCache()
                userIdProvider = { "u-42" }
                contextProvider = { mapOf("tenant" to "acme") }
            },
        )
        return service to provider
    }

    @Test
    fun a_service_rollout_reaches_the_client_as_a_typed_flag() = runTest {
        val (service, provider) = harness()

        assertEquals(TestFlags(), provider.current())

        service.setOverride("newCheckout", FlagSubjectRef.Service, FlagValue.of(true))
        assertIs<RefreshResult.Updated>(provider.refresh())

        assertEquals(true, provider.current().newCheckout)
    }

    @Test
    fun a_user_override_beats_the_service_rollout_over_the_full_stack() = runTest {
        val (service, provider) = harness()
        service.setOverride("darkMode", FlagSubjectRef.Service, FlagValue.of(true))
        service.setOverride("darkMode", FlagSubjectRef.user("u-42"), FlagValue.of(false))
        provider.refresh()

        assertEquals(false, provider.current().darkMode)
    }

    @Test
    fun a_context_override_reaches_the_client() = runTest {
        val (service, provider) = harness()
        service.setOverride("betaApi", FlagSubjectRef.context("tenant", "acme"), FlagValue.of(true))
        provider.refresh()

        assertEquals(true, provider.current().betaApi)
    }

    @Test
    fun every_value_type_survives_the_full_round_trip() = runTest {
        val (service, provider) = harness()
        service.setOverride("newCheckout", FlagSubjectRef.Service, FlagValue.of(true))
        service.setOverride("variant", FlagSubjectRef.user("u-42"), FlagValue.of("treatment"))
        service.setOverride("maxItems", FlagSubjectRef.Service, FlagValue.of(99))
        service.setOverride("samplingRate", FlagSubjectRef.Service, FlagValue.of(0.125))
        provider.refresh()

        assertEquals(
            TestFlags(
                newCheckout = true,
                variant = "treatment",
                maxItems = 99,
                samplingRate = 0.125,
            ),
            provider.current(),
        )
    }

    /**
     * The whole reason there is no streaming RPC: an unchanged refresh is a tiny round trip that
     * transfers no values at all.
     */
    @Test
    fun an_unchanged_refresh_reports_not_modified() = runTest {
        val (service, provider) = harness()
        service.setOverride("newCheckout", FlagSubjectRef.Service, FlagValue.of(true))

        val first = provider.refresh()
        assertIs<RefreshResult.Updated>(first)

        val second = provider.refresh()
        val notModified = assertIs<RefreshResult.NotModified>(second)
        assertEquals((first as RefreshResult.Updated).revision, notModified.revision)
        assertEquals(true, provider.current().newCheckout, "values survive a not-modified reply")
    }

    @Test
    fun a_write_after_a_not_modified_is_picked_up_again() = runTest {
        val (service, provider) = harness()
        provider.refresh()
        assertIs<RefreshResult.NotModified>(provider.refresh())

        service.setOverride("newCheckout", FlagSubjectRef.Service, FlagValue.of(true))
        assertIs<RefreshResult.Updated>(provider.refresh())
        assertEquals(true, provider.current().newCheckout)
    }

    @Test
    fun clearing_an_override_reverts_the_client_to_the_code_default() = runTest {
        val (service, provider) = harness()
        service.setOverride("maxItems", FlagSubjectRef.Service, FlagValue.of(99))
        provider.refresh()
        assertEquals(99, provider.current().maxItems)

        service.clearOverride("maxItems", FlagSubjectRef.Service)
        provider.refresh()
        assertEquals(10, provider.current().maxItems)
    }

    @Test
    fun switching_user_re_resolves_against_the_new_identity() = runTest {
        val service = testService(InMemoryFlagsRepository())
        val provider = FeatureFlagsProvider(
            TestFlagsSchema,
            FeatureFlagsConfig {
                transport = FlagsTransport.of(LocalFlagsServiceRpc(service.flagsServer))
                persistence = InMemoryFlagsCache()
                userIdProvider = { "u-1" }
            },
        )
        service.setOverride("variant", FlagSubjectRef.user("u-1"), FlagValue.of("alpha"))
        service.setOverride("variant", FlagSubjectRef.user("u-2"), FlagValue.of("beta"))

        provider.refresh()
        assertEquals("alpha", provider.current().variant)

        provider.setSubject("u-2")
        assertEquals("beta", provider.current().variant)
    }
}

/**
 * The same journey over a real socket, real HTTP and real protobuf.
 *
 * GOTCHA baked in below: `runTest` drives virtual time, so real network IO inside it never
 * completes unless it is pushed onto a real dispatcher. Every call here goes through [realTime].
 * ktbuf's own transport tests carry the same workaround.
 */
class WireEndToEndTest {

    private suspend fun <T> realTime(block: suspend () -> T): T =
        withContext(Dispatchers.Default) { withTimeout(15_000) { block() } }

    @Test
    fun the_client_resolves_flags_over_http() {
        val service = testService(InMemoryFlagsRepository())

        runTestWithServer({ routing { mountPublic(service) } }) { server, _ ->
            realTime {
                service.setOverride("newCheckout", FlagSubjectRef.Service, FlagValue.of(true))
                service.setOverride("variant", FlagSubjectRef.user("u-42"), FlagValue.of("treatment"))

                val provider = FeatureFlagsProvider(
                    TestFlagsSchema,
                    FeatureFlagsConfig {
                        // Schemeless host:port is ktbuf's contract for plain HTTP.
                        serverAddress = server.serverUrl
                        persistence = InMemoryFlagsCache()
                        userIdProvider = { "u-42" }
                    },
                )

                assertIs<RefreshResult.Updated>(provider.refresh())
                assertEquals(true, provider.current().newCheckout)
                assertEquals("treatment", provider.current().variant)

                // The conditional fetch works across the wire too.
                assertIs<RefreshResult.NotModified>(provider.refresh())
            }
        }
    }

    /**
     * A large schema pushes the response past ~8KB, which is the exact size ktbuf-server's unary
     * routing mishandles on Ktor 3.3.x. If someone bumps the Ktor pin, this hangs or truncates
     * first and the diagnosis is obvious.
     */
    @Test
    fun a_response_larger_than_eight_kilobytes_round_trips_intact() {
        val schema = LargeSchema(count = 400)
        val service = FeatureFlagsService(
            schema,
            InMemoryFlagsRepository(),
            FeatureFlagsConfig().apply { adminPort = null },
        )

        runTestWithServer({ routing { mountPublic(service) } }) { server, _ ->
            realTime {
                // Long string values so the encoded response is comfortably over the threshold.
                repeat(400) { i ->
                    service.setOverride(
                        "flag$i",
                        FlagSubjectRef.Service,
                        FlagValue.of("v".repeat(40) + i),
                    )
                }

                val provider = FeatureFlagsProvider(
                    schema,
                    FeatureFlagsConfig {
                        serverAddress = server.serverUrl
                        persistence = InMemoryFlagsCache()
                    },
                )
                assertIs<RefreshResult.Updated>(provider.refresh())

                val values = provider.current()
                assertTrue(
                    values.contentBytes >= 8_192,
                    "the payload carried only ${values.contentBytes} bytes, too small to be a canary",
                )
                assertEquals(400, values.map.size)
                assertEquals("v".repeat(40) + "0", values.map["flag0"])
                assertEquals("v".repeat(40) + "399", values.map["flag399"])
            }
        }
    }

    @Test
    fun a_server_error_arrives_as_a_typed_failure_rather_than_an_exception() {
        val service = testService(InMemoryFlagsRepository())

        runTestWithServer({ routing { mountPublic(service) } }) { server, _ ->
            realTime {
                val provider = FeatureFlagsProvider(
                    // A schema the server does not serve: the handler answers NOT_FOUND.
                    OtherSchema,
                    FeatureFlagsConfig {
                        serverAddress = server.serverUrl
                        persistence = InMemoryFlagsCache()
                        retryAttempts = 0
                    },
                )

                val failed = assertIs<RefreshResult.Failed>(provider.refresh())
                assertEquals(Codes.NOT_FOUND, failed.error.code)
                assertTrue(!failed.error.retriable, "NOT_FOUND must not be retried")
            }
        }
    }

    @Test
    fun the_admin_protobuf_service_is_reachable_over_http() {
        val service = testService(InMemoryFlagsRepository())

        runTestWithServer({ routing { mountAdmin(service) } }) { server, _ ->
            realTime {
                val admin = com.latenighthack.ktflags.proto.v1.FlagsAdminServiceRpc(
                    HttpRpcClient(server.serverUrl),
                )

                admin.setOverride(
                    com.latenighthack.ktflags.proto.v1.SetOverrideRequest {
                        schemaName = "TestFlags"
                        flagKey = "newCheckout"
                        subject = FlagSubjectRef.Service.toProto()
                        value = FlagValue.of(true).toProto()
                    },
                )

                val listed = admin.listFlags(
                    com.latenighthack.ktflags.proto.v1.ListFlagsRequest { schemaName = "TestFlags" },
                )
                val newCheckout = listed.flags.single { it.definition?.key == "newCheckout" }
                assertEquals(true, newCheckout.serviceValue?.value?.getBoolValue())
            }
        }
    }
}
