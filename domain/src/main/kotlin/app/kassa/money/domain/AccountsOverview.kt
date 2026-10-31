package app.kassa.money.domain

/**
 * The kinds of group the Accounts tab shows, in the order they appear. A group exists only when the person has an
 * open account of that kind. Assets sit on the left of an unfolded screen, liabilities on the right.
 */
enum class AccountSection(val title: String, val isDebt: Boolean) {
    CASH_AND_BANK("Cash and bank", false),
    INVESTMENTS("Investments", false),
    OTHER_ASSETS("Other assets", false),
    CREDIT_CARDS("Credit cards", true),
    LOANS("Loans", true),
    OTHER_DEBTS("Other debts", true);

    companion object {
        fun of(type: AccountType): AccountSection = when (type) {
            AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID, AccountType.FIXED_DEPOSIT -> CASH_AND_BANK
            AccountType.INVESTMENT -> INVESTMENTS
            AccountType.OTHER_ASSET -> OTHER_ASSETS
            AccountType.CREDIT_CARD -> CREDIT_CARDS
            AccountType.LOAN -> LOANS
            AccountType.OTHER_DEBT -> OTHER_DEBTS
        }
    }
}

/**
 * One account as the Accounts tab shows it. [shown] is the main figure: what you have for an asset, what you owe
 * for a debt (so an overpaid card is negative, a credit).
 */
data class AccountLine(val account: Account, val shown: Long)

/** One group: its [lines] in the order asked for, and their [total] (owed, for a debt group). */
data class AccountGroupView(val section: AccountSection, val lines: List<AccountLine>) {
    val total: Long get() = lines.sumOf { it.shown }
}

/**
 * What the Accounts tab shows, worked out from the accounts alone: Assets, Liabilities and Net worth, then the
 * groups. Closed and archived accounts are left out of every figure and group; closed ones are listed in [closed].
 * Nothing here is a forecast or a rate: it is what you have, what you owe, and the sums of those.
 */
data class AccountsOverview(
    val assets: Long,
    val liabilities: Long,
    val assetGroups: List<AccountGroupView>,
    val liabilityGroups: List<AccountGroupView>,
    val closed: List<Account>,
) {
    val netWorth: Long get() = assets - liabilities
    val groups: List<AccountGroupView> get() = assetGroups + liabilityGroups

    companion object {
        /**
         * [value] is an account's worth as an asset or its stored (negative) balance as a debt: an investment account
         * adds its holdings, so the caller supplies it. [receivable] and [payable] are what people owe you and what
         * you owe them, added to Assets and Liabilities. [order] arranges the lines inside each group.
         */
        fun of(
            accounts: List<Account>,
            value: (Account) -> Long,
            receivable: Long = 0,
            payable: Long = 0,
            order: Comparator<AccountLine> = Names.order { it.account.name },
        ): AccountsOverview {
            val open = accounts.filter { it.isActive }
            val lines = open.map { a -> AccountLine(a, if (a.type.isDebt) -value(a) else value(a)) }
            val groups = AccountSection.entries.mapNotNull { s ->
                val mine = lines.filter { AccountSection.of(it.account.type) == s }
                if (mine.isEmpty()) null else AccountGroupView(s, mine.sortedWith(order))
            }
            val assetGroups = groups.filter { !it.section.isDebt }
            val liabilityGroups = groups.filter { it.section.isDebt }
            return AccountsOverview(
                assets = assetGroups.sumOf { it.total } + receivable,
                liabilities = liabilityGroups.sumOf { it.total } + payable,
                assetGroups = assetGroups,
                liabilityGroups = liabilityGroups,
                closed = accounts.filter { !it.archived && it.isClosed }.sortedWith(Names.order { it.name }),
            )
        }

        /** A payment due on [due] is urgent from that day on: due today or overdue. Both are epoch days. */
        fun dueNeedsAttention(due: Int, today: Int): Boolean = due <= today
    }
}

/** Why an account can't be closed yet, or null when it can. Only a settled account closes. */
fun closeProblem(balance: Long): String? =
    if (balance == 0L) null else "Pay off or move the balance first."
