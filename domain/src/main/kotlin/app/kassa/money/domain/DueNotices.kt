package app.kassa.money.domain

import java.time.LocalDate
import java.time.ZoneId

/*
 * Payments that come due: which ones there are, which day each wants a notification, and when the next one rings.
 * One set of rules for the Notifications page and the phone's notifications, so they never disagree.
 *
 * Days are epoch days and times are minutes after midnight in the phone's own clock, as in LoanDates. Nothing here
 * touches Android. Nothing posts by itself: a notification only tells, it never pays or adds anything.
 */

/** What is due. Names the first word of a notification's title. */
enum class DueKind(val noun: String) {
    CARD("Card payment"),
    LOAN("Loan payment"),
    BILL("Bill"),
}

/**
 * One payment waiting for its due day. [amount] is the balance payable of a card, the scheduled payment of a loan or
 * the amount of a bill (its fixed amount or an estimate); null for a bill with neither. [clearKey] is the key the
 * Notifications page stores when the user clears it, when that isn't [key].
 */
data class DueItem(
    val kind: DueKind,
    val id: Long,
    val name: String,
    val dueDay: Int,
    val amount: Long?,
    val clearKey: String? = null,
) {
    /** Who and which due date: a notification is posted once per key. */
    val key: String get() = "${kind.name.lowercase()}:$id:$dueDay"

    /** What the user's clearing it stored. */
    val cleared: String get() = clearKey ?: key
}

/** One kind's phone notification settings: on or off, how many days before the due day, and the time of day. */
data class DueRule(val enabled: Boolean, val daysBefore: Int, val minute: Int) {
    companion object {
        const val DEFAULT_MINUTE = 9 * 60

        /** Cards and loans are on from the start; bills and repeating payments are the user's choice. */
        fun default(kind: DueKind) = DueRule(enabled = kind != DueKind.BILL, daysBefore = 0, minute = DEFAULT_MINUTE)
    }
}

/** The three rules together. */
data class DueRules(val card: DueRule, val loan: DueRule, val bill: DueRule) {
    fun of(kind: DueKind): DueRule = when (kind) {
        DueKind.CARD -> card
        DueKind.LOAN -> loan
        DueKind.BILL -> bill
    }

    val anyEnabled: Boolean get() = card.enabled || loan.enabled || bill.enabled
}

/** The title and body of one notification. */
data class DueText(val title: String, val body: String)

object DueNotices {
    /** "Other" in settings goes from the due day itself to two weeks before it. */
    const val MAX_DAYS_BEFORE = 14

    /** A notification that was due within this many minutes still rings when the phone is back; older ones are let go. */
    const val GRACE_MINUTES = 3 * 60

    /**
     * Even with nothing due soon the phone looks again once a day, just after midnight, because a card's balance
     * payable appears by itself when its statement closes.
     */
    const val RECHECK_MINUTE = 5

    /** A moment on the phone's clock: a day and a minute of it. Compared by the wall clock. */
    data class Moment(val day: Int, val minute: Int) : Comparable<Moment> {
        val wallMinutes: Long get() = day.toLong() * 1_440 + minute
        override fun compareTo(other: Moment): Int = wallMinutes.compareTo(other.wallMinutes)
    }

    /** The next alarm: when, and whether it is for a notification or only the daily look. */
    data class Next(val moment: Moment, val forNotification: Boolean)

    // ---------- days ----------

    /**
     * The due date in [month] (1 to 12) of [year] for a due day of the month. A due day past the end of a short month
     * falls on its last day: 31 is 28 February, or 29 in a leap year.
     */
    fun dueDayIn(year: Int, month: Int, dueDay: Int): Int =
        LoanDates.epochDay(year, month, dueDay.coerceAtLeast(1).coerceAtMost(LoanDates.daysInMonth(year, month)))

    /** The day a notification goes out: [daysBefore] days before [dueDay], where 0 is the due day itself. */
    fun notifyDay(dueDay: Int, daysBefore: Int): Int = dueDay - daysBefore.coerceIn(0, MAX_DAYS_BEFORE)

    /** [notifyDay] for a due day of the month in one month, with the short-month clamp. */
    fun notifyDayIn(year: Int, month: Int, dueDay: Int, daysBefore: Int): Int = notifyDay(dueDayIn(year, month, dueDay), daysBefore)

    /** Why [days] can't be used for "days before", or null. */
    fun daysProblem(days: Int?): String? =
        if (days == null || days !in 0..MAX_DAYS_BEFORE) "Enter 0 to $MAX_DAYS_BEFORE days." else null

    // ---------- the Notifications page ----------

    /** On the page from its due day on: due today, or overdue until the user acts or clears it. */
    fun isWaiting(item: DueItem, today: Int): Boolean = item.dueDay <= today

    fun isOverdue(item: DueItem, today: Int): Boolean = item.dueDay < today

    // ---------- the phone's notifications ----------

