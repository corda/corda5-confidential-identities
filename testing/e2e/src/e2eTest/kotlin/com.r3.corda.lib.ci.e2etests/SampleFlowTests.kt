package com.r3.corda.lib.ci.e2etests

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.corda.client.rpc.flow.FlowStarterRPCOps
import net.corda.confidentialexchange.flows.ConfidentialMoveFlow
import net.corda.confidentialexchange.flows.IssueFlow
import net.corda.confidentialexchange.flows.RequestConfidentialIdentityFlowSync
import net.corda.confidentialexchange.flows.VerifyPartyIsKnownFlow
import net.corda.confidentialexchange.states.ExchangeableState
import net.corda.test.dev.network.Node
import net.corda.test.dev.network.httpRpcClient
import net.corda.test.dev.network.withFlow
import net.corda.v5.application.identity.AbstractParty
import net.corda.v5.application.identity.CordaX500Name
import net.corda.v5.base.util.seconds
import net.corda.v5.ledger.transactions.SignedTransaction
import net.corda.v5.legacyapi.rpc.CordaRPCOps
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * This test class is used to verify the confidential identities flows can run successfully by calling them via sample flows.
 */
@Disabled("Confidential Identities is a corda 5 feature and the flows triggered by this test which once worked do" +
        "not work anymore. This will remain the case until an ENT version of corda 5 is available.")
class SampleFlowTests {

    companion object {
        @JvmStatic
        @BeforeAll
        fun verifySetup() {
            e2eTestNetwork.verify {
                listOf("alice", "bob", "caroline")
                    .map { hasNode(it) }
                    .forEach {
                        it.withFlow<IssueFlow>()
                            .withFlow<ConfidentialMoveFlow>()
                            //.withFlow<VerifyPartyIsKnownFlow>()
                            .withFlow<RequestConfidentialIdentityFlowSync>()
                    }
            }
        }
    }

    private fun Node.issue(): JsonObject = httpRpcClient<FlowStarterRPCOps, JsonObject> {
        val result = getFlowOutcome(runFlow(IssueFlow::class, emptyMap()))
        JsonParser.parseString(result.resultJson).asJsonObject
    }

    private fun Node.move(linearId : String, recipient: CordaX500Name): JsonObject {
        return httpRpcClient<FlowStarterRPCOps, JsonObject> {
            val result = getFlowOutcome(runFlow(ConfidentialMoveFlow::class, mapOf(
                "linearId" to linearId,
                "recipient" to recipient.toString()
            )))
            JsonParser.parseString(result.resultJson).asJsonObject
        }
    }

    private fun Node.identitySync(requestFrom: CordaX500Name, identities: List<String>): JsonObject {
        return httpRpcClient<FlowStarterRPCOps, JsonObject> {
            val result = getFlowOutcome(runFlow(RequestConfidentialIdentityFlowSync::class, mapOf(
                "requestFrom" to requestFrom.toString(),
                "identities" to identities
            )))
            JsonParser.parseString(result.resultJson).asJsonObject
        }
    }

    private val JsonObject.linearId: String
        get() = this["linearId"].asString

