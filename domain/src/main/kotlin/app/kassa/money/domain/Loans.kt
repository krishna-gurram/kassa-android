package app.kassa.money.domain

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/*
 * Loans that know their terms. Plain Kotlin, no Android and no java.time: dates are epoch days (see LoanDates),
 * amounts are whole minor units in a Long, and rates are Longs in 1/10000 of a percent (6.125% is 61,250).
 *
 * Rounding rule used everywhere: interest is rounded half-up to the minor unit, principal is the remainder, so the
 * parts of a payment always add up to it. Principal is never negative and never more than what is owed, and the last
 * payment clears the balance.
 */

/** How interest is charged. Islamic contracts are not a type of their own: see [LoanTerms.profit]. */
enum class InterestType(val label: String, val hint: String) {
    /** Balance times rate over twelve, each month. UK repayment mortgages, India EMI, most bank loans. */
    REDUCING("Monthly on balance", "Most home loans, repayment mortgages and EMIs."),

    /** Balance times rate times the days since the last payment over 365. US car loans; paying early saves interest. */
    DAILY("Daily on balance", "Common for car loans in the US. Paying early saves interest."),

    /** Interest on the original amount for the whole term, spread evenly. Car and personal loans in India and the UAE. */
    FLAT("Flat rate", "Common for car and personal loans in India and the UAE."),

    /** Pay the interest, the balance stays. A switch to repayment is a term change on a date. */
    INTEREST_ONLY("Interest only", "Interest-only mortgages, or while a home is still being built."),
}

/**
 * The terms of one loan account. Dates are epoch days. The terms in effect on a date are these with every
 * [LoanTermChange] on or before it applied (see [effective]).
 *
 * [payment] is principal plus interest; [escrow] rides on top of it, so what leaves the bank each month is
 * `payment + escrow`. [nextPaymentDay] is the scheduled payment Kassa is waiting for, kept ready for Notifications.
 */
data class LoanTerms(
    /** The loan's account; one terms row per loan account. */
    val accountId: Long,
    val type: InterestType,
    /** Islamic finance: screens say "Profit" where they would say "Interest". Same maths. */
    val profit: Boolean = false,
    /** Yearly rate in 1/10000 of a percent. */
    val rate: Long,
    /** The scheduled payment, principal and interest only. */
    val payment: Long,
    /** The whole term in months, when known. */
    val termMonths: Int? = null,
    val originalAmount: Long? = null,
    /** When the loan began; for a progress bar, and for the first month of a daily loan. */
    val startDay: Int? = null,
    val nextPaymentDay: Int,
    /** Day of the month payments fall due (1 to 31); a short month uses its last day. */
    val dueDay: Int,
    /** A fixed amount inside each payment that is neither interest nor principal. */
    val escrow: Long = 0,
    /** A fee paid when the loan started. Kept for the total cost; it isn't added to what is owed. */
    val fee: Long = 0,
    /** Charge on extra or early payments, in 1/10000 of a percent of the extra amount. */
    val earlyCharge: Long = 0,
    /** Where the interest part of a payment goes; null means the "Loan interest" category. */
    val interestCategoryId: Long? = null,
    /** The account payments start from; null means nothing is preselected. */
    val payFromId: Long? = null,
    /**
     * Spending, Insights and budgets also count each payment's principal (see [CountedSpending]). Balances and net
     * worth never change. A payment can turn it off or on for itself ([LoanPayment.countAllAsSpending]).
     */
    val countAllAsSpending: Boolean = false,
    /** Where the principal counts as spending; null means the same category as the interest. */
    val principalCategoryId: Long? = null,
) {
    /** The word screens use for the cost of borrowing. */
    val interestWord: String get() = if (profit) "Profit" else "Interest"

    /** What leaves the bank for a scheduled payment. */
    val scheduledTotal: Long get() = payment + escrow

    /** Interest every month on a flat loan is on this: the original amount, or [balance] when it isn't known. */
    fun flatBase(balance: Long): Long = originalAmount ?: balance

    /** The first thing wrong with these terms, as a message, or null. Repositories throw it as [IllegalArgumentException]. */
    fun problem(): String? =
        InputRules.rateProblem(rate, "${interestWord.lowercase()} rate")
            ?: InputRules.amountProblem(payment, zeroAllowed = type == InterestType.INTEREST_ONLY)
            ?: termMonths?.let { InputRules.monthsProblem(it, "a term in months") }
            ?: originalAmount?.let { InputRules.amountProblem(it) }
            ?: InputRules.dayProblem(dueDay, "due day")
            ?: InputRules.amountProblem(escrow, zeroAllowed = true)
            ?: InputRules.amountProblem(fee, zeroAllowed = true)
            ?: InputRules.rateProblem(earlyCharge, "early repayment charge")
            ?: if (type == InterestType.FLAT && originalAmount == null) "A flat rate loan needs its original amount." else null

    /** These terms with every change in [changes] on or before [day] applied, in date order. */
    fun effective(changes: List<LoanTermChange>, day: Int): EffectiveTerms {
        var type = this.type
        var rate = this.rate
        var payment = this.payment
        changes.filter { it.accountId == accountId && it.effectiveDay <= day }
            .sortedWith(compareBy({ it.effectiveDay }, { it.id }))
            .forEach { c ->
                c.type?.let { type = it }
                c.rate?.let { rate = it }
                c.payment?.let { payment = it }
            }
        return EffectiveTerms(type, rate, payment)
    }
}

