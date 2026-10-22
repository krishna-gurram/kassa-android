package dev.krishnagurram.kassa.ui.cards

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.data.EntryDraft
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.CardCycle
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.ui.CardSnapshotFor
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.GlyphBadge
import dev.krishnagurram.kassa.ui.common.PageTop
import dev.krishnagurram.kassa.ui.common.RaisedPanel
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.common.StatusChip
import dev.krishnagurram.kassa.ui.common.Tone
import dev.krishnagurram.kassa.ui.common.TransactionRow
import dev.krishnagurram.kassa.ui.common.plural
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/*
 * Wording follows Money Manager and card issuers:
 * Balance payable = unpaid part of the last statement (the due date applies to it).
 * Outstanding = charges since the statement date (they go on the next statement).
 * Total balance = the two together.
 */

private val MONTH_DAY = DateTimeFormatter.ofPattern("MMM d")
private val DAY_MONTH_DAY = DateTimeFormatter.ofPattern("EEE, MMM d")

/** Cards with something payable first, by due date; the rest by their next closing date. */
fun cardsByDueDate(ledger: Ledger): List<CardSnapshotFor> {
    val cards = ledger.cards()
    return cards.filter { it.snapshot.payable > 0 }.sortedBy { it.snapshot.due } +
        cards.filter { it.snapshot.payable == 0L }.sortedBy { it.snapshot.nextClose }
}

/** Every card by due date, under one total split into balance payable and outstanding. */
@Composable
fun CardsScreen(ledger: Ledger, contentPadding: PaddingValues, selectedCard: Long?, onOpen: (Long) -> Unit) {
    val cards = cardsByDueDate(ledger)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        Text(plural(cards.size, "card"), style = KassaType.small, color = Kassa.colors.ink2)
        Text("Cards", style = KassaType.title, color = Kassa.colors.ink)
        Gap(16)
        if (cards.isEmpty()) {
            Text("No cards yet. Add a credit card in Accounts with its statement day and due day.", style = KassaType.body, color = Kassa.colors.ink2)
            return@Column
        }
        CardTotals(cards)
        SectionHeader("By due date", "Balance payable")
        cards.forEachIndexed { i, c ->
            CardRow(c, ledger.today, selected = c.account.id == selectedCard, showDivider = i < cards.lastIndex) { onOpen(c.account.id) }
        }
    }
}

@Composable
private fun CardTotals(cards: List<CardSnapshotFor>) {
    val payable = cards.sumOf { it.snapshot.payable }
    val outstanding = cards.sumOf { it.snapshot.outstanding }
    val statements = cards.count { it.snapshot.payable > 0 }
    RaisedPanel {
        Text("Total card balance", style = KassaType.tiny, color = Kassa.colors.ink2)
        Text(Money.format(payable + outstanding), style = KassaType.hero.copy(fontSize = KassaType.hero.fontSize * 0.75f), color = Kassa.colors.ink)
        HorizontalDivider(color = Kassa.colors.line, modifier = Modifier.padding(vertical = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Balance payable", style = KassaType.tiny, color = Kassa.colors.ink2)
                Text(Money.format(payable), style = KassaType.stat, color = Kassa.colors.ink, maxLines = 1)
                Text(if (statements == 0) "Every statement is paid" else "Due on ${plural(statements, "statement")}", style = KassaType.tiny, color = Kassa.colors.ink2)
            }
            Column(Modifier.weight(1f)) {
                Text("Outstanding", style = KassaType.tiny, color = Kassa.colors.ink2)
                Text(Money.format(outstanding), style = KassaType.stat, color = Kassa.colors.ink2, maxLines = 1)
                Text("Goes on next statements", style = KassaType.tiny, color = Kassa.colors.ink2)
            }
        }
    }
}

/** Due state in a few words: paid in full, past due, due soon or the due date. */
fun dueStatus(c: CardSnapshotFor, today: LocalDate): Pair<String, Tone> {
    val s = c.snapshot
    val days = s.daysUntilDue(today)
    return when {
        s.statementTotal <= 0 && s.payable == 0L -> "No statement balance" to Tone.NEUTRAL
        s.payable == 0L -> "Paid in full" to Tone.POSITIVE
        days < 0 -> "Past due ${-days}d" to Tone.ATTENTION
        days == 0L -> "Due today" to Tone.ATTENTION
        days == 1L -> "Due tomorrow" to Tone.ATTENTION
        days <= 3 -> "Due in $days days" to Tone.ATTENTION
        else -> "Due ${s.due.format(MONTH_DAY)}" to Tone.NEUTRAL
    }
}

