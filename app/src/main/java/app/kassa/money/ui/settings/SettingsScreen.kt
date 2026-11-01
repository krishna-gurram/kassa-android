package app.kassa.money.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import app.kassa.money.BuildConfigInfo
import app.kassa.money.KassaApplication
import app.kassa.money.R
import app.kassa.money.data.backup.BackupContents
import app.kassa.money.domain.Account
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.Currencies
import app.kassa.money.domain.Money
import app.kassa.money.domain.Recurring
import app.kassa.money.domain.subcategory
import app.kassa.money.ui.AppLock
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.LocalSettings
import app.kassa.money.ui.categories.CategoryEditor
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.LocalMessages
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
    /** Replaces everything with [contents]; returns the message to show when it is done (restored, or why nothing changed). */
    suspend fun restore(contents: BackupContents): String
    /** True while something outside Settings (the "Backup needs attention" notification) asked for the backup page. */
    val wantsBackupPage: Boolean
    /** Settings opened the page that was asked for. */
    fun backupPageOpened()
    /** Opens the page for choosing Overview's account tiles. */
    fun openPins()
    /** Debug builds only: replaces everything with the made-up household in [app.kassa.money.domain.Demo]. */
    fun loadDemo()
}

/** The pages inside the Settings tab. They stack on the tab itself; Back returns to the list. */
private sealed interface SettingsPage {
    /** Whether the page shows amounts, and so gets the Hide balances switch. */
    val amounts: Boolean get() = false
    data object Main : SettingsPage
    data class Categories(val kind: CategoryKind) : SettingsPage {
        override val amounts: Boolean get() = true
    }
    data class EditCategory(val category: Category, val back: SettingsPage) : SettingsPage
    data object PersonAccounts : SettingsPage { override val amounts = true }
    data object Bills : SettingsPage { override val amounts = true }
    data object Notifications : SettingsPage
    data object Backup : SettingsPage
    data object BudgetMonth : SettingsPage
    data object Currency : SettingsPage
    data object Feedback : SettingsPage
    data object OpenOn : SettingsPage
    data object Privacy : SettingsPage
    data object Licences : SettingsPage
    data class LicenceDetail(val licence: Licence) : SettingsPage
}

/** The title shown at the top of a page. */
@Composable
private fun SettingsPage.titleText(): String = when (this) {
    SettingsPage.Main -> stringResource(R.string.settings_title)
    is SettingsPage.Categories -> stringResource(if (kind == CategoryKind.EXPENSE) R.string.settings_page_expense_categories else R.string.settings_page_income_categories)
    is SettingsPage.EditCategory -> stringResource(if (category.id == 0L) R.string.settings_page_new_category else R.string.settings_page_edit_category)
    SettingsPage.PersonAccounts -> stringResource(R.string.settings_page_account_to_person)
    SettingsPage.Bills -> stringResource(R.string.settings_page_bills)
    SettingsPage.Notifications -> stringResource(R.string.settings_page_notifications)
    SettingsPage.Backup -> stringResource(R.string.settings_page_backup)
    SettingsPage.BudgetMonth -> stringResource(R.string.settings_page_budget_month)
    SettingsPage.Currency -> stringResource(R.string.settings_page_currency)
    SettingsPage.Feedback -> stringResource(R.string.settings_page_feedback)
    SettingsPage.OpenOn -> stringResource(R.string.settings_page_open_on)
    SettingsPage.Privacy -> stringResource(R.string.settings_page_privacy)
    SettingsPage.Licences -> stringResource(R.string.settings_page_licences)
    is SettingsPage.LicenceDetail -> licence.name
}

