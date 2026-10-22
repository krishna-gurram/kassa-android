package dev.krishnagurram.kassa.ui.entry

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krishnagurram.kassa.data.EntryDraft
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.CategoryKind
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Split
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.ConfirmDialog
import dev.krishnagurram.kassa.ui.common.PageTop
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What the page opens with: a blank entry, an existing one to edit, or a prefilled one (a bank alert or bill). */
data class EntryStart(
    val editing: Transaction? = null,
    val splitWith: List<Long> = emptyList(),
    val prefill: EntryDraft? = null,
    /** Runs after a successful save, for example to clear the alert or bill that opened the page. */
    val afterSave: (() -> Unit)? = null,
)

private const val MAX_DIGITS = 8

/**
 * The add and edit page, built to fit one screen without scrolling to type:
 * kind, the amount, one row each for category and account (most used first, the rest a tap away),
 * date and note, then the number pad and save button fixed at the bottom.
 * Leaving with unsaved changes asks first.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntryPage(
    ledger: Ledger,
    start: EntryStart,
    contentPadding: PaddingValues,
    loadNotes: suspend (TxKind) -> List<String>,
    onSave: (EntryDraft) -> Unit,
    onDelete: (Long) -> Unit,
    onAddPerson: (String, (Long) -> Unit) -> Unit,
    onBack: () -> Unit,
) {
    val e = start.editing
    val p = start.prefill
    val zone = ledger.zone
    var kind by remember { mutableStateOf(e?.kind?.takeIf { it in setOf(TxKind.EXPENSE, TxKind.INCOME, TxKind.TRANSFER) } ?: p?.kind ?: TxKind.EXPENSE) }
    var amount by remember { mutableStateOf((e?.amount ?: p?.amount)?.takeIf { it > 0 }?.let { centsToInput(it) } ?: "") }
    var categoryId by remember { mutableStateOf(e?.categoryId ?: p?.categoryId) }
    var accountId by remember { mutableStateOf(e?.accountId ?: p?.accountId ?: lastUsedAccount(ledger, e?.kind ?: p?.kind ?: TxKind.EXPENSE)) }
    var toAccountId by remember { mutableStateOf(e?.toAccountId ?: p?.toAccountId) }
    var accountTouched by remember { mutableStateOf(e != null || p?.accountId != null) }
    val originalMillis = e?.epochMillis ?: p?.epochMillis
    var date by remember { mutableStateOf(originalMillis?.let { Instant.ofEpochMilli(it).atZone(zone).toLocalDate() } ?: ledger.today) }
    var note by remember { mutableStateOf(e?.note ?: p?.note ?: "") }
    val splitWith = remember { mutableStateListOf<Long>().apply { addAll(start.splitWith) } }
    var splitOn by remember { mutableStateOf(start.splitWith.isNotEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }
    var picking by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val initial = remember { listOf(kind, amount, categoryId, accountId, toAccountId, date, note, splitWith.toList()) }
    val dirty = listOf(kind, amount, categoryId, accountId, toAccountId, date, note, splitWith.toList()) != initial
    val leave: () -> Unit = { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    LaunchedEffect(kind) { notes = if (kind == TxKind.TRANSFER) emptyList() else loadNotes(kind) }

    val cents = Money.parseCents(amount) ?: 0L
    val cat = categoryId?.let { ledger.categoryById[it] }
    val topCat = cat?.let { c -> c.parentId?.let { ledger.categoryById[it] } ?: c }
    val catKind = if (kind == TxKind.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
    val ranked = remember(kind, ledger.recent.size) { rankTop(ledger, catKind) }
    val rankedAccounts = remember(kind, ledger.recent.size, ledger.accounts.accounts.size) { rankAccounts(ledger, kind, to = false) }
    val rankedTo = remember(ledger.recent.size, ledger.accounts.accounts.size) { rankAccounts(ledger, TxKind.TRANSFER, to = true) }

    fun pickCategory(c: Category) {
        categoryId = c.id
        if (!accountTouched && kind == TxKind.EXPENSE) defaultAccountFor(ledger, c)?.let { accountId = it }
        error = null
    }

    fun save() {
        val millis = millisFor(date, originalMillis, ledger)
        error = when {
            cents <= 0 -> "Enter an amount."
            kind != TxKind.TRANSFER && categoryId == null -> "Pick a category."
            accountId == null -> "Pick an account."
            kind == TxKind.TRANSFER && (toAccountId == null || toAccountId == accountId) -> "Pick two different accounts."
            splitOn && splitWith.isEmpty() -> "Pick who shared this, or turn off Split."
            else -> null
        }
        if (error == null) onSave(
            EntryDraft(
                id = e?.id, kind = kind, amount = cents, accountId = accountId,
                toAccountId = toAccountId, categoryId = categoryId, personId = null,
                epochMillis = millis, note = note,
                splitWith = if (kind == TxKind.EXPENSE && splitOn) splitWith.toList() else emptyList(),
            )
        )
    }

    val saveLabel = when {
        e != null -> "Save changes"
        kind == TxKind.INCOME -> "Add income"
        kind == TxKind.TRANSFER -> if (ledger.accountById[toAccountId]?.type?.hasStatement == true) "Record card payment" else "Record transfer"
        else -> "Add expense"
    }

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        PageTop(
            when { e != null -> "Edit entry"; p != null -> "Review entry"; else -> "New entry" },
            onBack = leave,
        ) {
            if (e != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Kassa.colors.attention, style = KassaType.bodyStrong) }
        }
        BoxWithConstraints(Modifier.weight(1f).imePadding()) {
            val wide = maxWidth >= 600.dp
            val typing = WindowInsets.isImeVisible

            val kindRow: @Composable () -> Unit = {
                Segmented(
                    options = listOf(TxKind.EXPENSE to "Expense", TxKind.INCOME to "Income", TxKind.TRANSFER to "Transfer"),
                    selected = kind,
                    onSelect = {
                        if (it != kind) {
                            kind = it; categoryId = null; splitOn = false; splitWith.clear(); error = null
                            if (!accountTouched) accountId = lastUsedAccount(ledger, it)
                            if (it == TxKind.TRANSFER && toAccountId == null) toAccountId = rankAccounts(ledger, TxKind.TRANSFER, to = true).firstOrNull { a -> a.id != accountId }?.id
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            val amountBlock: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$" + displayAmount(amount), style = KassaType.hero.copy(fontSize = if (wide) 52.sp else 44.sp),
                        color = if (amount.isEmpty()) Kassa.colors.ink3 else Kassa.colors.ink, maxLines = 1,
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    if (kind == TxKind.EXPENSE && splitOn && splitWith.isNotEmpty() && cents > 0) {
                        Text("Your share ${Money.format(Split.equal(cents, splitWith.size).mine)}", style = KassaType.small, color = Kassa.colors.ink2)
                    }
                }
            }
            val fields: @Composable () -> Unit = {
                Column {
                    if (kind != TxKind.TRANSFER) {
                        RowLabel("Category")
                        CategoryRow(ledger, catKind, ranked, topCat, cat, onPick = ::pickCategory, onClear = { categoryId = null }, onOpenAll = { picking = "category" }, onOpenSubs = { picking = "sub" })
                    }
                    RowLabel(when (kind) { TxKind.INCOME -> "Into"; TxKind.TRANSFER -> "From"; else -> "Paid with" })
                    AccountRow(rankedAccounts, accountId, ledger.accounts.balances, onPick = { accountId = it.id; accountTouched = true; error = null }, onOpenAll = { picking = "from" })
                    if (kind == TxKind.TRANSFER) {
                        RowLabel("To")
                        AccountRow(rankedTo.filter { it.id != accountId }, toAccountId, ledger.accounts.balances, onPick = { toAccountId = it.id; error = null }, onOpenAll = { picking = "to" })
                    }
                    RowLabel("Date and note")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        DateChips(date, ledger.today, onPick = { date = it }, onOther = { picking = "date" })
                    }
                    NoteField(note, { note = it }, if (kind == TxKind.TRANSFER) "Note (optional)" else "Merchant or note")
                    val sugg = suggestions(notes, note, ledger, kind, categoryId)
                    if (sugg.isNotEmpty()) LazyRow(Modifier.padding(top = 6.dp)) {
                        items(sugg) { s ->
                            Chip(s, selected = false, outlined = true, small = true) {
                                note = s
                                if (categoryId == null) ledger.recent.firstOrNull { it.note == s && it.kind == kind }?.let { last ->
                                    categoryId = last.categoryId
                                    if (!accountTouched && last.accountId != null) accountId = last.accountId
                                }
                            }
                        }
                    }
                    if (kind == TxKind.EXPENSE) SplitBlock(ledger, splitOn, { splitOn = it; if (!it) splitWith.clear() }, splitWith, onAddPerson)
                }
            }
            val padAndSave: @Composable () -> Unit = {
                Column {
                    error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention, modifier = Modifier.padding(bottom = 6.dp)) }
                    if (!typing) NumberPad(compact = !wide) { k -> amount = applyKey(amount, k); error = null }
                    PrimaryButton(saveLabel, ::save)
                }
            }

            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                    Column(Modifier.width(340.dp).fillMaxSize()) {
                        kindRow(); amountBlock()
                        Box(Modifier.weight(1f))
                        padAndSave()
                    }
                    Box(Modifier.width(32.dp))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { fields() }
                }
            } else {
                Column(Modifier.fillMaxSize().padding(horizontal = 4.dp)) {
                    kindRow()
                    // Everything above the pad scrolls on its own if it ever runs long; the pad and button never move.
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { amountBlock(); fields() }
                    padAndSave()
                }
            }
        }
    }

    when (picking) {
        "category" -> CategoryPickerDialog(
            title = if (kind == TxKind.INCOME) "Income category" else "Expense category",
            categories = ledger.categories.filter { it.kind == catKind },
            selected = categoryId,
            onPick = { pickCategory(it); picking = null },
            onDismiss = { picking = null },
        )
        "sub" -> topCat?.let { t ->
            SubcategoryPickerDialog(t, subsRanked(ledger, t), categoryId, onPick = { categoryId = it.id; picking = null }, onDismiss = { picking = null })
        }
        "from", "to" -> AccountPickerDialog(
            title = when { picking == "to" -> "Move to"; kind == TxKind.TRANSFER -> "Move from"; kind == TxKind.INCOME -> "Into"; else -> "Paid with" },
            accounts = ledger.accounts.accounts, groups = ledger.accounts.groups, balances = ledger.accounts.balances,
            mostUsed = if (picking == "to") rankedTo else rankedAccounts,
            selected = if (picking == "to") toAccountId else accountId,
            onPick = { a -> if (picking == "to") toAccountId = a.id else { accountId = a.id; accountTouched = true }; picking = null; error = null },
            onDismiss = { picking = null },
        )
        "date" -> {
            val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }
                        picking = null
                    }) { Text("Done", color = Kassa.colors.ink) }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel", color = Kassa.colors.ink2) } },
            ) { DatePicker(state = state) }
        }
    }

    if (confirmDelete && e != null) ConfirmDialog(
        title = "Delete this entry?",
        body = "${e.note.ifBlank { "This entry" }}, ${Money.format(e.amount)}. Balances update right away. This can't be undone.",
        confirm = "Delete", dismiss = "Keep it",
        onConfirm = { onDelete(e.id) }, onDismiss = { confirmDelete = false },
    )
    if (confirmDiscard) ConfirmDialog(
        title = if (e != null) "Discard your changes?" else "Discard this entry?",
        body = if (e != null) "The entry stays as it was before you opened it." else "Nothing you typed here will be saved.",
        confirm = "Discard", dismiss = "Keep editing",
        onConfirm = onBack, onDismiss = { confirmDiscard = false },
    )
}

@Composable
private fun RowLabel(text: String) = Text(text, style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))

/**
 * One line of category chips, most used first, scrolling sideways. Once one is picked the line shows only it
 * (tap to change) and its subcategories.
 */
