package com.r3.corda.lib.ci

import co.paralleluniverse.fibers.Suspendable
import net.corda.core.contracts.UniqueIdentifier
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowException
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.identity.AnonymousParty
import net.corda.core.identity.Party
import net.corda.core.serialization.deserialize
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.unwrap
import java.security.PublicKey
import java.util.*

/**
 * This flow registers a mapping in the [net.corda.core.node.services.IdentityService] between a [PublicKey] and a
 * [Party]. It can generate a new key pair for a given [UUID] and register the new key mapping, or a known [PublicKey]
 * can be supplied to the flow which will register a mapping between this key and the requesting party.
 *
 * The generation of the [SignedKeyForAccount] is delegated to the counter-party which concatenates the original
 * [ChallengeResponse] with its own [ChallengeResponse] and signs over the concatenated hash before sending this value
 * and the [PublicKey] and sends it back to the requesting node.
 *
 * The requesting node verifies the signature on the [ChallengeResponse] and verifies the concatenated
 * [ChallengeResponse] is the same as the one received from the counter-party.
 *
 * @property session the session of the node to request a new key from
 * @property uuid the accountId/externalId for the other node to generate a new key for
 * @property key an existing key a proof is being requested for (this is usually null)
 */
class RequestKeyFlow
private constructor(
        private val session: FlowSession,
        private val uuid: UUID?,
        private val key: PublicKey?) : FlowLogic<AnonymousParty>() {

    /**
     * For requesting a new key from a counter-party and have that counter-party assign it to a specified account.
     *
     * @param session existing flow session for the counterparty
     * @param uuid account id for the account hosted on the counterparty node
     */
    constructor(session: FlowSession, uuid: UUID) : this(session, uuid, null)

    /**
     * For requesting an ownership claim that the specified public key was generated by the node corresponding to the
     * supplied flow session.
     *
     * @param session existing flow session for the counterparty
     * @param key the public key to request an ownership claim for
     */
    constructor(session: FlowSession, key: PublicKey) : this(session, UniqueIdentifier().id, key)

    /**
     * For requesting a new key from a counter-party.
     *
     * @param session existing flow session for the counterparty
     */
    constructor(session: FlowSession) : this(session, null, null)

    companion object {
        object REQUESTING_KEY : ProgressTracker.Step("Requesting a public key")
        object VERIFYING_KEY : ProgressTracker.Step("Verifying counterparty's signature")
        object KEY_VERIFIED : ProgressTracker.Step("Signature is correct")
        object VERIFYING_CHALLENGE_RESPONSE : ProgressTracker.Step("Verifying the received SHA-256 matches the original that was sent")
        object CHALLENGE_RESPONSE_VERIFIED : ProgressTracker.Step("SHA-256 is correct")

        @JvmStatic
        fun tracker(): ProgressTracker = ProgressTracker(
                REQUESTING_KEY,
                VERIFYING_KEY,
                KEY_VERIFIED,
                VERIFYING_CHALLENGE_RESPONSE,
                CHALLENGE_RESPONSE_VERIFIED
        )
    }

    override val progressTracker = tracker()

    @Suspendable
    @Throws(FlowException::class)
    override fun call(): AnonymousParty {
        progressTracker.currentStep = REQUESTING_KEY
        val challengeResponseParam = SecureHash.randomSHA256()
        // Handle whether a key is already specified or not and whether a UUID is specified, or not.
        val requestKey = when {
            key == null && uuid != null -> RequestKeyForUUID(challengeResponseParam, uuid)
            key != null -> RequestForKnownKey(challengeResponseParam, key)
            else -> RequestFreshKey(challengeResponseParam)
        }
        // Either get back a signed key or a flow exception is thrown.
        val signedKeyForAccount = session.sendAndReceive<SignedKeyForAccount>(requestKey).unwrap { it }
        // We need to verify the signature of the response and check that the payload is equal to what we expect.
        progressTracker.currentStep = VERIFYING_KEY
        verifySignedChallengeResponseSignature(signedKeyForAccount)
        progressTracker.currentStep = KEY_VERIFIED
        // Ensure the hash of both challenge response parameters matches the received hashed function
        progressTracker.currentStep = VERIFYING_CHALLENGE_RESPONSE
        val additionalParam = signedKeyForAccount.additionalChallengeResponseParam
        val resultOfHashedParameters = challengeResponseParam.hashConcat(additionalParam)
        require(resultOfHashedParameters == signedKeyForAccount.signedChallengeResponse.raw.deserialize()) {
            "Challenge response invalid"
        }
        progressTracker.currentStep = CHALLENGE_RESPONSE_VERIFIED
        // Flow sessions can only be opened with parties in the networkMapCache so we can be assured this is a valid party
        val counterParty = session.counterparty
        val newKey = signedKeyForAccount.publicKey
        // Store a mapping of the key to the x500 name
        serviceHub.identityService.registerKey(newKey, counterParty, uuid)
        return AnonymousParty(newKey)
    }
}

/**
 * Responder flow to [RequestKeyFlow].
 */
class ProvideKeyFlow(private val otherSession: FlowSession) : FlowLogic<AnonymousParty>() {
    @Suspendable
    override fun call(): AnonymousParty {
        val request = otherSession.receive<SendRequestForKeyMapping>().unwrap { it }
        val key = when (request) {
            is RequestKeyForUUID -> {
                val signedKey = serviceHub.createSignedOwnershipClaimFromUUID(
                        challengeResponseParam = request.challengeResponseParam,
                        uuid = request.externalId
                )
                otherSession.send(signedKey)
                // No need to call RegisterKey as it's done by createSignedOwnershipClaimFromUUID.
                signedKey.publicKey
            }
            is RequestForKnownKey -> {
                val signedKey = serviceHub.createSignedOwnershipClaimFromKnownKey(
                        challengeResponseParam = request.challengeResponseParam,
                        knownKey = request.knownKey
                )
                otherSession.send(signedKey)
                // Double check that the key has not already been registered to another node.
                try {
                    serviceHub.identityService.registerKey(request.knownKey, ourIdentity)
                } catch (e: Exception) {
                    throw FlowException("Could not register a new key for party: $ourIdentity as the provided public " +
                            "key is already registered or registered to a different party.")
                }
                request.knownKey
            }
            is RequestFreshKey -> {
                // No need to call RegisterKey as it's done by keyManagementService.freshKey.
                val newKey = serviceHub.keyManagementService.freshKey()
                val signedKey = serviceHub.createSignedOwnershipClaimFromKnownKey(
                        challengeResponseParam = request.challengeResponseParam,
                        knownKey = newKey
                )
                otherSession.send(signedKey)
                newKey
            }
        }

        return AnonymousParty(key)
    }
}
