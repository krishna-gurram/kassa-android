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
import app.kassa.money.domain.BudgetRules
import app.kassa.money.domain.EntryFilter
import app.kassa.money.domain.WeekPace
import app.kassa.money.domain.WeekRules
import app.kassa.money.domain.BalanceCorrection
import app.kassa.money.domain.CardCycle
import app.kassa.money.domain.CardPageRules
import app.kassa.money.domain.CardPayment
import app.kassa.money.domain.CardSnapshot
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryColors
import app.kassa.money.domain.CategoryIcon
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.CategoryRules
import app.kassa.money.domain.CategoryPicking
import app.kassa.money.domain.CountedSpending
import app.kassa.money.domain.EditRules
import app.kassa.money.domain.EntryField
import app.kassa.money.domain.EntryRules
import app.kassa.money.domain.CurrencyStyle
import app.kassa.money.domain.Currencies
import app.kassa.money.domain.LoanEngine
import app.kassa.money.domain.LoanEntry
import app.kassa.money.domain.LoanEntryIndex
import app.kassa.money.domain.LoanForm
import app.kassa.money.domain.LoanRate
import app.kassa.money.domain.LoanSummary
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.InvestRules
import app.kassa.money.domain.Money
import app.kassa.money.domain.Names
import app.kassa.money.domain.Patterns
import app.kassa.money.domain.Person
import app.kassa.money.domain.PersonHistory
import app.kassa.money.domain.PersonPayment
import app.kassa.money.domain.Period
import app.kassa.money.domain.QueryWindows
import app.kassa.money.domain.Spending
import app.kassa.money.domain.SplitRules
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.domain.YearMonth
import app.kassa.money.domain.closeProblem as ruleCloseProblem
import app.kassa.money.domain.dateOfMillis
import app.kassa.money.domain.effectOn
import app.kassa.money.domain.isAfter
import app.kassa.money.domain.isBefore
import app.kassa.money.domain.localDateOfEpochDay
import app.kassa.money.domain.personBalance
import app.kassa.money.domain.subcategory
import app.kassa.money.domain.toEpochDay
import kotlinx.datetime.LocalDate
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
     * Whether the entry page may open this row (`EntryRules.isEditable`): an expense, income or transfer (a card payment is one) that
     * isn't a loan payment, a split or a person's payment. Other rows do nothing when tapped yet.
     */
    val editable: Boolean,
    /** A payment to or from a person: it opens the person payment sheet (edit or delete), not the entry page. */
    val personPayment: Boolean,
    /** The `loan_payments` row this entry belongs to, whatever its kind (0 for none): it opens the loan payment sheet. */
    val loanPaymentId: Long,
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
    /** Epoch day this week starts on: the phone's first day of the week on or before today. */
    val weekStartDay: Int,
    /** The same two flows from [weekStartDay] through today (Overview's "This week"). */
    val weekSpending: OverviewFlowData,
    val weekIncome: OverviewFlowData,
    val pins: List<PinRowData>,
)

/** One category line of Insights' By category: a top-level category's spending in the month, with its share. */
data class InsightLineData(
    /** 0 for spending with no category (Swift words "Uncategorized"). */
    val id: Long,
    val name: String,
    /** "emoji", "symbol" or "image" like [CategoryChoiceData]; empty without a category. */
    val iconKind: String,
    val iconValue: String,
    /** The category's colour as ARGB; 0 without a category. */
    val color: Int,
    val amountText: String,
    /** Whole percent of the month's spending, or -1 while Hide balances is on (a share would give the size away). */
    val percent: Int,
    /** The bar: the part of the budget used when there is one, else this line against the largest one; 0 to 1000; always 0 while hidden. */
    val barPermille: Int,
    /** The budget this month ("of <refText>"), empty without one. */
    val refText: String,
    /** Past the budget: the bar and amount turn orange. Never while hidden. */
    val over: Boolean,
)

/**
 * The Insights tab for one budget month (the one [monthsBack] months before the current period): the spending hero, and By category,
 * largest first. [year] and [month] name the month the period starts in, like [OverviewData].
 */
data class InsightsData(
    val year: Int,
    val month: Int,
    /** The period that holds today; only it runs "up to today" and has a comparison. */
    val isCurrentMonth: Boolean,
    /** Whether anything was entered before this month began, so the back arrow has somewhere to go. */
    val hasEarlier: Boolean,
    val totalText: String,
    /** Always Ink: spending never uses green, and a tint would give a figure away. */
    val tone: Tone,
    /** The same stretch of the month before ("Up to Oct 3"), only for the current month and when that stretch has entries. */
    val hasCompare: Boolean,
    /** Epoch day the comparison runs up to; 0 without one. */
    val compareDay: Int,
    val compareText: String,
    /** Largest first; ties by the category's own order. Only categories with spending. */
    val lines: List<InsightLineData>,
)

/** One category (with its subcategories) in one budget month: its total and every entry as spending sees it, newest day first. */
data class CategoryPageData(
    /** False when the category no longer exists. */
    val found: Boolean,
    val id: Long,
    val name: String,
    val iconKind: String,
    val iconValue: String,
    val color: Int,
    val year: Int,
    val month: Int,
    val isCurrentMonth: Boolean,
    val hasEarlier: Boolean,
    val totalText: String,
    val entryCount: Int,
    val days: List<DayData>,
)

// ---------- Account, card and person pages (iOS stage F) ----------

/** Under an account's name on its page: what the big figure is. */
enum class BalanceKind { Balance, OutstandingBalance, TotalValue, TotalBalance }

/**
 * One account's page (any kind but a card with statement days, which has [CardPageData]): the balance at the end of the budget month
 * shown, the month's money in and out, and its entries by day. [closeProblem] is why it can't be closed yet ("" when it can).
 */
data class AccountPageData(
    val found: Boolean,
    val id: Long,
    val name: String,
    val last4: String,
    /** An `AccountType` name. */
    val type: String,
    val balanceKind: BalanceKind,
    val balanceText: String,
    val balanceTone: Tone,
    val inText: String,
    val outText: String,
    val year: Int,
    val month: Int,
    val isCurrentMonth: Boolean,
    val hasEarlier: Boolean,
    /** An open card with statement days has its own page ([CardPageData]). */
    val hasCardPage: Boolean,
    /** Epoch day the account matures (fixed deposit), 0 for none. */
    val maturityDay: Int,
    /** Epoch day it was closed, 0 while open. */
    val closedDay: Int,
    /** Why Close account is refused ("Pay off or move the balance first."), empty when the balance is settled. Read only when the person taps Close, as on Android. */
    val closeProblem: String,
    val entryCount: Int,
    val days: List<DayData>,
)

/** What the line under Balance payable says; [CardPageData.statusDays] is the count DaysLate and DueInDays name. */
enum class CardStatus { DueOn, PaidExtra, PaidInFull, NothingDue, DaysLate, DueToday, DueTomorrow, DueInDays }

/** What sits under the statement figures when the last statement is shown. */
enum class CardLeft { None, LeftToPay, PaidExtra, PaidInFull }

/** A card with statement days: Balance payable, Total balance, Outstanding, and the statement navigator with its entries. */
data class CardPageData(
    val found: Boolean,
    val id: Long,
    val name: String,
    val last4: String,
    /** Balance payable without a minus (a credit shows its size, green). */
    val payableText: String,
    val payableTone: Tone,
    val status: CardStatus,
    val statusDays: Int,
    /** "Paid <x> extra": the size, only for PaidExtra. */
    val statusAmountText: String,
    val statusTone: Tone,
    /** Epoch day of the due date, and the tone of its tile. */
    val dueDay: Int,
    val dueTone: Tone,
    val totalText: String,
    val totalTone: Tone,
    val outstandingText: String,
    /** What the pay button names ("Pay $120.00"), and whether anything is payable (else the button says Record a payment). */
    val payText: String,
    val hasPayable: Boolean,
    /** Whether the card is open, so Pay card is offered. */
    val canPay: Boolean,
    /** Statements back from the current stretch (0 is charges since the last statement). */
    val back: Int,
    val canBack: Boolean,
    val canForward: Boolean,
    val fromDay: Int,
    /** For a statement, the day it closed (its entries run up to the day before); for the current stretch, today. */
    val toDay: Int,
    val lastCloseDay: Int,
    val nextCloseDay: Int,
    val chargesText: String,
    /** "" when no payment came in. */
    val paymentsText: String,
    val left: CardLeft,
    val leftText: String,
    val closedDay: Int,
    val closeProblem: String,
    val entryCount: Int,
    val days: List<DayData>,
)

/** One option of the pay-card sheet: [choice] is a `CardPayment.Choice` name, [cents] the amount (0 for OTHER). */
data class CardPayOptionData(
    val choice: String,
    val cents: Long,
    val amountText: String,
    /** Epoch day of the due date, for PAYABLE. */
    val dueDay: Int,
    /** "Balance payable plus <x> outstanding": the outstanding part, for TOTAL when both exist; empty otherwise. */
    val outstandingText: String,
)

/** What the pay-card sheet opens with. */
data class CardPayData(
    val found: Boolean,
    val cardId: Long,
    val name: String,
    val options: List<CardPayOptionData>,
    val defaultChoice: String,
    /** The card's own open "pay from" account, 0 for none: the only account ever preselected. */
    val defaultPayFromId: Long,
)

