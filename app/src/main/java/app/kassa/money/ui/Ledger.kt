package app.kassa.money.ui

import app.kassa.money.data.repo.AccountsState
import app.kassa.money.data.repo.LoansState
import app.kassa.money.data.repo.PeopleState
import app.kassa.money.domain.Trade
import app.kassa.money.domain.Account
import app.kassa.money.domain.Budget
import app.kassa.money.domain.Budgets
import app.kassa.money.domain.CardCycle
import app.kassa.money.domain.CardSnapshot
import app.kassa.money.domain.CountedSpending
import app.kassa.money.domain.Category
import app.kassa.money.domain.Holding
import app.kassa.money.domain.InvestmentTransfer
import app.kassa.money.domain.Money
import app.kassa.money.domain.Patterns
import app.kassa.money.domain.Period
import app.kassa.money.domain.Recurring
import app.kassa.money.domain.Repeat
import app.kassa.money.domain.Spending
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.WeekPace
import app.kassa.money.domain.personBalance
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.WeekFields
import java.util.Locale

/**
 * One consistent snapshot of what the screens show: categories, accounts, budgets, people, bills, investments and
 * the entries of roughly the last four months (enough for card statements and three months of comparisons).
 * Screens that need older entries load them themselves.
 */
data class Ledger(
    val categories: List<Category>,
    val accounts: AccountsState,
    val budgets: List<Budget>,
    val people: PeopleState,
    val recent: List<Transaction>,
    val bills: List<Recurring>,
    val holdings: List<Holding>,
    val transfers: List<InvestmentTransfer>,
    /** The shares and price behind each buy or sale, by its entry's id. Older trades have none. */
    val trades: Map<Long, Trade>,
    /** What repeats, from about two years of entries (see [Repeats]). */
    val repeats: List<Repeat>,
    /** Keys of notifications the user cleared (see Notice.key). */
    val cleared: Set<String>,
    /** Loan terms, term changes and recorded payments (see [LoansState]). */
    val loans: LoansState,
    val monthStartDay: Int,
    val today: LocalDate,
    val zone: ZoneId,
) {
    val categoryById: Map<Long, Category> = categories.associateBy { it.id }
    val accountById: Map<Long, Account> = accounts.accounts.associateBy { it.id }

    /**
     * [recent] as spending sees it (see [CountedSpending]): a loan payment's principal comes back as an expense in its
     * principal category when the loan counts the whole payment. Every figure of spending, Insights and budgets reads this;
     * balances, cards and bills read [recent], which is never changed.
     */
    val counted: List<Transaction> by lazy { counted(recent) }

    /** [counted] without loan payment entries: what Top store, Big purchases and Small purchases read (see `Patterns.purchases`). */
    val purchases: List<Transaction> by lazy { Patterns.purchases(counted, loans.payments) }

    /** Any list of entries (a month, a day) as spending sees it; see [counted]. */
    fun counted(list: List<Transaction>): List<Transaction> = CountedSpending.apply(list, loans.payments, loans.terms, categories)

    /** The budget month containing today. */
    val period: Period get() = Period.containing(today, monthStartDay)

    /** The phone's first day of the week (Sunday in the US). Every "this week" starts here. */
    val firstDayOfWeek: DayOfWeek get() = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val weekStart: LocalDate get() = WeekPace.startOf(today, firstDayOfWeek)

    fun holdingsValue(accountId: Long): Long = holdings.filter { it.accountId == accountId }.sumOf { it.value }

    /** Every card with a statement day, with where it stands today. */
    fun cards(): List<CardSnapshotFor> = accounts.accounts
        .filter { it.type.hasStatement && it.isActive && it.statementDay != null }
        .map { CardSnapshotFor(it, CardCycle.snapshot(it, recent, today, zone, balance = accounts.balances[it.id] ?: 0L)) }

    /** Person id to what they owe you (positive) or you owe them (negative). */
    fun personBalances(): Map<Long, Long> = people.people.associate { it.id to personBalance(it.id, people.loans, people.payments) }

    /** What active people owe or are owed: the Receivable and Payable totals. Archived people are left out, like archived accounts. */
    fun openPersonBalances(): Map<Long, Long> = people.people.filter { !it.archived }.associate { it.id to personBalance(it.id, people.loans, people.payments) }

    /** "Groceries: $412 of $600. On pace." Shown after saving an expense. */
    fun spendingLine(categoryId: Long?): String? {
        val top = Spending.topOf(categoryId, categoryById) ?: return null
        val spent = Spending.byTopCategory(counted, categoryById, period.start, period.endInclusive, zone)[top.id] ?: 0
        val budget = Budgets.amountFor(top.id, period.yearMonth, budgets)
        // While hidden, only the masked figure: "over budget" or a percentage ahead of pace would give the size away.
        if (budget == null || budget == 0L || Money.isHidden) return "${top.name}: ${Money.format(spent, wholeDollars = true)} this month."
        val pace = budget * period.progress(today)
        val tail = when {
            spent > budget -> "${Money.format(spent - budget, wholeDollars = true)} over budget."
            spent > pace * 1.05 -> "${Math.round((spent / pace - 1) * 100)}% ahead of an even pace."
            else -> "On pace."
        }
        return "${top.name}: ${Money.format(spent, wholeDollars = true)} of ${Money.format(budget, wholeDollars = true)}. $tail"
    }
}

data class CardSnapshotFor(val account: Account, val snapshot: CardSnapshot)
