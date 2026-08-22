package com.latenighthack.ktflags.demo

import com.latenighthack.ktflags.ContextScoped
import com.latenighthack.ktflags.FeatureFlagSet
import com.latenighthack.ktflags.FlagDescription
import com.latenighthack.ktflags.FlagKey
import com.latenighthack.ktflags.ServiceScoped
import com.latenighthack.ktflags.UserScoped

/**
 * The whole of a consumer's "flags" module: one annotated data class.
 *
 * This module depends only on `ktflags-core` -- no protobuf, no client, no server -- and is
 * consumed unchanged by both sides. KSP generates `DemoFlagsSchema` next to it.
 */
@FeatureFlagSet
public data class DemoFlags(
    @ServiceScoped
    @FlagDescription("Route checkout through the rewritten flow.")
    val newCheckout: Boolean = false,

    @UserScoped
    @FlagDescription("Opt a single account into the dark theme.")
    val darkMode: Boolean = false,

    @ContextScoped("tenant")
    @FlagDescription("Expose the v2 API surface to a whole tenant at once.")
    val betaApi: Boolean = false,

    @UserScoped
    @FlagDescription("Which arm of the checkout copy experiment this account sees.")
    val variant: String = "control",

    @ServiceScoped
    @FlagDescription("Maximum items rendered in the cart before paging.")
    val maxItems: Int = 10,

    @ServiceScoped
    @FlagDescription("Fraction of requests emitting a trace, 0.0 to 1.0.")
    val samplingRate: Double = 0.05,

    // The property was renamed but the stored overrides are keyed by the old string, so @FlagKey
    // keeps them attached. Renaming a flag key silently orphans every override for it.
    @ServiceScoped
    @FlagKey("legacy_banner")
    @FlagDescription("Show the end-of-life banner. Key is pinned to its pre-rename value.")
    val endOfLifeBanner: Boolean = false,
)
