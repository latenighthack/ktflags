package com.latenighthack.ktflags.server

import com.latenighthack.ktflags.FlagDefinition
import com.latenighthack.ktflags.FlagSchema
import com.latenighthack.ktflags.FlagScope
import com.latenighthack.ktflags.FlagType
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.FlagValues
import com.latenighthack.ktflags.FlagsRepository
import com.latenighthack.ktflags.InMemoryFlagsRepository

internal data class TestFlags(
    val newCheckout: Boolean = false,
    val darkMode: Boolean = false,
    val betaApi: Boolean = false,
    val variant: String = "control",
    val maxItems: Int = 10,
    val samplingRate: Double = 0.5,
)

/** Mirrors what ktflags-ksp generates, so the server tests exercise a realistic schema. */
internal object TestFlagsSchema : FlagSchema<TestFlags> {
    override val schemaName: String = "TestFlags"

    override val defaults: TestFlags = TestFlags()

    override val definitions: List<FlagDefinition> = listOf(
        FlagDefinition(
            "newCheckout", FlagScope.SERVICE, FlagType.BOOLEAN,
            FlagValue.BoolValue(defaults.newCheckout), description = "the rewritten flow",
        ),
        FlagDefinition(
            "darkMode", FlagScope.USER, FlagType.BOOLEAN,
            FlagValue.BoolValue(defaults.darkMode),
        ),
        FlagDefinition(
            "betaApi", FlagScope.CONTEXT, FlagType.BOOLEAN,
            FlagValue.BoolValue(defaults.betaApi), dimension = "tenant",
        ),
        FlagDefinition(
            "variant", FlagScope.USER, FlagType.STRING,
            FlagValue.StringValue(defaults.variant),
        ),
        FlagDefinition(
            "maxItems", FlagScope.SERVICE, FlagType.INT,
            FlagValue.IntValue(defaults.maxItems),
        ),
        FlagDefinition(
            "samplingRate", FlagScope.SERVICE, FlagType.DOUBLE,
            FlagValue.DoubleValue(defaults.samplingRate),
        ),
    )

    override fun materialize(values: FlagValues): TestFlags = TestFlags(
        newCheckout = values.boolean("newCheckout", defaults.newCheckout),
        darkMode = values.boolean("darkMode", defaults.darkMode),
        betaApi = values.boolean("betaApi", defaults.betaApi),
        variant = values.string("variant", defaults.variant),
        maxItems = values.int("maxItems", defaults.maxItems),
        samplingRate = values.double("samplingRate", defaults.samplingRate),
    )
}

internal fun testService(
    repository: FlagsRepository = InMemoryFlagsRepository(),
    configure: FeatureFlagsConfig.() -> Unit = {},
): FeatureFlagsService<TestFlags> = FeatureFlagsService(
    TestFlagsSchema,
    repository,
    FeatureFlagsConfig().apply {
        // Tests never want a real listener bound by default.
        adminPort = null
        clock = { 1_000L }
        configure()
    },
)
