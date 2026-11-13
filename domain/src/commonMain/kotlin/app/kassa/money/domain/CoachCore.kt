package app.kassa.money.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/*
 * Coach, layer 1: the rules. Everything the Coach screens say about your money is plain arithmetic on your own entries, worked out on
 * the phone, so every figure can be explained and nothing needs a network. Plain Kotlin, no Android, deterministic: the same input
 * always gives the same answer, and every list and loop is bounded by the input and the small constants below.
 *
 * Spending is always the entries as spending sees them ([CountedSpending]) with an explicit EXPENSE or INCOME filter, at the person's
 * share ([Transaction.categoryAmount]). Balance corrections, transfers and person payments never count. Nothing here writes
 * anything: a result is numbers and kind codes, and the screens word them. Coach never changes data and never guesses an account.
 */

/**
 * What the Coach rules read: the same data the app already holds. [balances] is account id to balance ([Balances.of]); [transactions]
 * is every entry the app has loaded (raw, not counted: each rule applies [CountedSpending] itself, and repeats and bills are matched on
 * the raw entries like everywhere else). [monthStartDay] is the budget month's first day, 1 to 28. [firstDay] is the phone's first
 * weekday for the week rules.
 */
data class CoachInput(
    val accounts: List<Account>,
    val balances: Map<Long, Long>,
    val transactions: List<Transaction>,
    val categories: List<Category>,
    val budgets: List<Budget> = emptyList(),
    val bills: List<Recurring> = emptyList(),
    val loanTerms: Map<Long, LoanTerms> = emptyMap(),
    val loanChanges: List<LoanTermChange> = emptyList(),
    val loanPayments: List<LoanPayment> = emptyList(),
    val monthStartDay: Int = 1,
    val today: LocalDate,
    val zone: TimeZone,
    val firstDay: DayOfWeek = DayOfWeek.MONDAY,
)

/** Whole-number division for non-negative amounts, so no rule needs floating point for money. */
object CoachMath {
    /** [a] over [b] rounded half up; 0 when [b] is not above zero. */
    fun divHalfUp(a: Long, b: Long): Long = if (b <= 0L) 0L else (a.coerceAtLeast(0L) * 2 + b) / (b * 2)

    /** [a] over [b] rounded up; 0 when [b] is not above zero. */
    fun divCeil(a: Long, b: Long): Long = if (b <= 0L) 0L else (a.coerceAtLeast(0L) + b - 1) / b

    /** [a] over [b] rounded down; 0 when [b] is not above zero. */
    fun divFloor(a: Long, b: Long): Long = if (b <= 0L) 0L else a.coerceAtLeast(0L) / b
}

/** The entries of one [CoachInput] prepared once: counted spending and income by day and top-level category, and what recurs. */
internal class CoachLedger(val input: CoachInput) {

    /** One counted entry: its id, epoch day, top-level category id (0 for none) and the amount that is yours. */
    class Spend(val id: Long, val day: Int, val top: Long, val amount: Long)

    val todayDay: Int = input.today.toEpochDay().toInt()
    val startDay: Int = input.monthStartDay.coerceIn(1, 28)
    val current: Period = Period.containing(input.today, startDay)
    val activeAccounts: List<Account> = input.accounts.filter { it.isActive }
    private val byId: Map<Long, Category> = input.categories.associateBy { it.id }

    /** The earliest day any entry is dated; null with no entries. */
    val firstEntryDay: Int? = input.transactions.minOfOrNull { dayOf(it) }

    private val counted: List<Transaction> by lazy {
        CountedSpending.apply(input.transactions, input.loanPayments, input.loanTerms, input.categories)
    }

    /**
     * Counted EXPENSE entries (explicit kind filter; corrections, transfers and person payments are not in it), without the entries that
     * only pay a card or other debt off ([debtPaymentIds]): paying a card is moving money to what you owe, not spending (what was bought
     * with the card is already in its own categories).
     */
    val expenses: List<Spend> by lazy { counted.filter { it.kind == TxKind.EXPENSE && it.id !in debtPaymentIds }.map { spendOf(it) } }

    /** Counted INCOME entries. */
    val incomes: List<Spend> by lazy { counted.filter { it.kind == TxKind.INCOME }.map { spendOf(it) } }

    /** The top-level expense categories, by id. */
    val topExpenseIds: Set<Long> by lazy {
        input.categories.filter { it.parentId == null && it.kind == CategoryKind.EXPENSE }.map { it.id }.toSet()
    }

    /**
     * Every card and other debt account, open or closed. Loans are left out on purpose: Kassa writes a loan's own interest and escrow as
     * expenses with the loan's name in the note, and those are spending ([loanKeys] keeps them out of what repeats instead).
     */
    private val debtAccounts: List<Account> = input.accounts.filter { it.type == AccountType.CREDIT_CARD || it.type == AccountType.OTHER_DEBT }

