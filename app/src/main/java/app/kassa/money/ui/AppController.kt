package app.kassa.money.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import app.kassa.money.data.Settings
import app.kassa.money.data.backup.BackupContents
import app.kassa.money.data.jobs.DailyJobs
import app.kassa.money.data.repo.EntryDraft
import app.kassa.money.data.repo.LoanPaymentDetail
import app.kassa.money.domain.Account
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.DueKind
import app.kassa.money.domain.InvestmentTransfer
import app.kassa.money.domain.Recurring
import app.kassa.money.domain.Repeat
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.ui.entry.EntryPreset
import app.kassa.money.ui.entry.EntryStart
import app.kassa.money.ui.insights.InsightActions
import app.kassa.money.ui.insights.InsightPage
import app.kassa.money.ui.settings.SettingsActions
import app.kassa.money.ui.today.Notice
import app.kassa.money.ui.today.NoticeActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/** The phone's settings, for any screen: `LocalSettings.current.theme.collectAsState()`. */
val LocalSettings = staticCompositionLocalOf<Settings> { error("LocalSettings is provided by MainActivity") }

/** A balance correction being made on [accountId], or [editing] an earlier one. */
data class Correcting(val accountId: Long, val editing: Transaction? = null)

/** What a tapped payment-due notification says when the card, loan or bill it was about no longer exists. */
const val PAYMENT_GONE = "That payment is no longer there."

/** The value of [NavViewModel.settingsRequest] that opens Settings > Backup and restore. */
const val SETTINGS_BACKUP = "backup"

/** Where a payment-due notification was tapped to go: a card, a loan or a bill by id. */
data class DueTarget(val kind: DueKind, val id: Long)

/**
 * Where screens go and what their buttons do. Screens call this instead of the view model and navigation
 * directly, so "open a card" or "edit an entry" means the same thing everywhere.
 */
