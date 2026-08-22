package com.latenighthack.ktflags.client

import java.io.File

/**
 * On Android there is no safe default location, so [directory] is required.
 *
 * This deliberately fails loudly rather than reaching for a process-wide Context singleton (the
 * shape ktstore uses) or silently writing to a temp dir the system may wipe. An explicit config
 * knob with an actionable message beats either.
 */
public actual fun defaultFlagsCache(name: String, directory: String?): FlagsCache {
    val dir = directory ?: error(
        "ktflags on Android needs an explicit cache directory: set " +
            "FeatureFlagsConfig.cacheDirectory = context.filesDir.absolutePath, or supply your " +
            "own FeatureFlagsConfig.persistence.",
    )
    return FileFlagsCache(File(dir, "$name.pb"))
}
