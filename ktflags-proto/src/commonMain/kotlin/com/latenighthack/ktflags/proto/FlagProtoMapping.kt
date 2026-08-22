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
import com.latenighthack.ktflags.proto.v1.FlagAssignment
import com.latenighthack.ktflags.proto.v1.FlagOverride
import com.latenighthack.ktflags.proto.v1.SubjectRef
import com.latenighthack.ktflags.proto.v1.FlagDefinition as ProtoFlagDefinition
import com.latenighthack.ktflags.proto.v1.FlagScope as ProtoFlagScope
import com.latenighthack.ktflags.proto.v1.FlagType as ProtoFlagType
import com.latenighthack.ktflags.proto.v1.FlagValue as ProtoFlagValue
import com.latenighthack.ktflags.proto.v1.ValueSource as ProtoValueSource

/**
 * The single boundary between the hand-written domain model (`ktflags-core`) and the generated
 * wire types.
 *
 * This is the only file that imports both namespaces, hence the `as Proto*` aliases -- the simple
 * names deliberately match, because renaming either set to dodge four aliases here would make one
 * of them read badly everywhere else.
 *
 * Two rules hold throughout:
 *  - Wire -> domain returns `null` for anything unrepresentable (an unset oneof, an unknown enum,
 *    an out-of-Int-range int64). Callers decide whether that is INVALID_ARGUMENT (server, on a
 *    write) or "drop the row and fall through" (client, on a read).
 *  - Domain -> wire is total and needs no `else`, which is exactly why the domain enums exist.
 */

// --- FlagValue ---------------------------------------------------------------------------------

/**
 * Null when the oneof is unset, or when `int_value` does not fit in a Kotlin `Int`.
 *
 * The narrowing check is here rather than at the call sites so there is one place where a wire
 * value too large for the domain model is rejected.
 */
public fun ProtoFlagValue.toDomainOrNull(): FlagValue? {
    val oneOf = value ?: return null
    oneOf.getBoolValue()?.let { return FlagValue.BoolValue(it) }
    oneOf.getStringValue()?.let { return FlagValue.StringValue(it) }
    oneOf.getIntValue()?.let {
        return if (it in INT_MIN..INT_MAX) FlagValue.IntValue(it.toInt()) else null
    }
    oneOf.getDoubleValue()?.let { return FlagValue.DoubleValue(it) }
    return null
}

public fun FlagValue.toProto(): ProtoFlagValue = when (this) {
    is FlagValue.BoolValue -> ProtoFlagValue { value.boolValue = this@toProto.value }
    is FlagValue.StringValue -> ProtoFlagValue { value.stringValue = this@toProto.value }
    is FlagValue.IntValue -> ProtoFlagValue { value.intValue = this@toProto.value.toLong() }
    is FlagValue.DoubleValue -> ProtoFlagValue { value.doubleValue = this@toProto.value }
}

private const val INT_MIN: Long = Int.MIN_VALUE.toLong()
private const val INT_MAX: Long = Int.MAX_VALUE.toLong()

// --- Enums -------------------------------------------------------------------------------------

/**
 * Null for UNSPECIFIED and for any `UNKNOWN_(n)` a newer peer might send.
 *
 * Read through the generated `isX()` predicates rather than comparing against the companion
 * singletons: protoc-gen-kt emits a nested class AND a companion property under each constant
 * name, and in a qualified expression the classifier wins, so `FlagScope.FLAG_SCOPE_SERVICE.value`
 * does not compile.
 */
public fun ProtoFlagScope.toDomainOrNull(): FlagScope? = when {
    isFlagScopeService() -> FlagScope.SERVICE
    isFlagScopeUser() -> FlagScope.USER
    isFlagScopeContext() -> FlagScope.CONTEXT
    else -> null
}

public fun FlagScope.toProto(): ProtoFlagScope = when (this) {
    FlagScope.SERVICE -> ProtoFlagScope.FLAG_SCOPE_SERVICE
    FlagScope.USER -> ProtoFlagScope.FLAG_SCOPE_USER
    FlagScope.CONTEXT -> ProtoFlagScope.FLAG_SCOPE_CONTEXT
}

public fun ProtoFlagType.toDomainOrNull(): FlagType? = when {
    isFlagTypeBool() -> FlagType.BOOLEAN
    isFlagTypeString() -> FlagType.STRING
    isFlagTypeInt() -> FlagType.INT
    isFlagTypeDouble() -> FlagType.DOUBLE
    else -> null
}

public fun FlagType.toProto(): ProtoFlagType = when (this) {
    FlagType.BOOLEAN -> ProtoFlagType.FLAG_TYPE_BOOL
    FlagType.STRING -> ProtoFlagType.FLAG_TYPE_STRING
    FlagType.INT -> ProtoFlagType.FLAG_TYPE_INT
    FlagType.DOUBLE -> ProtoFlagType.FLAG_TYPE_DOUBLE
}

public fun ProtoValueSource.toDomainOrNull(): ValueSource? = when {
    isValueSourceCodeDefault() -> ValueSource.CODE_DEFAULT
    isValueSourceServiceDefault() -> ValueSource.SERVICE_DEFAULT
    isValueSourceSubjectOverride() -> ValueSource.SUBJECT_OVERRIDE
    else -> null
}

