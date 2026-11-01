package app.kassa.money.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import app.kassa.money.R
import app.kassa.money.domain.Budgets
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.Money
import app.kassa.money.domain.Names
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.categories.CategoryEdit
import app.kassa.money.ui.categories.IconCatalog
import app.kassa.money.ui.common.CategoryBadge
import app.kassa.money.ui.common.EntryRow
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.importer.SecondaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/** Expense or income categories A to Z with their budgets; archived ones in their own group at the end. */
@Composable
internal fun CategoryList(ledger: Ledger, kind: CategoryKind, open: (Category) -> Unit) {
    val month = ledger.period.yearMonth
    val tops = ledger.categories.filter { it.kind == kind && it.parentId == null }.sortedWith(Names.order { it.name })
    val (active, archived) = tops.partition { !it.hidden }
    if (kind == CategoryKind.EXPENSE) {
        val total = active.sumOf { Budgets.amountFor(it.id, month, ledger.budgets) ?: 0 }
        Text(stringResource(R.string.settings_categories_budget_total, Money.format(total, wholeDollars = true)), style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(bottom = 8.dp))
    }
    SecondaryButton(stringResource(R.string.settings_categories_new), { open(Category(name = "", kind = kind, color = app.kassa.money.domain.CategoryColors.next(ledger.categories, kind))) })
    Gap(8)
    active.forEachIndexed { i, c -> CategoryRow(ledger, c, last = i == active.lastIndex) { open(c) } }
    if (archived.isNotEmpty()) {
        SectionHeader(stringResource(R.string.settings_categories_archived), "${archived.size}")
        archived.forEachIndexed { i, c -> CategoryRow(ledger, c, last = i == archived.lastIndex) { open(c) } }
    }
}

@Composable
private fun CategoryRow(ledger: Ledger, c: Category, last: Boolean, onClick: () -> Unit) {
    val expense = c.kind == CategoryKind.EXPENSE
    val subs = ledger.categories.count { it.parentId == c.id && !it.hidden }
    val budget = if (c.hidden) null else Budgets.amountFor(c.id, ledger.period.yearMonth, ledger.budgets)
    val detail = listOfNotNull(
        if (subs > 0) stringResource(R.string.settings_categories_subs, subs) else null,
        if (expense && !c.countsTowardDaily) stringResource(R.string.settings_categories_bill) else null,
    ).joinToString(", ").ifEmpty { if (expense) stringResource(R.string.settings_categories_everyday) else "" }
    EntryRow(
        badge = { CategoryBadge(c) },
        title = c.name, subtitle = detail,
        amount = if (expense && !c.hidden) budget?.let { Money.format(it, wholeDollars = true) } ?: stringResource(R.string.settings_categories_no_budget) else "",
        amountColor = if (budget == null) Kassa.colors.ink3 else Kassa.colors.ink,
        note = if (expense && budget != null) stringResource(R.string.settings_categories_a_month) else null,
        showDivider = !last,
        modifier = Modifier.clickable(onClick = onClick),
    )
}

/** Saves the editor's result: the category, its budgets, and its color on every subcategory. */
internal fun saveCategoryEdit(edit: CategoryEdit, actions: SettingsActions, ledger: Ledger) {
    actions.saveCategory(edit.category, edit.budget) { id ->
        when {
            edit.thisMonthBudget != null -> actions.setMonthBudget(id, edit.thisMonthBudget)
            edit.clearThisMonth -> actions.setMonthBudget(id, null)
        }
    }
    if (edit.category.parentId == null && edit.category.id != 0L) {
        ledger.categories.filter { it.parentId == edit.category.id && it.color != edit.category.color }
            .forEach { actions.saveCategory(it.copy(color = edit.category.color), null, quiet = true) }
    }
}