@Composable
private fun CategoryRow(
    ledger: Ledger,
    kind: CategoryKind,
    ranked: List<Category>,
    selectedTop: Category?,
    selected: Category?,
    onPick: (Category) -> Unit,
    onClear: () -> Unit,
    onOpenAll: () -> Unit,
    onOpenSubs: () -> Unit,
) {
    val total = ledger.categories.count { it.kind == kind && it.parentId == null && !it.hidden }
    if (selectedTop == null) {
        LazyRow {
            items(ranked.take(10), key = { it.id }) { c -> Chip("${emojiOf(c)}${c.name}", selected = false) { onPick(c) } }
            item { Chip("All $total", selected = false, dashed = true, onClick = onOpenAll) }
        }
        return
    }
    val subs = subsRanked(ledger, selectedTop)
    val chosenSub = selected?.takeIf { it.parentId == selectedTop.id }
    LazyRow {
        item { Chip("${emojiOf(selectedTop)}${selectedTop.name}  ✕", selected = true, onClick = onClear) }
        if (chosenSub != null) {
            item { Chip(chosenSub.name, selected = true, small = true) { onPick(selectedTop) } }
        } else {
            items(subs.take(8), key = { it.id }) { s -> Chip(s.name, selected = false, small = true) { onPick(s) } }
            if (subs.size > 8) item { Chip("All ${subs.size}", selected = false, small = true, dashed = true, onClick = onOpenSubs) }
        }
    }
}

