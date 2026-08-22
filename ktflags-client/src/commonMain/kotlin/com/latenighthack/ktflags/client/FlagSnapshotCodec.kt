package com.latenighthack.ktflags.client

import com.latenighthack.ktflags.ResolvedFlag
import com.latenighthack.ktflags.proto.toDomainOrNull
import com.latenighthack.ktflags.proto.toProto
import com.latenighthack.ktflags.proto.v1.FlagSnapshot
import com.latenighthack.ktflags.proto.v1.fromByteArray
import com.latenighthack.ktflags.proto.v1.toByteArray

/**
 * Bump when the meaning of an existing field changes. A snapshot with a different version is
 * discarded rather than reinterpreted.
 */
internal const val SNAPSHOT_FORMAT_VERSION: Int = 1

internal data class CachedSnapshot(
    val revision: Long,
    val fetchedAtMillis: Long,
    val flags: List<ResolvedFlag>,
)

internal fun encodeSnapshot(
    schemaName: String,
    subjectFingerprint: String,
    revision: Long,
    fetchedAtMillis: Long,
    flags: List<ResolvedFlag>,
): ByteArray = FlagSnapshot {
    formatVersion = SNAPSHOT_FORMAT_VERSION
    this.schemaName = schemaName
    this.subjectFingerprint = subjectFingerprint
    this.revision = revision
    this.fetchedAtMillis = fetchedAtMillis
    assignments = flags.map { it.toProto() }
}.toByteArray()

/**
 * Decodes a cached snapshot, or returns null if it must not be trusted.
 *
 * Every rejection is silent by design -- a stale, corrupt or foreign cache is not an error
 * condition, it just means starting from defaults. The three identity checks matter for different
 * reasons:
 *
 *  - [SNAPSHOT_FORMAT_VERSION] guards against reinterpreting an older layout.
 *  - `schemaName` stops one flag set's cache being read as another's.
 *  - `subjectFingerprint` is the important one: without it, a snapshot fetched for one user could
 *    be replayed to the next user to log in on the same device.
 *
 * Individual assignments that fail to map (an unknown enum from a newer server, a value the domain
 * model cannot hold) are dropped rather than failing the whole snapshot; the client then falls
 * back to its compile-time default for those flags only.
 */
internal fun decodeSnapshot(
    bytes: ByteArray,
    schemaName: String,
    subjectFingerprint: String,
): CachedSnapshot? {
    val snapshot = runCatching { FlagSnapshot.fromByteArray(bytes) }.getOrNull() ?: return null
    if (snapshot.formatVersion != SNAPSHOT_FORMAT_VERSION) return null
    if (snapshot.schemaName != schemaName) return null
    if (snapshot.subjectFingerprint != subjectFingerprint) return null

    return CachedSnapshot(
        revision = snapshot.revision,
        fetchedAtMillis = snapshot.fetchedAtMillis,
        flags = snapshot.assignments.mapNotNull { it.toDomainOrNull() },
    )
}
