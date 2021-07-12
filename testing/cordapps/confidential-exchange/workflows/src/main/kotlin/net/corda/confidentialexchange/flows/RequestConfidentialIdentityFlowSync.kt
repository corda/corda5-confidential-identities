package net.corda.confidentialexchange.flows

import com.r3.corda.lib.ci.workflows.SyncKeyMappingInitiator
import net.corda.v5.application.flows.Flow
import net.corda.v5.application.flows.FlowSession
import net.corda.v5.application.flows.InitiatedBy
import net.corda.v5.application.flows.InitiatingFlow
import net.corda.v5.application.flows.JsonConstructor
import net.corda.v5.application.flows.RpcStartFlowRequestParameters
import net.corda.v5.application.flows.StartableByRPC
import net.corda.v5.application.flows.flowservices.FlowEngine
import net.corda.v5.application.flows.flowservices.FlowMessaging
import net.corda.v5.application.flows.receive
import net.corda.v5.application.flows.unwrap
import net.corda.v5.application.identity.AnonymousParty
import net.corda.v5.application.identity.CordaX500Name
import net.corda.v5.application.identity.Party
import net.corda.v5.application.injection.CordaInject
import net.corda.v5.application.services.IdentityService
import net.corda.v5.application.services.json.JsonMarshallingService
import net.corda.v5.application.services.json.parseJson
import net.corda.v5.base.annotations.Suspendable

@StartableByRPC
@InitiatingFlow
class RequestConfidentialIdentityFlowSync @JsonConstructor constructor(
    val jsonParams: RpcStartFlowRequestParameters
) : Flow<Unit> {

    @CordaInject
    lateinit var flowMessaging: FlowMessaging

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var identityService: IdentityService

    @Suspendable
    override fun call() {
        val params: Map<String, String> = jsonMarshallingService.parseJson(jsonParams.parametersInJson)
        val x500NameToRequestIdFrom: CordaX500Name = CordaX500Name.parse(params["requestFrom"]!!)

        val partyToRequestIdFrom: Party = identityService.partyFromName(x500NameToRequestIdFrom)!!

        // TO IMPLEMENT!
        val unknownIds: List<AnonymousParty> = emptyList()

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