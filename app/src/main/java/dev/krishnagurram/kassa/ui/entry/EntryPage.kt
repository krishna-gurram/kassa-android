package dev.krishnagurram.kassa.ui.entry

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krishnagurram.kassa.data.EntryDraft
import dev.krishnagurram.kassa.domain.Account
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.CategoryKind
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.CategoryIconView
import dev.krishnagurram.kassa.ui.common.ConfirmDialog
import dev.krishnagurram.kassa.ui.common.PageTop
import dev.krishnagurram.kassa.ui.common.accountIcon
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/** What the page opens with: a blank entry, an existing one to edit, or a prefilled one (a bank alert or bill). */
data class EntryStart(
    val editing: Transaction? = null,
    val splitWith: List<Long> = emptyList(),
    val prefill: EntryDraft? = null,
    /** Runs after a successful save, for example to clear the alert or bill that opened the page. */
    val afterSave: (() -> Unit)? = null,
)

private const val MAX_DIGITS = 8

/** The input the bottom panel is showing. Null means none: the panel gives way to the save button. */
private enum class Field { AMOUNT, CATEGORY, SUB, ACCOUNT, TO, DATE, NOTE }

/**
 * The add and edit page. The amount sits big at the top, then three tiles (category, account, date; for a transfer
 * from, to and date) and a note. Tapping any of them opens its input in one panel at the bottom: a number pad,
 * a grid of categories then subcategories, a grid of accounts with card numbers and what's due, or a calendar.
 * Picking moves on to the next empty one, so a new entry is a few taps without scrolling.
 * Editing opens the same page filled in, with the panel closed. Leaving with unsaved changes asks first.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EntryPage(
    ledger: Ledger,
    start: EntryStart,
    contentPadding: PaddingValues,
    loadNotes: suspend (TxKind) -> List<String>,
    onSave: (EntryDraft) -> Unit,
    onDelete: (Long) -> Unit,
    @Suppress("UNUSED_PARAMETER") onAddPerson: (String, (Long) -> Unit) -> Unit,
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
    var error by remember { mutableStateOf<String?>(null) }
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    var field by remember {
        mutableStateOf(
            when {
                e != null -> null
                amount.isEmpty() -> Field.AMOUNT
                p != null && categoryId == null && kind != TxKind.TRANSFER -> Field.CATEGORY
                else -> null
            }
        )
    }
    var calMonth by remember { mutableStateOf(YearMonth.from(date)) }
    val initial = remember { listOf(kind, amount, categoryId, accountId, toAccountId, date, note) }
    val dirty = listOf(kind, amount, categoryId, accountId, toAccountId, date, note) != initial
    val leave: () -> Unit = { if (dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = dirty) { confirmDiscard = true }

    val focus = LocalFocusManager.current
    val noteFocus = remember { FocusRequester() }
    LaunchedEffect(field) {
        if (field == Field.NOTE) runCatching { noteFocus.requestFocus() } else focus.clearFocus()
    }
    LaunchedEffect(kind) { notes = if (kind == TxKind.TRANSFER) emptyList() else loadNotes(kind) }

    val cents = Money.parseCents(amount) ?: 0L
    val cat = categoryId?.let { ledger.categoryById[it] }
    val topCat = cat?.let { c -> c.parentId?.let { ledger.categoryById[it] } ?: c }
    val catKind = if (kind == TxKind.INCOME) CategoryKind.INCOME else CategoryKind.EXPENSE
    val ranked = remember(kind, ledger.recent.size) { rankTop(ledger, catKind) }
    val rankedAccounts = remember(kind, ledger.recent.size, ledger.accounts.accounts.size) { rankAccounts(ledger, kind, to = false) }
    val rankedTo = remember(ledger.recent.size, ledger.accounts.accounts.size) { rankAccounts(ledger, TxKind.TRANSFER, to = true) }
    val cards = remember(ledger) { ledger.cards().associate { it.account.id to it.snapshot } }
    val toCard = if (kind == TxKind.TRANSFER) toAccountId?.let { cards[it] } else null

    /** The next required input still empty, in the order the tiles read. */
    fun missing(): Field? = when (kind) {
        TxKind.TRANSFER -> when { accountId == null -> Field.ACCOUNT; toAccountId == null -> Field.TO; else -> null }
        else -> when { categoryId == null -> Field.CATEGORY; accountId == null -> Field.ACCOUNT; else -> null }
    }

    /** After a pick: the next empty input, then the note for a new entry, then nothing (the save button). */
    fun advance() {
        error = null
        field = missing() ?: if (cents <= 0) Field.AMOUNT else if (e == null && note.isBlank() && kind != TxKind.TRANSFER) Field.NOTE else null
    }

    fun pickCategory(c: Category) {
        categoryId = c.id
        if (!accountTouched && kind == TxKind.EXPENSE) defaultAccountFor(ledger, c)?.let { accountId = it }
        error = null
    }

    fun switchKind(k: TxKind) {
        if (k == kind) return
        kind = k; categoryId = null; error = null
        if (!accountTouched) accountId = lastUsedAccount(ledger, k)
        if (k == TxKind.TRANSFER && accountId?.let { ledger.accountById[it]?.type?.hasStatement } == true) accountId = null
        if (k != TxKind.TRANSFER) toAccountId = null
        field = if (amount.isEmpty()) Field.AMOUNT else missing()
    }

    fun save() {
        val millis = millisFor(date, originalMillis, ledger)
        val problem = when {
            cents <= 0 -> Field.AMOUNT to "Enter an amount."
            kind != TxKind.TRANSFER && categoryId == null -> Field.CATEGORY to "Pick a category."
            accountId == null -> Field.ACCOUNT to "Pick an account."
            kind == TxKind.TRANSFER && (toAccountId == null || toAccountId == accountId) -> Field.TO to "Pick where the money went."
            else -> null
        }
        if (problem != null) { field = problem.first; error = problem.second; return }
        onSave(
            EntryDraft(
                id = e?.id, kind = kind, amount = cents, accountId = accountId,
                toAccountId = if (kind == TxKind.TRANSFER) toAccountId else null,
                categoryId = if (kind == TxKind.TRANSFER) null else categoryId, personId = null,
                epochMillis = millis, note = note.trim(),
                // Splits are kept as they were when editing; the split row comes with the reimbursement rework.
                splitWith = if (kind == TxKind.EXPENSE) start.splitWith else emptyList(),
            )
        )
    }

    val saveLabel = when {
        e != null -> "Save changes"
        kind == TxKind.INCOME -> "Add income"
        kind == TxKind.TRANSFER -> if (toCard != null) "Record card payment" else "Record transfer"
        else -> "Add expense"
    }
    val kindWord = when (kind) { TxKind.INCOME -> "income"; TxKind.TRANSFER -> "transfer"; else -> "expense" }

    Column(Modifier.fillMaxSize().padding(contentPadding).imePadding()) {
        PageTop(
            when { e != null -> "Edit $kindWord"; p != null -> "Review entry"; else -> "New entry" },
            onBack = leave,
        ) {
            if (e != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Kassa.colors.attention, style = KassaType.bodyStrong) }
            else TextButton(onClick = ::save) { Text("Save", color = Kassa.colors.ink, style = KassaType.bodyStrong) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 600.dp

            val form: @Composable ColumnScope.() -> Unit = {
                Segmented(
                    options = listOf(TxKind.EXPENSE to "Expense", TxKind.INCOME to "Income", TxKind.TRANSFER to "Transfer"),
                    selected = kind, onSelect = ::switchKind, modifier = Modifier.fillMaxWidth(),
                )
                AmountHero(amount, focused = field == Field.AMOUNT || field == null, income = kind == TxKind.INCOME, large = wide) { field = Field.AMOUNT }
                if (kind == TxKind.TRANSFER && toAccountId != null) {
                    Text(
                        if (toCard != null) "Card payment" else "Transfer", style = KassaType.tiny, color = Kassa.colors.ink2,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
                    )
                }
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (kind == TxKind.TRANSFER) {
                        AccountTile("From", ledger.accountById[accountId], cards, showDue = true, twoLines = true, on = field == Field.ACCOUNT, modifier = Modifier.weight(1f)) { field = Field.ACCOUNT }
                        Text("→", style = KassaType.body, color = Kassa.colors.ink3)
                        AccountTile("To", ledger.accountById[toAccountId], cards, showDue = true, twoLines = true, on = field == Field.TO, modifier = Modifier.weight(1f)) { field = Field.TO }
                    } else {
                        CategoryTile(topCat, cat?.takeIf { it.parentId != null }, on = field == Field.CATEGORY || field == Field.SUB, modifier = Modifier.weight(1.15f)) { field = Field.CATEGORY }
                        AccountTile(if (kind == TxKind.INCOME) "Into" else "Paid with", ledger.accountById[accountId], cards, showDue = kind == TxKind.EXPENSE, twoLines = false, on = field == Field.ACCOUNT, modifier = Modifier.weight(1.15f)) { field = Field.ACCOUNT }
                    }
                    DateTile(date, on = field == Field.DATE, modifier = Modifier.weight(0.7f)) { calMonth = YearMonth.from(date); field = Field.DATE }
                }
                NoteRow(
                    note, { note = it }, focused = field == Field.NOTE,
                    hint = if (kind == TxKind.TRANSFER) "Add a note" else "Merchant or note",
                    requester = noteFocus,
                    onFocused = { field = Field.NOTE },
                    onDone = { field = null },
                )
                error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention, modifier = Modifier.padding(top = 6.dp)) }
            }

            val panel: @Composable (Modifier) -> Unit = { m ->
                Panel(m) {
                    when (field) {
                        // The pad is the resting state: with nothing else open, it stays up with the save button under it.
                        Field.AMOUNT, null -> {
                            PanelTitle("Amount", onDone = null)
                            if (toCard != null) {
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
                                    QuickButton("Payable ${Money.format(toCard.payable)}", on = cents == toCard.payable, modifier = Modifier.weight(1f)) { amount = centsToInput(toCard.payable); error = null }
                                    QuickButton("Total ${Money.format(toCard.totalBalance)}", on = cents == toCard.totalBalance, modifier = Modifier.weight(1f)) { amount = centsToInput(toCard.totalBalance); error = null }
                                }
                            }
                            NumberPad(Modifier.weight(1f)) { k -> amount = applyKey(amount, k); error = null }
                            val next = missing()
                            if (next != null) PrimaryButton(
                                when (next) {
                                    Field.CATEGORY -> "Next: category"
                                    Field.ACCOUNT -> if (kind == TxKind.TRANSFER) "Next: from" else if (kind == TxKind.INCOME) "Next: into" else "Next: paid with"
                                    else -> "Next: to"
                                },
                            ) { error = null; field = next }
                            else PrimaryButton(saveLabel, ::save)
                        }
                        Field.CATEGORY -> {
                            PanelTitle("Category") { field = null }
                            LazyVerticalGrid(GridCells.Fixed(if (wide) 4 else 3), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(ranked, key = { it.id }) { c ->
                                    PickTile(on = topCat?.id == c.id, minHeight = 66.dp, onClick = {
                                        pickCategory(c)
                                        if (subsRanked(ledger, c).isNotEmpty()) field = Field.SUB else advance()
                                    }) {
                                        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                                            CategoryIconView(c.icon, Color(c.color), 34, fallback = c.name.take(1))
                                            Text(c.name, style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                                        }
                                    }
                                }
                            }
                        }
                        Field.SUB -> {
                            val t = topCat
                            if (t == null) { field = Field.CATEGORY } else {
                                PanelTitle(t.name, back = { field = Field.CATEGORY }) { field = null }
                                LazyVerticalGrid(GridCells.Fixed(if (wide) 3 else 2), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    item(key = "none") {
                                        PickTile(on = cat?.id == t.id, onClick = { pickCategory(t); advance() }) {
                                            Text("No subcategory", style = KassaType.bodyStrong, color = Kassa.colors.ink2)
                                        }
                                    }
                                    items(subsRanked(ledger, t), key = { it.id }) { s ->
                                        PickTile(on = cat?.id == s.id, onClick = { pickCategory(s); advance() }) {
                                            Text(s.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                    }
                                }
                            }
                        }
                        Field.ACCOUNT, Field.TO -> {
                            val isTo = field == Field.TO
                            PanelTitle(when { isTo -> "To"; kind == TxKind.TRANSFER -> "From"; kind == TxKind.INCOME -> "Into"; else -> "Paid with" }) { field = null }
                            val list = if (isTo) rankedTo.filter { it.id != accountId }
                            else rankedAccounts.filter { kind != TxKind.TRANSFER || !it.type.hasStatement || it.id == accountId }
                            val selected = if (isTo) toAccountId else accountId
                            LazyVerticalGrid(GridCells.Fixed(if (wide) 3 else 2), Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                items(list, key = { it.id }) { a ->
                                    PickTile(on = selected == a.id, onClick = {
                                        if (isTo) {
                                            toAccountId = a.id
                                            error = null
                                            field = if (cards[a.id]?.payable?.let { it > 0 } == true && cents <= 0) Field.AMOUNT else missing() ?: if (cents <= 0) Field.AMOUNT else null
                                        } else {
                                            accountId = a.id; accountTouched = true
                                            if (toAccountId == a.id) toAccountId = null
                                            advance()
                                        }
                                    }) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(a.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                                a.last4?.takeIf { it.isNotBlank() }?.let { Text("  ··$it", style = KassaType.tiny, color = Kassa.colors.ink3, maxLines = 1) }
                                            }
                                            val due = cards[a.id]?.payable ?: 0L
                                            if (due > 0 && kind != TxKind.INCOME) Text("${Money.format(due)} due", style = KassaType.tiny.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.attention)
                                        }
                                    }
                                }
                            }
                        }
                        Field.DATE -> {
                            PanelTitle("Date") { field = null }
                            val after: () -> Unit = { error = null; field = if (cents <= 0) Field.AMOUNT else null }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                                QuickButton("Today", on = date == ledger.today, modifier = Modifier.weight(1f)) { date = ledger.today; after() }
                                QuickButton("Yesterday", on = date == ledger.today.minusDays(1), modifier = Modifier.weight(1f)) { date = ledger.today.minusDays(1); after() }
                            }
                            MonthCalendar(calMonth, date, ledger.today, onMonth = { calMonth = it }, modifier = Modifier.weight(1f)) { date = it; after() }
                        }
                        Field.NOTE -> {
                            PanelTitle("Note") { field = null }
                            val sugg = suggestions(notes, note, ledger, kind, categoryId)
                            if (sugg.isEmpty()) Text("Type a merchant or a note.", style = KassaType.small, color = Kassa.colors.ink3, modifier = Modifier.padding(4.dp))
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                sugg.forEach { s ->
                                    Box(
                                        Modifier.clip(RoundedCornerShape(50)).background(Kassa.colors.ground).clickable {
                                            note = s
                                            if (categoryId == null) ledger.recent.firstOrNull { it.note == s && it.kind == kind }?.let { last ->
                                                categoryId = last.categoryId
                                                if (!accountTouched && last.accountId != null) accountId = last.accountId
                                            }
                                            field = missing()
                                        }.padding(horizontal = 14.dp, vertical = 9.dp),
                                    ) { Text(s, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1) }
                                }
                            }
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).widthIn(max = 520.dp)) { form() }
                    panel(Modifier.weight(1f).fillMaxHeight().padding(bottom = 12.dp))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    Column(Modifier.padding(horizontal = 16.dp)) { form() }
                    Spacer(Modifier.height(10.dp))
                    panel(Modifier.weight(1f).fillMaxWidth())
                }
            }
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

