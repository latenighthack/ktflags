package com.latenighthack.ktflags

/**
 * The example schema from the README, hand-written.
 *
 * ktflags-core cannot depend on ktflags-ksp (the processor depends on nothing, but the dependency
 * would be circular through demo-flags), so this mirrors by hand exactly what the generator emits
 * -- including the "read defaults off an instance" shape. If this and the generator ever disagree,
 * the generator's own golden test in ktflags-ksp is the one that is right.
 */
internal data class TestFlags(
    val newCheckout: Boolean = false,
    val darkMode: Boolean = false,
    val betaApi: Boolean = false,
    val variant: String = "control",
    val maxItems: Int = 10,
    val samplingRate: Double = 0.5,
)

internal object TestFlagsSchema : FlagSchema<TestFlags> {
    override val schemaName: String = "TestFlags"

    override val defaults: TestFlags = TestFlags()

    override val definitions: List<FlagDefinition> = listOf(
        FlagDefinition(
            "newCheckout", FlagScope.SERVICE, FlagType.BOOLEAN,
            FlagValue.BoolValue(defaults.newCheckout),
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

/** Shorthand for building rows in tests. */
internal fun row(
    flagKey: String,
    ref: FlagSubjectRef,
    value: FlagValue,
): FlagOverrideRow = FlagOverrideRow(flagKey, ref, value, updatedAtMillis = 0L)
