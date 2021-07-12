package com.r3.corda.lib.ci.e2etests

import net.corda.test.dev.network.Node
import net.corda.test.dev.network.Nodes
import net.corda.test.dev.network.TestNetwork

fun Nodes<Node>.alice() = getNode("alice")
fun Nodes<Node>.bob() = getNode("bob")
fun Nodes<Node>.caroline() = getNode("caroline")

val e2eTestNetwork = TestNetwork.forNetwork(
    System.getenv("E2E_TEST_NETWORK_NAME")
        ?: System.getProperty("e2eTestNetwork", "smoke-tests-network")
)