/* ---------- the top half ---------- */

@Composable
internal fun AmountHero(amount: String, focused: Boolean, income: Boolean, large: Boolean, onClick: () -> Unit) {
    val size = if (large) 60f else 52f
    val color = when { amount.isEmpty() -> Kassa.colors.ink3; income -> Kassa.colors.positive; else -> Kassa.colors.ink }
    val ink2 = Kassa.colors.ink2
    Box(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(top = 8.dp, bottom = 6.dp), contentAlignment = Alignment.Center) {
        Text(
            buildAnnotatedString {
                withStyle(SpanStyle(fontSize = (size * 0.5f).sp, color = ink2, baselineShift = androidx.compose.ui.text.style.BaselineShift(0.6f))) { append(Money.symbol) }
                append(displayAmount(amount))
            },
            style = KassaType.hero.copy(fontSize = size.sp, letterSpacing = (-1.5).sp, textDecoration = if (focused) TextDecoration.Underline else null),
            color = color, maxLines = 1,
        )
    }
}

@Composable
internal fun Tile(label: String, on: Boolean, empty: Boolean, modifier: Modifier, onClick: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier.fillMaxHeight().heightIn(min = 88.dp).clip(shape).background(Kassa.colors.raised)
            .border(1.5.dp, if (on) Kassa.colors.ink else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(label, style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2)
        if (empty) {
            Text("+", style = KassaType.body.copy(fontSize = 18.sp), color = Kassa.colors.ink2)
            Text("Choose", style = KassaType.body, color = Kassa.colors.ink3)
        } else content()
    }
}

