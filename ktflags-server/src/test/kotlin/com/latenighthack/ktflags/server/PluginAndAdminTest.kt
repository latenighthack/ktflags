package com.latenighthack.ktflags.server

import com.latenighthack.ktflags.FlagSubjectRef
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.InMemoryFlagsRepository
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val json = Json { ignoreUnknownKeys = true }

/** The Ktor plugin: what it mounts, what it hands back, and when it binds a port. */
class FeatureFlagsPluginTest {

    @Test
    fun install_returns_a_typed_service_and_publishes_it_as_an_attribute() = testApplication {
        application {
            val handle = installFeatureFlags(TestFlagsSchema, InMemoryFlagsRepository()) {
                adminPort = null
            }
            // The returned handle keeps T; the attribute cannot.
            val fromAttribute: FeatureFlagsService<*> = attributes[FeatureFlagsServiceKey]
            assertTrue(handle === fromAttribute)
            assertEquals("TestFlags", handle.schema.schemaName)
        }
        // testApplication is lazy; touch the client so the application actually starts.
        client.get("/nope")
    }

    @Test
    fun the_public_evaluate_route_is_mounted_by_default() = testApplication {
        application { installFeatureFlags(TestFlagsSchema, InMemoryFlagsRepository()) { adminPort = null } }

        // An empty POST body decodes to a default EvaluateRequest, which is a valid call.
        val response = client.put("/api/ktflags.v1.Flags/Evaluate")
        // Not 404: the route exists. (PUT is not POST, so the method itself is refused.)
        assertTrue(response.status != HttpStatusCode.NotFound, "the Evaluate route was not mounted")
    }

    @Test
    fun mountPublicRoutes_false_mounts_nothing_on_the_host() = testApplication {
        application {
            installFeatureFlags(TestFlagsSchema, InMemoryFlagsRepository()) {
                adminPort = null
                mountPublicRoutes = false
            }
        }
        assertEquals(HttpStatusCode.NotFound, client.get("/api/ktflags.v1.Flags/Evaluate").status)
    }

    /**
     * `mountAdminOnPublicPort` puts flag *mutation* on the port users reach, so it must not be
     * possible to enable by accident without also setting a token.
     */
    @Test
    fun mounting_admin_publicly_without_a_token_fails_loudly() = runTest {
        val error = runCatching {
            testApplication {
                application {
                    installFeatureFlags(TestFlagsSchema, InMemoryFlagsRepository()) {
                        adminPort = null
                        mountAdminOnPublicPort = true
                    }
                }
                client.get("/")
            }
        }.exceptionOrNull()
        assertTrue(error != null, "expected the install to refuse")
        assertContains(error!!.stackTraceToString(), "adminToken")
    }

    /**
     * The admin listener binds inside the install body rather than on ApplicationStarted, because
     * ktbuf's defaultServer installs modules from a coroutine that can run after that event has
     * already fired. This proves the port is live as soon as install returns.
     */
    @Test
    fun the_admin_listener_binds_eagerly_on_an_ephemeral_port() = testApplication {
        var port: Int? = null
        application {
            installFeatureFlags(TestFlagsSchema, InMemoryFlagsRepository()) {
                adminPort = 0
                adminHost = "127.0.0.1"
            }
            port = kotlinx.coroutines.runBlocking { featureFlagsAdminPort() }
        }
        client.get("/nope")

        assertTrue(port != null && port!! > 0, "the admin listener did not bind (port=$port)")
    }

    @Test
    fun no_admin_port_binds_nothing() = testApplication {
        var port: Int? = 1
        application {
            installFeatureFlags(TestFlagsSchema, InMemoryFlagsRepository()) { adminPort = null }
            port = kotlinx.coroutines.runBlocking { featureFlagsAdminPort() }
        }
        client.get("/nope")
        assertNull(port)
    }
}

/**
 * The admin JSON API.
 *
 * Mounted here onto the test application's own routing rather than a second listener -- exactly
 * the path a deployment that already runs its own admin server would take.
 */
class AdminJsonRoutesTest {

    private fun ApplicationTestBuilder.mount(
        service: FeatureFlagsService<TestFlags>,
    ) {
        application { routing { mountAdmin(service) } }
    }

    private suspend fun HttpResponse.json(): Map<String, Any?> =
        json.parseToJsonElement(bodyAsText()).let {
            @Suppress("UNCHECKED_CAST")
            Json.decodeFromString<Map<String, kotlinx.serialization.json.JsonElement>>(bodyAsText())
        }

    @Test
    fun listing_flags_reports_the_schema_and_every_definition() = testApplication {
        val service = testService()
        mount(service)
        service.setOverride("newCheckout", FlagSubjectRef.Service, FlagValue.of(true))

        val body = client.get("/flags/api/flags").bodyAsText()
        val dto = json.decodeFromString<FlagListDto>(body)

        assertEquals("TestFlags", dto.schemaName)
        assertEquals(TestFlagsSchema.definitions.size, dto.flags.size)
        val newCheckout = dto.flags.single { it.key == "newCheckout" }
        assertEquals("service", newCheckout.scope)
        assertEquals("bool", newCheckout.type)
        assertEquals(true, newCheckout.serviceValue?.bool)
        assertEquals(false, newCheckout.codeDefault.bool)
        assertEquals("the rewritten flow", newCheckout.description)
    }