    @Test
    fun runSampleFlows() {
        e2eTestNetwork.use {
            /**
             * Issue exchangeable state
             */
            // Alice issues a state to be exchanged
            val issueResponse = alice().issue()
            val stateLinearId = issueResponse.linearId

            val aliceX500Name = alice().getX500Name()
            val bobX500Name = bob().getX500Name()
            val carolineX500Name = caroline().getX500Name()

//        val aliceKnownId = alice().rpc {
//            nodeInfo().party
//        }

            /**
             * Verify three nodes are aware of each others public keys
             */
            // Verify bob knows alice's public key
//        val bobKnownId = bob().rpc {
//            verifyKnownIdentity(aliceKnownId)
//            nodeInfo().party
//        }

            // Verify caroline knows both bob and alice's public keys
//        val carolineKnownId = caroline().rpc {
//            verifyKnownIdentity(aliceKnownId)
//            verifyKnownIdentity(bobKnownId)
//            nodeInfo().party
//        }

            // Verify bob knows carolines's public key
//        bob().rpc { verifyKnownIdentity(carolineKnownId) }

            // Verify alices knows both bob and caroline's public keys
//        alice().rpc {
//            verifyKnownIdentity(carolineKnownId)
//            verifyKnownIdentity(bobKnownId)
//        }

            /**
             * Move the issued state from alice to bob but keep bob's id confidential.
             * Alice and Bob should be able to map the returned anonymous party's public key to a well known identity since they were involved
             * in the transaction, but caroline should not be aware of a well known identity for the same public key.
             */
            alice().move(stateLinearId, bobX500Name)

            //val anonBKnownByAAndB = (stx!!.tx.outputStates.single() as ExchangeableState).owner

//        alice().rpc { verifyKnownIdentity(anonBKnownByAAndB) }
//        bob().rpc { verifyKnownIdentity(anonBKnownByAAndB) }
//        caroline().rpc { verifyKnownIdentity(anonBKnownByAAndB, false) }

            /**
             * Move the issued state from bob to caroline but keep caroline's id confidential.
             * Bob and Caroline should be able to map the returned anonymous party's public key to a well known identity since they were involved
             * in the transaction, but alice should not be aware of a well known identity for the same public key.
             */
            bob().move(stateLinearId, carolineX500Name)
            //val anonCKnownByBAndC = (stx!!.tx.outputStates.single() as ExchangeableState).owner

//        alice().rpc { verifyKnownIdentity(anonCKnownByBAndC, false) }
//        bob().rpc { verifyKnownIdentity(anonCKnownByBAndC) }
//        caroline().rpc { verifyKnownIdentity(anonCKnownByBAndC) }

            /**
             * Move the issued state from caroline to alice but keep alice's id confidential.
             * Alice and Caroline should be able to map the returned anonymous party's public key to a well known identity since they were involved
             * in the transaction, but bob should not be aware of a well known identity for the same public key.
             */
            caroline().move(stateLinearId, aliceX500Name)
            //val anonAKnownByAAndC = (stx!!.tx.outputStates.single() as ExchangeableState).owner

//        alice().rpc { verifyKnownIdentity(anonAKnownByAAndC) }
//        bob().rpc { verifyKnownIdentity(anonAKnownByAAndC, false) }
//        caroline().rpc { verifyKnownIdentity(anonAKnownByAAndC) }

            /**
             * Alice will attempt to sync confidential identities with Bob.
             * Alice doesn't know caroline's well known id maps to their anonymous id so bob can provide that.
             */
            //alice().identitySync(bobX500Name, emptyList())
//        alice().rpc {
//            verifyKnownIdentity(anonCKnownByBAndC, false)
//            startFlowDynamic(RequestConfidentialIdentityFlowSync::class.java, bobKnownId, listOf(anonCKnownByBAndC))
//            Thread.sleep(2.seconds.toMillis())
//            verifyKnownIdentity(anonCKnownByBAndC, true)
//        }
        }
    }

//    private fun Node.verifyKnownIdentity(identity: AbstractParty, checkIsKnown: Boolean = true) =
//        verifyKnownIdentity(listOf(identity), checkIsKnown)
//
//    private fun Node.verifyKnownIdentity(identities: List<AbstractParty>, checkIsKnown: Boolean = true) {
//        httpRpcClient<FlowStarterRPCOps, Unit> {
//            identities.forEach {
//                val response = getFlowOutcome(runFlow(VerifyPartyIsKnownFlow::class, mapOf("key" to it.owningKey)))
//                val result = JsonParser.parseString(response.resultJson).asBoolean
//                val assertion = assertThat(result)
//                if (checkIsKnown) assertion.isTrue else assertion.isFalse
//            }
//        }
//    }
}
