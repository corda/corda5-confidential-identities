package com.r3.corda.lib.ci.services

import com.nhaarman.mockito_kotlin.any
import com.nhaarman.mockito_kotlin.doReturn
import com.nhaarman.mockito_kotlin.mock
import com.r3.corda.lib.ci.workflows.ChallengeResponse
import com.r3.corda.lib.ci.workflows.SignedKeyForAccount
import net.corda.v5.application.crypto.SignedData
import net.corda.v5.base.types.OpaqueBytes
import net.corda.v5.crypto.DigitalSignature
import net.corda.v5.crypto.SecureHash
import net.corda.v5.serialization.SerializedBytes
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow
import org.junit.jupiter.api.assertThrows
import java.security.PublicKey
import java.security.SignatureException

class SignedKeyServiceImplTest {

    private lateinit var signedKeyService: SignedKeyService

    @BeforeEach
    fun setUp() {
        signedKeyService = SignedKeyServiceImpl().apply {
            signatureVerifier = mock()
            hashingService = mock {
                on { hash(any<OpaqueBytes>()) } doReturn SecureHash.create("SHA-256:0123456789ABCDEF")
            }
        }
    }

    @Test
    fun `PublicKey for SignedKeyForAccount must match the PublicKey used to sign the included data`() {
        fun mockSignedKeyForAccount(key: PublicKey, signingKey: PublicKey): SignedKeyForAccount {
            val signature = mock<DigitalSignature.WithKey> {
                on { by } doReturn signingKey
            }
            val signedData = mock<SignedData<ChallengeResponse>> {
                on { sig } doReturn signature
                on { raw } doReturn mock<SerializedBytes<ChallengeResponse>>()
            }
            return mock {
                on { publicKey } doReturn key
                on { signedChallengeResponse } doReturn signedData
            }
        }


        fun callFunctionUnderTest(key: PublicKey, signingKey: PublicKey) =
            signedKeyService.verifySignedChallengeResponseSignature(
                mockSignedKeyForAccount(key, signingKey)
            )


        // Mock two different keys for testing
        val key1: PublicKey = mock()
        val key2: PublicKey = mock()

        // If the key that signed the challenge response data is different to the key returned in the
        // SignedKeyForAccount then no exception should be thrown.
        assertThrows<SignatureException> {
            callFunctionUnderTest(key1, key2)
        }

        // If the key that signed the challenge response data is the same as the key returned in the
        // SignedKeyForAccount then no exception should be thrown.
        assertDoesNotThrow {
            callFunctionUnderTest(key1, key1)
        }
    }
}