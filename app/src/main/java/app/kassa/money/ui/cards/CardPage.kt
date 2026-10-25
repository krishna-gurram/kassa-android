package app.kassa.money.ui.cards

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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.unit.dp
import app.kassa.money.data.repo.EntryDraft
import androidx.compose.ui.text.style.TextOverflow
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.InputRules
import app.kassa.money.ui.common.AmountField
import app.kassa.money.ui.common.DateRow
import app.kassa.money.ui.common.momentOn
import app.kassa.money.domain.CardCycle
import app.kassa.money.domain.Money
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.ui.CardSnapshotFor
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.GlyphBadge
import app.kassa.money.ui.common.PageTop
import app.kassa.money.ui.common.RaisedPanel
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.common.StatusChip
import app.kassa.money.ui.common.Tone
import app.kassa.money.ui.common.TransactionRow
import app.kassa.money.ui.common.swipeShift
import app.kassa.money.ui.common.plural
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.importer.PrimaryButton
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.Instant
import java.time.LocalDate
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

/**
 * A card figure as shown everywhere: what's owed in ink, a credit (paid past zero) as a green +amount.
 * Nothing is clamped, so 159 paid on 100 payable shows +59 and the total drops by it.
 */
fun cardMoney(v: Long): String = if (v < 0) Money.format(-v, showPlus = true) else Money.format(v)

/** Balance payable: orange while anything is due, green once paid past it, plain at zero. Only payable is orange. */
@Composable
fun payableTint(v: Long): androidx.compose.ui.graphics.Color = cardTint(v, owed = if (v > 0) Kassa.colors.attention else Kassa.colors.ink)

