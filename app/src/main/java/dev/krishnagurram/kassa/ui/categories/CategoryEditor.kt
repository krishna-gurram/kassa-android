package dev.krishnagurram.kassa.ui.categories

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krishnagurram.kassa.domain.Budgets
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.CategoryIcon
import dev.krishnagurram.kassa.domain.CategoryKind
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.CategoryBadge
import dev.krishnagurram.kassa.ui.common.CategoryIconView
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.entry.SearchBox
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import kotlinx.coroutines.launch

/** Everything the editor can change, handed back on save. [budget] is the default monthly amount, null for none. */
data class CategoryEdit(val category: Category, val budget: Long?, val thisMonthBudget: Long?, val clearThisMonth: Boolean)

/**
 * Create or edit a category: name, icon (emoji, built-in symbol, or a photo), color, monthly budget,
 * whether it counts toward today's number, subcategories, and hiding or merging it away.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryEditor(
    ledger: Ledger,
    original: Category,
    onSave: (CategoryEdit) -> Unit,
    onAddSub: (Category, String) -> Unit,
    onRenameSub: (Category) -> Unit,
    onMerge: (Category, Category) -> Unit,
    savePhoto: suspend (android.net.Uri) -> String?,
) {
    val isNew = original.id == 0L
    val isTop = original.parentId == null
    var name by remember { mutableStateOf(original.name) }
    var icon by remember { mutableStateOf(original.icon) }
    var color by remember { mutableStateOf(original.color) }
    var counts by remember { mutableStateOf(original.countsTowardDaily) }
    var hidden by remember { mutableStateOf(original.hidden) }
    val month = ledger.period.yearMonth
    val defaultBudget = if (isNew) null else ledger.budgets.firstOrNull { it.categoryId == original.id && it.month == 0 }?.amount
    val monthBudget = if (isNew) null else ledger.budgets.firstOrNull { it.categoryId == original.id && it.month == Budgets.key(month) }?.amount
    var budget by remember { mutableStateOf(defaultBudget?.let { Money.format(it).removePrefix("$").replace(",", "") } ?: "") }
    var thisMonth by remember { mutableStateOf(monthBudget?.let { Money.format(it).removePrefix("$").replace(",", "") } ?: "") }
    var iconTab by remember { mutableStateOf(when (original.icon) { is CategoryIcon.Symbol -> 1; is CategoryIcon.Image -> 2; else -> 0 }) }
    var iconQuery by remember { mutableStateOf("") }
    var typedEmoji by remember { mutableStateOf("") }
    var newSub by remember { mutableStateOf("") }
    var mergeOpen by remember { mutableStateOf(false) }
    var mergeInto by remember { mutableStateOf<Category?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { savePhoto(uri)?.let { icon = CategoryIcon.Image(it) } ?: run { error = "Couldn't read that picture." } }
    }
    val preview = original.copy(name = name.ifBlank { "Category" }, icon = icon, color = color)
    val subs = ledger.categories.filter { it.parentId == original.id && original.id != 0L }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(preview, size = 56)
            Column(Modifier.padding(start = 14.dp)) {
                Text(name.ifBlank { "New category" }, style = KassaType.section, color = Kassa.colors.ink)
                Text(if (original.kind == CategoryKind.INCOME) "Income" else if (isTop) "Expense category" else "Subcategory", style = KassaType.small, color = Kassa.colors.ink2)
            }
        }
        OutlinedTextField(name, { name = it; error = null }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth().padding(top = 14.dp))

        SectionHeader("Icon")
        Row(Modifier.clip(RoundedCornerShape(12.dp)).background(Kassa.colors.chip).padding(3.dp)) {
            listOf("Emoji", "Icons", "Photo").forEachIndexed { i, label ->
                val on = iconTab == i
                Box(
                    Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (on) Kassa.colors.raised else Color.Transparent)
                        .clickable { iconTab = i }.padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(label, style = KassaType.bodyStrong, color = if (on) Kassa.colors.ink else Kassa.colors.ink2) }
            }
        }
        when (iconTab) {
            0 -> {
                OutlinedTextField(
                    typedEmoji, { v -> typedEmoji = v; val t = v.trim(); if (t.isNotEmpty()) icon = CategoryIcon.Emoji(t) },
                    singleLine = true, label = { Text("Type or paste any emoji") }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                FlowRow(Modifier.padding(top = 8.dp)) {
                    IconCatalog.emoji.forEach { e ->
                        val on = icon == CategoryIcon.Emoji(e)
                        Box(
                            Modifier.padding(2.dp).size(44.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (on) Kassa.colors.chip else Color.Transparent).clickable { icon = CategoryIcon.Emoji(e) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 22.sp) }
                    }
                }
            }
            1 -> {
                SearchBox(iconQuery, { iconQuery = it }, "Search icons, like fuel, baby or temple")
                FlowRow {
                    IconCatalog.search(iconQuery).forEach { ci ->
                        val on = icon == CategoryIcon.Symbol(ci.name)
                        Box(
                            Modifier.padding(2.dp).size(48.dp).clip(RoundedCornerShape(12.dp))
                                .background(if (on) Color(color).copy(alpha = 0.2f) else Color.Transparent)
                                .clickable { icon = CategoryIcon.Symbol(ci.name) },
                            contentAlignment = Alignment.Center,
                        ) { Icon(ci.vector, ci.name, tint = if (on) Color(color) else Kassa.colors.ink2) }
                    }
                }
            }
            else -> {
                Text("Pick a photo or logo from your phone. It's cropped to a circle and stored only in Kassa.", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 10.dp)) {
                    if (icon is CategoryIcon.Image) Box(Modifier.padding(end = 12.dp)) { CategoryIconView(icon, Color(color), 56) }
                    TextButton(onClick = { photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }) {
                        Text(if (icon is CategoryIcon.Image) "Choose a different photo" else "Choose a photo", color = Kassa.colors.ink)
                    }
                }
            }
        }

        if (isTop) {
            SectionHeader("Color")
            FlowRow {
                IconCatalog.colors.forEach { c ->
                    val on = c == color
                    Box(
                        Modifier.padding(4.dp).size(34.dp).clip(CircleShape).background(Color(c))
                            .then(if (on) Modifier.border(3.dp, Kassa.colors.ink, CircleShape) else Modifier)
                            .clickable { color = c },
                    )
                }
            }
        }

        if (isTop && original.kind == CategoryKind.EXPENSE) {
            SectionHeader("Budget")
            OutlinedTextField(budget, { budget = it }, singleLine = true, label = { Text("Every month") }, placeholder = { Text("No budget") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth())
            if (!isNew) OutlinedTextField(thisMonth, { thisMonth = it }, singleLine = true,
                label = { Text("Just for ${month.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault())}") },
                placeholder = { Text("Same as every month") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
            Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Counts toward today's number", style = KassaType.body, color = Kassa.colors.ink)
                    Text("Turn off for bills like mortgage or phone.", style = KassaType.small, color = Kassa.colors.ink2)
                }
                Switch(counts, { counts = it }, colors = SwitchDefaults.colors(checkedTrackColor = Kassa.colors.ink, checkedThumbColor = Kassa.colors.onInk))
            }
        }

        if (isTop && !isNew) {
            SectionHeader("Subcategories", "${subs.count { !it.hidden }}")
            subs.sortedWith(compareBy({ it.hidden }, { it.sortOrder })).forEach { s ->
                Row(Modifier.fillMaxWidth().clickable { onRenameSub(s) }.padding(vertical = 12.dp)) {
                    Text(s.name, style = KassaType.body, color = if (s.hidden) Kassa.colors.ink3 else Kassa.colors.ink, modifier = Modifier.weight(1f))
                    Text(if (s.hidden) "Hidden" else "Edit", style = KassaType.small, color = Kassa.colors.ink2)
                }
                HorizontalDivider(color = Kassa.colors.line)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(newSub, { newSub = it }, singleLine = true, label = { Text("Add a subcategory") }, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    val n = newSub.trim()
                    error = when {
                        n.isEmpty() -> "Type the subcategory's name."
                        subs.any { it.name.equals(n, ignoreCase = true) } -> "$n is already here."
                        else -> null
                    }
                    if (error == null) { onAddSub(original, n); newSub = "" }
                }) { Text("Add", color = Kassa.colors.ink) }
            }
        }

        if (!isNew) {
            Row(Modifier.fillMaxWidth().padding(top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Hide", style = KassaType.body, color = Kassa.colors.ink)
                    Text("Hidden categories leave the pickers. Past entries keep them.", style = KassaType.small, color = Kassa.colors.ink2)
                }
                Switch(hidden, { hidden = it }, colors = SwitchDefaults.colors(checkedTrackColor = Kassa.colors.ink, checkedThumbColor = Kassa.colors.onInk))
            }
            TextButton(onClick = { mergeOpen = !mergeOpen }) { Text("Move all entries into another category", color = Kassa.colors.ink2) }
            if (mergeOpen) {
                val targets = ledger.categories.filter { it.kind == original.kind && it.id != original.id && !it.hidden && (it.parentId == null) == isTop }
                Text("Every entry moves, then ${original.name} is hidden. This can't be undone.", style = KassaType.small, color = Kassa.colors.attention)
                FlowRow(Modifier.padding(top = 6.dp)) { targets.forEach { t -> Chip(t.name, selected = false) { mergeInto = t } } }
            }
            mergeInto?.let { t ->
                dev.krishnagurram.kassa.ui.common.ConfirmDialog(
                    title = "Move everything into ${t.name}?",
                    body = "Every ${original.name} entry moves to ${t.name}, then ${original.name} is hidden. This can't be undone.",
                    confirm = "Move entries", onConfirm = { onMerge(original, t) }, onDismiss = { mergeInto = null },
                )
            }
        }

        error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention, modifier = Modifier.padding(top = 10.dp)) }
        Gap(20)
        PrimaryButton(if (isNew) "Add category" else "Save") {
            val b = budget.trim().takeIf { it.isNotEmpty() }?.let { Money.parseCents(it) }
            val m = thisMonth.trim().takeIf { it.isNotEmpty() }?.let { Money.parseCents(it) }
            error = when {
                name.isBlank() -> "Give it a name."
                budget.isNotBlank() && b == null -> "The budget isn't a number."
                thisMonth.isNotBlank() && m == null -> "This month's budget isn't a number."
                else -> null
            }
            if (error == null) onSave(
                CategoryEdit(
                    original.copy(name = name.trim(), icon = icon, color = color, countsTowardDaily = counts, hidden = hidden),
                    budget = b, thisMonthBudget = m, clearThisMonth = thisMonth.isBlank() && monthBudget != null,
                )
            )
        }
        Gap(24)
    }
}
