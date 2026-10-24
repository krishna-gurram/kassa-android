package dev.krishnagurram.kassa.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.domain.InvestmentTransfer
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Recurring
import dev.krishnagurram.kassa.domain.RecurringRules
import dev.krishnagurram.kassa.domain.Repeat
import dev.krishnagurram.kassa.domain.Repeats
import dev.krishnagurram.kassa.domain.Spending
import dev.krishnagurram.kassa.ui.CardSnapshotFor
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.activity.Chevron
import dev.krishnagurram.kassa.ui.common.CategoryBadge
import dev.krishnagurram.kassa.ui.common.ConfirmDialog
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.GlyphBadge
import dev.krishnagurram.kassa.ui.common.PageTop
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** How far ahead bills and card payments start showing. */
const val NOTICE_DAYS = 5L

/**
 * One thing that needs the user. Nothing here posts by itself: each choice is theirs.
 * [date] orders the page, oldest first; suggestions go last. [key] identifies it once cleared.
 */
sealed interface Notice {
    val key: String
    val date: LocalDate
    /** A few words for Overview's summary line. */
    val label: String

    /** A bill overdue or due within [NOTICE_DAYS]. Clearing it skips that month. */
    data class Bill(val bill: Recurring, val due: LocalDate) : Notice {
        override val key get() = "bill:${bill.id}:$due"
        override val date get() = due
        override val label get() = bill.name
    }

    /** A card with a balance payable due within [NOTICE_DAYS]. Clearing it hides it until the next statement. */
    data class Card(val card: CardSnapshotFor) : Notice {
        override val key get() = "card:${card.account.id}:${card.snapshot.lastClose}"
        override val date get() = card.snapshot.due
        override val label get() = card.account.name
    }

    /** Money moved into investments that hasn't been explained yet. Clearing it stops asking. */
    data class Transfer(val transfer: InvestmentTransfer, override val date: LocalDate) : Notice {
        override val key get() = "transfer:${transfer.id}"
        override val label get() = "Investment transfer"
    }

    /** A monthly charge with the same amount each time that isn't a bill yet. Clearing it never suggests it again. */
    data class Suggestion(val repeat: Repeat) : Notice {
        override val key get() = "suggest:${repeat.key}:${repeat.categoryId}"
        override val date: LocalDate get() = LocalDate.MAX
        override val label get() = repeat.name
    }
}

/** Everything on the Notifications page, in order. */
fun notices(ledger: Ledger): List<Notice> {
    val until = ledger.today.plusDays(NOTICE_DAYS)
    val bills = ledger.bills.mapNotNull { b -> RecurringRules.nextDue(b, ledger.recent, ledger.today, ledger.zone, NOTICE_DAYS)?.let { Notice.Bill(b, it) } }
    val cards = ledger.cards().filter { it.snapshot.payable > 0 && !it.snapshot.due.isAfter(until) }.map { Notice.Card(it) }
    val transfers = ledger.transfers.map { Notice.Transfer(it, Instant.ofEpochMilli(it.epochMillis).atZone(ledger.zone).toLocalDate()) }
    val billKeys = ledger.bills.map { Repeats.key(it.name) }.toSet()
    val suggestions = ledger.repeats
        .filter { it.steadyMonthly && it.key !in billKeys && ledger.categoryById[it.categoryId]?.hidden == false }
        .map { Notice.Suggestion(it) }
    return (bills + cards + transfers + suggestions).filter { it.key !in ledger.cleared }.sortedBy { it.date }
}

/** What the Notifications page can do. */
interface NoticeActions {
    /** Records the bill for [amount]. */
    fun addBill(n: Notice.Bill, amount: Long)
    /** Opens the entry page filled in from the bill, to type what was actually paid. */
    fun editBill(n: Notice.Bill, amount: Long)
    fun payCard(accountId: Long)
    fun allocate(t: InvestmentTransfer)
    /** Opens a new bill filled in from the repeat. */
    fun trackAsBill(r: Repeat)
    fun clear(notices: List<Notice>)
}

/** Overview's link to the page: only there when something needs you. */
@Composable
fun NotificationsRow(list: List<Notice>, onOpen: () -> Unit, modifier: Modifier = Modifier) {
    if (list.isEmpty()) return
    val shown = list.take(2).joinToString(" · ") { it.label }
    val more = list.size - 2
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Kassa.colors.raised).clickable(onClick = onOpen)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(28.dp).clip(CircleShape).background(Kassa.colors.ink), contentAlignment = Alignment.Center) {
            Text(list.size.toString(), style = KassaType.tiny.copy(fontWeight = FontWeight.Bold), color = Kassa.colors.onInk, maxLines = 1)
        }
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text("Notifications", style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1)
            Text(if (more > 0) "$shown · $more more" else shown, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Icon(Chevron(left = false), null, tint = Kassa.colors.ink3, modifier = Modifier.size(18.dp))
    }
}