@Composable
internal fun CategoryTile(top: Category?, sub: Category?, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Tile("Category", on, empty = top == null, modifier = modifier, onClick = onClick) {
        if (top != null) {
            CategoryIconView(top.icon, Color(top.color), 28, fallback = top.name.take(1))
            Text(top.name, style = KassaType.bodyStrong.copy(fontSize = 14.sp), color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub.name, style = KassaType.tiny.copy(fontSize = 11.sp), color = Kassa.colors.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun AccountTile(label: String, a: Account?, cards: Map<Long, dev.krishnagurram.kassa.domain.CardSnapshot>, showDue: Boolean, twoLines: Boolean, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Tile(label, on, empty = a == null, modifier = modifier, onClick = onClick) {
        if (a != null) {
            Icon(accountIcon(a.type), null, tint = Kassa.colors.ink2, modifier = Modifier.size(18.dp))
            Text(a.name, style = KassaType.bodyStrong.copy(fontSize = 14.sp, lineHeight = 17.sp), color = Kassa.colors.ink, maxLines = if (twoLines) 2 else 1, overflow = TextOverflow.Ellipsis)
            a.last4?.takeIf { it.isNotBlank() }?.let { Text("··$it", style = KassaType.tiny.copy(fontSize = 11.sp), color = Kassa.colors.ink3) }
            val due = cards[a.id]?.payable ?: 0L
            if (showDue && due > 0) Text("${Money.format(due)} due", style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = Kassa.colors.attention, maxLines = 1)
        }
    }
}

@Composable
private fun DateTile(date: LocalDate, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Tile("Date", on, empty = false, modifier = modifier, onClick = onClick) {
        Text(
            date.month.getDisplayName(TextStyle.SHORT, Locale.US).uppercase(),
            style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.6.sp), color = Kassa.colors.ink2,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(date.dayOfMonth.toString(), style = KassaType.hero.copy(fontSize = 26.sp, letterSpacing = 0.sp, lineHeight = 28.sp), color = Kassa.colors.ink)
    }
}

@Composable
internal fun NoteRow(value: String, onChange: (String) -> Unit, focused: Boolean, hint: String, requester: FocusRequester, onFocused: () -> Unit, onDone: () -> Unit) {
    BasicTextField(
        value = value, onValueChange = onChange, singleLine = true,
        textStyle = KassaType.body.copy(color = Kassa.colors.ink),
        cursorBrush = SolidColor(Kassa.colors.ink),
        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { onDone() }),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).focusRequester(requester).onFocusChanged { if (it.isFocused) onFocused() },
        decorationBox = { inner ->
            Column {
                Box(Modifier.padding(horizontal = 2.dp, vertical = 10.dp)) {
                    if (value.isEmpty()) Text(hint, style = KassaType.body, color = Kassa.colors.ink3)
                    inner()
                }
                Box(Modifier.fillMaxWidth().height(if (focused) 2.dp else 1.dp).background(if (focused) Kassa.colors.ink else Kassa.colors.line))
            }
        },
    )
}

/* ---------- the panel ---------- */

@Composable
internal fun Panel(modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp, bottomStart = 0.dp, bottomEnd = 0.dp))
            .background(Kassa.colors.raised).padding(start = 12.dp, end = 12.dp, top = 6.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        content = content,
    )
}

