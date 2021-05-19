package net.corda.confidentialexchange.states

import net.corda.confidentialexchange.contracts.ExchangeableStateContract
import net.corda.v5.application.identity.AbstractParty
import net.corda.v5.application.identity.AnonymousParty
import net.corda.v5.application.identity.Party
import net.corda.v5.ledger.UniqueIdentifier
import net.corda.v5.ledger.contracts.BelongsToContract
import net.corda.v5.ledger.contracts.LinearState

@BelongsToContract(ExchangeableStateContract::class)
data class ExchangeableState(
    val issuer : Party,
    val owner : AnonymousParty,
    override val linearId: UniqueIdentifier = UniqueIdentifier()
) : LinearState {

    override val participants: List<AbstractParty>
        get() = listOf(owner)
}