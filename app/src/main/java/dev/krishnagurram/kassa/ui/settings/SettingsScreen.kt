package dev.krishnagurram.kassa.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import dev.krishnagurram.kassa.BuildConfigInfo
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.Budgets
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.CategoryKind
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.activity.Chevron
import dev.krishnagurram.kassa.ui.categories.CategoryEdit
import dev.krishnagurram.kassa.ui.categories.CategoryEditor
import dev.krishnagurram.kassa.ui.common.CategoryBadge
import dev.krishnagurram.kassa.ui.common.EntryRow
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.GlyphBadge
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.importer.SecondaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

/** Actions Settings can take. Implemented by the main screen with the view model. */
interface SettingsActions {
    fun saveCategory(category: Category, budget: Long?, then: (Long) -> Unit = {})
    fun setMonthBudget(categoryId: Long, amount: Long?)
    fun mergeCategory(from: Category, into: Category)
    fun convertToPerson(account: Account, balance: Long, name: String)
    fun setMonthStartDay(day: Int)
    fun importAgain()
    fun saveRecurring(r: dev.krishnagurram.kassa.domain.Recurring)
    fun deleteRecurring(id: Long)
    /** Opens the full-screen bill page; null adds a new bill. */
    fun openBill(r: dev.krishnagurram.kassa.domain.Recurring?)
    fun setSensitivity(v: String)
    fun setLock(on: Boolean)
    fun setTheme(v: String)
    fun setCurrency(code: String)
    suspend fun exportTo(uri: android.net.Uri, passphrase: CharArray): String
    suspend fun readBackup(uri: android.net.Uri, passphrase: CharArray): Result<Pair<org.json.JSONObject, String>>
    fun restore(json: org.json.JSONObject)
}

/** Current values for settings that live outside the database. */
data class SettingsValues(val alertApps: Set<String>, val seenApps: Set<String>, val sensitivity: String, val lock: Boolean, val theme: String = "SYSTEM", val currency: String = "USD")

private sealed interface Page {
    data object Main : Page
    data class Categories(val kind: CategoryKind) : Page
    data class Edit(val category: Category, val back: Page) : Page
    data object Splitwise : Page
    data object Recurring : Page
    data object Backup : Page
    data object BudgetMonth : Page
    data object Currency : Page
}

/** Settings as a tab: one list of groups, each row opening its own page. */
@Composable
fun SettingsScreen(ledger: Ledger, actions: SettingsActions, values: SettingsValues, contentPadding: androidx.compose.foundation.layout.PaddingValues) {
    var page by remember { mutableStateOf<Page>(Page.Main) }
    val back: () -> Unit = {
        page = when (val p = page) {
            is Page.Edit -> p.back
            else -> Page.Main
        }
    }
    BackHandler(enabled = page != Page.Main, onBack = back)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        if (page == Page.Main) {
            Text("Kassa ${BuildConfigInfo.versionName}", style = KassaType.small, color = Kassa.colors.ink2)
            Text("Settings", style = KassaType.title, color = Kassa.colors.ink)
        } else {
            dev.krishnagurram.kassa.ui.common.PageTop(
                when (val p = page) {
                    Page.Main -> "Settings"
                    is Page.Categories -> if (p.kind == CategoryKind.EXPENSE) "Expense categories" else "Income categories"
                    is Page.Edit -> if (p.category.id == 0L) "New category" else "Edit category"
                    Page.Splitwise -> "Splitwise accounts"
                    Page.Recurring -> "Recurring bills"
                    Page.Backup -> "Backup and restore"
                    Page.BudgetMonth -> "Budget month"
                    Page.Currency -> "Currency"
                },
                onBack = back,
            )
        }
        when (val p = page) {
            Page.Main -> Main(ledger, actions, values, open = { page = it })
            is Page.Categories -> CategoryList(ledger, p.kind, open = { page = Page.Edit(it, p) })
            is Page.Edit -> androidx.compose.runtime.key(p.category.id, p.category.parentId, p.category.kind) { CategoryEditor(
                ledger = ledger,
                original = p.category,
                onSave = { edit -> save(edit, actions, ledger); page = p.back },
                onAddSub = { parent, name ->
                    actions.saveCategory(Category(name = name, kind = parent.kind, parentId = parent.id, icon = parent.icon, color = parent.color, countsTowardDaily = parent.countsTowardDaily), null)
                },
                onSaveSub = { sub -> actions.saveCategory(sub, null) },
                onMergeSub = { from, into -> actions.mergeCategory(from, into) },
                onMerge = { from, into -> actions.mergeCategory(from, into); page = p.back },
            ) }
            Page.Splitwise -> SplitwiseCleanup(ledger, actions)
            Page.Recurring -> RecurringPage(ledger, actions)
            Page.Backup -> BackupPage(actions)
            Page.BudgetMonth -> BudgetMonthPage(ledger, actions)
            Page.Currency -> CurrencyPage(ledger, values.currency, actions)
        }
    }
}