/** What applies on one day: the terms with changes applied. */
data class EffectiveTerms(val type: InterestType, val rate: Long, val payment: Long)

/**
 * A change to the terms from [effectiveDay] on. Payments before it keep their old split. Each of the four values is
 * optional: null means it stays as it was. [termMonths] is the new number of months left; when no [payment] is given
 * the schedule works out the payment that clears the balance in that time. Fixed-then-variable deals are a rate
 * change booked ahead; interest only turning into repayment is a change of [type] with a payment.
 */
data class LoanTermChange(
    val id: Long = 0,
    val accountId: Long,
    val effectiveDay: Int,
    val rate: Long? = null,
    val payment: Long? = null,
    val termMonths: Int? = null,
    val type: InterestType? = null,
) {
    /**
     * The first thing wrong with this change for [terms], as a message, or null. Repositories throw it as
     * [IllegalArgumentException]. A change can't start before the loan did, and must change something. Interest only is
     * the one type whose payment may be zero, since it pays the interest alone.
     */
    fun problem(terms: LoanTerms, changes: List<LoanTermChange> = emptyList()): String? {
        if (rate == null && payment == null && termMonths == null && type == null) return "Nothing has changed."
        val after = type ?: terms.effective(changes, effectiveDay).type
        val word = terms.interestWord.lowercase()
        return rate?.let { InputRules.rateProblem(it, "$word rate") }
            ?: payment?.let { InputRules.amountProblem(it, zeroAllowed = after == InterestType.INTEREST_ONLY) }
            ?: termMonths?.let { InputRules.monthsProblem(it, "a term in months") }
            ?: terms.startDay?.takeIf { effectiveDay < it }?.let { "The change can't start before the loan did." }
            ?: if (type == InterestType.FLAT && terms.originalAmount == null) "A flat rate loan needs its original amount. Add it under Edit details first." else null
    }

    /** What changed, for the history list: "Rate 7.25%, Payment 600.00". [profit] says "Profit rate" for Islamic loans. */
    fun summary(profit: Boolean = false): String = listOfNotNull(
        rate?.let { (if (profit) "Profit rate " else "Rate ") + LoanRate.display(it) },
        payment?.let { "Payment " + Money.format(it) },
        termMonths?.let { "Term $it months" },
        type?.let { "Interest type: " + it.label },
    ).joinToString(", ")
}

/**
 * The link that makes entries one payment: the principal part is a transfer to the loan, the interest part an
 * expense, and an optional third entry holds escrow and any early repayment charge, which are costs but not
 * interest. All three open the same payment sheet.
 */
data class LoanPayment(
    val id: Long = 0,
    val accountId: Long,
    /** When it was paid, as an epoch day. */
    val day: Int,
    /** What left the bank: principal + interest + escrow + charge. */
    val amount: Long,
    val principalEntryId: Long?,
    val interestEntryId: Long?,
    /** Escrow and early repayment charge, together; null when there were neither. */
    val otherEntryId: Long?,
    val escrow: Long,
    val charge: Long,
    /** The interest part was changed by hand, so the hint says so. */
    val edited: Boolean,
    /** The interest Kassa suggested, to show "Kassa's estimate was" after an edit. */
    val estimate: Long,
    /** It covered the scheduled payment, so it moved the next payment date on by a month. */
    val scheduled: Boolean,
    /** Counts the principal as spending for this payment only: null follows the loan, true is on, false is off. */
    val countAllAsSpending: Boolean? = null,
    /** Where this payment's principal counts; null means the loan's principal category. */
    val principalCategoryId: Long? = null,
) {
    val entryIds: List<Long> get() = listOfNotNull(principalEntryId, interestEntryId, otherEntryId)
}