class AppController(
    val vm: LedgerViewModel,
    val nav: NavViewModel,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    /** True when pages open beside the list they came from (unfolded, 720dp and wider). */
    var split by mutableStateOf(false)

    /** The card whose pay sheet is open, if any. */
    var paying: Long? get() = nav.paying; set(v) { nav.paying = v }

    /** The loan whose payment sheet is open to record a new payment, if any. */
    var payingLoan: Long? get() = nav.payingLoan; set(v) { nav.payingLoan = v }

    /** The loan account that terms are being added to, if any. */
    var addingTermsTo: Long? get() = nav.addingTermsTo; set(v) { nav.addingTermsTo = v }

    /** The loan whose details (name, due day, escrow and so on) are being edited, if any. */
    var editingLoanDetails: Long? get() = nav.editingLoanDetails; set(v) { nav.editingLoanDetails = v }

    /** The balance correction sheet that is open, if any: new for an account, or an earlier correction being edited. */
    var correcting: Correcting? get() = nav.correcting; set(v) { nav.correcting = v }

    /** The recorded loan payment whose sheet is open, if any. Any of its entries opens it. */
    var editingLoanPayment: LoanPaymentDetail? get() = nav.editingLoanPayment; set(v) { nav.editingLoanPayment = v }

    /** The investment transfer being explained, if any. */
    var allocating: InvestmentTransfer? get() = nav.allocating; set(v) { nav.allocating = v }

    /**
     * A tapped payment-due notification: the card's page, the loan's page with its payment sheet open (when something
     * is owed), or the bill. Opening pays and adds nothing. When what it names is gone (the card or loan was deleted, the
     * bill removed), the tab it belongs to opens and a message says so, rather than nothing happening.
     */
    fun openDue(target: DueTarget) {
        val l = vm.ledger.value ?: return
        val account = l.accountById[target.id]
        when (target.kind) {
            DueKind.CARD -> if (account != null && account.type.hasStatement) openFromList(Page.CardDetail(target.id)) else dueIsGone(Tab.ACCOUNTS)
            DueKind.LOAN -> if (account != null) {
                openFromList(Page.AccountDetail(target.id))
                if (l.loans.terms[target.id] != null && (l.accounts.balances[target.id] ?: 0L) < 0) payingLoan = target.id
            } else dueIsGone(Tab.ACCOUNTS)
            DueKind.BILL -> l.bills.firstOrNull { it.id == target.id }?.let { openFromList(Page.Bill(it)) } ?: dueIsGone(Tab.TODAY)
        }
    }

    /** What a tapped notification named is no longer there: show the tab it would have been on, and say so. */
    private fun dueIsGone(tab: Tab) {
        nav.select(tab)
        vm.say(PAYMENT_GONE)
    }

    /** The Settings > Backup page, from the "Backup needs attention" notification. */
    fun openBackup() {
        nav.select(Tab.SETTINGS)
        nav.settingsRequest = SETTINGS_BACKUP
    }

    /** From a list: beside it when unfolded, over it on the cover screen. */
    fun openFromList(page: Page) = nav.open(page, besideList = split)

    /** From a page: on top of it. */
    fun push(page: Page) = nav.push(page)

    fun back() = nav.pop()

    /** Add, pressed on [from] (null for the tabs). A card, account or category page starts the entry with it picked. */
    fun newEntry(from: Page? = null) = openFromList(Page.Entry(EntryStart(preset = from?.let(::presetFor))))

    /** What Add starts with on [page]; null when that page doesn't offer Add. */
    fun presetFor(page: Page): EntryPreset? {
        val l = vm.ledger.value ?: return null
        return when (page) {
            is Page.CardDetail -> l.accountById[page.accountId]?.let { EntryPreset(accountId = it.id) }
            is Page.AccountDetail -> l.accountById[page.accountId]?.let { EntryPreset(accountId = it.id) }
            is Page.CategoryDetail -> l.categoryById[page.categoryId]?.let { c ->
                EntryPreset(kind = if (c.kind == CategoryKind.INCOME) TxKind.INCOME else TxKind.EXPENSE, categoryId = c.id)
            }
            else -> null
        }
    }

    /** The buy or sale whose sheet is open, if any. */
    var editingTrade: Transaction? get() = nav.editingTrade; set(v) { nav.editingTrade = v }

    /** The payment to or from a person whose sheet is open, if any. */
    var editingPayment: Transaction? get() = nav.editingPayment; set(v) { nav.editingPayment = v }

    /**
     * Opens an entry where it's edited: expenses, income and transfers on the entry page (its split loaded;
     * [withPerson] adds them to the split, as "Split a transaction" does), trades, person payments and loan payments in
     * their own sheets, balance corrections in the correction sheet. The entry page only knows its three kinds, so nothing else may reach it: an entry that is part
     * of a loan payment (its principal, interest or escrow) opens the loan payment sheet, whichever kind it is.
     */
    fun openEntry(id: Long, fromPage: Boolean, withPerson: Long? = null) {
        scope.launch {
            vm.loanPaymentForEntry(id)?.let { link ->
                vm.loanPaymentDetail(link.id)?.let { editingLoanPayment = it }
                return@launch
            }
            val t = vm.entry(id) ?: return@launch
            when (t.kind) {
                TxKind.BUY, TxKind.SELL -> editingTrade = t
                TxKind.PERSON_IN, TxKind.PERSON_OUT -> editingPayment = t
                TxKind.ADJUST_IN, TxKind.ADJUST_OUT -> t.accountId?.let { correcting = Correcting(it, t) }
                else -> {
                    val start = vm.entryStartFor(id, withPerson) ?: return@launch
                    if (fromPage) push(Page.Entry(start)) else openFromList(Page.Entry(start))
                }
            }
        }
    }

    val editFromList: (Transaction) -> Unit = { openEntry(it.id, fromPage = false) }
    val editFromPage: (Transaction) -> Unit = { openEntry(it.id, fromPage = true) }

    /** The Notifications page. Each choice is the user's; nothing posts by itself. */
    val notices = object : NoticeActions {
        override fun addBill(n: Notice.Bill, amount: Long) { vm.approveBill(n.bill, amount, n.due) }
        override fun editBill(n: Notice.Bill, amount: Long) {
            val l = vm.ledger.value ?: return
            val r = n.bill
            val draft = EntryDraft(
                id = null, kind = TxKind.EXPENSE, amount = amount, accountId = r.accountId, toAccountId = null, categoryId = r.categoryId,
                personId = null, epochMillis = minOf(n.due, l.today).atTime(9, 0).atZone(l.zone).toInstant().toEpochMilli(), note = r.name,
            )
            openFromList(Page.Entry(EntryStart(prefill = draft, afterSave = { vm.billHandled(r, n.due) })))
        }
        override fun payCard(accountId: Long) { paying = accountId }
        override fun payLoan(accountId: Long) { payingLoan = accountId }
        override fun allocate(t: InvestmentTransfer) { allocating = t }
        override fun trackAsBill(r: Repeat) {
            // The account is left for the user to pick: nothing guesses an account.
            push(Page.Bill(Recurring(name = r.name, categoryId = r.categoryId ?: 0, accountId = 0, dayOfMonth = r.dayOfMonth, fixedAmount = r.typical)))
        }
        override fun clear(notices: List<Notice>) { vm.clear(notices) }
    }

    val insights = object : InsightActions {
        override fun openCategory(category: Category) = openFromList(Page.CategoryDetail(category.id))
        override fun open(page: InsightPage) = openFromList(Page.Insight(page))
        override fun edit(t: Transaction) = editFromList(t)
        override fun openDay(date: LocalDate) = openFromList(Page.Day(date.toEpochDay()))
        override fun openSpending(month: Boolean) = openFromList(Page.Spending(month))
    }

    val settings = object : SettingsActions {
        override fun saveCategory(category: Category, budget: Long?, quiet: Boolean, then: (Long) -> Unit) { vm.saveCategory(category, budget, quiet, then) }
        override fun setMonthBudget(categoryId: Long, amount: Long?) {
            val l = vm.ledger.value ?: return
            vm.setMonthBudget(categoryId, l.period.yearMonth, amount)
        }
        override fun mergeCategory(from: Category, into: Category) { vm.mergeCategory(from, into) }
        override fun convertToPerson(account: Account, balance: Long, name: String) { vm.convertToPerson(account, balance, name) }
        override fun importAgain() = push(Page.Import)
        override fun openBill(r: Recurring?) = push(Page.Bill(r))
        override suspend fun exportTo(uri: Uri, password: CharArray?): String = vm.exportTo(uri, password)
        override suspend fun readBackup(uri: Uri, password: CharArray?): Result<BackupContents> = vm.readBackup(uri, password)
        override suspend fun backupNow(): String = withContext(Dispatchers.IO) { DailyJobs.backupNow(context) }
        override fun refreshDailyJobs() = DailyJobs.refresh(context)
        override suspend fun restore(contents: BackupContents): String = vm.restore(contents).await()
        override val wantsBackupPage: Boolean get() = nav.settingsRequest == SETTINGS_BACKUP
        override fun backupPageOpened() { if (nav.settingsRequest == SETTINGS_BACKUP) nav.settingsRequest = null }
        override fun loadDemo() { vm.loadDemo() }
        override fun openPins() = openFromList(Page.PinAccounts)
    }
}
