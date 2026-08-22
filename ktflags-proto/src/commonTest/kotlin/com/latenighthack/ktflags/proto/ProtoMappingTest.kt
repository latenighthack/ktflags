package com.latenighthack.ktflags.proto

import com.latenighthack.ktflags.FlagDefinition
import com.latenighthack.ktflags.FlagOverrideRow
import com.latenighthack.ktflags.FlagScope
import com.latenighthack.ktflags.FlagSubject
import com.latenighthack.ktflags.FlagSubjectRef
import com.latenighthack.ktflags.FlagType
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.ResolvedFlag
import com.latenighthack.ktflags.ValueSource
import com.latenighthack.ktflags.proto.v1.ContextEntry
import com.latenighthack.ktflags.proto.v1.EvaluateRequest
import com.latenighthack.ktflags.proto.v1.FlagAssignment
import com.latenighthack.ktflags.proto.v1.FlagOverride
import com.latenighthack.ktflags.proto.v1.SubjectRef
import com.latenighthack.ktflags.proto.v1.fromByteArray
import com.latenighthack.ktflags.proto.v1.toByteArray
import com.latenighthack.ktflags.proto.v1.FlagScope as ProtoFlagScope
import com.latenighthack.ktflags.proto.v1.FlagType as ProtoFlagType
import com.latenighthack.ktflags.proto.v1.ValueSource as ProtoValueSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ProtoEnumMappingTest {

    @Test
    fun every_scope_round_trips() {
        FlagScope.entries.forEach { assertEquals(it, it.toProto().toDomainOrNull(), "scope $it") }
    }

    @Test
    fun every_type_round_trips() {
        FlagType.entries.forEach { assertEquals(it, it.toProto().toDomainOrNull(), "type $it") }
    }

    @Test
    fun every_value_source_round_trips() {
        ValueSource.entries.forEach { assertEquals(it, it.toProto().toDomainOrNull(), "source $it") }
    }

    @Test
    fun unspecified_maps_to_null() {
        assertNull(ProtoFlagScope.fromInt(0).toDomainOrNull())
        assertNull(ProtoFlagType.fromInt(0).toDomainOrNull())
        assertNull(ProtoValueSource.fromInt(0).toDomainOrNull())
    }

    // A newer peer can send a value this build has never heard of. It must degrade to null, not
    // throw and not silently become the first enum constant.
    @Test
    fun an_unknown_wire_value_maps_to_null() {
        assertNull(ProtoFlagScope.fromInt(99).toDomainOrNull())
        assertNull(ProtoFlagType.fromInt(99).toDomainOrNull())
        assertNull(ProtoValueSource.fromInt(99).toDomainOrNull())
    }
}

class SubjectRefMappingTest {

    @Test
    fun every_well_formed_ref_round_trips() {
        listOf(
            FlagSubjectRef.Service,
            FlagSubjectRef.user("u-42"),
            FlagSubjectRef.context("tenant", "acme"),
        ).forEach { assertEquals(it, it.toProto().toDomainOrNull(), "ref $it") }
    }

    // A ref whose shape contradicts its scope means a malformed or hostile peer. Rejecting it here
    // stops the store ever accumulating a row that addresses nothing coherent.
    @Test
    fun a_ref_whose_shape_contradicts_its_scope_is_rejected() {
        val serviceWithKey = SubjectRef {
            scope = ProtoFlagScope.FLAG_SCOPE_SERVICE
            key = "u-42"
        }
        val userWithoutKey = SubjectRef { scope = ProtoFlagScope.FLAG_SCOPE_USER }
        val userWithDimension = SubjectRef {
            scope = ProtoFlagScope.FLAG_SCOPE_USER
            dimension = "tenant"
            key = "u-42"
        }
        val contextWithoutDimension = SubjectRef {
            scope = ProtoFlagScope.FLAG_SCOPE_CONTEXT
            key = "acme"
        }

        assertNull(serviceWithKey.toDomainOrNull())
        assertNull(userWithoutKey.toDomainOrNull())
        assertNull(userWithDimension.toDomainOrNull())
        assertNull(contextWithoutDimension.toDomainOrNull())
    }

    @Test
    fun an_unknown_scope_is_rejected() {
        assertNull(SubjectRef { scope = ProtoFlagScope.fromInt(99) }.toDomainOrNull())
    }
}

class SubjectMappingTest {

    @Test
    fun a_subject_round_trips_through_context_entries() {
        val subject = FlagSubject("u-42", mapOf("tenant" to "acme", "region" to "eu"))
        assertEquals(subject, subjectOf("u-42", subject.toContextEntries()))
    }

    @Test
    fun an_empty_user_id_normalizes_to_absent() {
        assertEquals(FlagSubject.Anonymous, subjectOf("", emptyList()))
    }

    @Test
    fun blank_context_entries_are_dropped() {
        val entries = listOf(
            ContextEntry { dimension = "tenant"; key = "acme" },
            ContextEntry { dimension = ""; key = "orphan" },
            ContextEntry { dimension = "region"; key = "" },
        )
        assertEquals(mapOf("tenant" to "acme"), subjectOf("u-1", entries).context)
    }

    // Byte-stability matters for request logs and for any future response cache.
    @Test
    fun context_entries_are_emitted_in_a_stable_order() {
        val a = FlagSubject("u-1", mapOf("tenant" to "acme", "region" to "eu"))
        val b = FlagSubject("u-1", mapOf("region" to "eu", "tenant" to "acme"))
        assertEquals(a.toContextEntries(), b.toContextEntries())
    }
}

