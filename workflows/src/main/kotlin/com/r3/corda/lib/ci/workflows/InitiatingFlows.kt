package com.r3.corda.lib.ci.workflows

import net.corda.v5.application.flows.Flow
import net.corda.v5.application.flows.FlowSession
import net.corda.v5.application.flows.InitiatedBy
import net.corda.v5.application.flows.InitiatingFlow
import net.corda.v5.application.flows.StartableByRPC
import net.corda.v5.application.flows.flowservices.FlowEngine
import net.corda.v5.application.flows.flowservices.FlowMessaging
import net.corda.v5.application.flows.flowservices.dependencies.CordaInject
import net.corda.v5.application.identity.AbstractParty
import net.corda.v5.application.identity.AnonymousParty
import net.corda.v5.application.identity.Party
import net.corda.v5.application.node.services.IdentityService
import net.corda.v5.application.node.services.KeyManagementService
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.ledger.transactions.WireTransaction
import java.security.PublicKey
import java.util.*

/**
 * This flow registers a mapping in the [IdentityService] between a [PublicKey] and a
 * [Party]. It generates a new key pair for a given [UUID] and register's the new key mapping.
 *
 * The generation of the [SignedKeyForAccount] is delegated to the counter-party which concatenates the original
 * [ChallengeResponse] with its own [ChallengeResponse] and signs over the concatenated hash before sending this value
 * and the [PublicKey] and sends it back to the requesting node. The requesting node verifies the signature on the
 * [ChallengeResponse] and verifies the concatenated [ChallengeResponse] is the same as the one received from the
 * counter-party.
 */
@StartableByRPC
@InitiatingFlow
class RequestKeyForUUIDInitiator(private val otherParty: Party, private val uuid: UUID) : AbstractInitiatingFlow<AnonymousParty>() {
    @Suspendable
    override fun call(): AnonymousParty {
        return subFlow(RequestKeyFlow(initiateFlow(otherParty), uuid))
    }
}

/**
 * Responder flow to [RequestKeyForUUID].
 */
@InitiatedBy(RequestKeyForUUIDInitiator::class)
class RequestKeyForUUIDResponder(private val otherSession: FlowSession) : AbstractInitiatingFlow<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(ProvideKeyFlow(otherSession))
    }
}

/**
 * This flow registers a mapping in the [IdentityService] between a known [PublicKey] and a [Party].
 *
 * The generation of the [SignedKeyForAccount] is delegated to the counter-party which concatenates the original
 * [ChallengeResponse] with its own [ChallengeResponse] and signs over the concatenated hash before sending this value
 * and the [PublicKey] and sends it back to the requesting node. The requesting node verifies the signature on the
 * [ChallengeResponse] and verifies the concatenated [ChallengeResponse] is the same as the one received from the
 * counter-party.
 */
@StartableByRPC
@InitiatingFlow
class VerifyAndAddKey(private val otherParty: Party, private val key: PublicKey) : AbstractInitiatingFlow<AnonymousParty>() {
    @Suspendable
    override fun call(): AnonymousParty {
        return subFlow(RequestKeyFlow(initiateFlow(otherParty), key))
    }
}

/**
 * Responder flow to [VerifyAndAddKey].
 */
@InitiatedBy(VerifyAndAddKey::class)
class VerifyAndAddKeyResponder(private val otherSession: FlowSession) : AbstractInitiatingFlow<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(ProvideKeyFlow(otherSession))
    }
}

/**
 * This flow registers a mapping in the [IdentityService] between a [PublicKey] and a [Party]. The counter-party will
 * generate a fresh [PublicKey] using the [KeyManagementService].
 *
 * The generation of the [SignedKeyForAccount] is delegated to the counter-party which concatenates the original
 * [ChallengeResponse] with its own [ChallengeResponse] and signs over the concatenated hash before sending this value
 * and the [PublicKey] and sends it back to the requesting node. The requesting node verifies the signature on the
 * [ChallengeResponse] and verifies the concatenated [ChallengeResponse] is the same as the one received from the
 * counter-party.
 */
@StartableByRPC
@InitiatingFlow
class RequestKey(private val otherParty: Party) : AbstractInitiatingFlow<AnonymousParty>() {
    @Suspendable
    override fun call(): AnonymousParty {
        return subFlow(RequestKeyFlow(initiateFlow(otherParty)))
    }
}

/**
 * Responder flow to [RequestKey].
 */
@InitiatedBy(RequestKey::class)
class RequestKeyResponder(private val otherSession: FlowSession) : AbstractInitiatingFlow<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(ProvideKeyFlow(otherSession))
    }
}

/**
 * This flow allows a node to share the [PublicKey] to [Party] mapping data of unknown parties present in a given
 * transaction. Alternatively, the initiating party can provide a list of [AbstractParty] they wish to synchronise the
 * [PublicKey] to [Party] mappings. The initiating sends a list of confidential identities to the counter-party who
 * attempts to resolve them. Parties that cannot be resolved are returned to the initiating node.
 *
 * The counter-party will request a new key mapping for each of the unresolved identities by calling [RequestKeyFlow] as
 * an inline flow.
 */
@InitiatingFlow
@StartableByRPC
class SyncKeyMappingInitiator
private constructor(
    private val otherParty: Party,
    private val tx: WireTransaction?,
    private val identitiesToSync: List<AbstractParty>?
) : AbstractInitiatingFlow<Unit>() {
    constructor(otherParty: Party, tx: WireTransaction) : this(otherParty, tx, null)
    constructor(otherParty: Party, identitiesToSync: List<AbstractParty>) : this(otherParty, null, identitiesToSync)

    @Suspendable
    override fun call() {
        if (tx != null) {
            subFlow(SyncKeyMappingFlow(initiateFlow(otherParty), tx))
        } else {
            subFlow(
                SyncKeyMappingFlow(
                    initiateFlow(otherParty), identitiesToSync
                        ?: throw IllegalArgumentException(
                            "A list of anonymous parties or a valid tx id must be provided " +
                                    "to this flow."
                        )
                )
            )
        }
    }
}

/**
 * Responder flow to [SyncKeyMappingInitiator].
 */
@InitiatedBy(SyncKeyMappingInitiator::class)
class SyncKeyMappingResponder(private val otherSession: FlowSession) : AbstractInitiatingFlow<Unit>() {
    @Suspendable
    override fun call() {
        subFlow(SyncKeyMappingFlowHandler(otherSession))
    }
}

abstract class AbstractInitiatingFlow<T> : Flow<T> {
    @CordaInject
    lateinit var flowEngine: FlowEngine

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @Suspendable
    fun <R> subFlow(flow: Flow<R>) = flowEngine.subFlow(flow)

    @Suspendable
    fun initiateFlow(otherParty: Party) = flowMessaging.initiateFlow(otherParty)
}
