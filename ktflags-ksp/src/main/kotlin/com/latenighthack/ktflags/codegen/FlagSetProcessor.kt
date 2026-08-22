package com.latenighthack.ktflags.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFile
import com.google.devtools.ksp.symbol.KSPropertyDeclaration
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier

/** The Kotlin types a flag may hold, and the [com.latenighthack.ktflags.FlagType] each maps to. */
internal enum class FlagKind(val qualifiedName: String, val flagType: String, val valueClass: String) {
    BOOLEAN("kotlin.Boolean", "BOOLEAN", "BoolValue"),
    STRING("kotlin.String", "STRING", "StringValue"),
    INT("kotlin.Int", "INT", "IntValue"),
    DOUBLE("kotlin.Double", "DOUBLE", "DoubleValue"),
    ;

    /** The `FlagValues` accessor that reads a flag of this kind. */
    val accessor: String
        get() = when (this) {
            BOOLEAN -> "boolean"
            STRING -> "string"
            INT -> "int"
            DOUBLE -> "double"
        }

    companion object {
        fun of(qualifiedName: String?): FlagKind? = entries.firstOrNull { it.qualifiedName == qualifiedName }

        val supported: String get() = entries.joinToString(", ") { it.qualifiedName.removePrefix("kotlin.") }
    }
}

internal data class FlagInfo(
    val propertyName: String,
    val key: String,
    val kind: FlagKind,
    val scope: String,
    val dimension: String,
    val description: String,
)

internal data class FlagSetInfo(
    val packageName: String,
    val className: String,
    val schemaName: String,
    val flags: List<FlagInfo>,
    val containingFile: KSFile?,
)

/**
 * Turns an `@FeatureFlagSet` data class into a `FlagSchema<T>` object.
 *
 * Symbols are collected in [process] and emitted in [finish], following `basekit-ksp`. Only the
 * common metadata pass is ever wired (see the ktflags Gradle plugin), so unlike basekit there is
 * no marker-file trick to detect which pass this is: this processor emits exactly one kind of
 * output into exactly one place. A double-wire surfaces as a loud `Redeclaration:` error naming
 * both source roots, which is a better diagnostic than anything a heuristic would produce.
 */
internal class FlagSetProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {

    private val flagSets = mutableListOf<FlagSetInfo>()
    private var collected = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (collected) return emptyList()
        collected = true

        resolver.getSymbolsWithAnnotation(Annotations.FEATURE_FLAG_SET)
            .filterIsInstance<KSClassDeclaration>()
            .forEach { declaration -> build(declaration)?.let(flagSets::add) }

