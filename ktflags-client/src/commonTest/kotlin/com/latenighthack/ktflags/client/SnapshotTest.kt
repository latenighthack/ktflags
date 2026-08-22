package com.latenighthack.ktflags.client

import com.latenighthack.ktflags.FlagSubject
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.ResolvedFlag
import com.latenighthack.ktflags.ValueSource
import com.latenighthack.ktflags.proto.v1.FlagSnapshot
import com.latenighthack.ktflags.proto.v1.toByteArray
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class SnapshotCodecTest {

    private val flags = listOf(
        ResolvedFlag("newCheckout", FlagValue.of(true), ValueSource.SERVICE_DEFAULT),
        ResolvedFlag("variant", FlagValue.of("treatment"), ValueSource.SUBJECT_OVERRIDE),
        ResolvedFlag("maxItems", FlagValue.of(0), ValueSource.CODE_DEFAULT),
    )

    private fun encoded(
        schemaName: String = "TestFlags",
        fingerprint: String = "fp",
        revision: Long = 3L,
    ) = encodeSnapshot(schemaName, fingerprint, revision, fetchedAtMillis = 99L, flags = flags)

    @Test
    fun a_snapshot_round_trips() {
        val decoded = assertNotNull(decodeSnapshot(encoded(), "TestFlags", "fp"))
        assertEquals(3L, decoded.revision)
        assertEquals(99L, decoded.fetchedAtMillis)
        assertEquals(flags, decoded.flags)
    }

    @Test
    fun garbage_bytes_are_discarded_rather_than_throwing() {
        assertNull(decodeSnapshot(byteArrayOf(-1, -2, -3, -4, -5), "TestFlags", "fp"))
    }

    @Test
    fun an_empty_payload_is_discarded() {
        assertNull(decodeSnapshot(ByteArray(0), "TestFlags", "fp"))
    }

    @Test
    fun a_snapshot_from_a_different_schema_is_discarded() {
        assertNull(decodeSnapshot(encoded(schemaName = "OtherFlags"), "TestFlags", "fp"))
    }

    // The important one. Without the fingerprint check, a snapshot fetched for one user would be
    // served to the next user who logs in on the same device.
    @Test
    fun a_snapshot_for_a_different_subject_is_discarded() {
        assertNull(decodeSnapshot(encoded(fingerprint = "someone-else"), "TestFlags", "fp"))
    }

    @Test
    fun a_snapshot_from_a_future_format_version_is_discarded() {
        val future = FlagSnapshot {
            formatVersion = SNAPSHOT_FORMAT_VERSION + 1
            schemaName = "TestFlags"
            subjectFingerprint = "fp"
            revision = 1L
        }.toByteArray()
        assertNull(decodeSnapshot(future, "TestFlags", "fp"))
    }

    @Test
    fun a_snapshot_with_no_version_at_all_is_discarded() {
        val versionless = FlagSnapshot {
            schemaName = "TestFlags"
            subjectFingerprint = "fp"
        }.toByteArray()
        assertNull(decodeSnapshot(versionless, "TestFlags", "fp"))
    }

    // Explicitly-false values are the whole reason FlagValue is a oneof; they must survive the
    // cache as well as the wire.
    @Test
    fun zero_values_survive_the_snapshot() {
        val zeros = listOf(
            ResolvedFlag("b", FlagValue.of(false), ValueSource.SERVICE_DEFAULT),
            ResolvedFlag("s", FlagValue.of(""), ValueSource.SERVICE_DEFAULT),
            ResolvedFlag("i", FlagValue.of(0), ValueSource.SERVICE_DEFAULT),
            ResolvedFlag("d", FlagValue.of(0.0), ValueSource.SERVICE_DEFAULT),
        )
        val bytes = encodeSnapshot("TestFlags", "fp", 1L, 0L, zeros)
        assertEquals(zeros, decodeSnapshot(bytes, "TestFlags", "fp")?.flags)
    }
}

class ProviderCacheTest {