    /** When [item] wants its notification, or null when its kind is off. */
    fun momentOf(item: DueItem, rules: DueRules): Moment? {
        val rule = rules.of(item.kind)
        return if (rule.enabled) Moment(notifyDay(item.dueDay, rule.daysBefore), rule.minute.coerceIn(0, 1_439)) else null
    }

    /**
     * What to post at [now]: items whose moment has come but is no more than [GRACE_MINUTES] ago, that haven't been
     * posted for that due date ([posted], by [DueItem.key]) and weren't cleared ([cleared]). Earliest due first.
     */
    fun ringing(items: List<DueItem>, rules: DueRules, now: Moment, posted: Set<String>, cleared: Set<String>): List<DueItem> =
        items.filter { i ->
            val m = momentOf(i, rules)
            m != null && i.key !in posted && i.cleared !in cleared && m <= now && now.wallMinutes - m.wallMinutes <= GRACE_MINUTES
        }.sortedWith(compareBy({ it.dueDay }, { it.kind }, { it.name }, { it.id }))

    /**
     * The earliest moment after [now] that an item wants a notification, or the daily look after midnight if that
     * comes first. Items already posted or cleared don't count.
     */
    fun next(items: List<DueItem>, rules: DueRules, now: Moment, posted: Set<String>, cleared: Set<String>): Next {
        val look = if (now.minute < RECHECK_MINUTE) Moment(now.day, RECHECK_MINUTE) else Moment(now.day + 1, RECHECK_MINUTE)
        val soonest = items.filter { it.key !in posted && it.cleared !in cleared }
            .mapNotNull { momentOf(it, rules) }.filter { it > now }.minOrNull()
        return if (soonest != null && soonest < look) Next(soonest, true) else Next(look, false)
    }

    // ---------- text ----------

    /**
     * The one place a notification's words are made. "Card payment due today", "Loan payment due tomorrow",
     * "Bill due in 3 days", with "<name>: <amount>" under it. [showAmount] false leaves the amount out, for a
     * Hide balances setting.
     */
    fun text(item: DueItem, daysUntil: Int, showAmount: Boolean = true): DueText {
        val when_ = when {
            daysUntil < 0 -> "overdue"
            daysUntil == 0 -> "due today"
            daysUntil == 1 -> "due tomorrow"
            else -> "due in $daysUntil days"
        }
        val amount = item.amount
        return DueText(
            title = "${item.kind.noun} $when_",
            body = if (showAmount && amount != null) "${item.name}: ${Money.format(amount)}" else item.name,
        )
    }
}

/** Works out what is due from the data. The same input gives the Notifications page and the notifier the same answer. */
object DueItems {

    /**
     * Every payment waiting on its due date, due soon or not:
     * - a card with a balance payable, due on its statement's due date. [forPhone] leaves out cards with no due day
     *   of their own, since they can't say when to notify;
     * - a loan with terms and something owed, due on its next scheduled payment day, unless a payment on or after that
     *   day is already recorded;
     * - an active bill with no matching entry and not handled for its month.
     * Archived accounts are left out.
     */
    fun build(
        accounts: List<Account>,
        balances: Map<Long, Long>,
        history: List<Transaction>,
        bills: List<Recurring>,
        terms: Map<Long, LoanTerms>,
        changes: List<LoanTermChange>,
        payments: List<LoanPayment>,
        today: LocalDate,
        zone: ZoneId,
        forPhone: Boolean = false,
    ): List<DueItem> {
        val items = ArrayList<DueItem>()
        for (a in accounts) {
            if (a.archived) continue
            when {
                a.type.hasStatement && a.statementDay != null && (!forPhone || a.dueDay != null) -> {
                    val s = CardCycle.snapshot(a, history, today, zone, balance = balances[a.id] ?: 0L)
                    if (s.payable > 0) items += DueItem(DueKind.CARD, a.id, a.name, s.due.toEpochDay().toInt(), s.payable, clearKey = "card:${a.id}:${s.lastClose}")
                }
                a.type == AccountType.LOAN -> loanItem(a, balances[a.id] ?: 0L, terms[a.id], changes, payments)?.let { items += it }
                else -> Unit
            }
        }
        bills.forEach { r ->
            val due = RecurringRules.nextDue(r, history, today, zone) ?: return@forEach
            val amount = RecurringRules.estimate(r, history, due.plusDays(1), zone)
            items += DueItem(DueKind.BILL, r.id, r.name, due.toEpochDay().toInt(), amount)
        }
        return items
    }

    private fun loanItem(a: Account, balance: Long, t: LoanTerms?, changes: List<LoanTermChange>, payments: List<LoanPayment>): DueItem? {
        if (t == null || balance >= 0) return null
        val due = t.nextPaymentDay
        if (payments.any { it.accountId == a.id && it.day >= due }) return null
        val amount = LoanEngine.amountDue(t, changes, -balance, LoanEngine.lastDay(t, payments), due)
        return if (amount > 0) DueItem(DueKind.LOAN, a.id, a.name, due, amount) else null
    }
}