/** Every notification on its own page, with ✕ on each and Clear all. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationsPage(ledger: Ledger, contentPadding: PaddingValues, onBack: (() -> Unit)?, actions: NoticeActions) {
    val list = remember(ledger) { notices(ledger) }
    var confirmAll by remember { mutableStateOf(false) }
    if (confirmAll) ConfirmDialog(
        "Clear all notifications?",
        "Nothing is added or paid. Bills come back next month, cards after their next statement.",
        "Clear all", onConfirm = { actions.clear(list) }, onDismiss = { confirmAll = false }, destructive = false,
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop("Notifications", onBack) {
            if (list.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text("Clear all", style = KassaType.bodyStrong, color = Kassa.colors.ink) }
        }
        if (list.isEmpty()) Text("Nothing needs you.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(16.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            list.forEach { n -> NoticeCard(ledger, n, actions) }
        }
        Gap(24)
    }
}

@Composable
private fun NoticeCard(ledger: Ledger, n: Notice, actions: NoticeActions) {
    val clear = { actions.clear(listOf(n)) }
    when (n) {
        is Notice.Bill -> {
            val r = n.bill
            val est = RecurringRules.estimate(r, ledger.recent, n.due.plusDays(1), ledger.zone)
            val last = RecurringRules.recent(r, ledger.recent)
            Card(
                kicker = dueText(n.due, ledger.today) +
                    if (r.fixedAmount == null && last.isNotEmpty()) ". Last ${last.size}: ${last.joinToString { Money.format(it, wholeDollars = true) }}" else "",
                badge = { CategoryBadge(Spending.topOf(r.categoryId, ledger.categoryById)) },
                title = r.name,
                subtitle = if (r.fixedAmount != null) "Fixed amount" else if (est != null) "Estimated, edit if the bill differs" else "No history yet, enter the amount",
                amount = est?.let { Money.format(it) } ?: "",
                onClear = clear,
            ) {
                if (est != null) Chip("Add ${Money.format(est)}", selected = true) { actions.addBill(n, est) }
                Chip(if (est != null) "Edit amount" else "Enter amount", selected = est == null) { actions.editBill(n, est ?: 0) }
            }
        }
        is Notice.Card -> Card(
            kicker = "Card payment, " + dueText(n.card.snapshot.due, ledger.today).replaceFirstChar { it.lowercase() },
            badge = { GlyphBadge(n.card.account.name.take(1)) },
            title = n.card.account.name,
            subtitle = "Balance payable",
            amount = Money.format(n.card.snapshot.payable),
            onClear = clear,
        ) { Chip("Pay", selected = true) { actions.payCard(n.card.account.id) } }
        is Notice.Transfer -> Card(
            kicker = "Investment transfer on ${n.date.format(DateTimeFormatter.ofPattern("MMM d"))}",
            badge = { GlyphBadge("↗") },
            title = "Where did it go?",
            subtitle = "Moved to ${ledger.accountById[n.transfer.accountId]?.name ?: "investments"}",
            amount = Money.format(n.transfer.amount),
            onClear = clear,
        ) { Chip("Answer", selected = true) { actions.allocate(n.transfer) } }
        is Notice.Suggestion -> {
            val r = n.repeat
            val same = r.amounts.reversed().takeWhile { it == r.amounts.last() }.size
            Card(
                kicker = "Looks like a bill",
                badge = { CategoryBadge(Spending.topOf(r.categoryId, ledger.categoryById)) },
                title = r.name,
                subtitle = "Every month, the same amount $same times",
                amount = Money.format(r.typical),
                onClear = clear,
            ) { Chip("Track as bill", selected = true) { actions.trackAsBill(r) } }
        }
    }
}

/** "Due today", "Due Thu, Oct 29" or "Overdue, was due Tue, Oct 20". */
private fun dueText(due: LocalDate, today: LocalDate): String {
    val day = due.format(DateTimeFormatter.ofPattern("EEE, MMM d"))
    return when {
        due == today -> "Due today"
        due.isBefore(today) -> "Overdue, was due $day"
        else -> "Due $day"
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Card(
    kicker: String,
    badge: @Composable () -> Unit,
    title: String,
    subtitle: String,
    amount: String,
    onClear: () -> Unit,
    buttons: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(20.dp)
    Column(Modifier.fillMaxWidth().clip(shape).background(Kassa.colors.raised).border(1.dp, Kassa.colors.line, shape).padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(kicker, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            IconButton(onClick = onClear, modifier = Modifier.size(48.dp).offset(x = 12.dp)) {
                Icon(Icons.Outlined.Close, "Clear", tint = Kassa.colors.ink2, modifier = Modifier.size(18.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            badge()
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(title, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = KassaType.small, color = Kassa.colors.ink2, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Text(amount, style = KassaType.bodyStrong, color = Kassa.colors.ink)
        }
        FlowRow(Modifier.padding(top = 12.dp)) { buttons() }
    }
}
