package dev.krishnagurram.kassa.ui.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.ConfirmDialog
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.Instant

/**
 * A category's subcategories, edited where they are: tap a name to rename it, ⋯ to move it up or down, move it to
 * another category, merge it or archive it. Each shows this month's amount and entries. "Add subcategory" stays
 * open after each one, so several can be typed in a row. Every change saves right away.
 */
@Composable
fun SubcategoryList(
    ledger: Ledger,
    parent: Category,
    onSave: (Category) -> Unit,
    onAdd: (String) -> Unit,
    onMerge: (from: Category, into: Category) -> Unit,
) {
    val all = ledger.categories.filter { it.parentId == parent.id }
    val active = all.filter { !it.hidden }.sortedWith(compareBy({ it.sortOrder }, { it.name.lowercase() }))
    val archived = all.filter { it.hidden }.sortedBy { it.name.lowercase() }
    var renaming by remember { mutableStateOf<Long?>(null) }
    var adding by remember { mutableStateOf(false) }
    var showArchived by remember { mutableStateOf(false) }
    var confirmArchive by remember { mutableStateOf<Category?>(null) }
    var merging by remember { mutableStateOf<Pair<Category, Category>?>(null) }

    // This month's figures per subcategory.
    val p = ledger.period
    val figures = HashMap<Long, Pair<Long, Int>>()
    for (t in ledger.recent) {
        if (t.kind != TxKind.EXPENSE && t.kind != TxKind.INCOME) continue
        val id = t.categoryId ?: continue
        val d = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate()
        if (d !in p) continue
        val (a, n) = figures[id] ?: (0L to 0)
        figures[id] = (a + t.categoryAmount) to (n + 1)
    }
    fun taken(name: String, except: Long?) = all.any { it.id != except && it.name.equals(name.trim(), ignoreCase = true) }

    SectionHeader("Subcategories", "${active.size}")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Kassa.colors.raised).padding(horizontal = 12.dp)) {
        active.forEachIndexed { i, s ->
            if (renaming == s.id) {
                NameField(s.name, "Name", button = "Save", keepOpen = false, error = { n -> if (taken(n, s.id)) "$n is already here." else null },
                    onDone = { n -> if (n != null && n != s.name) onSave(s.copy(name = n)); renaming = null })
            } else {
                var menu by remember(s.id) { mutableStateOf(false) }
                var moveMenu by remember(s.id) { mutableStateOf(false) }
                var mergeMenu by remember(s.id) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.name, style = KassaType.bodyStrong, color = Kassa.colors.ink,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable { renaming = s.id; adding = false }.padding(vertical = 10.dp),
                    )
                    val (amt, n) = figures[s.id] ?: (0L to 0)
                    Column(horizontalAlignment = Alignment.End) {
                        Text(Money.format(amt), style = KassaType.bodyStrong, color = if (n == 0) Kassa.colors.ink3 else Kassa.colors.ink)
                        Text("$n this month", style = KassaType.tiny, color = Kassa.colors.ink3)
                    }
                    Box {
                        Text("⋯", style = KassaType.section, color = Kassa.colors.ink2,
                            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { menu = true }.padding(horizontal = 10.dp, vertical = 6.dp))
                        DropdownMenu(menu, { menu = false }) {
                            DropdownMenuItem({ Text("Rename") }, { menu = false; renaming = s.id })
                            if (i > 0) DropdownMenuItem({ Text("Move up") }, { menu = false; swap(active, i, i - 1, onSave) })
                            if (i < active.lastIndex) DropdownMenuItem({ Text("Move down") }, { menu = false; swap(active, i, i + 1, onSave) })
                            DropdownMenuItem({ Text("Move to another category") }, { menu = false; moveMenu = true })
                            DropdownMenuItem({ Text("Merge into…") }, { menu = false; mergeMenu = true })
                            DropdownMenuItem({ Text("Archive", color = Kassa.colors.attention) }, { menu = false; confirmArchive = s })
                        }
                        DropdownMenu(moveMenu, { moveMenu = false }) {
                            ledger.categories.filter { it.parentId == null && it.kind == parent.kind && !it.hidden && it.id != parent.id }
                                .sortedBy { it.name.lowercase() }
                                .forEach { top -> DropdownMenuItem({ Text(top.name) }, { moveMenu = false; onSave(s.copy(parentId = top.id, color = top.color)) }) }
                        }
                        DropdownMenu(mergeMenu, { mergeMenu = false }) {
                            DropdownMenuItem({ Text("${parent.name}, no subcategory") }, { mergeMenu = false; merging = s to parent })
                            active.filter { it.id != s.id }.forEach { o -> DropdownMenuItem({ Text(o.name) }, { mergeMenu = false; merging = s to o }) }
                        }
                    }
                }
            }
            HorizontalDivider(color = Kassa.colors.line)
        }
        if (adding) NameField("", "New subcategory", button = "Add", keepOpen = true, error = { n -> if (taken(n, null)) "$n is already here." else null },
            onDone = { n -> if (n == null) adding = false else onAdd(n) })
        else Text(
            "+ Add subcategory", style = KassaType.bodyStrong, color = Kassa.colors.ink2,
            modifier = Modifier.fillMaxWidth().clickable { adding = true; renaming = null }.padding(vertical = 14.dp),
        )
    }
    if (archived.isNotEmpty()) {
        TextButton(onClick = { showArchived = !showArchived }) {
            Text((if (showArchived) "Hide archived" else "Archived") + " (${archived.size})", color = Kassa.colors.ink2)
        }
        if (showArchived) archived.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp, horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.name, style = KassaType.body, color = Kassa.colors.ink3, modifier = Modifier.weight(1f))
                TextButton(onClick = { onSave(s.copy(hidden = false)) }) { Text("Restore", color = Kassa.colors.ink) }
            }
        }
    }

    confirmArchive?.let { s ->
        ConfirmDialog(
            title = "Archive ${s.name}?", body = "It leaves the pickers. Past entries keep it, and you can restore it here.",
            confirm = "Archive", onConfirm = { onSave(s.copy(hidden = true)); confirmArchive = null }, onDismiss = { confirmArchive = null },
        )
    }
    merging?.let { (from, into) ->
        ConfirmDialog(
            title = "Merge ${from.name} into ${if (into.id == parent.id) parent.name else into.name}?",
            body = "Every ${from.name} entry moves there, then ${from.name} is archived. This can't be undone.",
            confirm = "Merge", onConfirm = { onMerge(from, into); merging = null }, onDismiss = { merging = null },
        )
    }
}

