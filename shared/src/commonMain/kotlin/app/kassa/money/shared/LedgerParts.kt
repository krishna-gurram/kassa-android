package app.kassa.money.shared

import app.kassa.money.backup.BackupData
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Budget
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryIcon
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.Holding
import app.kassa.money.domain.InterestType
import app.kassa.money.domain.InvestmentTransfer
import app.kassa.money.domain.LoanEntry
import app.kassa.money.domain.LoanPayment
import app.kassa.money.domain.LoanTermChange
import app.kassa.money.domain.LoanTerms
import app.kassa.money.domain.Person
import app.kassa.money.domain.Recurring
import app.kassa.money.domain.Trade
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind

/**
 * A [BackupData] as the :domain model, the same mapping Android's entities do (`Entities.kt` toDomain). A row with a kind or
 * type this version does not know, or one the model refuses (a transfer without a destination), is left out instead of
 * failing the whole screen; a backup from Kassa itself never has one.
 */
internal class LedgerParts(
    val accounts: List<Account>,
    /** Newest first, then by id, like the queries on Android. */
    val transactions: List<Transaction>,
    val categories: List<Category>,
    val people: List<Person>,
    val loanEntries: List<LoanEntry>,
    val holdings: List<Holding>,
    val loanTerms: Map<Long, LoanTerms>,
    val loanChanges: List<LoanTermChange>,
    val loanPayments: List<LoanPayment>,
    /** The shares and price behind BUY and SELL entries (none for trades from before database v7). */
    val trades: List<Trade> = emptyList(),
    /** Money moved into investment accounts that is still waiting to be explained. */
    val transfers: List<InvestmentTransfer> = emptyList(),
    /** The recurring bills. */
    val bills: List<Recurring> = emptyList(),
    /** Every budget row: month 0 is every month, a yyyymm key is that month only. */
    val budgets: List<Budget> = emptyList(),
) {
    companion object {
        fun of(d: BackupData): LedgerParts {
            val accounts = d.accounts.mapNotNull { a ->
                val type = AccountType.entries.firstOrNull { it.name == a.type } ?: return@mapNotNull null
                Account(
                    id = a.id, name = a.name, type = type, groupId = a.groupId, openingBalance = a.openingBalance,
                    statementDay = a.statementDay, dueDay = a.dueDay, payFromId = a.payFromId, creditLimit = a.creditLimit,
                    archived = a.archived, sortOrder = a.sortOrder, externalId = a.externalId, last4 = a.last4,
                    maturityDay = a.maturityDay, closedDay = a.closedDay,
                )
            }
            val categories = d.categories.mapNotNull { c ->
                val kind = CategoryKind.entries.firstOrNull { it.name == c.kind } ?: return@mapNotNull null
                Category(
                    id = c.id, name = c.name, kind = kind, parentId = c.parentId, icon = CategoryIcon.decode(c.icon), color = c.color,
                    countsTowardDaily = c.countsTowardDaily, hidden = c.hidden, sortOrder = c.sortOrder, externalId = c.externalId,
                )
            }
            val transactions = d.transactions.mapNotNull { t ->
                val kind = TxKind.entries.firstOrNull { it.name == t.kind } ?: return@mapNotNull null
                try {
                    Transaction(
                        id = t.id, kind = kind, amount = t.amount, accountId = t.accountId, toAccountId = t.toAccountId,
                        categoryId = t.categoryId, epochMillis = t.epochMillis, note = t.note, personId = t.personId,
                        myShare = t.myShare, externalId = t.externalId,
                    )
                } catch (e: IllegalArgumentException) {
                    null
                }
            }.sortedWith(compareByDescending<Transaction> { it.epochMillis }.thenByDescending { it.id })
            val terms = HashMap<Long, LoanTerms>()
            for (t in d.loanTerms) {
                val type = InterestType.entries.firstOrNull { it.name == t.type } ?: continue
                terms[t.accountId] = LoanTerms(
                    t.accountId, type, t.profit, t.rate, t.payment, t.termMonths, t.originalAmount, t.startDay, t.nextPaymentDay, t.dueDay,
                    t.escrow, t.fee, t.earlyCharge, t.interestCategoryId, t.payFromId, t.countAllAsSpending, t.principalCategoryId,
                )
            }
            return LedgerParts(
                accounts = accounts,
                transactions = transactions,
                categories = categories,
                people = d.people.map { Person(it.id, it.name, it.archived) },
                loanEntries = d.loans.map { LoanEntry(it.id, it.personId, it.amount, it.epochMillis, it.note, it.txId) },
                holdings = d.holdings.map { Holding(it.id, it.accountId, it.ticker, it.microShares, it.price, it.priceMillis, it.avgCost) },
                loanTerms = terms,
                loanChanges = d.loanChanges.map { c ->
                    LoanTermChange(c.id, c.accountId, c.effectiveDay, c.rate, c.payment, c.termMonths, c.type?.let { n -> InterestType.entries.firstOrNull { it.name == n } })
                },
                loanPayments = d.loanPayments.map { p ->
                    LoanPayment(
                        p.id, p.accountId, p.day, p.amount, p.principalEntryId, p.interestEntryId, p.otherEntryId, p.escrow, p.charge,
                        p.edited, p.estimate, p.scheduled, p.countAllAsSpending, p.principalCategoryId,
                    )
                },
                trades = d.trades.map { Trade(it.id, it.transactionId, it.ticker, it.microShares, it.price) },
                transfers = d.investmentTransfers.map { InvestmentTransfer(it.id, it.transactionId, it.accountId, it.amount, it.epochMillis) },
                budgets = d.budgets.map { Budget(it.categoryId, it.month, it.amount) },
                bills = d.bills.map { Recurring(it.id, it.name, it.categoryId, it.accountId, it.dayOfMonth, it.fixedAmount, it.active, it.handledMonth) },
            )
        }
    }
}
