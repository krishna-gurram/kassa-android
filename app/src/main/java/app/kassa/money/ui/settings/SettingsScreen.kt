package app.kassa.money.ui.settings

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.kassa.money.BuildConfigInfo
import app.kassa.money.KassaApplication
import app.kassa.money.data.backup.BackupContents
import app.kassa.money.domain.Account
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.Currencies
import app.kassa.money.domain.Money
import app.kassa.money.domain.Recurring
import app.kassa.money.domain.subcategory
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.LocalSettings
import app.kassa.money.ui.categories.CategoryEditor
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.PageTop
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** What Settings changes in the database or elsewhere in the app. Phone preferences go straight to [LocalSettings]. */
interface SettingsActions {
    /** [quiet] skips the "saved" message, for small changes like reordering. */
    fun saveCategory(category: Category, budget: Long?, quiet: Boolean = false, then: (Long) -> Unit = {})
    fun setMonthBudget(categoryId: Long, amount: Long?)
    fun mergeCategory(from: Category, into: Category)
    fun convertToPerson(account: Account, balance: Long, name: String)
    fun importAgain()
    /** Opens the full-screen bill page; null adds a new bill. */
    fun openBill(r: Recurring?)
    suspend fun exportTo(uri: Uri, password: CharArray?): String
    suspend fun readBackup(uri: Uri, password: CharArray?): Result<BackupContents>
    /** Saves a backup to the backup folder now. Returns the file's name; throws with a message to show. */
    suspend fun backupNow(): String
    /** Books or cancels the daily backup and reminder to match Settings. */
    fun refreshDailyJobs()
    fun restore(contents: BackupContents)
}

/** The pages inside the Settings tab. They stack on the tab itself; Back returns to the list. */
private sealed interface SettingsPage {
    val title: String
    data object Main : SettingsPage { override val title = "Settings" }
    data class Categories(val kind: CategoryKind) : SettingsPage {
        override val title get() = if (kind == CategoryKind.EXPENSE) "Expense categories" else "Income categories"
    }
    data class EditCategory(val category: Category, val back: SettingsPage) : SettingsPage {
        override val title get() = if (category.id == 0L) "New category" else "Edit category"
    }
    data object Splitwise : SettingsPage { override val title = "Splitwise accounts" }
    data object Bills : SettingsPage { override val title = "Recurring bills" }
    data object Backup : SettingsPage { override val title = "Backup and restore" }
    data object BudgetMonth : SettingsPage { override val title = "Budget month" }
    data object Currency : SettingsPage { override val title = "Currency" }
    data object Feedback : SettingsPage { override val title = "Send feedback" }
    data object Privacy : SettingsPage { override val title = "Privacy" }
    data object Licences : SettingsPage { override val title = "Open source licences" }
    data class LicenceDetail(val licence: Licence) : SettingsPage { override val title get() = licence.name }
}

/** Settings as a tab: one list of groups, each row opening its own page. */
@Composable
fun SettingsScreen(ledger: Ledger, actions: SettingsActions, contentPadding: PaddingValues) {
    var page by remember { mutableStateOf<SettingsPage>(SettingsPage.Main) }
    val back: () -> Unit = {
        page = when (val p = page) {
            is SettingsPage.EditCategory -> p.back
            is SettingsPage.LicenceDetail -> SettingsPage.Licences
            else -> SettingsPage.Main
        }
    }
    BackHandler(enabled = page != SettingsPage.Main, onBack = back)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        if (page == SettingsPage.Main) {
            Text("Kassa ${BuildConfigInfo.versionName}", style = KassaType.small, color = Kassa.colors.ink2)
            Text("Settings", style = KassaType.title, color = Kassa.colors.ink)
        } else {
            PageTop(page.title, onBack = back)
        }
        when (val p = page) {
            SettingsPage.Main -> MainList(ledger, actions, open = { page = it })
            is SettingsPage.Categories -> CategoryList(ledger, p.kind, open = { page = SettingsPage.EditCategory(it, p) })
            is SettingsPage.EditCategory -> key(p.category.id, p.category.parentId, p.category.kind) {
                CategoryEditor(
                    ledger = ledger,
                    original = p.category,
                    onSave = { edit -> saveCategoryEdit(edit, actions, ledger); page = p.back },
                    onAddSub = { parent, name -> actions.saveCategory(parent.subcategory(name), null) },
                    onSaveSub = { sub, quiet -> actions.saveCategory(sub, null, quiet) },
                    onMergeSub = { from, into -> actions.mergeCategory(from, into) },
                    onMerge = { from, into -> actions.mergeCategory(from, into); page = p.back },
                )
            }
            SettingsPage.Splitwise -> SplitwiseCleanup(ledger, actions)
            SettingsPage.Bills -> BillsPage(ledger, actions)
            SettingsPage.Backup -> BackupPage(actions)
            SettingsPage.BudgetMonth -> BudgetMonthPage(ledger)
            SettingsPage.Currency -> CurrencyPage(ledger)
            SettingsPage.Feedback -> FeedbackPage()
            SettingsPage.Privacy -> PrivacyPage()
            SettingsPage.Licences -> LicencesPage(open = { page = SettingsPage.LicenceDetail(it) })
            is SettingsPage.LicenceDetail -> LicenceText(p.licence)
        }
    }
}