/** One card: name and what's outstanding on the left, balance payable and its due state on the right. */
@Composable
fun CardRow(c: CardSnapshotFor, today: LocalDate, selected: Boolean = false, showDivider: Boolean = true, onClick: () -> Unit) {
    val s = c.snapshot
    val (status, tone) = dueStatus(c, today)
    Column(Modifier.clip(RoundedCornerShape(14.dp)).background(if (selected) Kassa.colors.chip else androidx.compose.ui.graphics.Color.Transparent).clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth().padding(vertical = 11.dp, horizontal = if (selected) 8.dp else 0.dp), verticalAlignment = Alignment.CenterVertically) {
            GlyphBadge(c.account.name.take(1).uppercase())
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(c.account.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1)
                Text(if (s.outstanding > 0) "+ ${Money.format(s.outstanding)} outstanding" else "No outstanding charges", style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(Money.format(s.payable), style = KassaType.bodyStrong, color = if (s.payable > 0) Kassa.colors.ink else Kassa.colors.ink3, maxLines = 1)
                Gap(3)
                StatusChip(status, tone)
            }
        }
        if (showDivider && !selected) HorizontalDivider(color = Kassa.colors.line)
    }
}

/** One card: what's payable and by when, what's outstanding for the next statement, and the entries behind both. */
@Composable
fun CardPage(
    c: CardSnapshotFor,
    ledger: Ledger,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)?,
    onEdit: (Transaction) -> Unit,
    onPay: () -> Unit,
) {
    val s = c.snapshot
    val a = c.account
    val id = a.id
    val nextDue = a.statementDay?.let { CardCycle.dueFor(it, a.dueDay ?: it, s.nextClose) }
    val (status, tone) = dueStatus(c, ledger.today)
    val since = ledger.recent.filter { (it.accountId == id || it.toAccountId == id) && dateOf(it, ledger).isAfter(s.lastClose) }.sortedByDescending { it.epochMillis }
    val stmt = ledger.recent.filter { (it.accountId == id || it.toAccountId == id) && dateOf(it, ledger).let { d -> d.isAfter(s.previousClose) && !d.isAfter(s.lastClose) } }.sortedByDescending { it.epochMillis }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(a.name, onBack)
        Text(
            a.creditLimit?.takeIf { it > 0 }?.let { "Available credit ${Money.format(it - s.totalBalance)} of ${Money.format(it)} limit" }
                ?: "Statement date ${a.statementDay}, payment due on day ${a.dueDay ?: a.statementDay}",
            style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(horizontal = 4.dp),
        )
        Gap(14)
        RaisedPanel {
            Text("Total balance", style = KassaType.tiny, color = Kassa.colors.ink2)
            Text(Money.format(s.totalBalance), style = KassaType.hero.copy(fontSize = KassaType.hero.fontSize * 0.75f), color = Kassa.colors.ink)
            Gap(12)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box2(Modifier.weight(1f)) {
                    Text("Balance payable", style = KassaType.tiny, color = Kassa.colors.ink2)
                    Text(Money.format(s.payable), style = KassaType.stat, color = Kassa.colors.ink, maxLines = 1)
                    Text("Statement balance ${Money.format(s.statementTotal)}", style = KassaType.tiny, color = Kassa.colors.ink2)
                    if (s.paidSinceClose > 0) Text("Payments ${Money.format(s.paidSinceClose)}", style = KassaType.tiny, color = Kassa.colors.ink2)
                    Gap(6)
                    StatusChip(status, tone)
                }
                Box2(Modifier.weight(1f)) {
                    Text("Outstanding", style = KassaType.tiny, color = Kassa.colors.ink2)
                    Text(Money.format(s.outstanding), style = KassaType.stat, color = Kassa.colors.ink, maxLines = 1)
                    Text("Posted since ${s.lastClose.format(MONTH_DAY)}", style = KassaType.tiny, color = Kassa.colors.ink2)
                    Text(
                        "Closing date ${s.nextClose.format(MONTH_DAY)}" + (nextDue?.let { ", due ${it.format(MONTH_DAY)}" } ?: ""),
                        style = KassaType.tiny, color = Kassa.colors.ink2,
                    )
                }
            }
            Gap(14)
            PrimaryButton(if (s.payable > 0) "Pay ${Money.format(s.payable)}" else "Record a payment", onPay)
        }
        SectionHeader("Outstanding", "Posts to the ${s.nextClose.format(MONTH_DAY)} statement")
        if (since.isEmpty()) Text("No charges since the statement date.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 8.dp))
        since.forEachIndexed { i, t -> TransactionRow(t, ledger, showDivider = i < since.lastIndex, onClick = onEdit) }
        SectionHeader("${s.lastClose.format(MONTH_DAY)} statement", "Statement balance ${Money.format(s.statementTotal)}")
        if (stmt.isEmpty()) Text("No charges on this statement.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 8.dp))
        stmt.forEachIndexed { i, t -> TransactionRow(t, ledger, showDivider = i < stmt.lastIndex, onClick = onEdit) }
        Gap(24)
    }
}

