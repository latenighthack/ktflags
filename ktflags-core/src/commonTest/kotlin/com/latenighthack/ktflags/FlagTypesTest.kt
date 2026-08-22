package com.latenighthack.ktflags

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FlagValueTest {

    @Test
    fun each_variant_reports_its_own_type() {
        assertEquals(FlagType.BOOLEAN, FlagValue.of(true).type)
        assertEquals(FlagType.STRING, FlagValue.of("x").type)
        assertEquals(FlagType.INT, FlagValue.of(1).type)
        assertEquals(FlagType.DOUBLE, FlagValue.of(1.0).type)
    }

    // The accessors must not coerce: an Int flag read as a Boolean is a bug to surface, not to
    // paper over with a truthiness rule.
    @Test
    fun typed_accessors_return_null_across_variants() {
        val int = FlagValue.of(1)
        assertEquals(1, int.asInt)
        assertNull(int.asBoolean)
        assertNull(int.asString)
        assertNull(int.asDouble)
    }

    @Test
    fun zero_values_are_ordinary_values() {
        assertEquals(false, FlagValue.of(false).asBoolean)
        assertEquals(0, FlagValue.of(0).asInt)
        assertEquals("", FlagValue.of("").asString)
        assertEquals(0.0, FlagValue.of(0.0).asDouble)
    }
}

class FlagValuesTest {

    private val values = FlagValues.of(
        "b" to FlagValue.of(true),
        "s" to FlagValue.of("hello"),
        "i" to FlagValue.of(7),
        "d" to FlagValue.of(2.5),
    )

    @Test
    fun typed_accessors_read_present_values() {
        assertEquals(true, values.boolean("b", false))
        assertEquals("hello", values.string("s", "x"))
        assertEquals(7, values.int("i", 0))
        assertEquals(2.5, values.double("d", 0.0))
    }

    @Test
    fun a_missing_key_falls_back_to_the_default() {
        assertEquals(true, values.boolean("nope", true))
        assertEquals("fallback", values.string("nope", "fallback"))
        assertEquals(42, values.int("nope", 42))
        assertEquals(9.5, values.double("nope", 9.5))
    }

    // The never-throw contract. A stale row from a retyped flag must degrade that one flag, not
    // crash the app on the next launch.
    @Test
    fun a_type_mismatch_falls_back_to_the_default_rather_than_throwing() {
        assertEquals(true, values.boolean("i", true))
        assertEquals("fallback", values.string("b", "fallback"))
        assertEquals(42, values.int("s", 42))
        assertEquals(9.5, values.double("i", 9.5))
    }

    @Test
    fun plus_is_right_biased() {
        val merged = values + FlagValues.of("i" to FlagValue.of(99))
        assertEquals(99, merged.int("i", 0))
        assertEquals(true, merged.boolean("b", false))
    }

    @Test
    fun empty_yields_every_default() {
        assertEquals(TestFlagsSchema.defaults, TestFlagsSchema.materialize(FlagValues.Empty))
    }

    @Test
    fun equality_is_by_content() {
        assertEquals(FlagValues.of("a" to FlagValue.of(1)), FlagValues.of("a" to FlagValue.of(1)))
        assertEquals(
            FlagValues.of("a" to FlagValue.of(1)).hashCode(),
            FlagValues.of("a" to FlagValue.of(1)).hashCode(),
        )
    }
}

class FlagDefinitionTest {

    @Test
    fun a_default_whose_type_disagrees_with_the_declared_type_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            FlagDefinition("k", FlagScope.SERVICE, FlagType.BOOLEAN, FlagValue.of(1))
        }
    }

    @Test
    fun a_blank_key_is_rejected() {
        assertFailsWith<IllegalArgumentException> {
            FlagDefinition("  ", FlagScope.SERVICE, FlagType.BOOLEAN, FlagValue.of(true))
        }
    }

    @Test
    fun a_context_scoped_flag_needs_a_dimension() {
        assertFailsWith<IllegalArgumentException> {
            FlagDefinition("k", FlagScope.CONTEXT, FlagType.BOOLEAN, FlagValue.of(true))
        }
    }

    @Test
    fun a_non_context_flag_must_not_declare_a_dimension() {
        assertFailsWith<IllegalArgumentException> {
            FlagDefinition(
                "k", FlagScope.USER, FlagType.BOOLEAN, FlagValue.of(true), dimension = "tenant",
            )
        }
    }
}

class FlagSubjectTest {