private fun save(edit: CategoryEdit, actions: SettingsActions, ledger: Ledger) {
    actions.saveCategory(edit.category, edit.budget) { id ->
        when {
            edit.thisMonthBudget != null -> actions.setMonthBudget(id, edit.thisMonthBudget)
            edit.clearThisMonth -> actions.setMonthBudget(id, null)
        }
    }
    // Subcategories follow their parent's color.
    if (edit.category.parentId == null && edit.category.id != 0L) {
        ledger.categories.filter { it.parentId == edit.category.id && it.color != edit.category.color }
            .forEach { actions.saveCategory(it.copy(color = edit.category.color), null) }
    }
}

@Composable
private fun Main(ledger: Ledger, actions: SettingsActions, values: SettingsValues, open: (Page) -> Unit) {
    val expenses = ledger.categories.count { it.kind == CategoryKind.EXPENSE && it.parentId == null && !it.hidden }
    val incomes = ledger.categories.count { it.kind == CategoryKind.INCOME && it.parentId == null && !it.hidden }
    val splitwise = splitwiseAccounts(ledger)

    SectionHeader("Appearance")
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Theme", style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
        Choice(listOf("SYSTEM" to "System", "LIGHT" to "Light", "DARK" to "Dark"), values.theme, actions::setTheme)
    }
    HorizontalDivider(color = Kassa.colors.line)
    val cur = dev.krishnagurram.kassa.domain.Currencies.byCode(values.currency)
    NavRow("Currency", "${cur.name}, ${Money.format(123_450, style = cur)}") { open(Page.Currency) }

    SectionHeader("Budgeting")
    NavRow("Expense categories", "$expenses") { open(Page.Categories(CategoryKind.EXPENSE)) }
    NavRow("Income categories", "$incomes") { open(Page.Categories(CategoryKind.INCOME)) }
    NavRow("Budget month", "Starts on day ${ledger.monthStartDay}") { open(Page.BudgetMonth) }

    SectionHeader("Bills")
    NavRow("Recurring bills", "${ledger.assist.recurring.size}") { open(Page.Recurring) }

    SectionHeader("Security")
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("App lock", style = KassaType.bodyStrong, color = Kassa.colors.ink)
            Text("Fingerprint, face or screen lock", style = KassaType.small, color = Kassa.colors.ink2)
        }
        androidx.compose.material3.Switch(values.lock, { actions.setLock(it) },
            colors = androidx.compose.material3.SwitchDefaults.colors(checkedTrackColor = Kassa.colors.ink, checkedThumbColor = Kassa.colors.onInk))
    }
    HorizontalDivider(color = Kassa.colors.line)

    SectionHeader("Data")
    NavRow("Backup and restore", "Encrypted file") { open(Page.Backup) }
    NavRow("Import from Money Manager", "Replaces data") { actions.importAgain() }
    if (splitwise.isNotEmpty()) NavRow("Splitwise accounts", "${splitwise.size} to convert") { open(Page.Splitwise) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    val crash = remember { (context.applicationContext as? dev.krishnagurram.kassa.KassaApplication)?.crashFile()?.takeIf { it.exists() } }
    var crashCopied by remember { mutableStateOf(false) }
    if (crash != null && !crashCopied) NavRow("Last crash report", "Tap to copy") {
        clipboard.setText(androidx.compose.ui.text.AnnotatedString(runCatching { crash.readText() }.getOrDefault("")))
        crash.delete(); crashCopied = true
    }

    Gap(20)
    Text("No internet permission. Data is encrypted on this phone.", style = KassaType.small, color = Kassa.colors.ink3)
    Gap(24)
}

/** A small segmented choice for settings with two to four options. */
@Composable
private fun Choice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    Row(Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(10.dp)).background(Kassa.colors.chip).padding(3.dp)) {
        options.forEach { (key, label) ->
            val on = key == selected
            Text(
                label, style = KassaType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold),
                color = if (on) Kassa.colors.ink else Kassa.colors.ink2,
                modifier = Modifier.clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(if (on) Kassa.colors.raised else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable { onSelect(key) }.padding(horizontal = 12.dp, vertical = 7.dp),
            )
        }
    }
}