/** One line of accounts, the picked one first, then the most used, then "All accounts" for the rest. */
@Composable
private fun AccountRow(ranked: List<Account>, selectedId: Long?, balances: Map<Long, Long>, onPick: (Account) -> Unit, onOpenAll: () -> Unit) {
    val selected = ranked.firstOrNull { it.id == selectedId }
    val shown = (listOfNotNull(selected) + ranked.filter { it.id != selectedId }).take(6)
    LazyRow {
        items(shown, key = { it.id }) { a -> Chip(a.name, selected = a.id == selectedId) { onPick(a) } }
        item { Chip("All accounts", selected = false, dashed = true, onClick = onOpenAll) }
    }
    if (selected != null) Text("Balance ${Money.format(balances[selected.id] ?: 0)}", style = KassaType.tiny, color = Kassa.colors.ink3, modifier = Modifier.padding(start = 4.dp))
}

@Composable
private fun DateChips(date: LocalDate, today: LocalDate, onPick: (LocalDate) -> Unit, onOther: () -> Unit) {
    val other = date != today && date != today.minusDays(1)
    LazyRow {
        item { Chip("Today", selected = date == today, small = true) { onPick(today) } }
        item { Chip("Yesterday", selected = date == today.minusDays(1), small = true) { onPick(today.minusDays(1)) } }
        item { Chip(if (other) date.format(DateTimeFormatter.ofPattern("EEE, MMM d")) else "Pick a date", selected = other, small = true, dashed = !other, onClick = onOther) }
    }
}