/** Swaps two neighbours' order, renumbering the list so the order is stable even when old values tie. */
private fun swap(list: List<Category>, a: Int, b: Int, onSave: (Category) -> Unit) {
    val order = list.toMutableList()
    val x = order[a]; order[a] = order[b]; order[b] = x
    order.forEachIndexed { i, c -> if (c.sortOrder != i) onSave(c.copy(sortOrder = i)) }
}

/** An inline name field. Enter or the button submits; with [keepOpen] it clears for the next one. Null means cancel. */
@Composable
private fun NameField(initial: String, label: String, button: String, keepOpen: Boolean, error: (String) -> String?, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var problem by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit: () -> Unit = {
        val n = text.trim()
        problem = if (n.isEmpty()) null else error(n)
        if (n.isEmpty()) onDone(null)
        else if (problem == null) { onDone(n); if (keepOpen) text = "" }
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = it; problem = null }, singleLine = true, label = { Text(label) },
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
                modifier = Modifier.weight(1f).focusRequester(focus),
            )
            TextButton(onClick = submit) { Text(button, color = Kassa.colors.ink) }
            TextButton(onClick = { onDone(null) }) { Text(if (keepOpen) "Done" else "Cancel", color = Kassa.colors.ink2) }
        }
        problem?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
    }
}
