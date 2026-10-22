package dev.krishnagurram.kassa.ui.cards

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.data.EntryDraft
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.ui.CardSnapshotFor
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.activity.Chevron
import dev.krishnagurram.kassa.ui.common.EntryRow
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.common.Stat
import dev.krishnagurram.kassa.ui.common.TransactionRow
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.importer.SecondaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import dev.krishnagurram.kassa.ui.today.DateBlock
import dev.krishnagurram.kassa.ui.today.inDays
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * Every card by due date. On the cover screen a card opens full screen; unfolded, the list stays on the left
 * and the selected card's statement sits on the right.
 */
@Composable
fun CardsScreen(
    ledger: Ledger,
    contentPadding: PaddingValues,
    selectedCard: Long?,
    onSelect: (Long?) -> Unit,
    onEdit: (Transaction) -> Unit,
    onPay: (EntryDraft) -> Unit,
) {
    val cards = ledger.cards()
    val open = cards.filter { it.snapshot.outstanding > 0 }.sortedBy { it.snapshot.due }
    val done = cards.filter { it.snapshot.outstanding == 0L }.sortedBy { it.snapshot.nextClose }
    var paying by remember { mutableStateOf<Long?>(null) }

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val current = selectedCard?.let { id -> cards.firstOrNull { it.account.id == id } }

        if (!wide && current != null) {
            BackHandler { onSelect(null) }
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)) {
                IconButton(onClick = { onSelect(null) }) { Icon(Chevron(left = true), "Back to cards", tint = Kassa.colors.ink) }
                CardDetail(current, ledger, onEdit, onPayStatement = { onPay(statementPayment(current, ledger)) }, onPayOther = { paying = current.account.id })
            }
        } else {
            val list: @Composable () -> Unit = {
                Text("Outstanding is what your last statement billed", style = KassaType.small, color = Kassa.colors.ink2)
                Text("Cards", style = KassaType.title, color = Kassa.colors.ink)
                Gap(16)
                val d7 = open.filter { it.snapshot.daysUntilDue(ledger.today) <= 7 }
                val d30 = open.filter { it.snapshot.daysUntilDue(ledger.today) <= 30 }
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Stat("Due in 7 days", Money.format(d7.sumOf { it.snapshot.outstanding }, wholeDollars = true), modifier = Modifier.weight(1f))
                    Stat("Due in 30 days", Money.format(d30.sumOf { it.snapshot.outstanding }, wholeDollars = true), modifier = Modifier.weight(1f))
                }
                if (cards.isEmpty()) {
                    Gap(24)
                    Text("No cards yet. Add a credit card in Accounts with its statement and due day.", style = KassaType.body, color = Kassa.colors.ink2)
                }
                if (open.isNotEmpty()) SectionHeader("To pay", "By due date")
                open.forEachIndexed { i, c ->
                    val days = c.snapshot.daysUntilDue(ledger.today)
                    val sel = wide && current?.account?.id == c.account.id
                    EntryRow(
                        badge = { DateBlock(c.snapshot.due, soon = days <= 3) },
                        title = c.account.name,
                        subtitle = "Due ${inDays(days)}" + if (c.snapshot.paidSinceClose > 0) ", ${Money.format(c.snapshot.paidSinceClose, wholeDollars = true)} paid" else "",
                        amount = Money.format(c.snapshot.outstanding),
                        note = if (c.snapshot.notYetBilled > 0) "${Money.format(c.snapshot.notYetBilled)} not billed" else "Nothing new",
                        showDivider = i < open.lastIndex,
                        modifier = Modifier.highlight(sel, Kassa.colors.chip).clickable { onSelect(c.account.id) },
                    )
                }
                if (done.isNotEmpty()) SectionHeader("Paid for this cycle", "Next statement")
                done.forEachIndexed { i, c ->
                    val sel = wide && current?.account?.id == c.account.id
                    EntryRow(
                        badge = { DateBlock(c.snapshot.nextClose, soon = false) },
                        title = c.account.name,
                        subtitle = "Statement closes ${inDays(java.time.temporal.ChronoUnit.DAYS.between(ledger.today, c.snapshot.nextClose))}",
                        amount = Money.format(c.snapshot.notYetBilled), amountColor = Kassa.colors.ink2,
                        note = "not billed yet",
                        showDivider = i < done.lastIndex,
                        modifier = Modifier.highlight(sel, Kassa.colors.chip).clickable { onSelect(c.account.id) },
                    )
                }
            }
            if (wide) {
                val shown = current ?: open.firstOrNull() ?: done.firstOrNull()
                Row(Modifier.fillMaxSize().padding(contentPadding), horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { list() }
                    Column(
                        Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(Kassa.colors.raised)
                            .border(1.dp, Kassa.colors.line, RoundedCornerShape(24.dp))
                            .verticalScroll(rememberScrollState()).padding(22.dp),
                    ) {
                        if (shown != null) CardDetail(shown, ledger, onEdit, onPayStatement = { onPay(statementPayment(shown, ledger)) }, onPayOther = { paying = shown.account.id })
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)) { list() }
            }
        }
    }

    paying?.let { id ->
        cards.firstOrNull { it.account.id == id }?.let { c ->
            PaySheet(c, ledger, onDismiss = { paying = null }, onPay = { draft -> paying = null; onPay(draft) })
        }
    }
}