/** A quieter box inside the raised panel. */
@Composable
private fun Box2(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Kassa.colors.ground).padding(12.dp)) { content() }
}

private fun dateOf(t: Transaction, ledger: Ledger) = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate()

private fun defaultPayFrom(c: CardSnapshotFor, ledger: Ledger): Long? =
    c.account.payFromId ?: ledger.accounts.accounts.firstOrNull { it.type == AccountType.CHECKING && !it.archived }?.id

/** Record a card payment: balance payable, total balance or another amount, from any account, on any date. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun PaySheet(c: CardSnapshotFor, ledger: Ledger, onDismiss: () -> Unit, onPay: (EntryDraft) -> Unit) {
    val s = c.snapshot
    val total = s.totalBalance
    var option by rememberSaveable { mutableStateOf(if (s.payable > 0) "payable" else if (total > 0) "total" else "other") }
    var other by rememberSaveable { mutableStateOf("") }
    var from by remember { mutableStateOf(defaultPayFrom(c, ledger)) }
    var date by remember { mutableStateOf(ledger.today) }
    var pickDate by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val amount = when (option) { "payable" -> s.payable; "total" -> total; else -> Money.parseCents(other) ?: 0 }
    val sources = ledger.accounts.accounts.filter { !it.archived && it.type in setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp).widthIn(max = 640.dp)) {
            Text("Pay ${c.account.name}", style = KassaType.section, color = Kassa.colors.ink)
            Text("Records the payment in Kassa. Nothing is sent to your bank.", style = KassaType.small, color = Kassa.colors.ink2)
            Text("Amount", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            if (s.payable > 0) Option("Balance payable, ${Money.format(s.payable)}", "Due ${s.due.format(DAY_MONTH_DAY)}", option == "payable") { option = "payable" }
            if (total > 0) Option(
                "Total balance, ${Money.format(total)}",
                if (s.outstanding > 0 && s.payable > 0) "Balance payable plus ${Money.format(s.outstanding)} outstanding" else "Everything owed on this card",
                option == "total",
            ) { option = "total" }
            Option("Other amount", "A partial or extra payment", option == "other") { option = "other" }
            if (option == "other") OutlinedTextField(
                other, { other = it; error = null }, singleLine = true, label = { Text("Amount") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                textStyle = KassaType.body, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
            )
            Text("From", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            FlowRow { sources.forEach { a -> Chip(a.name, selected = from == a.id) { from = a.id; error = null } } }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp).clickable { pickDate = true }.padding(vertical = 12.dp)) {
                Text("Date", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.weight(1f))
                Text(if (date == ledger.today) "Today" else date.format(DAY_MONTH_DAY), style = KassaType.bodyStrong, color = Kassa.colors.ink)
            }
            error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
            Gap(12)
            PrimaryButton(if (amount > 0) "Record ${Money.format(amount)} payment" else "Enter an amount") {
                error = when { amount <= 0 -> "Enter an amount."; from == null -> "Pick the account it's paid from."; else -> null }
                if (error == null) onPay(
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
