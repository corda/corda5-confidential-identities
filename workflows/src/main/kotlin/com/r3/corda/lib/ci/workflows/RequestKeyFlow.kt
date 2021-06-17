package com.r3.corda.lib.ci.workflows

import com.r3.corda.lib.ci.services.SignedKeyService
import net.corda.v5.application.flows.Flow
import net.corda.v5.application.flows.FlowException
import net.corda.v5.application.flows.FlowSession
import net.corda.v5.application.flows.flowservices.FlowIdentity
import net.corda.v5.application.injection.CordaInject
import net.corda.v5.application.identity.AbstractParty
import net.corda.v5.application.identity.AnonymousParty
import net.corda.v5.application.identity.CordaX500Name
import net.corda.v5.application.identity.Party
import net.corda.v5.application.identity.PartyAndReference
import net.corda.v5.application.services.IdentityService
import net.corda.v5.application.services.KeyManagementService
import net.corda.v5.application.services.serialization.SerializationService
import net.corda.v5.application.services.serialization.deserialize
import net.corda.v5.application.flows.unwrap
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.types.OpaqueBytes
import net.corda.v5.base.util.contextLogger
import net.corda.v5.crypto.SecureHash
import net.corda.v5.crypto.toStringShort
import net.corda.v5.ledger.UniqueIdentifier
import java.security.PublicKey
import java.util.*

/**
 * This flow registers a mapping in the [IdentityService] between a [PublicKey] and a
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
    private val key: PublicKey?
) : Flow<AnonymousParty> {

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

    private companion object {
        const val REQUESTING_KEY = "Requesting a public key"
        const val VERIFYING_KEY = "Verifying counterparty's signature"
        const val KEY_VERIFIED = "Signature is correct"
        const val VERIFYING_CHALLENGE_RESPONSE = "Verifying the received SHA-256 matches the original that was sent"
        const val CHALLENGE_RESPONSE_VERIFIED = "SHA-256 is correct"

        val logger = contextLogger()

        fun debug(msg: String) = logger.debug("${this::class.java.name}: $msg")
    }

    @CordaInject
    lateinit var identityService: IdentityService

    @CordaInject
    lateinit var serializationService: SerializationService

    @CordaInject
    lateinit var signedKeyService: SignedKeyService

    @Suspendable
    override fun call(): AnonymousParty {
        debug(REQUESTING_KEY)
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
        debug(VERIFYING_KEY)
        signedKeyService.verifySignedChallengeResponseSignature(signedKeyForAccount)
        debug(KEY_VERIFIED)
        // Ensure the hash of both challenge response parameters matches the received hashed function
        debug(VERIFYING_CHALLENGE_RESPONSE)
        val additionalParam = signedKeyForAccount.additionalChallengeResponseParam
        val resultOfHashedParameters = challengeResponseParam.hashConcat(additionalParam)
        require(resultOfHashedParameters == serializationService.deserialize(signedKeyForAccount.signedChallengeResponse.raw)) {
            "Challenge response invalid"
        }
        debug(CHALLENGE_RESPONSE_VERIFIED)
        // Flow sessions can only be opened with parties in the networkMapCache so we can be assured this is a valid party
        val counterParty = session.counterparty
        val newKey = signedKeyForAccount.publicKey
        // Store a mapping of the key to the x500 name
        when (uuid) {
            null -> identityService.registerKey(newKey, counterParty.name)
            else -> identityService.registerKey(newKey, counterParty.name, uuid)
        }
        return AnonymousPartyImpl(newKey)
    }
}

/**
 * Responder flow to [RequestKeyFlow].
 */
class ProvideKeyFlow(private val otherSession: FlowSession) : Flow<AnonymousParty> {
    @CordaInject
    lateinit var keyManagementService: KeyManagementService

    @CordaInject
    lateinit var identityService: IdentityService

    @CordaInject
    lateinit var flowIdentity: FlowIdentity

    @CordaInject
    lateinit var signedKeyService: SignedKeyService

    @Suspendable
    override fun call(): AnonymousParty {
        val key = when (
            val request = otherSession.receive<SendRequestForKeyMapping>().unwrap { it }
        ) {
            is RequestKeyForUUID -> {
                val signedKey = signedKeyService.createSignedOwnershipClaimFromUUID(
                    challengeResponseParam = request.challengeResponseParam,
                    uuid = request.externalId
                )
                otherSession.send(signedKey)
                // No need to call RegisterKey as it's done by createSignedOwnershipClaimFromUUID.
                signedKey.publicKey
            }
            is RequestForKnownKey -> {
                val signedKey = signedKeyService.createSignedOwnershipClaimFromKnownKey(
                    challengeResponseParam = request.challengeResponseParam,
                    knownKey = request.knownKey
                )
                otherSession.send(signedKey)
                // Double check that the key has not already been registered to another node.
                try {
                    identityService.registerKey(request.knownKey, flowIdentity.ourIdentity.name)
                } catch (e: Exception) {
                    throw FlowException(
                        "Could not register a new key for party: ${flowIdentity.ourIdentity.name} as the provided public " +
                                "key is already registered or registered to a different party."
                    )
                }
                request.knownKey
            }
            is RequestFreshKey -> {
                // No need to call RegisterKey as it's done by keyManagementService.freshKey.
                val newKey = keyManagementService.freshKey()
                val signedKey = signedKeyService.createSignedOwnershipClaimFromKnownKey(
                    challengeResponseParam = request.challengeResponseParam,
                    knownKey = newKey
                )
                otherSession.send(signedKey)
                newKey
            }
        }

        return AnonymousPartyImpl(key)
    }
}

private class AnonymousPartyImpl(override val owningKey: PublicKey) : AnonymousParty {
    override fun nameOrNull(): CordaX500Name? = null
    override fun ref(bytes: OpaqueBytes): PartyAndReference = PartyAndReference(this, bytes)
    override fun toString() = "Anonymous(${owningKey.toStringShort()})"

    /** Anonymised parties do not include any detail apart from owning key, so equality is dependent solely on the key */
    override fun equals(other: Any?): Boolean = other === this || other is AbstractParty && other.owningKey == owningKey

    override fun hashCode(): Int = owningKey.hashCode()
}
