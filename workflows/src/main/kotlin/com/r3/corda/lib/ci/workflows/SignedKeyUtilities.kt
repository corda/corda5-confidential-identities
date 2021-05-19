package com.r3.corda.lib.ci.workflows

import net.corda.v5.application.crypto.SignedData
import net.corda.v5.application.node.services.KeyManagementService
import net.corda.v5.application.serialization.serialize
import net.corda.v5.base.annotations.CordaInternal
import net.corda.v5.base.annotations.CordaSerializable
import net.corda.v5.base.annotations.VisibleForTesting
import net.corda.v5.crypto.SecureHash
import net.corda.v5.crypto.SignatureVerificationService
import net.corda.v5.crypto.hash
import java.security.PublicKey
import java.security.SignatureException
import java.util.*

/**
 * Random number used for authentication of communication between flow sessions.
 */
typealias ChallengeResponse = SecureHash.SHA256

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
fun KeyManagementService.createSignedOwnershipClaimFromUUID(
    challengeResponseParam: ChallengeResponse,
    uuid: UUID
): SignedKeyForAccount {
    val newKey = freshKey(uuid)
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
fun KeyManagementService.createSignedOwnershipClaimFromKnownKey(
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
private fun KeyManagementService.concatChallengeResponseAndSign(
    challengeResponseParam: ChallengeResponse,
    key: PublicKey
): SignedKeyForAccount {
    // Introduce a second parameter to prevent signing over some malicious transaction ID which may be in the form of a SHA256 hash
    val additionalParameter = SecureHash.randomSHA256()
    val hashOfBothParameters = challengeResponseParam.hashConcat(additionalParameter)
    val keySig = sign(hashOfBothParameters.serialize().hash.bytes, key)
    // Sign the challengeResponse with the newly generated key
    val signedData = SignedData(hashOfBothParameters.serialize(), keySig)
    return SignedKeyForAccount(key, signedData, additionalParameter)
}

/**
 * Verifies the signature on the used to sign the [ChallengeResponse].
 */
@CordaInternal
@VisibleForTesting
fun verifySignedChallengeResponseSignature(signatureVerifier: SignatureVerificationService, signedKeyForAccount: SignedKeyForAccount) {
    try {
        with(signedKeyForAccount.signedChallengeResponse) {
            signatureVerifier.verify(sig.by, sig.bytes, raw.hash.bytes)
        }
    } catch (ex: SignatureException) {
        throw SignatureException("The signature on the object does not match that of the expected public key signature", ex)
    }
}

/**
 * Parent class of all classes that can be shared between flow sessions when requesting [PublicKey] to [Party] mappings in
 * the context of confidential identities.
 */
@CordaSerializable
sealed class SendRequestForKeyMapping

/**
 * Object to be shared between flow sessions when a new [PublicKey] is required to be registered against a given externalId
 * provided by the [UUID].
 *
 * @param challengeResponseParam Arbitrary number that can only be used once in a cryptographic communication
 * @param externalId The external ID for a new key to be mapped to
 */
data class RequestKeyForUUID(val challengeResponseParam: ChallengeResponse, val externalId: UUID) : SendRequestForKeyMapping()

/**
 * Object to be shared between flow sessions when a node wants to register a mapping between a known [PublicKey] and a [Party].
 *
 * @param challengeResponseParam Arbitrary number that can only be used once in a cryptographic communication
 * @param knownKey The [PublicKey] to be mapped to the node party
 */
data class RequestForKnownKey(val challengeResponseParam: ChallengeResponse, val knownKey: PublicKey) : SendRequestForKeyMapping()

/**
 * Object to be shared between flow sessions when a node wants to request a new [PublicKey] to be mapped against a known [Party].
 *
 * @param challengeResponseParam Arbitrary number that can only be used once in a cryptographic communication
 */
data class RequestFreshKey(val challengeResponseParam: ChallengeResponse) : SendRequestForKeyMapping()

/**
 * Object that holds a [PublicKey], the serialized and signed [ChallengeResponse] and the additional [ChallengeResponse]
 * parameter provided by a counter-party.
 *
 * @param publicKey The public key that was used to generate the signedChallengeResponse
 * @param signedChallengeResponse The serialized and signed [ChallengeResponse]
 * @param additionalChallengeResponseParam The additional parameter provided by the key generating party to prevent
 *        signing over a malicious transaction
 */
@CordaSerializable
data class SignedKeyForAccount(
    val publicKey: PublicKey,
    val signedChallengeResponse: SignedData<ChallengeResponse>,
    val additionalChallengeResponseParam: ChallengeResponse
)