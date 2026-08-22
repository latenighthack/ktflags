package com.latenighthack.ktflags.codegen

import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import java.io.OutputStream

internal object Annotations {
    const val PACKAGE = "com.latenighthack.ktflags"

    const val FEATURE_FLAG_SET = "$PACKAGE.FeatureFlagSet"
    const val SERVICE_SCOPED = "$PACKAGE.ServiceScoped"
    const val USER_SCOPED = "$PACKAGE.UserScoped"
    const val CONTEXT_SCOPED = "$PACKAGE.ContextScoped"
    const val FLAG_IGNORE = "$PACKAGE.FlagIgnore"
    const val FLAG_KEY = "$PACKAGE.FlagKey"
    const val FLAG_DESCRIPTION = "$PACKAGE.FlagDescription"

    val SCOPES = listOf(SERVICE_SCOPED, USER_SCOPED, CONTEXT_SCOPED)
}

internal fun OutputStream.writeln(line: String = "") {
    write(line.encodeToByteArray())
    write("\n".encodeToByteArray())
}

internal fun KSAnnotation.qualifiedName(): String? =
    annotationType.resolve().declaration.qualifiedName?.asString()

internal fun KSAnnotated.annotationNamed(fqn: String): KSAnnotation? =
    annotations.firstOrNull { it.qualifiedName() == fqn }

internal fun KSAnnotated.hasAnnotation(fqn: String): Boolean = annotationNamed(fqn) != null

/**
 * Reads a String argument off an annotation.
 *
 * Returns null when the annotation is absent; returns the declared default (which KSP materializes
 * for us) when the argument is not explicitly written.
 */
internal fun KSAnnotated.stringArgument(annotationFqn: String, argumentName: String): String? =
    annotationNamed(annotationFqn)
        ?.arguments
        ?.firstOrNull { it.name?.asString() == argumentName }
        ?.value as? String

/** Escapes a value for embedding in a generated Kotlin string literal. */
internal fun String.asKotlinStringLiteral(): String = buildString {
    append('"')
    this@asKotlinStringLiteral.forEach { c ->
        when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '$' -> append("\\$")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }
    append('"')
}