@Composable
internal fun PanelTitle(title: String, back: (() -> Unit)? = null, onDone: (() -> Unit)?) {
    Row(Modifier.fillMaxWidth().height(40.dp), verticalAlignment = Alignment.CenterVertically) {
        if (back != null) Text("‹", style = KassaType.body.copy(fontSize = 22.sp), color = Kassa.colors.ink, modifier = Modifier.clip(CircleShape).clickable(onClick = back).padding(horizontal = 8.dp))
        Text(title, style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2, modifier = Modifier.weight(1f).padding(start = 4.dp))
        if (onDone != null) Text("Done", style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onDone).padding(horizontal = 8.dp, vertical = 6.dp))
    }
}

@Composable
internal fun PickTile(on: Boolean, minHeight: Dp = 54.dp, onClick: () -> Unit, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        Modifier.fillMaxWidth().heightIn(min = minHeight).clip(shape).background(Kassa.colors.ground)
            .border(1.5.dp, if (on) Kassa.colors.ink else Color.Transparent, shape)
            .clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}

@Composable
internal fun QuickButton(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Box(
        modifier.height(44.dp).clip(shape).background(Kassa.colors.ground)
            .border(1.5.dp, if (on) Kassa.colors.ink else Color.Transparent, shape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(label, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
}

/** A month of days starting on Sunday. Days after today can't be picked; neither can months after this one. */
@Composable
private fun MonthCalendar(month: YearMonth, selected: LocalDate, today: LocalDate, onMonth: (YearMonth) -> Unit, modifier: Modifier, onPick: (LocalDate) -> Unit) {
    Column(modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("‹", style = KassaType.body.copy(fontSize = 22.sp), color = Kassa.colors.ink, modifier = Modifier.clip(CircleShape).clickable { onMonth(month.minusMonths(1)) }.padding(horizontal = 12.dp))
            Text(month.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.US)), style = KassaType.bodyStrong, color = Kassa.colors.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            val canNext = month < YearMonth.from(today)
            Text("›", style = KassaType.body.copy(fontSize = 22.sp), color = if (canNext) Kassa.colors.ink else Kassa.colors.ink3.copy(alpha = 0.4f),
                modifier = Modifier.clip(CircleShape).clickable(enabled = canNext) { onMonth(month.plusMonths(1)) }.padding(horizontal = 12.dp))
        }
        Row(Modifier.fillMaxWidth()) {
            listOf(DayOfWeek.SUNDAY, DayOfWeek.MONDAY, DayOfWeek.TUESDAY, DayOfWeek.WEDNESDAY, DayOfWeek.THURSDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY).forEach {
                Text(it.getDisplayName(TextStyle.NARROW, Locale.US), style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink3, textAlign = TextAlign.Center, modifier = Modifier.weight(1f).padding(vertical = 2.dp))
            }
        }
        val lead = month.atDay(1).dayOfWeek.value % 7
        val cells = List(lead) { null } + (1..month.lengthOfMonth()).map { month.atDay(it) }
        val weeks = cells.chunked(7)
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth().weight(1f, fill = false).heightIn(max = 40.dp)) {
                (0 until 7).forEach { i ->
                    val d = week.getOrNull(i)
                    Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.Center) {
                        if (d != null) {
                            val future = d.isAfter(today)
                            val sel = d == selected
                            Box(
                                Modifier.size(34.dp).clip(CircleShape).background(if (sel) Kassa.colors.ink else Color.Transparent)
                                    .clickable(enabled = !future) { onPick(d) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Text(
                                    d.dayOfMonth.toString(),
                                    style = KassaType.body.copy(fontWeight = if (d == today) FontWeight.Bold else FontWeight.Medium, textDecoration = if (d == today && !sel) TextDecoration.Underline else null),
                                    color = when { sel -> Kassa.colors.onInk; future -> Kassa.colors.ink3.copy(alpha = 0.4f); else -> Kassa.colors.ink },
                                )
                            }
                        }
                    }
                }
            }
        }
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

/** Keys that fill whatever height the panel has, so the pad is as big as the screen allows. */
@Composable
internal fun NumberPad(modifier: Modifier, onKey: (String) -> Unit) {
    val keys = listOf("1", "2", "3", "4", "5", "6", "7", "8", "9", ".", "0", "del")
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        keys.chunked(3).forEach { row ->
            Row(Modifier.weight(1f).heightIn(max = 64.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                row.forEach { k ->
                    Box(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(12.dp))
                            .background(Kassa.colors.ground).clickable { onKey(k) },
                        contentAlignment = Alignment.Center,
                    ) { Text(if (k == "del") "⌫" else k, style = KassaType.body.copy(fontSize = 24.sp, fontWeight = FontWeight.Medium), color = Kassa.colors.ink, textAlign = TextAlign.Center) }
                }
            }
        }
    }
}

/* ---------- helpers ---------- */

internal fun applyKey(current: String, k: String): String = when (k) {
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
    val grouped = whole.toLongOrNull()?.let { java.text.NumberFormat.getIntegerInstance(Money.style.locale).format(it) } ?: whole
    return if (s.contains('.')) grouped + "." + s.substringAfter('.') else grouped
}

internal fun centsToInput(c: Long): String {
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
internal fun rankTop(ledger: Ledger, kind: CategoryKind): List<Category> {
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