private fun Modifier.highlight(on: Boolean, color: androidx.compose.ui.graphics.Color): Modifier =
    if (on) this.clip(RoundedCornerShape(14.dp)).background(color) else this

@Composable
private fun CardDetail(c: CardSnapshotFor, ledger: Ledger, onEdit: (Transaction) -> Unit, onPayStatement: () -> Unit, onPayOther: () -> Unit) {
    val s = c.snapshot
    val fmt = DateTimeFormatter.ofPattern("EEE, MMM d")
    val id = c.account.id
    val payFrom = c.account.payFromId?.let { ledger.accountById[it]?.name }
    Text(c.account.name, style = KassaType.section.copy(fontSize = KassaType.title.fontSize * 0.75f), color = Kassa.colors.ink)
    Text(
        "Statement closed ${s.lastClose.format(DateTimeFormatter.ofPattern("MMM d"))}. " +
            if (s.outstanding > 0) "Due ${s.due.format(fmt)}, ${inDays(s.daysUntilDue(ledger.today))}." else "Next statement closes ${s.nextClose.format(DateTimeFormatter.ofPattern("MMM d"))}.",
        style = KassaType.body, color = Kassa.colors.ink2,
    )
    Row(Modifier.fillMaxWidth().padding(vertical = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Stat("Outstanding", Money.format(s.outstanding), modifier = Modifier.weight(1f))
        Stat("Not yet billed", Money.format(s.notYetBilled), modifier = Modifier.weight(1f))
        Stat("Paid since", Money.format(s.paidSinceClose), modifier = Modifier.weight(1f))
    }
    if (s.outstanding > 0) {
        PrimaryButton("Pay statement balance, ${Money.format(s.outstanding)}", onPayStatement)
        Text("From ${payFrom ?: "your checking account"}, dated today", style = KassaType.small, color = Kassa.colors.ink2, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        Gap(10)
    }
    SecondaryButton(if (s.outstanding > 0) "Choose amount, account or date" else "Make a payment", onPayOther)

    val since = ledger.recent.filter { (it.accountId == id || it.toAccountId == id) && dateOf(it, ledger).isAfter(s.lastClose) }.sortedByDescending { it.epochMillis }
    val stmt = ledger.recent.filter { (it.accountId == id || it.toAccountId == id) && dateOf(it, ledger).let { d -> d.isAfter(s.previousClose) && !d.isAfter(s.lastClose) } }.sortedByDescending { it.epochMillis }
    SectionHeader("Since the statement")
    if (since.isEmpty()) Text("Nothing yet.", style = KassaType.body, color = Kassa.colors.ink2)
    since.forEachIndexed { i, t -> TransactionRow(t, ledger, showDivider = i < since.lastIndex, onClick = onEdit) }
    SectionHeader("On the ${s.lastClose.format(DateTimeFormatter.ofPattern("MMM d"))} statement")
    if (stmt.isEmpty()) Text("No charges.", style = KassaType.body, color = Kassa.colors.ink2)
    stmt.forEachIndexed { i, t -> TransactionRow(t, ledger, showDivider = i < stmt.lastIndex, onClick = onEdit) }
}

private fun dateOf(t: Transaction, ledger: Ledger) = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate()

private fun defaultPayFrom(c: CardSnapshotFor, ledger: Ledger): Long? =
    c.account.payFromId ?: ledger.accounts.accounts.firstOrNull { it.type == AccountType.CHECKING && !it.archived }?.id

private fun statementPayment(c: CardSnapshotFor, ledger: Ledger) = EntryDraft(
    id = null, kind = TxKind.TRANSFER, amount = c.snapshot.outstanding,
    accountId = defaultPayFrom(c, ledger), toAccountId = c.account.id, categoryId = null, personId = null,
    epochMillis = System.currentTimeMillis(), note = "Card payment", splitWith = emptyList(),
)

/** Pay a card: statement balance, everything owed, or a custom amount, from any account, on any date. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun PaySheet(c: CardSnapshotFor, ledger: Ledger, onDismiss: () -> Unit, onPay: (EntryDraft) -> Unit) {
    val s = c.snapshot
    val full = s.outstanding + s.notYetBilled
    var option by rememberSaveable { mutableStateOf(if (s.outstanding > 0) "statement" else if (full > 0) "full" else "other") }
    var other by rememberSaveable { mutableStateOf("") }
    var from by remember { mutableStateOf(defaultPayFrom(c, ledger)) }
    var date by remember { mutableStateOf(ledger.today) }
    var pickDate by remember { mutableStateOf(false) }
    val amount = when (option) { "statement" -> s.outstanding; "full" -> full; else -> Money.parseCents(other) ?: 0 }
    val sources = ledger.accounts.accounts.filter { !it.archived && it.type in setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp).widthIn(max = 640.dp)) {
            Text("Pay ${c.account.name}", style = KassaType.section, color = Kassa.colors.ink)
            Text("Amount", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            if (s.outstanding > 0) Option("Statement balance, ${Money.format(s.outstanding)}", "Clears what's due ${s.due.format(DateTimeFormatter.ofPattern("EEE, MMM d"))}", option == "statement") { option = "statement" }
            if (full > 0) Option("Current balance, ${Money.format(full)}", if (s.notYetBilled > 0) "Also pays ${Money.format(s.notYetBilled)} not billed yet" else "Everything you owe on this card", option == "full") { option = "full" }
            Option("Another amount", "A partial or extra payment", option == "other") { option = "other" }
            if (option == "other") OutlinedTextField(
                other, { other = it }, singleLine = true, label = { Text("Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = KassaType.body, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text("From", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            FlowRow { sources.forEach { a -> Chip(a.name, selected = from == a.id) { from = a.id } } }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { pickDate = true }.padding(vertical = 12.dp)) {
                Text("Date", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.weight(1f))
                Text(if (date == ledger.today) "Today" else date.format(DateTimeFormatter.ofPattern("EEE, MMM d")), style = KassaType.bodyStrong, color = Kassa.colors.ink)
            }
            Gap(12)
            PrimaryButton(if (amount > 0) "Pay ${Money.format(amount)}" else "Enter an amount") {
                if (amount > 0 && from != null) onPay(
                    EntryDraft(
                        id = null, kind = TxKind.TRANSFER, amount = amount, accountId = from, toAccountId = c.account.id,
                        categoryId = null, personId = null,
                        epochMillis = date.atTime(if (date == ledger.today) LocalTime.now(ledger.zone) else LocalTime.NOON).atZone(ledger.zone).toInstant().toEpochMilli(),
                        note = "Card payment", splitWith = emptyList(),
                    )
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = Kassa.colors.ink2) }
        }
    }
    if (pickDate) {
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = { TextButton(onClick = { state.selectedDateMillis?.let { date = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate() }; pickDate = false }) { Text("Done", color = Kassa.colors.ink) } },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel", color = Kassa.colors.ink2) } },
        ) { DatePicker(state = state) }
    }
}

@Composable
private fun Option(title: String, sub: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(shape)
            .border(if (selected) 2.dp else 1.dp, if (selected) Kassa.colors.ink else Kassa.colors.line, shape)
            .clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(title, style = KassaType.bodyStrong, color = Kassa.colors.ink)
        Text(sub, style = KassaType.small, color = Kassa.colors.ink2)
    }
}
