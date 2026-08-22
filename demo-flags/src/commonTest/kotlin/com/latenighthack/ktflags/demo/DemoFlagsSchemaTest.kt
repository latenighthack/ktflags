package com.latenighthack.ktflags.demo

import com.latenighthack.ktflags.FlagScope
import com.latenighthack.ktflags.FlagSchemaIndex
import com.latenighthack.ktflags.FlagType
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.FlagValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Asserts what ktflags-ksp actually generated.
 *
 * This is the golden test for the processor: it runs against the real generated
 * `DemoFlagsSchema`, so a regression in the generator fails here rather than at some consumer's
 * build months later.
 */
class DemoFlagsSchemaTest {

    @Test
    fun the_schema_name_defaults_to_the_class_name() {
        assertEquals("DemoFlags", DemoFlagsSchema.schemaName)
    }

    @Test
    fun defaults_match_a_default_constructed_instance() {
        assertEquals(DemoFlags(), DemoFlagsSchema.defaults)
    }

    // The core invariant of materialize: with nothing resolved, you get exactly the code defaults.
    @Test
    fun materializing_nothing_yields_the_defaults() {
        assertEquals(DemoFlagsSchema.defaults, DemoFlagsSchema.materialize(FlagValues.Empty))
    }

    @Test
    fun every_declared_default_matches_the_data_class() {
        val byKey = DemoFlagsSchema.definitions.associateBy { it.key }
        assertEquals(FlagValue.BoolValue(false), byKey.getValue("newCheckout").defaultValue)
        assertEquals(FlagValue.BoolValue(false), byKey.getValue("darkMode").defaultValue)
        assertEquals(FlagValue.StringValue("control"), byKey.getValue("variant").defaultValue)
        assertEquals(FlagValue.IntValue(10), byKey.getValue("maxItems").defaultValue)
        assertEquals(FlagValue.DoubleValue(0.05), byKey.getValue("samplingRate").defaultValue)
    }

    @Test
    fun scopes_and_types_survive_codegen() {
        val byKey = DemoFlagsSchema.definitions.associateBy { it.key }
        assertEquals(FlagScope.SERVICE, byKey.getValue("newCheckout").scope)
        assertEquals(FlagScope.USER, byKey.getValue("darkMode").scope)
        assertEquals(FlagScope.CONTEXT, byKey.getValue("betaApi").scope)
        assertEquals("tenant", byKey.getValue("betaApi").dimension)

        assertEquals(FlagType.BOOLEAN, byKey.getValue("newCheckout").type)
        assertEquals(FlagType.STRING, byKey.getValue("variant").type)
        assertEquals(FlagType.INT, byKey.getValue("maxItems").type)
        assertEquals(FlagType.DOUBLE, byKey.getValue("samplingRate").type)
    }

    // @FlagKey decouples the wire key from the property name, which is what lets a property be
    // renamed without orphaning every stored override for it.
    @Test
    fun flagKey_overrides_the_wire_key_without_touching_the_property() {
        assertTrue(DemoFlagsSchema.definitions.any { it.key == "legacy_banner" })
        assertNull(DemoFlagsSchema.definitions.firstOrNull { it.key == "endOfLifeBanner" })

        // ...and it still reads and writes the renamed property.
        val flags = DemoFlagsSchema.materialize(
            FlagValues.of("legacy_banner" to FlagValue.of(true)),
        )
        assertEquals(true, flags.endOfLifeBanner)
    }

    @Test
    fun descriptions_are_carried_through() {
        assertEquals(
            "Route checkout through the rewritten flow.",
            DemoFlagsSchema.definitions.first { it.key == "newCheckout" }.description,
        )
    }

    @Test
    fun every_flag_round_trips_through_materialize() {
        val overridden = FlagValues.of(
            "newCheckout" to FlagValue.of(true),
            "darkMode" to FlagValue.of(true),
            "betaApi" to FlagValue.of(true),
            "variant" to FlagValue.of("treatment"),
            "maxItems" to FlagValue.of(99),
            "samplingRate" to FlagValue.of(1.0),
            "legacy_banner" to FlagValue.of(true),
        )
        assertEquals(
            DemoFlags(
                newCheckout = true,
                darkMode = true,
                betaApi = true,
                variant = "treatment",
                maxItems = 99,
                samplingRate = 1.0,
                endOfLifeBanner = true,
            ),
            DemoFlagsSchema.materialize(overridden),
        )
    }

    @Test
    fun the_schema_indexes_without_duplicate_keys() {
        val index = FlagSchemaIndex(DemoFlagsSchema)
        assertEquals(DemoFlagsSchema.definitions.size, index.keys.size)
        assertEquals(setOf("tenant"), index.dimensions)
    }

    // Every definition must actually feed the property it claims to. A generator bug that wired
    // two definitions to the same field would pass every check above but fail here.
    @Test
    fun each_definition_drives_exactly_one_distinct_field() {
        val distinct = DemoFlagsSchema.definitions.map { definition ->
            val flipped = when (val default = definition.defaultValue) {
                is FlagValue.BoolValue -> FlagValue.of(!default.value)
                is FlagValue.StringValue -> FlagValue.of(default.value + "-changed")
                is FlagValue.IntValue -> FlagValue.of(default.value + 1)
                is FlagValue.DoubleValue -> FlagValue.of(default.value + 1.0)
            }
            DemoFlagsSchema.materialize(FlagValues.of(definition.key to flipped))
        }
        assertEquals(
            DemoFlagsSchema.definitions.size,
            distinct.toSet().size,
            "two definitions produced the same instance, so they are wired to the same field",
        )
        distinct.forEach { assertTrue(it != DemoFlagsSchema.defaults) }
    }
}
