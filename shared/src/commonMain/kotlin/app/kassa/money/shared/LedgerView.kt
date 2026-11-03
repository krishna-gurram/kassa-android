package app.kassa.money.shared

import app.kassa.money.backup.BackupData
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountArrangement
import app.kassa.money.domain.AccountLine
import app.kassa.money.domain.AccountKindFilter
import app.kassa.money.domain.AccountPicking
import app.kassa.money.domain.AccountSection
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.AccountsOverview
import app.kassa.money.domain.Balances
import app.kassa.money.domain.BalanceCorrection
import app.kassa.money.domain.CardCycle
import app.kassa.money.domain.CardSnapshot
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryIcon
import app.kassa.money.domain.CategoryPicking
import app.kassa.money.domain.CountedSpending
import app.kassa.money.domain.EntryField
import app.kassa.money.domain.EntryRules
import app.kassa.money.domain.CurrencyStyle
import app.kassa.money.domain.Currencies
import app.kassa.money.domain.LoanEngine
import app.kassa.money.domain.LoanEntry
import app.kassa.money.domain.LoanEntryIndex
import app.kassa.money.domain.Money
import app.kassa.money.domain.Names
import app.kassa.money.domain.Period
import app.kassa.money.domain.QueryWindows
import app.kassa.money.domain.Spending
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.domain.YearMonth
import app.kassa.money.domain.dateOfMillis
import app.kassa.money.domain.isAfter
import app.kassa.money.domain.isBefore
import app.kassa.money.domain.localDateOfEpochDay
import app.kassa.money.domain.personBalance
import app.kassa.money.domain.toEpochDay
import kotlinx.datetime.TimeZone
import kotlin.math.abs

/*
 * The read model behind the iOS Accounts and Activity screens (stage B).
 *
 * Why it is built from a BackupData and not from database rows: LedgerStore already exports one (`export()`), it is the
 * shape every rule here was written for (the same tables as Android's entities), and it keeps the Swift side free of any
 * second reading of the schema. The price is that the whole ledger is read once per refresh; a later stage can read
 * less (a month, a few accounts) behind the same [LedgerView] calls.
 *
 * Everything a screen shows is worked out here with the :domain rules, so the figures equal Android's: Swift only lays
 * them out. Money text is always `Money.format` (so Hide balances is honoured in one place), colour is a [Tone] that is
 * Ink while hidden, dates cross as epoch days (calendar day) or epoch milliseconds for Swift to write with the phone's
 * locale, and no sentence is built here: a row says what it is (a kind, a note kind) and Swift words it from its
 * Localizable.strings. The only English that crosses is what :domain already owns (section titles, loan tags).
 *
 * Only plain types cross to Swift: String (empty for "none"), Int, Long, Boolean, enums and lists of the small classes below.
 *
 * Money.style, Money.hideAmounts and the number style are globals of :domain; each call sets the first two for its own
 * duration and puts them back, so call a [LedgerView] from one thread at a time (the app uses one serial queue).
 */

/** The colour of a figure. Always [Ink] while Hide balances is on, so a colour never gives a figure away. */
enum class Tone { Ink, Green, Orange }

/** The small line under an account's name. */
enum class RowNote { Plain, TotalBalance, Outstanding, NothingToPay }

/** What the line under the main figure is about: a payment due, a deposit maturing, or the day an account was closed. */
enum class DueKind { Nothing, Due, Matures, Closed }

/** Under a person's figure: who owes whom. [Hidden] while Hide balances is on. */
enum class PersonSide { Hidden, OwesYou, YouOwe, Settled }

/** One account or person row of the Accounts tab. Empty text means "none". */
data class AccountRowData(
    val id: Long,
    val name: String,
    val last4: String,
    /** The one main figure on the right, already formatted ("" for a closed account). */
    val amountText: String,
    val amountTone: Tone,
    val note: RowNote,
    /** The amount [note] names ("Total balance <x>", "Outstanding <x>"); empty otherwise. */
    val noteAmountText: String,
    val dueKind: DueKind,
    /** Epoch day of the due, maturity or closing date; 0 when [dueKind] is Nothing. */
    val dueDay: Int,
    val dueTone: Tone,
    /** Only for people. */
    val side: PersonSide,
)

/** A foldable group: a kind of account, People, or Closed. [key] is stable (AccountSection name, "PEOPLE", "CLOSED"). */
data class AccountsGroupData(
    val key: String,
    /** The group's English name from :domain; Swift words its own from [key]. */
    val title: String,
    val isDebt: Boolean,
    val totalText: String,
    val totalTone: Tone,
    val rows: List<AccountRowData>,
)

