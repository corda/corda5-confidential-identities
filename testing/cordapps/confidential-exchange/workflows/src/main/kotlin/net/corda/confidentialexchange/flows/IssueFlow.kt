package net.corda.confidentialexchange.flows

import net.corda.confidentialexchange.contracts.ExchangeableStateContract.Commands
import net.corda.confidentialexchange.states.ExchangeableState
import net.corda.systemflows.FinalityFlow
import net.corda.v5.application.flows.Flow
import net.corda.v5.application.flows.JsonConstructor
import net.corda.v5.application.flows.RpcStartFlowRequestParameters
import net.corda.v5.application.flows.StartableByRPC
import net.corda.v5.application.flows.flowservices.FlowEngine
import net.corda.v5.application.flows.flowservices.FlowIdentity
import net.corda.v5.application.injection.CordaInject
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.ledger.services.NotaryLookupService
import net.corda.v5.ledger.services.StatesToRecord
import net.corda.v5.ledger.transactions.SignedTransactionDigest
import net.corda.v5.ledger.transactions.TransactionBuilderFactory

@StartableByRPC
class IssueFlow @JsonConstructor constructor(
    @Suppress("UNUSED") val inputJson: RpcStartFlowRequestParameters
): Flow<SignedTransactionDigest> {

    @CordaInject
    lateinit var flowIdentity: FlowIdentity

    @CordaInject
    lateinit var flowEngine: FlowEngine

    @CordaInject
    lateinit var transactionBuilderFactory: TransactionBuilderFactory

    @CordaInject
    lateinit var notaryLookupService: NotaryLookupService

    @Suspendable
    override fun call(): SignedTransactionDigest {
        val myIdentity = flowIdentity.ourIdentity
        val notary = notaryLookupService.notaryIdentities.first()

        val issuedState = ExchangeableState(myIdentity, myIdentity.anonymise())

        val tb = transactionBuilderFactory.create().apply {
            setNotary(notary)
            addOutputState(issuedState)
            addCommand(Commands.Issue(), myIdentity.owningKey)
            verify()
        }
        val notarisedTx = flowEngine.subFlow(FinalityFlow(tb.sign(), emptyList(), StatesToRecord.ALL_VISIBLE))
        return SignedTransactionDigest(
            notarisedTx.id,
            listOf(issuedState.toJsonString()),
            notarisedTx.sigs
        )
    }
}