@Composable
private fun NoteField(value: String, onChange: (String) -> Unit, hint: String) {
    val shape = RoundedCornerShape(12.dp)
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = KassaType.body.copy(color = Kassa.colors.ink),
        cursorBrush = SolidColor(Kassa.colors.ink),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        modifier = Modifier.fillMaxWidth().padding(top = 2.dp).clip(shape).background(Kassa.colors.chip).padding(horizontal = 14.dp, vertical = 12.dp),
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) Text(hint, style = KassaType.body, color = Kassa.colors.ink3)
                inner()
            }
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SplitBlock(
    ledger: Ledger,
    on: Boolean,
    setOn: (Boolean) -> Unit,
    with: MutableList<Long>,
    onAddPerson: (String, (Long) -> Unit) -> Unit,
) {
    var newName by remember { mutableStateOf("") }
    Row(Modifier.padding(top = 10.dp)) {
        Chip(if (on) "Split  ✕" else "Split with people", selected = on, small = true) { setOn(!on) }
    }
    if (!on) return
    FlowRow {
        ledger.people.people.filter { !it.archived }.forEach { p ->
            val sel = p.id in with
            Chip(p.name, selected = sel, small = true) { if (sel) with.remove(p.id) else with.add(p.id) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("Add a person") }, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            val n = newName.trim()
            if (n.isNotEmpty()) { onAddPerson(n) { id -> with.add(id) }; newName = "" }
        }) { Text("Add", color = Kassa.colors.ink) }
    }
    if (with.isNotEmpty()) Text("Split ${with.size + 1} ways. Your share counts as spending; the rest is receivable.", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 6.dp))
}

