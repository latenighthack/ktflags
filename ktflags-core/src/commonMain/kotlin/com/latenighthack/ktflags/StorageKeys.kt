package com.latenighthack.ktflags

/**
 * How a scope is spelled in storage, on the admin JSON wire, and in query parameters.
 *
 * Defined once because three places depend on it agreeing: the SQL stores write it into
 * `scope_kind`, the admin API accepts it as `?scope=`, and every multi-row read is ordered by it.
 * That last one is why the ordering comparator must use this rather than the enum ordinal -- SQL
 * sorts the stored text, so `context` < `service` < `user`, and a reference implementation that
 * sorted by declaration order would disagree with the databases it is supposed to define.
 */
public val FlagScope.storageKey: String
    get() = when (this) {
        FlagScope.SERVICE -> "service"
        FlagScope.USER -> "user"
        FlagScope.CONTEXT -> "context"
    }

/** Parses [storageKey]. Null for anything unrecognised. */
public fun flagScopeFromStorageKey(value: String): FlagScope? = when (value) {
    "service" -> FlagScope.SERVICE
    "user" -> FlagScope.USER
    "context" -> FlagScope.CONTEXT
    else -> null
}

/** How a type is spelled in storage and on the admin JSON wire. */
public val FlagType.storageKey: String
    get() = when (this) {
        FlagType.BOOLEAN -> "bool"
        FlagType.STRING -> "string"
        FlagType.INT -> "int"
        FlagType.DOUBLE -> "double"
    }

/** The `value_type` tag for a value, matching the stores' CHECK constraint. */
public val FlagValue.storageKey: String
    get() = when (this) {
        is FlagValue.BoolValue -> "bool"
        is FlagValue.StringValue -> "string"
        is FlagValue.IntValue -> "int"
        is FlagValue.DoubleValue -> "double"
    }