/** The Accounts tab: the Net worth hero, then the groups in the order the person arranged them (else A to Z). */
data class AccountsData(
    val netWorthText: String,
    /** Green or Orange (below zero) for the hero; Ink (grey hero) while hidden. */
    val netWorthTone: Tone,
    val assetsText: String,
    val liabilitiesText: String,
    val receivableText: String,
    val payableText: String,
    /** The kinds of account the person has, then People (always), then Closed (only when something is closed). */
    val groups: List<AccountsGroupData>,
)

/** What an Activity row is, for its icon, wording and layout. A loan principal that counts as spending is an [Expense]. */
enum class EntryKind { Expense, Income, Transfer, CardPayment, Buy, Sell, PersonIn, PersonOut, Correction }

/** One entry of Activity. Empty text means "none". */
data class EntryRowData(
    val id: Long,
    val epochMillis: Long,
    val kind: EntryKind,
    /** The entry's note, as typed; Swift falls back to the category, or its own wording for the kind. */
    val note: String,
    val categoryName: String,
    /** An emoji or a letter for the category's badge, and the category's colour as ARGB ("" and 0 without a category). */
    val badgeText: String,
    val badgeColor: Int,
    val accountName: String,
    val toAccountName: String,
    val personName: String,
    /** "Your share <x>" amount of a split entry, empty when the entry isn't split. */
    val myShareText: String,
    /** "principal", "interest", "escrow", "charge" ... from :domain, empty for an ordinary entry. */
    val tag: String,
    val countsAsSpending: Boolean,
    val amountText: String,
    val amountTone: Tone,
    /** True for amounts that are not spending or income (transfers, buys, person payments, corrections): secondary colour. */
    val amountMuted: Boolean,
    /**
     * Whether the entry page may open this row (`EntryRules.isEditable`): an expense, income or transfer that isn't a loan payment,
     * a split, a person's payment or a card payment. Other rows do nothing when tapped yet.
     */
    val editable: Boolean,
)

/** One day of Activity: newest first, with what was spent that day (empty when nothing was, except while hidden). */
data class DayData(
    val epochDay: Int,
    val spentText: String,
    val entries: List<EntryRowData>,
)

/** One month of Activity (a calendar month): the totals as spending sees them, and the days, newest first. */
data class ActivityData(
    val year: Int,
    val month: Int,
    val isCurrentMonth: Boolean,
    val spendingText: String,
    val incomeText: String,
    val incomeTone: Tone,
    val entryCount: Int,
    val days: List<DayData>,
)

/** One category in the picker. [iconKind] is "emoji", "symbol" (an Android icon-catalog name) or "image"; [color] is ARGB. [parentName] is empty for a top-level category. */
data class CategoryChoiceData(
    val id: Long,
    val name: String,
    val parentName: String,
    val iconKind: String,
    val iconValue: String,
    val color: Int,
    val hasSubcategories: Boolean,
)

/** One account in the picker or on a tile; [type] is an `AccountType` name. No amount, on purpose. */
data class AccountChoiceData(val id: Long, val name: String, val last4: String, val type: String, val closed: Boolean)

/** The accounts of one kind in the picker; [key] is an `AccountSection` name. */
data class AccountChoiceGroupData(val key: String, val accounts: List<AccountChoiceData>)

/** The account picker: the kind chips ([filters] are `AccountKindFilter` names, "ALL" first), the groups, the Recent row, and whether no account is open at all. */
data class AccountPickData(
    val filters: List<String>,
    val groups: List<AccountChoiceGroupData>,
    val recent: List<AccountChoiceData>,
    val noneOpen: Boolean,
)

/** An entry as the page edits it: flat, 0 means "none", [kind] is a `TxKind` name. */
data class EntryEditData(
    val id: Long,
    val kind: String,
    val amount: Long,
    val accountId: Long,
    val toAccountId: Long,
    val categoryId: Long,
    val epochMillis: Long,
    val note: String,
)

/** Whether an account tile on Overview has a card payment waiting: coming up, or already past its due day. */
enum class PinDue { Nothing, Due, Overdue }

/** One of the top categories of the Overview hero. [id] is 0 for entries with no category (Swift words "Uncategorized"). */
data class TopCategoryData(
    val id: Long,
    val name: String,
    /** "emoji", "symbol" or "image" like [CategoryChoiceData]; empty without a category. */
    val iconKind: String,
    val iconValue: String,
    /** The category's colour as ARGB; 0 without a category. */
    val color: Int,
    val amountText: String,
)

/** The Overview hero for one flow, spending or income, over the current period: the total and its three largest categories. */
data class OverviewFlowData(
    val totalText: String,
    /** Green for income above zero, Ink otherwise and always while hidden. */
    val tone: Tone,
    /** Largest first, at most three; empty when nothing was spent (or earned) yet. */
    val top: List<TopCategoryData>,
)

