@file:OptIn(ExperimentalForeignApi::class)

package com.latenighthack.ktflags.client

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArrayOf
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import platform.Foundation.NSApplicationSupportDirectory
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
import platform.posix.memcpy

/**
 * Covers every Apple target: iOS device, both simulators, and both macOS architectures.
 *
 * Defaults to Application Support, which is the right place for data the app regenerates but
 * would rather not lose (Caches can be evicted under disk pressure mid-session).
 */
public actual fun defaultFlagsCache(name: String, directory: String?): FlagsCache {
    val dir = directory ?: run {
        val base = NSSearchPathForDirectoriesInDomains(
            NSApplicationSupportDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String
        base?.let { "$it/ktflags" } ?: NSTemporaryDirectory()
    }
    NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
    return NsDataFlagsCache("$dir/$name.pb")
}

internal class NsDataFlagsCache(private val path: String) : FlagsCache {
    private val mutex = Mutex()

    override suspend fun load(): ByteArray? = mutex.withLock {
        NSData.dataWithContentsOfFile(path)?.toByteArray()
    }

    override suspend fun save(bytes: ByteArray) {
        mutex.withLock {
            // `atomically = true` is Foundation's own write-temp-then-rename, so atomicity comes
            // for free here rather than being hand-rolled as it is on the JVM.
            bytes.toNSData().writeToFile(path, atomically = true)
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            NSFileManager.defaultManager.removeItemAtPath(path, null)
        }
    }
}

private fun ByteArray.toNSData(): NSData {
    // NSData.create with a zero-length array would read from a dangling pointer, so short-circuit.
    if (isEmpty()) return NSData()
    return memScoped { NSData.create(bytes = allocArrayOf(this@toNSData), length = size.toULong()) }
}

private fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val result = ByteArray(size)
    result.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return result
}