    @Test
    fun setting_and_clearing_the_service_value_round_trips() = testApplication {
        val service = testService()
        mount(service)

        val set = client.put("/flags/api/flags/newCheckout/service") {
            contentType(ContentType.Application.Json)
            setBody("""{"value":{"type":"bool","bool":true},"updatedBy":"alice"}""")
        }
        assertEquals(HttpStatusCode.OK, set.status)
        assertEquals(
            true,
            service.evaluate(com.latenighthack.ktflags.FlagSubject(null)).newCheckout,
        )

        val cleared = client.delete("/flags/api/flags/newCheckout/service")
        assertEquals(HttpStatusCode.OK, cleared.status)
        assertEquals(true, json.decodeFromString<RevisionDto>(cleared.bodyAsText()).removed)
        assertEquals(
            false,
            service.evaluate(com.latenighthack.ktflags.FlagSubject(null)).newCheckout,
        )
    }

    @Test
    fun every_value_type_can_be_set_through_json() = testApplication {
        val service = testService()
        mount(service)

        val cases = listOf(
            "maxItems" to """{"type":"int","int":99}""",
            "samplingRate" to """{"type":"double","double":0.25}""",
            "newCheckout" to """{"type":"bool","bool":true}""",
        )
        cases.forEach { (key, value) ->
            val response = client.put("/flags/api/flags/$key/service") {
                contentType(ContentType.Application.Json)
                setBody("""{"value":$value}""")
            }
            assertEquals(HttpStatusCode.OK, response.status, "setting $key")
        }

        val flags = service.evaluate(com.latenighthack.ktflags.FlagSubject(null))
        assertEquals(99, flags.maxItems)
        assertEquals(0.25, flags.samplingRate)
        assertEquals(true, flags.newCheckout)
    }

    @Test
    fun the_subject_view_lists_every_flag_and_marks_applicability() = testApplication {
        val service = testService()
        mount(service)
        service.setOverride("darkMode", FlagSubjectRef.user("u-42"), FlagValue.of(true))

        val dto = json.decodeFromString<SubjectDto>(
            client.get("/flags/api/subject?scope=user&key=u-42").bodyAsText(),
        )

        assertEquals(TestFlagsSchema.definitions.size, dto.flags.size)
        val darkMode = dto.flags.single { it.key == "darkMode" }
        assertEquals("subject", darkMode.source)
        assertTrue(darkMode.overridden)
        assertTrue(darkMode.applicable)

        val newCheckout = dto.flags.single { it.key == "newCheckout" }
        assertEquals("code", newCheckout.source)
        assertTrue(!newCheckout.applicable, "a user cannot own a service-scoped flag")
    }

    @Test
    fun a_subject_batch_write_applies_sets_and_clears() = testApplication {
        val service = testService()
        mount(service)
        service.setOverride("variant", FlagSubjectRef.user("u-42"), FlagValue.of("old"))

        val response = client.put("/flags/api/subject") {
            contentType(ContentType.Application.Json)
            setBody(
                """
                {"subject":{"scope":"user","dimension":"","key":"u-42"},
                 "set":{"darkMode":{"type":"bool","bool":true}},
                 "clear":["variant"]}
                """.trimIndent(),
            )
        }
        assertEquals(HttpStatusCode.OK, response.status)

        val flags = service.evaluate(com.latenighthack.ktflags.FlagSubject("u-42"))
        assertEquals(true, flags.darkMode)
        assertEquals("control", flags.variant)
    }

    @Test
    fun subjects_can_be_browsed_by_prefix() = testApplication {
        val service = testService()
        mount(service)
        service.setOverride("darkMode", FlagSubjectRef.user("u-1"), FlagValue.of(true))
        service.setOverride("darkMode", FlagSubjectRef.user("u-2"), FlagValue.of(true))
        service.setOverride("darkMode", FlagSubjectRef.user("zz"), FlagValue.of(true))

        val dto = json.decodeFromString<SubjectListDto>(
            client.get("/flags/api/subjects?scope=user&prefix=u-").bodyAsText(),
        )
        assertEquals(listOf("u-1", "u-2"), dto.subjects.map { it.key })
    }

    @Test
    fun orphans_can_be_listed_and_purged() = testApplication {
        val repository = InMemoryFlagsRepository()
        repository.put("TestFlags", "goneAway", FlagSubjectRef.user("u-1"), FlagValue.of(true), 0L)
        val service = testService(repository)
        mount(service)

        val listed = json.decodeFromString<OverrideListDto>(
            client.get("/flags/api/orphans").bodyAsText(),
        )
        assertEquals(listOf("goneAway"), listed.overrides.map { it.flagKey })

        val purged = json.decodeFromString<PurgeDto>(
            client.delete("/flags/api/orphans").bodyAsText(),
        )
        assertEquals(1, purged.purged)
    }

