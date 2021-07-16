package com.r3.corda.lib.ci.workflows

import net.corda.v5.application.flows.Flow
import net.corda.v5.application.flows.FlowSession
import net.corda.v5.application.flows.flowservices.FlowEngine
import net.corda.v5.application.injection.CordaInject
import net.corda.v5.application.identity.AbstractParty
import net.corda.v5.application.identity.Party
import net.corda.v5.application.services.IdentityService
import net.corda.v5.application.flows.unwrap
import net.corda.v5.application.services.MemberLookupService
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.util.contextLogger
import net.corda.v5.ledger.contracts.ContractState
import net.corda.v5.ledger.contracts.TransactionResolutionException
import net.corda.v5.ledger.services.StateLoaderService
import net.corda.v5.ledger.transactions.WireTransaction
import java.security.PublicKey

/**
 * This flow allows a node to share the [PublicKey] to [Party] mapping data of unknown parties present in a given
 * transaction. Alternatively, the initiating party can provide a list of [AbstractParty] they wish to synchronise the
 * [PublicKey] to [Party] mappings. The initiating sends a list of confidential identities to the counter-party who attempts to resolve
 * them. Parties that cannot be resolved are returned to the initiating node.
 *
 * The counter-party will request a new key mapping for each of the unresolved identities by calling [RequestKeyFlow] as
 * an inline flow.
 */
class SyncKeyMappingFlow
private constructor(
    private val session: FlowSession,
    private val tx: WireTransaction?,
    private val identitiesToSync: List<AbstractParty>?
) : Flow<Unit> {

    /**
     * Synchronize the "confidential identities" present in a transaction with the counterparty specified by the
     * supplied flow session.
     *
     * @param session a flow session for the party to synchronize the confidential identities with
     * @param tx the transaction to extract confidential identities from.
     */
    constructor(session: FlowSession, tx: WireTransaction) : this(session, tx, null)

    /**
     * Synchronize the "confidential identities" present in a list of [AbstractParty]s with the counterparty specified
     * by the supplied flow session.
     *
     * @param session a flow session for the party to synchronize the confidential identities with
     * @param identitiesToSync the confidential identities to synchronize.
     */
    constructor(session: FlowSession, identitiesToSync: List<AbstractParty>) : this(session, null, identitiesToSync)

    private companion object {
        const val SYNCING_KEY_MAPPINGS = "Syncing key mappings."

        val logger = contextLogger()
    }

    @CordaInject
    lateinit var identityService: IdentityService

    @CordaInject
    lateinit var stateLoaderService: StateLoaderService

    @CordaInject
    lateinit var memberLookupService: MemberLookupService

    @Suspendable
    override fun call() {
        logger.debug("${this::class.java.name}: $SYNCING_KEY_MAPPINGS")
        val confidentialIdentities =
            if (tx != null) {
                extractConfidentialIdentities(tx)
            } else {
                identitiesToSync ?: throw IllegalArgumentException(
                    "A transaction or a list of anonymous parties " +
                            "must be provided to this flow."
                )
            }

        // Send confidential identities to the counter party and return a list of parties they wish to resolve
        val requestedIdentities = session.sendAndReceive<List<AbstractParty>>(confidentialIdentities).unwrap { req ->
            require(req.all { it in confidentialIdentities }) {
                "${session.counterparty} requested resolution of a confidential identity that is not present in the " +
                        "list of identities initially provided."
            }
            req
        }
        val resolvedIds = requestedIdentities.map {
            it.owningKey to identityService.partyFromAnonymous(it)
        }.toMap()
        session.send(resolvedIds)
    }

    private fun extractConfidentialIdentities(tx: WireTransaction): List<AbstractParty> {
        val inputStates: List<ContractState> = (tx.inputs.toSet()).mapNotNull {
            try {
                stateLoaderService.loadState(it).state.data
            } catch (e: TransactionResolutionException) {
                logger.warn("WARNING: Could not resolve state with StateRef $it")
                null
            }
        }
        val states: List<ContractState> = inputStates + tx.outputs.map { it.data }
        val identities: Set<AbstractParty> = states.flatMap(ContractState::participants).toSet()

        return identities
            .filter { memberLookupService.lookup(identities.first().owningKey) == null }
            .toList()
    }
}

class SyncKeyMappingFlowHandler(private val otherSession: FlowSession) : Flow<Unit> {
    private companion object {
        const val RECEIVING_IDENTITIES = "Receiving confidential identities."
        const val RECEIVING_PARTIES = "Receiving potential party objects for unknown identities."
        const val NO_PARTIES_RECEIVED =
            "None of the requested unknown parties were resolved by the " +
                    "counter party. Terminating the flow early."

        const val REQUESTING_PROOF_OF_ID =
            "Requesting a signed key to party mapping for the " +
                    "received parties to verify the authenticity of the party."

        const val IDENTITIES_SYNCHRONISED = "Identities have finished synchronising."

        val logger = contextLogger()

        fun debug(msg: String) = logger.debug("${this::class.java.name}: $msg")
    }

    @CordaInject
    lateinit var identityService: IdentityService

    @CordaInject
    lateinit var flowEngine: FlowEngine

    @Suspendable
    override fun call() {
        debug(RECEIVING_IDENTITIES)
        val allConfidentialIds = otherSession.receive<List<AbstractParty>>().unwrap { it }
        val unknownIdentities = allConfidentialIds.filter {
            identityService.partyFromAnonymous(it) == null
        }
        otherSession.send(unknownIdentities)
        debug(RECEIVING_PARTIES)

        val mapConfidentialKeyToParty = otherSession.receive<Map<PublicKey, Party>>().unwrap { it.toList() }
        if (mapConfidentialKeyToParty.isEmpty()) {
            debug(NO_PARTIES_RECEIVED)
        }

        debug(REQUESTING_PROOF_OF_ID)

        mapConfidentialKeyToParty.forEach {
            flowEngine.subFlow(VerifyAndAddKey(it.second, it.first))
        }
        debug(IDENTITIES_SYNCHRONISED)
    }
}