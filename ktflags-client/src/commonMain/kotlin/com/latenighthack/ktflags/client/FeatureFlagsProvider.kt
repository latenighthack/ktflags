package com.latenighthack.ktflags.client

import com.latenighthack.ktbuf.net.RpcResponseException
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktflags.FlagSchema
import com.latenighthack.ktflags.FlagSubject
import com.latenighthack.ktflags.ResolvedFlag
import com.latenighthack.ktflags.proto.toContextEntries
import com.latenighthack.ktflags.proto.toDomainOrNull
import com.latenighthack.ktflags.proto.v1.EvaluateRequest
import com.latenighthack.ktflags.toFlagValues
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout

/** Where the current flag values came from. */
public sealed class FlagsState {
    /** Nothing loaded yet: the values are the compile-time defaults. */
    public data object Defaults : FlagsState()

    /** Loaded from the on-disk snapshot; no successful fetch this session. */
    public data class Cached(val revision: Long, val asOfMillis: Long) : FlagsState()

    /** Confirmed current with the server. */
    public data class Fresh(val revision: Long, val asOfMillis: Long) : FlagsState()

    /** We have values, but the most recent fetch failed. The values are unchanged. */
    public data class Stale(
        val revision: Long,
        val asOfMillis: Long,
        val lastError: FlagsError,
    ) : FlagsState()
}

public sealed class RefreshResult {
    public data class Updated(val revision: Long) : RefreshResult()

    /** The server confirmed our revision is current; nothing was transferred. */
    public data class NotModified(val revision: Long) : RefreshResult()

    public data class Failed(val error: FlagsError) : RefreshResult()
}

/** Cancels an [FeatureFlagsProvider.watch] subscription. */
public fun interface FlagsSubscription {
    public fun cancel()
}

/** The read side of the provider. Depend on this in code that only consumes flags. */
public interface FeatureFlags<T : Any> {
    public val flags: StateFlow<T>

    public val state: StateFlow<FlagsState>

    public fun current(): T

    public suspend fun refresh(): RefreshResult
}

/**
 * The client entry point: one object that turns a [FlagSchema] plus a server address into typed,
 * cache-first feature flags.
 *
 * **Not a singleton, deliberately.** There is no global instance and no `install()`. That is what
 * lets a test hold two providers with different identities, lets a server-side renderer resolve
 * per request, and keeps the whole thing injectable.
 *
 * Construction does no IO and does not suspend, so it is safe in an `Application.onCreate` or an
 * app delegate. Values start at [FlagSchema.defaults]; call [start] to load the cache and fetch.
 *
 * ```
 * val flags = FeatureFlagsProvider(AppFlagsSchema, FeatureFlagsConfig {
 *     serverAddress = "flags.example.com:8080"
 *     cacheDirectory = filesDir.absolutePath      // required on Android
 *     userIdProvider = { session.userId }
 * })
 * flags.startIn(applicationScope)
 * if (flags.current().newCheckout) { ... }
 * ```
 */