    @Test
    fun the_fingerprint_does_not_depend_on_context_ordering() {
        val a = FlagSubject("u-1", mapOf("tenant" to "acme", "region" to "eu"))
        val b = FlagSubject("u-1", mapOf("region" to "eu", "tenant" to "acme"))
        assertEquals(a.fingerprint, b.fingerprint)
    }

    @Test
    fun empty_entries_normalize_away() {
        assertEquals(
            FlagSubject(null).fingerprint,
            FlagSubject("", mapOf("tenant" to "", "" to "x")).fingerprint,
        )
    }

    // The fingerprint gates whether a cached snapshot is reused, so a collision would serve one
    // user another's flags.
    @Test
    fun different_subjects_do_not_collide() {
        val seen = listOf(
            FlagSubject(null),
            FlagSubject("u-1"),
            FlagSubject("u-2"),
            FlagSubject("u-1", mapOf("tenant" to "acme")),
            FlagSubject("u-1", mapOf("tenant" to "globex")),
            FlagSubject("u-1", mapOf("region" to "acme")),
            FlagSubject(null, mapOf("tenant" to "acme")),
        ).map { it.fingerprint }
        assertEquals(seen.size, seen.toSet().size, "fingerprints collided: $seen")
    }

    // A user id containing the separator must not be able to forge another subject's fingerprint.
    @Test
    fun a_user_id_cannot_forge_a_context_entry() {
        val forged = FlagSubject("u-1\u001Ftenant\u001Eacme\u001F")
        val real = FlagSubject("u-1", mapOf("tenant" to "acme"))
        assertTrue(forged.fingerprint != real.fingerprint)
    }
}

class FlagSchemaIndexTest {

    private val index = FlagSchemaIndex(TestFlagsSchema)

    @Test
    fun lookup_and_dimensions_are_derived_from_the_schema() {
        assertEquals(FlagScope.USER, index["darkMode"]?.scope)
        assertNull(index["notAFlag"])
        assertEquals(setOf("tenant"), index.dimensions)
        assertEquals(TestFlagsSchema.definitions.map { it.key }.toSet(), index.keys)
    }

    @Test
    fun duplicate_keys_are_rejected_at_construction() {
        val duplicated = object : FlagSchema<TestFlags> {
            override val schemaName: String = "Dup"
            override val defaults: TestFlags = TestFlags()
            override val definitions: List<FlagDefinition> = listOf(
                FlagDefinition("same", FlagScope.SERVICE, FlagType.BOOLEAN, FlagValue.of(false)),
                FlagDefinition("same", FlagScope.SERVICE, FlagType.BOOLEAN, FlagValue.of(true)),
            )
            override fun materialize(values: FlagValues): TestFlags = defaults
        }
        assertFailsWith<IllegalArgumentException> { FlagSchemaIndex(duplicated) }
    }

    // canOwn is what stops the store accumulating rows the schema forbids, so check every
    // combination of subject scope against flag scope.
    @Test
    fun canOwn_covers_every_scope_combination() {
        // Any flag can carry a service-wide value -- that is the rollout knob.
        assertTrue(index.canOwn(FlagSubjectRef.Service, "newCheckout"))
        assertTrue(index.canOwn(FlagSubjectRef.Service, "darkMode"))
        assertTrue(index.canOwn(FlagSubjectRef.Service, "betaApi"))

        // Only a user-scoped flag can carry a user row.
        assertTrue(index.canOwn(FlagSubjectRef.user("u-1"), "darkMode"))
        assertFalse(index.canOwn(FlagSubjectRef.user("u-1"), "newCheckout"))
        assertFalse(index.canOwn(FlagSubjectRef.user("u-1"), "betaApi"))

        // Only a context-scoped flag, and only in its own dimension.
        assertTrue(index.canOwn(FlagSubjectRef.context("tenant", "acme"), "betaApi"))
        assertFalse(index.canOwn(FlagSubjectRef.context("region", "eu"), "betaApi"))
        assertFalse(index.canOwn(FlagSubjectRef.context("tenant", "acme"), "darkMode"))
        assertFalse(index.canOwn(FlagSubjectRef.context("tenant", "acme"), "newCheckout"))
    }

    @Test
    fun an_unknown_key_can_never_be_owned() {
        assertFalse(index.canOwn(FlagSubjectRef.Service, "notAFlag"))
        assertFalse(index.canOwn(FlagSubjectRef.user("u-1"), "notAFlag"))
    }
}