/** One pinned tile on Overview: an open account, or [isPeople] for everyone you owe or who owes you (then [id] is 0, [name] empty). */
data class PinRowData(
    val isPeople: Boolean,
    val id: Long,
    val name: String,
    val last4: String,
    val amountText: String,
    val amountTone: Tone,
    val dueKind: PinDue,
    /** The card's balance payable when [dueKind] is not Nothing. */
    val dueAmountText: String,
    /** Epoch day of the card's due date; 0 when [dueKind] is Nothing. */
    val dueDay: Int,
)

/**
 * The Overview tab: the month (or custom-start period) that holds today, spending and income to date with their top three
 * categories, and the pinned accounts (until the person can pin, the first [LedgerView.MAX_PINS] in Accounts order).
 */
data class OverviewData(
    /** The period's month for its label (the month it starts in). */
    val year: Int,
    val month: Int,
    val spending: OverviewFlowData,
    val income: OverviewFlowData,
    val pins: List<PinRowData>,
)

/** The ledger of one restored backup, worked out for the iOS screens. Build one per refresh; see the file comment. */
class LedgerView(data: BackupData, fallbackCurrency: String?) {

    private val currency: CurrencyStyle =
        Currencies.forCode(data.currency) ?: Currencies.forCode(fallbackCurrency) ?: Currencies.fallback

    private val parts = LedgerParts.of(data)
    private val accounts: List<Account> = parts.accounts
    private val transactions: List<Transaction> = parts.transactions
    private val categoryById: Map<Long, Category> = parts.categories.associateBy { it.id }
    private val accountById: Map<Long, Account> = accounts.associateBy { it.id }
    private val balances: Map<Long, Long> = Balances.of(accounts, transactions)
    private val loanIndex = LoanEntryIndex(parts.loanPayments, parts.loanTerms)
    private val splitEntryIds: Set<Long> = parts.loanEntries.mapNotNull { it.transactionId }.toSet()

    /**
     * The arrangement the person made (Accounts > Order > Your order). A backup carries it but not the sort, so a backup
     * that has one shows it, as Android does after a restore; otherwise the groups and rows are A to Z.
     */
    private val arrangement: AccountArrangement? = AccountArrangement.decode(data.accountArrangement)

    /** The day each period starts on (1 to 28), as Android's `Ledger.period` reads it. */
    private val monthStartDay: Int = data.monthStartDay.coerceIn(1, 28)

    /** The currency code this ledger shows ("USD"). */
    fun currencyCode(): String = currency.code

    /** Whether there is nothing at all: no accounts, no entries, no people. */
    fun isEmpty(): Boolean = accounts.isEmpty() && transactions.isEmpty() && parts.people.isEmpty()

    // ---------- Accounts ----------

    /**
     * The Accounts tab as of [todayEpochDay] (epoch day of the phone's today) in the zone [timeZoneId]; [hidden] is Hide
     * balances. Mirrors `AccountsScreen`: groups from [AccountsOverview], an investment account worth its cash plus its
     * holdings, cards from [CardCycle.snapshot], loans from [LoanEngine.amountDue], people from [personBalance].
     */
    fun accounts(hidden: Boolean, todayEpochDay: Int, timeZoneId: String): AccountsData = shown(hidden) {
        val today = todayEpochDay
        val zone = zoneOf(timeZoneId)
        val valueOf: (Account) -> Long = { a -> (balances[a.id] ?: 0L) + holdingsValue(a) }
        val cards = cardSnapshots(today, zone)

        val owed = openPeople().map { p -> personBalance(p.id, parts.loanEntries, transactions) }
        val receivable = owed.filter { it > 0 }.sum()
        val payable = -owed.filter { it < 0 }.sum()
        val overview = AccountsOverview.of(accounts, valueOf, receivable, payable, Names.order { it.account.name }, arrangement)

        val groups = ArrayList<AccountsGroupData>()
        for (g in overview.groups) {
            val rows = g.lines.map { line -> accountRow(line, cards, today) }
            val debt = g.section.isDebt
            val totalText = if (debt) cardMoney(g.total) else Money.format(g.total)
            val totalTone = if (debt) cardTint(g.total) else figure(g.total < 0)
            groups.add(AccountsGroupData(g.section.name, g.section.title, debt, totalText, totalTone, rows))
        }
        groups.add(peopleGroup(receivable - payable))
        if (overview.closed.isNotEmpty()) groups.add(closedGroup(overview.closed))

        val net = overview.netWorth
        AccountsData(
            netWorthText = Money.format(net),
            netWorthTone = if (Money.isHidden) Tone.Ink else if (net < 0) Tone.Orange else Tone.Green,
            assetsText = Money.format(overview.assets),
            liabilitiesText = Money.format(overview.liabilities),
            receivableText = Money.format(receivable),
            payableText = Money.format(payable),
            groups = groups,
        )
    }