@Composable
fun cardTint(v: Long, owed: androidx.compose.ui.graphics.Color = Kassa.colors.ink): androidx.compose.ui.graphics.Color = if (v < 0) Kassa.colors.positive else owed

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
fun CardRow(c: CardSnapshotFor, today: LocalDate, selected: Boolean = false, onClick: () -> Unit) {
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
                Text(cardMoney(s.totalBalance), style = KassaType.bodyStrong.copy(fontSize = 17.sp), color = cardTint(s.totalBalance), maxLines = 1, modifier = Modifier.padding(start = 8.dp))
            }
            Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(
                    androidx.compose.ui.text.buildAnnotatedString {
                        append("Payable ")
                        pushStyle(androidx.compose.ui.text.SpanStyle(color = payableTint(s.balancePayable), fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold))
                        append(cardMoney(s.balancePayable)); pop()
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
    /** Every entry on this card, newest first, so older statements can be shown. Falls back to the loaded months. */
    history: List<Transaction> = emptyList(),
) {
    val s = c.snapshot
    val a = c.account
    val id = a.id
    val dateNote: (Transaction) -> String = { t -> dateOf(t, ledger).format(MONTH_DAY) }

    // Set further down, where the statement navigator is built; a plain holder so assigning it doesn't recompose.
    val pageShift = remember { arrayOf<(Int) -> Unit>({}) }
    Column(Modifier.fillMaxSize().swipeShift { d -> pageShift[0](d) }.verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(a.name + (a.last4?.let { "  ··$it" } ?: ""), onBack) {
            androidx.compose.material3.TextButton(onClick = onEditCard) { Text("Edit", style = KassaType.bodyStrong, color = Kassa.colors.ink) }
        }
        RaisedPanel {
            val dueTone = dueTone(c, ledger.today)
            val days = s.daysUntilDue(ledger.today)
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text("Balance payable", style = KassaType.tiny, color = Kassa.colors.ink2)
                    app.kassa.money.ui.today.BigMoney(Math.abs(s.balancePayable), sizeSp = 36f, color = payableTint(s.balancePayable))
                    Text(
                        when {
                            s.balancePayable < 0 -> "Paid ${Money.format(-s.balancePayable)} extra"
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
                    Text(cardMoney(s.totalBalance), style = KassaType.stat, color = cardTint(s.totalBalance), maxLines = 1)
                }
                Box2(Modifier.weight(1f).fillMaxHeight()) {
                    Text("Outstanding", style = KassaType.tiny, color = Kassa.colors.ink2)
                    Text(Money.format(s.outstanding), style = KassaType.stat, color = Kassa.colors.ink, maxLines = 1)
                }
            }
            Gap(14)
            PrimaryButton(if (s.payable > 0) "Pay ${Money.format(s.payable)}" else "Record a payment", onPay)
        }
        // Statement by statement: 0 is what's charged since the last statement, 1 the last statement, and so on back.
        var back by androidx.compose.runtime.saveable.rememberSaveable(id) { androidx.compose.runtime.mutableIntStateOf(0) }
        val all = (if (history.isNotEmpty()) history else ledger.recent).filter { it.accountId == id || it.toAccountId == id }
        val oldest = all.minOfOrNull { it.epochMillis }?.let { Instant.ofEpochMilli(it).atZone(ledger.zone).toLocalDate() }
        val statementDay = a.statementDay ?: 1
        var close = s.lastClose
        repeat((back - 1).coerceAtLeast(0)) { close = app.kassa.money.domain.CardCycle.previousClose(statementDay, close) }
        val open = app.kassa.money.domain.CardCycle.previousClose(statementDay, close)
        val (from, to) = if (back == 0) s.lastClose to ledger.today else open to close
        // A statement runs from its opening statement date up to the day before its own: the statement date starts the next one.
        val list = all.filter { dateOf(it, ledger).let { d -> !d.isBefore(from) && (if (back == 0) !d.isAfter(to) else d.isBefore(to)) } }.sortedByDescending { it.epochMillis }
        val charges = list.filter { it.kind == TxKind.EXPENSE && it.accountId == id }.sumOf { it.amount }
        val credits = list.filter { it.toAccountId == id || (it.kind == TxKind.INCOME && it.accountId == id) }.sumOf { it.amount }
        val canBack = oldest != null && from.isAfter(oldest)
        val shift: (Int) -> Unit = { d -> if (d < 0 && canBack) back++ else if (d > 0 && back > 0) back-- }
        pageShift[0] = shift
        Gap(18)
        Column {
            app.kassa.money.ui.common.PeriodSwitcher(
                if (back == 0) "Outstanding" else "${to.format(MONTH_DAY)} statement",
                when (back) {
                    0 -> "Since ${s.lastClose.format(MONTH_DAY)} \u00b7 closes ${s.nextClose.format(MONTH_DAY)}"
                    1 -> "Due ${s.due.format(MONTH_DAY)}"
                    else -> "${from.format(MONTH_DAY)} to ${to.minusDays(1).format(MONTH_DAY)}"
                },
                canBack, back > 0, onBack = { shift(-1) }, onForward = { shift(1) },
            )
            Row(Modifier.padding(top = 8.dp, bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text("Charges ${Money.format(charges)}", style = KassaType.small, color = Kassa.colors.ink)
                if (credits > 0) Text("Payments ${Money.format(credits)}", style = KassaType.small, color = Kassa.colors.ink2)
                if (back == 1) Text(if (s.payable > 0) "${Money.format(s.payable)} left to pay" else if (s.balancePayable < 0) "Paid ${Money.format(-s.balancePayable)} extra" else "Paid in full", style = KassaType.small,
                    color = if (s.payable > 0) Kassa.colors.attention else Kassa.colors.positive)
            }
            if (list.isEmpty()) Text(if (back == 0) "No charges since the statement date." else "Nothing on this statement.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 8.dp))
            list.forEachIndexed { i, t -> TransactionRow(t, ledger, showDivider = i < list.lastIndex, noteOverride = dateNote(t), onClick = onEdit) }
        }
        Gap(24)
    }
}

/** A quieter box inside the raised panel. */
@Composable
private fun Box2(modifier: Modifier, content: @Composable () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(14.dp)).background(Kassa.colors.ground).padding(12.dp)) { content() }
}

private fun dateOf(t: Transaction, ledger: Ledger) = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate()

/** The card's own default payment account, if you set one and it's still open. Nothing else is guessed. */
private fun defaultPayFrom(c: CardSnapshotFor, ledger: Ledger): Long? =
    c.account.payFromId?.takeIf { id -> ledger.accounts.accounts.any { it.id == id && !it.archived } }

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
    var error by remember { mutableStateOf<String?>(null) }
    val amount = when (option) { "payable" -> s.payable; "total" -> total; else -> Money.parseCents(other) ?: 0 }
    var amountProblem by remember { mutableStateOf<String?>(null) }
    val sources = ledger.accounts.accounts.filter { !it.archived && it.type in setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp).widthIn(max = 640.dp)) {
            Text("Pay ${c.account.name}", style = KassaType.section, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("Amount", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            if (s.payable > 0) Option("Balance payable, ${Money.format(s.payable)}", "Due ${s.due.format(DAY_MONTH_DAY)}", option == "payable") { option = "payable" }
            if (total > 0) Option(
                "Total balance, ${Money.format(total)}",
                if (s.outstanding > 0 && s.payable > 0) "Balance payable plus ${Money.format(s.outstanding)} outstanding" else "Everything owed on this card",
                option == "total",
            ) { option = "total" }
            Option("Other amount", "A partial or extra payment", option == "other") { option = "other" }
            if (option == "other") AmountField(
                other, { other = it; amountProblem = null }, "Amount", Modifier.fillMaxWidth().padding(top = 8.dp), problem = amountProblem,
            )
            Text("From", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            FlowRow { sources.forEach { a -> Chip(a.name, selected = from == a.id) { from = a.id; error = null } } }
            Text("Date", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 16.dp, bottom = 4.dp))
            DateRow(date, ledger.today) { date = it }
            error?.let { Text(it, style = KassaType.small, color = Kassa.colors.attention) }
            Gap(12)
            PrimaryButton(if (amount > 0) "Record ${Money.format(amount)} payment" else "Enter an amount") {
                amountProblem = InputRules.amountProblem(amount)
                error = if (from == null) "Pick the account it's paid from." else null
                if (amountProblem == null && error == null) onPay(
                    EntryDraft(
                        id = null, kind = TxKind.TRANSFER, amount = amount, accountId = from, toAccountId = c.account.id,
                        categoryId = null, personId = null,
                        epochMillis = momentOn(date, ledger.today, ledger.zone),
                        note = "Card payment", splitWith = emptyList(),
                    )
                )
            }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Cancel", color = Kassa.colors.ink2) }
        }
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
