package app.kassa.money.domain

import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone

/**
 * Synthetic data for the Coach tests. Today is Thursday 12 November 2026 unless a test says otherwise. Every entry gets a unique id (the
 * rules skip known payments by id). Categories: Groceries 10, Dining 11 (with subcategory Restaurants 13), Rent 12, Internet 14,
 * Salary 20 (income). Accounts: Everyday checking 1, Rainy savings 2.
 */
class CoachFx(todayText: String = "2026-11-12") {
    val zone: TimeZone = TimeZone.UTC
    val today: LocalDate = LocalDate.parse(todayText)
    private var seq = 0L
    val txs = ArrayList<Transaction>()
    val accounts = arrayListOf(
        Account(id = 1, name = "Everyday", type = AccountType.CHECKING),
        Account(id = 2, name = "Rainy", type = AccountType.SAVINGS),
    )
    val categories = arrayListOf(
        cat(10, "Groceries"), cat(11, "Dining"), cat(12, "Rent"), cat(13, "Restaurants", parent = 11), cat(14, "Internet"),
        cat(20, "Salary", CategoryKind.INCOME),
    )
    val budgets = ArrayList<Budget>()
    val bills = ArrayList<Recurring>()
    val loanTerms = HashMap<Long, LoanTerms>()
    val loanPayments = ArrayList<LoanPayment>()
    var monthStart = 1
    var firstDay = DayOfWeek.MONDAY

    private fun cat(id: Long, name: String, kind: CategoryKind = CategoryKind.EXPENSE, parent: Long? = null) =
        Category(id = id, name = name, kind = kind, parentId = parent, color = 0)

    fun at(date: String): Long = millisAt(LocalDate.parse(date), 12, 0, zone)

    fun account(id: Long, name: String, type: AccountType, opening: Long = 0L, statementDay: Int? = null, dueDay: Int? = null) {
        accounts.add(Account(id = id, name = name, type = type, openingBalance = opening, statementDay = statementDay, dueDay = dueDay))
    }

    fun opening(id: Long, cents: Long) {
        val i = accounts.indexOfFirst { it.id == id }
        accounts[i] = accounts[i].copy(openingBalance = cents)
    }

    fun spend(date: String, cents: Long, cat: Long? = 10, note: String = "", account: Long? = 1, share: Long? = null, person: Long? = null): Long {
        val id = ++seq
        txs.add(
            Transaction(
                id = id, kind = TxKind.EXPENSE, amount = cents, accountId = account, categoryId = cat, epochMillis = at(date),
                note = note, myShare = share, personId = person,
            ),
        )
        return id
    }

    fun earn(date: String, cents: Long, note: String = "", cat: Long? = 20, account: Long = 1): Long {
        val id = ++seq
        txs.add(Transaction(id = id, kind = TxKind.INCOME, amount = cents, accountId = account, categoryId = cat, epochMillis = at(date), note = note))
        return id
    }

    fun other(kind: TxKind, date: String, cents: Long, account: Long = 1, to: Long? = null, person: Long? = null, note: String = ""): Long {
        val id = ++seq
        txs.add(Transaction(id = id, kind = kind, amount = cents, accountId = account, toAccountId = to, personId = person, epochMillis = at(date), note = note))
        return id
    }

    /** The same charge on the [day] of each listed month ("2026-08", ...). */
    fun monthly(months: List<String>, day: Int, cents: Long, cat: Long?, note: String, income: Boolean = false) {
        for (m in months) {
            val date = "$m-${day.toString().padStart(2, '0')}"
            if (income) earn(date, cents, note, cat) else spend(date, cents, cat, note)
        }
    }

    fun day(text: String): Int = LocalDate.parse(text).toEpochDay().toInt()

    /** [extra] replaces a computed balance, so a test can say what an account holds without building its history. */
    fun input(extra: Map<Long, Long> = emptyMap()): CoachInput = CoachInput(
        accounts = accounts, balances = Balances.of(accounts, txs) + extra, transactions = txs, categories = categories,
        budgets = budgets, bills = bills, loanTerms = loanTerms, loanChanges = emptyList(), loanPayments = loanPayments,
        monthStartDay = monthStart, today = today, zone = zone, firstDay = firstDay,
    )

    companion object {
        val AUG_TO_OCT = listOf("2026-08", "2026-09", "2026-10")

        /**
         * Three full months of life: groceries of 100.00 on the 10th, rent of 1,500.00 on the 1st (also 1 November), a salary of 4,000.00
         * on the 28th. Entries start 1 August.
         */
        fun lived(): CoachFx {
            val fx = CoachFx()
            fx.monthly(AUG_TO_OCT, 10, 10_000, 10, "")
            fx.monthly(AUG_TO_OCT + "2026-11", 1, 150_000, 12, "Rent")
            fx.monthly(AUG_TO_OCT, 28, 400_000, 20, "Paycheck", income = true)
            return fx
        }
    }
}