    private fun holdingsValue(a: Account): Long =
        if (a.type == AccountType.INVESTMENT) parts.holdings.filter { it.accountId == a.id }.sumOf { it.value } else 0L

    private fun cardSnapshots(todayEpochDay: Int, zone: TimeZone): Map<Long, CardSnapshot> {
        val today = localDateOfEpochDay(todayEpochDay)
        val result = HashMap<Long, CardSnapshot>()
        for (a in accounts) {
            if (a.type.hasStatement && a.isActive && a.statementDay != null) {
                result[a.id] = CardCycle.snapshot(a, transactions, today, zone, balance = balances[a.id] ?: 0L)
            }
        }
        return result
    }

    private fun openPeople() = parts.people.filter { !it.archived }

    /** One account row, as `AccountRow` in the Android Accounts screen builds it. */
    private fun accountRow(line: AccountLine, cards: Map<Long, CardSnapshot>, today: Int): AccountRowData {
        val a = line.account
        val debt = a.type.isDebt
        var amount = if (debt) cardMoney(line.shown) else Money.format(line.shown)
        var amountTone = if (debt) cardTint(line.shown) else balanceTone(a.type, line.shown)
        var note = RowNote.Plain
        var noteAmount = ""
        var dueKind = DueKind.Nothing
        var dueDay = 0
        var dueTone = Tone.Ink
        when (a.type) {
            AccountType.CREDIT_CARD -> {
                val s = cards[a.id]
                if (s != null && s.payable > 0) {
                    note = RowNote.TotalBalance
                    noteAmount = amount
                    amount = Money.format(s.payable)
                    amountTone = figure(true)
                    dueKind = DueKind.Due
                    dueDay = s.due.toEpochDay().toInt()
                    dueTone = if (AccountsOverview.dueNeedsAttention(dueDay, today)) figure(true) else Tone.Ink
                } else if (s != null && !Money.isHidden) {
                    // While hidden "nothing to pay" would say the card is settled.
                    note = RowNote.NothingToPay
                }
            }
            AccountType.LOAN -> {
                val t = parts.loanTerms[a.id]
                if (t != null && line.shown > 0) {
                    val changes = parts.loanChanges.filter { it.accountId == a.id }
                    val last = LoanEngine.lastDay(t, parts.loanPayments.filter { it.accountId == a.id })
                    val payable = LoanEngine.amountDue(t, changes, line.shown, last, t.nextPaymentDay)
                    note = RowNote.Outstanding
                    noteAmount = amount
                    amount = Money.format(payable)
                    amountTone = figure(true)
                    dueKind = DueKind.Due
                    dueDay = t.nextPaymentDay
                    dueTone = if (AccountsOverview.dueNeedsAttention(dueDay, today)) figure(true) else Tone.Ink
                }
            }
            AccountType.FIXED_DEPOSIT -> {
                val matures = a.maturityDay
                if (matures != null) {
                    dueKind = DueKind.Matures
                    dueDay = matures
                }
            }
            else -> Unit
        }
        return AccountRowData(a.id, a.name, a.last4 ?: "", amount, amountTone, note, noteAmount, dueKind, dueDay, dueTone, PersonSide.Hidden)
    }

    private fun peopleGroup(total: Long): AccountsGroupData {
        val rows = openPeople().sortedWith(Names.order { it.name }).map { p ->
            val balance = personBalance(p.id, parts.loanEntries, transactions)
            val side = when {
                Money.isHidden -> PersonSide.Hidden
                balance > 0 -> PersonSide.OwesYou
                balance < 0 -> PersonSide.YouOwe
                else -> PersonSide.Settled
            }
            AccountRowData(
                p.id, p.name, "", Money.format(abs(balance)), favour(balance), RowNote.Plain, "", DueKind.Nothing, 0, Tone.Ink, side,
            )
        }
        return AccountsGroupData(PEOPLE, "People", false, Money.format(total), favour(total), rows)
    }

    private fun closedGroup(closed: List<Account>): AccountsGroupData {
        val rows = closed.map { a ->
            AccountRowData(
                a.id, a.name, a.last4 ?: "", "", Tone.Ink, RowNote.Plain, "", DueKind.Closed, a.closedDay ?: 0, Tone.Ink, PersonSide.Hidden,
            )
        }
        return AccountsGroupData(CLOSED, "Closed", false, "", Tone.Ink, rows)
    }

    // ---------- Activity ----------