/** Which entry of a loan payment an entry is. */
enum class LoanPart { PRINCIPAL, INTEREST, OTHER }

/** Which part of this payment [entryId] is, or null when it isn't one of its entries. */
fun LoanPayment.partOf(entryId: Long): LoanPart? = when (entryId) {
    principalEntryId -> LoanPart.PRINCIPAL
    interestEntryId -> LoanPart.INTEREST
    otherEntryId -> LoanPart.OTHER
    else -> null
}

/** The small tag an Activity row carries: "principal", "interest" (or "profit"), "escrow", "charge", or both of the last two. */
fun LoanPayment.tag(part: LoanPart, interestWord: String): String = when (part) {
    LoanPart.PRINCIPAL -> "principal"
    LoanPart.INTEREST -> interestWord.lowercase()
    LoanPart.OTHER -> when {
        escrow > 0 && charge > 0 -> "escrow, charge"
        escrow > 0 -> "escrow"
        else -> "charge"
    }
}

/** The notes a payment's entries get, so they read the same everywhere and Kassa can tell them from the user's own. */
object LoanNotes {
    fun principal(loan: String) = "$loan principal"

    fun interest(loan: String, word: String) = "$loan ${word.lowercase()}"

    fun other(loan: String, escrow: Long, charge: Long) =
        "$loan " + when { escrow > 0 && charge > 0 -> "escrow and early repayment charge"; escrow > 0 -> "escrow"; else -> "early repayment charge" }

    /** The `Repeats.key`s of every note [loan]'s payments can carry: a steady escrow is not a bill to suggest. */
    fun repeatKeys(loan: String): Set<String> =
        listOf(principal(loan), interest(loan, "interest"), interest(loan, "profit"), other(loan, 1, 0), other(loan, 0, 1), other(loan, 1, 1))
            .map { Repeats.key(it) }.toSet()
}

/** How a rate field is shown and typed. */
object LoanRate {
    /** "6.125" for a field (a comma in "6,125" where the phone writes one); no grouping and no trailing zeros. */
    fun toInput(rate: Long): String = NumberText.toField(BigDecimal.valueOf(rate).movePointLeft(InputRules.RATE_DECIMALS))

    /** "6.125%" in the phone's number style, up to four decimals. */
    fun display(rate: Long): String =
        NumberText.decimal(BigDecimal.valueOf(rate).movePointLeft(InputRules.RATE_DECIMALS), InputRules.RATE_DECIMALS, trim = true) + "%"
}

/** Where a loan stands: what is owed, the scheduled payment date, and the date interest was last paid up to. */
data class LoanCursor(val balance: Long, val nextDay: Int, val lastDay: Int)

/** One row of the forecast. */
data class LoanRow(val day: Int, val principal: Long, val interest: Long, val escrow: Long, val balance: Long) {
    val payment: Long get() = principal + interest + escrow
}

/** What is left of a loan, from a forecast. The nulls mean it is not paid off within a hundred years. */
data class LoanOutlook(
    val paymentsLeft: Int?,
    val payoffDay: Int?,
    val interestLeft: Long?,
    /** False when the payment is no more than the interest, so the balance never falls. */
    val coversInterest: Boolean,
)

/** The suggested split of one payment. The four parts and [leftover] add up to the amount. */
data class SplitSuggestion(
    val principal: Long,
    val interest: Long,
    val escrow: Long,
    val charge: Long,
    /** The part above the scheduled payment; it goes to principal. */
    val extra: Long,
    /** More than the loan needs: what no part can take. Zero for any payment that can be recorded. */
    val leftover: Long,
    /** For a daily loan, the days the interest is for; null when it is for a month. */
    val days: Int?,
    /** The interest the terms ask for, before the amount limited it. */
    val interestDue: Long,
)

/** Which of the two the lender kept when the rate changed. */
enum class LenderKept { PAYMENT, TERM }

