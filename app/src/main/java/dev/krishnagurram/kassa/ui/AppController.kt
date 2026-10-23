package dev.krishnagurram.kassa.ui

import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import dev.krishnagurram.kassa.data.Settings
import dev.krishnagurram.kassa.data.backup.BackupContents
import dev.krishnagurram.kassa.data.jobs.DailyJobs
import dev.krishnagurram.kassa.data.repo.EntryDraft
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.CategoryKind
import dev.krishnagurram.kassa.domain.InvestmentTransfer
import dev.krishnagurram.kassa.domain.Recurring
import dev.krishnagurram.kassa.domain.RecurringRules
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.ui.entry.EntryPreset
import dev.krishnagurram.kassa.ui.entry.EntryStart
import dev.krishnagurram.kassa.ui.insights.InsightActions
import dev.krishnagurram.kassa.ui.insights.InsightPage
import dev.krishnagurram.kassa.ui.settings.SettingsActions
import dev.krishnagurram.kassa.ui.today.NeedsYouActions
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.YearMonth

/** The phone's settings, for any screen: `LocalSettings.current.theme.collectAsState()`. */
val LocalSettings = staticCompositionLocalOf<Settings> { error("LocalSettings is provided by MainActivity") }

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
    var paying by mutableStateOf<Long?>(null)

    /** The investment transfer being explained, if any. */
    var allocating by mutableStateOf<InvestmentTransfer?>(null)

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

    /** Opens an entry on the entry page, its split loaded. [withPerson] adds them to the split, as "Split a transaction" does. */
    fun openEntry(id: Long, fromPage: Boolean, withPerson: Long? = null) {
        scope.launch {
            val start = vm.entryStartFor(id, withPerson) ?: return@launch
            if (fromPage) push(Page.Entry(start)) else openFromList(Page.Entry(start))
        }
    }

    val editFromList: (Transaction) -> Unit = { openEntry(it.id, fromPage = false) }
    val editFromPage: (Transaction) -> Unit = { openEntry(it.id, fromPage = true) }

    /** Overview's review list: bills due today and transfers to explain. */
    val needs = object : NeedsYouActions {
        override fun approve(r: Recurring, amount: Long) { vm.approveBill(r, amount) }
        override fun skip(r: Recurring) { vm.skipBill(r) }
        override fun allocate(t: InvestmentTransfer) { allocating = t }
        override fun editRecurring(r: Recurring, amount: Long) {
            val l = vm.ledger.value ?: return
            val due = RecurringRules.dueDate(r, YearMonth.from(l.today))
            val draft = EntryDraft(
                id = null, kind = TxKind.EXPENSE, amount = amount, accountId = r.accountId, toAccountId = null, categoryId = r.categoryId,
                personId = null, epochMillis = due.atTime(9, 0).atZone(l.zone).toInstant().toEpochMilli(), note = r.name,
            )
            openFromList(Page.Entry(EntryStart(prefill = draft, afterSave = { vm.billHandled(r) })))
        }
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
        override fun restore(contents: BackupContents) { vm.restore(contents) }
    }
}