@Composable
fun Chip(
    label: String, selected: Boolean, small: Boolean = false, outlined: Boolean = false, dashed: Boolean = false,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val bg = when { selected -> Kassa.colors.ink; outlined || dashed -> Color.Transparent; else -> Kassa.colors.chip }
    var m = Modifier.padding(end = 8.dp, bottom = 8.dp).clip(shape).background(bg)
    if (outlined || dashed) m = m.border(1.dp, if (dashed) Kassa.colors.ink3 else Kassa.colors.line, shape)
    Box(m.clickable(onClick = onClick).padding(horizontal = if (small) 12.dp else 14.dp, vertical = if (small) 7.dp else 9.dp)) {
        Text(
            label, style = if (small) KassaType.small else KassaType.body,
            color = if (selected) Kassa.colors.onInk else if (dashed) Kassa.colors.ink2 else Kassa.colors.ink,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp),
        )
    }
}

@Composable
private fun Segmented(options: List<Pair<TxKind, String>>, selected: TxKind, onSelect: (TxKind) -> Unit, modifier: Modifier) {
    Row(modifier.clip(RoundedCornerShape(12.dp)).background(Kassa.colors.chip).padding(3.dp)) {
        options.forEach { (k, label) ->
            val on = k == selected
            Box(
                Modifier.weight(1f).height(36.dp).clip(RoundedCornerShape(9.dp))
                    .background(if (on) Kassa.colors.raised else Color.Transparent)
                    .clickable { onSelect(k) },
                contentAlignment = Alignment.Center,
            ) { Text(label, style = KassaType.bodyStrong, color = if (on) Kassa.colors.ink else Kassa.colors.ink2) }
        }
    }
}