    // The JSON layer and the protobuf admin service share one implementation, so they share its
    // errors; this checks the ktbuf status codes reach the browser as sensible HTTP.
    @Test
    fun errors_map_onto_http_status_codes() = testApplication {
        mount(testService())

        val unknown = client.put("/flags/api/flags/notAFlag/service") {
            contentType(ContentType.Application.Json)
            setBody("""{"value":{"type":"bool","bool":true}}""")
        }
        assertEquals(HttpStatusCode.NotFound, unknown.status)
        assertContains(unknown.bodyAsText(), "NOT_FOUND")

        val wrongType = client.put("/flags/api/flags/maxItems/service") {
            contentType(ContentType.Application.Json)
            setBody("""{"value":{"type":"bool","bool":true}}""")
        }
        assertEquals(HttpStatusCode.BadRequest, wrongType.status)

        val badScope = client.get("/flags/api/subject?scope=nonsense&key=x")
        assertEquals(HttpStatusCode.BadRequest, badScope.status)

        val userScopedServiceFlag = client.put("/flags/api/subject") {
            contentType(ContentType.Application.Json)
            setBody(
                """{"subject":{"scope":"user","dimension":"","key":"u-1"},
                    "set":{"newCheckout":{"type":"bool","bool":true}}}""",
            )
        }
        assertEquals(HttpStatusCode.BadRequest, userScopedServiceFlag.status)
    }

    @Test
    fun malformed_json_is_a_bad_request_not_a_server_error() = testApplication {
        mount(testService())
        val response = client.put("/flags/api/flags/newCheckout/service") {
            contentType(ContentType.Application.Json)
            setBody("{ this is not json")
        }
        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}

class AdminPanelTest {

    /** Catches a broken jar layout, which nothing else would notice until someone opened it. */
    @Test
    fun the_html_panel_is_on_the_classpath_and_served() = testApplication {
        application { routing { mountAdmin(testService()) } }

        val response = client.get("/flags/")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(ContentType.Text.Html, response.contentType()?.withoutParameters())
        val body = response.bodyAsText()
        assertContains(body, "<!doctype html>")
        assertContains(body, "ktflags")
        // The page builds its URLs relative to the panel path and drives itself off these
        // endpoints; renaming either side would silently break it with no compiler to notice.
        assertContains(body, "fetch('api/'")
        assertContains(body, "api('GET', 'flags')")
        assertContains(body, "subject?scope=")
    }

    @Test
    fun the_panel_can_be_disabled_while_the_json_api_stays_up() = testApplication {
        application { routing { mountAdmin(testService { adminPanelEnabled = false }) } }

        assertEquals(HttpStatusCode.NotFound, client.get("/flags/").status)
        assertEquals(HttpStatusCode.OK, client.get("/flags/api/flags").status)
    }
}

class AdminTokenTest {

    private fun ApplicationTestBuilder.mountWithToken() {
        application { routing { mountAdmin(testService { adminToken = "s3cret" }) } }
    }

    @Test
    fun a_request_with_no_token_is_forbidden() = testApplication {
        mountWithToken()
        assertEquals(HttpStatusCode.Forbidden, client.get("/flags/api/flags").status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/flags/").status)
    }

    @Test
    fun the_header_the_cookie_and_the_query_parameter_are_all_accepted() = testApplication {
        mountWithToken()

        assertEquals(
            HttpStatusCode.OK,
            client.get("/flags/api/flags") { header("x-admin-token", "s3cret") }.status,
        )
        assertEquals(
            HttpStatusCode.OK,
            client.get("/flags/api/flags") { header("Cookie", "ktflags_admin=s3cret") }.status,
        )
        // A browser cannot set a header when you navigate, hence the query parameter.
        assertEquals(HttpStatusCode.OK, client.get("/flags/?token=s3cret").status)
    }

    @Test
    fun a_wrong_token_is_forbidden() = testApplication {
        mountWithToken()
        assertEquals(
            HttpStatusCode.Forbidden,
            client.get("/flags/api/flags") { header("x-admin-token", "wrong") }.status,
        )
    }

    /** Navigating with `?token=` sets a cookie so the page's own fetch calls carry it. */
    @Test
    fun opening_the_panel_with_a_token_sets_the_cookie() = testApplication {
        mountWithToken()
        val response = client.get("/flags/?token=s3cret")
        val cookie = response.headers["Set-Cookie"]
        assertTrue(cookie != null && cookie.contains("ktflags_admin=s3cret"), "no cookie: $cookie")
        assertContains(cookie!!, "HttpOnly")
        assertContains(cookie, "SameSite=Strict")
    }

    /** A liveness probe that needs a secret is not a liveness probe. */
    @Test
    fun the_health_endpoint_is_exempt() = testApplication {
        mountWithToken()
        assertEquals(HttpStatusCode.OK, client.get("/flags/healthz").status)
    }

    @Test
    fun with_no_token_configured_everything_is_open() = testApplication {
        application { routing { mountAdmin(testService()) } }
        assertEquals(HttpStatusCode.OK, client.get("/flags/api/flags").status)
    }
}

private fun HttpResponse.contentType(): ContentType? =
    headers["Content-Type"]?.let { ContentType.parse(it) }
