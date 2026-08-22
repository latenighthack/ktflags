package com.latenighthack.ktflags.server

import com.latenighthack.ktflags.FlagScope
import com.latenighthack.ktflags.FlagSubjectRef
import com.latenighthack.ktflags.FlagType
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.ValueSource
import kotlinx.serialization.Serializable

internal const val ADMIN_TOKEN_HEADER: String = "x-admin-token"
internal const val ADMIN_COOKIE: String = "ktflags_admin"

/**
 * Compares two secrets without leaking their common prefix through timing.
 *
 * Hand-rolled because `MessageDigest.isEqual` would drag in a needless dependency on byte arrays
 * and this is four lines.
 */
internal fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) diff = diff or (a[i].code xor b[i].code)
    return diff == 0
}

/**
 * A tagged value rather than a polymorphic serializer: `{"type":"bool","bool":true}` is trivial to
 * produce and consume from forty lines of vanilla JavaScript, and the tag drives both the input
 * widget the panel renders and the value it posts back.
 */
@Serializable
public data class ValueDto(
    val type: String,
    val bool: Boolean? = null,
    val string: String? = null,
    val int: Int? = null,
    val double: Double? = null,
)

@Serializable
public data class FlagDto(
    val key: String,
    val scope: String,
    val type: String,
    val dimension: String,
    val description: String,
    val codeDefault: ValueDto,
    /** Null when no service-wide row exists, i.e. the code default is in effect. */
    val serviceValue: ValueDto? = null,
    val serviceUpdatedAtMillis: Long = 0,
    val serviceUpdatedBy: String = "",
    val overrideCount: Int = 0,
)

@Serializable
public data class FlagListDto(
    val schemaName: String,
    val revision: Long,
    val flags: List<FlagDto>,
    val orphanCount: Int,
)

@Serializable
public data class SubjectRefDto(
    val scope: String,
    val dimension: String = "",
    val key: String,
)

@Serializable
public data class SubjectFlagDto(
    val key: String,
    val scope: String,
    val type: String,
    val dimension: String,
    val effective: ValueDto,
    /** "code" | "service" | "subject" -- rendered as a provenance badge. */
    val source: String,
    val overridden: Boolean,
    /** False means this subject cannot own this flag; the row renders read-only. */
    val applicable: Boolean,
    val updatedAtMillis: Long = 0,
    val updatedBy: String = "",
)

@Serializable
public data class SubjectDto(
    val subject: SubjectRefDto,
    val revision: Long,
    val flags: List<SubjectFlagDto>,
)

@Serializable
public data class SetValueDto(val value: ValueDto, val updatedBy: String = "")

@Serializable
public data class SubjectPatchDto(
    val subject: SubjectRefDto,
    val set: Map<String, ValueDto> = emptyMap(),
    val clear: List<String> = emptyList(),
    val updatedBy: String = "",
)

@Serializable
public data class RevisionDto(val revision: Long, val removed: Boolean? = null)

@Serializable
public data class SubjectListDto(val subjects: List<SubjectRefDto>)

@Serializable
public data class OverrideDto(
    val flagKey: String,
    val subject: SubjectRefDto,
    val value: ValueDto,
    val updatedAtMillis: Long,
    val updatedBy: String,
)

@Serializable
public data class OverrideListDto(val overrides: List<OverrideDto>)

@Serializable
public data class PurgeDto(val revision: Long, val purged: Int)

@Serializable
public data class ErrorDto(val code: String, val message: String)

// --- conversions -------------------------------------------------------------------------------

internal fun FlagValue.toDto(): ValueDto = when (this) {
    is FlagValue.BoolValue -> ValueDto("bool", bool = value)
    is FlagValue.StringValue -> ValueDto("string", string = value)
    is FlagValue.IntValue -> ValueDto("int", int = value)
    is FlagValue.DoubleValue -> ValueDto("double", double = value)
}

/** Null when the tag is unknown or the matching payload field is absent. */
internal fun ValueDto.toDomainOrNull(): FlagValue? = when (type) {
    "bool" -> bool?.let(FlagValue::BoolValue)
    "string" -> string?.let(FlagValue::StringValue)
    "int" -> int?.let(FlagValue::IntValue)
    "double" -> double?.let(FlagValue::DoubleValue)
    else -> null
}

internal fun FlagType.toWire(): String = when (this) {
    FlagType.BOOLEAN -> "bool"
    FlagType.STRING -> "string"
    FlagType.INT -> "int"
    FlagType.DOUBLE -> "double"
}

internal fun FlagScope.toWire(): String = when (this) {
    FlagScope.SERVICE -> "service"
    FlagScope.USER -> "user"
    FlagScope.CONTEXT -> "context"
}

internal fun scopeFromWire(value: String): FlagScope? = when (value.lowercase()) {
    "service" -> FlagScope.SERVICE
    "user" -> FlagScope.USER
    "context" -> FlagScope.CONTEXT
    else -> null
}

internal fun ValueSource.toWire(): String = when (this) {
    ValueSource.CODE_DEFAULT -> "code"
    ValueSource.SERVICE_DEFAULT -> "service"
    ValueSource.SUBJECT_OVERRIDE -> "subject"
}

internal fun FlagSubjectRef.toDto(): SubjectRefDto =
    SubjectRefDto(scope.toWire(), dimension, key)
