package app.kassa.money.domain

import kotlinx.datetime.TimeZone

/*
 * The rules of a recurring bill that both apps share: what a bill form refuses, which month approving a bill marks
 * handled, and the entry an approval records. Android's `BillRepository`, `LedgerViewModel.approveBill` and `BillPage`
 * say the same things; the iOS app reaches this through `shared/.../DueBridge.kt`. Plain Kotlin, no Android.
 */
object BillRules {
    const val NAME = "NAME"
    const val CATEGORY = "CATEGORY"
    const val ACCOUNT = "ACCOUNT"
    const val AMOUNT = "AMOUNT"
    const val DAY = "DAY"

    /** One thing wrong with a bill form: [field] is one of the constants above. */
    data class Problem(val field: String, val message: String)

    /**
     * Every problem of a bill as typed, in the order the form checks them (name, category, account, then the optional fixed
     * amount and the day). [typedAmount] is the amount text as typed ("" for none, which means "estimate each month");
     * [fixedCents] is what it parses to (null when it doesn't). Zero counts as none, as on Android.
     */
    fun problems(name: String, day: Int?, typedAmount: String, fixedCents: Long?, categoryId: Long?, accountId: Long?): List<Problem> {
        val out = ArrayList<Problem>()
        InputRules.nameProblem(name, "bill")?.let { out += Problem(NAME, it) }
        if (categoryId == null || categoryId <= 0) out += Problem(CATEGORY, "Pick a category.")
        if (accountId == null || accountId <= 0) out += Problem(ACCOUNT, "Pick an account.")
        if (typedAmount.isNotBlank() && (fixedCents == null || fixedCents > 0)) {
            InputRules.amountProblem(fixedCents)?.let { out += Problem(AMOUNT, it) }
        }
        InputRules.dayProblem(day, "day")?.let { out += Problem(DAY, it) }
        return out
    }

    /** The fixed amount a typed amount means: null for none (empty or zero), so the bill is estimated. */
    fun fixedAmount(cents: Long?): Long? = cents?.takeIf { it > 0 }

    /** yyyyMM of the month the bill due on [dueDay] (epoch day) belongs to: what "handled" stores. */
    fun handledMonth(dueDay: Int): Int = Budgets.key(YearMonth.from(localDateOfEpochDay(dueDay)))

    /**
     * The entry an approval records: an expense of [amount] on the bill's account and category, noted with the bill's name,
     * at 09:00 on the due day, or today when the bill is paid ahead of its day (the money moved today).
     */
    fun approveDraft(bill: Recurring, amount: Long, dueDay: Int, today: Int, zone: TimeZone): EntryRules.Draft =
        EntryRules.Draft(
            id = null, kind = TxKind.EXPENSE, amount = amount, accountId = bill.accountId, toAccountId = null,
            categoryId = bill.categoryId, epochMillis = millisAt(localDateOfEpochDay(minOf(dueDay, today)), 9, 0, zone), note = bill.name,
        )

    /**
     * What Notifications suggests as bills: monthly expenses with the same amount the last three times, that aren't a bill yet
     * (matched by [Repeats.key] on the name) and whose category is still there and not hidden.
     */
    fun suggestions(repeats: List<Repeat>, bills: List<Recurring>, categories: Map<Long, Category>): List<Repeat> {
        val billKeys = bills.map { Repeats.key(it.name) }.toSet()
        return repeats.filter { it.steadyMonthly && it.key !in billKeys && categories[it.categoryId]?.hidden == false }
    }
}
