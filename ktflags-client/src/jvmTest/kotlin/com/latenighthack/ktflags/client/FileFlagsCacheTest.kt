package com.latenighthack.ktflags.client

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Holds the real filesystem-backed cache to the same contract as every other implementation. */
class FileFlagsCacheTest : FlagsCacheContract() {
    override fun newCache(): FlagsCache {
        val dir = Files.createTempDirectory("ktflags-cache").toFile().apply { deleteOnExit() }
        return FileFlagsCache(File(dir, "snapshot.pb"))
    }
}

class FileFlagsCacheBehaviourTest {

    private fun tempFile(): File {
        val dir = Files.createTempDirectory("ktflags-cache").toFile().apply { deleteOnExit() }
        return File(dir, "snapshot.pb")
    }

    @Test
    fun the_snapshot_really_is_a_flat_file_on_disk() = runTest {
        val file = tempFile()
        FileFlagsCache(file).save(byteArrayOf(1, 2, 3))
        assertTrue(file.isFile, "expected a file at ${file.absolutePath}")
        assertContentEquals(byteArrayOf(1, 2, 3), file.readBytes())
    }

    @Test
    fun a_missing_parent_directory_is_created() = runTest {
        val nested = File(tempFile().parentFile, "a/b/c/snapshot.pb")
        FileFlagsCache(nested).save(byteArrayOf(7))
        assertTrue(nested.isFile)
    }

    // No temp file may survive a save, or a long-lived app would litter its data directory.
    @Test
    fun writing_leaves_no_temp_files_behind() = runTest {
        val file = tempFile()
        val cache = FileFlagsCache(file)
        repeat(5) { cache.save(byteArrayOf(it.toByte())) }
        assertEquals(
            listOf(file.name),
            file.parentFile.list()!!.sorted(),
            "a temp file survived the write",
        )
    }

    // The cache must degrade, never throw: a full disk or a revoked permission is not a reason to
    // crash an app over a feature flag.
    @Test
    fun an_unwritable_location_fails_silently_rather_than_throwing() = runTest {
        // A path whose parent is an existing *file* can never be created as a directory.
        val blocker = tempFile().apply { parentFile.mkdirs(); writeBytes(byteArrayOf(0)) }
        val impossible = FileFlagsCache(File(blocker, "child/snapshot.pb"))

        impossible.save(byteArrayOf(1, 2, 3))
        assertNull(impossible.load())
        impossible.clear()
    }

    @Test
    fun a_corrupt_file_loads_as_bytes_and_is_rejected_by_the_decoder_not_the_cache() = runTest {
        val file = tempFile()
        file.parentFile.mkdirs()
        file.writeBytes(byteArrayOf(-1, -1, -1, -1))

        // The cache's job is bytes in, bytes out; validity is the snapshot codec's problem.
        assertContentEquals(byteArrayOf(-1, -1, -1, -1), FileFlagsCache(file).load())
        assertNull(decodeSnapshot(file.readBytes(), "TestFlags", ""))
    }

    @Test
    fun concurrent_saves_do_not_interleave_into_a_torn_file() = runTest {
        val file = tempFile()
        val cache = FileFlagsCache(file)
        val payloads = (1..20).map { n -> ByteArray(5_000) { n.toByte() } }

        payloads.map { async { cache.save(it) } }.awaitAll()

        // Whichever write won, the file is exactly one payload -- never a mix of two.
        val actual = cache.load()!!
        assertEquals(5_000, actual.size)
        assertEquals(1, actual.toSet().size, "the file contains bytes from more than one write")
    }
}
