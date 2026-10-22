package dev.krishnagurram.kassa.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.ui.activity.Chevron
import dev.krishnagurram.kassa.ui.common.CategoryBadge
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

/** Full-screen picker frame with a back arrow, a title and a search box. */
@Composable
private fun PickerFrame(title: String, onDismiss: () -> Unit, content: @Composable () -> Unit) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize(), color = Kassa.colors.raised) {
            Column(Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = 760.dp).fillMaxWidth()) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onDismiss) { Icon(Chevron(left = true), "Back", tint = Kassa.colors.ink) }
                        Text(title, style = KassaType.section, color = Kassa.colors.ink)
                    }
                    content()
                }
            }
        }
    }
}

@Composable
fun SearchBox(value: String, onChange: (String) -> Unit, hint: String) {
    TextField(
        value = value, onValueChange = onChange, singleLine = true,
        placeholder = { Text(hint, style = KassaType.body) }, textStyle = KassaType.body,
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp).clip(RoundedCornerShape(12.dp)),
        colors = TextFieldDefaults.colors(
            focusedContainerColor = Kassa.colors.chip, unfocusedContainerColor = Kassa.colors.chip,
            focusedIndicatorColor = Color.Transparent, unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

/**
 * Money Manager-style grid of every category, alphabetical. Search matches subcategories too and returns
 * them as their own tiles ("Costco in Groceries").
 */
@Composable
fun CategoryPickerDialog(
    title: String,
    categories: List<Category>,
    selected: Long?,
    onPick: (Category) -> Unit,
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    val tops = categories.filter { it.parentId == null && !it.hidden }.sortedBy { it.name.lowercase() }
    val subsOf = categories.filter { it.parentId != null && !it.hidden }.groupBy { it.parentId }
    val needle = q.trim().lowercase()
    val tiles: List<Pair<Category, Category?>> = if (needle.isEmpty()) tops.map { it to null } else buildList {
        tops.forEach { t ->
            if (t.name.lowercase().contains(needle)) add(t to null)
            subsOf[t.id].orEmpty().filter { it.name.lowercase().contains(needle) }.forEach { add(it to t) }
        }
    }
    PickerFrame(title, onDismiss) {
        SearchBox(q, { q = it }, "Search categories and subcategories")
        if (tiles.isEmpty()) Text("No category matches “$q”.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(24.dp))
        LazyVerticalGrid(GridCells.Adaptive(minSize = 92.dp), Modifier.fillMaxSize()) {
            items(tiles, key = { it.first.id }) { (cat, parent) ->
                val on = selected == cat.id
                Column(
                    Modifier.padding(3.dp).clip(RoundedCornerShape(16.dp))
                        .background(if (on) Kassa.colors.chip else Color.Transparent)
                        .clickable { onPick(cat) }.padding(vertical = 12.dp, horizontal = 4.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CategoryBadge(parent ?: cat, size = 46)
                    Text(cat.name, style = KassaType.small, color = Kassa.colors.ink, textAlign = TextAlign.Center, maxLines = 2, modifier = Modifier.padding(top = 6.dp))
                    val sub = when {
                        parent != null -> "in ${parent.name}"
                        subsOf[cat.id].orEmpty().isNotEmpty() -> "${subsOf[cat.id]!!.size} sub"
                        else -> null
                    }
                    if (sub != null) Text(sub, style = KassaType.tiny, color = Kassa.colors.ink2, textAlign = TextAlign.Center, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Every account, searchable. Most used first, then your own groups (as in Accounts), each with its balance.
 * With a dozen cards and banks, typing two letters is faster than scanning.
 */
@Composable
fun AccountPickerDialog(
    title: String,
    accounts: List<Account>,
    groups: List<dev.krishnagurram.kassa.domain.AccountGroup>,
    balances: Map<Long, Long>,
    mostUsed: List<Account>,
    selected: Long?,
    onPick: (Account) -> Unit,
    onDismiss: () -> Unit,
) {
    var q by remember { mutableStateOf("") }
    val active = accounts.filter { !it.archived }
    val needle = q.trim().lowercase()
    val sections: List<Pair<String, List<Account>>> = if (needle.isNotEmpty()) {
        listOf("Matches" to active.filter { it.name.lowercase().contains(needle) || it.type.label.lowercase().contains(needle) })
    } else buildList {
        add("Most used" to mostUsed.filter { !it.archived }.take(4))
        groups.forEach { g -> active.filter { it.groupId == g.id }.takeIf { it.isNotEmpty() }?.let { add(g.name to it) } }
        active.filter { a -> a.groupId == null || groups.none { it.id == a.groupId } }.takeIf { it.isNotEmpty() }?.let { add("Other" to it) }
    }
    PickerFrame(title, onDismiss) {
        SearchBox(q, { q = it }, "Search accounts")
        if (needle.isNotEmpty() && sections.first().second.isEmpty()) Text("No account matches \u201c$q\u201d.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(24.dp))
        LazyColumn(Modifier.fillMaxSize()) {
            sections.forEach { (name, list) ->
                if (list.isNotEmpty()) {
                    item(key = "h-$name") { Text(name, style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 18.dp, bottom = 4.dp)) }
                    items(list, key = { "$name-${it.id}" }) { a ->
                        Column(Modifier.clickable { onPick(a) }) {
                            Row(Modifier.fillMaxWidth().padding(vertical = 13.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                                Column(Modifier.weight(1f)) {
                                    Text(a.name, style = if (a.id == selected) KassaType.bodyStrong else KassaType.body, color = Kassa.colors.ink)
                                    Text(a.type.label, style = KassaType.tiny, color = Kassa.colors.ink3)
                                }
                                Text(Money.format(balances[a.id] ?: 0), style = KassaType.body, color = Kassa.colors.ink2)
                            }
                            HorizontalDivider(color = Kassa.colors.line)
                        }
                    }
                }
            }
        }
    }
}

/** Plain list picker for subcategories when a category has more than five. */
@Composable
fun SubcategoryPickerDialog(parent: Category, subs: List<Category>, selected: Long?, onPick: (Category) -> Unit, onDismiss: () -> Unit) {
    PickerFrame(parent.name, onDismiss) {
        LazyColumn(Modifier.fillMaxSize()) {
            items(subs, key = { it.id }) { s ->
                Column(Modifier.clickable { onPick(s) }) {
                    Text(s.name, style = if (s.id == selected) KassaType.bodyStrong else KassaType.body, color = Kassa.colors.ink, modifier = Modifier.padding(vertical = 14.dp))
                    HorizontalDivider(color = Kassa.colors.line)
                }
            }
        }
    }
}
