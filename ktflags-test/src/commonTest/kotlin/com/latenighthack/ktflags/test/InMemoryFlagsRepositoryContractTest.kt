package com.latenighthack.ktflags.test

import com.latenighthack.ktflags.FlagsRepository
import com.latenighthack.ktflags.InMemoryFlagsRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The reference implementation held to the contract it defines.
 *
 * `InMemoryFlagsRepository` is what a consumer tests their server handlers against and what the
 * in-process harness runs on, so its behaviour has to be the same behaviour the SQL stores are
 * measured by -- not merely similar.
 */
class InMemoryFlagsRepositoryContractTest {
    private fun repo(): FlagsRepository = InMemoryFlagsRepository()

    @Test
    fun satisfies_the_write_contract() = runTest { assertWriteContract(::repo) }

    @Test
    fun satisfies_the_read_contract() = runTest { assertReadContract(::repo) }

    @Test
    fun satisfies_the_admin_contract() = runTest { assertAdminContract(::repo) }
}
