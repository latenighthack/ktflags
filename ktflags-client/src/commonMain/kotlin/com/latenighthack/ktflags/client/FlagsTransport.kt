package com.latenighthack.ktflags.client

import com.latenighthack.ktbuf.net.RpcClient
import com.latenighthack.ktbuf.net.RpcMethodSpecifier
import com.latenighthack.ktbuf.net.RpcResponse
import com.latenighthack.ktbuf.net.RpcServerStream
import com.latenighthack.ktbuf.proto.Codes
import com.latenighthack.ktflags.proto.v1.EvaluateRequest
import com.latenighthack.ktflags.proto.v1.EvaluateResponse
import com.latenighthack.ktflags.proto.v1.FlagsService
import com.latenighthack.ktflags.proto.v1.FlagsServiceRpc

/**
 * The single thing [FeatureFlagsProvider] needs from the outside world.
 *
 * This exists as its own interface rather than the provider taking an [RpcClient] because ktbuf's
 * generated `LocalFlagsServiceRpc` -- the in-process, zero-network implementation you get for free
 * from the server module -- implements the *service* interface, not [RpcClient]. Narrowing the
 * dependency to one method makes that a one-line substitution in tests, and makes a hand-written
 * stub trivial.
 */
public fun interface FlagsTransport {
    public suspend fun evaluate(request: EvaluateRequest): EvaluateResponse

    public companion object {
        /** Wraps any [FlagsService], including the generated in-process `LocalFlagsServiceRpc`. */
        public fun of(service: FlagsService): FlagsTransport =
            FlagsTransport { service.evaluate(it) }

        /** Wraps a raw ktbuf transport, e.g. `HttpRpcClient("flags.example.com:8080")`. */
        public fun rpc(client: RpcClient): FlagsTransport = of(FlagsServiceRpc(client))
    }
}

/** Why a refresh failed. Mirrors ktbuf's gRPC-style status codes. */
public data class FlagsError(
    public val code: Codes,
    public val message: String,
    public val retriable: Boolean,
    public val cause: Throwable? = null,
)

/**
 * Adds headers to every unary call.
 *
 * ktbuf's generated stubs hardcode the unary header map to empty and expose only a query-parameter
 * hook, so authenticating a flag client means decorating the transport. Same shape as kitkit's
 * `HeaderInjectingRpcClient`, but the supplier is `suspend` so a rotating token can be fetched per
 * call rather than captured once at construction.
 */
internal class HeaderProvidingRpcClient(
    private val delegate: RpcClient,
    private val headers: suspend () -> Map<String, String>,
) : RpcClient {
    override suspend fun unaryCall(
        method: RpcMethodSpecifier,
        headers: Map<String, String>,
        request: ByteArray,
    ): RpcResponse = delegate.unaryCall(method, headers + this.headers(), request)

    override suspend fun serverStreamingCall(
        method: RpcMethodSpecifier,
        block: suspend RpcServerStream.() -> Unit,
        readyCallback: () -> Unit,
    ) {
        // ktflags is unary-only, so this is never reached; pass it through rather than throw so
        // the decorator stays a faithful RpcClient if someone reuses it.
        delegate.serverStreamingCall(method, block, readyCallback)
    }
}