    /**
     * One calendar month of Activity ([month] 1 to 12), entries as spending sees them (`CountedSpending`: a loan principal
     * that counts is an expense in its category), the totals from that same list, days newest first like Android.
     */
    fun activity(year: Int, month: Int, hidden: Boolean, todayEpochDay: Int, timeZoneId: String): ActivityData = shown(hidden) {
        val zone = zoneOf(timeZoneId)
        val shownMonth = YearMonth(year, month.coerceIn(1, 12))
        val range = QueryWindows.monthRange(shownMonth, zone)
        val inMonth = transactions.filter { it.epochMillis in range }
        val list = CountedSpending.apply(inMonth, parts.loanPayments, parts.loanTerms, parts.categories)
        val spent = list.filter { it.kind == TxKind.EXPENSE }.sumOf { it.categoryAmount }
        val income = list.filter { it.kind == TxKind.INCOME }.sumOf { it.amount }

        val days = ArrayList<DayData>()
        val grouped = list.groupBy { dateOfMillis(it.epochMillis, zone) }
        for ((day, entries) in grouped) {
            val daySpent = entries.filter { it.kind == TxKind.EXPENSE }.sumOf { it.categoryAmount }
            // A zero would give away which days had spending, so while hidden every day shows the mask.
            val spentText = if (daySpent > 0 || Money.isHidden) Money.format(daySpent) else ""
            days.add(DayData(day.toEpochDay().toInt(), spentText, entries.map { entryRow(it) }))
        }
        val current = YearMonth.from(localDateOfEpochDay(todayEpochDay))
        ActivityData(
            year = shownMonth.year,
            month = shownMonth.month,
            isCurrentMonth = shownMonth == current,
            spendingText = Money.format(spent),
            incomeText = Money.format(income),
            incomeTone = figurePositive(),
            entryCount = list.size,
            days = days,
        )
    }

    /** One entry row, as `TransactionRow` in the Android app builds it. The wording is Swift's. */
    private fun entryRow(t: Transaction): EntryRowData {
        val account = t.accountId?.let { accountById[it] }
        val to = t.toAccountId?.let { accountById[it] }
        val intoCard = account?.type?.hasStatement == true
        val person = t.personId?.let { id -> parts.people.firstOrNull { it.id == id }?.name } ?: ""
        val tag = loanIndex.tagFor(t.id) ?: ""
        val counted = loanIndex.countsAsSpending(t.id)
        val category = t.categoryId?.let { categoryById[it] }
        val badgeSource = category?.parentId?.let { categoryById[it] } ?: category

        var kind = EntryKind.Expense
        var amount = Money.format(t.amount)
        var tone = Tone.Ink
        var muted = false
        when (t.kind) {
            TxKind.ADJUST_IN, TxKind.ADJUST_OUT -> {
                kind = EntryKind.Correction
                amount = BalanceCorrection.describe(BalanceCorrection.shown(t, account?.type?.isDebt == true))
                muted = true
            }
            TxKind.TRANSFER -> {
                val cardPayment = to?.type?.hasStatement == true
                kind = if (cardPayment) EntryKind.CardPayment else EntryKind.Transfer
                amount = Money.format(t.amount, showPlus = cardPayment)
                // A payment to a card is a credit on it: green, like a refund.
                if (cardPayment) tone = Tone.Green else muted = true
            }
            TxKind.BUY, TxKind.SELL -> {
                kind = if (t.kind == TxKind.BUY) EntryKind.Buy else EntryKind.Sell
                muted = true
            }
            TxKind.PERSON_IN, TxKind.PERSON_OUT -> {
                kind = if (t.kind == TxKind.PERSON_IN) EntryKind.PersonIn else EntryKind.PersonOut
                val credit = t.kind == TxKind.PERSON_IN && intoCard
                amount = Money.format(t.amount, showPlus = credit)
                if (credit) tone = Tone.Green else muted = true
            }
            TxKind.INCOME -> {
                kind = EntryKind.Income
                amount = Money.format(t.amount, showPlus = true)
                // Income is green by what it is, not by its size, so it stays green while hidden.
                tone = Tone.Green
            }
            TxKind.EXPENSE -> Unit
        }
        val share = t.myShare?.takeIf { it != t.amount }?.let { Money.format(it) } ?: ""
        return EntryRowData(
            id = t.id,
            epochMillis = t.epochMillis,
            kind = kind,
            note = t.note,
            categoryName = category?.name ?: "",
            badgeText = badgeSource?.let { badgeOf(it) } ?: "",
            badgeColor = badgeSource?.color ?: 0,
            accountName = account?.name ?: "",
            toAccountName = to?.name ?: "",
            personName = person,
            myShareText = share,
            tag = tag,
            countsAsSpending = counted,
            amountText = amount,
            amountTone = tone,
            amountMuted = muted,
            editable = isEditable(t, to),
        )
    }