@Composable
private fun NavRow(title: String, value: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
            Text(value, style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(end = 6.dp))
            Icon(Chevron(left = false), null, tint = Kassa.colors.ink3)
        }
        HorizontalDivider(color = Kassa.colors.line)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BudgetMonthPage(ledger: Ledger, actions: SettingsActions) {
    Text("The day your budget month starts. Pick payday to budget from one paycheck to the next.", style = KassaType.small, color = Kassa.colors.ink2)
    FlowRow(Modifier.padding(top = 12.dp)) {
        listOf(1, 5, 10, 15, 20, 25).forEach { d -> Chip("Day $d", selected = ledger.monthStartDay == d) { actions.setMonthStartDay(d) } }
    }
    var custom by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(custom, { custom = it.filter(Char::isDigit).take(2) }, singleLine = true, label = { Text("Another day, 1 to 28") }, modifier = Modifier.weight(1f))
        TextButton(onClick = { custom.toIntOrNull()?.takeIf { it in 1..28 }?.let { actions.setMonthStartDay(it); custom = "" } }) { Text("Set", color = Kassa.colors.ink) }
    }
}

/**
 * The one currency this phone shows. Changing it relabels what's here without converting anything, so with
 * entries already in Kassa it asks first.
 */
@Composable
private fun CurrencyPage(ledger: Ledger, selected: String, actions: SettingsActions) {
    var confirm by remember { mutableStateOf<dev.krishnagurram.kassa.domain.CurrencyStyle?>(null) }
    val hasData = ledger.recent.isNotEmpty() || ledger.accounts.accounts.isNotEmpty()
    dev.krishnagurram.kassa.domain.Currencies.all.forEach { c ->
        Column(Modifier.clickable { if (c.code == selected) Unit else if (hasData) confirm = c else actions.setCurrency(c.code) }) {
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name, style = KassaType.bodyStrong, color = Kassa.colors.ink)
                    Text(c.code, style = KassaType.small, color = Kassa.colors.ink2)
                }
                Text(Money.format(123_450, style = c), style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(end = 10.dp))
                Text(if (c.code == selected) "\u2713" else "", style = KassaType.bodyStrong, color = Kassa.colors.ink)
            }
            HorizontalDivider(color = Kassa.colors.line)
        }
    }
    confirm?.let { c ->
        dev.krishnagurram.kassa.ui.common.ConfirmDialog(
            title = "Show amounts in ${c.name}?",
            body = "Every amount already in Kassa keeps its number and shows as ${c.code}. Nothing is converted.",
            confirm = "Switch to ${c.code}", destructive = false,
            onConfirm = { actions.setCurrency(c.code); confirm = null }, onDismiss = { confirm = null },
        )
    }
    Gap(24)
}

@Composable
private fun CategoryList(ledger: Ledger, kind: CategoryKind, open: (Category) -> Unit) {
    val month = ledger.period.yearMonth
    val tops = ledger.categories.filter { it.kind == kind && it.parentId == null }.sortedWith(compareBy({ it.hidden }, { it.sortOrder }))
    val total = tops.filter { !it.hidden }.sumOf { Budgets.amountFor(it.id, month, ledger.budgets) ?: 0 }
    if (kind == CategoryKind.EXPENSE) Text("Budgets this month add up to ${Money.format(total, wholeDollars = true)}.", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(bottom = 8.dp))
    SecondaryButton("New category", {
        open(Category(name = "", kind = kind, color = dev.krishnagurram.kassa.ui.categories.IconCatalog.colors[tops.size % 14]))
    })
    Gap(8)
    val (active, archived) = tops.partition { !it.hidden }
    @Composable
    fun row(c: Category, last: Boolean) {
        val subs = ledger.categories.count { it.parentId == c.id && !it.hidden }
        val budget = Budgets.amountFor(c.id, month, ledger.budgets).takeIf { !c.hidden }
        val detail = listOfNotNull(
            if (subs > 0) "$subs sub" else null,
            if (kind == CategoryKind.EXPENSE && !c.countsTowardDaily) "bill" else null,
        ).joinToString(", ").ifEmpty { if (kind == CategoryKind.EXPENSE) "everyday" else "" }
        EntryRow(
            badge = { CategoryBadge(c) },
            title = c.name, subtitle = detail,
            amount = if (kind == CategoryKind.EXPENSE && !c.hidden) budget?.let { Money.format(it, wholeDollars = true) } ?: "No budget" else "",
            amountColor = if (budget == null) Kassa.colors.ink3 else Kassa.colors.ink,
            note = if (kind == CategoryKind.EXPENSE && budget != null) "a month" else null,
            showDivider = !last,
            modifier = Modifier.clickable { open(c) },
        )
    }
    active.forEachIndexed { i, c -> row(c, i == active.lastIndex) }
    if (archived.isNotEmpty()) {
        SectionHeader("Archived", "${archived.size}")
        archived.forEachIndexed { i, c -> row(c, i == archived.lastIndex) }
    }
}

private fun splitwiseAccounts(ledger: Ledger): List<Account> =
    ledger.accounts.accounts.filter { !it.archived && it.name.contains("splitwise", ignoreCase = true) }

