package com.r3.corda.lib.ci.workflows

import net.corda.v5.application.crypto.SignedData
import net.corda.v5.base.annotations.CordaSerializable
import net.corda.v5.crypto.SecureHash
import java.security.PublicKey
import java.util.*

/**
 * Random number used for authentication of communication between flow sessions.
 */
typealias ChallengeResponse = SecureHash

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