    @Test
    fun start_loads_a_cached_snapshot_before_the_network_answers() = runTest {
        val fingerprint = FlagSubject("u-1").fingerprint
        val cached = encodeSnapshot(
            schemaName = "TestFlags",
            subjectFingerprint = fingerprint,
            revision = 2L,
            fetchedAtMillis = 500L,
            flags = listOf(
                ResolvedFlag("newCheckout", FlagValue.of(true), ValueSource.SERVICE_DEFAULT),
            ),
        )
        // The network never answers, so anything present came from the cache.
        val provider = FeatureFlagsProvider(
            TestFlagsSchema,
            testConfig(
                ScriptedTransport.failing(com.latenighthack.ktbuf.proto.Codes.UNAVAILABLE),
                cache = InMemoryFlagsCache(cached),
                userId = "u-1",
            ),
        )

        provider.start()

        assertEquals(true, provider.current().newCheckout)
    }

    @Test
    fun a_successful_refresh_writes_the_snapshot_back() = runTest {
        val cache = InMemoryFlagsCache()
        val provider = FeatureFlagsProvider(
            TestFlagsSchema,
            testConfig(
                ScriptedTransport.returning(6L, "darkMode" to FlagValue.of(true)),
                cache = cache,
                userId = "u-1",
            ),
        )

        provider.refresh()

        val decoded = assertNotNull(
            decodeSnapshot(assertNotNull(cache.load()), "TestFlags", FlagSubject("u-1").fingerprint),
        )
        assertEquals(6L, decoded.revision)
        assertEquals(listOf("darkMode"), decoded.flags.map { it.key })
    }

    @Test
    fun a_cache_written_for_another_user_is_ignored() = runTest {
        val foreign = encodeSnapshot(
            "TestFlags", FlagSubject("someone-else").fingerprint, 2L, 0L,
            listOf(ResolvedFlag("newCheckout", FlagValue.of(true), ValueSource.SERVICE_DEFAULT)),
        )
        val provider = FeatureFlagsProvider(
            TestFlagsSchema,
            testConfig(
                ScriptedTransport.failing(com.latenighthack.ktbuf.proto.Codes.UNAVAILABLE),
                cache = InMemoryFlagsCache(foreign),
                userId = "u-1",
            ),
        )

        provider.start()

        assertEquals(TestFlags(), provider.current())
    }

    @Test
    fun setSubject_clears_the_cached_snapshot() = runTest {
        val cache = InMemoryFlagsCache()
        val transport = ScriptedTransport.returning(1L, "darkMode" to FlagValue.of(true))
        val provider = FeatureFlagsProvider(
            TestFlagsSchema,
            testConfig(transport, cache = cache, userId = "u-1"),
        )
        provider.refresh()
        assertNotNull(cache.load())

        transport.respondWith { evaluateResponse(2L) }
        provider.setSubject("u-2")

        // The new snapshot belongs to u-2, and cannot be read back as u-1's.
        assertNull(decodeSnapshot(assertNotNull(cache.load()), "TestFlags", FlagSubject("u-1").fingerprint))
        assertNotNull(decodeSnapshot(assertNotNull(cache.load()), "TestFlags", FlagSubject("u-2").fingerprint))
    }

    // A cache is an optimisation. If it cannot be read, that must cost nothing but a fetch.
    @Test
    fun a_cache_that_always_fails_does_not_break_the_provider() = runTest {
        val broken = object : FlagsCache {
            override suspend fun load(): ByteArray? = throw IllegalStateException("disk on fire")
            override suspend fun save(bytes: ByteArray) = throw IllegalStateException("disk on fire")
            override suspend fun clear() {}
        }
        val provider = FeatureFlagsProvider(
            TestFlagsSchema,
            testConfig(
                ScriptedTransport.returning(1L, "newCheckout" to FlagValue.of(true)),
                cache = broken,
            ),
        )

        assertEquals(RefreshResult.Updated(1L), provider.start())
        assertEquals(true, provider.current().newCheckout)
    }
}
