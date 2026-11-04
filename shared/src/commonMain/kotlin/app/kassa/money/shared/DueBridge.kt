package app.kassa.money.shared

import app.kassa.money.domain.Account
import app.kassa.money.domain.BillRules
import app.kassa.money.domain.CardCycle
import app.kassa.money.domain.Category
import app.kassa.money.domain.DueItem
import app.kassa.money.domain.DueItems
import app.kassa.money.domain.DueNotices
import app.kassa.money.domain.DueRule
import app.kassa.money.domain.DueRules
import app.kassa.money.domain.LoanNotes
import app.kassa.money.domain.Money
import app.kassa.money.domain.PlannedNotice
import app.kassa.money.domain.QueryWindows
import app.kassa.money.domain.Recurring
import app.kassa.money.domain.RecurringRules
import app.kassa.money.domain.Repeat
import app.kassa.money.domain.Repeats
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.dateOfMillis
import app.kassa.money.domain.isAfter
import app.kassa.money.domain.localDateOfEpochDay
import app.kassa.money.domain.plusDays
import app.kassa.money.domain.toEpochDay
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import app.kassa.money.domain.DueKind as DomainDueKind

/*
 * Due notices on iOS (batch I): the Notifications page, Overview's Upcoming rows, the bills and the phone's notification plan.
 * Every rule is :domain's (`DueItems`, `DueNotices`, `RecurringRules`, `Repeats`, `BillRules`); this file only turns them into flat values
 * for Swift, the way the other bridges do: kinds are Strings ("CARD", "LOAN", "BILL", "TRANSFER", "SUGGEST"), 0 means "none", epoch days
 * for calendar days. No sentence is built here: a notice says what it is and Swift words it from its Localizable.strings.
 * (This package already has a `DueKind` of its own for Accounts rows, so :domain's is imported under another name and never reaches Swift.)
 */

/**
 * One thing waiting on the Notifications page. [kind] is CARD, LOAN, BILL, TRANSFER (money moved into investments to explain) or SUGGEST
 * (a repeating charge that looks like a bill). [id] is the account's, the bill's or the transfer's id (0 for SUGGEST). [dueDay] is the
 * due date, the transfer's date (0 for SUGGEST). [key] is what Clear remembers when [remembers] (a card's statement, a loan's due
 * date, a suggested bill); a bill clears by marking [handledMonth] handled and a transfer by being answered, neither is remembered.
 */
data class NoticeData(
    val key: String,
    val kind: String,
    val id: Long,
    val dueDay: Int,
    val overdue: Boolean,
    /** The card's, loan's or bill's name; the repeat's name for SUGGEST; empty for TRANSFER. */
    val name: String,
    /** For TRANSFER: the investment account's name (empty when it is gone). */
    val accountName: String,
    /** The balance payable, the scheduled payment, the bill's estimate (empty without one), the transfer or the usual amount. */
    val amountText: String,
    val amountCents: Long,
    /** BILL only: no fixed amount, so [amountText] is an estimate (empty when there is no history either). */
    val estimated: Boolean,
    /** BILL only: how many past amounts are listed in [lastText] (the ones the estimate comes from). */
    val lastCount: Int,
    val lastText: String,
    /** SUGGEST only: how many times in a row the same amount, the usual day of the month and the category. */
    val sameCount: Int,
    val dayOfMonth: Int,
    val categoryId: Long,
    /** BILL only: yyyyMM of the month approving or clearing it marks handled. */
    val handledMonth: Int,
    val remembers: Boolean,
)

/** The Notifications page: everything waiting, in the order Android lists it (by date, suggestions last). */
data class NoticesData(val items: List<NoticeData>)

/** One Upcoming row on Overview: a card payment (CARD, [accountId] set) or a bill ([billId] set) due in the next days. */
data class UpcomingData(
    val dueDay: Int,
    val name: String,
    val kind: String,
    val estimated: Boolean,
    val amountText: String,
    val accountId: Long,
    val billId: Long,
)

data class BillRowData(
    val id: Long,
    val name: String,
    val day: Int,
    /** Empty when the account is gone. */
    val accountName: String,
    val active: Boolean,
    /** The fixed amount, or the estimate; empty without either. */
    val amountText: String,
    /** True when [amountText] is an estimate (no fixed amount). */
    val approx: Boolean,
)

