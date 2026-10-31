package app.kassa.money.ui.today

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
import androidx.compose.foundation.layout.heightIn
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
import app.kassa.money.domain.Account
import app.kassa.money.domain.DueItems
import app.kassa.money.domain.DueKind
import app.kassa.money.domain.DueNotices
import app.kassa.money.domain.InvestmentTransfer
import app.kassa.money.domain.Money
import app.kassa.money.domain.Recurring
import app.kassa.money.domain.RecurringRules
import app.kassa.money.domain.Repeat
import app.kassa.money.domain.Repeats
import app.kassa.money.domain.Spending
import app.kassa.money.ui.common.weekdayDate
import app.kassa.money.ui.common.shortDate
import app.kassa.money.ui.CardSnapshotFor
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.activity.Chevron
import app.kassa.money.ui.common.CategoryBadge
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.GlyphBadge
import app.kassa.money.ui.common.PageTop
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.entry.Chip
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.Instant
import java.time.LocalDate

/**
 * One thing that needs the user. Nothing here posts by itself: each choice is theirs.
 * [date] orders the page, oldest first; suggestions go last. [key] identifies it once cleared.
 */
sealed interface Notice {
    val key: String
    val date: LocalDate
    /** A few words for Overview's summary line. */
    val label: String

    /** A bill on its due day or overdue. Clearing it skips that month. */
    data class Bill(val bill: Recurring, val due: LocalDate) : Notice {
        override val key get() = "bill:${bill.id}:$due"
        override val date get() = due
        override val label get() = bill.name
    }

    /** A card with a balance payable, on its due day or overdue. Clearing it hides it until the next statement. */
    data class Card(val card: CardSnapshotFor) : Notice {
        override val key get() = "card:${card.account.id}:${card.snapshot.lastClose}"
        override val date get() = card.snapshot.due
        override val label get() = card.account.name
    }

    /** A loan's scheduled payment on its due day or overdue. Clearing it hides that due date; the next one shows later. */
    data class Loan(val loan: Account, val due: LocalDate, val amount: Long) : Notice {
        override val key get() = "loan:${loan.id}:${due.toEpochDay()}"
        override val date get() = due
        override val label get() = loan.name
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

/**
 * Everything on the Notifications page, in order. A bill, card payment or loan payment is here from its due day, never
 * before, and stays while it is overdue until the user acts or clears it (see [DueItems] for what counts as due).
 */
fun notices(ledger: Ledger): List<Notice> {
    val today = ledger.today.toEpochDay().toInt()
    val cards = ledger.cards().associateBy { it.account.id }
    val due = DueItems.build(
        ledger.accounts.accounts, ledger.accounts.balances, ledger.recent, ledger.bills,
        ledger.loans.terms, ledger.loans.changes, ledger.loans.payments, ledger.today, ledger.zone,
    ).filter { DueNotices.isWaiting(it, today) }.mapNotNull { item ->
        val day = LocalDate.ofEpochDay(item.dueDay.toLong())
        when (item.kind) {
            DueKind.CARD -> cards[item.id]?.let { Notice.Card(it) }
            DueKind.LOAN -> ledger.accountById[item.id]?.let { Notice.Loan(it, day, item.amount ?: 0L) }
            DueKind.BILL -> ledger.bills.firstOrNull { it.id == item.id }?.let { Notice.Bill(it, day) }
        }
    }
    val transfers = ledger.transfers.map { Notice.Transfer(it, Instant.ofEpochMilli(it.epochMillis).atZone(ledger.zone).toLocalDate()) }
    val billKeys = ledger.bills.map { Repeats.key(it.name) }.toSet()
    val suggestions = ledger.repeats
        .filter { it.steadyMonthly && it.key !in billKeys && ledger.categoryById[it.categoryId]?.hidden == false }
        .map { Notice.Suggestion(it) }
    return (due + transfers + suggestions).filter { it.key !in ledger.cleared }.sortedBy { it.date }
}

/** What the Notifications page can do. */
interface NoticeActions {
    /** Records the bill for [amount]. */
    fun addBill(n: Notice.Bill, amount: Long)
    /** Opens the entry page filled in from the bill, to type what was actually paid. */
    fun editBill(n: Notice.Bill, amount: Long)
    fun payCard(accountId: Long)
    /** Opens the loan payment sheet, with the scheduled payment. */
    fun payLoan(accountId: Long)
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
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(Kassa.colors.chip).clickable(onClick = onOpen)
            .heightIn(min = 48.dp).padding(horizontal = 12.dp, vertical = 8.dp),
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
        "Nothing is added or paid. Bills come back next month, cards after their next statement, loans on their next payment day.",
        "Clear all", onConfirm = { actions.clear(list) }, onDismiss = { confirmAll = false }, destructive = false,
    )
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop("Notifications", onBack) {
            if (list.isNotEmpty()) TextButton(onClick = { confirmAll = true }) { Text("Clear all", style = KassaType.bodyStrong, color = Kassa.colors.ink) }
        }
        if (list.isEmpty()) Text("Nothing needs you.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(16.dp))
        val payments = list.filter { it is Notice.Bill || it is Notice.Card || it is Notice.Loan }
        listOf(
            "Due today" to payments.filter { it.date == ledger.today },
            "Overdue" to payments.filter { it.date.isBefore(ledger.today) },
            "Investment transfers" to list.filterIsInstance<Notice.Transfer>(),
            "Suggested bills" to list.filterIsInstance<Notice.Suggestion>(),
        ).filter { it.second.isNotEmpty() }.forEach { (title, group) ->
            SectionHeader(title)
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                group.forEach { n -> NoticeCard(ledger, n, actions) }
            }
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
        is Notice.Loan -> Card(
            kicker = "Loan payment, " + dueText(n.due, ledger.today).replaceFirstChar { it.lowercase() },
            badge = { GlyphBadge(n.loan.name.take(1)) },
            title = n.loan.name,
            subtitle = "Scheduled payment",
            amount = Money.format(n.amount),
            onClear = clear,
        ) { Chip("Pay", selected = true) { actions.payLoan(n.loan.id) } }
        is Notice.Transfer -> Card(
            kicker = "Investment transfer on ${shortDate(n.date)}",
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
    val day = weekdayDate(due)
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
