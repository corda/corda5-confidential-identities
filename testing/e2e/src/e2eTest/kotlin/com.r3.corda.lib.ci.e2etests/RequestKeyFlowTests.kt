package com.r3.corda.lib.ci.e2etests

import net.corda.client.rpc.flow.FlowStarterRPCOps
import net.corda.client.rpc.flow.RpcFlowStatus
import net.corda.test.dev.network.httpRpcClient
import net.corda.test.dev.network.withFlow
import net.corda.testflows.VerifyAndAddKeyTestFlow
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test

class RequestKeyFlowTests {
    companion object {
        @JvmStatic
        @BeforeAll
        fun verifySetup() {
            e2eTestNetwork.verify {
                listOf("alice", "bob", "caroline")
                    .map { hasNode(it) }
                    .forEach {
                        it.withFlow<VerifyAndAddKeyTestFlow>()
                    }
            }
        }
    }

    @Test
    fun `Request ownership claim for party and key belonging to that party`() {
        e2eTestNetwork.use {
            val bobName = bob().getX500Name().toString()

            alice().httpRpcClient<FlowStarterRPCOps, Unit> {
                // Assert that the flow is successful
                getFlowOutcome(runFlow(VerifyAndAddKeyTestFlow::class, mapOf(
                    VerifyAndAddKeyTestFlow.PARTY_A_KEY to bobName,
                    VerifyAndAddKeyTestFlow.TEST_CASE_KEY to VerifyAndAddKeyTestFlow.Companion.TestCase.VERIFY_AND_ADD_PARTY_A_KEY_FOR_PARTY_A.toString()
                )))
            }
        }
    }

    @Test
    fun `Request ownership claim for party with key belonging to a different party`() {
        e2eTestNetwork.use {
            val bobName = bob().getX500Name().toString()
            val carolineName = caroline().getX500Name().toString()

            alice().httpRpcClient<FlowStarterRPCOps, Unit> {
                // Check that the flow fails
                getFlowOutcome(runFlow(VerifyAndAddKeyTestFlow::class, mapOf(
                    VerifyAndAddKeyTestFlow.PARTY_A_KEY to bobName,
                    VerifyAndAddKeyTestFlow.PARTY_B_KEY to carolineName,
                    VerifyAndAddKeyTestFlow.TEST_CASE_KEY to VerifyAndAddKeyTestFlow.Companion.TestCase.VERIFY_AND_ADD_PARTY_A_KEY_FOR_PARTY_B.toString()
                )), RpcFlowStatus.FAILED)
            }
        }
    }

}