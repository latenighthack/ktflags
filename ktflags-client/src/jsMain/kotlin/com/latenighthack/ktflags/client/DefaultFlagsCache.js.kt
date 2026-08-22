package com.latenighthack.ktflags.client

import kotlinx.browser.localStorage
import org.w3c.dom.Storage
import org.w3c.dom.get
import org.w3c.dom.set

/**
 * A browser has no filesystem, so the "flat file" degrades to one `localStorage` entry.
 *
 * Outside a browser (Node, a worker without storage, Safari private mode) this falls back to an
 * in-memory cache. That is a silent degradation rather than an error on purpose: a cache is an
 * optimisation, and failing to construct a provider because the host has no storage would be
 * worse than starting cold. A Node consumer that wants persistence supplies its own
 * `FeatureFlagsConfig.persistence`.
 */
public actual fun defaultFlagsCache(name: String, directory: String?): FlagsCache =
    runCatching { localStorage }.getOrNull()
        ?.let { LocalStorageFlagsCache(it, name) }
        ?: InMemoryFlagsCache()

internal class LocalStorageFlagsCache(
    private val storage: Storage,
    name: String,
) : FlagsCache {
    private val key = "ktflags.$name.snapshot"

    override suspend fun load(): ByteArray? =
        runCatching { storage[key]?.decodeBase64() }.getOrNull()

    override suspend fun save(bytes: ByteArray) {
        // QuotaExceededError, and a SecurityError in private browsing, must not surface.
        runCatching { storage[key] = bytes.encodeBase64() }
    }

    override suspend fun clear() {
        runCatching { storage.removeItem(key) }
    }
}

// localStorage holds strings, so the protobuf bytes are base64'd. Standard alphabet with padding,
// via the browser's own btoa/atob -- no dependency, and correct for arbitrary bytes as long as
// each byte maps to one code unit, which the latin1 round trip below guarantees.
private fun ByteArray.encodeBase64(): String {
    val chars = StringBuilder(size)
    forEach { chars.append((it.toInt() and 0xFF).toChar()) }
    return btoa(chars.toString())
}

private fun String.decodeBase64(): ByteArray {
    val binary = atob(this)
    return ByteArray(binary.length) { binary[it].code.toByte() }
}

private external fun btoa(data: String): String

private external fun atob(data: String): String
