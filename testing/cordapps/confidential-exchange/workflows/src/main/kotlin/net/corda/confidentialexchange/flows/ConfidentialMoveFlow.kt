package net.corda.confidentialexchange.flows

import com.r3.corda.lib.ci.workflows.RequestKey
import net.corda.confidentialexchange.contracts.ExchangeableStateContract
import net.corda.confidentialexchange.states.ExchangeableState
import net.corda.systemflows.CollectSignaturesFlow
import net.corda.systemflows.FinalityFlow
import net.corda.systemflows.ReceiveFinalityFlow
import net.corda.systemflows.SignTransactionFlow
import net.corda.v5.application.flows.Flow
import net.corda.v5.application.flows.FlowSession
import net.corda.v5.application.flows.InitiatedBy
import net.corda.v5.application.flows.InitiatingFlow
import net.corda.v5.application.flows.StartableByRPC
import net.corda.v5.application.flows.flowservices.FlowEngine
import net.corda.v5.application.flows.flowservices.FlowIdentity
import net.corda.v5.application.flows.flowservices.FlowMessaging
import net.corda.v5.application.injection.CordaInject
import net.corda.v5.application.identity.Party
import net.corda.v5.application.services.persistence.PersistenceService
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.base.util.seconds
import net.corda.v5.ledger.contracts.StateAndRef
import net.corda.v5.ledger.services.NotaryLookupService
import net.corda.v5.ledger.services.vault.IdentityStateAndRefPostProcessor
import net.corda.v5.ledger.services.vault.StateStatus
import net.corda.v5.ledger.transactions.SignedTransaction
import net.corda.v5.ledger.transactions.TransactionBuilderFactory
import java.util.*

@StartableByRPC
@InitiatingFlow
class ConfidentialMoveFlow(
    val targetParty: Party,
    val stateId : String,
) : Flow<SignedTransaction> {

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

    @Suspendable
    override fun call() : SignedTransaction {
        val myIdentity = flowIdentity.ourIdentity
        val notary = notaryLookupService.notaryIdentities.single()

        // Create confidential key pair
        val targetConfidentialIdentity = flowEngine.subFlow(RequestKey(targetParty))

        // Retrieve the state to move
        val cursor = persistenceService.query<StateAndRef<ExchangeableState>>(
            "LinearState.findByUuidAndStateStatus",
            mapOf("uuid" to UUID.fromString(stateId), "stateStatus" to StateStatus.UNCONSUMED),
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

        val targetSessions = mutableSetOf(flowMessaging.initiateFlow(targetConfidentialIdentity))

        val fullySignedTx = flowEngine.subFlow(CollectSignaturesFlow(tb.sign(), targetSessions))

        return flowEngine.subFlow(FinalityFlow(fullySignedTx, targetSessions))
    }
}

@InitiatedBy(ConfidentialMoveFlow::class)
class ConfidentialMoveResponseFlow(private val counterPartySession: FlowSession) : Flow<Unit> {
    @CordaInject
    lateinit var flowEngine: FlowEngine

    @Suspendable
    override fun call() {
        val signTransactionFlow = object : SignTransactionFlow(counterPartySession) {
            override fun checkTransaction(stx: SignedTransaction) {
                transactionMappingService.toLedgerTransaction(stx, false)
            }
        }
        val txId = flowEngine.subFlow(signTransactionFlow).id
        flowEngine.subFlow(ReceiveFinalityFlow(counterPartySession, txId))
    }
}