/** One line of a person's history. [noteKind]: TYPED (the note is the person's own), SPLIT, PAID_YOU or YOU_PAID (Swift words them). */
data class PersonLineData(
    val epochMillis: Long,
    val day: Int,
    val note: String,
    val noteKind: String,
    val amountText: String,
    val amountTone: Tone,
    val payment: Boolean,
    /** The payment's entry, 0 for a split or a starting balance. */
    val paymentId: Long,
)

/** A person's page: what is owed and since when, and the history. */
data class PersonPageData(
    val found: Boolean,
    val id: Long,
    val name: String,
    val archived: Boolean,
    val balanceText: String,
    val balanceTone: Tone,
    val side: PersonSide,
    /** Epoch day the open balance started; 0 when settled or while hidden. */
    val sinceDay: Int,
    /** What a new payment starts on: a `TxKind` name and the amount in cents (0 when settled). */
    val defaultKind: String,
    val suggestedCents: Long,
    val lines: List<PersonLineData>,
)

/** A payment as the person payment sheet edits it: flat, 0 means none, [kind] is a `TxKind` name. */
data class PersonPaymentEditData(
    val id: Long,
    val personId: Long,
    val personName: String,
    val kind: String,
    val amount: Long,
    val accountId: Long,
    val epochMillis: Long,
    val note: String,
)

/** The ledger of one restored backup, worked out for the iOS screens. Build one per refresh; see the file comment. */
class LedgerView(data: BackupData, fallbackCurrency: String?) {

    private val currency: CurrencyStyle =
        Currencies.forCode(data.currency) ?: Currencies.forCode(fallbackCurrency) ?: Currencies.fallback

    internal val parts = LedgerParts.of(data)
    private val accounts: List<Account> = parts.accounts
    internal val transactions: List<Transaction> = parts.transactions
    internal val categoryById: Map<Long, Category> = parts.categories.associateBy { it.id }
    internal val accountById: Map<Long, Account> = accounts.associateBy { it.id }
    private val balances: Map<Long, Long> = Balances.of(accounts, transactions)
    private val loanIndex = LoanEntryIndex(parts.loanPayments, parts.loanTerms)
    private val splitEntryIds: Set<Long> = parts.loanEntries.mapNotNull { it.transactionId }.toSet()

    /**
     * The arrangement the person made (Accounts > Order > Your order). A backup carries it but not the sort, so a backup
     * that has one shows it, as Android does after a restore; otherwise the groups and rows are A to Z.
     */
    private val arrangement: AccountArrangement? = AccountArrangement.decode(data.accountArrangement)

    /** The day each period starts on (1 to 28), as Android's `Ledger.period` reads it. */
    internal val monthStartDay: Int = data.monthStartDay.coerceIn(1, 28)

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
        val archivedAccounts = accounts.filter { it.archived }.sortedWith(Names.order { it.name })
        if (archivedAccounts.isNotEmpty()) groups.add(archivedGroup(archivedAccounts))
        val archivedPeople = parts.people.filter { it.archived }.sortedWith(Names.order { it.name })
        if (archivedPeople.isNotEmpty()) groups.add(archivedPeopleGroup(archivedPeople))

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

