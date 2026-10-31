package app.kassa.money.domain

/**
 * A dated balance correction: the smallest way to make an account show what its bank or lender shows without rewriting
 * anything before it. It is an ordinary entry of kind [TxKind.ADJUST_IN] (balance goes up) or [TxKind.ADJUST_OUT]
 * (balance goes down), so it moves the balance, backs up, restores and shows in Activity like any entry, and because
 * every spending, income, budget and daily figure counts only expenses and income, it is in none of them.
 *
 * The amount is stored positive, like every amount; the kind carries the direction. Screens show it as a change with an
 * arrow (see [shown]), never with a minus.
 */
object BalanceCorrection {

    /** What Activity calls it when the entry has no note. */
    const val TITLE = "Balance correction"

    fun isCorrection(kind: TxKind): Boolean = kind == TxKind.ADJUST_IN || kind == TxKind.ADJUST_OUT

    /** How much the account's balance has to change to reach [target] from [balance]; zero when they already match. */
    fun change(target: Long, balance: Long): Long = target - balance

    /** The kind for a [change] to the balance: up is [TxKind.ADJUST_IN], down is [TxKind.ADJUST_OUT]. Null for no change. */
    fun kindFor(change: Long): TxKind? = when {
        change > 0 -> TxKind.ADJUST_IN
        change < 0 -> TxKind.ADJUST_OUT
        else -> null
    }

    /** The entry for [change] to [accountId]'s balance, or null when there is nothing to correct. */
    fun entry(id: Long, accountId: Long, change: Long, epochMillis: Long): Transaction? =
        kindFor(change)?.let { Transaction(id = id, kind = it, amount = Math.abs(change), accountId = accountId, epochMillis = epochMillis) }

    /** The figure screens show for a [balance]: what is owed for a debt (a loan, a card), the balance itself for the rest. */
    fun shownFrom(balance: Long, debt: Boolean): Long = if (debt) -balance else balance

    /** The balance an account has to have for the figure the person typed, which a debt reads as what is owed. */
    fun targetFrom(typed: Long, debt: Boolean): Long = if (debt) -typed else typed

    /** The problem with a [change], as a message to show, or null. A correction can't be zero or beyond the largest amount. */
    fun problem(change: Long): String? =
        if (change == 0L) "That's the balance Kassa already shows." else InputRules.amountProblem(Math.abs(change))

    /**
     * The change as the person reads it: positive when the figure shown for the account went up. A debt (a loan, a card)
     * is shown as what is owed, so a correction that raises its balance lowers what is owed.
     */
    fun shown(t: Transaction, debt: Boolean): Long {
        val balance = when (t.kind) {
            TxKind.ADJUST_IN -> t.amount
            TxKind.ADJUST_OUT -> -t.amount
            else -> 0L
        }
        return if (debt) -balance else balance
    }

    /** "↑ " for a rise, "↓ " for a fall, "" for none; followed by the amount through `Money.format`. */
    fun arrow(shown: Long): String = when {
        // The direction would give the figure away while balances are hidden.
        Money.isHidden -> ""
        shown > 0 -> "↑ "
        shown < 0 -> "↓ "
        else -> ""
    }

    /** An arrow and the size of the change, like "↓ 24.80". */
    fun describe(shown: Long): String = arrow(shown) + Money.format(Math.abs(shown))
}
