package app.kassa.money.ui

import android.app.Application
import android.net.Uri
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.kassa.money.KassaApplication
import app.kassa.money.R
import app.kassa.money.data.Failures
import app.kassa.money.data.Failures.Doing
import app.kassa.money.data.backup.BackupContents
import app.kassa.money.data.db.toEntity
import app.kassa.money.data.jobs.DueNotifier
import app.kassa.money.data.repo.EntryDraft
import app.kassa.money.data.repo.LoanPaymentDetail
import app.kassa.money.data.repo.LoanPaymentDraft
import app.kassa.money.data.repo.LoansState
import app.kassa.money.data.repo.PeopleState
import app.kassa.money.domain.Trade
import app.kassa.money.domain.InvestmentTransfer
import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Budgets
import app.kassa.money.domain.Category
import app.kassa.money.domain.CountedSpending
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.Holding
import app.kassa.money.domain.LoanNotes
import app.kassa.money.domain.LoanPayment
import app.kassa.money.domain.LoanTermChange
import app.kassa.money.domain.LoanTerms
import app.kassa.money.domain.Money
import app.kassa.money.domain.QueryWindows
import app.kassa.money.domain.Person
import app.kassa.money.domain.Recurring
import app.kassa.money.domain.Repeat
import app.kassa.money.domain.Repeats
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.ui.entry.EntryStart
import app.kassa.money.ui.today.Notice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.FileNotFoundException
import java.time.LocalDate
import java.time.YearMonth

/**
 * The main screens' state ([ledger]) and every change they make. Each change runs in the background and reports
 * through [messages]: what happened, or why it couldn't be saved (an input rule the repository enforced).
 */
/** Bills and investing rows, gathered before they go into the [Ledger]. */
/** Entries and what's derived from them, gathered before they go into the [Ledger]. */
private data class Quad(val people: PeopleState, val recent: List<Transaction>, val repeats: List<Repeat>, val cleared: Set<String>)

private data class Investing(
    val bills: List<Recurring>, val holdings: List<Holding>, val transfers: List<InvestmentTransfer>, val trades: List<Trade>, val loans: LoansState,
)

@OptIn(ExperimentalCoroutinesApi::class)
class LedgerViewModel(app: Application) : AndroidViewModel(app) {
    private val kassa = app as KassaApplication
    private val settings = kassa.settings

    private fun s(@StringRes id: Int, vararg args: Any): String = getApplication<Application>().getString(id, *args)

    private fun pluralS(@PluralsRes id: Int, n: Int): String = getApplication<Application>().resources.getQuantityString(id, n, n)

    /** Today and the phone's time zone; they move on at midnight and when the zone changes, and everything below follows. */
    private val clock = kassa.clock.day

    /** The last [QueryWindows.RECENT_DAYS] days of entries, re-read from the new window when the date or zone changes. */
    private val recent = clock.flatMapLatest { d ->
        kassa.entries.between(QueryWindows.recentFrom(d.today, d.zone), Long.MAX_VALUE)
    }

    /** What repeats, found in the last [QueryWindows.HISTORY_DAYS] days: long enough for a yearly charge to show up twice. */
    private val repeats = clock.flatMapLatest { d ->
        kassa.entries.between(QueryWindows.historyFrom(d.today, d.zone), Long.MAX_VALUE)
            .map { Repeats.find(it, d.today, d.zone) }.flowOn(Dispatchers.Default)
    }