/** Settings as a tab: one list of groups, each row opening its own page. */
@Composable
fun SettingsScreen(ledger: Ledger, actions: SettingsActions, contentPadding: PaddingValues) {
    var page by remember { mutableStateOf<SettingsPage>(SettingsPage.Main) }
    // Asked for from a notification: show the backup page, whatever Settings was showing.
    LaunchedEffect(actions.wantsBackupPage) {
        if (actions.wantsBackupPage) { page = SettingsPage.Backup; actions.backupPageOpened() }
    }
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
            Text(stringResource(R.string.settings_version, BuildConfigInfo.versionName), style = KassaType.small, color = Kassa.colors.ink2)
            Text(stringResource(R.string.settings_title), style = KassaType.title, color = Kassa.colors.ink)
        } else {
            PageTop(page.titleText(), onBack = back, amounts = page.amounts)
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
            SettingsPage.PersonAccounts -> AccountsToPeople(ledger, actions)
            SettingsPage.Bills -> BillsPage(ledger, actions)
            SettingsPage.Notifications -> NotificationSettingsPage(actions)
            SettingsPage.Backup -> BackupPage(actions)
            SettingsPage.BudgetMonth -> BudgetMonthPage(ledger)
            SettingsPage.Currency -> CurrencyPage(ledger)
            SettingsPage.Feedback -> FeedbackPage()
            SettingsPage.OpenOn -> OpenOnPage()
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

    SectionHeader(stringResource(R.string.settings_section_appearance))
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.settings_theme), style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
        Choice(listOf("SYSTEM" to stringResource(R.string.settings_theme_system), "LIGHT" to stringResource(R.string.settings_theme_light), "DARK" to stringResource(R.string.settings_theme_dark)), theme, settings::setTheme)
    }
    HorizontalDivider(color = Kassa.colors.line)
    val cur = Currencies.byCode(currency)
    NavRow(stringResource(R.string.settings_page_currency), stringResource(R.string.settings_currency_row, cur.name, Money.sample(123_450, style = cur))) { open(SettingsPage.Currency) }

    SectionHeader(stringResource(R.string.settings_section_home))
    val openTab by settings.openTab.collectAsState()
    NavRow(stringResource(R.string.settings_page_open_on), app.kassa.money.ui.Tab.entries.firstOrNull { it.name == openTab }?.label ?: stringResource(R.string.settings_open_on_default)) { open(SettingsPage.OpenOn) }
    val pins by settings.pins.collectAsState()
    NavRow(stringResource(R.string.settings_pins_title), stringResource(R.string.settings_pins_value, app.kassa.money.ui.today.pinned(ledger, pins).size)) { actions.openPins() }

    SectionHeader(stringResource(R.string.settings_section_budgeting))
    NavRow(stringResource(R.string.settings_page_expense_categories), "${topLevel(CategoryKind.EXPENSE)}") { open(SettingsPage.Categories(CategoryKind.EXPENSE)) }
    NavRow(stringResource(R.string.settings_page_income_categories), "${topLevel(CategoryKind.INCOME)}") { open(SettingsPage.Categories(CategoryKind.INCOME)) }
    NavRow(stringResource(R.string.settings_page_budget_month), stringResource(R.string.settings_budget_month_value, ledger.monthStartDay)) { open(SettingsPage.BudgetMonth) }

    SectionHeader(stringResource(R.string.settings_section_bills))
    NavRow(stringResource(R.string.settings_page_bills), "${ledger.bills.size}") { open(SettingsPage.Bills) }

    SectionHeader(stringResource(R.string.settings_section_reminders))
    NavRow(stringResource(R.string.settings_page_notifications), notificationsSummary()) { open(SettingsPage.Notifications) }

    SectionHeader(stringResource(R.string.settings_section_security))
    val context = LocalContext.current
    val messages = LocalMessages.current
    val lockUnavailable = stringResource(R.string.settings_lock_unavailable)
    val coffeeFailed = stringResource(R.string.settings_coffee_failed)
    SwitchRow(stringResource(R.string.settings_lock_title), stringResource(R.string.settings_lock_detail), lock) { on ->
        when {
            !on -> settings.setLock(false)
            // The lock asks for a fingerprint, face or the screen lock, so the phone needs one before this can be on.
            AppLock.check(context) == AppLock.Status.READY -> settings.setLock(true)
            AppLock.check(context) == AppLock.Status.NO_SCREEN_LOCK -> messages?.say(context.getString(R.string.shell_set_a_screen_lock))
            else -> messages?.say(lockUnavailable)
        }
    }

    SectionHeader(stringResource(R.string.settings_section_data))
    val (lastAt, problem) = lastBackup
    NavRow(stringResource(R.string.settings_page_backup), when {
        problem != null -> stringResource(R.string.settings_backup_needs_attention)
        lastAt > 0 -> stringResource(
            R.string.settings_backup_last_day,
            Instant.ofEpochMilli(lastAt).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)),
        )
        else -> stringResource(R.string.settings_backup_not_set_up)
    }) { open(SettingsPage.Backup) }
    NavRow(stringResource(R.string.settings_import_title), stringResource(R.string.settings_import_detail)) { actions.importAgain() }
    if (convertibleAccounts(ledger).isNotEmpty()) NavRow(stringResource(R.string.settings_person_title), stringResource(R.string.settings_person_detail)) { open(SettingsPage.PersonAccounts) }

    SectionHeader(stringResource(R.string.settings_section_about))
    NavRow(stringResource(R.string.settings_page_feedback), "") { open(SettingsPage.Feedback) }
    // A plain link to the website, which then goes to the tip page. Kassa itself never goes online.
    NavRow(stringResource(R.string.settings_coffee_title), "www.kassamoney.com") {
        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.kassamoney.com/support.html"))) }
            .onFailure { messages?.say(coffeeFailed) }
    }
    val tip by settings.tip.collectAsState()
    NavRow(stringResource(R.string.settings_tips_title), if (tip == 0) stringResource(R.string.settings_tips_value) else "") { settings.setTip(0) }
    NavRow(stringResource(R.string.settings_page_privacy), "") { open(SettingsPage.Privacy) }
    NavRow(stringResource(R.string.settings_page_licences), "") { open(SettingsPage.Licences) }
    // Screenshots and walkthroughs use made-up data; the debug build installs beside the real app.
    if (app.kassa.money.BuildConfig.DEBUG) {
        var confirmDemo by remember { mutableStateOf(false) }
        NavRow(stringResource(R.string.settings_demo_title), stringResource(R.string.settings_demo_value)) { confirmDemo = true }
        if (confirmDemo) app.kassa.money.ui.common.ConfirmDialog(
            stringResource(R.string.settings_demo_confirm_title),
            stringResource(R.string.settings_demo_confirm_body),
            stringResource(R.string.settings_demo_title), onConfirm = { actions.loadDemo() }, onDismiss = { confirmDemo = false },
        )
    }

    Gap(20)
    Text(stringResource(R.string.settings_footer), style = KassaType.small, color = Kassa.colors.ink3)
    Gap(24)
}

/** The tab Kassa starts on. Settings isn't offered: it's never where you'd want to land. */
@Composable
private fun OpenOnPage() {
    val settings = LocalSettings.current
    val selected by settings.openTab.collectAsState()
    Text(stringResource(R.string.settings_open_on_hint), style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(bottom = 6.dp))
    app.kassa.money.ui.Tab.entries.filter { it != app.kassa.money.ui.Tab.SETTINGS }.forEach { t ->
        Column(Modifier.clickable { settings.setOpenTab(t.name) }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(t.label, style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
                Text(if (t.name == selected) "✓" else "", style = KassaType.bodyStrong, color = Kassa.colors.ink)
            }
            HorizontalDivider(color = Kassa.colors.line)
        }
    }
    Gap(24)
}