/** What a rate change does: the change to save, and how the loan looks afterwards. */
data class RateChangeOutcome(
    val change: LoanTermChange,
    /** The payment from the change date on. */
    val payment: Long,
    val outlook: LoanOutlook,
    /** Payments left on the same date had the rate not changed, to compare with. */
    val paymentsLeftBefore: Int?,
)

object LoanEngine {

    /** A rate in 1/10000 of a percent is this many parts of 1 (100% = 1,000,000). */
    const val RATE_UNIT = 1_000_000L

    /** A forecast stops here; a loan is never longer than this. */
    const val MAX_ROWS = 1_200

    private const val MAX_DAYS = 36_500

    // ---------- rounding ----------

    /** `x * m / den` rounded half-up, without overflowing for the amounts Kassa allows (x up to about 1e16). */
    private fun mulDivHalfUp(x: Long, m: Long, den: Long): Long {
        if (x <= 0 || m <= 0) return 0
        val whole = (x / den) * m
        val part = (x % den) * m
        val rest = part % den
        return whole + part / den + if (rest * 2 >= den) 1 else 0
    }

    /** One month of interest: balance x rate / 12. 21,480.00 at 6% is 107.40. */
    fun monthlyInterest(balance: Long, rate: Long): Long = mulDivHalfUp(balance.coerceAtLeast(0) * rate, 1, 12 * RATE_UNIT)

    /** Interest for [days] days: balance x rate x days / 365. */
    fun dailyInterest(balance: Long, rate: Long, days: Int): Long =
        mulDivHalfUp(balance.coerceAtLeast(0) * rate, days.coerceIn(0, MAX_DAYS).toLong(), 365 * RATE_UNIT)

    /**
     * The interest a payment owes under [type]. A flat loan owes the same every time, on [flatBase]; a daily loan
     * owes for [days] days (30 when unknown); the rest owe one month on the balance.
     */
    fun interestDue(type: InterestType, rate: Long, balance: Long, days: Int?, flatBase: Long): Long = when (type) {
        InterestType.REDUCING, InterestType.INTEREST_ONLY -> monthlyInterest(balance, rate)
        InterestType.DAILY -> dailyInterest(balance, rate, days ?: 30)
        InterestType.FLAT -> if (balance <= 0) 0 else monthlyInterest(flatBase, rate)
    }

    // ---------- payments at setup ----------

    private val MC = MathContext.DECIMAL128

    /**
     * The payment that clears [principal] in [months] equal payments at [rate]: P r (1+r)^n / ((1+r)^n - 1) with r the
     * monthly rate, rounded half-up. 30,000.00 at 6% over 60 months is 579.98. The formula runs in BigDecimal and the
     * result is whole cents, so no floating point reaches a stored amount.
     */
    fun emi(principal: Long, rate: Long, months: Int): Long {
        if (principal <= 0 || months <= 0) return 0
        if (rate == 0L) return mulDivHalfUp(principal, 1, months.toLong())
        val r = BigDecimal(rate).divide(BigDecimal(12 * RATE_UNIT), MC)
        val growth = BigDecimal.ONE.add(r).pow(months, MC)
        val pay = BigDecimal(principal).multiply(r).multiply(growth).divide(growth.subtract(BigDecimal.ONE), MC)
        return pay.setScale(0, RoundingMode.HALF_UP).longValueExact()
    }

    /** All the interest a flat loan charges over its term: amount x rate x years. 30,000.00 at 6% for 60 months is 9,000.00. */
    fun flatTotalInterest(amount: Long, rate: Long, months: Int): Long =
        mulDivHalfUp(amount.coerceAtLeast(0) * months.toLong().coerceAtLeast(0), rate, 12 * RATE_UNIT)

    /** A flat loan's payment: amount plus all its interest, over the term. 30,000.00 at 6% for 60 months is 650.00. */
    fun flatPayment(amount: Long, rate: Long, months: Int): Long =
        if (months <= 0) 0 else mulDivHalfUp(amount + flatTotalInterest(amount, rate, months), 1, months.toLong())

