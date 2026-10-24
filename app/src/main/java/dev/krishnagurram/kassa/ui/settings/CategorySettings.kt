package dev.krishnagurram.kassa.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import dev.krishnagurram.kassa.domain.Budgets
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.CategoryKind
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Names
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.categories.CategoryEdit
import dev.krishnagurram.kassa.ui.categories.IconCatalog
import dev.krishnagurram.kassa.ui.common.CategoryBadge
import dev.krishnagurram.kassa.ui.common.EntryRow
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.importer.SecondaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

/** Expense or income categories A to Z with their budgets; archived ones in their own group at the end. */
@Composable
internal fun CategoryList(ledger: Ledger, kind: CategoryKind, open: (Category) -> Unit) {
    val month = ledger.period.yearMonth
    val tops = ledger.categories.filter { it.kind == kind && it.parentId == null }.sortedWith(Names.order { it.name })
    val (active, archived) = tops.partition { !it.hidden }
    if (kind == CategoryKind.EXPENSE) {
        val total = active.sumOf { Budgets.amountFor(it.id, month, ledger.budgets) ?: 0 }
        Text("Budgets this month add up to ${Money.format(total, wholeDollars = true)}.", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(bottom = 8.dp))
    }
    SecondaryButton("New category", { open(Category(name = "", kind = kind, color = IconCatalog.colors[tops.size % IconCatalog.colors.size])) })
    Gap(8)
    active.forEachIndexed { i, c -> CategoryRow(ledger, c, last = i == active.lastIndex) { open(c) } }
    if (archived.isNotEmpty()) {
        SectionHeader("Archived", "${archived.size}")
        archived.forEachIndexed { i, c -> CategoryRow(ledger, c, last = i == archived.lastIndex) { open(c) } }
    }
}

@Composable
private fun CategoryRow(ledger: Ledger, c: Category, last: Boolean, onClick: () -> Unit) {
    val expense = c.kind == CategoryKind.EXPENSE
    val subs = ledger.categories.count { it.parentId == c.id && !it.hidden }
    val budget = if (c.hidden) null else Budgets.amountFor(c.id, ledger.period.yearMonth, ledger.budgets)
    val detail = listOfNotNull(
        if (subs > 0) "$subs sub" else null,
        if (expense && !c.countsTowardDaily) "bill" else null,
    ).joinToString(", ").ifEmpty { if (expense) "everyday" else "" }
    EntryRow(
        badge = { CategoryBadge(c) },
        title = c.name, subtitle = detail,
        amount = if (expense && !c.hidden) budget?.let { Money.format(it, wholeDollars = true) } ?: "No budget" else "",
        amountColor = if (budget == null) Kassa.colors.ink3 else Kassa.colors.ink,
        note = if (expense && budget != null) "a month" else null,
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
