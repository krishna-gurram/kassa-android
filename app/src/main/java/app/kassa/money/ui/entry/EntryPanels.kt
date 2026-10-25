package app.kassa.money.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kassa.money.domain.Account
import app.kassa.money.domain.CardSnapshot
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.Money
import app.kassa.money.domain.TxKind
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.common.CategoryIconView
import app.kassa.money.ui.common.MonthCalendar
import app.kassa.money.ui.common.swipeShift
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/*
 * The inputs the entry page's bottom panel shows, one per [Field]. Each reads and changes the page's [EntryState].
 * They're ColumnScope extensions so a grid or pad can take the panel's remaining height.
 */

/** The pad, with Payable and Total buttons when paying a card, then Next or Save. */
@Composable
internal fun ColumnScope.AmountPanel(s: EntryState, toCard: CardSnapshot?, saveLabel: String, onSave: () -> Unit) {
    PanelTitle("Amount", onDone = null)
    if (toCard != null && (toCard.payable > 0 || toCard.totalBalance > 0)) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
            if (toCard.payable > 0) QuickButton("Payable ${Money.format(toCard.payable)}", on = s.cents == toCard.payable, modifier = Modifier.weight(1f)) {
                s.amount = centsToInput(toCard.payable); s.error = null
            }
            if (toCard.totalBalance > 0) QuickButton("Total ${Money.format(toCard.totalBalance)}", on = s.cents == toCard.totalBalance, modifier = Modifier.weight(1f)) {
                s.amount = centsToInput(toCard.totalBalance); s.error = null
            }
        }
    }
    NumberPad(Modifier.weight(1f), s::pressKey)
    val next = s.missing()
    if (next == null) PrimaryButton(saveLabel, onSave)
    else PrimaryButton(
        when {
            next == Field.CATEGORY -> "Next: category"
            next == Field.TO -> "Next: to"
            s.kind == TxKind.TRANSFER -> "Next: from"
            s.kind == TxKind.INCOME -> "Next: into"
            else -> "Next: paid with"
        },
    ) { s.error = null; s.field = next }
}

