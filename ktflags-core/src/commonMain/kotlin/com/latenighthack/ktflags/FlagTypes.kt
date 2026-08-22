package com.latenighthack.ktflags

/**
 * How a flag's value is keyed.
 *
 * These are real Kotlin enums rather than the generated protobuf ones on purpose. The wire enums
 * are sealed classes with an `UNKNOWN_(n)` case, so every `when` over one needs an `else` and the
 * compiler stops reporting missing branches. Scope resolution is the correctness core of this
 * library; it is worth a mapping layer to keep those `when`s provably total.
 */
public enum class FlagScope { SERVICE, USER, CONTEXT }

/** The four value types a flag may hold. */
public enum class FlagType { BOOLEAN, STRING, INT, DOUBLE }

/** Which layer of the resolution chain produced a value. */
public enum class ValueSource {
    /** The compile-time default from the annotated data class. */
    CODE_DEFAULT,

    /** The service-wide stored value -- the rollout knob. */
    SERVICE_DEFAULT,

    /** A stored value addressed to this specific user or context key. */
    SUBJECT_OVERRIDE,
}

/** A single typed flag value. */
public sealed class FlagValue {
    public abstract val type: FlagType

    public data class BoolValue(public val value: Boolean) : FlagValue() {
        override val type: FlagType get() = FlagType.BOOLEAN
    }

    public data class StringValue(public val value: String) : FlagValue() {
        override val type: FlagType get() = FlagType.STRING
    }

    public data class IntValue(public val value: Int) : FlagValue() {
        override val type: FlagType get() = FlagType.INT
    }

    public data class DoubleValue(public val value: Double) : FlagValue() {
        override val type: FlagType get() = FlagType.DOUBLE
    }

    /** The value if it is of this type, else null. Never coerces between types. */
    public val asBoolean: Boolean? get() = (this as? BoolValue)?.value
    public val asString: String? get() = (this as? StringValue)?.value
    public val asInt: Int? get() = (this as? IntValue)?.value
    public val asDouble: Double? get() = (this as? DoubleValue)?.value

    public companion object {
        public fun of(value: Boolean): FlagValue = BoolValue(value)
        public fun of(value: String): FlagValue = StringValue(value)
        public fun of(value: Int): FlagValue = IntValue(value)
        public fun of(value: Double): FlagValue = DoubleValue(value)
    }
}

/**
 * One flag, as declared in the annotated data class.
 *
 * @param key the wire key -- the property name, or the `@FlagKey` override. Stored rows are keyed
 *   by this string, so changing it orphans existing overrides.
 * @param dimension the `@ContextScoped` axis name. Empty for [FlagScope.SERVICE] and
 *   [FlagScope.USER].
 */
public data class FlagDefinition(
    public val key: String,
    public val scope: FlagScope,
    public val type: FlagType,
    public val defaultValue: FlagValue,
    public val dimension: String = "",
    public val description: String = "",
) {
    init {
        require(key.isNotBlank()) { "flag key must not be blank" }
        require(defaultValue.type == type) {
            "flag '$key' is declared $type but its default value is ${defaultValue.type}"
        }
        when (scope) {
            FlagScope.CONTEXT -> require(dimension.isNotBlank()) {
                "context-scoped flag '$key' needs a non-blank dimension"
            }
            FlagScope.SERVICE, FlagScope.USER -> require(dimension.isEmpty()) {
                "flag '$key' is $scope but declares dimension '$dimension'"
            }
        }
    }
}

/**
 * Addresses one stored row: the service-wide value, one user, or one key within one dimension.
 *
 * Deliberately one addressing scheme for the wire, the repository and the admin JSON, so the three
 * cannot disagree about what a row is.
 */
public data class FlagSubjectRef(
    public val scope: FlagScope,
    public val dimension: String,
    public val key: String,
) {
    public companion object {
        /** The service-wide row: `{SERVICE, "", ""}`. */
        public val Service: FlagSubjectRef = FlagSubjectRef(FlagScope.SERVICE, "", "")

        public fun user(userId: String): FlagSubjectRef =
            FlagSubjectRef(FlagScope.USER, "", userId)

        public fun context(dimension: String, key: String): FlagSubjectRef =
            FlagSubjectRef(FlagScope.CONTEXT, dimension, key)
    }
}

/**
 * Who is asking: an optional user id plus any number of context dimensions.
 *
 * Empty strings normalize to absent throughout. proto3 has no `optional`, so a client that always
 * sends `user_id` and sometimes leaves it blank must behave identically to one that omits it.
 */
