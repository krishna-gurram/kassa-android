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
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.outlined.ExpandMore
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
import androidx.compose.material.icons.outlined.CreditCard
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

/** How the cards list is ordered. */
enum class CardSort(val label: String) {
    DUE("Due date"), NAME("Name"), PAYABLE("Balance payable"), TOTAL("Total balance"), OUTSTANDING("Outstanding");
}

fun sortCards(ledger: Ledger, by: CardSort): List<CardSnapshotFor> = when (by) {
    CardSort.DUE -> cardsByDueDate(ledger)
    CardSort.NAME -> ledger.cards().sortedBy { it.account.name.lowercase() }
    CardSort.PAYABLE -> ledger.cards().sortedByDescending { it.snapshot.payable }
    CardSort.TOTAL -> ledger.cards().sortedByDescending { it.snapshot.totalBalance }
    CardSort.OUTSTANDING -> ledger.cards().sortedByDescending { it.snapshot.outstanding }
}

/** Every card under one total split into balance payable and outstanding, sorted the way you pick. */
@Composable
fun CardsScreen(ledger: Ledger, contentPadding: PaddingValues, selectedCard: Long?, onOpen: (Long) -> Unit) {
    var sort by rememberSaveable { mutableStateOf(CardSort.DUE) }
    var menu by remember { mutableStateOf(false) }
    val cards = sortCards(ledger, sort)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        Text("Cards", style = KassaType.title, color = Kassa.colors.ink)
        Gap(14)
        if (cards.isEmpty()) {
            Text("No cards yet. Add a credit card in Accounts with its statement day and due day.", style = KassaType.body, color = Kassa.colors.ink2)
            return@Column
        }
        CardTotals(cards)
        Row(Modifier.fillMaxWidth().padding(top = 24.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${cards.size} cards", style = KassaType.section, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
            androidx.compose.foundation.layout.Box {
                Row(
                    Modifier.clip(RoundedCornerShape(50)).background(Kassa.colors.chip).clickable { menu = true }.padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Sort: ${sort.label}", style = KassaType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Kassa.colors.ink)
                    androidx.compose.material3.Icon(androidx.compose.material.icons.Icons.Outlined.ExpandMore, null, tint = Kassa.colors.ink2, modifier = Modifier.padding(start = 2.dp).size(18.dp))
                }
                androidx.compose.material3.DropdownMenu(menu, { menu = false }, containerColor = Kassa.colors.raised) {
                    CardSort.entries.forEach { o ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text(o.label, style = if (o == sort) KassaType.bodyStrong else KassaType.body, color = Kassa.colors.ink) },
                            onClick = { sort = o; menu = false },
                        )
                    }
                }
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            cards.forEach { c -> CardRow(c, ledger.today, selected = c.account.id == selectedCard) { onOpen(c.account.id) } }
        }
    }
}

