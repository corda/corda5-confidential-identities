package com.r3.corda.lib.ci.services

import com.r3.corda.lib.ci.workflows.ChallengeResponse
import com.r3.corda.lib.ci.workflows.SignedKeyForAccount
import net.corda.v5.application.crypto.SignedData
import net.corda.v5.application.flows.flowservices.dependencies.CordaFlowInjectable
import net.corda.v5.application.flows.flowservices.dependencies.CordaInject
import net.corda.v5.application.node.services.CordaService
import net.corda.v5.application.node.services.KeyManagementService
import net.corda.v5.application.node.services.TransactionSignatureVerificationService
import net.corda.v5.application.serialization.SerializationService
import net.corda.v5.base.annotations.CordaInternal
import net.corda.v5.base.annotations.VisibleForTesting
import net.corda.v5.crypto.SecureHash
import net.corda.v5.crypto.hash
import java.security.PublicKey
import java.security.SignatureException
import java.util.*

class SignedKeyService : CordaService, CordaFlowInjectable {

    @CordaInject
    lateinit var keyManagementService: KeyManagementService

    @CordaInject
    lateinit var serializationService: SerializationService

    @CordaInject
    lateinit var signatureVerifier: TransactionSignatureVerificationService

    /**
     * Generates a fresh key pair and stores the mapping to the [UUID]. This key is used construct the [SignedKeyForAccount]
     * containing the new [PublicKey], signed data structure and additional [ChallengeResponse] parameter required for
     * verification by the counter-party.
     *
     * @param challengeResponseParam The random number used to prevent replay attacks
     * @param uuid The external ID to be associated with the new [PublicKey]
     */
    @CordaInternal
    @VisibleForTesting
    fun createSignedOwnershipClaimFromUUID(
        challengeResponseParam: ChallengeResponse,
        uuid: UUID
    ): SignedKeyForAccount {
        val newKey = keyManagementService.freshKey(uuid)
        return concatChallengeResponseAndSign(challengeResponseParam, newKey)
    }

    /**
     * Returns the [SignedKeyForAccount] containing the known [PublicKey], signed data structure and additional
     * [ChallengeResponse] parameter required for verification by the counter-party.
     *
     * @param challengeResponseParam The random number used to prevent replay attacks
     * @param knownKey The [PublicKey] to sign the challengeResponseId
     */
    @CordaInternal
    @VisibleForTesting
    fun createSignedOwnershipClaimFromKnownKey(
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
        val additionalParameter = SecureHash.randomSHA256()
        val hashOfBothParameters = challengeResponseParam.hashConcat(additionalParameter)
        val keySig = keyManagementService.sign(serializationService.serialize(hashOfBothParameters).hash.bytes, key)
        // Sign the challengeResponse with the newly generated key
        val signedData = SignedData(serializationService.serialize(hashOfBothParameters), keySig)
        return SignedKeyForAccount(key, signedData, additionalParameter)
    }

    /**
     * Verifies the signature on the used to sign the [ChallengeResponse].
     */
    @CordaInternal
    @VisibleForTesting
    fun verifySignedChallengeResponseSignature(signedKeyForAccount: SignedKeyForAccount) {
        try {
            with(signedKeyForAccount.signedChallengeResponse) {
                signatureVerifier.verify(sig.by, sig.bytes, raw.hash.bytes)
            }
        } catch (ex: SignatureException) {
            throw SignatureException("The signature on the object does not match that of the expected public key signature", ex)
        }
    }
}