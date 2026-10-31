package app.kassa.money.ui.accounts

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.data.repo.EntryDraft
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.Money
import app.kassa.money.domain.Person
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.ui.common.shortDateYear
import app.kassa.money.ui.common.shortDate
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.common.favourColor
import app.kassa.money.ui.common.balanceColor
import app.kassa.money.ui.common.AmountField
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.ui.common.DateRow
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.momentOn
import app.kassa.money.ui.common.rememberSavedDate
import app.kassa.money.ui.common.PageTop
import app.kassa.money.ui.common.RaisedPanel
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.common.TransactionRow
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.importer.SecondaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import app.kassa.money.ui.today.BigMoney
import java.time.Instant

/** One line of a person's history: a split share or a payment. [amount] is positive when it adds to what they owe you. */
/** [payment] lines settle up; the rest (splits, starting balance) change what's owed. */
private data class PersonHistoryLine(val millis: Long, val note: String, val amount: Long, val transactionId: Long?, val payment: Boolean = false)

/** Splits and payments with [p], newest first. */
private fun historyOf(p: Person, ledger: Ledger): List<PersonHistoryLine> {
    val splits = ledger.people.loans.filter { it.personId == p.id }
        .map { PersonHistoryLine(it.epochMillis, it.note.ifBlank { "Split" }, it.amount, it.transactionId) }
    val payments = ledger.people.payments.filter { it.personId == p.id }.map { t ->
        val fallback = if (t.kind == TxKind.PERSON_IN) "${p.name} paid you" else "You paid ${p.name}"
        PersonHistoryLine(t.epochMillis, t.note.ifBlank { fallback }, if (t.kind == TxKind.PERSON_OUT) t.amount else -t.amount, t.id, payment = true)
    }
    return (splits + payments).sortedByDescending { it.millis }
}

/** When the current balance started: the last time it moved away from zero. Null when settled. */
private fun openSince(history: List<PersonHistoryLine>): Long? {
    var running = 0L
    var start: Long? = null
    history.sortedBy { it.millis }.forEach { line ->
        if (running == 0L && line.amount != 0L) start = line.millis
        running += line.amount
        if (running == 0L) start = null
    }
    return start
}

/**
 * One person, ledger style: what's owed and since when, then two things you can do. Record a payment covers money
 * either way (they paid you, you paid or lent them). Split a transaction takes an expense you already added and
 * opens it on the entry page with this person in its split.
 */
