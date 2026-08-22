package com.latenighthack.ktflags.client

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Opaque byte storage for exactly one flag snapshot.
 *
 * Deliberately not a key-value store: one blob means one atomic write and no partial-state
 * reasoning. The bytes are a protobuf `FlagSnapshot`.
 *
 * No method may throw. A cache is an optimisation -- a full disk, a revoked permission or a
 * private-browsing localStorage must degrade to "no cache", never to a crashed app.
 */
public interface FlagsCache {
    public suspend fun load(): ByteArray?

    public suspend fun save(bytes: ByteArray)

    public suspend fun clear()
}

/** Holds the snapshot in memory only. The default for tests, and for JS outside a browser. */
public class InMemoryFlagsCache(initial: ByteArray? = null) : FlagsCache {
    private val mutex = Mutex()
    private var bytes: ByteArray? = initial

    override suspend fun load(): ByteArray? = mutex.withLock { bytes }

    override suspend fun save(bytes: ByteArray) {
        mutex.withLock { this.bytes = bytes }
    }

    override suspend fun clear() {
        mutex.withLock { bytes = null }
    }
}

/**
 * The platform's default cache.
 *
 * An `expect fun` rather than an `expect class`: a function needs no
 * `EXPECT_ACTUAL_CLASSIFIERS_ARE_IN_BETA` suppression, and it lets a platform choose an
 * implementation at runtime (JS picks localStorage or memory depending on whether it is in a
 * browser).
 *
 * @param name base name for the file or storage key.
 * @param directory where to put it. Required on Android -- pass `context.filesDir.absolutePath`;
 *   every other platform has a sensible default.
 */
public expect fun defaultFlagsCache(name: String, directory: String?): FlagsCache