/**
 * Guards against anyone reintroducing `map<string, string>` for the context bag.
 *
 * protoc-gen-kt emits an EMPTY encoder branch for map fields, so a map would encode to nothing at
 * all and every context-scoped flag would silently stop resolving. These golden byte counts fail
 * the moment the repeated-entry encoding stops happening.
 */
class ContextEntryWireTest {

    private fun encoded(subject: FlagSubject): ByteArray = EvaluateRequest {
        schemaName = "S"
        userId = subject.userId ?: ""
        context = subject.toContextEntries()
    }.toByteArray()

    @Test
    fun context_entries_actually_reach_the_wire() {
        val none = encoded(FlagSubject("u-1"))
        val one = encoded(FlagSubject("u-1", mapOf("tenant" to "acme")))
        val three = encoded(
            FlagSubject("u-1", mapOf("tenant" to "acme", "region" to "eu", "tier" to "pro")),
        )

        // Each entry adds bytes. A map field would make all three identical.
        assertEquals(
            listOf(true, true),
            listOf(one.size > none.size, three.size > one.size),
            "context entries did not grow the payload: ${none.size}/${one.size}/${three.size}",
        )
    }

    @Test
    fun context_entries_decode_back_to_the_same_subject() {
        val subject = FlagSubject("u-1", mapOf("tenant" to "acme", "region" to "eu", "tier" to "pro"))
        val decoded = EvaluateRequest.fromByteArray(encoded(subject))
        assertEquals(subject, subjectOf(decoded.userId, decoded.context))
    }

    @Test
    fun an_empty_context_decodes_to_an_empty_map() {
        val decoded = EvaluateRequest.fromByteArray(encoded(FlagSubject("u-1")))
        assertEquals(emptyMap(), subjectOf(decoded.userId, decoded.context).context)
    }
}

class DefinitionAndOverrideMappingTest {

    private val definitions = listOf(
        FlagDefinition("a", FlagScope.SERVICE, FlagType.BOOLEAN, FlagValue.of(false)),
        FlagDefinition("b", FlagScope.USER, FlagType.STRING, FlagValue.of("control"), description = "d"),
        FlagDefinition("c", FlagScope.CONTEXT, FlagType.INT, FlagValue.of(3), dimension = "tenant"),
        FlagDefinition("d", FlagScope.SERVICE, FlagType.DOUBLE, FlagValue.of(0.5)),
    )

    @Test
    fun every_definition_round_trips() {
        definitions.forEach { assertEquals(it, it.toProto().toDomainOrNull(), "definition $it") }
    }

    @Test
    fun a_definition_survives_encoding_to_bytes() {
        definitions.forEach {
            val bytes = it.toProto().toByteArray()
            val back = com.latenighthack.ktflags.proto.v1.FlagDefinition.fromByteArray(bytes)
            assertEquals(it, back.toDomainOrNull(), "byte round trip of $it")
        }
    }

    // Generated singular message fields are nullable, so a hand-built or truncated message can
    // arrive with them unset. That must be a null, never an NPE.
    @Test
    fun a_definition_with_a_missing_default_value_is_rejected() {
        val incomplete = com.latenighthack.ktflags.proto.v1.FlagDefinition {
            key = "a"
            scope = ProtoFlagScope.FLAG_SCOPE_SERVICE
            type = ProtoFlagType.FLAG_TYPE_BOOL
        }
        assertNull(incomplete.toDomainOrNull())
    }

    // FlagDefinition's own init would throw on this; the mapping must reject it first so a bad
    // peer cannot crash the server.
    @Test
    fun a_definition_whose_default_disagrees_with_its_type_is_rejected_not_thrown() {
        val mismatched = com.latenighthack.ktflags.proto.v1.FlagDefinition {
            key = "a"
            scope = ProtoFlagScope.FLAG_SCOPE_SERVICE
            type = ProtoFlagType.FLAG_TYPE_BOOL
            defaultValue = FlagValue.of(7).toProto()
        }
        assertNull(mismatched.toDomainOrNull())
    }

    @Test
    fun a_context_definition_without_a_dimension_is_rejected() {
        val bad = com.latenighthack.ktflags.proto.v1.FlagDefinition {
            key = "c"
            scope = ProtoFlagScope.FLAG_SCOPE_CONTEXT
            type = ProtoFlagType.FLAG_TYPE_BOOL
            defaultValue = FlagValue.of(false).toProto()
        }
        assertNull(bad.toDomainOrNull())
    }

    @Test
    fun an_assignment_round_trips() {
        val assignment = ResolvedFlag("a", FlagValue.of(true), ValueSource.SUBJECT_OVERRIDE)
        assertEquals(assignment, assignment.toProto().toDomainOrNull())
    }

    @Test
    fun an_assignment_with_a_missing_value_is_rejected() {
        assertNull(FlagAssignment { key = "a" }.toDomainOrNull())
    }

    @Test
    fun an_override_row_round_trips() {
        val row = FlagOverrideRow(
            "a", FlagSubjectRef.user("u-1"), FlagValue.of("x"),
            updatedAtMillis = 1234L, updatedBy = "someone",
        )
        assertEquals(row, row.toProto().toDomainOrNull())
    }

    @Test
    fun an_override_row_with_a_missing_subject_is_rejected() {
        assertNull(FlagOverride { flagKey = "a"; value = FlagValue.of(true).toProto() }.toDomainOrNull())
    }
}
