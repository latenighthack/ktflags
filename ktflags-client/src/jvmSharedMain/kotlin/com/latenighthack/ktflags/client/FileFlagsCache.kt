package com.latenighthack.ktflags.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * The snapshot as a flat file, written atomically.
 *
 * Shared by the JVM and Android actuals. Two deliberate choices:
 *
 *  - **Temp file plus atomic rename.** A reader always sees either the whole old file or the whole
 *    new one, never a half-written one. ktstore's JVM delegate writes in place, which can leave a
 *    truncated file if the process dies mid-write; that is worth not repeating.
 *  - **No fsync.** A torn cache after a power cut costs one refetch (the decode fails and the
 *    snapshot is discarded), which is not worth an fsync on every refresh.
 *
 * All IO runs on [Dispatchers.IO], and no method throws.
 */
internal class FileFlagsCache(private val file: File) : FlagsCache {
    // Serialises this process's own writers. Cross-process safety comes from the atomic rename,
    // which is all a cache needs.
    private val mutex = Mutex()

    override suspend fun load(): ByteArray? = mutex.withLock {
        withContext(Dispatchers.IO) {
            runCatching { if (file.isFile) file.readBytes() else null }.getOrNull()
        }
    }

    override suspend fun save(bytes: ByteArray) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching {
                    val parent = file.parentFile
                    parent?.mkdirs()
                    val temp = File.createTempFile(file.name, ".tmp", parent)
                    try {
                        temp.writeBytes(bytes)
                        try {
                            Files.move(
                                temp.toPath(),
                                file.toPath(),
                                StandardCopyOption.REPLACE_EXISTING,
                                StandardCopyOption.ATOMIC_MOVE,
                            )
                        } catch (_: AtomicMoveNotSupportedException) {
                            // Some filesystems (and some Android external storage) cannot do it.
                            // A non-atomic replace is still better than writing in place.
                            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                    } finally {
                        temp.delete()
                    }
                }
                Unit
            }
        }
    }

    override suspend fun clear() {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                runCatching { file.delete() }
                Unit
            }
        }
    }
}