/**
 * Money Manager had no idea of people, so Splitwise balances lived in pretend accounts. Each one becomes a
 * person carrying the same balance; the account is archived with its history, so past months stay as they were.
 */
@Composable
private fun SplitwiseCleanup(ledger: Ledger, actions: SettingsActions) {
    Text(
        "Each account becomes a person with the same balance. A positive balance means they owe you. The account is archived and keeps its history, so past months don't change.",
        style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(bottom = 8.dp),
    )
    val accounts = splitwiseAccounts(ledger)
    var converting by remember { mutableStateOf<Account?>(null) }
    if (accounts.isEmpty()) Text("Nothing left to clean up.", style = KassaType.body, color = Kassa.colors.ink2)
    accounts.forEach { a ->
        val bal = ledger.accounts.balances[a.id] ?: 0
        var name by remember(a.id) { mutableStateOf("Splitwise friends") }
        if (converting == a) dev.krishnagurram.kassa.ui.common.ConfirmDialog(
            title = "Turn ${a.name} into ${name.trim()}?",
            body = "${name.trim()} starts with a balance of ${Money.format(Math.abs(bal))} " +
                (if (bal >= 0) "receivable" else "payable") + ". ${a.name} is archived with its history.",
            confirm = "Convert", destructive = false,
            onConfirm = { actions.convertToPerson(a, bal, name) }, onDismiss = { converting = null },
        )
        Column(Modifier.padding(vertical = 10.dp)) {
            EntryRow(
                badge = { GlyphBadge(a.name.take(1)) },
                title = a.name,
                subtitle = when { bal > 0 -> "Receivable, they owe you"; bal < 0 -> "Payable, you owe them"; else -> "Settled" },
                amount = Money.format(Math.abs(bal)), showDivider = false,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Person name") }, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (name.isNotBlank()) converting = a }) { Text("Convert", color = Kassa.colors.ink) }
            }
            HorizontalDivider(color = Kassa.colors.line, modifier = Modifier.padding(top = 10.dp))
        }
    }
    Gap(24)
}

/** Save everything to one encrypted file, or bring it back. The passphrase never leaves this screen. */
@Composable
private fun BackupPage(actions: SettingsActions) {
    var pass by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }
    var pending by remember { mutableStateOf<Pair<org.json.JSONObject, String>?>(null) }
    var confirmRestore by remember { mutableStateOf(false) }
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val stamp = java.time.LocalDate.now().toString()
    val saver = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri != null) scope.launch { status = actions.exportTo(uri, pass.toCharArray()) }
    }
    val opener = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            actions.readBackup(uri, pass.toCharArray())
                .onSuccess { pending = it; status = null }
                .onFailure { status = it.message ?: "Couldn't open that file." }
        }
    }
    Text("A backup holds everything in Kassa, encrypted with a passphrase you choose. Save it somewhere you control, like a USB drive or your computer. Kassa can't recover a lost passphrase.",
        style = KassaType.body, color = Kassa.colors.ink2)
    OutlinedTextField(pass, { pass = it }, singleLine = true, label = { Text("Passphrase") },
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 12.dp))
    OutlinedTextField(confirm, { confirm = it }, singleLine = true, label = { Text("Repeat it to save a backup") },
        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
    Gap(12)
    dev.krishnagurram.kassa.ui.importer.PrimaryButton("Save encrypted backup") {
        status = when {
            pass.length < 8 -> "Use at least 8 characters."
            pass != confirm -> "The two passphrases don't match."
            else -> { saver.launch("kassa-backup-$stamp.kassa"); null }
        }
    }
    Gap(8)
    SecondaryButton("Restore from a backup", {
        if (pass.isEmpty()) status = "Enter the backup's passphrase first." else opener.launch(arrayOf("*/*"))
    })
    status?.let { Text(it, style = KassaType.body, color = Kassa.colors.ink, modifier = Modifier.padding(top = 12.dp)) }
    pending?.let { (json, summary) ->
        Gap(16)
        Text(summary, style = KassaType.bodyStrong, color = Kassa.colors.ink)
        Text("Restoring replaces everything in Kassa now with this backup.", style = KassaType.small, color = Kassa.colors.attention)
        Gap(8)
        dev.krishnagurram.kassa.ui.importer.PrimaryButton("Replace with this backup") { confirmRestore = true }
        if (confirmRestore) dev.krishnagurram.kassa.ui.common.ConfirmDialog(
            title = "Replace everything in Kassa?",
            body = "All accounts, entries and settings in Kassa now are replaced by this backup. This can't be undone.",
            confirm = "Replace", onConfirm = { actions.restore(json); pending = null; status = "Restoring..." }, onDismiss = { confirmRestore = false },
        )
        TextButton(onClick = { pending = null }) { Text("Cancel", color = Kassa.colors.ink2) }
    }
    Gap(24)
}
