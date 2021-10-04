package net.corda.testflows

import com.r3.corda.lib.ci.workflows.VerifyAndAddKey
import net.corda.v5.application.flows.BadRpcStartFlowRequestException
import net.corda.v5.application.flows.Flow
import net.corda.v5.application.flows.JsonConstructor
import net.corda.v5.application.flows.RpcStartFlowRequestParameters
import net.corda.v5.application.flows.StartableByRPC
import net.corda.v5.application.flows.flowservices.FlowEngine
import net.corda.v5.application.identity.CordaX500Name
import net.corda.v5.application.injection.CordaInject
import net.corda.v5.application.node.MemberInfo
import net.corda.v5.application.services.MemberLookupService
import net.corda.v5.application.services.json.JsonMarshallingService
import net.corda.v5.application.services.json.parseJson
import net.corda.v5.base.annotations.Suspendable

/**
 * Flow used for test cases in test class [RequestKeyFlowTests].
 * Flow parameters allow the test to test different scenarios.
 */
@StartableByRPC
class VerifyAndAddKeyTestFlow @JsonConstructor constructor(
    val params: RpcStartFlowRequestParameters
) : Flow<Unit> {

    companion object {
        const val PARTY_A_KEY = "PartyA"
        const val PARTY_B_KEY = "PartyB"
        const val TEST_CASE_KEY = "TestCase"
        enum class TestCase {
            VERIFY_AND_ADD_PARTY_A_KEY_FOR_PARTY_A,
            VERIFY_AND_ADD_PARTY_A_KEY_FOR_PARTY_B,
        }

    }

    @CordaInject
    lateinit var flowEngine: FlowEngine

    @CordaInject
    lateinit var jsonMarshallingService: JsonMarshallingService

    @CordaInject
    lateinit var memberLookupService: MemberLookupService

    @Suspendable
    override fun call() {
        val inputParams: Map<String, String> = jsonMarshallingService.parseJson(params.parametersInJson)

        fun parseParty(partyId: String, optional: Boolean = false): MemberInfo? {
            return inputParams[partyId]?.let {
                memberLookupService.lookup(CordaX500Name.parse(it))
            } ?: if(!optional) {
                throw BadRpcStartFlowRequestException("Could not find member info for input party: $partyId")
            } else null

        }
        val partyA = parseParty(PARTY_A_KEY)
        val partyB = parseParty(PARTY_B_KEY, optional = true)

        val testCase = TestCase.valueOf(inputParams[TEST_CASE_KEY]
            ?: throw BadRpcStartFlowRequestException("Test case was not passed in to the flow as a parameter."))

        require(partyA != null)
        when(testCase) {
            TestCase.VERIFY_AND_ADD_PARTY_A_KEY_FOR_PARTY_A -> {
                flowEngine.subFlow(VerifyAndAddKey(partyA.party, partyA.identityKeys.first()))
            }
            TestCase.VERIFY_AND_ADD_PARTY_A_KEY_FOR_PARTY_B -> {
                require(partyB != null)
                flowEngine.subFlow(VerifyAndAddKey(partyA.party, partyB.identityKeys.first()))
            }
        }
    }
}