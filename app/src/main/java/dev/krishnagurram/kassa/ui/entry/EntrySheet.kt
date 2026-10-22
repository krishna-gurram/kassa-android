package dev.krishnagurram.kassa.ui.entry

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
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
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** What the sheet opens with: a blank entry, an existing one to edit, or a prefilled one (card payment). */
data class EntryStart(
    val editing: Transaction? = null,
    val splitWith: List<Long> = emptyList(),
    val prefill: EntryDraft? = null,
    /** Runs after a successful save, for example to clear the alert or bill that opened the sheet. */
    val afterSave: (() -> Unit)? = null,
)

private const val MAX_DIGITS = 8

/** The add and edit sheet: amount first on a number pad, then category, account, date, note and split. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EntrySheet(
    ledger: Ledger,
    start: EntryStart,
    loadNotes: suspend (TxKind) -> List<String>,
    onSave: (EntryDraft) -> Unit,
    onDelete: (Long) -> Unit,
    onAddPerson: (String, (Long) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    val e = start.editing
    val p = start.prefill
    val zone = ledger.zone
    var kind by remember { mutableStateOf(e?.kind?.takeIf { it in setOf(TxKind.EXPENSE, TxKind.INCOME, TxKind.TRANSFER) } ?: p?.kind ?: TxKind.EXPENSE) }
    var amount by remember { mutableStateOf((e?.amount ?: p?.amount)?.takeIf { it > 0 }?.let { centsToInput(it) } ?: "") }
    var categoryId by remember { mutableStateOf(e?.categoryId ?: p?.categoryId) }
    var accountId by remember { mutableStateOf(e?.accountId ?: p?.accountId) }
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

    LaunchedEffect(kind) { notes = if (kind == TxKind.TRANSFER) emptyList() else loadNotes(kind) }

    val cents = Money.parseCents(amount) ?: 0L
    val cat = categoryId?.let { ledger.categoryById[it] }
    val topCat = cat?.let { c -> c.parentId?.let { ledger.categoryById[it] } ?: c }
    val catKind = if (kind == TxKind.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
    val ranked = remember(kind, ledger.recent.size) { rankTop(ledger, catKind) }

    fun pickCategory(c: Category) {
        categoryId = c.id
        if (!accountTouched && kind == TxKind.EXPENSE) defaultAccountFor(ledger, c)?.let { accountId = it }
        error = null
    }

    fun save() {
        val millis = millisFor(date, originalMillis, ledger)
        error = when {
            cents <= 0 -> "Enter an amount first."
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

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        BoxWithConstraints(Modifier.navigationBarsPadding().imePadding()) {
            val wide = maxWidth >= 600.dp

            val header: @Composable () -> Unit = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Segmented(
                        options = listOf(TxKind.EXPENSE to "Expense", TxKind.INCOME to "Income", TxKind.TRANSFER to "Transfer"),
                        selected = kind,
                        onSelect = {
                            if (it != kind) {
                                kind = it; categoryId = null; splitOn = false; splitWith.clear(); error = null
                                if (!accountTouched) accountId = null
                                if (it == TxKind.TRANSFER && toAccountId == null) toAccountId = ledger.accounts.accounts.firstOrNull { a -> a.type == AccountType.SAVINGS }?.id
                            }
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            val amountBlock: @Composable () -> Unit = {
                Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        "$" + displayAmount(amount), style = KassaType.hero.copy(fontSize = 52.sp),
                        color = if (amount.isEmpty()) Kassa.colors.ink3 else Kassa.colors.ink,
                        modifier = Modifier.padding(top = 14.dp),
                    )
                    if (kind == TxKind.EXPENSE && splitOn && splitWith.isNotEmpty() && cents > 0) {
                        Text("Your share ${Money.format(Split.equal(cents, splitWith.size).mine)}", style = KassaType.small, color = Kassa.colors.ink2)
                    }
                }
            }
            val categoryBlock: @Composable () -> Unit = {
                if (kind != TxKind.TRANSFER) CategoryChooser(
                    ledger = ledger, kind = catKind, ranked = ranked, wide = wide,
                    selectedTop = topCat, selected = cat,
                    onPick = ::pickCategory,
                    onClear = { categoryId = null },
                    onOpenAll = { picking = "category" },
                    onOpenSubs = { picking = "sub" },
                )
            }
            val fields: @Composable () -> Unit = {
                Column {
                    if (kind == TxKind.TRANSFER) {
                        FieldRow("From", accountName(ledger, accountId)) { picking = "from" }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                            TextButton(onClick = { val a = accountId; accountId = toAccountId; toAccountId = a }) { Text("Swap", color = Kassa.colors.ink2) }
                        }
                        FieldRow("To", accountName(ledger, toAccountId)) { picking = "to" }
                    } else {
                        FieldRow(if (kind == TxKind.INCOME) "Into" else "Paid with", accountName(ledger, accountId)) { picking = "from" }
                    }
                    FieldRow("Date", dateLabel(date, ledger.today)) { picking = "date" }
                    OutlinedTextField(
                        value = note, onValueChange = { note = it }, singleLine = true,
                        label = { Text(if (kind == TxKind.TRANSFER) "Note (optional)" else "Merchant or note") },
                        textStyle = KassaType.body,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    val sugg = suggestions(notes, note, ledger, kind, categoryId)
                    if (sugg.isNotEmpty()) FlowRow(Modifier.padding(top = 6.dp)) {
                        sugg.forEach { s ->
                            Chip(s, selected = false, outlined = true) {
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
            val padBlock: @Composable () -> Unit = {
                Column {
                    NumberPad { k -> amount = applyKey(amount, k) }
                    error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention, modifier = Modifier.padding(top = 10.dp)) }
                    Gap(12)
                    PrimaryButton(
                        when {
                            e != null -> "Save changes"
                            kind == TxKind.INCOME -> "Add income"
                            kind == TxKind.TRANSFER -> if (ledger.accountById[toAccountId]?.type?.hasStatement == true) "Pay card" else "Move money"
                            else -> "Add expense"
                        },
                        ::save,
                    )
                    if (e != null) TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) { Text("Delete entry", color = Kassa.colors.attention) }
                }
            }

            Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 20.dp)) {
                header()
                if (wide) {
                    Row(Modifier.padding(top = 8.dp)) {
                        Column(Modifier.width(300.dp)) { amountBlock(); Gap(8); padBlock() }
                        Box(Modifier.width(32.dp))
                        Column(Modifier.weight(1f)) { categoryBlock(); fields() }
                    }
                } else {
                    amountBlock(); categoryBlock(); fields(); padBlock()
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
            title = when { picking == "to" -> "Move to"; kind == TxKind.TRANSFER -> "Move from"; else -> "Choose account" },
            accounts = ledger.accounts.accounts, balances = ledger.accounts.balances,
            selected = if (picking == "to") toAccountId else accountId,
            onPick = { a -> if (picking == "to") toAccountId = a.id else { accountId = a.id; accountTouched = true }; picking = null },
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

    if (confirmDelete && e != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete this entry?") },
            text = { Text("${e.note.ifBlank { "This entry" }}, ${Money.format(e.amount)}. Balances update right away. This can't be undone.") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(e.id) }) { Text("Delete", color = Kassa.colors.attention) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Keep it", color = Kassa.colors.ink) } },
        )
    }
}

/**
 * Top categories as chips. Once one is picked, the row collapses to just that one plus "Change".
 * Subcategories: five or fewer are all shown; more than five show the five most used plus "All N",
 * and collapse to the picked one.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryChooser(
    ledger: Ledger,
    kind: CategoryKind,
    ranked: List<Category>,
    wide: Boolean,
    selectedTop: Category?,
    selected: Category?,
    onPick: (Category) -> Unit,
    onClear: () -> Unit,
    onOpenAll: () -> Unit,
    onOpenSubs: () -> Unit,
) {
    val total = ledger.categories.count { it.kind == kind && it.parentId == null && !it.hidden }
    Text(if (selectedTop == null) "Most used now" else "Category", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 12.dp, bottom = 4.dp))
    if (selectedTop == null) {
        FlowRow {
            ranked.take(if (wide) 8 else 6).forEach { c -> Chip("${emojiOf(c)}${c.name}", selected = false) { onPick(c) } }
            Chip("All $total categories", selected = false, dashed = true, onClick = onOpenAll)
        }
        return
    }
    FlowRow {
        Chip("${emojiOf(selectedTop)}${selectedTop.name}", selected = true, onClick = onClear)
        Chip("Change", selected = false, dashed = true, onClick = onClear)
    }
    val subs = subsRanked(ledger, selectedTop)
    if (subs.isEmpty()) return
    val chosenSub = selected?.takeIf { it.parentId == selectedTop.id }
    FlowRow(Modifier.padding(top = 4.dp)) {
        if (subs.size > 5) {
            if (chosenSub != null) {
                Chip(chosenSub.name, selected = true, small = true) { onPick(selectedTop) }
                Chip("Change subcategory", selected = false, small = true, dashed = true, onClick = onOpenSubs)
            } else {
                subs.take(5).forEach { s -> Chip(s.name, selected = false, small = true) { onPick(s) } }
                Chip("All ${subs.size}", selected = false, small = true, dashed = true, onClick = onOpenSubs)
            }
        } else {
            subs.forEach { s -> Chip(s.name, selected = s.id == chosenSub?.id, small = true) { if (s.id == chosenSub?.id) onPick(selectedTop) else onPick(s) } }
        }
    }
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
    Row(Modifier.fillMaxWidth().padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Split with people", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.weight(1f))
        Switch(checked = on, onCheckedChange = setOn, colors = SwitchDefaults.colors(checkedTrackColor = Kassa.colors.ink, checkedThumbColor = Kassa.colors.onInk))
    }
    if (!on) return
    FlowRow {
        ledger.people.people.filter { !it.archived }.forEach { p ->
            val sel = p.id in with
            Chip(p.name, selected = sel) { if (sel) with.remove(p.id) else with.add(p.id) }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("Add a person") }, modifier = Modifier.weight(1f))
        TextButton(onClick = {
            val n = newName.trim()
            if (n.isNotEmpty()) { onAddPerson(n) { id -> with.add(id) }; newName = "" }
        }) { Text("Add", color = Kassa.colors.ink) }
    }
    Text(
        if (with.isEmpty()) "Pick who shared this." else "Split equally ${with.size + 1} ways. Only your share counts as spending; the rest is owed to you.",
        style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun FieldRow(label: String, value: String, onClick: () -> Unit) {
    Column(Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().height(50.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.weight(1f))
            Text(value, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1)
        }
        HorizontalDivider(color = Kassa.colors.line)
    }
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
        Text(label, style = if (small) KassaType.small else KassaType.body, color = if (selected) Kassa.colors.onInk else if (dashed) Kassa.colors.ink2 else Kassa.colors.ink, maxLines = 1)
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
private fun NumberPad(onKey: (String) -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "del")
    Column(Modifier.padding(top = 16.dp)) {
        keys.chunked(3).forEach { row ->
            Row {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).padding(3.dp).height(52.dp).clip(RoundedCornerShape(14.dp))
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

private fun accountName(ledger: Ledger, id: Long?): String = id?.let { ledger.accountById[it]?.name } ?: "Choose"

private fun dateLabel(d: LocalDate, today: LocalDate): String = when (d) {
    today -> "Today"
    today.minusDays(1) -> "Yesterday"
    else -> d.format(DateTimeFormatter.ofPattern("EEE, MMM d, yyyy"))
}

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

private fun subsRanked(ledger: Ledger, top: Category): List<Category> {
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
    return pool.filter { q.isEmpty() || (it.lowercase().contains(q) && it.lowercase() != q) }.take(4)
}