    /**
     * The ids of expenses that pay one of the person's own debts off, found only from the person's own data and never from a brand or a
     * word: expenses whose category is named like a card or other debt account, or whose note is its name or starts with it ("Credit card
     * payment" on an account called "Credit card"). Kassa's own card payment is a TRANSFER and is never counted anyway; this covers payments typed in or imported as expenses. They are out of pace, usual spending, the
     * emergency fund's monthly spending and what repeats.
     */
    val debtPaymentIds: Set<Long> by lazy {
        val out = HashSet<Long>()
        val names = debtAccounts.map { it.name.trim().lowercase() }.filter { it.length >= 2 }.toSet()
        if (names.isNotEmpty()) {
            for (t in input.transactions) {
                if (t.kind != TxKind.EXPENSE) continue
                val noteKey = Repeats.key(t.note)
                val byNote = names.any { noteKey == it || noteKey.startsWith("$it ") }
                val byCategory = categoryChain(t.categoryId).any { it.trim().lowercase() in names }
                if (byNote || byCategory) out.add(t.id)
            }
        }
        out
    }

    /** The entries a loan payment is made of: interest and escrow are spending, but never a subscription or a repeat. */
    private val loanPaymentEntryIds: Set<Long> by lazy {
        input.loanPayments.flatMap { listOfNotNull(it.principalEntryId, it.interestEntryId, it.otherEntryId) }.toSet()
    }

    private fun categoryChain(id: Long?): List<String> {
        val out = ArrayList<String>()
        var c = id?.let { byId[it] }
        var guard = 0
        while (c != null && guard++ < 4) {
            out.add(c.name)
            c = c.parentId?.let { byId[it] }
        }
        return out
    }

    private fun dayOf(t: Transaction): Int = dateOfMillis(t.epochMillis, input.zone).toEpochDay().toInt()

    private fun spendOf(t: Transaction): Spend = Spend(t.id, dayOf(t), Spending.topOf(t.categoryId, byId)?.id ?: 0L, t.categoryAmount)

    /** The budget period [back] months before the current one. */
    fun periodBack(back: Int): Period = Period.startingIn(current.yearMonth.minusMonths(back), startDay)

    /** The [n] periods before the current one that start on or after the first entry (so they hold a whole month of the person's life), newest first. */
    fun fullPriorPeriods(n: Int): List<Period> {
        val first = firstEntryDay ?: return emptyList()
        return (1..n).map { periodBack(it) }.filter { it.start.toEpochDay().toInt() >= first }
    }

    /** What [list] adds up to from [from] to [to] (epoch days, inclusive), for one top-level category when [top] is given, skipping [skip]. */
    fun sum(list: List<Spend>, from: Int, to: Int, top: Long? = null, skip: Set<Long> = emptySet()): Long {
        var total = 0L
        for (e in list) {
            if (e.day < from || e.day > to) continue
            if (top != null && e.top != top) continue
            if (e.id in skip) continue
            total += e.amount
        }
        return total
    }

    /** Checking, savings, cash and prepaid money: what can be spent now. Fixed deposits, investments and other assets are not liquid. */
    fun liquidBalance(): Long = activeAccounts.filter { it.type in LIQUID }.sumOf { input.balances[it.id] ?: 0L }

    val hasLiquidAccount: Boolean get() = activeAccounts.any { it.type in LIQUID }

    /** What [Repeats] finds in the entries, biggest yearly cost first. */
    val repeats: List<Repeat> by lazy {
        Repeats.find(input.transactions.filter { it.id !in debtPaymentIds && it.id !in loanPaymentEntryIds }, input.today, input.zone)
    }

    /** The notes loan payments carry (also of a closed loan), so a loan's steady escrow or interest is never read as a subscription. */
    private val loanKeys: Set<String> by lazy {
        input.accounts.filter { it.type == AccountType.LOAN }.flatMap { LoanNotes.repeatKeys(it.name) }.toSet()
    }

    /** Fixed monthly expenses: subscriptions, rent, insurance. */
    val monthlyRepeats: List<Repeat> by lazy {
        repeats.filter { it.kind == TxKind.EXPENSE && it.cadence == Cadence.MONTHLY && it.fixed && it.key !in loanKeys }
    }

    /** Fixed expenses that come back monthly, every 3 months or yearly: known costs, not habits. Weekly ones are habits and stay in variable spending. */
    val recurringRepeats: List<Repeat> by lazy {
        repeats.filter { it.kind == TxKind.EXPENSE && it.fixed && it.cadence in RECURRING_CADENCES && it.key !in loanKeys }
    }

    /**
     * The ids of the entries that are known costs and not day to day spending: every entry of a loan payment, entries that match an active
     * bill, and charges of a recurring fixed expense ([recurringRepeats], matched by note key and an amount inside its range).
     * "Usual spending" leaves these out because they are subtracted as known payments instead.
     */
    val recurringIds: Set<Long> by lazy {
        val out = HashSet<Long>()
        for (p in input.loanPayments) {
            p.principalEntryId?.let { out.add(it) }
            p.interestEntryId?.let { out.add(it) }
            p.otherEntryId?.let { out.add(it) }
        }
        val bills = input.bills.filter { it.active }
        val byKey = recurringRepeats.groupBy { it.key }
        for (t in input.transactions) {
            if (t.kind != TxKind.EXPENSE) continue
            if (bills.any { RecurringRules.matches(it, t) }) {
                out.add(t.id)
            } else {
                val rs = byKey[Repeats.key(t.note)] ?: continue
                if (rs.any { t.amount in it.low..it.high }) out.add(t.id)
            }
        }
        out
    }

    fun categoryName(id: Long): String = byId[id]?.name ?: ""

    companion object {
        val LIQUID: Set<AccountType> = setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID)
        val RECURRING_CADENCES: Set<Cadence> = setOf(Cadence.MONTHLY, Cadence.QUARTERLY, Cadence.YEARLY)
    }
}
