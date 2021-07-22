package com.r3.corda.lib.ci.services

import com.r3.corda.lib.ci.workflows.ChallengeResponse
import com.r3.corda.lib.ci.workflows.SignedKeyForAccount
import net.corda.v5.application.crypto.SignedData
import net.corda.v5.application.injection.CordaInject
import net.corda.v5.application.services.crypto.DigitalSignatureVerificationService
import net.corda.v5.application.services.crypto.HashingService
import net.corda.v5.application.services.crypto.KeyManagementService
import net.corda.v5.application.services.serialization.SerializationService
import net.corda.v5.base.annotations.CordaInternal
import net.corda.v5.base.annotations.VisibleForTesting
import net.corda.v5.crypto.DigestAlgorithmName
import java.security.PublicKey
import java.security.SignatureException
import java.util.*

class SignedKeyServiceImpl : SignedKeyService {

    @CordaInject
    lateinit var keyManagementService: KeyManagementService

    @CordaInject
    lateinit var serializationService: SerializationService

    @CordaInject
    lateinit var signatureVerifier: DigitalSignatureVerificationService

    @CordaInject
    lateinit var hashingService: HashingService

    @CordaInternal
    @VisibleForTesting
    override fun createSignedOwnershipClaimFromUUID(
        challengeResponseParam: ChallengeResponse,
        uuid: UUID
    ): SignedKeyForAccount {
        val newKey = keyManagementService.freshKey(uuid)
        return concatChallengeResponseAndSign(challengeResponseParam, newKey)
    }

    @CordaInternal
    @VisibleForTesting
    override fun createSignedOwnershipClaimFromKnownKey(
        challengeResponseParam: ChallengeResponse,
        knownKey: PublicKey
    ): SignedKeyForAccount {
        return concatChallengeResponseAndSign(challengeResponseParam, knownKey)
    }

    /**
     * Generate a second [ChallengeResponse] parameter and concatenate this with the initial one that was sent. We sign over
     * the concatenated [ChallengeResponse] using the new [PublicKey]. The method returns the [SignedKeyForAccount] containing
     * the new [PublicKey], signed data structure and additional [ChallengeResponse] parameter.
     */
    private fun concatChallengeResponseAndSign(
        challengeResponseParam: ChallengeResponse,
        key: PublicKey
    ): SignedKeyForAccount {
        // Introduce a second parameter to prevent signing over some malicious transaction ID which may be in the form of a SHA256 hash
        val additionalParameter = hashingService.randomHash(DigestAlgorithmName.SHA2_256)
        val hashOfBothParameters = hashingService.concatenate(challengeResponseParam, additionalParameter)
        val keySig = keyManagementService.sign(hashingService.hash(serializationService.serialize(hashOfBothParameters)).bytes, key)
        // Sign the challengeResponse with the newly generated key
        val signedData = SignedData(serializationService.serialize(hashOfBothParameters), keySig)
        return SignedKeyForAccount(key, signedData, additionalParameter)
    }

    @CordaInternal
    @VisibleForTesting
    override fun verifySignedChallengeResponseSignature(signedKeyForAccount: SignedKeyForAccount) {
        try {
            with(signedKeyForAccount.signedChallengeResponse) {
                signatureVerifier.verify(sig.by, sig.bytes, hashingService.hash(raw).bytes)
            }
        } catch (ex: SignatureException) {
            throw SignatureException(
                "The signature on the object does not match that of the expected public key signature",
                ex
            )
        }
    }
}