    /**
     * The rate on the reducing balance that costs the same as a flat loan: what rate would make [payment] clear
     * [amount] over [months] the usual way. 6% flat for 60 months is about 10.85%. Found by bisection in floating
     * point because it is only a rate to show, never an amount to store.
     */
    fun equivalentReducingRate(amount: Long, payment: Long, months: Int): Long {
        if (amount <= 0 || months <= 0 || payment * months <= amount) return 0
        var lo = 0.0
        var hi = 1.0
        repeat(100) {
            val mid = (lo + hi) / 2
            val pay = amount * mid * Math.pow(1 + mid, months.toDouble()) / (Math.pow(1 + mid, months.toDouble()) - 1)
            if (pay > payment) hi = mid else lo = mid
        }
        return Math.round(lo * 12 * RATE_UNIT)
    }

    /**
     * The amount a flat rate loan started with, from what is left of it. Each payment holds `balance / paymentsLeft` of
     * principal and the rest is the flat interest, which is amount x rate / 12, so the amount follows. Null when the
     * payment is too small to hold both, or the answer is beyond what Kassa allows.
     */
    fun flatBaseFrom(balance: Long, payment: Long, rate: Long, paymentsLeft: Int): Long? {
        if (balance <= 0 || paymentsLeft <= 0) return null
        val principalPart = mulDivHalfUp(balance, 1, paymentsLeft.toLong())
        if (rate == 0L) return if (payment >= principalPart) balance else null
        val interest = payment - principalPart
        if (interest <= 0) return null
        return mulDivHalfUp(interest, 12 * RATE_UNIT, rate).coerceAtLeast(balance).takeIf { it <= InputRules.AMOUNT_MAX }
    }

    /** What Kassa suggests the payment is when a loan starts. Interest only suggests one month's interest. */
    fun suggestedPayment(type: InterestType, amount: Long, rate: Long, months: Int): Long = when (type) {
        InterestType.REDUCING, InterestType.DAILY -> emi(amount, rate, months)
        InterestType.FLAT -> flatPayment(amount, rate, months)
        InterestType.INTEREST_ONLY -> monthlyInterest(amount, rate)
    }

    /** Whether [payment] is more than the interest it has to pay; if not, the balance never falls. Interest only always does. */
    fun coversInterest(type: InterestType, rate: Long, balance: Long, payment: Long, flatBase: Long): Boolean =
        type == InterestType.INTEREST_ONLY || balance <= 0 || payment > interestDue(type, rate, balance, null, flatBase)

    // ---------- one payment ----------

    /** The date interest was last paid up to: the latest payment on or before [before], else the start, else a month before the next due date. */
    fun lastDay(terms: LoanTerms, payments: List<LoanPayment>, before: Int? = null, except: Long = 0): Int =
        payments.filter { it.accountId == terms.accountId && it.id != except && (before == null || it.day <= before) }
            .maxOfOrNull { it.day }
            ?: terms.startDay
            ?: LoanDates.addMonths(terms.nextPaymentDay, -1, terms.dueDay)

    /**
     * How a payment of [amount] on [day] splits, from the terms in effect that day.
     *
     * Escrow comes off first. Anything above the scheduled payment is extra; it goes to principal, less the early
     * repayment charge when the loan has one. The rest pays the interest due, capped at what is left, and what
     * remains is principal, never more than [balance]. A payment that is more than the loan needs shows the surplus
     * as [SplitSuggestion.leftover] instead of inventing a place for it.
     */
    fun suggest(amount: Long, day: Int, balance: Long, lastDay: Int, terms: LoanTerms, changes: List<LoanTermChange>): SplitSuggestion {
        val eff = terms.effective(changes, day)
        val owed = balance.coerceAtLeast(0)
        val days = if (eff.type == InterestType.DAILY) (day - lastDay).coerceIn(0, MAX_DAYS) else null
        val due = interestDue(eff.type, eff.rate, owed, days, terms.flatBase(owed))
        val escrow = minOf(terms.escrow, amount)
        val scheduled = scheduledPayment(eff, due, owed)
        val extra = (amount - escrow - scheduled).coerceAtLeast(0)
        val charge = if (owed > 0 && extra > 0 && terms.earlyCharge > 0) minOf(mulDivHalfUp(extra, terms.earlyCharge, RATE_UNIT), amount - escrow) else 0
        val avail = amount - escrow - charge
        val interest = minOf(due, avail)
        val principal = minOf(avail - interest, owed)
        return SplitSuggestion(principal, interest, escrow, charge, extra, avail - interest - principal, days, due)
    }

    /** The principal and interest of a scheduled payment: the payment, or less when that clears the loan. Interest only pays the interest. */
    private fun scheduledPayment(eff: EffectiveTerms, interestDue: Long, balance: Long): Long =
        if (eff.type == InterestType.INTEREST_ONLY) interestDue else minOf(eff.payment, balance + interestDue)