    internal fun cardSnapshots(todayEpochDay: Int, zone: TimeZone): Map<Long, CardSnapshot> {
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

    /** Archived accounts: a name and nothing else (an archived account is out of every figure), so it can be opened and restored. */
    private fun archivedGroup(archived: List<Account>): AccountsGroupData {
        val rows = archived.map { a ->
            AccountRowData(a.id, a.name, a.last4 ?: "", "", Tone.Ink, RowNote.Plain, "", DueKind.Nothing, 0, Tone.Ink, PersonSide.Hidden)
        }
        return AccountsGroupData(ARCHIVED, "Archived", false, "", Tone.Ink, rows)
    }

    private fun archivedPeopleGroup(archived: List<Person>): AccountsGroupData {
        val rows = archived.map { p ->
            AccountRowData(p.id, p.name, "", "", Tone.Ink, RowNote.Plain, "", DueKind.Nothing, 0, Tone.Ink, PersonSide.Hidden)
        }
        return AccountsGroupData(ARCHIVED_PEOPLE, "Archived people", false, "", Tone.Ink, rows)
    }

    // ---------- Activity ----------

    /**
     * One calendar month of Activity ([month] 1 to 12), entries as spending sees them (`CountedSpending`: a loan principal
     * that counts is an expense in its category), the totals from that same list, days newest first like Android.
     */
    fun activity(year: Int, month: Int, hidden: Boolean, todayEpochDay: Int, timeZoneId: String): ActivityData =
        activityFiltered(year, month, "ALL", hidden, todayEpochDay, timeZoneId)

    /** One entry row, as `TransactionRow` in the Android app builds it. The wording is Swift's. */
    internal fun entryRow(t: Transaction): EntryRowData {
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
            personPayment = (t.kind == TxKind.PERSON_IN || t.kind == TxKind.PERSON_OUT) && !loanIndex.isLoanEntry(t.id),
            loanPaymentId = loanIndex.paymentIdFor(t.id),
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
    fun overview(hidden: Boolean, todayEpochDay: Int, timeZoneId: String, firstWeekday: Int): OverviewData = shown(hidden) {
        val zone = zoneOf(timeZoneId)
        val today = localDateOfEpochDay(todayEpochDay)
        val period = Period.containing(today, monthStartDay)
        // This week starts on the phone's first day of the week (as Android's `weekPace(ledger).start`), and may begin before the month does.
        val weekStart = WeekPace.startOf(today, WeekRules.firstDayOf(firstWeekday))
        val earliest = if (weekStart.isBefore(period.start)) weekStart else period.start
        val inWindow = transactions.filter { t ->
            val day = dateOfMillis(t.epochMillis, zone)
            !day.isBefore(earliest) && !day.isAfter(today)
        }
        val counted = CountedSpending.apply(inWindow, parts.loanPayments, parts.loanTerms, parts.categories)
        val inPeriod = counted.filter { t -> !dateOfMillis(t.epochMillis, zone).isBefore(period.start) }
        val inWeek = counted.filter { t -> !dateOfMillis(t.epochMillis, zone).isBefore(weekStart) }
        OverviewData(
            year = period.yearMonth.year,
            month = period.yearMonth.month,
            spending = overviewFlow(inPeriod, TxKind.EXPENSE, false),
            income = overviewFlow(inPeriod, TxKind.INCOME, true),
            weekStartDay = weekStart.toEpochDay().toInt(),
            weekSpending = overviewFlow(inWeek, TxKind.EXPENSE, false),
            weekIncome = overviewFlow(inWeek, TxKind.INCOME, true),
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

    // ---------- Insights ----------

    /**
     * Insights for the budget month [monthsBack] months before the one that holds today (0 is the current one), as Android's
     * `InsightsScreen` month view: spending is the entries as spending sees them (`CountedSpending`) from the period's start to
     * today (the whole period once it is over), and By category rolls them up to top-level categories (`Spending.topOf`, each
     * entry's own share) largest first, ties by the category's order, spending with no category last. The comparison is
     * `Patterns.samePointBefore`: the same stretch of the month before. While [hidden] every amount is the mask, no share or bar
     * is given and the tone is Ink.
     */
    fun insights(hidden: Boolean, todayEpochDay: Int, timeZoneId: String, monthsBack: Int): InsightsData = shown(hidden) {
        val zone = zoneOf(timeZoneId)
        val today = localDateOfEpochDay(todayEpochDay)
        val period = insightsPeriod(today, monthsBack)
        val current = period.contains(today)
        val to = if (current) today else period.endInclusive
        val previous = Period.startingIn(period.yearMonth.minusMonths(1), monthStartDay)
        val window = countedBetween(previous.start, period.endInclusive, zone)
        // Like Android's By category (domain `Insights` skips spending with no category), so the lines can add up to less than the total.
        val rows = spendingRows(window, period.start, to, zone).filter { it.first != null }
        val total = Patterns.spent(window, period.start, to, zone)

        var hasCompare = false
        var compareDay = 0
        var compareText = ""
        if (current) {
            val same = Patterns.samePointBefore(period.start, today, previous.start)
            val before = Patterns.spent(window, same.first, same.second, zone)
            hasCompare = window.any { t ->
                val d = dateOfMillis(t.epochMillis, zone)
                !d.isBefore(same.first) && !d.isAfter(same.second)
            }
            compareDay = same.second.toEpochDay().toInt()
            compareText = Money.format(before)
        }
        val largest = rows.firstOrNull()?.second ?: 0L
        val lines = rows.map { r ->
            val c = r.first
            val icon = if (c == null) Pair("", "") else iconParts(c.icon)
            val share = if (Money.isHidden || total <= 0L) -1 else ((r.second * 100L + total / 2L) / total).toInt()
            // A category with a budget shows how much of it is used (orange past it, never while hidden); one without, its size against the largest.
            val budget = if (c == null) null else BudgetRules.lineFor(c.id, period.yearMonth, parts.budgets, r.second)
            val limit = budget?.budget
            val bar = if (Money.isHidden) 0
            else if (limit != null) (budget?.usedPermille ?: 0)
            else if (largest <= 0L) 0
            else (r.second * 1000L / largest).toInt().coerceIn(0, 1000)
            InsightLineData(
                c?.id ?: 0L, c?.name ?: "", icon.first, icon.second, c?.color ?: 0, Money.format(r.second), share, bar,
                if (limit == null) "" else Money.format(limit), !Money.isHidden && budget?.over == true,
            )
        }
        InsightsData(
            year = period.yearMonth.year,
            month = period.yearMonth.month,
            isCurrentMonth = current,
            hasEarlier = hasEarlier(period.start, zone),
            totalText = Money.format(total),
            tone = Tone.Ink,
            hasCompare = hasCompare,
            compareDay = compareDay,
            compareText = compareText,
            lines = lines,
        )
    }

    /**
     * The category page of Insights: top-level category [categoryId] (0 for spending with no category) in the same month
     * [insights] shows for [monthsBack]. Its entries are the counted ones (a loan principal that counts is an expense in its
     * principal category), so the total equals the line on Insights. Days newest first, rows as in Activity.
     */
    fun categoryPage(hidden: Boolean, todayEpochDay: Int, timeZoneId: String, monthsBack: Int, categoryId: Long): CategoryPageData = shown(hidden) {
        val zone = zoneOf(timeZoneId)
        val today = localDateOfEpochDay(todayEpochDay)
        val period = insightsPeriod(today, monthsBack)
        val current = period.contains(today)
        val to = if (current) today else period.endInclusive
        val category = categoryById[categoryId]
        val found = categoryId == 0L || category != null
        val counted = countedBetween(period.start, to, zone)
        val mine = counted.filter { t ->
            val top = Spending.topOf(t.categoryId, categoryById)
            t.kind == TxKind.EXPENSE && (top?.id ?: 0L) == categoryId
        }
        val days = ArrayList<DayData>()
        val grouped = mine.groupBy { dateOfMillis(it.epochMillis, zone) }
        for ((day, entries) in grouped) {
            val spent = entries.sumOf { it.categoryAmount }
            days.add(DayData(day.toEpochDay().toInt(), Money.format(spent), entries.map { entryRow(it) }))
        }
        val icon = if (category == null) Pair("", "") else iconParts(category.icon)
        CategoryPageData(
            found = found,
            id = categoryId,
            name = category?.name ?: "",
            iconKind = icon.first,
            iconValue = icon.second,
            color = category?.color ?: 0,
            year = period.yearMonth.year,
            month = period.yearMonth.month,
            isCurrentMonth = current,
            hasEarlier = hasEarlier(period.start, zone),
            totalText = Money.format(mine.sumOf { it.categoryAmount }),
            entryCount = mine.size,
            days = days,
        )
    }

    /** The budget month [monthsBack] before the one that holds [today]. */
    private fun insightsPeriod(today: LocalDate, monthsBack: Int): Period {
        val current = Period.containing(today, monthStartDay)
        return Period.startingIn(current.yearMonth.minusMonths(monthsBack.coerceAtLeast(0)), monthStartDay)
    }

    /** The entries dated from [from] to [to], inclusive, as spending sees them (`CountedSpending`), newest first. */
    internal fun countedBetween(from: LocalDate, to: LocalDate, zone: TimeZone): List<Transaction> {
        val inRange = transactions.filter { t ->
            val d = dateOfMillis(t.epochMillis, zone)
            !d.isBefore(from) && !d.isAfter(to)
        }
        return CountedSpending.apply(inRange, parts.loanPayments, parts.loanTerms, parts.categories)
    }

    /** Expense totals per top-level category (null for none) between two dates, only above zero, largest first, then by category order. */
    private fun spendingRows(list: List<Transaction>, from: LocalDate, to: LocalDate, zone: TimeZone): List<Pair<Category?, Long>> {
        val inRange = list.filter { t ->
            val d = dateOfMillis(t.epochMillis, zone)
            t.kind == TxKind.EXPENSE && !d.isBefore(from) && !d.isAfter(to)
        }
        val grouped = inRange.groupBy { Spending.topOf(it.categoryId, categoryById) }
        val rows = grouped.entries.map { e -> Pair(e.key, e.value.sumOf { it.categoryAmount }) }.filter { it.second > 0L }
        return rows.sortedWith(
            compareByDescending<Pair<Category?, Long>> { it.second }.thenBy { it.first?.sortOrder ?: Int.MAX_VALUE },
        )
    }

    internal fun hasEarlier(start: LocalDate, zone: TimeZone): Boolean =
        transactions.any { t -> dateOfMillis(t.epochMillis, zone).isBefore(start) }

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

    // ---------- Categories and subcategories (batch Q) ----------

    private fun listRow(c: Category): CategoryListRowData {
        val subs = parts.categories.count { it.parentId == c.id && !it.hidden }
        return CategoryListRowData(categoryChoiceOf(c, false), subs, c.kind == CategoryKind.EXPENSE && !c.countsTowardDaily)
    }

    /** Settings > Categories for [kind] (a `CategoryKind` name): in use first, A to Z, then the archived ones. */
    fun categoryList(kind: String): CategoryListData {
        val k = categoryKindOf(kind) ?: return CategoryListData(emptyList(), emptyList())
        val (active, archived) = CategoryRules.listing(parts.categories, k)
        return CategoryListData(active.map(::listRow), archived.map(::listRow))
    }

    private fun editData(c: Category, isNew: Boolean): CategoryEditData {
        val all = parts.categories
        val isTop = c.parentId == null
        val used = if (isTop) CategoryColors.usedBy(all, c.kind, c.id) else emptyMap()
        val colors = CategoryColors.palette.map { color ->
            ColorChoiceData(color, used[color].orEmpty().sortedWith(Names.order<String> { it }).joinToString(", "))
        }
        val subs = if (isTop && !isNew) {
            val (active, archived) = CategoryRules.subs(all, c.id)
            (active + archived).map { SubcategoryRowData(it.id, it.name, it.hidden) }
        } else emptyList()
        val merge = if (isNew) emptyList() else CategoryRules.mergeTargets(all, c).map { CategoryTargetData(it.id, it.name) }
        val move = if (isNew || isTop) emptyList() else CategoryRules.moveTargets(all, c).map { CategoryTargetData(it.id, it.name) }
        return CategoryEditData(true, CategoryInput.of(c), isNew, isTop, colors, subs, merge, move)
    }

    /** The editor for the stored category [categoryId] (top-level or a subcategory). */
    fun categoryEdit(categoryId: Long): CategoryEditData {
        val c = categoryById[categoryId]
            ?: return CategoryEditData(false, CategoryInput(0, "EXPENSE", 0, "", "emoji", "", 0, true, false), true, true, emptyList(), emptyList(), emptyList(), emptyList())
        return editData(c, false)
    }

    /** The editor for a new top-level category of [kind]: blank, with the first colour nobody of its kind uses while one is free. */
    fun categoryNew(kind: String): CategoryEditData {
        val k = categoryKindOf(kind) ?: CategoryKind.EXPENSE
        return editData(CategoryRules.blank(parts.categories, k).copy(icon = CategoryIcon.DEFAULT), true)
    }

    /** The first problem of saving [input] (a sentence), or empty text when it can be saved. */
    fun categoryProblem(input: CategoryInput): String {
        val c = input.category() ?: return CategoryRules.GONE
        return CategoryRules.problem(parts.categories, c) ?: ""
    }

    /** The problem of adding a subcategory named [name] under [parentId], or empty text. */
    fun subcategoryProblem(parentId: Long, name: String): String {
        val parent = categoryById[parentId] ?: return CategoryRules.GONE
        return CategoryRules.nameProblem(parts.categories, parent.kind, parent.id, 0, name) ?: ""
    }

    /** A new subcategory called [name] under [parentId]: same kind, icon, colour and daily setting as its parent, cleaned name. */
    fun subcategoryInput(parentId: Long, name: String): CategoryInput {
        val parent = categoryById[parentId] ?: return CategoryInput(0, "EXPENSE", 0, "", "emoji", "", 0, true, false)
        return CategoryInput.of(parent.subcategory(InputRules.clean(name)))
    }

    /** The stored category [categoryId] with a new [name] and archived state, everything else as stored (rename, archive and restore). */
    fun categoryInputWith(categoryId: Long, name: String, hidden: Boolean): CategoryInput {
        val c = categoryById[categoryId] ?: return CategoryInput(0, "EXPENSE", 0, "", "emoji", "", 0, true, false)
        return CategoryInput.of(c.copy(name = InputRules.clean(name), hidden = hidden))
    }

    /** The stored subcategory [subId] moved under the top-level category [toTopId], wearing its colour. */
    fun movedSubcategoryInput(subId: Long, toTopId: Long): CategoryInput {
        val sub = categoryById[subId]
        val top = categoryById[toTopId]
        if (sub == null || top == null) return CategoryInput(0, "EXPENSE", 0, "", "emoji", "", 0, true, false)
        return CategoryInput.of(CategoryRules.moved(sub, top))
    }

    /** The subcategories (ids as decimal text) that must take [input]'s colour when a top-level category is saved with it. */
    fun recolorIds(input: CategoryInput): List<String> {
        val c = input.category() ?: return emptyList()
        return CategoryRules.recolored(parts.categories, c).map { it.id.toString() }
    }

    /** One cell of the `categories` row for [input] (BackupRows column order); null for the id, the external id and what isn't the editor's. [sortOrder] numbers a new one. */
    fun categoryCell(input: CategoryInput, column: Int, sortOrder: Int): String? {
        val c = input.category() ?: return null
        val stored = categoryById[c.id]
        return when (BackupRows.columns("categories").getOrNull(column)) {
            "name" -> InputRules.clean(c.name)
            "kind" -> c.kind.name
            "parentId" -> c.parentId?.toString()
            "icon" -> c.icon.encode()
            "color" -> c.color.toString()
            "countsTowardDaily" -> if (c.countsTowardDaily) "1" else "0"
            "hidden" -> if (c.hidden) "1" else "0"
            "sortOrder" -> (stored?.sortOrder ?: sortOrder).toString()
            else -> null
        }
    }

    /** Why [fromId] can't be merged into [intoId] (every entry moves, then it is archived), or empty text. */
    fun mergeProblem(fromId: Long, intoId: Long): String = CategoryRules.mergeProblem(parts.categories, fromId, intoId) ?: ""

    /** The name of category [id], or empty text. */
    fun categoryName(id: Long): String = categoryById[id]?.name ?: ""

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

    // ---------- Splits ----------

    /**
     * The split the entry page opens with for entry [entryId] (`SplitRules.start`): its people and shares read from its lines, or the
     * payer when someone else paid. [withPersonId] (0 for none) adds a person, as "Split a transaction" from their page does.
     */
    fun splitStart(entryId: Long, withPersonId: Long): SplitState {
        val t = transactions.firstOrNull { it.id == entryId } ?: return SplitBridge.none()
        val lines = parts.loanEntries.filter { it.transactionId == t.id }.groupBy { it.personId }.mapValues { (_, rows) -> rows.sumOf { it.amount } }
        return SplitState.of(SplitRules.start(t.kind, t.amount, t.accountId, t.personId, t.myShare, lines, withPersonId))
    }

    /** The people the split picker lists, A to Z; an archived person only while [selected] (ids as "1,2") already has them. */
    fun splitPeople(selected: String): List<SplitPersonData> =
        SplitRules.pickable(parts.people, SplitText.ids(selected)).map { SplitPersonData(it.id, it.name, it.archived) }

    /** Whether there is anyone to split with (a person who isn't archived): the Split row is offered only then. */
    fun hasSplitPeople(): Boolean = openPeople().isNotEmpty()

    /** The name of person [id], empty text when there is none. */
    fun personName(id: Long): String = parts.people.firstOrNull { it.id == id }?.name ?: ""

    /**
     * What "Split a transaction" on person [personId]'s page offers (`SplitRules.canSplitWith`): expenses you paid from an account in
     * the last 60 days, newest first, not already split with them and not part of a loan payment.
     */
    fun splitCandidates(personId: Long, hidden: Boolean, todayEpochDay: Int, timeZoneId: String): List<DatedRowData> = shown(hidden) {
        val zone = zoneOf(timeZoneId)
        val already = parts.loanEntries.filter { it.personId == personId }.mapNotNull { it.transactionId }.toSet()
        transactions
            .filter { t ->
                SplitRules.canSplitWith(
                    t.kind, t.accountId, t.id in already, loanIndex.isLoanEntry(t.id),
                    dateOfMillis(t.epochMillis, zone).toEpochDay().toInt(), todayEpochDay,
                )
            }
            .sortedByDescending { it.epochMillis }
            .map { DatedRowData(dateOfMillis(it.epochMillis, zone).toEpochDay().toInt(), entryRow(it)) }
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

    // ---------- Account, card and person pages ----------

    /** What an account is worth to close it: its balance plus, for an investment account, its holdings. */
    private fun worth(a: Account): Long = (balances[a.id] ?: 0L) + holdingsValue(a)

    /** Why account [accountId] can't be closed yet ("Pay off or move the balance first."), or empty text when it can (`closeProblem`). */
    fun closeProblem(accountId: Long): String {
        val a = accountById[accountId] ?: return ""
        return ruleCloseProblem(worth(a)) ?: ""
    }

    // ---------- Editing accounts, cards and people (batch Q) ----------

    /** The Edit account form for [accountId] as it opens: what is stored, the balance as the person sees it, the kinds it may change to. */
    fun accountEdit(accountId: Long): AccountEditData {
        val a = accountById[accountId]
            ?: return AccountEditData(false, AccountInput("CHECKING", "", "", "", "", "", "", 0L, 0), false, false, emptyList())
        return AccountEditData(
            true, EditParts.input(a, balances[a.id] ?: 0L), a.isClosed, a.archived, EditRules.typeChoices(a.type).map { it.name },
        )
    }

    /** Every other account's name (this one may keep its own), archived and closed ones too. */
    fun otherAccountNames(accountId: Long): List<String> = accounts.filter { it.id != accountId }.map { it.name }

    /** Every other person's name, archived ones too. */
    fun otherPersonNames(personId: Long): List<String> = parts.people.filter { it.id != personId }.map { it.name }

    /**
     * The first problem of an edited account (the `AccountField` name, "PAY_FROM" or "BALANCE", and the sentence), in the ledger's
     * currency and never masked; empty when it can be saved. A closed account refuses a new balance.
     */
    fun accountEditProblems(accountId: Long, input: AccountInput): List<FieldProblemData> = shown(false) {
        val a = accountById[accountId] ?: return@shown listOf(FieldProblemData("", EditRules.ACCOUNT_GONE))
        EditParts.problems(a, balances[a.id] ?: 0L, input, otherAccountNames(accountId))
    }

    /** One cell of the edited `accounts` row (BackupRows column order); null when the account is gone or the column isn't the form's. */
    fun editedAccountCell(accountId: Long, input: AccountInput, column: Int): String? = shown(false) {
        val a = accountById[accountId] ?: return@shown null
        val saved = EditParts.edited(a, balances[a.id] ?: 0L, input) ?: return@shown null
        EditParts.cell(saved, BackupRows.columns("accounts").getOrNull(column))
    }

    /** The Edit person form for [personId]; [hidden] masks the size of what is open in the sentence that says why archiving waits. */
    fun personEdit(personId: Long, hidden: Boolean): PersonEditData = shown(hidden) {
        val p = parts.people.firstOrNull { it.id == personId }
            ?: return@shown PersonEditData(false, "", true, "", false, false, "")
        val balance = personBalance(p.id, parts.loanEntries, transactions)
        val form = EditRules.personForm(p.name, startingBalanceOf(p.id))
        PersonEditData(
            true, form.name, form.theyOwe, form.amount, p.archived, EditRules.archiveBlocked(balance),
            Money.format(EditRules.archiveBlockedSize(balance)),
        )
    }

    /** What was owed when the person was added: their lines not tied to an entry (positive: they owe you). */
    fun startingBalanceOf(personId: Long): Long =
        parts.loanEntries.filter { it.personId == personId && it.transactionId == null }.sumOf { it.amount }

    /** True when the person has money open, so archiving waits (restoring never does). */
    fun personArchiveBlocked(personId: Long): Boolean =
        EditRules.archiveBlocked(personBalance(personId, parts.loanEntries, transactions))

    private fun hasCardPage(a: Account): Boolean = a.type.hasStatement && a.isActive && a.statementDay != null

    private fun snapshotOf(a: Account, todayEpochDay: Int, zone: TimeZone): CardSnapshot =
        CardCycle.snapshot(a, transactions, localDateOfEpochDay(todayEpochDay), zone, balance = balances[a.id] ?: 0L)

    private fun dayGroups(list: List<Transaction>, zone: TimeZone): List<DayData> {
        val days = ArrayList<DayData>()
        val grouped = list.groupBy { dateOfMillis(it.epochMillis, zone) }
        for ((day, entries) in grouped) days.add(DayData(day.toEpochDay().toInt(), "", entries.map { entryRow(it) }))
        return days
    }

    /**
     * One account's page for the budget month [monthsBack] before the current one (as Insights counts months): the balance at the
     * end of that month ("what is owed", no minus, for a debt; cash plus holdings for an investment), money in and out, and the
     * entries on the account (as stored: balances, cards and loans read the real entries). Close and Reopen state ride along.
     */
    fun accountPage(accountId: Long, hidden: Boolean, todayEpochDay: Int, timeZoneId: String, monthsBack: Int): AccountPageData = shown(hidden) {
        val a = accountById[accountId]
        if (a == null) {
            AccountPageData(false, accountId, "", "", "", BalanceKind.Balance, "", Tone.Ink, "", "", 0, 0, true, false, false, 0, 0, "", 0, emptyList())
        } else {
            val zone = zoneOf(timeZoneId)
            val period = insightsPeriod(localDateOfEpochDay(todayEpochDay), monthsBack)
            val mine = transactions.filter { it.accountId == a.id || it.toAccountId == a.id }
            val rows = mine.filter { dateOfMillis(it.epochMillis, zone) in period }
            val end = worth(a) - mine.filter { dateOfMillis(it.epochMillis, zone).isAfter(period.endInclusive) }.sumOf { it.effectOn(a.id) }
            val moneyIn = rows.sumOf { maxOf(0L, it.effectOn(a.id)) }
            val moneyOut = rows.sumOf { maxOf(0L, -it.effectOn(a.id)) }
            val debt = a.type.isDebt
            val kind = when {
                a.type == AccountType.INVESTMENT -> BalanceKind.TotalValue
                a.type == AccountType.CREDIT_CARD -> BalanceKind.TotalBalance
                debt -> BalanceKind.OutstandingBalance
                else -> BalanceKind.Balance
            }
            AccountPageData(
                found = true,
                id = a.id,
                name = a.name,
                last4 = a.last4 ?: "",
                type = a.type.name,
                balanceKind = kind,
                balanceText = if (debt) cardMoney(-end) else Money.format(end),
                balanceTone = if (debt) cardTint(-end) else balanceTone(a.type, end),
                inText = Money.format(moneyIn),
                outText = Money.format(moneyOut),
                year = period.yearMonth.year,
                month = period.yearMonth.month,
                isCurrentMonth = monthsBack <= 0,
                hasEarlier = hasEarlier(period.start, zone),
                hasCardPage = hasCardPage(a),
                maturityDay = a.maturityDay ?: 0,
                closedDay = a.closedDay ?: 0,
                closeProblem = ruleCloseProblem(worth(a)) ?: "",
                entryCount = rows.size,
                days = dayGroups(rows, zone),
            )
        }
    }

    private fun emptyCardPage(id: Long) = CardPageData(
        false, id, "", "", "", Tone.Ink, CardStatus.DueOn, 0, "", Tone.Ink, 0, Tone.Ink, "", Tone.Ink, "", "", false, false,
        0, false, false, 0, 0, 0, 0, "", "", CardLeft.None, "", 0, "", 0, emptyList(),
    )

    private fun statusOf(s: CardPageRules.Status): CardStatus = when (s) {
        CardPageRules.Status.DUE_ON -> CardStatus.DueOn
        CardPageRules.Status.PAID_EXTRA -> CardStatus.PaidExtra
        CardPageRules.Status.PAID_IN_FULL -> CardStatus.PaidInFull
        CardPageRules.Status.NOTHING_DUE -> CardStatus.NothingDue
        CardPageRules.Status.DAYS_LATE -> CardStatus.DaysLate
        CardPageRules.Status.DUE_TODAY -> CardStatus.DueToday
        CardPageRules.Status.DUE_TOMORROW -> CardStatus.DueTomorrow
        CardPageRules.Status.DUE_IN_DAYS -> CardStatus.DueInDays
    }

    /**
     * A card's page as of [todayEpochDay]: Balance payable, Total balance and Outstanding from [CardCycle.snapshot] (the wording of
     * Money Manager: payable is the unpaid last statement, outstanding the charges since the statement date, total both), the
     * due line from [CardPageRules.status], and the statement [back] (0 is the charges since the last statement date) with its
     * entries. While hidden the due line says only when it is due and no colour is given. Empty (not found) for anything that is not an
     * open card with statement days.
     */
    fun cardPage(cardId: Long, hidden: Boolean, todayEpochDay: Int, timeZoneId: String, back: Int): CardPageData = shown(hidden) {
        val a = accountById[cardId]
        if (a == null || !hasCardPage(a)) {
            emptyCardPage(cardId)
        } else {
            val zone = zoneOf(timeZoneId)
            val today = localDateOfEpochDay(todayEpochDay)
            val s = snapshotOf(a, todayEpochDay, zone)
            val w = CardPageRules.window(s, a.statementDay ?: 1, back.coerceAtLeast(0), today)
            val oldest = transactions.filter { it.accountId == a.id || it.toAccountId == a.id }
                .minOfOrNull { it.epochMillis }?.let { dateOfMillis(it, zone) }
            val list = CardPageRules.entriesIn(a.id, transactions, w, zone)
            val credits = CardPageRules.credits(a.id, list)
            val line = CardPageRules.status(s, today, Money.isHidden)
            val tone = CardPageRules.dueTone(s, today)
            val dueTone = if (Money.isHidden) Tone.Ink else when (tone) {
                CardPageRules.DueTone.ATTENTION -> Tone.Orange
                CardPageRules.DueTone.POSITIVE -> Tone.Green
                CardPageRules.DueTone.NEUTRAL -> Tone.Ink
            }
            val payableTone = if (Money.isHidden) Tone.Ink else if (s.balancePayable < 0) Tone.Green else if (s.balancePayable > 0) Tone.Orange else Tone.Ink
            var left = CardLeft.None
            var leftText = ""
            if (w.back == 1 && !Money.isHidden) {
                if (s.payable > 0) {
                    left = CardLeft.LeftToPay
                    leftText = Money.format(s.payable)
                } else if (s.balancePayable < 0) {
                    left = CardLeft.PaidExtra
                    leftText = Money.format(-s.balancePayable)
                } else {
                    left = CardLeft.PaidInFull
                }
            }
            CardPageData(
                found = true,
                id = a.id,
                name = a.name,
                last4 = a.last4 ?: "",
                payableText = Money.format(abs(s.balancePayable)),
                payableTone = payableTone,
                status = statusOf(line.status),
                statusDays = line.days.toInt(),
                statusAmountText = if (line.status == CardPageRules.Status.PAID_EXTRA) Money.format(-s.balancePayable) else "",
                statusTone = dueTone,
                dueDay = s.due.toEpochDay().toInt(),
                dueTone = dueTone,
                totalText = cardMoney(s.totalBalance),
                totalTone = cardTint(s.totalBalance),
                outstandingText = Money.format(s.outstanding),
                payText = Money.format(s.payable),
                hasPayable = s.payable > 0,
                canPay = true,
                back = w.back,
                canBack = CardPageRules.canGoBack(oldest, w),
                canForward = w.back > 0,
                fromDay = w.from.toEpochDay().toInt(),
                toDay = w.to.toEpochDay().toInt(),
                lastCloseDay = s.lastClose.toEpochDay().toInt(),
                nextCloseDay = s.nextClose.toEpochDay().toInt(),
                chargesText = Money.format(CardPageRules.charges(a.id, list)),
                paymentsText = if (credits > 0) Money.format(credits) else "",
                left = left,
                leftText = leftText,
                closedDay = a.closedDay ?: 0,
                closeProblem = ruleCloseProblem(worth(a)) ?: "",
                entryCount = list.size,
                days = dayGroups(list, zone),
            )
        }
    }

    /**
     * What the pay-card sheet opens with: the amounts it offers (`CardPayment.options`), the one it starts on, and the card's own
     * "pay from" account when still open (the only account ever preselected). Not found for anything but an open card with statement days.
     */
    fun cardPayData(cardId: Long, hidden: Boolean, todayEpochDay: Int, timeZoneId: String): CardPayData = shown(hidden) {
        val a = accountById[cardId]
        if (a == null || !hasCardPage(a)) {
            CardPayData(false, cardId, "", emptyList(), CardPayment.Choice.OTHER.name, 0)
        } else {
            val s = snapshotOf(a, todayEpochDay, zoneOf(timeZoneId))
            val options = CardPayment.options(s).map { o ->
                CardPayOptionData(
                    choice = o.choice.name,
                    cents = o.amount,
                    amountText = if (o.choice == CardPayment.Choice.OTHER) "" else Money.format(o.amount),
                    dueDay = if (o.choice == CardPayment.Choice.PAYABLE) s.due.toEpochDay().toInt() else 0,
                    outstandingText = if (o.choice == CardPayment.Choice.TOTAL && s.outstanding > 0 && s.payable > 0) Money.format(s.outstanding) else "",
                )
            }
            CardPayData(true, a.id, a.name, options, CardPayment.defaultChoice(s).name, CardPayment.defaultPayFrom(a, accounts) ?: 0L)
        }
    }

    /** A person's page: the balance (no minus; the side says who owes whom, never while hidden), since when, and the history, newest first. */
    fun personPage(personId: Long, hidden: Boolean, timeZoneId: String): PersonPageData = shown(hidden) {
        val p = parts.people.firstOrNull { it.id == personId }
        if (p == null) {
            PersonPageData(false, personId, "", false, "", Tone.Ink, PersonSide.Hidden, 0, TxKind.PERSON_IN.name, 0, emptyList())
        } else {
            val zone = zoneOf(timeZoneId)
            val balance = personBalance(p.id, parts.loanEntries, transactions)
            val history = PersonHistory.lines(p.id, parts.loanEntries, transactions)
            val since = if (balance != 0L && !Money.isHidden) PersonHistory.openSince(history) else null
            val side = when {
                Money.isHidden -> PersonSide.Hidden
                balance > 0 -> PersonSide.OwesYou
                balance < 0 -> PersonSide.YouOwe
                else -> PersonSide.Settled
            }
            val lines = history.map { l ->
                val noteKind = when {
                    l.note.isNotBlank() -> "TYPED"
                    !l.payment -> "SPLIT"
                    l.kind == TxKind.PERSON_IN -> "PAID_YOU"
                    else -> "YOU_PAID"
                }
                PersonLineData(
                    epochMillis = l.epochMillis,
                    day = dateOfMillis(l.epochMillis, zone).toEpochDay().toInt(),
                    note = l.note,
                    noteKind = noteKind,
                    amountText = if (l.payment) Money.format(abs(l.amount)) else Money.format(l.amount, showPlus = true),
                    amountTone = if (l.payment) Tone.Ink else favour(l.amount),
                    payment = l.payment,
                    paymentId = if (l.payment) l.transactionId ?: 0L else 0L,
                )
            }
            PersonPageData(
                found = true,
                id = p.id,
                name = p.name,
                archived = p.archived,
                balanceText = Money.format(abs(balance)),
                balanceTone = favour(balance),
                side = side,
                sinceDay = since?.let { dateOfMillis(it, zone).toEpochDay().toInt() } ?: 0,
                defaultKind = PersonPayment.defaultKind(balance).name,
                suggestedCents = PersonPayment.suggestedAmount(balance),
                lines = lines,
            )
        }
    }

    /** The payment [id] as the person payment sheet edits it, or null when it isn't a payment to or from a person (or belongs to a loan payment). */
    fun personPaymentEdit(id: Long): PersonPaymentEditData? {
        val t = transactions.firstOrNull { it.id == id } ?: return null
        val personId = t.personId ?: return null
        if (!PersonPayment.isPaymentKind(t.kind) || loanIndex.isLoanEntry(t.id)) return null
        val name = parts.people.firstOrNull { it.id == personId }?.name ?: ""
        return PersonPaymentEditData(t.id, personId, name, t.kind.name, t.amount, t.accountId ?: 0L, t.epochMillis, t.note)
    }

    /**
     * The picker of the person payment sheet: open checking, savings, cash, prepaid and card accounts (`PersonPayment.sources`), plus
     * [keepAccountId] (an edited payment's account) even when closed since; grouped like [accountPick], no Recent row, nothing preselected.
     */
    fun personPaymentPick(query: String, keepAccountId: Long): AccountPickData {
        val options = PersonPayment.sources(accounts, if (keepAccountId > 0) keepAccountId else null)
        val groups = AccountPicking.groups(options, AccountKindFilter.ALL, query.trim()).map { g ->
            AccountChoiceGroupData(g.section.name, g.accounts.map(::accountChoiceOf))
        }
        return AccountPickData(listOf(AccountKindFilter.ALL.name), groups, emptyList(), options.isEmpty())
    }

    // ---------- Loans and balance corrections ----------

    /**
     * A loan account's page as of [todayEpochDay] (`LoanSummary.of`, the same figures as Android's loan page): outstanding balance today,
     * progress, the payment due and the payoff, interest paid and left, the next [LOAN_NEXT_ROWS] schedule rows (all of them when
     * [allRows]), the terms in effect today with their dated changes, and the entries of the budget month [monthsBack] before the
     * current one. A loan with no terms comes back with [LoanPageData.hasTerms] false.
     */
    fun loanPage(accountId: Long, hidden: Boolean, todayEpochDay: Int, timeZoneId: String, monthsBack: Int, allRows: Boolean): LoanPageData = shown(hidden) {
        buildLoanPage(accountId, hidden, todayEpochDay, timeZoneId, monthsBack, allRows)
    }

    private fun emptyLoanPage(id: Long, found: Boolean, name: String, last4: String) = LoanPageData(
        found, false, id, name, last4, 0, "", false, "", Tone.Ink, Tone.Ink, false, 0, 0, "", false, "", 0, 0, "", "", false,
        "", "", "", false, "", 0, 0, emptyList(), 0, emptyList(), 0, 0, true, false, 0, emptyList(),
    )

    private fun buildLoanPage(accountId: Long, hidden: Boolean, todayEpochDay: Int, timeZoneId: String, monthsBack: Int, allRows: Boolean): LoanPageData {
        val a = accountById[accountId]
        if (a == null || a.type != AccountType.LOAN) return emptyLoanPage(accountId, false, "", "")
        val terms = parts.loanTerms[a.id] ?: return emptyLoanPage(a.id, true, a.name, a.last4 ?: "")
        val changes = parts.loanChanges.filter { it.accountId == a.id }.sortedWith(compareBy({ it.effectiveDay }, { it.id }))
        val payments = parts.loanPayments.filter { it.accountId == a.id }
        val byId = transactions.associateBy { it.id }
        val interestPaid = payments.sumOf { p -> p.interestEntryId?.let { byId[it]?.amount } ?: 0L }
        val balance = balances[a.id] ?: 0L
        val summary = LoanSummary.of(terms, changes, payments, -balance, interestPaid)
        val eff = terms.effective(changes, todayEpochDay)
        val paying = summary.owed > 0
        val fraction = summary.fractionRepaid
        val percent = summary.percentRepaid
        val original = summary.originalAmount
        val progress = fraction != null && percent != null && original != null && !Money.isHidden
        val entries = accountPage(accountId, hidden, todayEpochDay, timeZoneId, monthsBack)
        val shownRows = if (allRows) summary.rows else summary.rows.take(LOAN_NEXT_ROWS)
        return LoanPageData(
            found = true,
            hasTerms = true,
            id = a.id,
            name = a.name,
            last4 = a.last4 ?: "",
            closedDay = a.closedDay ?: 0,
            closeProblem = ruleCloseProblem(worth(a)) ?: "",
            profit = terms.profit,
            balanceText = Money.format(abs(balance)),
            balanceTone = cardTint(-balance),
            heroTone = if (Money.isHidden) Tone.Ink else if (balance < 0) Tone.Orange else Tone.Green,
            hasProgress = progress,
            percent = percent ?: 0,
            permille = ((fraction ?: 0f) * 1000f).toInt().coerceIn(0, 1000),
            originalText = if (original != null) Money.format(original) else "",
            paying = paying,
            paymentText = if (paying) Money.format(summary.payment) else "",
            nextPaymentDay = summary.nextPaymentDay,
            payoffDay = summary.payoffDay ?: 0,
            interestPaidText = Money.format(summary.interestPaid),
            interestLeftText = summary.interestLeft?.let { Money.format(it) } ?: "",
            doesntCover = paying && !summary.outlook.coversInterest && !Money.isHidden,
            interestType = eff.type.name,
            rateText = LoanRate.display(eff.rate),
            termPaymentText = Money.format(eff.payment),
            hasEscrow = terms.escrow > 0,
            escrowText = if (terms.escrow > 0) Money.format(terms.escrow) else "",
            termMonths = terms.termMonths ?: 0,
            dueDay = terms.dueDay,
            changes = changes.map { LoanChangeData(it.id, it.effectiveDay, it.summary(terms.profit)) },
            scheduleCount = summary.rows.size,
            rows = shownRows.map { LoanRowData(it.day, Money.format(it.principal), Money.format(it.interest), Money.format(it.balance)) },
            year = entries.year,
            month = entries.month,
            isCurrentMonth = entries.isCurrentMonth,
            hasEarlier = entries.hasEarlier,
            entryCount = entries.entryCount,
            days = entries.days,
        )
    }

    /**
     * The setup form for "Add terms" on loan account [accountId]: already partway, filled with what is owed today (`LoanForm.forAccount`).
     * The form's `payFromId` starts as the account's own, when it has one.
     */
    fun loanTermsInput(accountId: Long, todayEpochDay: Int): LoanInput {
        val a = accountById[accountId]
        val owed = -(balances[accountId] ?: 0L)
        return shown(false) { LoanInput.of(LoanForm.forAccount(owed, todayEpochDay, a?.payFromId)) }
    }

    /** Whether loan account [accountId] has terms. */
    fun hasLoanTerms(accountId: Long): Boolean = parts.loanTerms.containsKey(accountId)

    /**
     * The balance correction sheet for [accountId] (a new correction) or the correction entry [editingId] (its account is read from it).
     * Not found when the account or the correction is gone, or the entry is not a correction. See `BalanceCorrectionSheet`.
     */
    fun correctionSheet(accountId: Long, editingId: Long, hidden: Boolean): CorrectionSheetData = shown(hidden) {
        val old = if (editingId > 0) transactions.firstOrNull { it.id == editingId } else null
        val id = old?.accountId ?: accountId
        val a = accountById[id]
        val badEdit = editingId > 0 && (old == null || !BalanceCorrection.isCorrection(old.kind) || old.accountId != id)
        if (a == null || badEdit) {
            CorrectionSheetData(false, accountId, "", false, false, 0, 0, "", 0, 0)
        } else {
            val debt = a.type.isDebt
            val now = balances[a.id] ?: 0L
            val without = now - (old?.effectOn(a.id) ?: 0L)
            val shownWithout = BalanceCorrection.shownFrom(without, debt)
            CorrectionSheetData(
                found = true,
                accountId = a.id,
                accountName = a.name,
                debt = debt,
                isLoan = a.type == AccountType.LOAN,
                editingId = if (old != null) old.id else 0L,
                shownWithout = shownWithout,
                balanceInKassaText = if (debt) cardMoney(shownWithout) else Money.format(shownWithout),
                initialShown = if (old != null) BalanceCorrection.shownFrom(now, debt) else 0L,
                epochMillis = old?.epochMillis ?: 0L,
            )
        }
    }

    /**
     * What saving a correction makes [accountId] show [typedShown] comes to (a debt typed as what is owed): the domain's problem
     * sentence, or the kind and amount to store. [editingId] names an earlier correction that is replaced, so the difference is
     * worked out without it (`EntryRepository.correctBalance`). The sentence is never masked.
     */
    fun correctionPlan(accountId: Long, editingId: Long, typedShown: Long): CorrectionPlanData = shown(false) {
        val a = accountById[accountId]
        if (a == null) {
            CorrectionPlanData("", "", 0L)
        } else {
            val old = if (editingId > 0) transactions.firstOrNull { it.id == editingId } else null
            val target = BalanceCorrection.targetFrom(typedShown, a.type.isDebt)
            val without = (balances[accountId] ?: 0L) - (old?.effectOn(accountId) ?: 0L)
            val change = BalanceCorrection.change(target, without)
            val problem = InputRules.balanceProblem(target) ?: BalanceCorrection.problem(change)
            val entry = BalanceCorrection.entry(0, accountId, change, 0)
            if (problem != null || entry == null) CorrectionPlanData(problem ?: "", "", 0L)
            else CorrectionPlanData("", entry.kind.name, entry.amount)
        }
    }

    /** The `loan_payments` row entry [entryId] belongs to, whatever its kind (0 for none): Activity opens it on the payment sheet. */
    fun loanPaymentIdOfEntry(entryId: Long): Long = loanIndex.paymentIdFor(entryId)

    // ---------- Loans, stage G2: the payment sheet, Change terms and Edit details (rules in `LoanSheetRules`) ----------

    private val loanRules: LoanSheetRules by lazy { LoanSheetRules(parts, balances) }

    /** The payment sheet for loan [accountId] (0 to read it from [paymentId]): a new payment ([paymentId] 0) or the one recorded ([LoanPaymentSheetData.found] false when it is gone). */
    fun loanPaymentSheet(accountId: Long, paymentId: Long, todayEpochDay: Int, hidden: Boolean): LoanPaymentSheetData =
        shown(hidden) { loanRules.sheet(if (accountId > 0) accountId else loanRules.accountOfPayment(paymentId), paymentId, todayEpochDay) }

    /** One edit of the payment sheet (`LoanSheetRules.apply`): the form as it is after [action] with [text]. */
    fun loanPaymentApply(accountId: Long, paymentId: Long, input: LoanPaymentInput, action: String, text: String): LoanPaymentInput =
        shown(false) { loanRules.apply(accountId, paymentId, input, action, text) }

    /** The figures and words under the payment sheet's fields for [input]; problems are never masked. */
    fun loanPaymentPreview(accountId: Long, paymentId: Long, input: LoanPaymentInput, hidden: Boolean): LoanPaymentPreviewData {
        val problems = shown(false) { loanRules.problems(accountId, paymentId, input) }
        return shown(hidden) { loanRules.preview(accountId, paymentId, input, problems) }
    }

    /** The Change terms page for loan [accountId], starting from the payment that is waiting when it is already due. */
    fun loanChangeSheet(accountId: Long, todayEpochDay: Int): LoanChangeSheetData = shown(false) { loanRules.changeSheet(accountId, todayEpochDay) }

    /** The live result under the Change terms form; problems are never masked. */
    fun loanChangePreview(accountId: Long, input: LoanChangeInput, hidden: Boolean): LoanChangePreviewData {
        val problems = shown(false) { loanRules.changeProblems(accountId, input) }
        return shown(hidden) { loanRules.changePreview(accountId, input, problems) }
    }

    /** The Edit details form for loan [accountId], filled from its terms. */
    fun loanDetailsSheet(accountId: Long): LoanDetailsSheetData = shown(false) { loanRules.detailsSheet(accountId) }

    /** Each field's problem (a `LoanField` name and the sentence) of the Edit details form; empty when it can be saved. */
    fun loanDetailsProblems(accountId: Long, input: LoanDetailsInput): List<FieldProblemData> =
        shown(false) { loanRules.detailsProblems(accountId, input) }

    /** What "Same as interest" on the Edit details Principal category row stands for. */
    fun loanDetailsPrincipalDefault(accountId: Long, input: LoanDetailsInput): String =
        shown(false) { loanRules.detailsPrincipalDefault(accountId, input) }

    // ---------- Investments, batch H: the account page and its sheets (rules in `InvestRules`, figures in `InvestSheetRules`) ----------

    private val investRules: InvestSheetRules by lazy { InvestSheetRules(parts, balances, this::zoneOf) }

    private fun emptyInvestPage(id: Long) = InvestPageData(
        false, id, "", "", 0, "", "", Tone.Ink, "NONE", "", "", Tone.Ink, false, "", "", emptyList(), 0, 0, emptyList(), false, emptyList(),
    )

    /**
     * An investment account's page (`InvestmentBody`): total value (cash plus holdings), the gain on what was put in, the holdings by value,
     * waiting transfers, and every cash move by month with the cash right after it. Hidden masks every figure, drops the percent and the
     * direction of the gain, and turns every tone Ink.
     */
    fun investPage(accountId: Long, hidden: Boolean, todayEpochDay: Int, timeZoneId: String): InvestPageData = shown(hidden) {
        val a = accountById[accountId]?.takeIf { it.type == AccountType.INVESTMENT }
        if (a == null) {
            emptyInvestPage(accountId)
        } else {
            val zone = zoneOf(timeZoneId)
            val cash = balances[a.id] ?: 0L
            val holdings = investRules.holdingsOf(a.id)
            val history = transactions.filter { it.accountId == a.id || it.toAccountId == a.id }
            val s = InvestRules.summary(a, cash, holdings, history)
            val gainKind = when {
                s.putIn <= 0 -> "NONE"
                Money.isHidden -> "MASKED"
                s.gain >= 0 -> "UP"
                else -> "DOWN"
            }
            // The cash right after each entry: walk back from today's.
            var running = cash
            val rows = ArrayList<Pair<Transaction, Long>>()
            for (t in history) {
                rows.add(t to running)
                running -= t.effectOn(a.id)
            }
            val months = ArrayList<InvestMonthData>()
            val grouped = rows.groupBy { (t, _) -> dateOfMillis(t.epochMillis, zone).let { it.year * 100 + it.monthNumber } }
            for ((key, list) in grouped) {
                months.add(InvestMonthData(key / 100, key % 100, list.map { (t, after) -> InvestEntryData(entryRow(t), Money.format(after)) }))
            }
            val oldestDay = if (holdings.isEmpty()) 0 else dateOfMillis(holdings.minOf { it.priceMillis }, zone).toEpochDay().toInt()
            InvestPageData(
                found = true,
                id = a.id,
                name = a.name,
                last4 = a.last4 ?: "",
                closedDay = a.closedDay ?: 0,
                closeProblem = ruleCloseProblem(worth(a)) ?: "",
                totalValueText = Money.format(s.value),
                totalValueTone = figure(s.value < 0),
                gainKind = gainKind,
                gainAmountText = Money.format(abs(s.gain)),
                gainPercentText = if (s.putIn > 0 && !Money.isHidden) InvestRules.percentOf(abs(s.gain), s.putIn) else "",
                gainTone = if (Money.isHidden) Tone.Ink else if (s.gain >= 0) Tone.Green else Tone.Orange,
                firstBoxIsPutIn = s.putIn > 0,
                firstBoxText = Money.format(if (s.putIn > 0) s.putIn else s.holdingsValue),
                cashText = Money.format(cash),
                holdings = holdings.map { investRules.row(it) },
                oldestPriceDay = oldestDay,
                priceDays = if (oldestDay > 0) todayEpochDay - oldestDay else 0,
                waiting = parts.transfers.filter { it.accountId == a.id }.sortedBy { it.epochMillis }.map {
                    WaitingTransferData(it.id, Money.format(it.amount), dateOfMillis(it.epochMillis, zone).toEpochDay().toInt())
                },
                hasHistory = history.isNotEmpty(),
                months = months,
            )
        }
    }

    /** What a holding's sheet opens on: [holdingId] 0 is "Add shares you own". */
    fun holdingSheet(accountId: Long, holdingId: Long, hidden: Boolean): HoldingSheetData = shown(hidden) { investRules.holdingSheet(accountId, holdingId) }

    /** The worth and profit lines of an existing holding's sheet for the price and what-was-paid typed so far. */
    fun holdingPreview(accountId: Long, holdingId: Long, price: String, paid: String, hidden: Boolean): HoldingPreviewData =
        shown(hidden) { investRules.holdingPreview(accountId, holdingId, price, paid) }

    fun addHoldingProblems(ticker: String, shares: String, paid: String): List<FieldProblemData> =
        shown(false) { investRules.addHoldingProblems(ticker, shares, paid) }

    fun holdingProblems(price: String, paid: String): List<FieldProblemData> = shown(false) { investRules.holdingProblems(price, paid) }

    /** Every holding's price in one sheet, by value as the page lists them. */
    fun pricesSheet(accountId: Long): PricesSheetData = shown(false) { investRules.pricesSheet(accountId) }

    /** Each typed price's problem (the field is the holding's id); empty when the prices can be saved. */
    fun pricesProblems(accountId: Long, ids: List<String>, prices: List<String>): List<FieldProblemData> =
        shown(false) { investRules.pricesProblems(accountId, ids, prices) }

    /** A new buy or sale on [accountId]: its cash and the holdings as chips. */
    fun tradeSheet(accountId: Long, hidden: Boolean): TradeSheetData = shown(hidden) { investRules.tradeSheet(accountId) }

    /** "Costs X of Y cash" / "Adds X to cash": the total of the shares and price typed, and the cash. */
    fun tradePreview(accountId: Long, shares: String, price: String, hidden: Boolean): TradePreviewData =
        shown(hidden) { investRules.tradePreview(accountId, shares, price) }

    /** The problems of a trade as typed: each field's, then (for a sale) what is not held. [savedTicker] is an edited entry's ticker, "" for a new trade. */
    fun tradeProblems(accountId: Long, buy: Boolean, ticker: String, savedTicker: String, shares: String, price: String): List<FieldProblemData> =
        shown(false) { investRules.tradeProblems(accountId, buy, ticker, savedTicker, shares, price) }

    /** The buy or sale [entryId] on its sheet (not found for any other entry). */
    fun tradeEditSheet(entryId: Long, hidden: Boolean, timeZoneId: String): TradeEditData = shown(hidden) { investRules.tradeEdit(entryId, timeZoneId) }

    /** The "Where did it go?" sheet of waiting transfer [transferId]. */
    fun allocateSheet(transferId: Long, hidden: Boolean, timeZoneId: String): AllocateSheetData = shown(hidden) { investRules.allocateSheet(transferId, timeZoneId) }

    /** What the shares and price typed on that sheet leave of the money moved. */
    fun allocatePreview(transferId: Long, shares: String, price: String, hidden: Boolean): AllocatePreviewData =
        shown(hidden) { investRules.allocatePreview(transferId, shares, price) }

    // ---------- Due notices, batch I: the Notifications page, Upcoming, bills and the phone's plan (rules in `DueNotices`, `BillRules`) ----------

    internal val dueViews: DueViews by lazy { DueViews(parts, balances, accountById, categoryById) }

    /**
     * Everything waiting on the Notifications page as of [todayEpochDay]: a card payment, a loan payment or a bill from its due day (and
     * while overdue), investment transfers to explain, suggested bills; minus what [cleared] (the keys Clear remembered) names.
     */
    fun notices(hidden: Boolean, todayEpochDay: Int, timeZoneId: String, cleared: List<String>): NoticesData =
        shown(hidden) { dueViews.notices(todayEpochDay, zoneOf(timeZoneId), cleared.toSet()) }

    /** Overview's Upcoming rows: card payments and bills due within [days] days (as Android's `upcoming(ledger, 30)`). */
    fun upcoming(hidden: Boolean, todayEpochDay: Int, timeZoneId: String, days: Int): List<UpcomingData> =
        shown(hidden) { dueViews.upcoming(todayEpochDay, zoneOf(timeZoneId), days) }

    /** Settings > Bills: each bill with its day, account and fixed or estimated amount, by day of the month. */
    fun billsPage(hidden: Boolean, todayEpochDay: Int, timeZoneId: String): BillsData =
        shown(hidden) { dueViews.bills(todayEpochDay, zoneOf(timeZoneId)) }

    /** Bill [billId] as its sheet opens ([found] false when it is gone); a category or account that is gone reads as none. */
    fun billSheet(billId: Long): BillSheetData = shown(false) { dueViews.billSheet(billId) }

    /** Every problem of the bill as typed; empty when it can be saved. */
    fun billProblems(input: BillInput): List<FieldProblemData> = BillBridge.problems(input, currency.code)

    /** Whether a card with a due day or a loan with terms exists: the moment Kassa offers phone notifications. */
    fun hasNotifiable(): Boolean = dueViews.hasNotifiable()

    /**
     * The phone notifications to schedule from [nowMillis] on (at most 60, within 60 days): see `DueNotices.plan`. [posted] are the keys
     * already rung, [cleared] the keys Clear remembered; [title] and [body] are the one generic text every notice carries.
     */
    fun notificationPlan(
        rules: DueRulesInput, nowMillis: Long, timeZoneId: String, posted: List<String>, cleared: List<String>, title: String, body: String,
    ): List<app.kassa.money.domain.PlannedNotice> =
        dueViews.plan(rules, nowMillis, zoneOf(timeZoneId), posted.toSet(), cleared.toSet(), title, body)

    // ---------- Batch J: Patterns, week, spending, day, store, budgets, Activity search (see ReadPages.kt) ----------

    private val readViews: ReadViews by lazy { ReadViews(this) }

    /** Insights' six Patterns tiles as of [todayEpochDay]. */
    fun patterns(hidden: Boolean, todayEpochDay: Int, timeZoneId: String): PatternsData =
        shown(hidden) { readViews.patterns(localDateOfEpochDay(todayEpochDay), zoneOf(timeZoneId)) }

    /** Insights' week view. [firstWeekday] is the phone's `Calendar.firstWeekday` (1 Sunday ... 7 Saturday), as Android's `Ledger.firstDayOfWeek`. */
    fun weekView(hidden: Boolean, todayEpochDay: Int, timeZoneId: String, firstWeekday: Int): WeekData =
        shown(hidden) { readViews.weekView(localDateOfEpochDay(todayEpochDay), zoneOf(timeZoneId), WeekRules.firstDayOf(firstWeekday)) }

    /** The spending (or income) page of the month or week [back] periods before the current one. */
    fun spendingPage(hidden: Boolean, todayEpochDay: Int, timeZoneId: String, firstWeekday: Int, month: Boolean, income: Boolean, back: Int): SpendingPageData =
        shown(hidden) {
            readViews.spendingPage(localDateOfEpochDay(todayEpochDay), zoneOf(timeZoneId), WeekRules.firstDayOf(firstWeekday), month, income, back)
        }

    /** The page behind a Patterns or week tile; [kind] is BIG_MONTH, BIG_WEEK, STORES, SMALL, CREEP, REPEATING, WEEKDAYS or DUES. */
    fun insightDetail(kind: String, hidden: Boolean, todayEpochDay: Int, timeZoneId: String, firstWeekday: Int): InsightDetailData =
        shown(hidden) { readViews.detail(kind, localDateOfEpochDay(todayEpochDay), zoneOf(timeZoneId), WeekRules.firstDayOf(firstWeekday)) }

    /** One day: what was spent and every entry that day (any day, from the whole history). */
    fun dayPage(epochDay: Int, hidden: Boolean, timeZoneId: String): DayPageData =
        shown(hidden) { readViews.dayPage(localDateOfEpochDay(epochDay), zoneOf(timeZoneId)) }

    /** Every entry at one store in the last months, newest first, and this month's total. */
    fun storePage(name: String, hidden: Boolean, todayEpochDay: Int, timeZoneId: String): StorePageData =
        shown(hidden) { readViews.storePage(name, localDateOfEpochDay(todayEpochDay), zoneOf(timeZoneId)) }

    /** Settings > Budgets: every top-level expense category against its budget in the current budget month. */
    fun budgetsPage(hidden: Boolean, todayEpochDay: Int, timeZoneId: String): BudgetsData =
        shown(hidden) { readViews.budgetsPage(localDateOfEpochDay(todayEpochDay), zoneOf(timeZoneId)) }

    /** The budget editor of [categoryId]: its stored amounts as typed text. */
    fun budgetEdit(categoryId: Long, todayEpochDay: Int): BudgetEditData =
        shown(false) { readViews.budgetEdit(categoryId, localDateOfEpochDay(todayEpochDay)) }

    /** What saving the editor with these typed amounts does: the problems, or the writes to run in one transaction. */
    fun budgetPlan(categoryId: Long, every: String, thisMonth: String, todayEpochDay: Int): BudgetPlanData =
        shown(false) { readViews.budgetPlan(categoryId, every, thisMonth, localDateOfEpochDay(todayEpochDay)) }

    /** [activity] with the Spending or Income total as a filter ([filter] is ALL, EXPENSE or INCOME): the totals stay, the days keep only those entries. */
    fun activityFiltered(year: Int, month: Int, filter: String, hidden: Boolean, todayEpochDay: Int, timeZoneId: String): ActivityData =
        shown(hidden) { readViews.activity(year, month, EntryFilter.named(filter), localDateOfEpochDay(todayEpochDay), zoneOf(timeZoneId)) }

    /** Activity's search over every month: note, category, account, person or the exact amount; what is typed is text, never a pattern. */
    fun activitySearch(query: String, hidden: Boolean, timeZoneId: String): SearchData =
        shown(hidden) { readViews.search(query, zoneOf(timeZoneId)) }

    // ---------- Money state and colour ----------

    /** Runs [block] with Money showing this ledger's currency and [hidden] as Hide balances, then puts both back. */
    internal fun <T> shown(hidden: Boolean, block: () -> T): T {
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

    internal fun zoneOf(id: String): TimeZone = try {
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
    internal fun figure(against: Boolean): Tone = if (Money.isHidden || !against) Tone.Ink else Tone.Orange

    /** `figureColor(positive)`: Green, Ink while hidden. */
    internal fun figurePositive(): Tone = if (Money.isHidden) Tone.Ink else Tone.Green

    companion object {
        const val PEOPLE = "PEOPLE"
        /** At most this many tiles on Overview, as Android's `MAX_PINS`. */
        const val MAX_PINS = 8
        const val CLOSED = "CLOSED"
        /** Archived accounts and archived people: hidden from every figure, listed (folded) so they can be restored. */
        const val ARCHIVED = "ARCHIVED"
        const val ARCHIVED_PEOPLE = "ARCHIVED_PEOPLE"
        /** How many schedule rows the loan page shows before "Show full schedule", as Android's `NEXT_ROWS`. */
        const val LOAN_NEXT_ROWS = 6
    }
}
