package net.corda.confidentialexchange.flows

import net.corda.confidentialexchange.contracts.ExchangeableStateContract.Commands
import net.corda.confidentialexchange.states.ExchangeableState
import net.corda.systemflows.FinalityFlow
import net.corda.v5.application.flows.Flow
import net.corda.v5.application.flows.StartableByRPC
import net.corda.v5.application.flows.flowservices.FlowEngine
import net.corda.v5.application.flows.flowservices.FlowIdentity
import net.corda.v5.application.injection.CordaInject
import net.corda.v5.base.annotations.Suspendable
import net.corda.v5.ledger.services.NotaryLookupService
import net.corda.v5.ledger.services.StatesToRecord
import net.corda.v5.ledger.transactions.SignedTransaction
import net.corda.v5.ledger.transactions.TransactionBuilderFactory

@StartableByRPC
class IssueFlow : Flow<SignedTransaction> {

    @CordaInject
    lateinit var flowIdentity: FlowIdentity

    @CordaInject
    lateinit var flowEngine: FlowEngine

    @CordaInject
    lateinit var transactionBuilderFactory: TransactionBuilderFactory

    @CordaInject
    lateinit var notaryLookupService: NotaryLookupService

    @Suspendable
    override fun call(): SignedTransaction {
        val myIdentity = flowIdentity.ourIdentity
        val notary = notaryLookupService.notaryIdentities.first()

        val issuedState = ExchangeableState(myIdentity, myIdentity.anonymise())

        val tb = transactionBuilderFactory.create().apply {
            setNotary(notary)
            addOutputState(issuedState)
            addCommand(Commands.Issue(), myIdentity.owningKey)
            verify()
        }

        return flowEngine.subFlow(FinalityFlow(tb.sign(), emptyList(), StatesToRecord.ALL_VISIBLE))
    }
}