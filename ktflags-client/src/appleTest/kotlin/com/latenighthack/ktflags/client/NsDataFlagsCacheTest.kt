package com.latenighthack.ktflags.client

import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID

/**
 * Runs the shared cache contract against the real Foundation-backed implementation.
 *
 * The ByteArray/NSData interop here is the only hand-written pointer code in ktflags, so it gets
 * exercised on a real simulator/host rather than merely compiled.
 */
class NsDataFlagsCacheTest : FlagsCacheContract() {
    override fun newCache(): FlagsCache {
        val dir = NSTemporaryDirectory() + "ktflags-" + NSUUID().UUIDString()
        return defaultFlagsCache(name = "snapshot", directory = dir)
    }
}