        return emptyList()
    }

    override fun finish() {
        if (flagSets.isEmpty()) return
        flagSets.forEach { SchemaGenerator(codeGenerator).generate(it) }
    }

    private fun build(declaration: KSClassDeclaration): FlagSetInfo? {
        val simpleName = declaration.simpleName.asString()

        if (declaration.classKind != ClassKind.CLASS || Modifier.DATA !in declaration.modifiers) {
            logger.error(
                "@FeatureFlagSet $simpleName must be a data class: the generated schema builds " +
                    "instances through its primary constructor and compares them by value.",
                declaration,
            )
            return null
        }

        val constructor = declaration.primaryConstructor
        if (constructor == null || constructor.parameters.isEmpty()) {
            logger.error(
                "@FeatureFlagSet $simpleName must declare at least one flag as a primary " +
                    "constructor property.",
                declaration,
            )
            return null
        }

        // Annotations on a constructor `val` can be attached to the parameter, the property, or
        // both, depending on use-site targeting. Reading both and unioning them means the
        // processor never depends on which one KSP happens to surface.
        val propertiesByName: Map<String, KSPropertyDeclaration> =
            declaration.getAllProperties().associateBy { it.simpleName.asString() }

        var failed = false
        val flags = mutableListOf<FlagInfo>()

        constructor.parameters.forEach { parameter ->
            val name = parameter.name?.asString()
            if (name == null) {
                logger.error("@FeatureFlagSet $simpleName has an unnamed constructor parameter.", parameter)
                failed = true
                return@forEach
            }
            val sources = listOfNotNull(parameter, propertiesByName[name])

            if (sources.any { it.hasAnnotation(Annotations.FLAG_IGNORE) }) {
                // The generated schema constructs `ClassName()` to read defaults from, so even a
                // property that is not a flag has to be defaultable.
                if (!parameter.hasDefault) {
                    logger.error(
                        "@FlagIgnore $simpleName.$name has no default value. The generated schema " +
                            "reads defaults from a default-constructed $simpleName, so every " +
                            "primary-constructor property needs one.",
                        parameter,
                    )
                    failed = true
                }
                return@forEach
            }

            val flag = buildFlag(simpleName, name, parameter, sources)
            if (flag == null) failed = true else flags.add(flag)
        }

        if (failed) return null

        if (flags.isEmpty()) {
            logger.error(
                "@FeatureFlagSet $simpleName declares no flags: every property is @FlagIgnore.",
                declaration,
            )
            return null
        }

        val duplicates = flags.groupBy { it.key }.filterValues { it.size > 1 }
        if (duplicates.isNotEmpty()) {
            duplicates.forEach { (key, group) ->
                logger.error(
                    "@FeatureFlagSet $simpleName maps ${group.size} properties " +
                        "(${group.joinToString(", ") { it.propertyName }}) to the same flag key " +
                        "'$key'. Flag keys must be unique -- stored overrides are keyed by them.",
                    declaration,
                )
            }
            return null
        }

        val declaredName = declaration.stringArgument(Annotations.FEATURE_FLAG_SET, "name")
        return FlagSetInfo(
            packageName = declaration.packageName.asString(),
            className = simpleName,
            schemaName = declaredName?.takeIf { it.isNotBlank() } ?: simpleName,
            flags = flags,
            containingFile = declaration.containingFile,
        )
    }

    private fun buildFlag(
        className: String,
        propertyName: String,
        parameter: KSValueParameter,
        sources: List<KSAnnotated>,
    ): FlagInfo? {
        val where = "$className.$propertyName"

        // The generated schema reads every default off an `AppFlags()` instance rather than
        // parsing default-value expressions, which KSP cannot give us. That only works if the
        // no-argument construction compiles.
        if (!parameter.hasDefault) {
            logger.error(
                "Flag $where has no default value. Every flag needs one: it is the value the app " +
                    "falls back to offline and before the first fetch, and the generated schema " +
                    "reads it from a default-constructed $className.",
                parameter,
            )
            return null
        }

        val declaredScopes = Annotations.SCOPES.filter { fqn -> sources.any { it.hasAnnotation(fqn) } }
        when (declaredScopes.size) {
            1 -> Unit
            0 -> {
                logger.error(
                    "Flag $where has no scope annotation. Add @ServiceScoped, @UserScoped or " +
                        "@ContextScoped(\"dimension\") -- or @FlagIgnore if it is not a flag. " +
                        "Unannotated properties are rejected rather than skipped so a forgotten " +
                        "annotation cannot silently drop a flag from the schema.",
                    parameter,
                )
                return null
            }
            else -> {
                logger.error(
                    "Flag $where declares ${declaredScopes.size} scope annotations " +
                        "(${declaredScopes.joinToString(", ") { it.substringAfterLast('.') }}). " +
                        "A flag is keyed exactly one way.",
                    parameter,
                )
                return null
            }
        }
        val scopeAnnotation = declaredScopes.single()

        val resolved = parameter.type.resolve()
        val kind = FlagKind.of(resolved.declaration.qualifiedName?.asString())
        if (kind == null || resolved.isMarkedNullable) {
            logger.error(
                "Flag $where is ${resolved.declaration.qualifiedName?.asString() ?: "?"}" +
                    "${if (resolved.isMarkedNullable) "?" else ""}, which cannot be a flag value. " +
                    "Supported types are ${FlagKind.supported} (non-null).",
                parameter,
            )
            return null
        }

        val dimension = if (scopeAnnotation == Annotations.CONTEXT_SCOPED) {
            val declared = sources.firstNotNullOfOrNull {
                it.stringArgument(Annotations.CONTEXT_SCOPED, "dimension")
            }
            if (declared.isNullOrBlank()) {
                logger.error(
                    "Flag $where is @ContextScoped with a blank dimension. The dimension names " +
                        "the axis a caller supplies a key for, e.g. @ContextScoped(\"tenant\").",
                    parameter,
                )
                return null
            }
            declared
        } else {
            ""
        }

        val key = sources.firstNotNullOfOrNull { it.stringArgument(Annotations.FLAG_KEY, "name") }
            ?.takeIf { it.isNotBlank() }
            ?: propertyName

        val description =
            sources.firstNotNullOfOrNull { it.stringArgument(Annotations.FLAG_DESCRIPTION, "text") }
                .orEmpty()

        return FlagInfo(
            propertyName = propertyName,
            key = key,
            kind = kind,
            scope = when (scopeAnnotation) {
                Annotations.SERVICE_SCOPED -> "SERVICE"
                Annotations.USER_SCOPED -> "USER"
                else -> "CONTEXT"
            },
            dimension = dimension,
            description = description,
        )
    }
}
