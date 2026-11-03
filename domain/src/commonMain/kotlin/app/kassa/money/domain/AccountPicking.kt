package app.kassa.money.domain

/** The kind chips of the account picker. */
enum class AccountKindFilter(val label: String) {
    ALL("All"), CASH_AND_BANK("Cash and bank"), CARDS("Cards"), LOANS("Loans"), INVESTMENTS("Investments"), OTHER("Other")
}

/**
 * What the account picker shows, worked out from the accounts alone: closed and archived accounts and the ones the
 * caller excludes are left out, the rest are grouped by kind (the order of [AccountSection]) and A to Z inside each
 * group. Nothing is preselected and no amount is involved. Plain Kotlin so the rules have JVM tests.
 */
object AccountPicking {
    data class Group(val section: AccountSection, val accounts: List<Account>)

    fun kindOf(account: Account): AccountKindFilter = when (AccountSection.of(account.type)) {
        AccountSection.CASH_AND_BANK -> AccountKindFilter.CASH_AND_BANK
        AccountSection.INVESTMENTS -> AccountKindFilter.INVESTMENTS
        AccountSection.CREDIT_CARDS -> AccountKindFilter.CARDS
        AccountSection.LOANS -> AccountKindFilter.LOANS
        AccountSection.OTHER_ASSETS, AccountSection.OTHER_DEBTS -> AccountKindFilter.OTHER
    }

    /** Open accounts the caller hasn't excluded. */
    fun available(accounts: List<Account>, excluded: Set<Long> = emptySet()): List<Account> =
        accounts.filter { it.isActive && it.id !in excluded }

    /** "All" first, then only the kinds that have an account to pick. */
    fun filtersFor(available: List<Account>): List<AccountKindFilter> {
        val kinds = available.map(::kindOf).toSet()
        return listOf(AccountKindFilter.ALL) + AccountKindFilter.entries.filter { it != AccountKindFilter.ALL && it in kinds }
    }

    /** The groups to show for a kind chip and typed text (name or last four digits, ignoring case). */
    fun groups(available: List<Account>, filter: AccountKindFilter = AccountKindFilter.ALL, query: String = ""): List<Group> {
        val q = query.trim()
        val shown = available.filter {
            (filter == AccountKindFilter.ALL || kindOf(it) == filter) &&
                (q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.last4?.contains(q) == true)
        }
        return AccountSection.entries.mapNotNull { s ->
            val mine = shown.filter { AccountSection.of(it.type) == s }
            if (mine.isEmpty()) null else Group(s, mine.sortedWith(Names.order { it.name }))
        }
    }

    /** The accounts [usedIds] (newest use first) names that are still available, at most [limit]. Never a guess: only what was used. */
    fun recent(usedIds: List<Long>, available: List<Account>, limit: Int): List<Account> {
        val byId = available.associateBy { it.id }
        return usedIds.distinct().mapNotNull { byId[it] }.take(limit)
    }
}