public fun ValueSource.toProto(): ProtoValueSource = when (this) {
    ValueSource.CODE_DEFAULT -> ProtoValueSource.VALUE_SOURCE_CODE_DEFAULT
    ValueSource.SERVICE_DEFAULT -> ProtoValueSource.VALUE_SOURCE_SERVICE_DEFAULT
    ValueSource.SUBJECT_OVERRIDE -> ProtoValueSource.VALUE_SOURCE_SUBJECT_OVERRIDE
}

// --- Subjects ----------------------------------------------------------------------------------

/** Null when the scope is unknown, or when the ref's shape contradicts its scope. */
public fun SubjectRef.toDomainOrNull(): FlagSubjectRef? {
    val domainScope = scope.toDomainOrNull() ?: return null
    return when (domainScope) {
        // A service row addresses nothing else; a stray dimension or key means a malformed peer.
        FlagScope.SERVICE ->
            if (dimension.isEmpty() && key.isEmpty()) FlagSubjectRef.Service else null
        FlagScope.USER ->
            if (dimension.isEmpty() && key.isNotEmpty()) FlagSubjectRef.user(key) else null
        FlagScope.CONTEXT ->
            if (dimension.isNotEmpty() && key.isNotEmpty()) {
                FlagSubjectRef.context(dimension, key)
            } else {
                null
            }
    }
}

public fun FlagSubjectRef.toProto(): SubjectRef = SubjectRef {
    scope = this@toProto.scope.toProto()
    dimension = this@toProto.dimension
    key = this@toProto.key
}

/** Builds a [FlagSubject] from the wire's user id and context entries. Blank entries are dropped. */
public fun subjectOf(userId: String, context: List<ContextEntry>): FlagSubject = FlagSubject(
    userId = userId.takeIf { it.isNotEmpty() },
    context = context
        .filter { it.dimension.isNotEmpty() && it.key.isNotEmpty() }
        .associate { it.dimension to it.key },
)

public fun FlagSubject.toContextEntries(): List<ContextEntry> = context
    .filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
    .map { (dimension, key) -> ContextEntry { this.dimension = dimension; this.key = key } }
    // Sorted so an EvaluateRequest for the same subject is byte-identical run to run, which keeps
    // request logs and any future caching layer stable.
    .sortedBy { it.dimension }

// --- Definitions and assignments -----------------------------------------------------------------

/**
 * Null when any part is unrepresentable, or when the definition is internally inconsistent (a
 * default whose type disagrees with the declared type, a context flag with no dimension). The
 * domain [FlagDefinition] enforces those invariants in its `init`, so they are checked here rather
 * than allowed to throw.
 */
public fun ProtoFlagDefinition.toDomainOrNull(): FlagDefinition? {
    val domainScope = scope.toDomainOrNull() ?: return null
    val domainType = type.toDomainOrNull() ?: return null
    val default = defaultValue?.toDomainOrNull() ?: return null
    if (default.type != domainType) return null
    if (domainScope == FlagScope.CONTEXT && dimension.isEmpty()) return null
    if (domainScope != FlagScope.CONTEXT && dimension.isNotEmpty()) return null
    return FlagDefinition(key, domainScope, domainType, default, dimension, description)
}

public fun FlagDefinition.toProto(): ProtoFlagDefinition = ProtoFlagDefinition {
    key = this@toProto.key
    scope = this@toProto.scope.toProto()
    type = this@toProto.type.toProto()
    dimension = this@toProto.dimension
    defaultValue = this@toProto.defaultValue.toProto()
    description = this@toProto.description
}

public fun ResolvedFlag.toProto(): FlagAssignment = FlagAssignment {
    key = this@toProto.key
    value = this@toProto.value.toProto()
    source = this@toProto.source.toProto()
}

/**
 * Null when the value or the source is unrepresentable.
 *
 * An unrecognised source is not fatal on its own -- the value is what the app actually reads --
 * but reporting it as CODE_DEFAULT would be a lie to the admin panel, so the whole assignment is
 * dropped and the client falls back to its own compile-time default.
 */
public fun FlagAssignment.toDomainOrNull(): ResolvedFlag? {
    val domainValue = value?.toDomainOrNull() ?: return null
    val domainSource = source.toDomainOrNull() ?: return null
    return ResolvedFlag(key, domainValue, domainSource)
}

public fun FlagOverrideRow.toProto(): FlagOverride = FlagOverride {
    flagKey = this@toProto.flagKey
    subject = this@toProto.ref.toProto()
    value = this@toProto.value.toProto()
    updatedAtMillis = this@toProto.updatedAtMillis
    updatedBy = this@toProto.updatedBy
}

/** Null when the subject or the value is unrepresentable. */
public fun FlagOverride.toDomainOrNull(): FlagOverrideRow? {
    val ref = subject?.toDomainOrNull() ?: return null
    val domainValue = value?.toDomainOrNull() ?: return null
    return FlagOverrideRow(flagKey, ref, domainValue, updatedAtMillis, updatedBy)
}
