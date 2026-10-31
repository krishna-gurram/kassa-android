package app.kassa.money.ui.entry

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kassa.money.data.repo.EntryDraft
import app.kassa.money.domain.CardSnapshot
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.Money
import app.kassa.money.domain.TxKind
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.ui.common.PageTop
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.YearMonth

/**
 * The add and edit page. The amount sits big at the top, then three tiles (category, account, date; for a transfer
 * from, to and date), a note and the split line. Tapping any of them opens its input in one panel at the bottom
 * (see EntryPanels.kt). Picking moves on to the next empty one, so a new entry is a few taps without scrolling.
 * Editing opens the same page filled in, with the panel closed. Leaving with unsaved changes asks first.
 *
 * What's typed and the rules for saving live in [EntryState]; this file only lays the page out.
 */
@Composable
fun EntryPage(
    ledger: Ledger,
    start: EntryStart,
    contentPadding: PaddingValues,
    loadNotes: suspend (TxKind) -> List<String>,
    onSave: (EntryDraft) -> Unit,
    onDelete: (Long) -> Unit,
    onBack: () -> Unit,
) {
    val s = rememberSaveable(saver = EntryState.saver(start, ledger.zone, ledger.today)) { EntryState(start, ledger.zone, ledger.today) }
    // Left open past midnight, or carried to another time zone: "today" and the zone follow the phone.
    LaunchedEffect(ledger.zone, ledger.today) { s.updateClock(ledger.zone, ledger.today) }
    var notes by remember { mutableStateOf<List<String>>(emptyList()) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmDiscard by remember { mutableStateOf(false) }
    val leave: () -> Unit = { if (s.dirty) confirmDiscard = true else onBack() }
    BackHandler(enabled = s.dirty) { confirmDiscard = true }

    val focus = LocalFocusManager.current
    val noteFocus = remember { FocusRequester() }
    LaunchedEffect(s.field) {
        s.query = ""; s.searching = false
        if (s.field == Field.NOTE) runCatching { noteFocus.requestFocus() } else focus.clearFocus()
    }
    LaunchedEffect(s.kind) { notes = if (s.kind == TxKind.TRANSFER) emptyList() else loadNotes(s.kind) }

    val accountChoices = remember(s.kind, ledger.recent.size, ledger.accounts.accounts) { openAccounts(ledger) }
    val toChoices = remember(ledger.recent.size, ledger.accounts.accounts) { openAccounts(ledger) }
    val cards = remember(ledger) { ledger.cards().associate { it.account.id to it.snapshot } }
    val toCard = if (s.kind == TxKind.TRANSFER) s.toAccountId?.let { cards[it] } else null

    val save: () -> Unit = {
        val problem = s.problem()
        if (problem != null) { s.field = problem.first; s.error = problem.second } else onSave(s.toDraft())
    }
    val saveLabel = when {
        s.editing != null -> "Save changes"
        s.kind == TxKind.INCOME -> "Add income"
        s.kind == TxKind.TRANSFER -> if (toCard != null) "Record card payment" else "Record transfer"
        else -> "Add expense"
    }
    val title = when {
        s.editing != null -> "Edit " + when (s.kind) { TxKind.INCOME -> "income"; TxKind.TRANSFER -> "transfer"; else -> "expense" }
        start.prefill != null -> "Review entry"
        else -> "New entry"
    }

    Column(Modifier.fillMaxSize().padding(contentPadding).imePadding()) {
        PageTop(title, onBack = leave) {
            if (s.editing != null) TextButton(onClick = { confirmDelete = true }) { Text("Delete", color = Kassa.colors.attention, style = KassaType.bodyStrong) }
            else TextButton(onClick = save) { Text("Save", color = Kassa.colors.ink, style = KassaType.bodyStrong) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            val wide = maxWidth >= 600.dp
            val panel: @Composable (Modifier) -> Unit = { m ->
                Panel(m) {
                    when (s.field) {
                        // The pad is the resting state: with nothing else open, it stays up with the save button under it.
                        Field.AMOUNT, null -> AmountPanel(s, toCard, saveLabel, save)
                        Field.CATEGORY -> CategoryPanel(s, ledger, wide)
                        Field.SUB -> {
                            val top = s.categoryId?.let { ledger.categoryById[it] }?.let { c -> c.parentId?.let { ledger.categoryById[it] } ?: c }
                            if (top == null) s.field = Field.CATEGORY else SubcategoryPanel(s, ledger, top, wide)
                        }
                        Field.ACCOUNT, Field.TO -> AccountPanel(s, accountChoices, toChoices, cards, wide)
                        Field.DATE -> DatePanel(s, ledger.today)
                        Field.SPLIT -> SplitPanel(s, ledger)
                        Field.NOTE -> NotePanel(s, ledger, notes)
                    }
                }
            }
            if (wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).widthIn(max = 520.dp)) { EntryForm(s, ledger, cards, toCard, wide, noteFocus) }
                    panel(Modifier.weight(1f).fillMaxHeight().padding(bottom = 12.dp))
                }
            } else {
                Column(Modifier.fillMaxSize()) {
                    if (!s.searching) {
                        Column(Modifier.padding(horizontal = 16.dp)) { EntryForm(s, ledger, cards, toCard, wide, noteFocus) }
                        Spacer(Modifier.height(10.dp))
                    }
                    panel(Modifier.weight(1f).fillMaxWidth())
                }
            }
        }
    }

    val editing = s.editing
    if (confirmDelete && editing != null) ConfirmDialog(
        title = "Delete this entry?",
        body = "${editing.note.ifBlank { "This entry" }}, ${Money.format(editing.amount)}. Balances update right away. This can't be undone.",
        confirm = "Delete", dismiss = "Keep it",
        onConfirm = { onDelete(editing.id) }, onDismiss = { confirmDelete = false },
    )
    if (confirmDiscard) ConfirmDialog(
        title = if (editing != null) "Discard your changes?" else "Discard this entry?",
        body = if (editing != null) "The entry stays as it was before you opened it." else "Nothing you typed here will be saved.",
        confirm = "Discard", dismiss = "Keep editing",
        onConfirm = onBack, onDismiss = { confirmDiscard = false },
    )
}

