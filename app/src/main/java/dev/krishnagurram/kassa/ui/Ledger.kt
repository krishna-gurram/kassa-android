package dev.krishnagurram.kassa.ui

import dev.krishnagurram.kassa.data.repo.AccountsState
import dev.krishnagurram.kassa.data.repo.PeopleState
import dev.krishnagurram.kassa.domain.Trade
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.Budget
import dev.krishnagurram.kassa.domain.Budgets
import dev.krishnagurram.kassa.domain.CardCycle
import dev.krishnagurram.kassa.domain.CardSnapshot
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.Holding
import dev.krishnagurram.kassa.domain.InvestmentTransfer
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Period
import dev.krishnagurram.kassa.domain.Recurring
import dev.krishnagurram.kassa.domain.Repeat
import dev.krishnagurram.kassa.domain.Spending
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.WeekPace
import dev.krishnagurram.kassa.domain.personBalance
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
    val monthStartDay: Int,
    val today: LocalDate,
    val zone: ZoneId,
) {
    val categoryById: Map<Long, Category> = categories.associateBy { it.id }
    val accountById: Map<Long, Account> = accounts.accounts.associateBy { it.id }

    /** The budget month containing today. */
    val period: Period get() = Period.containing(today, monthStartDay)

    /** The phone's first day of the week (Sunday in the US). Every "this week" starts here. */
    val firstDayOfWeek: DayOfWeek get() = WeekFields.of(Locale.getDefault()).firstDayOfWeek
    val weekStart: LocalDate get() = WeekPace.startOf(today, firstDayOfWeek)

    fun holdingsValue(accountId: Long): Long = holdings.filter { it.accountId == accountId }.sumOf { it.value }

    /** Every card with a statement day, with where it stands today. */
    fun cards(): List<CardSnapshotFor> = accounts.accounts
        .filter { it.type.hasStatement && !it.archived && it.statementDay != null }
        .map { CardSnapshotFor(it, CardCycle.snapshot(it, recent, today, zone, balance = accounts.balances[it.id] ?: 0L)) }

    /** Person id to what they owe you (positive) or you owe them (negative). */
    fun personBalances(): Map<Long, Long> = people.people.associate { it.id to personBalance(it.id, people.loans, people.payments) }

    /** "Groceries: $412 of $600. On pace." Shown after saving an expense. */
    fun spendingLine(categoryId: Long?): String? {
        val top = Spending.topOf(categoryId, categoryById) ?: return null
        val spent = Spending.byTopCategory(recent, categoryById, period.start, period.endInclusive, zone)[top.id] ?: 0
        val budget = Budgets.amountFor(top.id, period.yearMonth, budgets)
        if (budget == null || budget == 0L) return "${top.name}: ${Money.format(spent, wholeDollars = true)} this month."
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