/** Categories most used first; typing searches categories and subcategories together. */
@Composable
internal fun ColumnScope.CategoryPanel(s: EntryState, ledger: Ledger, categoryChoices: List<Category>, wide: Boolean) {
    val focus = LocalFocusManager.current
    val kind = if (s.kind == TxKind.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
    val picked = s.categoryId?.let { ledger.categoryById[it] }
    val pickedTop = picked?.let { c -> c.parentId?.let { ledger.categoryById[it] } ?: c }
    // A top-level category with subcategories opens them; anything else moves on.
    val choose: (Category) -> Unit = { c ->
        s.pickCategory(c.id)
        if (c.parentId == null && subcategories(ledger, c).isNotEmpty()) s.field = Field.SUB else s.advance()
    }

    PanelSearch(s.query, { s.query = it }, "Search categories", onFocus = { s.searching = it }) { s.field = null }
    val q = s.query.trim()
    if (q.isEmpty()) {
        LazyVerticalGrid(GridCells.Fixed(if (wide) 4 else 3), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(categoryChoices, key = { it.id }) { c ->
                PickTile(on = pickedTop?.id == c.id, minHeight = 66.dp, onClick = { choose(c) }) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        CategoryIconView(c.icon, Color(c.color), 34, fallback = c.name.take(1))
                        Text(c.name, style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                    }
                }
            }
        }
        return
    }
    val hits = searchCategories(ledger, kind, q)
    if (hits.isEmpty()) Text("No category matches \"$q\".", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(4.dp))
    LazyVerticalGrid(GridCells.Fixed(if (wide) 3 else 2), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(hits, key = { it.id }) { c ->
            val parent = c.parentId?.let { ledger.categoryById[it] }
            PickTile(on = picked?.id == c.id, onClick = { s.searching = false; focus.clearFocus(); choose(c) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryIconView((parent ?: c).icon, Color((parent ?: c).color), 26, fallback = c.name.take(1))
                    Column(Modifier.padding(start = 8.dp)) {
                        Text(c.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (parent != null) Text(parent.name, style = KassaType.tiny, color = Kassa.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

/**
 * A category's subcategories, "No subcategory" first, and "+ New" to add one without leaving the entry.
 * [onAdd] creates the subcategory and hands back its id.
 */
@Composable
internal fun ColumnScope.SubcategoryPanel(s: EntryState, ledger: Ledger, top: Category, wide: Boolean, onAdd: (Category, String, (Long) -> Unit) -> Unit) {
    var newName by remember(top.id) { mutableStateOf<String?>(null) }
    val pickedId = s.categoryId
    PanelTitle(top.name, back = { s.field = Field.CATEGORY }) { s.field = null }
    LazyVerticalGrid(GridCells.Fixed(if (wide) 3 else 2), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item(key = "none") {
            PickTile(on = pickedId == top.id, onClick = { s.pickCategory(top.id); s.advance() }) {
                Text("No subcategory", style = KassaType.bodyStrong, color = Kassa.colors.ink2)
            }
        }
        items(subcategories(ledger, top), key = { it.id }) { sub ->
            PickTile(on = pickedId == sub.id, onClick = { s.pickCategory(sub.id); s.advance() }) {
                Text(sub.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        item(key = "new", span = { GridItemSpan(if (newName == null) 1 else maxLineSpan) }) {
            val typed = newName
            if (typed == null) PickTile(on = false, onClick = { newName = "" }) {
                Text("+ New", style = KassaType.bodyStrong, color = Kassa.colors.ink2)
            } else NewSubcategory(
                parent = top,
                value = typed,
                siblings = ledger.categories.filter { it.parentId == top.id }.map { it.name },
                onChange = { newName = it },
                onAdd = { name -> onAdd(top, name) { id -> s.pickCategory(id); newName = null; s.advance() } },
            )
        }
    }
}

/** The field for a new subcategory's name, checked against [siblings] before [onAdd] gets the cleaned name. */
@Composable
private fun NewSubcategory(parent: Category, value: String, siblings: List<String>, onChange: (String) -> Unit, onAdd: (String) -> Unit) {
    var problem by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val add: () -> Unit = {
        problem = InputRules.nameProblem(value, "subcategory", InputRules.CATEGORY_NAME_MAX,
            taken = { n -> siblings.any { it.equals(n, ignoreCase = true) } })
        if (problem == null) onAdd(InputRules.clean(value))
    }
    Row(verticalAlignment = Alignment.Top) {
        OutlinedTextField(
            value, { onChange(InputRules.typed(it, InputRules.CATEGORY_NAME_MAX)); problem = null }, singleLine = true,
            placeholder = { Text("New under ${parent.name}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            isError = problem != null,
            supportingText = problem?.let { { Text(it, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { add() }),
            modifier = Modifier.weight(1f).focusRequester(focus),
        )
        TextButton(onClick = add, modifier = Modifier.padding(top = 4.dp)) { Text("Add", color = Kassa.colors.ink, style = KassaType.bodyStrong) }
    }
}

/**
 * Accounts to pay with (or, for [Field.TO], to move money into), most used first. A transfer can't come from a card.
 * Picking a card to pay that has a balance payable opens the pad, so the Payable button is one tap away.
 */
@Composable
internal fun ColumnScope.AccountPanel(
    s: EntryState, accountChoices: List<Account>, toChoices: List<Account>, cards: Map<Long, CardSnapshot>, wide: Boolean,
) {
    val focus = LocalFocusManager.current
    val isTo = s.field == Field.TO
    PanelSearch(s.query, { s.query = it }, "Search accounts", onFocus = { s.searching = it }) { s.field = null }
    val q = s.query.trim()
    val list = (if (isTo) toChoices.filter { it.id != s.accountId } else accountChoices.filter { s.kind != TxKind.TRANSFER || !it.type.hasStatement || it.id == s.accountId })
        .filter { q.isEmpty() || it.name.contains(q, ignoreCase = true) || it.last4?.contains(q) == true }
    if (list.isEmpty()) Text(if (q.isEmpty()) "Add an account in Accounts first." else "No account matches \"$q\".",
        style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(4.dp))
    val selected = if (isTo) s.toAccountId else s.accountId
    LazyVerticalGrid(GridCells.Fixed(if (wide) 3 else 2), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(list, key = { it.id }) { a ->
            PickTile(on = selected == a.id, onClick = {
                s.searching = false; focus.clearFocus()
                if (isTo) {
                    s.toAccountId = a.id
                    s.error = null
                    val payable = (cards[a.id]?.payable ?: 0L) > 0
                    s.field = if (payable && s.cents <= 0) Field.AMOUNT else s.missing() ?: if (s.cents <= 0) Field.AMOUNT else null
                } else {
                    s.accountId = a.id
                    if (s.toAccountId == a.id) s.toAccountId = null
                    s.advance()
                }
            }) {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(a.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                        a.last4?.takeIf { it.isNotBlank() }?.let { Text("  ··$it", style = KassaType.tiny, color = Kassa.colors.ink3, maxLines = 1, softWrap = false) }
                    }
                    val due = cards[a.id]?.payable ?: 0L
                    if (due > 0 && s.kind != TxKind.INCOME) Text("${Money.format(due)} due", style = KassaType.tiny.copy(fontWeight = FontWeight.SemiBold),
                        color = Kassa.colors.attention, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Today, Yesterday, and a month calendar. */
@Composable
internal fun ColumnScope.DatePanel(s: EntryState, today: LocalDate) {
    PanelTitle("Date") { s.field = null }
    val pick: (LocalDate) -> Unit = { d -> s.date = d; s.error = null; s.field = if (s.cents <= 0) Field.AMOUNT else null }
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
        QuickButton("Today", on = s.date == today, modifier = Modifier.weight(1f)) { pick(today) }
        QuickButton("Yesterday", on = s.date == today.minusDays(1), modifier = Modifier.weight(1f)) { pick(today.minusDays(1)) }
    }
    MonthCalendar(s.calendarMonth, s.date, today, onMonth = { s.calendarMonth = it }, modifier = Modifier.weight(1f), onPick = pick)
}

/** Notes you've used before; picking one also picks the category it last went with, if none is picked yet. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ColumnScope.NotePanel(s: EntryState, ledger: Ledger, frequentNotes: List<String>) {
    PanelTitle("Note") { s.field = null }
    val suggestions = noteSuggestions(frequentNotes, s.note, ledger, s.kind, s.categoryId)
    if (suggestions.isEmpty()) Text("Type a merchant or a note.", style = KassaType.small, color = Kassa.colors.ink3, modifier = Modifier.padding(4.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        suggestions.forEach { text ->
            Box(
                Modifier.clip(RoundedCornerShape(50)).background(Kassa.colors.ground).clickable {
                    s.pickNote(text, ledger.recent.firstOrNull { it.note == text && it.kind == s.kind }?.categoryId)
                }.padding(horizontal = 14.dp, vertical = 9.dp),
            ) { Text(text, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    Spacer(Modifier.weight(1f))
}
