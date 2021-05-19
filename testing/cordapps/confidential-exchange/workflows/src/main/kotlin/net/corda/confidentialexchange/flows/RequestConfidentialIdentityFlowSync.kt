package net.corda.confidentialexchange.flows

import com.r3.corda.lib.ci.workflows.SyncKeyMappingInitiator
import net.corda.v5.application.flows.Flow
import net.corda.v5.application.flows.FlowSession
import net.corda.v5.application.flows.InitiatedBy
import net.corda.v5.application.flows.InitiatingFlow
import net.corda.v5.application.flows.StartableByRPC
import net.corda.v5.application.flows.flowservices.FlowEngine
import net.corda.v5.application.flows.flowservices.FlowMessaging
import net.corda.v5.application.flows.flowservices.dependencies.CordaInject
import net.corda.v5.application.identity.AnonymousParty
import net.corda.v5.application.identity.Party
import net.corda.v5.application.utilities.unwrap
import net.corda.v5.base.annotations.Suspendable

@StartableByRPC
@InitiatingFlow
class RequestConfidentialIdentityFlowSync(
    val partyToRequestIdFrom: Party,
    val unknownIds: List<AnonymousParty>
) : Flow<Unit> {

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @Suspendable
    override fun call() {
        val otherSideSessions = setOf(flowMessaging.initiateFlow(partyToRequestIdFrom))
        flowMessaging.sendAll(unknownIds, otherSideSessions)
    }
}

@StartableByRPC
@InitiatedBy(RequestConfidentialIdentityFlowSync::class)
class ReturnConfidentialIdentityFlowSync(private val counterPartySession: FlowSession) : Flow<Unit> {

    @CordaInject
    lateinit var flowEngine: FlowEngine

    @Suspendable
    override fun call() {
        val partiesToReturn = counterPartySession.receive<List<AnonymousParty>>().unwrap { it }
        flowEngine.subFlow(SyncKeyMappingInitiator(counterPartySession.counterparty, partiesToReturn))
    }
}