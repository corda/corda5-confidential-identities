package com.r3.corda.lib.ci.e2etests

import net.corda.confidentialexchange.flows.ConfidentialMoveFlow
import net.corda.confidentialexchange.flows.IssueFlow
import net.corda.confidentialexchange.flows.RequestConfidentialIdentityFlowSync
import net.corda.confidentialexchange.flows.VerifyPartyIsKnownFlow
import net.corda.confidentialexchange.states.ExchangeableState
import net.corda.test.dev.network.withFlow
import net.corda.v5.application.identity.AbstractParty
import net.corda.v5.base.util.seconds
import net.corda.v5.ledger.transactions.SignedTransaction
import net.corda.v5.legacyapi.rpc.CordaRPCOps
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
                            .withFlow<VerifyPartyIsKnownFlow>()
                            .withFlow<RequestConfidentialIdentityFlowSync>()
                    }
            }
        }
    }

    @Test
    fun runSampleFlows() = e2eTestNetwork.use {
        /**
         * Issue exchangeable state
         */
        var stx: SignedTransaction? = null
        // Alice issues a state to be exchanged
        val aliceKnownIds = alice().rpc {
            stx = startFlowDynamic(IssueFlow::class.java).returnValue.getOrThrow()
            nodeInfo().legalIdentities
        }
        val stateLinearId = (stx!!.tx.outputStates.single() as ExchangeableState).linearId.toString()

        /**
         * Verify three nodes are aware of each others public keys
         */
        // Verify bob knows alice's public key
        val bobKnownIds = bob().rpc {
            verifyKnownIdentity(aliceKnownIds)
            nodeInfo().legalIdentities
        }

        // Verify caroline knows both bob and alice's public keys
        val carolineKnownIds = caroline().rpc {
            verifyKnownIdentity(aliceKnownIds)
            verifyKnownIdentity(bobKnownIds)
            nodeInfo().legalIdentities
        }

        // Verify bob knows carolines's public key
        bob().rpc { verifyKnownIdentity(carolineKnownIds) }

        // Verify alices knows both bob and caroline's public keys
        alice().rpc {
            verifyKnownIdentity(carolineKnownIds)
            verifyKnownIdentity(bobKnownIds)
        }

        /**
         * Move the issued state from alice to bob but keep bob's id confidential.
         * Alice and Bob should be able to map the returned anonymous party's public key to a well known identity since they were involved
         * in the transaction, but caroline should not be aware of a well known identity for the same public key.
         */
        stx = alice().rpc {
            startFlowDynamic(
                ConfidentialMoveFlow::class.java,
                bobKnownIds.first(),
                stateLinearId
            ).returnValue.getOrThrow()
        }
        val anonBKnownByAAndB = (stx!!.tx.outputStates.single() as ExchangeableState).owner

        alice().rpc { verifyKnownIdentity(anonBKnownByAAndB) }
        bob().rpc { verifyKnownIdentity(anonBKnownByAAndB) }
        caroline().rpc { verifyKnownIdentity(anonBKnownByAAndB, false) }

        /**
         * Move the issued state from bob to caroline but keep caroline's id confidential.
         * Bob and Caroline should be able to map the returned anonymous party's public key to a well known identity since they were involved
         * in the transaction, but alice should not be aware of a well known identity for the same public key.
         */
        stx = bob().rpc {
            startFlowDynamic(
                ConfidentialMoveFlow::class.java,
                carolineKnownIds.first(),
                stateLinearId
            ).returnValue.getOrThrow()
        }
        val anonCKnownByBAndC = (stx!!.tx.outputStates.single() as ExchangeableState).owner

        alice().rpc { verifyKnownIdentity(anonCKnownByBAndC, false) }
        bob().rpc { verifyKnownIdentity(anonCKnownByBAndC) }
        caroline().rpc { verifyKnownIdentity(anonCKnownByBAndC) }

        /**
         * Move the issued state from caroline to alice but keep alice's id confidential.
         * Alice and Caroline should be able to map the returned anonymous party's public key to a well known identity since they were involved
         * in the transaction, but bob should not be aware of a well known identity for the same public key.
         */
        caroline().rpc {
            stx = startFlowDynamic(ConfidentialMoveFlow::class.java, aliceKnownIds.first(), stateLinearId).returnValue.getOrThrow()
        }
        val anonAKnownByAAndC = (stx!!.tx.outputStates.single() as ExchangeableState).owner

        alice().rpc { verifyKnownIdentity(anonAKnownByAAndC) }
        bob().rpc { verifyKnownIdentity(anonAKnownByAAndC, false) }
        caroline().rpc { verifyKnownIdentity(anonAKnownByAAndC) }

        /**
         * Alice will attempt to sync confidential identities with Bob.
         * Alice doesn't know caroline's well known id maps to their anonymous id so bob can provide that.
         */
        alice().rpc {
            verifyKnownIdentity(anonCKnownByBAndC, false)
            startFlowDynamic(RequestConfidentialIdentityFlowSync::class.java, bobKnownIds.first(), listOf(anonCKnownByBAndC))
            Thread.sleep(2.seconds.toMillis())
            verifyKnownIdentity(anonCKnownByBAndC, true)
        }
    }

    private fun CordaRPCOps.verifyKnownIdentity(identity: AbstractParty, checkIsKnown: Boolean = true) =
        verifyKnownIdentity(listOf(identity), checkIsKnown)

    private fun CordaRPCOps.verifyKnownIdentity(identities: List<AbstractParty>, checkIsKnown: Boolean = true) {
        identities.forEach {
            val x = assertThat(startFlowDynamic(VerifyPartyIsKnownFlow::class.java, it.owningKey).returnValue.getOrThrow())
            if (checkIsKnown) x.isTrue else x.isFalse
        }
    }
}
