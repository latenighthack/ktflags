package com.latenighthack.ktflags.client

import java.io.File

/**
 * On a JVM the cache is a real flat file.
 *
 * The default location is `~/.ktflags`, falling back to the temp dir. A server-side or CLI
 * consumer that wants it somewhere specific sets `cacheDirectory`.
 */
public actual fun defaultFlagsCache(name: String, directory: String?): FlagsCache {
    val dir = directory
        ?: System.getProperty("user.home")?.let { "$it/.ktflags" }
        ?: System.getProperty("java.io.tmpdir")
        ?: "."
    return FileFlagsCache(File(dir, "$name.pb"))
}
