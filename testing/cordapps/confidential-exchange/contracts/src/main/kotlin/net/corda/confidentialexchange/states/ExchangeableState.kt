package net.corda.confidentialexchange.states

import net.corda.confidentialexchange.contracts.ExchangeableStateContract
import net.corda.v5.application.identity.AbstractParty
import net.corda.v5.application.identity.Party
import net.corda.v5.application.utilities.JsonRepresentable
import net.corda.v5.ledger.UniqueIdentifier
import net.corda.v5.ledger.contracts.BelongsToContract
import net.corda.v5.ledger.contracts.LinearState

@BelongsToContract(ExchangeableStateContract::class)
data class ExchangeableState(
    val issuer : Party,
    val owner : AbstractParty,
    override val linearId: UniqueIdentifier = UniqueIdentifier()
) : LinearState, JsonRepresentable {

    override val participants: List<AbstractParty>
        get() = listOf(owner)

    override fun toJsonString(): String {
        return """
            {
                "issuer" : "${issuer.name}",
                "owner" : "${owner.nameOrNull()}",
                "linearId" : "$linearId"
            }
        """.trimIndent()
    }
}