    /** What to pay for the next scheduled payment on [day]: its principal and interest plus escrow, and no more than clears the loan. */
    fun amountDue(terms: LoanTerms, changes: List<LoanTermChange>, balance: Long, lastDay: Int, day: Int): Long {
        val eff = terms.effective(changes, day)
        val owed = balance.coerceAtLeast(0)
        if (owed <= 0) return 0
        val days = if (eff.type == InterestType.DAILY) (day - lastDay).coerceIn(0, MAX_DAYS) else null
        val due = interestDue(eff.type, eff.rate, owed, days, terms.flatBase(owed))
        return scheduledPayment(eff, due, owed) + terms.escrow
    }

    // ---------- forecast ----------

    /**
     * The next payments from [from], at most [limit] rows, until the loan is paid off. Each row uses the terms in
     * effect on its date. A change that gives a new term and no payment sets the payment that clears the balance in
     * that many months, from the balance on the first row on or after its date.
     *
     * A last payment smaller than the rounding drift (under 1% of the payment) is added to the one before it, as
     * lenders do, so a loan of 60 payments doesn't end with a 61st for 29 cents.
     */
    fun project(terms: LoanTerms, changes: List<LoanTermChange>, from: LoanCursor, limit: Int = MAX_ROWS): List<LoanRow> {
        val sorted = changes.filter { it.accountId == terms.accountId }.sortedWith(compareBy({ it.effectiveDay }, { it.id }))
        var type = terms.type
        var rate = terms.rate
        var payment = terms.payment
        var next = 0
        var balance = from.balance.coerceAtLeast(0)
        var day = from.nextDay
        var previous = from.lastDay
        val rows = ArrayList<LoanRow>()
        while (balance > 0 && rows.size < minOf(limit, MAX_ROWS)) {
            while (next < sorted.size && sorted[next].effectiveDay <= day) {
                val c = sorted[next++]
                c.type?.let { type = it }
                c.rate?.let { rate = it }
                if (c.payment != null) payment = c.payment
                else if (c.termMonths != null && c.effectiveDay > from.lastDay) payment = reducedPayment(type, rate, balance, c.termMonths, terms.flatBase(balance), payment)
            }
            val due = interestDue(type, rate, balance, (day - previous).coerceAtLeast(0), terms.flatBase(balance))
            var principal = if (type == InterestType.INTEREST_ONLY) 0 else (payment - due).coerceIn(0, balance)
            if (type != InterestType.INTEREST_ONLY && balance - principal in 1..(payment / 100)) principal = balance
            balance -= principal
            rows += LoanRow(day, principal, due, terms.escrow, balance)
            // A payment that doesn't cover the interest would repeat forever.
            if (principal == 0L && type != InterestType.INTEREST_ONLY) break
            previous = day
            day = LoanDates.addMonths(day, 1, terms.dueDay)
        }
        return rows
    }

    /** The payment that clears [balance] in [months]; a flat loan keeps its principal share and takes the new interest. */
    private fun reducedPayment(type: InterestType, rate: Long, balance: Long, months: Int, flatBase: Long, current: Long): Long = when (type) {
        InterestType.FLAT -> balance / months.coerceAtLeast(1) + monthlyInterest(flatBase, rate)
        InterestType.INTEREST_ONLY -> current
        else -> emi(balance, rate, months)
    }

    /** Payments left, payoff date and the interest still to pay, from a forecast of everything left. */
    fun outlook(terms: LoanTerms, changes: List<LoanTermChange>, from: LoanCursor): LoanOutlook {
        if (from.balance <= 0) return LoanOutlook(0, null, 0, true)
        val rows = project(terms, changes, from)
        val interestOnlyNow = terms.effective(changes, from.nextDay).type == InterestType.INTEREST_ONLY
        val paidOff = rows.isNotEmpty() && rows.last().balance == 0L
        return LoanOutlook(
            paymentsLeft = if (paidOff) rows.size else null,
            payoffDay = if (paidOff) rows.last().day else null,
            interestLeft = if (paidOff) rows.sumOf { it.interest } else null,
            coversInterest = interestOnlyNow || rows.any { it.principal > 0 },
        )
    }

