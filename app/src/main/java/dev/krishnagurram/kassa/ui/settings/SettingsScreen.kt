package dev.krishnagurram.kassa.ui.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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
    suspend fun savePhoto(uri: android.net.Uri): String?
    fun saveRecurring(r: dev.krishnagurram.kassa.domain.Recurring)
    fun deleteRecurring(id: Long)
    fun setAlertApp(pkg: String, on: Boolean)
    fun addRule(pkg: String, last4: String?, accountId: Long)
    fun deleteRule(id: Long)
    fun openNotificationAccess()
    fun hasNotificationAccess(): Boolean
    fun appLabel(pkg: String): String
}

private sealed interface Page {
    data object Main : Page
    data class Categories(val kind: CategoryKind) : Page
    data class Edit(val category: Category, val back: Page) : Page
    data object Splitwise : Page
    data object Recurring : Page
    data object Alerts : Page
}

@Composable
fun SettingsScreen(ledger: Ledger, actions: SettingsActions, alertApps: Set<String>, seenApps: Set<String>, onClose: () -> Unit) {
    var page by remember { mutableStateOf<Page>(Page.Main) }
    val back: () -> Unit = {
        page = when (val p = page) {
            Page.Main -> { onClose(); Page.Main }
            is Page.Categories, Page.Splitwise, Page.Recurring, Page.Alerts -> Page.Main
            is Page.Edit -> p.back
        }
    }
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        BackHandler(onBack = back)
        Surface(Modifier.fillMaxSize(), color = Kassa.colors.ground) {
            Column(Modifier.fillMaxSize().safeDrawingPadding(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = back) { Icon(Chevron(left = true), "Back", tint = Kassa.colors.ink) }
                        Text(
                            when (val p = page) {
                                Page.Main -> "Settings"
                                is Page.Categories -> if (p.kind == CategoryKind.EXPENSE) "Expense categories" else "Income categories"
                                is Page.Edit -> if (p.category.id == 0L) "New category" else "Edit category"
                                Page.Splitwise -> "Splitwise cleanup"
                                Page.Recurring -> "Recurring bills"
                                Page.Alerts -> "Bank notifications"
                            },
                            style = KassaType.section, color = Kassa.colors.ink,
                        )
                    }
                    when (val p = page) {
                        Page.Main -> Main(ledger, actions, open = { page = it })
                        is Page.Categories -> CategoryList(ledger, p.kind, open = { page = Page.Edit(it, p) })
                        is Page.Edit -> CategoryEditor(
                            ledger = ledger,
                            original = p.category,
                            onSave = { edit -> save(edit, actions, ledger); page = p.back },
                            onAddSub = { parent, name ->
                                actions.saveCategory(Category(name = name, kind = parent.kind, parentId = parent.id, icon = parent.icon, color = parent.color, countsTowardDaily = parent.countsTowardDaily), null)
                            },
                            onRenameSub = { sub -> page = Page.Edit(sub, p) },
                            onMerge = { from, into -> actions.mergeCategory(from, into); page = p.back },
                            savePhoto = actions::savePhoto,
                        )
                        Page.Splitwise -> SplitwiseCleanup(ledger, actions)
                        Page.Recurring -> RecurringPage(ledger, actions)
                        Page.Alerts -> AlertsPage(ledger, actions, alertApps, seenApps)
                    }
                }
            }
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Main(ledger: Ledger, actions: SettingsActions, open: (Page) -> Unit) {
    val expenses = ledger.categories.count { it.kind == CategoryKind.EXPENSE && it.parentId == null && !it.hidden }
    val incomes = ledger.categories.count { it.kind == CategoryKind.INCOME && it.parentId == null && !it.hidden }
    val splitwise = splitwiseAccounts(ledger)

    SectionHeader("Categories and budgets")
    NavRow("Expense categories", "$expenses, with budgets") { open(Page.Categories(CategoryKind.EXPENSE)) }
    NavRow("Income categories", "$incomes") { open(Page.Categories(CategoryKind.INCOME)) }

    SectionHeader("Assist")
    NavRow("Recurring bills", "${ledger.assist.recurring.size} set up. Each asks before it's added.") { open(Page.Recurring) }
    NavRow("Bank notifications", if (actions.hasNotificationAccess()) "${alertApps.size} apps turned on" else "Off. Turn on to capture purchases as you make them.") { open(Page.Alerts) }

    SectionHeader("Budget month")
    Text("Your month starts on day ${ledger.monthStartDay}. Pick payday if you budget from one paycheck to the next.", style = KassaType.small, color = Kassa.colors.ink2)
    FlowRow(Modifier.padding(top = 8.dp)) {
        listOf(1, 5, 10, 15, 20, 25).forEach { d -> Chip("Day $d", selected = ledger.monthStartDay == d) { actions.setMonthStartDay(d) } }
    }
    var custom by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(custom, { custom = it.filter(Char::isDigit).take(2) }, singleLine = true, label = { Text("Another day, 1 to 28") }, modifier = Modifier.weight(1f))
        TextButton(onClick = { custom.toIntOrNull()?.takeIf { it in 1..28 }?.let { actions.setMonthStartDay(it); custom = "" } }) { Text("Set", color = Kassa.colors.ink) }
    }

    if (splitwise.isNotEmpty()) {
        SectionHeader("Cleanup")
        NavRow("Splitwise accounts", "${splitwise.size} to turn into people") { open(Page.Splitwise) }
    }

    SectionHeader("Data")
    SecondaryButton("Import again from Money Manager", actions::importAgain)
    Gap(16)
    Text(
        "Kassa has no internet permission, so it can't send anything anywhere. Your data is in an encrypted database on this phone, excluded from Google backups. Version ${BuildConfigInfo.versionName}.",
        style = KassaType.small, color = Kassa.colors.ink2,
    )
    Gap(24)
}

