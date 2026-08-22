package com.latenighthack.ktflags.test

import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktflags.FlagSchema
import com.latenighthack.ktflags.FlagValues
import com.latenighthack.ktflags.client.FeatureFlags
import com.latenighthack.ktflags.client.FlagsError
import com.latenighthack.ktflags.client.FlagsState
import com.latenighthack.ktflags.client.RefreshResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [FeatureFlags] you can drive directly from a test.
 *
 * Before reaching for this, consider whether the component under test could just take `T` -- your
 * own flags data class -- as a parameter. `renderCheckout(AppFlags(newCheckout = true))` needs no
 * ktflags at all and is the better design. This is for the cases where a component genuinely owns
 * a provider and you need to move it mid-test.
 *
 * ```
 * val flags = FakeFeatureFlags(AppFlagsSchema)
 * flags.set { it.copy(darkMode = true) }
 * flags.failNextRefresh(Codes.UNAVAILABLE)   // exercise the offline path
 * ```
 */
public class FakeFeatureFlags<T : Any>(
    private val schema: FlagSchema<T>,
    initial: T = schema.defaults,
) : FeatureFlags<T> {

    private val mutableFlags = MutableStateFlow(initial)
    private val mutableState = MutableStateFlow<FlagsState>(FlagsState.Defaults)

    private var revision: Long = 0L
    private var nextFailure: FlagsError? = null

    /** How many times [refresh] has been called. */
    public var refreshCount: Int = 0
        private set

    override val flags: StateFlow<T> = mutableFlags.asStateFlow()

    override val state: StateFlow<FlagsState> = mutableState.asStateFlow()

    override fun current(): T = mutableFlags.value

    /** Replaces the current values and marks them fresh. */
    public fun set(values: T) {
        mutableFlags.value = values
        revision++
        mutableState.value = FlagsState.Fresh(revision, revision)
    }

    /** Mutates the current values, e.g. `set { it.copy(darkMode = true) }`. */
    public fun set(transform: (T) -> T) {
        set(transform(mutableFlags.value))
    }

    /** Sets values by flag key, going through the schema exactly as a real fetch would. */
    public fun setValues(values: FlagValues) {
        set(schema.materialize(values))
    }

    /** Makes the next [refresh] fail. Use to exercise offline and error handling. */
    public fun failNextRefresh(
        code: Codes = Codes.UNAVAILABLE,
        message: String = "fake failure",
    ) {
        nextFailure = FlagsError(code, message, retriable = code.retriable())
    }

    /** Puts the fake into the "loaded from cache, not confirmed with the server" state. */
    public fun markCached(revision: Long = 1L, asOfMillis: Long = 0L) {
        this.revision = revision
        mutableState.value = FlagsState.Cached(revision, asOfMillis)
    }

    override suspend fun refresh(): RefreshResult {
        refreshCount++
        nextFailure?.let { error ->
            nextFailure = null
            // Same contract as the real provider: values survive a failure.
            mutableState.value = FlagsState.Stale(revision, revision, error)
            return RefreshResult.Failed(error)
        }
        mutableState.value = FlagsState.Fresh(revision, revision)
        return RefreshResult.NotModified(revision)
    }
}