    /** What is owed on [day] if nothing changes but the schedule: the balance after the last forecast payment before it. */
    fun balanceOn(terms: LoanTerms, changes: List<LoanTermChange>, from: LoanCursor, day: Int): Long =
        (project(terms, changes, from).lastOrNull { it.day < day }?.balance ?: from.balance).coerceAtLeast(0)

    /**
     * What a rate change from [effectiveDay] does, for the change-terms screen. Paying on as before and the payment
     * the lender asks for are both worked out from the balance on that date.
     *
     * [LenderKept.PAYMENT]: the payment stays, so the payoff date moves. [LenderKept.TERM]: the payment becomes the
     * one that clears the balance in the payments that were left, so the payoff date stays. Either way the returned
     * [RateChangeOutcome.change] is ready to save: keeping the payment leaves it null.
     */
    fun rateChange(terms: LoanTerms, changes: List<LoanTermChange>, from: LoanCursor, effectiveDay: Int, newRate: Long, kept: LenderKept): RateChangeOutcome {
        val before = project(terms, changes, from)
        val atChange = before.filter { it.day >= effectiveDay }
        val balanceThen = (before.lastOrNull { it.day < effectiveDay }?.balance ?: from.balance).coerceAtLeast(0)
        val eff = terms.effective(changes, effectiveDay)
        val left = atChange.size.takeIf { atChange.isNotEmpty() && before.last().balance == 0L }
        val payment = when {
            kept == LenderKept.PAYMENT || left == null -> eff.payment
            else -> reducedPayment(eff.type, newRate, balanceThen, left, terms.flatBase(balanceThen), eff.payment)
        }
        val change = LoanTermChange(
            accountId = terms.accountId, effectiveDay = effectiveDay, rate = newRate,
            payment = if (kept == LenderKept.TERM && payment != eff.payment) payment else null,
        )
        return RateChangeOutcome(change, payment, outlook(terms, changes + change, from), left)
    }
}

/**
 * Everything the loan page shows, worked out in one place: what is owed, the progress when the original amount is
 * known, the next payment, the payoff month, interest paid and still to pay, and the forecast rows of the schedule.
 * [rows] and [outlook] come from the terms in effect on each date, so a rate change shows in both.
 */
data class LoanSummary(
    /** Outstanding balance: what is owed, never negative here (a credit is shown as nothing owed). */
    val owed: Long,
    val originalAmount: Long?,
    /** The scheduled payment due next, principal, interest and escrow. */
    val payment: Long,
    val dueDay: Int,
    val nextPaymentDay: Int,
    /** The interest (profit) in the payments recorded so far. */
    val interestPaid: Long,
    val rows: List<LoanRow>,
    val outlook: LoanOutlook,
) {
    /** Share of the original amount repaid, 0 to 100; null when the original amount isn't known, so there is no bar. */
    val percentRepaid: Int?
        get() = originalAmount?.takeIf { it > 0 }?.let { o ->
            val repaid = (o - owed).coerceIn(0, o)
            (repaid * 100 / o).toInt()
        }

    /** The same share as 0.0 to 1.0, for the bar. */
    val fractionRepaid: Float?
        get() = originalAmount?.takeIf { it > 0 }?.let { o -> ((o - owed).coerceIn(0, o).toDouble() / o).toFloat() }

    val payoffDay: Int? get() = outlook.payoffDay

    /** Interest still to pay until the loan is paid off; null when it isn't within a hundred years. */
    val interestLeft: Long? get() = outlook.interestLeft

    companion object {
        /** [owed] is positive; [interestPaid] is the sum of the loan's linked interest entries. */
        fun of(terms: LoanTerms, changes: List<LoanTermChange>, payments: List<LoanPayment>, owed: Long, interestPaid: Long): LoanSummary {
            val balance = owed.coerceAtLeast(0)
            val last = LoanEngine.lastDay(terms, payments)
            val cursor = LoanCursor(balance, terms.nextPaymentDay, last)
            return LoanSummary(
                owed = balance, originalAmount = terms.originalAmount,
                payment = LoanEngine.amountDue(terms, changes, balance, last, terms.nextPaymentDay),
                dueDay = terms.dueDay, nextPaymentDay = terms.nextPaymentDay, interestPaid = interestPaid,
                rows = LoanEngine.project(terms, changes, cursor), outlook = LoanEngine.outlook(terms, changes, cursor),
            )
        }
    }
}