/** Total card balance, split into balance payable and outstanding. Used on Cards and in Accounts. */
@Composable
fun CardTotals(cards: List<CardSnapshotFor>) {
    val payable = cards.sumOf { it.snapshot.payable }
    val outstanding = cards.sumOf { it.snapshot.outstanding }
    RaisedPanel {
        Text("Total card balance", style = KassaType.tiny, color = Kassa.colors.ink2)
        dev.krishnagurram.kassa.ui.today.BigMoney(payable + outstanding, sizeSp = 40f)
        HorizontalDivider(color = Kassa.colors.line, modifier = Modifier.padding(vertical = 12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text("Balance payable", style = KassaType.tiny, color = Kassa.colors.ink2)
                Text(Money.format(payable), style = KassaType.stat, color = Kassa.colors.ink, maxLines = 1)
            }
            Column(Modifier.weight(1f)) {
                Text("Outstanding", style = KassaType.tiny, color = Kassa.colors.ink2)
                Text(Money.format(outstanding), style = KassaType.stat, color = Kassa.colors.ink, maxLines = 1)
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

/** A calendar-style date: month over day, orange when due within three days, green once paid in full. */
@Composable
fun DueTile(date: LocalDate, tone: Tone, large: Boolean = false, caption: String? = null) {
    val fg = when (tone) { Tone.ATTENTION -> Kassa.colors.attention; Tone.POSITIVE -> Kassa.colors.positive; Tone.NEUTRAL -> Kassa.colors.ink }
    val bg = when (tone) { Tone.NEUTRAL -> Kassa.colors.ground; else -> fg.copy(alpha = 0.14f) }
    Column(
        Modifier.width(if (large) 66.dp else 48.dp).clip(RoundedCornerShape(if (large) 16.dp else 13.dp)).background(bg).padding(vertical = if (large) 8.dp else 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(date.format(DateTimeFormatter.ofPattern("MMM")).uppercase(), style = KassaType.tiny.copy(fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 0.6.sp), color = if (tone == Tone.NEUTRAL) Kassa.colors.ink2 else fg)
        Text(date.dayOfMonth.toString(), style = KassaType.stat.copy(fontSize = if (large) 26.sp else 19.sp, lineHeight = if (large) 30.sp else 22.sp), color = fg)
        if (caption != null) Text(caption.uppercase(), style = KassaType.tiny.copy(fontSize = 10.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, letterSpacing = 0.6.sp), color = if (tone == Tone.NEUTRAL) Kassa.colors.ink2 else fg)
    }
}

/** Tone for a card's due date: attention when due soon or late, positive when paid in full. */
fun dueTone(c: CardSnapshotFor, today: LocalDate): Tone {
    val s = c.snapshot
    val days = s.daysUntilDue(today)
    return when {
        s.statementTotal > 0 && s.payable == 0L -> Tone.POSITIVE
        s.payable > 0 && days <= 3 -> Tone.ATTENTION
        else -> Tone.NEUTRAL
    }
}

/**
 * One card as a raised row, led by its due date. First line: name with its last 4 digits, and the total
 * balance. Second line: what's payable (bold) and what's outstanding.
 */
@Composable
fun CardRow(c: CardSnapshotFor, today: LocalDate, selected: Boolean = false, @Suppress("UNUSED_PARAMETER") showDivider: Boolean = true, onClick: () -> Unit) {
    val s = c.snapshot
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(Kassa.colors.raised)
            .border(1.5.dp, if (selected) Kassa.colors.ink else androidx.compose.ui.graphics.Color.Transparent, shape)
            .clickable(onClick = onClick).padding(start = 12.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DueTile(s.due, dueTone(c, today))
        Column(Modifier.weight(1f).padding(start = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                    Text(c.account.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    c.account.last4?.let { Text("  ··$it", style = KassaType.small, color = Kassa.colors.ink3, maxLines = 1) }
                }
                Text(Money.format(s.totalBalance), style = KassaType.bodyStrong.copy(fontSize = 17.sp), color = Kassa.colors.ink, maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append("Payable ")
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = Kassa.colors.ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                        append(Money.format(s.payable)); pop()
                    },
                    style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1,
                )
                Text("Outstanding ${Money.format(s.outstanding)}", style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1)
            }
        }
    }
}

/**
 * One card. First what you owe and when, then the total and what's outstanding, side by side in equal boxes,
 * then the entries behind each. Edit opens the card's details: name, last 4 digits, days, limit, balance.
 */
@Composable
fun CardPage(
    c: CardSnapshotFor,
    ledger: Ledger,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)?,
    onEdit: (Transaction) -> Unit,
    onPay: () -> Unit,
    onEditCard: () -> Unit,
) {
    val s = c.snapshot
    val a = c.account
    val id = a.id
    val since = ledger.recent.filter { (it.accountId == id || it.toAccountId == id) && dateOf(it, ledger).isAfter(s.lastClose) }.sortedByDescending { it.epochMillis }
    val stmt = ledger.recent.filter { (it.accountId == id || it.toAccountId == id) && dateOf(it, ledger).let { d -> d.isAfter(s.previousClose) && !d.isAfter(s.lastClose) } }.sortedByDescending { it.epochMillis }
    val dateNote: (Transaction) -> String = { t -> dateOf(t, ledger).format(MONTH_DAY) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(a.name + (a.last4?.let { "  ··$it" } ?: ""), onBack) {
            androidx.compose.material3.TextButton(onClick = onEditCard) { Text("Edit", style = KassaType.bodyStrong, color = Kassa.colors.ink) }
        }
        RaisedPanel {
            val dueTone = dueTone(c, ledger.today)
            val days = s.daysUntilDue(ledger.today)
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("Balance payable", style = KassaType.tiny, color = Kassa.colors.ink2)
                    dev.krishnagurram.kassa.ui.today.BigMoney(s.payable, sizeSp = 36f)
                    Text(
                        when {
                            dueTone == Tone.POSITIVE -> "Paid in full"
                            s.payable == 0L -> "Nothing due"
                            days < 0 -> "${-days} days late"
                            days == 0L -> "Due today"
                            days == 1L -> "Due tomorrow"
                            else -> "Due in $days days"
                        },
                        style = KassaType.bodyStrong,
                        color = when (dueTone) { Tone.ATTENTION -> Kassa.colors.attention; Tone.POSITIVE -> Kassa.colors.positive; Tone.NEUTRAL -> Kassa.colors.ink2 },
                    )
                }
                DueTile(s.due, dueTone, large = true, caption = "Due")
            }
            Gap(12)
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box2(Modifier.weight(1f).fillMaxHeight()) {
                    Text("Total balance", style = KassaType.tiny, color = Kassa.colors.ink2)
                    Text(Money.format(s.totalBalance), style = KassaType.stat, color = Kassa.colors.ink, maxLines = 1)
                }
                Box2(Modifier.weight(1f).fillMaxHeight()) {
                    Text("Outstanding", style = KassaType.tiny, color = Kassa.colors.ink2)
                    Text(Money.format(s.outstanding), style = KassaType.stat, color = Kassa.colors.ink, maxLines = 1)
                }
            }
            Gap(14)
            PrimaryButton(if (s.payable > 0) "Pay ${Money.format(s.payable)}" else "Record a payment", onPay)
        }
        SectionHeader("Outstanding", "Next statement ${s.nextClose.format(MONTH_DAY)}")
        if (since.isEmpty()) Text("No charges since the statement date.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 8.dp))
        since.forEachIndexed { i, t -> TransactionRow(t, ledger, showDivider = i < since.lastIndex, noteOverride = dateNote(t), onClick = onEdit) }
        SectionHeader("${s.lastClose.format(MONTH_DAY)} statement", Money.format(s.statementTotal))
        if (stmt.isEmpty()) Text("No charges on this statement.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 8.dp))
        stmt.forEachIndexed { i, t -> TransactionRow(t, ledger, showDivider = i < stmt.lastIndex, noteOverride = dateNote(t), onClick = onEdit) }
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
