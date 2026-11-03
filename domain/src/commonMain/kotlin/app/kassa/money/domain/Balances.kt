package app.kassa.money.domain

/**
 * What every account holds today, from the entries alone: the account's opening balance, plus income, person money in,
 * sales and upward balance corrections, minus expenses, person money out, buys and downward corrections, minus transfers
 * out and plus transfers in. Android reads the same sum in SQL (`AccountDao.observeBalances`); this is the plain Kotlin
 * twin for apps that hold the entries in memory (iOS), and a test keeps the two in step through [Transaction.effectOn].
 * An investment account's holdings are not in it (see [Holding.value]); a debt's balance is negative while it is owed.
 */
object Balances {

    /** Account id to its balance, for every account in [accounts]; ids that no account has are ignored. */
    fun of(accounts: List<Account>, transactions: Iterable<Transaction>): Map<Long, Long> {
        val result = HashMap<Long, Long>()
        for (a in accounts) result[a.id] = a.openingBalance
        for (t in transactions) {
            val from = t.accountId
            if (from != null && result.containsKey(from)) result[from] = result.getValue(from) + signed(t)
            val to = t.toAccountId
            if (t.kind == TxKind.TRANSFER && to != null && result.containsKey(to)) result[to] = result.getValue(to) + t.amount
        }
        return result
    }

    /** What an entry does to the balance of the account it is on: up for the money-in kinds, down for everything else. */
    private fun signed(t: Transaction): Long = when (t.kind) {
        TxKind.INCOME, TxKind.PERSON_IN, TxKind.SELL, TxKind.ADJUST_IN -> t.amount
        else -> -t.amount
    }
}
