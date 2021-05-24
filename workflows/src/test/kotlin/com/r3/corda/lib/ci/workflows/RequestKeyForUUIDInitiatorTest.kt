package com.r3.corda.lib.ci.workflows

import io.mockk.every
import io.mockk.mockk
import net.corda.v5.application.flows.FlowSession
import net.corda.v5.application.identity.AnonymousParty
import net.corda.v5.application.identity.Party
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.util.UUID

class RequestKeyForUUIDInitiatorTest {
    @Test
    fun `call returns the sub flow`() {
        val party = mockk<Party>()
        val uuid = UUID.fromString("32e3fa7f-8986-404e-b07e-b5e1559b16d1")
        val request = RequestKeyForUUIDInitiator(party, uuid)
        val session = mockk<FlowSession>()
        val expected = mockk<AnonymousParty>()
        request.flowEngine = mockk {
            every { subFlow(any<RequestKeyFlow>()) } returns expected
        }
        request.flowMessaging = mockk {
            every { initiateFlow(party) } returns session
        }

        val returned = request.call()

        assertThat(returned).isEqualTo(expected)
    }
}
