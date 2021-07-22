package com.r3.corda.lib.ci.e2etests

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.corda.client.rpc.flow.FlowStarterRPCOps
import net.corda.confidentialexchange.flows.ConfidentialMoveFlow
import net.corda.confidentialexchange.flows.IssueFlow
import net.corda.test.dev.network.Node
import net.corda.test.dev.network.httpRpcClient
import net.corda.test.dev.network.withFlow
import net.corda.v5.application.identity.CordaX500Name
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

/**
 * This test class is used to verify the confidential identities flows can run successfully by calling them via sample flows.
 */
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
                    }
            }
        }
    }

    @Test
    fun runSampleFlows() {
        e2eTestNetwork.use {
            /**
             * Issue exchangeable state
             */
            // Alice issues a state to be exchanged
            val issueResponse = alice().issue()
            assertThat(issueResponse.ownerIsAnonymous()).isTrue

            val stateLinearId = issueResponse.linearId

            val aliceX500Name = alice().getX500Name()
            val bobX500Name = bob().getX500Name()
            val carolineX500Name = caroline().getX500Name()

            //Move the issued state from alice to bob.
            val aliceToBobMoveResponse = alice().move(stateLinearId, bobX500Name)

            assertThat(aliceToBobMoveResponse.ownerIsAnonymous()).isTrue

            // Move the issued state from bob to caroline and share that info with alice
            val bobToCarolineMoveResponse = bob().move(stateLinearId, carolineX500Name, aliceX500Name)
            assertThat(bobToCarolineMoveResponse.ownerIsAnonymous()).isTrue

            // Move the issued state from caroline to alice
            val carolineToAliceMoveResponse = caroline().move(stateLinearId, aliceX500Name)
            assertThat(carolineToAliceMoveResponse.ownerIsAnonymous()).isTrue
        }
    }

    private val JsonObject.linearId: String
        get() = JsonParser.parseString(this["outputStates"].asJsonArray[0].asJsonPrimitive.asString).asJsonObject["linearId"].asString

    private fun Node.issue(): JsonObject = httpRpcClient<FlowStarterRPCOps, JsonObject> {
        val result = getFlowOutcome(runFlow(IssueFlow::class, emptyMap()))
        JsonParser.parseString(result.resultJson).asJsonObject
    }

    private fun JsonObject.ownerIsAnonymous() = "null" ==
            JsonParser.parseString(this["outputStates"].asJsonArray[0].asJsonPrimitive.asString).asJsonObject["owner"].asString

    private fun Node.move(linearId : String, recipient: CordaX500Name, observer: CordaX500Name? = null): JsonObject {
        return httpRpcClient<FlowStarterRPCOps, JsonObject> {
            val result = getFlowOutcome(runFlow(ConfidentialMoveFlow::class,
                mutableMapOf(
                    "linearId" to linearId,
                    "recipient" to recipient.toString(),
                ).apply {
                    observer?.let { put("observer", observer.toString())}
                }
            ))
            JsonParser.parseString(result.resultJson).asJsonObject
        }
    }
}