    val ledger: StateFlow<Ledger?> = combine(
        combine(kassa.categories.categories, kassa.accounts.state, kassa.categories.budgets) { c, a, b -> Triple(c, a, b) },
        combine(kassa.people.state, recent, repeats, settings.cleared) { p, r, rep, cl -> Quad(p, r, rep, cl) },
        combine(kassa.bills.bills, kassa.investments.holdings, kassa.investments.transfers, kassa.investments.trades, kassa.loans.state) { b, h, t, tr, ln ->
            Investing(b, h, t, tr, ln)
        },
        combine(settings.monthStartDay, clock) { m, d -> m to d },
    ) { (categories, accounts, budgets), (people, entries, rep, cleared), inv, (monthStart, day) ->
        // A loan's steady escrow or interest is already tracked by the loan, so it isn't suggested as a bill.
        val loanNotes = inv.loans.terms.keys.mapNotNull { id -> accounts.accounts.firstOrNull { it.id == id }?.name }.flatMap { LoanNotes.repeatKeys(it) }.toSet()
        Ledger(
            categories, accounts, budgets, people, entries, inv.bills, inv.holdings, inv.transfers,
            inv.trades.associateBy { it.transactionId }, rep.filterNot { it.key in loanNotes }, cleared, inv.loans, monthStart, day.today, day.zone,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val _askNotifications = MutableStateFlow(false)
    /** True when Kassa should explain why it wants to notify and then ask Android for permission (once, in context). */
    val askNotifications: StateFlow<Boolean> = _askNotifications

    /** The explanation was answered, either way: never ask again. */
    fun askedNotifications() { settings.notifyAsked = true; _askNotifications.value = false }

    /** After saving a card with a due day or a loan with terms: offers the permission once, and only where Android asks for one. */
    private fun offerNotifications() {
        if (settings.notifyAsked || android.os.Build.VERSION.SDK_INT < 33) return
        val nm = kassa.getSystemService(android.app.NotificationManager::class.java)
        if (nm == null || nm.areNotificationsEnabled()) return
        _askNotifications.value = true
    }

    private fun hasDueDay(a: Account) = a.type.hasStatement && a.statementDay != null && a.dueDay != null

    /** Runs [block] in the background; on success shows [done] (if any) and calls [onDone], otherwise shows why. */
    /** A short message for the person, from a screen that found the problem itself (a check before a confirm). */
    fun say(message: String) { _messages.tryEmit(message) }

    private fun change(done: String? = null, onDone: () -> Unit = {}, block: suspend () -> Unit) = viewModelScope.launch {
        try {
            block()
            // Cards, loans, bills and payments may have moved: the payments-due alarm follows.
            DueNotifier.refresh(kassa)
            done?.let(_messages::tryEmit)
            onDone()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // An input rule the repository enforced ("Pick a category.") is written for the person and shown as it is; anything
            // else is a plain line, never exception text.
            Log.w(TAG, "A change couldn't be saved (${Failures.code(e)})")
            _messages.tryEmit(Failures.message(e, Doing.SAVE))
        }
    }

    /* ---------- entries ---------- */

    fun save(draft: EntryDraft, afterSave: (() -> Unit)? = null, onDone: () -> Unit = {}) = change(onDone = onDone) {
        val saved = kassa.entries.save(draft)
        afterSave?.invoke()
        _messages.tryEmit(savedMessage(draft, saved))
    }

    fun delete(id: Long) = change(s(R.string.shell_entry_deleted)) { kassa.entries.delete(id) }

    suspend fun notes(kind: TxKind): List<String> = kassa.entries.frequentNotes(kind)

    fun between(fromMillis: Long, toMillis: Long): Flow<List<Transaction>> = kassa.entries.between(fromMillis, toMillis)

    /** [between] as spending sees the entries: a counted loan principal is an expense in its category (see `CountedSpending`). */
    fun countedBetween(fromMillis: Long, toMillis: Long): Flow<List<Transaction>> =
        combine(between(fromMillis, toMillis), kassa.loans.state, kassa.categories.categories) { list, loans, cats ->
            CountedSpending.apply(list, loans.payments, loans.terms, cats)
        }

    fun accountHistory(accountId: Long): Flow<List<Transaction>> = kassa.entries.forAccount(accountId)

    /**
     * How the entry page opens an existing entry, split included. [withPerson] adds someone to the split and opens
     * the split panel, for "Split a transaction" from a person.
     */
    suspend fun entry(id: Long): Transaction? = kassa.entries.get(id)

    suspend fun entryStartFor(id: Long, withPerson: Long? = null): EntryStart? {
        val t = kassa.entries.get(id) ?: return null
        val paidBy = if (t.kind == TxKind.EXPENSE && t.accountId == null) t.personId else null
        val shares = if (paidBy != null) mapOf(paidBy to (t.amount - (t.myShare ?: t.amount)).coerceAtLeast(0))
        else kassa.entries.sharesFor(id).filterValues { it > 0 }
        val people = (shares.keys + listOfNotNull(withPerson)).distinct()
        return EntryStart(editing = t, splitWith = people, shares = shares, paidBy = paidBy, openSplit = withPerson != null)
    }

    /** What to say after a save: a transfer into investments asks to be explained; an expense shows its category. */
    private suspend fun savedMessage(draft: EntryDraft, saved: Transaction): String {
        val l = ledger.value
        val to = saved.toAccountId?.let { l?.accountById?.get(it) }
        if (draft.id == null && saved.kind == TxKind.TRANSFER && to?.type == AccountType.INVESTMENT) {
            kassa.investments.awaitExplanation(saved)
            return s(R.string.shell_moved_to_investment, Money.format(saved.amount), to.name)
        }
        val text = when (saved.kind) {
            // The ledger updates a moment later, so add this entry to the numbers now.
            TxKind.EXPENSE -> l?.copy(recent = l.recent.filter { it.id != saved.id } + saved)?.spendingLine(getApplication<Application>().resources, saved.categoryId) ?: s(R.string.shell_expense_saved)
            TxKind.INCOME -> s(R.string.shell_income_added, Money.format(saved.amount), l?.accountById?.get(saved.accountId)?.name ?: s(R.string.shell_the_account))
            TxKind.TRANSFER -> if (to?.type?.hasStatement == true) s(R.string.shell_paid_to, Money.format(saved.amount), to.name) else s(R.string.shell_moved_to, Money.format(saved.amount), to?.name ?: s(R.string.shell_the_account))
            TxKind.PERSON_IN, TxKind.PERSON_OUT -> s(R.string.shell_payment_recorded)
            TxKind.BUY, TxKind.SELL -> s(R.string.shell_saved)
            TxKind.ADJUST_IN, TxKind.ADJUST_OUT -> s(R.string.shell_balance_corrected)
        }
        return if (draft.id != null) s(R.string.shell_saved_then, text) else text
    }

    /* ---------- accounts and people ---------- */

    fun addAccount(account: Account, newGroupName: String?, onDone: () -> Unit = {}) =
        change(s(R.string.shell_added, account.name.trim()), { onDone(); if (hasDueDay(account)) offerNotifications() }) { kassa.accounts.add(account, newGroupName) }

    fun updateAccount(account: Account, newGroupName: String?, onDone: () -> Unit = {}) =
        change(s(R.string.shell_saved_named, account.name.trim()), { onDone(); if (hasDueDay(account)) offerNotifications() }) { kassa.accounts.update(account, newGroupName) }

    fun setArchived(account: Account, archived: Boolean) =
        change(if (archived) s(R.string.shell_archived, account.name) else s(R.string.shell_restored, account.name)) { kassa.accounts.update(account.copy(archived = archived)) }

    /** Closes an account (its history stays) or reopens it. The balance check is the repository's; a problem shows as a message. */
    fun setClosed(account: Account, closedDay: Int?) =
        change(if (closedDay != null) s(R.string.shell_closed, account.name) else s(R.string.shell_reopened, account.name)) { kassa.accounts.setClosed(account.id, closedDay) }

    fun addPerson(name: String, opening: Long, onDone: () -> Unit = {}) = change(s(R.string.shell_added, name.trim()), onDone) { kassa.people.add(name, opening) }

    fun updatePerson(p: Person, startingBalance: Long? = null, onDone: () -> Unit = {}) =
        change(s(R.string.shell_saved_named, p.name.trim()), onDone) { kassa.people.update(p, startingBalance) }

    fun convertToPerson(account: Account, balance: Long, name: String) =
        change(s(R.string.shell_account_now_person, account.name, name.trim())) { kassa.people.fromAccount(account, balance, name) }

    /* ---------- loans ---------- */

    /** Adds a new loan account with its terms; [onDone] runs once it is saved, so a sheet stays open on a problem. */
    fun addLoan(account: Account, terms: LoanTerms, newGroupName: String?, onDone: () -> Unit = {}) =
        change(s(R.string.shell_added, account.name.trim()), { onDone(); offerNotifications() }) { kassa.loans.addLoan(account, terms, newGroupName) }

    /** Adds terms to an existing loan account, keeping its entries. */
    fun addLoanTerms(account: Account, terms: LoanTerms, onDone: () -> Unit = {}) =
        change(s(R.string.shell_terms_added, account.name.trim()), { onDone(); offerNotifications() }) { kassa.loans.addTerms(account, terms) }

    /** Records a loan payment (or saves an edit of one) as its linked entries. */
    fun recordLoanPayment(draft: LoanPaymentDraft, onDone: () -> Unit = {}) =
        change(if (draft.id == null) s(R.string.shell_payment_recorded) else s(R.string.shell_payment_saved), onDone) { kassa.loans.recordPayment(draft) }

    fun deleteLoanPayment(id: Long, onDone: () -> Unit = {}) = change(s(R.string.shell_payment_deleted), onDone) { kassa.loans.deletePayment(id) }

    /** Saves a loan's undated details; the account carries its name. */
    fun updateLoanDetails(account: Account, terms: LoanTerms, onDone: () -> Unit = {}) =
        change(s(R.string.shell_details_saved), onDone) { kassa.loans.updateDetails(account, terms) }

    /** Saves a change of terms from a date on; earlier payments keep their split. */
    fun addLoanChange(termChange: LoanTermChange, onDone: () -> Unit = {}) = change(s(R.string.shell_change_saved), onDone) { kassa.loans.addChange(termChange) }

    fun deleteLoanChange(id: Long) = change(s(R.string.shell_change_removed)) { kassa.loans.deleteChange(id) }

    /** Makes [accountId] show [target] by saving a dated correction, or replaces the earlier one [editingId]. */
    fun correctBalance(accountId: Long, target: Long, epochMillis: Long, editingId: Long?, onDone: () -> Unit = {}) =
        change(if (editingId == null) s(R.string.shell_balance_corrected) else s(R.string.shell_correction_saved), onDone) { kassa.entries.correctBalance(accountId, target, epochMillis, editingId) }

    /** Deletes a balance correction: the balance goes back to what it was without it. */
    fun deleteCorrection(id: Long, onDone: () -> Unit = {}) = change(s(R.string.shell_correction_deleted), onDone) { kassa.entries.delete(id) }

    /** The loan payment an entry belongs to, if any: such an entry opens the payment sheet, not the entry page. */
    suspend fun loanPaymentForEntry(entryId: Long): LoanPayment? = kassa.loans.paymentForEntry(entryId)

    suspend fun loanPaymentDetail(paymentId: Long): LoanPaymentDetail? = kassa.loans.detail(paymentId)

    /** First run's "Start fresh": the starter categories picked and, unless skipped, a first account. */
    fun startFresh(categories: List<Category>, account: Account?) = change {
        categories.forEach { kassa.categories.save(it) }
        account?.let { kassa.accounts.add(it, null) }
    }

    /* ---------- categories and budgets ---------- */

    /** Saves a category, and its default budget when it's a top-level expense category. [quiet] skips the message (reordering). */
    fun saveCategory(category: Category, budget: Long?, quiet: Boolean = false, then: (Long) -> Unit = {}) =
        change(if (quiet) null else s(R.string.shell_saved_named, category.name.trim())) {
            val id = kassa.categories.save(category)
            if (category.parentId == null && category.kind == CategoryKind.EXPENSE) kassa.categories.setBudget(id, 0, budget)
            then(id)
        }

    fun setMonthBudget(categoryId: Long, month: YearMonth, amount: Long?) = change { kassa.categories.setBudget(categoryId, Budgets.key(month), amount) }

    fun mergeCategory(from: Category, into: Category) = change(s(R.string.shell_category_merged, from.name, into.name)) { kassa.categories.merge(from, into.id) }

    /* ---------- bills ---------- */

    fun saveBill(bill: Recurring, onDone: () -> Unit = {}) = change(s(R.string.shell_saved_named, bill.name.trim()), onDone) { kassa.bills.save(bill) }

    fun deleteBill(id: Long) = change { kassa.bills.delete(id) }

    /**
     * Records the bill due on [due] for [amount] and marks that month handled. Paid ahead of its day, it's dated
     * today, when the money actually moved.
     */
    fun approveBill(bill: Recurring, amount: Long, due: LocalDate) = change(s(R.string.shell_bill_added, bill.name, Money.format(amount))) {
        val now = clock.value
        val day = minOf(due, now.today)
        val draft = EntryDraft(
            id = null, kind = TxKind.EXPENSE, amount = amount, accountId = bill.accountId, toAccountId = null,
            categoryId = bill.categoryId, personId = null, epochMillis = day.atTime(9, 0).atZone(now.zone).toInstant().toEpochMilli(), note = bill.name,
        )
        // The entry and "handled" are one change: a failure of either leaves neither, and the bill still asks.
        kassa.bills.approve(bill, draft, Budgets.key(YearMonth.from(due)))
    }

    /** After the bill due on [due] was entered by hand from its notification. */
    fun billHandled(bill: Recurring, due: LocalDate) = change { kassa.bills.markHandled(bill, Budgets.key(YearMonth.from(due))) }

    /**
     * Clears notifications without adding or paying anything: a bill skips that month, a transfer stops asking,
     * and a card or loan payment or a suggested bill is hidden by its key.
     */
    fun clear(notices: List<Notice>) = change(pluralS(R.plurals.shell_cleared, notices.size)) {
        notices.forEach { n ->
            when (n) {
                is Notice.Bill -> kassa.bills.markHandled(n.bill, Budgets.key(YearMonth.from(n.due)))
                is Notice.Transfer -> kassa.investments.settle(n.transfer.id)
                is Notice.Card, is Notice.Loan, is Notice.Suggestion -> Unit
            }
        }
        settings.clear(notices.filter { it is Notice.Card || it is Notice.Loan || it is Notice.Suggestion }.map { it.key })
    }

    /* ---------- investments ---------- */

    fun keepTransferAsCash(transferId: Long) = change(s(R.string.shell_kept_as_cash)) { kassa.investments.settle(transferId) }

    /** Clears a transfer the user already accounted for (or no longer wants asked about). */
    fun clearTransfer(transferId: Long) = change { kassa.investments.settle(transferId) }

    fun buy(accountId: Long, ticker: String, microShares: Long, price: Long, at: Long, transferId: Long?, onDone: () -> Unit = {}) =
        change(s(R.string.shell_added_to_holdings, ticker.trim().uppercase()), onDone) { kassa.investments.buy(accountId, ticker, microShares, price, at, transferId) }

    fun sell(accountId: Long, ticker: String, microShares: Long, price: Long, at: Long, onDone: () -> Unit = {}) =
        change(s(R.string.shell_sold, ticker.trim().uppercase()), onDone) { kassa.investments.sell(accountId, ticker, microShares, price, at) }

    fun editTrade(entry: Transaction, ticker: String, microShares: Long, price: Long, at: Long, onDone: () -> Unit) =
        change(s(R.string.shell_trade_saved), onDone) { kassa.investments.editTrade(entry, ticker, microShares, price, at) }

    fun moveTrade(entry: Transaction, at: Long, onDone: () -> Unit) = change(s(R.string.shell_date_changed), onDone) { kassa.investments.moveTrade(entry, at) }

    fun deleteTrade(entry: Transaction, onDone: () -> Unit) = change(s(R.string.shell_trade_deleted), onDone) { kassa.investments.deleteTrade(entry) }

    fun saveHolding(h: Holding) = change { kassa.investments.save(h) }

    fun deleteHolding(id: Long) = change { kassa.investments.delete(id) }

    /* ---------- settings ---------- */

    val lockEnabled = settings.lockEnabled
    val theme = settings.theme
    val currency = settings.currency
    fun setLock(on: Boolean) = settings.setLock(on)
    fun setTheme(v: String) = settings.setTheme(v)
    fun setCurrency(code: String) = settings.setCurrency(code)
    fun setMonthStartDay(day: Int) = settings.setMonthStartDay(day)

    /* ---------- backups ---------- */

    /** Saves a backup to [uri]; returns the message to show. */
    suspend fun exportTo(uri: Uri, password: CharArray?): String = withContext(Dispatchers.IO) {
        try {
            val out = getApplication<Application>().contentResolver.openOutputStream(uri) ?: throw FileNotFoundException("No output stream")
            out.use { kassa.backups.write(it, password) }
            if (password == null || password.isEmpty()) s(R.string.shell_backup_saved) else s(R.string.shell_copy_saved)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "A backup couldn't be saved (${Failures.code(e)})")
            Failures.message(e, Doing.WRITE_BACKUP)
        }
    }

    /** Reads a backup file without changing anything; a failure carries a message that is safe to show (see [Failures.message]). */
    suspend fun readBackup(uri: Uri, password: CharArray?): Result<BackupContents> = withContext(Dispatchers.IO) {
        try {
            val input = getApplication<Application>().contentResolver.openInputStream(uri) ?: throw FileNotFoundException("No input stream")
            Result.success(input.use { kassa.backups.read(it, password) })
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.w(TAG, "A backup couldn't be read (${Failures.code(e)})")
            Result.failure(e)
        }
    }

    /** Debug builds only: the made-up household from [Demo], for screenshots and walkthroughs. */
    fun loadDemo() = viewModelScope.launch(Dispatchers.IO) {
        if (!app.kassa.money.BuildConfig.DEBUG) return@launch
        val now = clock.value
        val d = app.kassa.money.domain.Demo.data(now.today, now.zone)
        val contents = BackupContents(
            createdAt = System.currentTimeMillis(), monthStartDay = 1, currency = null, groups = emptyList(),
            accounts = d.accounts.map { it.toEntity() }, categories = d.categories.map { it.toEntity() },
            transactions = d.transactions.map { it.toEntity() }, budgets = d.budgets.map { it.toEntity() },
            people = d.people.map { it.toEntity() }, loans = d.loans.map { it.toEntity() }, bills = d.bills.map { it.toEntity() },
            holdings = d.holdings.map { it.toEntity() }, icons = emptyMap(),
        )
        try {
            kassa.backups.restore(contents)
            settings.setMonthStartDay(1); DueNotifier.refresh(kassa); _messages.tryEmit(s(R.string.shell_demo_loaded))
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            _messages.tryEmit(s(R.string.shell_demo_failed))
        }
    }

    /**
     * Replaces everything with [contents]. Runs on the view model's scope, so leaving the page that asked doesn't cancel it halfway;
     * the result is the message to show, and is also shown as a message in case nobody is waiting for it.
     */
    fun restore(contents: BackupContents): Deferred<String> = viewModelScope.async(Dispatchers.IO) {
        val message = try {
            kassa.backups.restore(contents)
            DueNotifier.refresh(kassa)
            s(R.string.shell_backup_restored)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "A backup couldn't be restored (${Failures.code(e)})")
            Failures.message(e, Doing.RESTORE)
        }
        _messages.tryEmit(message)
        message
    }

    private companion object {
        const val TAG = "Kassa"
    }
}
