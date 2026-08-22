package com.latenighthack.ktflags.client

import com.latenighthack.ktbuf.net.RpcResponseException
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktflags.FlagDefinition
import com.latenighthack.ktflags.FlagSchema
import com.latenighthack.ktflags.FlagScope
import com.latenighthack.ktflags.FlagType
import com.latenighthack.ktflags.FlagValue
import com.latenighthack.ktflags.FlagValues
import com.latenighthack.ktflags.ResolvedFlag
import com.latenighthack.ktflags.ValueSource
import com.latenighthack.ktflags.proto.toProto
import com.latenighthack.ktflags.proto.v1.EvaluateRequest
import com.latenighthack.ktflags.proto.v1.EvaluateResponse

internal data class TestFlags(
    val newCheckout: Boolean = false,
    val darkMode: Boolean = false,
    val variant: String = "control",
    val maxItems: Int = 10,
)

internal object TestFlagsSchema : FlagSchema<TestFlags> {
    override val schemaName: String = "TestFlags"

    override val defaults: TestFlags = TestFlags()

    override val definitions: List<FlagDefinition> = listOf(
        FlagDefinition(
            "newCheckout", FlagScope.SERVICE, FlagType.BOOLEAN,
            FlagValue.BoolValue(defaults.newCheckout),
        ),
        FlagDefinition(
            "darkMode", FlagScope.USER, FlagType.BOOLEAN,
            FlagValue.BoolValue(defaults.darkMode),
        ),
        FlagDefinition(
            "variant", FlagScope.USER, FlagType.STRING,
            FlagValue.StringValue(defaults.variant),
        ),
        FlagDefinition(
            "maxItems", FlagScope.SERVICE, FlagType.INT,
            FlagValue.IntValue(defaults.maxItems),
        ),
    )

    override fun materialize(values: FlagValues): TestFlags = TestFlags(
        newCheckout = values.boolean("newCheckout", defaults.newCheckout),
        darkMode = values.boolean("darkMode", defaults.darkMode),
        variant = values.string("variant", defaults.variant),
        maxItems = values.int("maxItems", defaults.maxItems),
    )
}

/** A scriptable [FlagsTransport] that records what it was asked for. */
internal class ScriptedTransport(
    private var responder: suspend (EvaluateRequest) -> EvaluateResponse,
) : FlagsTransport {
    val requests = mutableListOf<EvaluateRequest>()
    var callCount: Int = 0
        private set

    override suspend fun evaluate(request: EvaluateRequest): EvaluateResponse {
        callCount++
        requests.add(request)
        return responder(request)
    }

    fun respondWith(responder: suspend (EvaluateRequest) -> EvaluateResponse) {
        this.responder = responder
    }

    companion object {
        fun returning(
            revision: Long = 1L,
            vararg flags: Pair<String, FlagValue>,
        ): ScriptedTransport = ScriptedTransport { evaluateResponse(revision, *flags) }

        fun failing(code: Codes): ScriptedTransport = ScriptedTransport {
            throw RpcResponseException("/api/x", "POST", code, "scripted failure")
        }
    }
}

internal fun evaluateResponse(
    revision: Long = 1L,
    vararg flags: Pair<String, FlagValue>,
): EvaluateResponse = EvaluateResponse {
    this.revision = revision
    evaluatedAtMillis = 1_000L
    assignments = flags.map { (key, value) ->
        ResolvedFlag(key, value, ValueSource.SERVICE_DEFAULT).toProto()
    }
}

internal fun notModifiedResponse(revision: Long): EvaluateResponse = EvaluateResponse {
    notModified = true
    this.revision = revision
}

/** A config wired for tests: no network, no filesystem, no retry backoff. */
internal fun testConfig(
    transport: FlagsTransport,
    cache: FlagsCache = InMemoryFlagsCache(),
    userId: String? = null,
    context: Map<String, String> = emptyMap(),
    refreshOnStart: Boolean = true,
    retryAttempts: Int = 0,
    timeoutMillis: Long = 5_000,
): FeatureFlagsConfig = FeatureFlagsConfig {
    this.transport = transport
    persistence = cache
    userIdProvider = { userId }
    contextProvider = { context }
    this.refreshOnStart = refreshOnStart
    this.retryAttempts = retryAttempts
    requestTimeoutMillis = timeoutMillis
    clock = { 1_000L }
}
