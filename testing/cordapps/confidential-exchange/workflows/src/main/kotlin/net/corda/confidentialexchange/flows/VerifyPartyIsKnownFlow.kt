package net.corda.confidentialexchange.flows

import net.corda.v5.application.flows.Flow
import net.corda.v5.application.flows.StartableByRPC
import net.corda.v5.application.flows.flowservices.dependencies.CordaInject
import net.corda.v5.application.node.services.IdentityService
import net.corda.v5.base.annotations.Suspendable
import java.security.PublicKey

@StartableByRPC
class VerifyPartyIsKnownFlow(
    val otherPartyKey : PublicKey
) : Flow<Boolean> {

    @CordaInject
    lateinit var identityService: IdentityService

    @Suspendable
    override fun call() =  identityService.partyFromKey(otherPartyKey) != null
}