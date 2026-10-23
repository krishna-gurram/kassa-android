package dev.krishnagurram.kassa.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.krishnagurram.kassa.KassaApplication
import dev.krishnagurram.kassa.data.AccountsState
import dev.krishnagurram.kassa.data.EntryDraft
import dev.krishnagurram.kassa.data.PeopleState
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.Budget
import dev.krishnagurram.kassa.domain.Budgets
import dev.krishnagurram.kassa.domain.CardCycle
import dev.krishnagurram.kassa.domain.CardSnapshot
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Period
import dev.krishnagurram.kassa.domain.Spending
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.domain.personBalance
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * One snapshot of what the main screens need: categories, accounts, budgets, people and roughly the last
 * four months of entries (enough for card statements and three months of baselines).
 */
data class Ledger(
    val categories: List<Category>,
    val accounts: AccountsState,
    val budgets: List<Budget>,
    val people: PeopleState,
    val recent: List<Transaction>,
    val monthStartDay: Int,
    val today: LocalDate,
    val zone: ZoneId,
    val assist: dev.krishnagurram.kassa.data.AssistState = dev.krishnagurram.kassa.data.AssistState(emptyList(), emptyList(), emptyList(), emptyList()),
) {
    /** Recurring bills waiting for approval today. */
    fun dueRecurring() = assist.recurring.filter { dev.krishnagurram.kassa.domain.RecurringRules.isDue(it, recent, today, zone) }

    fun holdingsValue(accountId: Long): Long = assist.holdings.filter { it.accountId == accountId }.sumOf { it.value }

    val categoryById: Map<Long, Category> = categories.associateBy { it.id }
    val accountById: Map<Long, Account> = accounts.accounts.associateBy { it.id }
    val period: Period get() = Period.containing(today, monthStartDay)

    /** The phone's first day of the week (Sunday in the US). Every "this week" in the app starts here. */
    val firstDayOfWeek: java.time.DayOfWeek get() = java.time.temporal.WeekFields.of(java.util.Locale.getDefault()).firstDayOfWeek
    val weekStart: LocalDate get() = dev.krishnagurram.kassa.domain.WeekPace.startOf(today, firstDayOfWeek)

    fun cards(): List<CardSnapshotFor> = accounts.accounts
        .filter { it.type.hasStatement && !it.archived && it.statementDay != null }
        .map { CardSnapshotFor(it, CardCycle.snapshot(it, recent, today, zone, balance = accounts.balances[it.id] ?: 0L)) }

    fun personBalances(): Map<Long, Long> = people.people.associate { it.id to personBalance(it.id, people.loans, people.payments) }

    /** "Groceries: $412 of $600, 8% ahead of an even pace." Shown right after logging an expense. */
    fun feedbackFor(categoryId: Long?): String? {
        val top = Spending.topOf(categoryId, categoryById) ?: return null
        val spent = Spending.byTopCategory(recent, categoryById, period.start, period.endInclusive, zone)[top.id] ?: 0
        val budget = Budgets.amountFor(top.id, period.yearMonth, budgets)
        if (budget == null || budget == 0L) return "${top.name}: ${Money.format(spent, wholeDollars = true)} this month."
        val pace = budget * period.progress(today)
        val tail = when {
            spent > budget -> "${Money.format(spent - budget, wholeDollars = true)} over budget."
            spent > pace * 1.05 -> "${Math.round((spent / pace - 1) * 100)}% ahead of an even pace."
            else -> "On pace."
        }
        return "${top.name}: ${Money.format(spent, wholeDollars = true)} of ${Money.format(budget, wholeDollars = true)}. $tail"
    }
}

data class CardSnapshotFor(val account: Account, val snapshot: CardSnapshot)

class LedgerViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = (app as KassaApplication).repository
    private val settings = (app as KassaApplication).settings
    private val zone = ZoneId.systemDefault()

    private val recent = repo.transactionsBetween(
        LocalDate.now(zone).minusDays(130).atStartOfDay(zone).toInstant().toEpochMilli(),
        Long.MAX_VALUE,
    )

    private val assist = (app as KassaApplication).assist

    val ledger: StateFlow<Ledger?> = combine(
        combine(repo.categories, repo.accounts, repo.budgets) { c, a, b -> Triple(c, a, b) },
        repo.people,
        recent,
        settings.monthStartDay,
        assist.state,
    ) { (c, a, b), p, r, ms, x ->
        Ledger(c, a, b, p, r, ms, LocalDate.now(zone), zone, x)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    val alertApps = settings.alertApps
    val seenApps = settings.seenApps

    fun save(draft: EntryDraft, afterSave: (() -> Unit)? = null) = viewModelScope.launch {
        val saved = repo.save(draft)
        afterSave?.invoke()
        val l = ledger.value
        val to = saved.toAccountId?.let { l?.accountById?.get(it) }
        if (draft.id == null && saved.kind == TxKind.TRANSFER && to?.type == dev.krishnagurram.kassa.domain.AccountType.INVESTMENT) {
            assist.addAllocation(saved.id, saved.amount, to.id, saved.epochMillis)
            _messages.tryEmit("Moved ${Money.format(saved.amount)} to ${to.name}. Say where it went on Overview.")
            return@launch
        }
        val msg = when (saved.kind) {
            TxKind.EXPENSE -> {
                // The ledger updates a moment later; add this entry so the numbers include it.
                val withThis = l?.copy(recent = l.recent.filter { it.id != saved.id } + saved)
                withThis?.feedbackFor(saved.categoryId) ?: "Expense saved."
            }
            TxKind.INCOME -> "${Money.format(saved.amount)} added to ${l?.accountById?.get(saved.accountId)?.name ?: "the account"}."
            TxKind.TRANSFER -> {
                val to = l?.accountById?.get(saved.toAccountId)
                if (to?.type?.hasStatement == true) "Paid ${Money.format(saved.amount)} to ${to.name}." else "Moved ${Money.format(saved.amount)} to ${to?.name ?: "the account"}."
            }
            TxKind.PERSON_IN, TxKind.PERSON_OUT -> "Payment recorded."
            TxKind.BUY, TxKind.SELL -> "Saved."
        }
        _messages.tryEmit(if (draft.id != null) "Saved. $msg" else msg)
    }

    fun delete(id: Long) = viewModelScope.launch { repo.delete(id); _messages.tryEmit("Entry deleted.") }

    fun addPerson(name: String, then: (Long) -> Unit = {}) = viewModelScope.launch { then(repo.addPerson(name)) }

    fun updatePerson(p: dev.krishnagurram.kassa.domain.Person) = viewModelScope.launch { repo.updatePerson(p); _messages.tryEmit("${p.name} saved.") }

    fun addPerson(name: String, opening: Long) = viewModelScope.launch { repo.addPerson(name, opening); _messages.tryEmit("$name added.") }

    fun splitExisting(txId: Long, personId: Long, theirShare: Long) = viewModelScope.launch {
        runCatching { repo.splitExisting(txId, personId, theirShare) }
            .onSuccess { _messages.tryEmit("Split saved.") }.onFailure { _messages.tryEmit(it.message ?: "Couldn't split that.") }
    }

    fun sell(accountId: Long, ticker: String, microShares: Long, price: Long) = viewModelScope.launch {
        repo.sell(accountId, ticker, microShares, price, System.currentTimeMillis())
    }

    fun addAccount(account: Account, newGroupName: String?) = viewModelScope.launch {
        repo.addAccount(account, newGroupName); _messages.tryEmit("${account.name} added.")
    }

    fun setArchived(account: Account, archived: Boolean) = viewModelScope.launch {
        repo.updateAccount(account.copy(archived = archived))
        _messages.tryEmit(if (archived) "${account.name} archived. Its history stays." else "${account.name} restored.")
    }

    fun saveCategory(category: Category, budget: Long?, then: (Long) -> Unit = {}) = viewModelScope.launch {
        val id = repo.saveCategory(category)
        if (category.parentId == null && category.kind == dev.krishnagurram.kassa.domain.CategoryKind.EXPENSE) repo.setBudget(id, 0, budget)
        then(id)
        _messages.tryEmit("${category.name} saved.")
    }

    fun setMonthBudget(categoryId: Long, month: java.time.YearMonth, amount: Long?) = viewModelScope.launch {
        repo.setBudget(categoryId, Budgets.key(month), amount)
    }

    fun mergeCategory(from: Category, into: Category) = viewModelScope.launch {
        repo.mergeCategory(from, into.id)
        _messages.tryEmit("Moved everything from ${from.name} into ${into.name}.")
    }

    fun convertToPerson(account: Account, balance: Long, name: String) = viewModelScope.launch {
        repo.convertAccountToPerson(account, balance, name)
        _messages.tryEmit("${account.name} is now ${name.trim()} under People. The account is archived with its history.")
    }

    fun setMonthStartDay(day: Int) { settings.setMonthStartDay(day) }

    /** Copies a picked photo into private storage and returns its file name. Runs off the main thread. */
    suspend fun saveIconImage(uri: android.net.Uri): String? = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { dev.krishnagurram.kassa.data.IconImages.save(getApplication(), uri) }.getOrNull()
    }

    /* ---------- assist ---------- */
    fun approveRecurring(r: dev.krishnagurram.kassa.domain.Recurring, amount: Long) = viewModelScope.launch {
        val l = ledger.value ?: return@launch
        val due = dev.krishnagurram.kassa.domain.RecurringRules.dueDate(r, java.time.YearMonth.from(l.today))
        repo.save(EntryDraft(null, TxKind.EXPENSE, amount, r.accountId, null, r.categoryId, null,
            due.atTime(9, 0).atZone(zone).toInstant().toEpochMilli(), r.name, emptyList()))
        assist.markHandled(r, Budgets.key(java.time.YearMonth.from(l.today)))
        _messages.tryEmit("${r.name} added for ${Money.format(amount)}.")
    }

    fun skipRecurring(r: dev.krishnagurram.kassa.domain.Recurring) = viewModelScope.launch {
        val l = ledger.value ?: return@launch
        assist.markHandled(r, Budgets.key(java.time.YearMonth.from(l.today)))
        _messages.tryEmit("Skipped ${r.name} this month.")
    }

    fun markHandled(r: dev.krishnagurram.kassa.domain.Recurring) = viewModelScope.launch {
        assist.markHandled(r, Budgets.key(java.time.YearMonth.from(LocalDate.now(zone))))
    }

    fun saveRecurring(r: dev.krishnagurram.kassa.domain.Recurring) = viewModelScope.launch { assist.saveRecurring(r); _messages.tryEmit("${r.name} saved.") }
    fun deleteRecurring(id: Long) = viewModelScope.launch { assist.deleteRecurring(id) }

    fun dismissPending(id: Long, message: String? = null) = viewModelScope.launch { assist.dismiss(id); message?.let { _messages.tryEmit(it) } }

    fun buy(accountId: Long, ticker: String, microShares: Long, price: Long, pendingId: Long?) = viewModelScope.launch {
        assist.buy(accountId, ticker, microShares, price, System.currentTimeMillis(), pendingId)
        _messages.tryEmit("Added ${ticker.uppercase()} to holdings.")
    }

    fun saveHolding(h: dev.krishnagurram.kassa.domain.Holding) = viewModelScope.launch { assist.saveHolding(h) }
    fun deleteHolding(id: Long) = viewModelScope.launch { assist.deleteHolding(id) }
    fun addRule(pkg: String, last4: String?, accountId: Long) = viewModelScope.launch { assist.addRule(pkg, last4, accountId) }
    fun deleteRule(id: Long) = viewModelScope.launch { assist.deleteRule(id) }
    fun setAlertApp(pkg: String, on: Boolean) = settings.setAlertApp(pkg, on)

    /* ---------- insights, privacy, backup ---------- */
    val sensitivity = settings.sensitivity
    val dismissedFlags = settings.dismissed
    val lockEnabled = settings.lockEnabled
    fun setSensitivity(v: String) = settings.setSensitivity(v)
    fun setLock(on: Boolean) = settings.setLock(on)
    val theme = settings.theme
    fun setTheme(v: String) = settings.setTheme(v)
    val currency = settings.currency
    fun setCurrency(code: String) = settings.setCurrency(code)
    fun dismissFlag(category: Category) {
        val l = ledger.value ?: return
        settings.dismiss("${category.id}:${Budgets.key(l.period.yearMonth)}")
        _messages.tryEmit("Got it. No more flags for ${category.name} this month.")
    }

    /** Sets the category's monthly budget, which is what a cap is. */
    fun setCap(category: Category, amount: Long) = viewModelScope.launch {
        repo.setBudget(category.id, 0, amount)
        _messages.tryEmit("${category.name} capped at ${Money.format(amount, wholeDollars = true)} a month.")
    }

    private val backup = (app as KassaApplication).backup

    suspend fun exportTo(uri: android.net.Uri, passphrase: CharArray): String = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver.openOutputStream(uri)!!.use { backup.write(it, passphrase) }
            "Backup saved. Keep the passphrase somewhere safe; without it the file can't be opened."
        }.getOrElse { "Couldn't save the backup: ${it.message}" }
    }

    suspend fun readBackup(uri: android.net.Uri, passphrase: CharArray): Result<Pair<org.json.JSONObject, String>> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { getApplication<Application>().contentResolver.openInputStream(uri)!!.use { backup.read(it, passphrase) } }
    }

    fun restore(json: org.json.JSONObject) = viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
        runCatching { backup.restore(json) }
            .onSuccess { _messages.tryEmit("Backup restored.") }
            .onFailure { _messages.tryEmit("Nothing was changed. ${it.message}") }
    }

    suspend fun loadForEdit(id: Long): Pair<Transaction, List<Long>>? {
        val t = repo.transaction(id) ?: return null
        return t to repo.splitPeopleFor(id)
    }

    /**
     * How the entry page opens an existing entry, split included. [withPerson] adds someone to the split and opens
     * the split panel, for "Split a transaction" from a person.
     */
    suspend fun entryStartFor(id: Long, withPerson: Long? = null): dev.krishnagurram.kassa.ui.entry.EntryStart? {
        val t = repo.transaction(id) ?: return null
        val loans = repo.loansFor(id)
        val paidBy = if (t.kind == dev.krishnagurram.kassa.domain.TxKind.EXPENSE && t.accountId == null) t.personId else null
        val shares = if (paidBy != null) mapOf(paidBy to (t.amount - (t.myShare ?: t.amount)).coerceAtLeast(0))
        else loans.filterValues { it > 0 }
        val people = (shares.keys.toList() + listOfNotNull(withPerson)).distinct()
        return dev.krishnagurram.kassa.ui.entry.EntryStart(editing = t, splitWith = people, shares = shares, paidBy = paidBy, openSplit = withPerson != null)
    }

    fun accountHistory(accountId: Long) = repo.accountHistory(accountId)

    fun between(fromMillis: Long, toMillis: Long) = repo.transactionsBetween(fromMillis, toMillis)

    fun updateAccount(account: Account, newGroupName: String?) = viewModelScope.launch {
        repo.updateAccount(account, newGroupName); _messages.tryEmit("${account.name} saved.")
    }

    suspend fun notes(kind: TxKind): List<String> = repo.frequentNotes(kind)
}
