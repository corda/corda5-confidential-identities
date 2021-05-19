package net.corda.confidentialexchange.contracts

import net.corda.v5.ledger.contracts.CommandData
import net.corda.v5.ledger.contracts.Contract
import net.corda.v5.ledger.transactions.LedgerTransaction

class ExchangeableStateContract : Contract {
    companion object {
        const val ID = "net.corda.confidentialexchange.contracts.ExchangeStateContract"
    }

    override fun verify(tx: LedgerTransaction) {
    }

    interface Commands : CommandData {
        class Issue : Commands
        class Move : Commands
    }
}