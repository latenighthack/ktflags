package com.latenighthack.ktflags.server

import com.latenighthack.ktflags.FlagDefinition
import com.latenighthack.ktflags.FlagSchema
import com.latenighthack.ktflags.FlagScope
import com.latenighthack.ktflags.FlagType
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.FlagValues

/** Values keyed by flag name, for a schema built at runtime rather than by codegen. */
internal data class LargeFlags(val map: Map<String, String> = emptyMap()) {
    /** Roughly what the encoded response carried, used to prove the payload cleared 8KB. */
    val contentBytes: Int get() = map.entries.sumOf { it.key.length + it.value.length }
}

/**
 * A schema with an arbitrary number of string flags.
 *
 * Exists to push an `EvaluateResponse` past the ~8KB mark where ktbuf-server's unary routing
 * breaks on Ktor 3.3.x, so the Ktor pin has a tripwire.
 */
internal class LargeSchema(count: Int) : FlagSchema<LargeFlags> {
    override val schemaName: String = "LargeFlags"

    override val defaults: LargeFlags = LargeFlags()

    override val definitions: List<FlagDefinition> = (0 until count).map { i ->
        FlagDefinition("flag$i", FlagScope.SERVICE, FlagType.STRING, FlagValue.of(""))
    }

    override fun materialize(values: FlagValues): LargeFlags =
        LargeFlags(definitions.associate { it.key to values.string(it.key, "") })
}

/** A schema no test server serves, for exercising the NOT_FOUND path. */
internal object OtherSchema : FlagSchema<LargeFlags> {
    override val schemaName: String = "SomeOtherSchema"
    override val defaults: LargeFlags = LargeFlags()
    override val definitions: List<FlagDefinition> = listOf(
        FlagDefinition("only", FlagScope.SERVICE, FlagType.STRING, FlagValue.of("")),
    )
    override fun materialize(values: FlagValues): LargeFlags =
        LargeFlags(mapOf("only" to values.string("only", "")))
}
