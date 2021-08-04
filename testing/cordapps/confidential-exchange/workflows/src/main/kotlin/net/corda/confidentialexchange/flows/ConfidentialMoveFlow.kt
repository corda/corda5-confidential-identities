package net.corda.confidentialexchange.flows

import com.r3.corda.lib.ci.workflows.RequestKey
import com.r3.corda.lib.ci.workflows.SyncKeyMappingInitiator
import net.corda.confidentialexchange.contracts.ExchangeableStateContract
import net.corda.confidentialexchange.states.ExchangeableState
import net.corda.systemflows.CollectSignaturesFlow
import net.corda.systemflows.FinalityFlow
import net.corda.systemflows.ReceiveFinalityFlow
import net.corda.systemflows.ReceiveTransactionFlow
import net.corda.systemflows.SendTransactionFlow
import net.corda.systemflows.SignTransactionFlow
import net.corda.v5.application.flows.Flow
import net.corda.v5.application.flows.FlowSession
import net.corda.v5.application.flows.InitiatedBy
import net.corda.v5.application.flows.InitiatingFlow
import net.corda.v5.application.flows.JsonConstructor
import net.corda.v5.application.flows.RpcStartFlowRequestParameters
import net.corda.v5.application.flows.StartableByRPC
import net.corda.v5.application.flows.flowservices.FlowEngine
import net.corda.v5.application.flows.flowservices.FlowIdentity
import net.corda.v5.application.flows.flowservices.FlowMessaging
import net.corda.v5.application.identity.AnonymousParty
import net.corda.v5.application.identity.CordaX500Name
import net.corda.v5.application.identity.Party
import net.corda.v5.application.injection.CordaInject
import net.corda.v5.application.services.IdentityService
import net.corda.v5.application.services.json.JsonMarshallingService
import net.corda.v5.application.services.json.parseJson
import net.corda.v5.application.services.persistence.PersistenceService
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.util.seconds
import net.corda.v5.ledger.contracts.StateAndRef
import net.corda.v5.ledger.services.NotaryLookupService
import net.corda.v5.ledger.services.StatesToRecord
import net.corda.v5.ledger.services.vault.IdentityStateAndRefPostProcessor
import net.corda.v5.ledger.services.vault.StateStatus
import net.corda.v5.ledger.transactions.SignedTransaction
import net.corda.v5.ledger.transactions.SignedTransactionDigest
import net.corda.v5.ledger.transactions.TransactionBuilderFactory
import java.util.*

@StartableByRPC
@InitiatingFlow
class ConfidentialMoveFlow @JsonConstructor constructor(
    val jsonParams: RpcStartFlowRequestParameters
) : Flow<SignedTransactionDigest> {

    @CordaInject
    lateinit var flowIdentity: FlowIdentity

    @CordaInject
    lateinit var flowEngine: FlowEngine

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @CordaInject
    lateinit var notaryLookupService: NotaryLookupService

    @CordaInject
    lateinit var transactionBuilderFactory: TransactionBuilderFactory

    @CordaInject
    lateinit var persistenceService: PersistenceService

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var identityService: IdentityService


    @Suspendable
    override fun call() : SignedTransactionDigest {
        val params : Map<String, String> = jsonMarshallingService.parseJson(jsonParams.parametersInJson)
        val recipient: CordaX500Name = CordaX500Name.parse(params["recipient"]!!)
        val linearId : String = params["linearId"]!!
        val observer : String? = params["observer"]

        val targetParty = identityService.partyFromName(recipient)
        require(targetParty != null) {
            "Target party not found for provided CordaX500Name: [$recipient]."
        }

        val myIdentity = flowIdentity.ourIdentity
        val notary = notaryLookupService.notaryIdentities.single()

        // Create confidential key pair
        val targetConfidentialIdentity = flowEngine.subFlow(RequestKey(targetParty))

        // Retrieve the state to move
        val cursor = persistenceService.query<StateAndRef<ExchangeableState>>(
            "LinearState.findByUuidAndStateStatus",
            mapOf("uuid" to UUID.fromString(linearId), "stateStatus" to StateStatus.UNCONSUMED),
            IdentityStateAndRefPostProcessor.POST_PROCESSOR_NAME
        )
        val oldState = cursor.poll(1, 20.seconds)
            .values
            .single()

        val newState : ExchangeableState = oldState.state.data.copy(owner = targetConfidentialIdentity)

        // Move state
        val signingKeys = listOf(myIdentity.owningKey, targetConfidentialIdentity.owningKey)
        val tb = transactionBuilderFactory.create().apply {
            setNotary(notary)
            addInputState(oldState)
            addOutputState(newState)
            addCommand(ExchangeableStateContract.Commands.Move(), signingKeys)
            verify()
        }

        val targetSessions = mutableSetOf(
            flowMessaging.initiateFlow(targetConfidentialIdentity)
        )

        val fullySignedTx = flowEngine.subFlow(CollectSignaturesFlow(tb.sign(), targetSessions))

        val notarisedTx = flowEngine.subFlow(FinalityFlow(fullySignedTx, targetSessions))

        // Broadcast transaction to observer
        observer?.let {
            identityService.partyFromName(CordaX500Name.parse(observer))?.let {
                // share confidential identities before sending
                flowEngine.subFlow(SyncKeyMappingInitiator(it, notarisedTx.tx))
                flowEngine.subFlow(BroadcastTransactionFlow(notarisedTx, listOf(it)))
            }
        }

        return SignedTransactionDigest(
            notarisedTx.id,
            listOf(newState.toJsonString()),
            notarisedTx.sigs
        )
    }
}

@InitiatedBy(ConfidentialMoveFlow::class)
class ConfidentialMoveResponseFlow(private val counterPartySession: FlowSession) : Flow<Unit> {
    @CordaInject
    lateinit var flowEngine: FlowEngine

    @Suspendable
    override fun call() {
        val signTransactionFlow = object : SignTransactionFlow(counterPartySession) {
            @Suspendable
            override fun checkTransaction(stx: SignedTransaction) {
                // For test purposes we are assuming the previous owner was anonymous and there was a single input state
                val state = stateLoaderService.load(stx.inputs[0]).state.data as ExchangeableState
                require(state.owner is AnonymousParty)

                // share the confidential identity used for the input state
                flowEngine.subFlow(SyncKeyMappingInitiator(counterPartySession.counterparty, listOf(state.owner)))
            }
        }
        val txId = flowEngine.subFlow(signTransactionFlow).id
        flowEngine.subFlow(ReceiveFinalityFlow(counterPartySession, txId))
    }
}

@InitiatingFlow
class BroadcastTransactionFlow(
    private val stx: SignedTransaction,
    private val recipients: List<Party>
) : Flow<Unit> {

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @CordaInject
    lateinit var flowEngine: FlowEngine

    @Suspendable
    override fun call() {
        for (recipient in recipients) {
            val session = flowMessaging.initiateFlow(recipient)
            flowEngine.subFlow(SendTransactionFlow(session, stx))
        }
    }
}

@InitiatedBy(BroadcastTransactionFlow::class)
class BroadcastTransactionResponder(private val session: FlowSession) : Flow<Unit> {

    @CordaInject
    lateinit var flowEngine: FlowEngine

    @Suspendable
    override fun call() {
        flowEngine.subFlow(ReceiveTransactionFlow(session, statesToRecord = StatesToRecord.ALL_VISIBLE))
    }
}