package com.r3.corda.lib.ci.workflows

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.eq
import com.nhaarman.mockito_kotlin.mock
import com.nhaarman.mockito_kotlin.whenever
import net.corda.testing.flow.utils.flowTest
import net.corda.v5.application.flows.UntrustworthyData
import net.corda.v5.application.services.crypto.HashingService
import net.corda.v5.crypto.SecureHash
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.lang.IllegalArgumentException
import kotlin.test.assertEquals

class RequestKeyFlowTest {

    /**
     * Verify that when we request an ownership claim from a party for a given public key, the counter party doesn't
     * return a different public key.
     *
     * Added to verify fix for security bug: ENT-6267
     */
    @Test
    fun `RequestKeyFlow throws exception if ownership claim for public key returns a different public key`() {
        flowTest<RequestKeyFlow> {
            val pubKeyA = ourPublicKey
            val pubKeyB = otherSidePublicKey

            // return a different public key than given to the flow
            val mockSignedKeyForAccount: SignedKeyForAccount = mock()
            doReturn(pubKeyA).whenever(mockSignedKeyForAccount).publicKey
            doReturn(UntrustworthyData(mockSignedKeyForAccount)).whenever(otherSideSession).sendAndReceive(eq(SignedKeyForAccount::class.java), any())

            val hashingService: HashingService = mock()
            doReturn(SecureHash.create("SHA-256:0123456789ABCDEF")).whenever(hashingService).randomHash(any())

            overrideDefaultInjectableMock(HashingService::class.java, hashingService)
            createFlow { RequestKeyFlow(otherSideSession, pubKeyB) }

            assertThrows<IllegalArgumentException> {
                flow.call()
            }.apply {
                assertEquals("PublicKey returned by counter-party was not the key we requested an ownership claim for.", message)
            }
        }
    }
}