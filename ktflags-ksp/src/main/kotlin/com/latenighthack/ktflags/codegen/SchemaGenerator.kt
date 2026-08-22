package com.latenighthack.ktflags.codegen

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies

/**
 * Emits `<ClassName>Schema.kt` next to the annotated class.
 *
 * The generated object never contains a default-value *literal*. KSP can tell us that a
 * constructor parameter has a default but not what it is, so the schema declares
 * `defaults = AppFlags()` first and reads every default off that instance. Kotlin initialises
 * object properties in declaration order, so `definitions` below can safely reference `defaults`.
 * That is why every flag is required to have a constructor default.
 */
internal class SchemaGenerator(private val codeGenerator: CodeGenerator) {

    fun generate(info: FlagSetInfo) {
        val objectName = "${info.className}Schema"
        val dependencies = info.containingFile
            ?.let { Dependencies(aggregating = false, it) }
            ?: Dependencies(aggregating = false)

        codeGenerator
            .createNewFile(dependencies, info.packageName, objectName)
            .use { out ->
                out.writeln("package ${info.packageName}")
                out.writeln()
                out.writeln("import com.latenighthack.ktflags.FlagDefinition")
                out.writeln("import com.latenighthack.ktflags.FlagSchema")
                out.writeln("import com.latenighthack.ktflags.FlagScope")
                out.writeln("import com.latenighthack.ktflags.FlagType")
                out.writeln("import com.latenighthack.ktflags.FlagValue")
                out.writeln("import com.latenighthack.ktflags.FlagValues")
                out.writeln()
                out.writeln("/**")
                out.writeln(" * Generated from [${info.className}] by ktflags-ksp. Do not edit.")
                out.writeln(" *")
                out.writeln(" * Pass this to `FeatureFlagsProvider(${objectName}, ...)` on the client and to")
                out.writeln(" * `installFeatureFlags(${objectName}, ...)` on the server.")
                out.writeln(" */")
                out.writeln("public object $objectName : FlagSchema<${info.className}> {")
                out.writeln("    override val schemaName: String = ${info.schemaName.asKotlinStringLiteral()}")
                out.writeln()
                out.writeln("    // Declared before `definitions` on purpose: object properties initialise in")
                out.writeln("    // declaration order, and every default below is read from this instance rather")
                out.writeln("    // than duplicated as a literal.")
                out.writeln("    override val defaults: ${info.className} = ${info.className}()")
                out.writeln()
                out.writeln("    override val definitions: List<FlagDefinition> = listOf(")
                info.flags.forEach { flag ->
                    out.writeln("        FlagDefinition(")
                    out.writeln("            key = ${flag.key.asKotlinStringLiteral()},")
                    out.writeln("            scope = FlagScope.${flag.scope},")
                    out.writeln("            type = FlagType.${flag.kind.flagType},")
                    out.writeln(
                        "            defaultValue = FlagValue.${flag.kind.valueClass}" +
                            "(defaults.${flag.propertyName}),",
                    )
                    if (flag.dimension.isNotEmpty()) {
                        out.writeln("            dimension = ${flag.dimension.asKotlinStringLiteral()},")
                    }
                    if (flag.description.isNotEmpty()) {
                        out.writeln("            description = ${flag.description.asKotlinStringLiteral()},")
                    }
                    out.writeln("        ),")
                }
                out.writeln("    )")
                out.writeln()
                out.writeln("    override fun materialize(values: FlagValues): ${info.className} =")
                out.writeln("        ${info.className}(")
                info.flags.forEach { flag ->
                    out.writeln(
                        "            ${flag.propertyName} = values.${flag.kind.accessor}(" +
                            "${flag.key.asKotlinStringLiteral()}, defaults.${flag.propertyName}),",
                    )
                }
                out.writeln("        )")
                out.writeln("}")
            }
    }
}