data class BillsData(val rows: List<BillRowData>)

/** A bill as its sheet opens: [id] 0 is a new bill. [amount] is what goes in the amount field ("" for none). */
data class BillSheetData(
    val found: Boolean,
    val id: Long,
    val name: String,
    val categoryId: Long,
    val accountId: Long,
    val day: Int,
    val amount: String,
    val active: Boolean,
)

/** A bill as Swift hands it over: [amount] as typed ("" for none), 0 for no category or account. */
class BillInput(
    val id: Long,
    val name: String,
    val categoryId: Long,
    val accountId: Long,
    val day: Int,
    val amount: String,
    val active: Boolean,
)

/** The three rules of Settings > Notifications, flat. */
class DueRulesInput(
    val cardOn: Boolean, val cardDays: Int, val cardMinute: Int,
    val loanOn: Boolean, val loanDays: Int, val loanMinute: Int,
    val billOn: Boolean, val billDays: Int, val billMinute: Int,
) {
    internal fun toRules(): DueRules = DueRules(
        DueRule(cardOn, cardDays.coerceIn(0, DueNotices.MAX_DAYS_BEFORE), cardMinute.coerceIn(0, 1_439)),
        DueRule(loanOn, loanDays.coerceIn(0, DueNotices.MAX_DAYS_BEFORE), loanMinute.coerceIn(0, 1_439)),
        DueRule(billOn, billDays.coerceIn(0, DueNotices.MAX_DAYS_BEFORE), billMinute.coerceIn(0, 1_439)),
    )
}

/** What Settings > Notifications needs from the rules. [kind] is CARD, LOAN or BILL. */
object DueBridge {
    fun defaultOn(kind: String): Boolean = DomainDueKind.entries.firstOrNull { it.name == kind }?.let { DueRule.default(it).enabled } ?: false

    fun defaultMinute(): Int = DueRule.DEFAULT_MINUTE

    fun maxDays(): Int = DueNotices.MAX_DAYS_BEFORE

    /** Why [days] can't be used for "days before", or null. */
    fun daysProblem(days: Int): String? = DueNotices.daysProblem(days)

    /** How many days ahead a plan looks and how many notifications it holds (iOS keeps 64 pending; one is the daily reminder). */
    fun planDays(): Int = DueNotices.PLAN_DAYS
}

/** What the store needs to write a bill: its cells and its checks. */
object BillBridge {
    private fun cellsOf(input: BillInput): Map<String, String?> {
        val fixed = BillRules.fixedAmount(Money.parseCents(input.amount))
        return mapOf(
            "name" to app.kassa.money.domain.InputRules.clean(input.name),
            "categoryId" to input.categoryId.toString(),
            "accountId" to input.accountId.toString(),
            "dayOfMonth" to input.day.toString(),
            "fixedAmount" to fixed?.toString(),
            "active" to if (input.active) "1" else "0",
            "handledMonth" to "0",
        )
    }

    /** One cell of the `recurring` row for [input] (null for the id, which the database numbers). A new bill starts with no month handled. */
    fun cell(input: BillInput, column: Int): String? {
        val name = BackupRows.columns("recurring").getOrNull(column) ?: return null
        return cellsOf(input)[name]
    }

    /** True for the columns an edit rewrites: everything but the id and the handled month (an edit never un-handles a month). */
    fun ownsColumn(column: Int): Boolean {
        val name = BackupRows.columns("recurring").getOrNull(column) ?: return false
        return name != "id" && name != "handledMonth"
    }

    /** Every problem of the bill as typed (a field name and a sentence each); empty when it can be saved. */
    fun problems(input: BillInput, currency: String): List<FieldProblemData> = withMoney(currency) {
        val cents = Money.parseCents(input.amount)
        BillRules.problems(input.name, input.day, input.amount, cents, input.categoryId, input.accountId).map { FieldProblemData(it.field, it.message) }
    }

    /** What the amount field holds for a fixed amount of [cents]. */
    fun amountText(cents: Long): String = Money.toInput(cents)

    /** yyyyMM of the month the bill due on [dueDay] belongs to. */
    fun handledMonth(dueDay: Int): Int = BillRules.handledMonth(dueDay)