@Composable
private fun NumberPad(compact: Boolean, onKey: (String) -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "del")
    Column(Modifier.padding(bottom = 8.dp)) {
        keys.chunked(3).forEach { row ->
            Row {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).padding(3.dp).height(if (compact) 46.dp else 52.dp).clip(RoundedCornerShape(12.dp))
                            .background(Kassa.colors.chip).clickable { onKey(k) },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (k == "del") "⌫" else k, style = KassaType.body.copy(fontSize = 22.sp), color = Kassa.colors.ink, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

/* ---------- helpers ---------- */

private fun applyKey(current: String, k: String): String = when (k) {
    "del" -> current.dropLast(1)
    "." -> if (current.contains('.')) current else (current.ifEmpty { "0" }) + "."
    else -> when {
        current.contains('.') && current.substringAfter('.').length >= 2 -> current
        current.replace(".", "").length >= MAX_DIGITS -> current
        current == "0" -> k
        else -> current + k
    }
}

private fun displayAmount(s: String): String {
    if (s.isEmpty()) return "0"
    val whole = s.substringBefore('.').ifEmpty { "0" }
    val grouped = whole.toLongOrNull()?.let { java.text.NumberFormat.getIntegerInstance(java.util.Locale.US).format(it) } ?: whole
    return if (s.contains('.')) grouped + "." + s.substringAfter('.') else grouped
}

private fun centsToInput(c: Long): String {
    val s = java.math.BigDecimal.valueOf(c).movePointLeft(2).toPlainString()
    return s.removeSuffix(".00")
}

private fun emojiOf(c: Category): String = (c.icon as? dev.krishnagurram.kassa.domain.CategoryIcon.Emoji)?.let { it.value + " " } ?: ""

/** Keeps the original time of day when editing; uses now for today and noon for other new dates. */
private fun millisFor(date: LocalDate, original: Long?, ledger: Ledger): Long {
    val time = when {
        original != null -> Instant.ofEpochMilli(original).atZone(ledger.zone).toLocalTime()
        date == ledger.today -> LocalTime.now(ledger.zone)
        else -> LocalTime.NOON
    }
    return date.atTime(time).atZone(ledger.zone).toInstant().toEpochMilli()
}

/** Top-level categories ranked by how often they're used, weighted toward entries logged near this hour. */
private fun rankTop(ledger: Ledger, kind: CategoryKind): List<Category> {
    val hour = LocalTime.now(ledger.zone).hour
    val score = HashMap<Long, Double>()
    val wanted = if (kind == CategoryKind.INCOME) TxKind.INCOME else TxKind.EXPENSE
    for (t in ledger.recent) {
        if (t.kind != wanted) continue
        val top = t.categoryId?.let { ledger.categoryById[it] }?.let { c -> c.parentId ?: c.id } ?: continue
        val h = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).hour
        score[top] = (score[top] ?: 0.0) + 1 + 2 * maxOf(0.0, 1 - Math.abs(h - hour) / 4.0)
    }
    return ledger.categories.filter { it.kind == kind && it.parentId == null && !it.hidden }
        .sortedWith(compareByDescending<Category> { score[it.id] ?: 0.0 }.thenBy { it.sortOrder })
}

/**
 * Accounts that make sense for this kind of entry, most used first (recent use counts more).
 * Expenses lean to cards and checking, income to bank accounts; investments and loans come last.
 */
internal fun rankAccounts(ledger: Ledger, kind: TxKind, to: Boolean): List<Account> {
    val score = HashMap<Long, Double>()
    val newest = ledger.recent.maxOfOrNull { it.epochMillis } ?: 0L
    for (t in ledger.recent) {
        if (t.kind != kind) continue
        val id = (if (to) t.toAccountId else t.accountId) ?: continue
        val ageDays = (newest - t.epochMillis) / 86_400_000.0
        score[id] = (score[id] ?: 0.0) + 1 + maxOf(0.0, 1 - ageDays / 30.0)
    }
    val likely = when (kind) {
        TxKind.EXPENSE -> setOf(AccountType.CREDIT_CARD, AccountType.CHECKING, AccountType.CASH, AccountType.PREPAID)
        TxKind.INCOME -> setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH)
        else -> AccountType.entries.toSet()
    }
    return ledger.accounts.accounts.filter { !it.archived }
        .sortedWith(compareByDescending<Account> { score[it.id] ?: 0.0 }.thenByDescending { it.type in likely }.thenBy { it.sortOrder })
}

private fun lastUsedAccount(ledger: Ledger, kind: TxKind): Long? =
    ledger.recent.filter { it.kind == kind && it.accountId != null }.maxByOrNull { it.epochMillis }?.accountId

internal fun subsRanked(ledger: Ledger, top: Category): List<Category> {
    val n = HashMap<Long, Int>()
    for (t in ledger.recent) t.categoryId?.let { n[it] = (n[it] ?: 0) + 1 }
    return ledger.categories.filter { it.parentId == top.id && !it.hidden }
        .sortedWith(compareByDescending<Category> { n[it.id] ?: 0 }.thenBy { it.sortOrder })
}

private fun defaultAccountFor(ledger: Ledger, c: Category): Long? {
    val ids = ledger.categories.filter { it.id == c.id || it.parentId == c.id }.map { it.id }.toSet()
    return ledger.recent.filter { it.kind == TxKind.EXPENSE && it.categoryId in ids && it.accountId != null }
        .groupingBy { it.accountId!! }.eachCount().maxByOrNull { it.value }?.key
}

private fun suggestions(all: List<String>, typed: String, ledger: Ledger, kind: TxKind, categoryId: Long?): List<String> {
    val q = typed.trim().lowercase()
    val pool = if (categoryId != null && q.isEmpty()) {
        ledger.recent.filter { it.kind == kind && it.categoryId == categoryId && it.note.isNotBlank() }
            .groupingBy { it.note }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    } else all
    return pool.filter { q.isEmpty() || (it.lowercase().contains(q) && it.lowercase() != q) }.take(6)
}
