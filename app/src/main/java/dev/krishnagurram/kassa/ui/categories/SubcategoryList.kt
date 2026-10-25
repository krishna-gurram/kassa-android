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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.InputRules
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Names
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.ConfirmDialog
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.Instant

/**
 * A category's subcategories, A to Z, edited where they are: tap a name to rename it, ⋯ to move it to another
 * category, merge it or archive it. Each shows this month's amount and entries. "Add subcategory" stays
 * open after each one, so several can be typed in a row. Every change saves right away.
 */
@Composable
fun SubcategoryList(
    ledger: Ledger,
    parent: Category,
    /** Saves a subcategory; the flag is true for quiet changes. */
    onSave: (Category, Boolean) -> Unit,
    onAdd: (String) -> Unit,
    onMerge: (from: Category, into: Category) -> Unit,
) {
    val all = ledger.categories.filter { it.parentId == parent.id }
    val byName = Names.order<Category> { it.name }
    val active = all.filter { !it.hidden }.sortedWith(byName)
    val archived = all.filter { it.hidden }.sortedWith(byName)
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
    fun problem(name: String, except: Long?) = InputRules.nameProblem(name, "subcategory", InputRules.CATEGORY_NAME_MAX,
        taken = { n -> all.any { it.id != except && it.name.equals(n, ignoreCase = true) } })

    SectionHeader("Subcategories", "${active.size}")
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Kassa.colors.raised).padding(horizontal = 12.dp)) {
        active.forEach { s ->
            if (renaming == s.id) {
                NameField(s.name, "Name", button = "Save", keepOpen = false, error = { n -> problem(n, s.id) },
                    onDone = { n -> if (n != null && n != s.name) onSave(s.copy(name = n), false); renaming = null })
            } else {
                var menu by remember(s.id) { mutableStateOf(false) }
                var moveMenu by remember(s.id) { mutableStateOf(false) }
                var mergeMenu by remember(s.id) { mutableStateOf(false) }
                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        s.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
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
                            DropdownMenuItem({ Text("Move to another category") }, { menu = false; moveMenu = true })
                            DropdownMenuItem({ Text("Merge into…") }, { menu = false; mergeMenu = true })
                            DropdownMenuItem({ Text("Archive", color = Kassa.colors.attention) }, { menu = false; confirmArchive = s })
                        }
                        DropdownMenu(moveMenu, { moveMenu = false }) {
                            ledger.categories.filter { it.parentId == null && it.kind == parent.kind && !it.hidden && it.id != parent.id }
                                .sortedWith(byName)
                                .forEach { top -> DropdownMenuItem({ Text(top.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, { moveMenu = false; onSave(s.copy(parentId = top.id, color = top.color), false) }) }
                        }
                        DropdownMenu(mergeMenu, { mergeMenu = false }) {
                            DropdownMenuItem({ Text("${parent.name}, no subcategory") }, { mergeMenu = false; merging = s to parent })
                            active.filter { it.id != s.id }.forEach { o -> DropdownMenuItem({ Text(o.name, maxLines = 1, overflow = TextOverflow.Ellipsis) }, { mergeMenu = false; merging = s to o }) }
                        }
                    }
                }
            }
            HorizontalDivider(color = Kassa.colors.line)
        }
        if (adding) NameField("", "New subcategory", button = "Add", keepOpen = true, error = { n -> problem(n, null) },
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
                Text(s.name, style = KassaType.body, color = Kassa.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                TextButton(onClick = { onSave(s.copy(hidden = false), false) }) { Text("Restore", color = Kassa.colors.ink) }
            }
        }
    }

    confirmArchive?.let { s ->
        ConfirmDialog(
            title = "Archive ${s.name}?", body = "It leaves the pickers. Its entries stay and still count under ${parent.name}, and you can restore it here. To move them instead, use Merge.",
            confirm = "Archive", onConfirm = { onSave(s.copy(hidden = true), false); confirmArchive = null }, onDismiss = { confirmArchive = null },
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

/** An inline name field. Enter or the button submits; with [keepOpen] it clears for the next one. Null means cancel. */
@Composable
private fun NameField(initial: String, label: String, button: String, keepOpen: Boolean, error: (String) -> String?, onDone: (String?) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    var problem by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val submit: () -> Unit = {
        val n = InputRules.clean(text)
        problem = if (n.isEmpty()) null else error(n)
        if (n.isEmpty()) onDone(null)
        else if (problem == null) { onDone(n); if (keepOpen) text = "" }
    }
    Column(Modifier.padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                text, { text = InputRules.typed(it, InputRules.CATEGORY_NAME_MAX); problem = null }, singleLine = true, label = { Text(label) },
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