/** The top half: kind, amount, the three tiles, the note and the split line. */
@Composable
private fun EntryForm(s: EntryState, ledger: Ledger, cards: Map<Long, CardSnapshot>, toCard: CardSnapshot?, wide: Boolean, noteFocus: FocusRequester) {
    val category = s.categoryId?.let { ledger.categoryById[it] }
    val topCategory = category?.let { c -> c.parentId?.let { ledger.categoryById[it] } ?: c }
    val people = ledger.people.people.associateBy { it.id }

    KindSwitch(s.kind, onSelect = { s.switchKind(it, ledger.accountById) }, modifier = Modifier.fillMaxWidth())
    AmountHero(s.amount, income = s.kind == TxKind.INCOME, large = wide) { s.field = Field.AMOUNT }
    if (s.kind == TxKind.TRANSFER && s.toAccountId != null) {
        Text(if (toCard != null) "Card payment" else "Transfer", style = KassaType.tiny, color = Kassa.colors.ink2,
            textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp))
    }
    Row(
        Modifier.fillMaxWidth().height(IntrinsicSize.Min).padding(top = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        if (s.kind == TxKind.TRANSFER) {
            AccountTile("From", ledger.accountById[s.accountId], cards, showDue = true, twoLines = true, on = s.field == Field.ACCOUNT, modifier = Modifier.weight(1f)) { s.field = Field.ACCOUNT }
            Text("→", style = KassaType.body, color = Kassa.colors.ink3)
            AccountTile("To", ledger.accountById[s.toAccountId], cards, showDue = true, twoLines = true, on = s.field == Field.TO, modifier = Modifier.weight(1f)) { s.field = Field.TO }
        } else {
            CategoryTile(topCategory, category?.takeIf { it.parentId != null }, on = s.field == Field.CATEGORY || s.field == Field.SUB, modifier = Modifier.weight(1.15f)) { s.field = Field.CATEGORY }
            if (s.someoneElsePaid) Tile("Paid by", on = s.field == Field.SPLIT, empty = false, modifier = Modifier.weight(1.15f), onClick = { s.field = Field.SPLIT }) {
                Text(people[s.paidBy]?.name ?: "Someone", style = KassaType.bodyStrong.copy(fontSize = 14.sp), color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("Not your account", style = KassaType.tiny.copy(fontSize = 11.sp), color = Kassa.colors.ink3, maxLines = 1)
            }
            else AccountTile(
                if (s.kind == TxKind.INCOME) "Into" else "Paid with", ledger.accountById[s.accountId], cards,
                showDue = s.kind == TxKind.EXPENSE, twoLines = false, on = s.field == Field.ACCOUNT, modifier = Modifier.weight(1.15f),
            ) { s.field = Field.ACCOUNT }
        }
        DateTile(s.date, on = s.field == Field.DATE, modifier = Modifier.weight(0.7f)) { s.calendarMonth = YearMonth.from(s.date); s.field = Field.DATE }
    }
    NoteRow(
        s.note, s::typeNote, focused = s.field == Field.NOTE,
        hint = if (s.kind == TxKind.TRANSFER) "Add a note" else "Merchant or note",
        requester = noteFocus,
        onFocused = { s.field = Field.NOTE },
        onDone = { s.field = null },
    )
    if (s.kind == TxKind.EXPENSE && ledger.people.people.any { !it.archived } || s.shared) SplitRow(
        who = if (s.shared) (listOf("You") + s.splitPeople.map { people[it]?.name ?: "?" }).joinToString(", ") else "Just you",
        mine = if (s.shared) s.myShare else null, on = s.field == Field.SPLIT,
    ) { s.field = Field.SPLIT; s.editingShare = null }
    s.error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention, modifier = Modifier.padding(top = 6.dp)) }
}