@Composable
fun PersonPage(
    p: Person,
    balance: Long,
    ledger: Ledger,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)?,
    onSave: (EntryDraft, () -> Unit) -> Unit,
    onSplit: (Long) -> Unit,
    onOpenEntry: (Long) -> Unit,
    /** Saves the person and, when it changed, their starting balance; then calls the callback. */
    onUpdate: (Person, Long?, () -> Unit) -> Unit,
) {
    var editing by rememberSaveable(p.id) { mutableStateOf(false) }
    var recording by rememberSaveable(p.id) { mutableStateOf(false) }
    var splitting by rememberSaveable(p.id) { mutableStateOf(false) }
    val history = remember(p, ledger.people) { historyOf(p, ledger) }
    val since = remember(history) { openSince(history) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(p.name + if (p.archived) " (archived)" else "", onBack) {
            TextButton(onClick = { editing = true }) { Text("Edit", color = Kassa.colors.ink, style = KassaType.bodyStrong) }
        }
        RaisedPanel {
            Text(
                when { balance > 0 -> "${p.name} owes you"; balance < 0 -> "You owe ${p.name}"; else -> "Settled" },
                style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            BigMoney(Math.abs(balance), sizeSp = 36f, color = favourColor(balance))
            if (balance != 0L) since?.let {
                Text("Since ${shortDate(Instant.ofEpochMilli(it).atZone(ledger.zone).toLocalDate())}", style = KassaType.bodyStrong, color = Kassa.colors.ink2)
            }
            Gap(14)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(Modifier.weight(1f)) { PrimaryButton("Record a payment") { recording = true } }
                SecondaryButton("Split a transaction", { splitting = true }, Modifier.weight(1f))
            }
        }
        SectionHeader("History")
        if (history.isEmpty()) Text("Nothing yet.", style = KassaType.body, color = Kassa.colors.ink2)
        history.forEach { line ->
            Row(
                Modifier.fillMaxWidth()
                    .then(if (line.transactionId != null) Modifier.clickable { onOpenEntry(line.transactionId) } else Modifier)
                    .padding(vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(line.note, style = KassaType.body, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(shortDateYear(Instant.ofEpochMilli(line.millis).atZone(ledger.zone).toLocalDate()), style = KassaType.small, color = Kassa.colors.ink2)
                }
                // A payment either way only settles up, so it's plain with no sign. A split or starting balance shows
                // which way it moved: + green when they owe you more, − orange when you owe them more.
                Text(if (line.payment) Money.format(Math.abs(line.amount)) else Money.format(line.amount, showPlus = true), style = KassaType.bodyStrong, maxLines = 1,
                    color = if (line.payment) Kassa.colors.ink else favourColor(line.amount))
            }
            HorizontalDivider(color = Kassa.colors.line)
        }
        Gap(24)
    }

    if (editing) {
        val starting = ledger.people.loans.filter { it.personId == p.id && it.transactionId == null }.sumOf { it.amount }
        EditPersonSheet(p, balance, starting, ledger.people.people, onDismiss = { editing = false }) { updated, newStarting ->
            onUpdate(updated, newStarting.takeIf { it != starting }) { editing = false }
        }
    }
    if (recording) PaymentSheet(p, balance, ledger, onDismiss = { recording = false }, onSave = { draft -> onSave(draft) { recording = false } })
    if (splitting) SplitSheet(p, ledger, onDismiss = { splitting = false }) { id -> splitting = false; onSplit(id) }
}

/** A payment's direction kept across recreation by its name. */
private val TxKindSaver: Saver<TxKind, String> = Saver(save = { it.name }, restore = { TxKind.valueOf(it) })

/**
 * Money between you and one person, either way. A new payment starts on settling the balance; [editing] opens an
 * existing one filled in, with Delete ([onDelete]) under Save.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
internal fun PaymentSheet(
    p: Person,
    balance: Long,
    ledger: Ledger,
    onDismiss: () -> Unit,
    onSave: (EntryDraft) -> Unit,
    editing: Transaction? = null,
    onDelete: (() -> Unit)? = null,
) {
    // An edited payment keeps its account in the list even if that account was archived since.
    val banks = ledger.accounts.accounts.filter { (it.isActive && it.type in PAYMENT_ACCOUNT_TYPES) || it.id == editing?.accountId }
    // What is typed here survives folding or rotating the phone.
    var direction by rememberSaveable(p.id, stateSaver = TxKindSaver) { mutableStateOf(editing?.kind ?: if (balance < 0) TxKind.PERSON_OUT else TxKind.PERSON_IN) }
    var amount by rememberSaveable(p.id) {
        mutableStateOf(editing?.let { Money.toInput(it.amount) } ?: if (balance != 0L) Money.toInput(Math.abs(balance)) else "")
    }
    // Nothing guesses an account: you pick where the money went.
    var account by rememberSaveable { mutableStateOf(editing?.accountId) }
    val originalDate = editing?.let { Instant.ofEpochMilli(it.epochMillis).atZone(ledger.zone).toLocalDate() }
    var date by rememberSavedDate { originalDate ?: ledger.today }
    var amountProblem by rememberSaveable { mutableStateOf<String?>(null) }
    var accountProblem by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Kassa.colors.raised) {
        app.kassa.money.ui.common.AccountPickerHost(banks, Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text(if (editing != null) "Edit payment" else "Record a payment", style = KassaType.section, color = Kassa.colors.ink)
            FlowRow(Modifier.padding(top = 10.dp)) {
                Chip("${p.name} paid me", selected = direction == TxKind.PERSON_IN) { direction = TxKind.PERSON_IN }
                Chip("I paid or lent ${p.name}", selected = direction == TxKind.PERSON_OUT) { direction = TxKind.PERSON_OUT }
            }
            AmountField(amount, { amount = it; amountProblem = null }, "Amount", Modifier.fillMaxWidth(), problem = amountProblem)
            app.kassa.money.ui.common.AccountChoiceRow(
                if (direction == TxKind.PERSON_IN) "Into" else "From", account, banks, { id -> account = id; accountProblem = null },
                modifier = Modifier.padding(top = 8.dp), recentIds = app.kassa.money.ui.common.recentAccountIds(ledger),
            )
            accountProblem?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
            Text("Date", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            DateRow(date, ledger.today) { date = it }
            Gap(12)
            PrimaryButton(if (editing != null) "Save changes" else "Save payment") {
                val cents = Money.parseCents(amount)
                amountProblem = InputRules.amountProblem(cents)
                accountProblem = if (account == null) "Pick an account." else null
                if (amountProblem != null || accountProblem != null) return@PrimaryButton
                val defaultNote = if (direction == TxKind.PERSON_IN) "${p.name} paid you" else "You paid ${p.name}"
                onSave(EntryDraft(
                    id = editing?.id, kind = direction, amount = cents!!, accountId = account, toAccountId = null, categoryId = null,
                    personId = p.id,
                    // An unchanged date keeps the original time, so the payment doesn't move within its day.
                    epochMillis = if (editing != null && date == originalDate) editing.epochMillis else momentOn(date, ledger.today, ledger.zone),
                    // A note the user wrote stays; the generated one follows the direction.
                    note = editing?.note?.takeIf { direction == editing.kind && it.isNotBlank() } ?: defaultNote,
                ))
            }
            if (onDelete != null) TextButton(onClick = { confirmDelete = true }, modifier = Modifier.fillMaxWidth()) {
                Text("Delete payment", color = Kassa.colors.attention, style = KassaType.bodyStrong)
            }
        }
    }
    if (confirmDelete && onDelete != null) ConfirmDialog(
        title = "Delete this payment?",
        body = "${Money.format(editing?.amount ?: 0)} comes off what's between you and ${p.name}, and off the account. This can't be undone.",
        confirm = "Delete", onConfirm = onDelete, onDismiss = { confirmDelete = false },
    )
}

private val PAYMENT_ACCOUNT_TYPES = setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID, AccountType.CREDIT_CARD)

/** Pick an expense from the last 60 days to split with this person; it opens on the entry page with the split panel up. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SplitSheet(p: Person, ledger: Ledger, onDismiss: () -> Unit, onPick: (Long) -> Unit) {
    val alreadySplit = ledger.people.loans.filter { it.personId == p.id }.mapNotNull { it.transactionId }.toSet()
    val from = ledger.today.minusDays(60)
    val choices = ledger.recent
        .filter { it.kind == TxKind.EXPENSE && it.accountId != null && it.id !in alreadySplit && !ledger.loans.isLoanEntry(it.id) }
        .filter { !Instant.ofEpochMilli(it.epochMillis).atZone(ledger.zone).toLocalDate().isBefore(from) }
        .sortedByDescending { it.epochMillis }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Split with ${p.name}", style = KassaType.section, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Pick an expense", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            if (choices.isEmpty()) Text("No expenses in the last 60 days.", style = KassaType.body, color = Kassa.colors.ink2)
            LazyColumn(Modifier.fillMaxWidth().height(420.dp)) {
                items(choices, key = { it.id }) { t -> TransactionRow(t, ledger, showDate = true, onClick = { onPick(it.id) }) }
            }
        }
    }
}