public data class FlagSubject(
    public val userId: String? = null,
    public val context: Map<String, String> = emptyMap(),
) {
    public val normalizedUserId: String? get() = userId?.takeIf { it.isNotEmpty() }

    public fun contextKey(dimension: String): String? =
        context[dimension]?.takeIf { it.isNotEmpty() }

    /**
     * A stable, order-independent identity for this subject.
     *
     * Persisted alongside the client's cached snapshot and compared before that snapshot is
     * trusted, so one user's flags can never be replayed to another after a login switch. The
     * separators are ASCII control characters so they cannot occur in a user id or context key.
     */
    public val fingerprint: String
        get() = buildString {
            append(normalizedUserId ?: "")
            append(UNIT_SEPARATOR)
            context.entries
                .filter { it.key.isNotEmpty() && it.value.isNotEmpty() }
                .sortedBy { it.key }
                .forEach {
                    append(it.key).append(RECORD_SEPARATOR).append(it.value).append(UNIT_SEPARATOR)
                }
        }

    public companion object {
        private const val UNIT_SEPARATOR = '\u001F'
        private const val RECORD_SEPARATOR = '\u001E'

        public val Anonymous: FlagSubject = FlagSubject()
    }
}

/**
 * A resolved key -> value map.
 *
 * The typed accessors NEVER throw: a missing key or a type mismatch falls back to the supplied
 * compile-time default. That is a load-bearing invariant, not laziness -- a bad row in the database
 * must degrade one flag, not crash the app. Bad rows are prevented at the write boundary and
 * surfaced by the admin panel's orphan view.
 */
public class FlagValues private constructor(private val byKey: Map<String, FlagValue>) {
    public val keys: Set<String> get() = byKey.keys

    public operator fun get(key: String): FlagValue? = byKey[key]

    public fun toMap(): Map<String, FlagValue> = byKey

    public fun boolean(key: String, default: Boolean): Boolean = byKey[key]?.asBoolean ?: default

    public fun string(key: String, default: String): String = byKey[key]?.asString ?: default

    public fun int(key: String, default: Int): Int = byKey[key]?.asInt ?: default

    public fun double(key: String, default: Double): Double = byKey[key]?.asDouble ?: default

    /** Right-biased merge: entries in [other] win. */
    public operator fun plus(other: FlagValues): FlagValues = FlagValues(byKey + other.byKey)

    override fun equals(other: Any?): Boolean = other is FlagValues && other.byKey == byKey

    override fun hashCode(): Int = byKey.hashCode()

    override fun toString(): String = "FlagValues($byKey)"

    public companion object {
        public val Empty: FlagValues = FlagValues(emptyMap())

        public fun from(map: Map<String, FlagValue>): FlagValues = FlagValues(map.toMap())

        public fun of(vararg pairs: Pair<String, FlagValue>): FlagValues = FlagValues(pairs.toMap())
    }
}

/**
 * The bridge between a consumer's flag data class and ktflags. Implemented by the KSP-generated
 * `<ClassName>Schema` object; there is no reason to write one by hand.
 */
public interface FlagSchema<T : Any> {
    /** Identifies this flag set on the wire and in storage. */
    public val schemaName: String

    public val definitions: List<FlagDefinition>

    /** An instance carrying every compile-time default. */
    public val defaults: T

    /**
     * Builds an instance from resolved values, falling back to [defaults] per field.
     *
     * Must be total: `materialize(FlagValues.Empty) == defaults`.
     */
    public fun materialize(values: FlagValues): T
}

/**
 * O(1) lookup and derived facts over a [FlagSchema]. Build once per provider or per server, not
 * per call.
 */
public class FlagSchemaIndex(public val schema: FlagSchema<*>) {
    private val byKey: Map<String, FlagDefinition> = schema.definitions.associateBy { it.key }

    init {
        require(byKey.size == schema.definitions.size) {
            val duplicates = schema.definitions.groupBy { it.key }.filterValues { it.size > 1 }.keys
            "schema '${schema.schemaName}' declares duplicate flag keys: $duplicates"
        }
    }

    public val keys: Set<String> get() = byKey.keys

    public operator fun get(key: String): FlagDefinition? = byKey[key]

    /** Every dimension any context-scoped flag declares. */
    public val dimensions: Set<String> =
        schema.definitions.filter { it.scope == FlagScope.CONTEXT }.map { it.dimension }.toSet()

    /**
     * Whether [ref] is allowed to hold a row for [key].
     *
     * Any flag can have a service-wide value; only a user-scoped flag can have a user row, and
     * only a context-scoped flag can have a row in its own declared dimension. The admin surface
     * rejects writes that fail this, so the store can never accumulate rows the schema forbids.
     */
    public fun canOwn(ref: FlagSubjectRef, key: String): Boolean {
        val definition = byKey[key] ?: return false
        return when (ref.scope) {
            FlagScope.SERVICE -> true
            FlagScope.USER -> definition.scope == FlagScope.USER
            FlagScope.CONTEXT ->
                definition.scope == FlagScope.CONTEXT && definition.dimension == ref.dimension
        }
    }
}
