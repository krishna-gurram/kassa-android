package app.kassa.money.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kassa.money.R
import app.kassa.money.domain.Budgets
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryIcon
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.Money
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.common.CategoryBadge
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.ui.common.CategoryIconView
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.common.SearchBox
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.importer.SecondaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType

/** Everything the editor can change, handed back on save. [budget] is the default monthly amount, null for none. */
data class CategoryEdit(val category: Category, val budget: Long?, val thisMonthBudget: Long?, val clearThisMonth: Boolean)

/**
 * Create or edit a category: name, icon (an emoji or a built-in symbol), color, monthly budget,
 * whether it counts toward today's number, subcategories, and hiding or merging it away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryEditor(
    ledger: Ledger,
    original: Category,
    onSave: (CategoryEdit) -> Unit,
    onAddSub: (Category, String) -> Unit,
    /** Saves a subcategory; quiet when it's just reordering. */
    onSaveSub: (Category, Boolean) -> Unit,
    onMergeSub: (Category, Category) -> Unit,
    onMerge: (Category, Category) -> Unit,
) {
    val context = LocalContext.current
    val isNew = original.id == 0L
    val isTop = original.parentId == null
    var name by remember { mutableStateOf(original.name) }
    var icon by remember { mutableStateOf(original.icon) }
    var color by remember { mutableStateOf(original.color) }
    var counts by remember { mutableStateOf(original.countsTowardDaily) }
    val hidden = original.hidden
    var confirmArchive by remember { mutableStateOf(false) }
    val month = ledger.period.yearMonth
    val defaultBudget = if (isNew) null else ledger.budgets.firstOrNull { it.categoryId == original.id && it.month == 0 }?.amount
    val monthBudget = if (isNew) null else ledger.budgets.firstOrNull { it.categoryId == original.id && it.month == Budgets.key(month) }?.amount
    var budget by remember { mutableStateOf(defaultBudget?.let { Money.toInput(it) } ?: "") }
    var thisMonth by remember { mutableStateOf(monthBudget?.let { Money.toInput(it) } ?: "") }
    var iconTab by remember { mutableStateOf(if (original.icon is CategoryIcon.Symbol) 1 else 0) }
    var iconQuery by remember { mutableStateOf("") }
    var typedEmoji by remember { mutableStateOf("") }
    var mergeOpen by remember { mutableStateOf(false) }
    var mergeInto by remember { mutableStateOf<Category?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val preview = original.copy(name = name.ifBlank { stringResource(R.string.categories_default_name) }, icon = icon, color = color)
    // Names are unique among siblings: other top-level categories of the kind, or other subcategories of the parent.
    val siblings = ledger.categories.filter { it.id != original.id && it.parentId == original.parentId && it.kind == original.kind }
    val nameProblem = InputRules.nameProblem(name, if (isTop) "category" else "subcategory", InputRules.CATEGORY_NAME_MAX,
        taken = { n -> siblings.any { it.name.equals(n, ignoreCase = true) } })

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(preview, size = 56)
            Column(Modifier.padding(start = 14.dp)) {
                Text(name.ifBlank { stringResource(R.string.categories_new_category) }, style = KassaType.section, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (original.kind == CategoryKind.INCOME) stringResource(R.string.categories_income) else if (isTop) stringResource(R.string.categories_expense_category) else stringResource(R.string.categories_subcategory), style = KassaType.small, color = Kassa.colors.ink2)
            }
        }
        OutlinedTextField(
            name, { name = InputRules.typed(it, InputRules.CATEGORY_NAME_MAX); error = null }, singleLine = true, label = { Text(stringResource(R.string.categories_name)) },
            supportingText = { Text(stringResource(R.string.categories_name_counter, name.length, InputRules.CATEGORY_NAME_MAX)) },
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        )

        SectionHeader(stringResource(R.string.categories_icon))
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Kassa.colors.chip).padding(3.dp)) {
            listOf(stringResource(R.string.categories_tab_emoji), stringResource(R.string.categories_tab_icons)).forEachIndexed { i, label ->
                val on = iconTab == i
                Box(
                    Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(9.dp)).background(if (on) Kassa.colors.raised else Color.Transparent)
                        .selectable(selected = on, role = Role.Tab) { iconTab = i },
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = KassaType.bodyStrong, color = if (on) Kassa.colors.ink else Kassa.colors.ink2) }
            }
        }
        when (iconTab) {
            0 -> {
                OutlinedTextField(
                    typedEmoji, { v -> typedEmoji = InputRules.typedEmoji(v); if (typedEmoji.isNotEmpty()) icon = CategoryIcon.Emoji(typedEmoji) },
                    singleLine = true, label = { Text(stringResource(R.string.categories_emoji_label)) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                FlowRow(Modifier.padding(top = 8.dp)) {
                    IconCatalog.emoji.forEach { e ->
                        val on = icon == CategoryIcon.Emoji(e)
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (on) Kassa.colors.chip else Color.Transparent).clickable(role = Role.Button) { icon = CategoryIcon.Emoji(e) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 22.sp) }
                    }
                }
            }
            else -> {
                SearchBox(iconQuery, { iconQuery = it }, stringResource(R.string.categories_icon_search))
                FlowRow {
                    IconCatalog.search(iconQuery).forEach { ci ->
                        val on = icon == CategoryIcon.Symbol(ci.name)
                        Box(
                            Modifier.size(48.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (on) Color(color).copy(alpha = 0.2f) else Color.Transparent)
                                .clickable(role = Role.Button) { icon = CategoryIcon.Symbol(ci.name) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(ci.vector, ci.name, tint = if (on) Color(color) else Kassa.colors.ink2) }
                    }
                }
            }
        }

        if (isTop) {
            SectionHeader(stringResource(R.string.categories_color))
            // Any color can be picked. A dot marks colors other categories of this kind use, and the line under
            // the colors names who shares the picked one.
            val usedBy = app.kassa.money.domain.CategoryColors.usedBy(ledger.categories, original.kind, original.id)
            FlowRow {
                IconCatalog.colors.forEach { c ->
                    val on = c == color
                    val used = c in usedBy
                    val colorDescription = when {
                        on && used -> stringResource(R.string.categories_color_picked_shared, usedBy[c].orEmpty().joinToString())
                        on -> stringResource(R.string.categories_color_picked)
                        used -> stringResource(R.string.categories_color_shared, usedBy[c].orEmpty().joinToString())
                        else -> stringResource(R.string.categories_color)
                    }
                    // The whole 48dp cell is the target; the swatch is drawn 40dp inside it.
                    Box(
                        Modifier.size(48.dp).clickable(role = Role.RadioButton) { color = c }
                            .semantics { contentDescription = colorDescription },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(Modifier.size(40.dp)) {
                            Box(
                                Modifier.fillMaxSize().clip(CircleShape).background(Color(c))
                                    .then(if (on) Modifier.border(3.dp, Kassa.colors.ink, CircleShape) else Modifier),
                            )
                            if (used) Box(
                                Modifier.align(Alignment.BottomEnd).size(12.dp).clip(CircleShape).background(Kassa.colors.ground)
                                    .padding(2.dp).clip(CircleShape).background(Kassa.colors.ink3),
                            )
                        }
                    }
                }
            }
            usedBy[color]?.let { names ->
                Text(stringResource(R.string.categories_also_used_by, names.sortedWith(app.kassa.money.domain.Names.order<String> { it }).joinToString()), style = KassaType.small, color = Kassa.colors.ink2,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            }
        }

        if (isTop && original.kind == CategoryKind.EXPENSE) {
            SectionHeader(stringResource(R.string.categories_budget))
            OutlinedTextField(budget, { budget = InputRules.typedAmount(it); error = null }, singleLine = true, label = { Text(stringResource(R.string.categories_budget_every_month)) }, placeholder = { Text(stringResource(R.string.categories_budget_none)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            if (!isNew) OutlinedTextField(thisMonth, { thisMonth = InputRules.typedAmount(it); error = null }, singleLine = true,
                label = { Text(stringResource(R.string.categories_budget_just_for, month.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()))) },
                placeholder = { Text(stringResource(R.string.categories_budget_same)) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.categories_counts_daily), style = KassaType.body, color = Kassa.colors.ink)
                    Text(stringResource(R.string.categories_counts_daily_hint), style = KassaType.small, color = Kassa.colors.ink2)
                }
                Switch(counts, { counts = it }, colors = SwitchDefaults.colors(checkedTrackColor = Kassa.colors.ink, checkedThumbColor = Kassa.colors.onInk))
            }
        }

        if (isTop && !isNew) SubcategoryList(ledger, original, onSave = onSaveSub, onAdd = { n -> onAddSub(original, n) }, onMerge = onMergeSub)

        if (!isNew) {
            TextButton(onClick = { mergeOpen = !mergeOpen }) { Text(stringResource(R.string.categories_move_all), color = Kassa.colors.ink2) }
            if (mergeOpen) {
                val targets = ledger.categories.filter { it.kind == original.kind && it.id != original.id && !it.hidden && (it.parentId == null) == isTop }
                Text(stringResource(R.string.categories_move_all_warning, original.name), style = KassaType.small, color = Kassa.colors.attention)
                FlowRow(Modifier.padding(top = 6.dp)) { targets.forEach { t -> Chip(t.name, selected = false) { mergeInto = t } } }
            }
            mergeInto?.let { t ->
                ConfirmDialog(
                    title = stringResource(R.string.categories_move_title, t.name),
                    body = stringResource(R.string.categories_move_body, original.name, t.name),
                    confirm = stringResource(R.string.categories_move_confirm), onConfirm = { onMerge(original, t) }, onDismiss = { mergeInto = null },
                )
            }
        }

        error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention, modifier = Modifier.padding(top = 10.dp)) }
        Gap(20)
        /** Checks the form and saves it, archived or not. */
        fun submit(archive: Boolean) {
            val b = budget.trim().takeIf { it.isNotEmpty() }?.let { Money.parseCents(it) }
            val m = thisMonth.trim().takeIf { it.isNotEmpty() }?.let { Money.parseCents(it) }
            error = nameProblem
                ?: b?.let { InputRules.amountProblem(it) }?.let { context.getString(R.string.categories_budget_error, it) }
                ?: m?.let { InputRules.amountProblem(it) }?.let { context.getString(R.string.categories_month_budget_error, it) }
            if (error == null) onSave(
                CategoryEdit(
                    original.copy(name = InputRules.clean(name), icon = icon, color = color, countsTowardDaily = counts, hidden = archive),
                    budget = b, thisMonthBudget = m, clearThisMonth = thisMonth.isBlank() && monthBudget != null,
                )
            )
        }
        PrimaryButton(if (isNew) stringResource(R.string.categories_add_category) else if (hidden) stringResource(R.string.categories_restore) else stringResource(R.string.categories_save)) { submit(archive = false) }
        if (!isNew && !hidden) {
            Gap(8)
            SecondaryButton(stringResource(R.string.categories_archive), { confirmArchive = true })
        }
        if (confirmArchive) ConfirmDialog(
            title = stringResource(R.string.categories_archive_title, original.name),
            body = stringResource(R.string.categories_archive_body),
            confirm = stringResource(R.string.categories_archive), onConfirm = { confirmArchive = false; submit(archive = true) }, onDismiss = { confirmArchive = false },
        )
        Gap(24)
    }
}