@Composable
private fun NavRow(title: String, sub: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = KassaType.bodyStrong, color = Kassa.colors.ink)
                Text(sub, style = KassaType.small, color = Kassa.colors.ink2)
            }
            Icon(Chevron(left = false), null, tint = Kassa.colors.ink3)
        }
        HorizontalDivider(color = Kassa.colors.line)
    }
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
    tops.forEachIndexed { i, c ->
        val subs = ledger.categories.count { it.parentId == c.id && !it.hidden }
        val budget = Budgets.amountFor(c.id, month, ledger.budgets)
        val detail = listOfNotNull(
            if (subs > 0) "$subs sub" else null,
            if (kind == CategoryKind.EXPENSE && !c.countsTowardDaily) "bill" else null,
            if (c.hidden) "hidden" else null,
        ).joinToString(", ").ifEmpty { if (kind == CategoryKind.EXPENSE) "everyday" else "" }
        EntryRow(
            badge = { CategoryBadge(c) },
            title = c.name, subtitle = detail,
            amount = if (kind == CategoryKind.EXPENSE) budget?.let { Money.format(it, wholeDollars = true) } ?: "No budget" else "",
            amountColor = if (budget == null) Kassa.colors.ink3 else Kassa.colors.ink,
            note = if (kind == CategoryKind.EXPENSE && budget != null) "a month" else null,
            showDivider = i < tops.lastIndex,
            modifier = Modifier.clickable { open(c) },
        )
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
    if (accounts.isEmpty()) Text("Nothing left to clean up.", style = KassaType.body, color = Kassa.colors.ink2)
    accounts.forEach { a ->
        val bal = ledger.accounts.balances[a.id] ?: 0
        var name by remember(a.id) { mutableStateOf("Splitwise friends") }
        Column(Modifier.padding(vertical = 10.dp)) {
            EntryRow(
                badge = { GlyphBadge(a.name.take(1)) },
                title = a.name,
                subtitle = when { bal > 0 -> "They owe you"; bal < 0 -> "You owe them"; else -> "Settled" },
                amount = Money.format(Math.abs(bal)), showDivider = false,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Person name") }, modifier = Modifier.weight(1f))
                TextButton(onClick = { if (name.isNotBlank()) actions.convertToPerson(a, bal, name) }) { Text("Convert", color = Kassa.colors.ink) }
            }
            HorizontalDivider(color = Kassa.colors.line, modifier = Modifier.padding(top = 10.dp))
        }
    }
    Gap(24)
}