    /** `EntryRules.isEditable` for [t] (its destination [to]), by what the ledger knows about its loan payment and split. */
    private fun isEditable(t: Transaction, to: Account?): Boolean =
        EntryRules.isEditable(
            t.kind, to?.type, loanIndex.isLoanEntry(t.id), t.personId != null, t.myShare != null || t.id in splitEntryIds,
        )

    private fun badgeOf(c: Category): String = when (val icon = c.icon) {
        is CategoryIcon.Emoji -> icon.value
        is CategoryIcon.Symbol -> c.name.trim().take(1).uppercase()
        is CategoryIcon.Image -> c.name.trim().take(1).uppercase()
    }

    // ---------- Overview ----------

    /**
     * The Overview as of [todayEpochDay] in the zone [timeZoneId]; [hidden] is Hide balances. Mirrors Android's `TodayScreen`: the
     * period is `Period.containing(today, monthStartDay)`, spending and income are the entries as spending sees them
     * (`CountedSpending`) from the period's start up to today, rolled up to top-level categories (`Spending.topOf`) with each
     * entry's own share (`categoryAmount`), largest first. Pins are the open accounts in the person's Accounts order (A to Z
     * until they arrange), then People when anyone is listed, at most [MAX_PINS].
     */
    fun overview(hidden: Boolean, todayEpochDay: Int, timeZoneId: String): OverviewData = shown(hidden) {
        val zone = zoneOf(timeZoneId)
        val today = localDateOfEpochDay(todayEpochDay)
        val period = Period.containing(today, monthStartDay)
        val inPeriod = transactions.filter { t ->
            val day = dateOfMillis(t.epochMillis, zone)
            !day.isBefore(period.start) && !day.isAfter(today)
        }
        val counted = CountedSpending.apply(inPeriod, parts.loanPayments, parts.loanTerms, parts.categories)
        OverviewData(
            year = period.yearMonth.year,
            month = period.yearMonth.month,
            spending = overviewFlow(counted, TxKind.EXPENSE, false),
            income = overviewFlow(counted, TxKind.INCOME, true),
            pins = overviewPins(todayEpochDay, zone),
        )
    }

    private fun overviewFlow(list: List<Transaction>, kind: TxKind, positive: Boolean): OverviewFlowData {
        val grouped = list.filter { it.kind == kind }.groupBy { Spending.topOf(it.categoryId, categoryById) }
        val rows = grouped.entries.map { e -> Pair(e.key, e.value.sumOf { it.categoryAmount }) }.sortedByDescending { it.second }
        val total = rows.sumOf { it.second }
        val top = rows.take(3).map { r -> topCategoryOf(r.first, r.second) }
        val tone = if (positive && total > 0) figurePositive() else Tone.Ink
        return OverviewFlowData(Money.format(total), tone, top)
    }

    private fun topCategoryOf(c: Category?, amount: Long): TopCategoryData {
        if (c == null) return TopCategoryData(0L, "", "", "", 0, Money.format(amount))
        val icon = iconParts(c.icon)
        return TopCategoryData(c.id, c.name, icon.first, icon.second, c.color, Money.format(amount))
    }

    private fun overviewPins(todayEpochDay: Int, zone: TimeZone): List<PinRowData> {
        val open = accounts.filter { it.isActive }
        val arranged = arrangement
        val ordered: List<Account> = if (arranged == null) {
            open.sortedWith(Names.order { a: Account -> a.name })
        } else {
            arranged.flatten(
                open,
                { a: Account -> AccountSection.of(a.type).name },
                { a: Account -> AccountSection.of(a.type).title },
                { a: Account -> a.id },
                { a: Account -> a.name },
            )
        }
        val cards = cardSnapshots(todayEpochDay, zone)
        val rows = ArrayList<PinRowData>()
        for (a in ordered) {
            val balance = (balances[a.id] ?: 0L) + holdingsValue(a)
            val debt = a.type.isDebt
            val text = if (debt) cardMoney(-balance) else Money.format(balance)
            val tone = if (debt) cardTint(-balance) else balanceTone(a.type, balance)
            var due = PinDue.Nothing
            var dueText = ""
            var dueDay = 0
            val snapshot = cards[a.id]
            if (snapshot != null && snapshot.payable > 0) {
                dueText = Money.format(snapshot.payable)
                dueDay = snapshot.due.toEpochDay().toInt()
                due = if (dueDay < todayEpochDay) PinDue.Overdue else PinDue.Due
            }
            rows.add(PinRowData(false, a.id, a.name, a.last4 ?: "", text, tone, due, dueText, dueDay))
        }
        val people = openPeople()
        if (people.isNotEmpty()) {
            val owed = people.map { p -> personBalance(p.id, parts.loanEntries, transactions) }
            val theyOwe = owed.filter { it > 0 }.sum()
            val youOwe = -owed.filter { it < 0 }.sum()
            val net = if (theyOwe > 0L || youOwe == 0L) theyOwe else -youOwe
            rows.add(PinRowData(true, 0L, "", "", Money.format(abs(net)), favour(net), PinDue.Nothing, "", 0))
        }
        return rows.take(MAX_PINS)
    }

