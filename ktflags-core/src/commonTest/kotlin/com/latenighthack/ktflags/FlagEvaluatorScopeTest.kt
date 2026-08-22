package com.latenighthack.ktflags

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The scope-resolution table, one assertion per cell, asserting the value AND its provenance.
 *
 * Provenance matters as much as the value: "true because somebody rolled it out" and "true because
 * this one user was opted in" look identical in the value alone, and the admin panel, the client
 * and support all need to tell them apart.
 */
class FlagEvaluatorScopeTest {

    private val evaluator = FlagEvaluator(TestFlagsSchema)

    /**
     * The fixture from the design doc:
     *  - newCheckout rolled out service-wide
     *  - darkMode rolled out service-wide, but explicitly off for u-42
     *  - variant personalized for u-42
     *  - betaApi on for the acme tenant
     */
    private val overrides = listOf(
        row("newCheckout", FlagSubjectRef.Service, FlagValue.BoolValue(true)),
        row("darkMode", FlagSubjectRef.Service, FlagValue.BoolValue(true)),
        row("darkMode", FlagSubjectRef.user("u-42"), FlagValue.BoolValue(false)),
        row("variant", FlagSubjectRef.user("u-42"), FlagValue.StringValue("treatment")),
        row("betaApi", FlagSubjectRef.context("tenant", "acme"), FlagValue.BoolValue(true)),
    )

    private fun resolve(subject: FlagSubject): Map<String, ResolvedFlag> =
        evaluator.resolve(subject, overrides).associateBy { it.key }

    private fun assertFlag(
        resolved: Map<String, ResolvedFlag>,
        key: String,
        value: FlagValue,
        source: ValueSource,
    ) {
        val actual = resolved.getValue(key)
        assertEquals(value, actual.value, "value of $key")
        assertEquals(source, actual.source, "source of $key")
    }

    @Test
    fun a_user_with_overrides_beats_the_service_rollout() {
        val r = resolve(FlagSubject("u-42", mapOf("tenant" to "acme")))
        assertFlag(r, "newCheckout", FlagValue.BoolValue(true), ValueSource.SERVICE_DEFAULT)
        assertFlag(r, "darkMode", FlagValue.BoolValue(false), ValueSource.SUBJECT_OVERRIDE)
        assertFlag(r, "betaApi", FlagValue.BoolValue(true), ValueSource.SUBJECT_OVERRIDE)
        assertFlag(r, "variant", FlagValue.StringValue("treatment"), ValueSource.SUBJECT_OVERRIDE)
        assertFlag(r, "maxItems", FlagValue.IntValue(10), ValueSource.CODE_DEFAULT)
    }

    @Test
    fun a_user_without_overrides_gets_the_service_rollout() {
        val r = resolve(FlagSubject("u-7", mapOf("tenant" to "acme")))
        assertFlag(r, "newCheckout", FlagValue.BoolValue(true), ValueSource.SERVICE_DEFAULT)
        assertFlag(r, "darkMode", FlagValue.BoolValue(true), ValueSource.SERVICE_DEFAULT)
        assertFlag(r, "betaApi", FlagValue.BoolValue(true), ValueSource.SUBJECT_OVERRIDE)
        assertFlag(r, "variant", FlagValue.StringValue("control"), ValueSource.CODE_DEFAULT)
    }

    // A logged-out client must still boot with sane flags, so a user-scoped flag with no user id
    // resolves to the service value rather than erroring.
    @Test
    fun an_anonymous_subject_skips_the_user_layer_without_erroring() {
        val r = resolve(FlagSubject(null, mapOf("tenant" to "acme")))
        assertFlag(r, "darkMode", FlagValue.BoolValue(true), ValueSource.SERVICE_DEFAULT)
        assertFlag(r, "variant", FlagValue.StringValue("control"), ValueSource.CODE_DEFAULT)
        assertFlag(r, "betaApi", FlagValue.BoolValue(true), ValueSource.SUBJECT_OVERRIDE)
    }

    @Test
    fun a_different_context_key_falls_through_to_the_code_default() {
        val r = resolve(FlagSubject("u-42", mapOf("tenant" to "globex")))
        assertFlag(r, "betaApi", FlagValue.BoolValue(false), ValueSource.CODE_DEFAULT)
        assertFlag(r, "darkMode", FlagValue.BoolValue(false), ValueSource.SUBJECT_OVERRIDE)
    }

    @Test
    fun a_missing_context_dimension_falls_through_to_the_code_default() {
        val r = resolve(FlagSubject("u-42"))
        assertFlag(r, "betaApi", FlagValue.BoolValue(false), ValueSource.CODE_DEFAULT)
        assertFlag(r, "variant", FlagValue.StringValue("treatment"), ValueSource.SUBJECT_OVERRIDE)
    }

    // proto3 has no `optional`, so a client that always sends the field and leaves it blank must
    // behave exactly like one that omits it.
    @Test
    fun empty_strings_normalize_to_absent() {
        val r = resolve(FlagSubject("", mapOf("tenant" to "")))
        assertFlag(r, "darkMode", FlagValue.BoolValue(true), ValueSource.SERVICE_DEFAULT)
        assertFlag(r, "betaApi", FlagValue.BoolValue(false), ValueSource.CODE_DEFAULT)
        assertFlag(r, "variant", FlagValue.StringValue("control"), ValueSource.CODE_DEFAULT)
    }

    // A client can send a fixed context bag as the schema evolves; unknown axes are inert.
    @Test
    fun context_dimensions_no_flag_declares_are_ignored() {
        val r = resolve(FlagSubject("u-42", mapOf("region" to "eu")))
        assertFlag(r, "betaApi", FlagValue.BoolValue(false), ValueSource.CODE_DEFAULT)
        assertFlag(r, "darkMode", FlagValue.BoolValue(false), ValueSource.SUBJECT_OVERRIDE)
    }

    @Test
    fun resolving_with_no_overrides_at_all_yields_exactly_the_defaults() {
        val resolved = evaluator.resolve(FlagSubject("u-42"), emptyList())
        assertEquals(
            TestFlagsSchema.defaults,
            TestFlagsSchema.materialize(resolved.toFlagValues()),
        )
        assertEquals(
            List(TestFlagsSchema.definitions.size) { ValueSource.CODE_DEFAULT },
            resolved.map { it.source },
        )
    }

    @Test
    fun onlyKeys_restricts_the_result_and_ignores_unknown_keys() {
        val resolved = evaluator.resolve(
            FlagSubject("u-42"),
            overrides,
            onlyKeys = setOf("darkMode", "notAFlag"),
        )
        assertEquals(listOf("darkMode"), resolved.map { it.key })
    }

    @Test
    fun resolution_materializes_into_the_typed_flag_class() {
        val flags = TestFlagsSchema.materialize(
            evaluator.resolve(FlagSubject("u-42", mapOf("tenant" to "acme")), overrides)
                .toFlagValues(),
        )
        assertEquals(
            TestFlags(
                newCheckout = true,
                darkMode = false,
                betaApi = true,
                variant = "treatment",
                maxItems = 10,
            ),
            flags,
        )
    }
}