    /** The entry approving the bill records, as the store writes it (its checks run with it). */
    fun approveEntry(name: String, categoryId: Long, accountId: Long, amount: Long, dueDay: Int, today: Int, zoneId: String): EntryInput {
        val zone = try { TimeZone.of(zoneId) } catch (e: Exception) { TimeZone.UTC }
        val bill = Recurring(name = name, categoryId = categoryId, accountId = accountId, dayOfMonth = 1, fixedAmount = null)
        val d = BillRules.approveDraft(bill, amount, dueDay, today, zone)
        return EntryInput(0L, d.kind.name, d.amount, d.accountId ?: 0L, 0L, d.categoryId ?: 0L, d.epochMillis, d.note)
    }
}

/** The views of due notices over one ledger. */
internal class DueViews(
    private val parts: LedgerParts,
    private val balances: Map<Long, Long>,
    private val accountById: Map<Long, Account>,
    private val categoryById: Map<Long, Category>,
) {
    private var repeatsFor: Pair<Pair<Int, String>, List<Repeat>>? = null

    private fun recent(today: LocalDate, zone: TimeZone): List<Transaction> {
        val from = QueryWindows.recentFrom(today, zone)
        return parts.transactions.filter { it.epochMillis >= from }
    }

    /** What repeats in about two years of entries, minus a loan's own steady escrow and interest (the loan already tracks them). */
    private fun repeats(today: LocalDate, zone: TimeZone): List<Repeat> {
        val key = today.toEpochDay().toInt() to zone.id
        repeatsFor?.let { if (it.first == key) return it.second }
        val from = QueryWindows.historyFrom(today, zone)
        val history = parts.transactions.filter { it.epochMillis >= from }
        val loanNotes = parts.loanTerms.keys.mapNotNull { accountById[it]?.name }.flatMap { LoanNotes.repeatKeys(it) }.toSet()
        val found = Repeats.find(history, today, zone).filterNot { it.key in loanNotes }
        repeatsFor = key to found
        return found
    }

    fun items(today: LocalDate, zone: TimeZone, forPhone: Boolean): List<DueItem> =
        DueItems.build(
            parts.accounts, balances, recent(today, zone), parts.bills, parts.loanTerms, parts.loanChanges, parts.loanPayments,
            today, zone, forPhone,
        )

    fun notices(todayDay: Int, zone: TimeZone, cleared: Set<String>): NoticesData {
        val today = localDateOfEpochDay(todayDay)
        val recent = recent(today, zone)
        val sorted = ArrayList<Pair<Int, NoticeData>>()
        for (item in items(today, zone, false)) {
            if (!DueNotices.isWaiting(item, todayDay)) continue
            val overdue = DueNotices.isOverdue(item, todayDay)
            val amount = item.amount ?: 0L
            val data: NoticeData? = when (item.kind) {
                DomainDueKind.CARD -> accountById[item.id]?.takeIf { it.type.hasStatement && it.isActive && it.statementDay != null }?.let { a ->
                    NoticeData(item.cleared, "CARD", a.id, item.dueDay, overdue, a.name, "", Money.format(amount), amount, false, 0, "", 0, 0, 0L, 0, true)
                }
                DomainDueKind.LOAN -> accountById[item.id]?.let { a ->
                    NoticeData(item.cleared, "LOAN", a.id, item.dueDay, overdue, a.name, "", Money.format(amount), amount, false, 0, "", 0, 0, 0L, 0, true)
                }
                DomainDueKind.BILL -> parts.bills.firstOrNull { it.id == item.id }?.let { r ->
                    val last = RecurringRules.recent(r, recent)
                    NoticeData(
                        item.key, "BILL", r.id, item.dueDay, overdue, r.name, "", item.amount?.let { Money.format(it) } ?: "", amount,
                        r.fixedAmount == null, if (r.fixedAmount == null) last.size else 0,
                        if (r.fixedAmount == null) last.joinToString { Money.format(it, wholeDollars = true) } else "",
                        0, 0, 0L, BillRules.handledMonth(item.dueDay), false,
                    )
                }
            }
            if (data != null && data.key !in cleared) sorted += item.dueDay to data
        }
        for (t in parts.transfers) {
            val day = dateOfMillis(t.epochMillis, zone).toEpochDay().toInt()
            val key = "transfer:${t.id}"
            if (key in cleared) continue
            sorted += day to NoticeData(
                key, "TRANSFER", t.id, day, false, "", accountById[t.accountId]?.name ?: "", Money.format(t.amount), t.amount,
                false, 0, "", 0, 0, 0L, 0, false,
            )
        }
        for (r in BillRules.suggestions(repeats(today, zone), parts.bills, categoryById)) {
            val key = "suggest:${r.key}:${r.categoryId}"
            if (key in cleared) continue
            val same = r.amounts.reversed().takeWhile { it == r.amounts.last() }.size
            sorted += Int.MAX_VALUE to NoticeData(
                key, "SUGGEST", 0L, 0, false, r.name, "", Money.format(r.typical), r.typical, false, 0, "", same, r.dayOfMonth,
                r.categoryId ?: 0L, 0, true,
            )
        }
        return NoticesData(sorted.sortedBy { it.first }.map { it.second })
    }

    /** A card payment or a bill coming up within [days] days (a card also while overdue), earliest first. */
    fun upcoming(todayDay: Int, zone: TimeZone, days: Int): List<UpcomingData> {
        val today = localDateOfEpochDay(todayDay)
        val until = today.plusDays(days.toLong())
        val recent = recent(today, zone)
        val out = ArrayList<Pair<Int, UpcomingData>>()
        for (a in parts.accounts) {
            if (!(a.type.hasStatement && a.isActive && a.statementDay != null)) continue
            val s = CardCycle.snapshot(a, recent, today, zone, balance = balances[a.id] ?: 0L)
            if (s.payable > 0 && !s.due.isAfter(until)) {
                val day = s.due.toEpochDay().toInt()
                out += day to UpcomingData(day, a.name, "CARD", false, Money.format(s.payable), a.id, 0L)
            }
        }
        for (r in parts.bills) {
            val d = RecurringRules.upcoming(r, today, days.toLong()) ?: continue
            val est = RecurringRules.estimate(r, recent, d, zone)
            val day = d.toEpochDay().toInt()
            out += day to UpcomingData(day, r.name, "BILL", r.fixedAmount == null, est?.let { Money.format(it) } ?: "", 0L, r.id)
        }
        return out.sortedBy { it.first }.map { it.second }
    }

    fun bills(todayDay: Int, zone: TimeZone): BillsData {
        val today = localDateOfEpochDay(todayDay)
        val recent = recent(today, zone)
        val rows = parts.bills.sortedBy { it.dayOfMonth }.map { r ->
            val est = RecurringRules.estimate(r, recent, today.plusDays(1), zone)
            BillRowData(r.id, r.name, r.dayOfMonth, accountById[r.accountId]?.name ?: "", r.active, est?.let { Money.format(it) } ?: "", r.fixedAmount == null && est != null)
        }
        return BillsData(rows)
    }

    fun billSheet(id: Long): BillSheetData {
        val r = parts.bills.firstOrNull { it.id == id } ?: return BillSheetData(false, id, "", 0L, 0L, 1, "", true)
        return BillSheetData(
            true, r.id, r.name, r.categoryId.takeIf { categoryById[it] != null } ?: 0L, r.accountId.takeIf { accountById[it] != null } ?: 0L,
            r.dayOfMonth, r.fixedAmount?.let { Money.toInput(it) } ?: "", r.active,
        )
    }

    fun hasNotifiable(): Boolean =
        parts.loanTerms.isNotEmpty() ||
            parts.accounts.any { !it.archived && it.type.hasStatement && it.statementDay != null && it.dueDay != null }

    fun plan(rules: DueRulesInput, nowMillis: Long, zone: TimeZone, posted: Set<String>, cleared: Set<String>, title: String, body: String): List<PlannedNotice> {
        val local = Instant.fromEpochMilliseconds(nowMillis).toLocalDateTime(zone)
        val today = local.date
        val now = DueNotices.Moment(today.toEpochDay().toInt(), local.hour * 60 + local.minute)
        return DueNotices.plan(items(today, zone, true), rules.toRules(), now, nowMillis, zone, posted, cleared, title, body)
    }
}