    // ---------- Pickers and editing (the write path) ----------

    /** Every account's name, archived and closed ones too: what a new account's name may not repeat. */
    fun accountNames(): List<String> = accounts.map { it.name }

    /** Every person's name, archived ones too: what a new person's name may not repeat. */
    fun personNames(): List<String> = parts.people.map { it.name }

    /** Where the largest sort order of the accounts is, so a new account goes last. */
    fun nextAccountSortOrder(): Int = (accounts.maxOfOrNull { it.sortOrder } ?: -1) + 1

    /** The accounts a card can be paid from (open checking, savings and cash), A to Z. */
    fun payFromChoices(): List<AccountChoiceData> = AccountPicking.payFromOptions(accounts).map(::accountChoiceOf)

    private fun categoryChoiceOf(c: Category, withParent: Boolean): CategoryChoiceData {
        val parent = c.parentId?.let { categoryById[it] }
        val shown = parent ?: c
        val (kind, value) = iconParts(shown.icon)
        val hasChildren = c.parentId == null && CategoryPicking.subcategories(parts.categories, c).isNotEmpty()
        return CategoryChoiceData(
            id = c.id,
            name = c.name,
            parentName = if (withParent) parent?.name ?: "" else "",
            iconKind = kind,
            iconValue = value,
            color = shown.color,
            hasSubcategories = hasChildren,
        )
    }

    /**
     * What the category picker shows for [kind], a `CategoryKind` name ("EXPENSE" or "INCOME"; a transfer has no category): the top-level categories A to Z, or, once something is typed in [query], the categories and
     * subcategories that match, best first. Hidden (archived) categories are never offered.
     */
    fun categoryChoices(kind: String, query: String): List<CategoryChoiceData> {
        val k = categoryKindOf(kind) ?: return emptyList()
        val q = query.trim()
        if (q.isEmpty()) return CategoryPicking.top(parts.categories, k).map { categoryChoiceOf(it, false) }
        return CategoryPicking.search(parts.categories, k, q).map { categoryChoiceOf(it, true) }
    }

    /** The subcategories of the top-level category [topId], A to Z. */
    fun subcategoryChoices(topId: Long): List<CategoryChoiceData> {
        val top = categoryById[topId] ?: return emptyList()
        return CategoryPicking.subcategories(parts.categories, top).map { categoryChoiceOf(it, true) }
    }

    /** One category for the page's tile (its parent's name set when it is a subcategory), or null. */
    fun categoryChoice(id: Long): CategoryChoiceData? = categoryById[id]?.let { categoryChoiceOf(it, true) }

    private fun accountChoiceOf(a: Account) = AccountChoiceData(a.id, a.name, a.last4 ?: "", a.type.name, a.isClosed)

    /** One account for the page's tile, or null. A closed account is still described (an old entry may sit on it). */
    fun accountChoice(id: Long): AccountChoiceData? = accountById[id]?.let(::accountChoiceOf)

    /**
     * What the account picker shows (`AccountPicking`): open accounts the page doesn't exclude (`EntryRules.excludedAccounts`),
     * grouped by kind and A to Z, filtered by the kind chip [filter] (an `AccountKindFilter` name) and the typed [query]; the
     * Recent row (accounts used on the newest entries, newest first, only with nothing typed and the All chip) holds at most [recentLimit].
     * [field] is "ACCOUNT" or "TO", [accountId] the source already picked (0 for none). No amounts, nothing preselected.
     */
    fun accountPick(entryKind: String, field: String, accountId: Long, filter: String, query: String, recentLimit: Int): AccountPickData {
        val k = TxKind.entries.firstOrNull { it.name == entryKind } ?: TxKind.EXPENSE
        val f = if (field == EntryField.TO.name) EntryField.TO else EntryField.ACCOUNT
        val excluded = EntryRules.excludedAccounts(k, f, if (accountId > 0) accountId else null, accounts)
        val available = AccountPicking.available(accounts, excluded)
        val chosen = AccountKindFilter.entries.firstOrNull { it.name == filter } ?: AccountKindFilter.ALL
        val q = query.trim()
        val groups = AccountPicking.groups(available, chosen, q).map { g ->
            AccountChoiceGroupData(g.section.name, g.accounts.map(::accountChoiceOf))
        }
        val recent = if (q.isEmpty() && chosen == AccountKindFilter.ALL) {
            val used = transactions.sortedByDescending { it.epochMillis }.mapNotNull { it.accountId }
            AccountPicking.recent(used, available, recentLimit).map(::accountChoiceOf)
        } else emptyList()
        return AccountPickData(AccountPicking.filtersFor(available).map { it.name }, groups, recent, available.isEmpty())
    }

