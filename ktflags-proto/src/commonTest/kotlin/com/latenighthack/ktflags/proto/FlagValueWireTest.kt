package com.latenighthack.ktflags.proto

import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.proto.v1.FlagValue as ProtoFlagValue
import com.latenighthack.ktflags.proto.v1.fromByteArray
import com.latenighthack.ktflags.proto.v1.toByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The zero-value presence guard.
 *
 * ktflags models a flag value as a protobuf `oneof`, and the entire design rests on one property
 * of protoc-gen-kt's generated encoder: the oneof branch writes the selected case
 * UNCONDITIONALLY, with no `if (x != default)` suppression. That is what lets a flag be
 * *explicitly false* rather than merely absent.
 *
 * If someone regenerates against a protoc-gen-kt whose oneof template gained default suppression,
 * every `false` / `0` / `""` / `0.0` flag would silently revert to its compile-time default across
 * the whole fleet -- a silent, plausible-looking, per-value failure. This test is the tripwire, and
 * it is why it asserts on bytes rather than only on a round trip.
 */
class FlagValueWireTest {

    private fun roundTrip(value: FlagValue): FlagValue? =
        ProtoFlagValue.fromByteArray(value.toProto().toByteArray()).toDomainOrNull()

    @Test
    fun an_explicitly_false_boolean_survives_the_wire_as_present() {
        assertEquals(FlagValue.BoolValue(false), roundTrip(FlagValue.BoolValue(false)))
    }

    @Test
    fun a_zero_int_survives_the_wire_as_present() {
        assertEquals(FlagValue.IntValue(0), roundTrip(FlagValue.IntValue(0)))
    }

    @Test
    fun an_empty_string_survives_the_wire_as_present() {
        assertEquals(FlagValue.StringValue(""), roundTrip(FlagValue.StringValue("")))
    }

    @Test
    fun a_zero_double_survives_the_wire_as_present() {
        assertEquals(FlagValue.DoubleValue(0.0), roundTrip(FlagValue.DoubleValue(0.0)))
    }

    // A suppressed zero would encode to zero bytes. Asserting the exact encoding makes the
    // failure diagnosis obvious rather than "the round trip returned null somehow".
    @Test
    fun a_false_boolean_encodes_to_a_tag_and_a_zero_rather_than_nothing() {
        val bytes = FlagValue.BoolValue(false).toProto().toByteArray()
        // field 1, varint wire type -> tag 0x08; value 0x00.
        assertEquals(listOf<Byte>(0x08, 0x00), bytes.toList())
    }

    @Test
    fun a_zero_int_encodes_to_a_tag_and_a_zero_rather_than_nothing() {
        val bytes = FlagValue.IntValue(0).toProto().toByteArray()
        // field 3, varint wire type -> tag 0x18; value 0x00.
        assertEquals(listOf<Byte>(0x18, 0x00), bytes.toList())
    }

    @Test
    fun an_empty_string_encodes_to_a_tag_and_a_zero_length_rather_than_nothing() {
        val bytes = FlagValue.StringValue("").toProto().toByteArray()
        // field 2, length-delimited -> tag 0x12; length 0x00.
        assertEquals(listOf<Byte>(0x12, 0x00), bytes.toList())
    }

    @Test
    fun every_variant_round_trips_including_extremes() {
        val cases = listOf(
            FlagValue.BoolValue(true),
            FlagValue.BoolValue(false),
            FlagValue.StringValue(""),
            FlagValue.StringValue("hello"),
            FlagValue.StringValue("unicode: é中🎉"),
            FlagValue.IntValue(0),
            FlagValue.IntValue(-1),
            FlagValue.IntValue(Int.MAX_VALUE),
            FlagValue.IntValue(Int.MIN_VALUE),
            FlagValue.DoubleValue(0.0),
            FlagValue.DoubleValue(-1.5),
            FlagValue.DoubleValue(Double.MAX_VALUE),
            FlagValue.DoubleValue(Double.MIN_VALUE),
        )
        cases.forEach { assertEquals(it, roundTrip(it), "round trip of $it") }
    }

    // An unset oneof is a malformed FlagValue: the server rejects it, the client drops the row.
    @Test
    fun an_unset_oneof_maps_to_null_rather_than_a_default() {
        assertEquals(null, ProtoFlagValue().toDomainOrNull())
        assertTrue(ProtoFlagValue().toByteArray().isEmpty())
    }

    // int_value is int64 on the wire for headroom; the domain model is Int. A value that does not
    // fit must be rejected at this boundary, never silently wrapped.
    @Test
    fun an_int64_outside_kotlin_int_range_is_rejected_rather_than_wrapped() {
        val tooBig = ProtoFlagValue { value.intValue = Int.MAX_VALUE.toLong() + 1L }
        val tooSmall = ProtoFlagValue { value.intValue = Int.MIN_VALUE.toLong() - 1L }
        assertEquals(null, tooBig.toDomainOrNull())
        assertEquals(null, tooSmall.toDomainOrNull())

        // The boundaries themselves are fine.
        assertNotNull(ProtoFlagValue { value.intValue = Int.MAX_VALUE.toLong() }.toDomainOrNull())
        assertNotNull(ProtoFlagValue { value.intValue = Int.MIN_VALUE.toLong() }.toDomainOrNull())
    }

    @Test
    fun setting_a_second_oneof_case_replaces_the_first() {
        val v = ProtoFlagValue {
            value.boolValue = true
            value.stringValue = "wins"
        }
        assertEquals(FlagValue.StringValue("wins"), v.toDomainOrNull())
    }
}
