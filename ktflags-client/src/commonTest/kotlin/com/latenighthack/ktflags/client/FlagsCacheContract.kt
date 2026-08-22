package com.latenighthack.ktflags.client

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNull

/**
 * The behaviour every [FlagsCache] must have, whatever it is backed by.
 *
 * There are five implementations across the targets (a JVM/Android file, an Apple `NSData` file,
 * browser `localStorage`, and the in-memory fallback), and the only way they stay honest is by
 * being held to one suite. Each platform subclasses this next to its own actual.
 */
abstract class FlagsCacheContract {

    /** A fresh, empty cache. Implementations must isolate per call. */
    abstract fun newCache(): FlagsCache

    @Test
    fun an_empty_cache_loads_null() = runTest {
        assertNull(newCache().load())
    }

    @Test
    fun saved_bytes_load_back_exactly() = runTest {
        val cache = newCache()
        val bytes = byteArrayOf(1, 2, 3, 0, -1, 127, -128)
        cache.save(bytes)
        assertContentEquals(bytes, cache.load())
    }

    // Protobuf is binary: a cache that round-trips through a text encoding must handle every byte
    // value, not just printable ASCII.
    @Test
    fun every_byte_value_survives_a_round_trip() = runTest {
        val cache = newCache()
        val bytes = ByteArray(256) { (it - 128).toByte() }
        cache.save(bytes)
        assertContentEquals(bytes, cache.load())
    }

    @Test
    fun an_empty_payload_round_trips() = runTest {
        val cache = newCache()
        cache.save(ByteArray(0))
        assertContentEquals(ByteArray(0), cache.load())
    }

    @Test
    fun a_second_save_replaces_the_first() = runTest {
        val cache = newCache()
        cache.save(byteArrayOf(1, 2, 3))
        cache.save(byteArrayOf(9))
        assertContentEquals(byteArrayOf(9), cache.load())
    }

    // A shorter payload overwriting a longer one is where a non-atomic in-place write leaves
    // trailing garbage from the previous contents.
    @Test
    fun a_shorter_payload_does_not_leave_a_tail_behind() = runTest {
        val cache = newCache()
        cache.save(ByteArray(4096) { 0x7F })
        cache.save(byteArrayOf(1, 2))
        assertContentEquals(byteArrayOf(1, 2), cache.load())
    }

    @Test
    fun clear_removes_the_snapshot() = runTest {
        val cache = newCache()
        cache.save(byteArrayOf(1))
        cache.clear()
        assertNull(cache.load())
    }

    @Test
    fun clearing_an_empty_cache_is_not_an_error() = runTest {
        val cache = newCache()
        cache.clear()
        cache.clear()
        assertNull(cache.load())
    }

    @Test
    fun a_large_payload_round_trips() = runTest {
        val cache = newCache()
        val bytes = ByteArray(200_000) { (it % 251).toByte() }
        cache.save(bytes)
        assertContentEquals(bytes, cache.load())
    }
}

class InMemoryFlagsCacheTest : FlagsCacheContract() {
    override fun newCache(): FlagsCache = InMemoryFlagsCache()
}