public class FeatureFlagsProvider<T : Any>(
    private val schema: FlagSchema<T>,
    private val config: FeatureFlagsConfig,
) : FeatureFlags<T> {

    private val mutableFlags = MutableStateFlow(schema.defaults)
    private val mutableState = MutableStateFlow<FlagsState>(FlagsState.Defaults)

    /** Guards [inFlight] and the cache-load-once latch, not the network call itself. */
    private val mutex = Mutex()
    private var inFlight: CompletableDeferred<RefreshResult>? = null
    private var cacheLoaded = false

    /** Identity and revision of the values currently held. */
    private var lastFingerprint: String? = null
    private var lastRevision: Long = 0L
    private var lastAsOfMillis: Long = 0L

    /** Whether [lastRevision] describes values we actually hold, as opposed to bare defaults. */
    private var hasSnapshot: Boolean = false

    /** Set by [setSubject] to pin the identity; otherwise the providers are consulted per refresh. */
    private var pinnedSubject: FlagSubject? = null

    override val flags: StateFlow<T> = mutableFlags.asStateFlow()

    override val state: StateFlow<FlagsState> = mutableState.asStateFlow()

    override fun current(): T = mutableFlags.value

    /**
     * Loads the cached snapshot, then fetches if [FeatureFlagsConfig.Builder.refreshOnStart].
     *
     * Idempotent: the cache is only read once, and concurrent calls share one fetch.
     */
    public suspend fun start(): RefreshResult {
        loadCacheOnce()
        if (!config.refreshOnStart) {
            return RefreshResult.NotModified(lastRevision)
        }
        return refresh()
    }

    /** Fire-and-forget [start], for `Application.onCreate` and app delegates. */
    public fun startIn(scope: CoroutineScope): Job = scope.launch { start() }

    /**
     * Fetches once.
     *
     * **Never throws** (except [CancellationException]). A flag client that can throw forces every
     * call site into a try/catch, and eventually somebody crashes an app because the flag server
     * hiccuped. Failures come back as [RefreshResult.Failed] and are reflected in [state]; the
     * currently held values are left untouched.
     *
     * Concurrent calls are coalesced into one round trip -- a UI can easily trigger three on
     * resume.
     */
    override suspend fun refresh(): RefreshResult {
        var owned: CompletableDeferred<RefreshResult>? = null
        val pending = mutex.withLock {
            inFlight ?: CompletableDeferred<RefreshResult>().also { inFlight = it; owned = it }
        }
        // Someone else already has this round trip in the air: wait on theirs instead of issuing
        // a duplicate. A UI can easily trigger three refreshes on resume.
        val deferred = owned ?: return pending.await()

        val result = try {
            doRefresh()
            // TimeoutCancellationException IS a CancellationException, so it has to be caught
            // first -- otherwise a timeout would propagate out of a function documented never to
            // throw, instead of becoming RefreshResult.Failed(DEADLINE_EXCEEDED).
        } catch (timeout: TimeoutCancellationException) {
            failure(timeout)
        } catch (cancellation: CancellationException) {
            // A genuine cancellation of the caller's scope. Propagate it, and make sure anyone
            // waiting on this round trip is released rather than left hanging forever.
            mutex.withLock { inFlight = null }
            deferred.cancel(cancellation)
            throw cancellation
        } catch (t: Throwable) {
            failure(t)
        }

        mutex.withLock { inFlight = null }
        deferred.complete(result)
        return result
    }

    /**
     * Changes who flags are resolved for, then refetches.
     *
     * Discards the cached snapshot and sends `known_revision = 0`: a revision is only meaningful
     * for the subject it was fetched for.
     */
    public suspend fun setSubject(
        userId: String?,
        context: Map<String, String> = emptyMap(),
    ): RefreshResult {
        mutex.withLock {
            pinnedSubject = FlagSubject(userId, context)
            lastFingerprint = null
            lastRevision = 0L
            hasSnapshot = false
        }
        mutableFlags.value = schema.defaults
        mutableState.value = FlagsState.Defaults
        config.cache.clear()
        return refresh()
    }

    /**
     * Observes flag changes through a callback.
     *
     * `StateFlow` is not consumable from Swift or from JS, so this is the portable alternative.
     * The callback fires immediately with the current values.
     */
    public fun watch(scope: CoroutineScope, onEach: (T) -> Unit): FlagsSubscription {
        val job = scope.launch {
            mutableFlags.collect { onEach(it) }
        }
        return FlagsSubscription { job.cancel() }
    }

    public suspend fun clearCache() {
        config.cache.clear()
    }

    private suspend fun loadCacheOnce() {
        val shouldLoad = mutex.withLock {
            if (cacheLoaded) false else { cacheLoaded = true; true }
        }
        if (!shouldLoad) return

        val subject = currentSubject()
        val bytes = runCatching { config.cache.load() }
            .onFailure { config.logger("ktflags: reading the flag cache failed", it) }
            .getOrNull() ?: return

        val snapshot = decodeSnapshot(bytes, schema.schemaName, subject.fingerprint) ?: return

        apply(snapshot.flags)
        mutex.withLock {
            lastFingerprint = subject.fingerprint
            lastRevision = snapshot.revision
            lastAsOfMillis = snapshot.fetchedAtMillis
            hasSnapshot = true
        }
        mutableState.value = FlagsState.Cached(snapshot.revision, snapshot.fetchedAtMillis)
    }

    private suspend fun doRefresh(): RefreshResult {
        // The timeout covers the identity providers too: a hanging token refresh would otherwise
        // hang the whole provider indefinitely.
        return withTimeout(config.requestTimeoutMillis) {
            val subject = currentSubject()
            val fingerprint = subject.fingerprint
            // A revision is only meaningful for the subject it was issued against, and setSubject
            // can have moved us since the last fetch, so read both under the lock together.
            // Presence is explicit: revision 0 is a real value for a store with no overrides.
            val known: Long? = mutex.withLock {
                if (fingerprint == lastFingerprint && hasSnapshot) lastRevision else null
            }

            val request = EvaluateRequest {
                schemaName = schema.schemaName
                userId = subject.normalizedUserId.orEmpty()
                context = subject.toContextEntries()
                known?.let {
                    hasKnownRevision = true
                    knownRevision = it
                }
            }

            val response = withRetries { config.transport.evaluate(request) }
            val now = config.clock()

            if (response.notModified) {
                mutex.withLock {
                    lastFingerprint = fingerprint
                    lastAsOfMillis = now
                }
                mutableState.value = FlagsState.Fresh(lastRevision, now)
                return@withTimeout RefreshResult.NotModified(lastRevision)
            }

            // An assignment that fails to map (unknown enum from a newer server, a value the
            // domain model cannot hold) is dropped, and that flag falls back to its code default.
            val resolved = response.assignments.mapNotNull { it.toDomainOrNull() }
            apply(resolved)
            mutex.withLock {
                lastFingerprint = fingerprint
                lastRevision = response.revision
                lastAsOfMillis = now
                hasSnapshot = true
            }
            mutableState.value = FlagsState.Fresh(response.revision, now)

            val bytes = encodeSnapshot(
                schemaName = schema.schemaName,
                subjectFingerprint = fingerprint,
                revision = response.revision,
                fetchedAtMillis = now,
                flags = resolved,
            )
            runCatching { config.cache.save(bytes) }
                .onFailure { config.logger("ktflags: writing the flag cache failed", it) }

            RefreshResult.Updated(response.revision)
        }
    }

    /** Retries only what ktbuf considers retriable, so a NOT_FOUND fails immediately. */
    private suspend fun <R> withRetries(block: suspend () -> R): R {
        var attempt = 0
        while (true) {
            try {
                return block()
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (t: Throwable) {
                val retriable = (t as? RpcResponseException)?.retriable() ?: false
                if (!retriable || attempt >= config.retryAttempts) throw t
                attempt++
            }
        }
    }

    private fun failure(t: Throwable): RefreshResult {
        val error = t.toFlagsError()
        config.logger("ktflags: refreshing flags failed (${error.code})", t)
        // The held values stay exactly as they were: a stale flag beats a wrong one.
        mutableState.value = FlagsState.Stale(lastRevision, lastAsOfMillis, error)
        return RefreshResult.Failed(error)
    }

    private fun apply(resolved: List<ResolvedFlag>) {
        mutableFlags.value = schema.materialize(resolved.toFlagValues())
    }

    private suspend fun currentSubject(): FlagSubject =
        pinnedSubject ?: FlagSubject(config.userIdProvider(), config.contextProvider())
}

private fun Throwable.toFlagsError(): FlagsError = when (this) {
    is RpcResponseException -> FlagsError(code, errorMessage, retriable(), this)
    // A timeout is the one non-RPC failure worth naming precisely, because it is the common one
    // and it is always worth retrying.
    is kotlinx.coroutines.TimeoutCancellationException ->
        FlagsError(Codes.DEADLINE_EXCEEDED, "flag refresh timed out", retriable = true, cause = this)
    else -> FlagsError(Codes.UNKNOWN, message ?: this::class.simpleName ?: "unknown error", true, this)
}