@Composable
private fun MainList(ledger: Ledger, actions: SettingsActions, open: (SettingsPage) -> Unit) {
    val settings = LocalSettings.current
    val theme by settings.theme.collectAsState()
    val currency by settings.currency.collectAsState()
    val lock by settings.lockEnabled.collectAsState()
    val lastBackup by settings.lastBackup.collectAsState()
    val topLevel = { kind: CategoryKind -> ledger.categories.count { it.kind == kind && it.parentId == null && !it.hidden } }

    SectionHeader("Appearance")
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Theme", style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
        Choice(listOf("SYSTEM" to "System", "LIGHT" to "Light", "DARK" to "Dark"), theme, settings::setTheme)
    }
    HorizontalDivider(color = Kassa.colors.line)
    val cur = Currencies.byCode(currency)
    NavRow("Currency", "${cur.name}, ${Money.format(123_450, style = cur)}") { open(SettingsPage.Currency) }

    SectionHeader("Budgeting")
    NavRow("Expense categories", "${topLevel(CategoryKind.EXPENSE)}") { open(SettingsPage.Categories(CategoryKind.EXPENSE)) }
    NavRow("Income categories", "${topLevel(CategoryKind.INCOME)}") { open(SettingsPage.Categories(CategoryKind.INCOME)) }
    NavRow("Budget month", "Starts on day ${ledger.monthStartDay}") { open(SettingsPage.BudgetMonth) }

    SectionHeader("Bills")
    NavRow("Recurring bills", "${ledger.bills.size}") { open(SettingsPage.Bills) }

    SectionHeader("Reminder")
    DailyReminder(actions)

    SectionHeader("Security")
    SwitchRow("App lock", "Fingerprint, face or screen lock", lock, settings::setLock)

    SectionHeader("Data")
    val (lastAt, problem) = lastBackup
    NavRow("Backup and restore", when {
        problem != null -> "Needs attention"
        lastAt > 0 -> "Last " + Instant.ofEpochMilli(lastAt).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
        else -> "Not set up"
    }) { open(SettingsPage.Backup) }
    NavRow("Import from another app", "Supported files: .mmbak") { actions.importAgain() }
    val splitwise = splitwiseAccounts(ledger)
    if (splitwise.isNotEmpty()) NavRow("Splitwise accounts", "${splitwise.size} to convert") { open(SettingsPage.Splitwise) }

    SectionHeader("About")
    NavRow("Send feedback", "") { open(SettingsPage.Feedback) }
    val tip by settings.tip.collectAsState()
    NavRow("Show tips again", if (tip == 0) "On Overview" else "") { settings.setTip(0) }
    NavRow("Privacy", "") { open(SettingsPage.Privacy) }
    NavRow("Open source licences", "") { open(SettingsPage.Licences) }

    Gap(20)
    Text("No internet permission. Data is encrypted on this phone.", style = KassaType.small, color = Kassa.colors.ink3)
    Gap(24)
}