    /**
     * The entry [id] as the page edits it, or null when there is none or the page may not edit it (`EntryRowData.editable`). Read from
     * the stored entry, not the counted one, so a transfer is a transfer.
     */
    fun entryEdit(id: Long): EntryEditData? {
        val t = transactions.firstOrNull { it.id == id } ?: return null
        if (!isEditable(t, t.toAccountId?.let { accountById[it] })) return null
        return EntryEditData(
            id = t.id, kind = t.kind.name, amount = t.amount, accountId = t.accountId ?: 0, toAccountId = t.toAccountId ?: 0,
            categoryId = t.categoryId ?: 0, epochMillis = t.epochMillis, note = t.note,
        )
    }

    /** The entry's amount in this ledger's currency, as Money.format writes it (the mask while [hidden]): the "Delete this entry?" question names it, as Android's does. */
    fun entryAmountText(id: Long, hidden: Boolean): String {
        val t = transactions.firstOrNull { it.id == id } ?: return ""
        return shown(hidden) { Money.format(t.amount) }
    }

    /** The ledger currency's bare symbol ("$"), for the amount at the top of the entry page; the only place a symbol stands without a figure. */
    fun currencySymbol(): String = currency.symbol.trim()

    /**
     * The picker for "Pay from" on a card: only open checking, savings and cash accounts (`AccountPicking.payFromOptions`), grouped
     * and filtered like [accountPick] but with no Recent row and no kind chips.
     */
    fun payFromPick(query: String): AccountPickData {
        val options = AccountPicking.payFromOptions(accounts)
        val groups = AccountPicking.groups(options, AccountKindFilter.ALL, query.trim()).map { g ->
            AccountChoiceGroupData(g.section.name, g.accounts.map(::accountChoiceOf))
        }
        return AccountPickData(listOf(AccountKindFilter.ALL.name), groups, emptyList(), options.isEmpty())
    }

    // ---------- Money state and colour ----------

    /** Runs [block] with Money showing this ledger's currency and [hidden] as Hide balances, then puts both back. */
    private fun <T> shown(hidden: Boolean, block: () -> T): T {
        val style = Money.style
        val hide = Money.hideAmounts
        Money.style = currency
        Money.hideAmounts = { hidden }
        try {
            return block()
        } finally {
            Money.style = style
            Money.hideAmounts = hide
        }
    }

    private fun zoneOf(id: String): TimeZone = try {
        TimeZone.of(id)
    } catch (e: Exception) {
        TimeZone.UTC
    }

    /** `cardMoney`: what a card or debt shows, no minus; a credit is a plus. */
    private fun cardMoney(v: Long): String = if (v < 0) Money.format(-v, showPlus = true) else Money.format(v)

    /** `cardTint`: green for a credit, else Ink; always Ink while hidden. */
    private fun cardTint(v: Long): Tone = if (Money.isHidden) Tone.Ink else if (v < 0) Tone.Green else Tone.Ink

    /** `favourColor`: green when in your favour, orange against, Ink for zero or while hidden. */
    private fun favour(v: Long): Tone = if (Money.isHidden) Tone.Ink else if (v > 0) Tone.Green else if (v < 0) Tone.Orange else Tone.Ink

    /** `balanceColor`: orange only when money you own goes below zero. */
    private fun balanceTone(type: AccountType, balance: Long): Tone =
        if (!Money.isHidden && !type.isDebt && balance < 0) Tone.Orange else Tone.Ink

    /** `figureColor` for orange: Orange when [against], Ink otherwise and always while hidden. */
    private fun figure(against: Boolean): Tone = if (Money.isHidden || !against) Tone.Ink else Tone.Orange

    /** `figureColor(positive)`: Green, Ink while hidden. */
    private fun figurePositive(): Tone = if (Money.isHidden) Tone.Ink else Tone.Green

    companion object {
        const val PEOPLE = "PEOPLE"
        /** At most this many tiles on Overview, as Android's `MAX_PINS`. */
        const val MAX_PINS = 8
        const val CLOSED = "CLOSED"
    }
}
