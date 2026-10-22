package dev.krishnagurram.kassa.ui.today

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.People
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import dev.krishnagurram.kassa.domain.AccountType
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Spending
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.domain.WeekPace
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.cards.CardRow
import dev.krishnagurram.kassa.ui.cards.cardsByDueDate
import dev.krishnagurram.kassa.ui.common.CategoryBreakdown
import dev.krishnagurram.kassa.ui.common.EntryRow
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.IconBadge
import dev.krishnagurram.kassa.ui.common.ValueRow
import dev.krishnagurram.kassa.ui.common.accountIcon
import dev.krishnagurram.kassa.ui.common.CategoryBadge
import dev.krishnagurram.kassa.ui.common.PageTop
import dev.krishnagurram.kassa.ui.common.RaisedPanel
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.common.TransactionRow
import dev.krishnagurram.kassa.ui.common.plural
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.Instant
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale

/** Account types that count as cash and bank on Today. */
val CASH_TYPES = setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID)

fun weekPace(ledger: Ledger): WeekPace.Result =
    WeekPace.compute(ledger.recent, ledger.today, WeekFields.of(Locale.getDefault()).firstDayOfWeek, ledger.zone)

/** How many things are waiting to be confirmed: bank alerts, investment transfers and bills due. */
fun pendingCount(ledger: Ledger): Int = ledger.assist.pending.size + ledger.dueRecurring().size

/** A card payment or a recurring bill coming up. [accountId] is set for card payments. */
data class Upcoming(val date: java.time.LocalDate, val name: String, val kind: String, val amount: Long?, val accountId: Long?)

fun upcoming(ledger: Ledger, days: Long): List<Upcoming> {
    val until = ledger.today.plusDays(days)
    val cards = ledger.cards().filter { it.snapshot.payable > 0 && !it.snapshot.due.isAfter(until) }
        .map { Upcoming(it.snapshot.due, it.account.name, "Card payment", it.snapshot.payable, it.account.id) }
    val bills = ledger.assist.recurring.mapNotNull { r ->
        dev.krishnagurram.kassa.domain.RecurringRules.upcoming(r, ledger.today, days)?.let { d ->
            val est = dev.krishnagurram.kassa.domain.RecurringRules.estimate(r, ledger.recent, d, ledger.zone)
            Upcoming(d, r.name, if (r.fixedAmount == null) "Bill, estimated" else "Bill", est, null)
        }
    }
    return (cards + bills).sortedBy { it.date }
}

/**
 * The Overview: spending this week or this month, what's coming up, and net worth. Built to fit the
 * cover screen without scrolling; everything else is a tap away.
 */
@Composable
fun TodayScreen(
    ledger: Ledger,
    contentPadding: PaddingValues,
    onOpenCard: (Long) -> Unit,
    onSpending: (month: Boolean) -> Unit,
    onUpcoming: () -> Unit,
    onReview: () -> Unit,
    onAccounts: () -> Unit,
    onCards: () -> Unit,
) {
    var month by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val pending = pendingCount(ledger)
        val banner: @Composable () -> Unit = {
            if (pending > 0) Row(
                Modifier.fillMaxWidth().padding(bottom = 14.dp).clip(RoundedCornerShape(12.dp))
                    .background(Kassa.colors.attention.copy(alpha = 0.10f)).clickable(onClick = onReview)
                    .padding(horizontal = 12.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(7.dp).clip(RoundedCornerShape(50)).background(Kassa.colors.attention))
                Text(
                    if (pending == 1) "1 transaction to confirm" else "$pending transactions to confirm",
                    style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink,
                    modifier = Modifier.weight(1f).padding(start = 10.dp),
                )
                androidx.compose.material3.Icon(dev.krishnagurram.kassa.ui.activity.Chevron(left = false), null, tint = Kassa.colors.ink3, modifier = Modifier.size(18.dp))
            }
        }
        val spending: @Composable () -> Unit = { SpendingSection(ledger, month, { month = it }, wide, onOpen = { onSpending(month) }) }
        val coming: @Composable () -> Unit = { UpcomingSection(ledger, if (wide) 5 else 2, onOpenCard, onUpcoming) }
        val worth: @Composable () -> Unit = { NetWorthSection(ledger, onAccounts, onCards) }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)) {
            Text("Overview", style = KassaType.title, color = Kassa.colors.ink, modifier = Modifier.padding(bottom = 10.dp))
            if (wide) {
                banner()
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.weight(1f)) { spending() }
                    androidx.compose.material3.VerticalDivider(color = Kassa.colors.line, modifier = Modifier.height(420.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(18.dp)) { coming(); worth() }
                }
            } else {
                Column(Modifier.widthIn(max = 640.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column { banner(); spending() }
                    coming(); worth()
                }
            }
        }
    }
}

/** A section title on the left and a link to the full view on the right. */
@Composable
private fun SectionTop(title: String, link: String? = null, onLink: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = KassaType.section, color = Kassa.colors.ink)
        Box(Modifier.weight(1f))
        trailing()
        if (link != null && onLink != null) LinkText(link, onLink)
    }
}

@Composable
private fun LinkText(text: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2)
        androidx.compose.material3.Icon(dev.krishnagurram.kassa.ui.activity.Chevron(left = false), null, tint = Kassa.colors.ink3, modifier = Modifier.size(18.dp))
    }
}

/** Dollars large, cents small, the way bank apps show a headline figure. */
@Composable
fun BigMoney(cents: Long, sizeSp: Float = 38f, color: Color = Kassa.colors.ink) {
    val text = Money.format(cents)
    val dot = text.lastIndexOf('.')
    Text(
        buildAnnotatedString {
            append(text.substring(0, dot))
            withStyle(SpanStyle(fontSize = (sizeSp * 0.5f).sp, baselineShift = androidx.compose.ui.text.style.BaselineShift(0.8f))) {
                append(text.substring(dot))
            }
        },
        style = KassaType.hero.copy(fontSize = sizeSp.sp, letterSpacing = (-sizeSp / 32f).sp),
        color = color, maxLines = 1,
    )
}

@Composable
private fun SpendingSection(ledger: Ledger, month: Boolean, setMonth: (Boolean) -> Unit, wide: Boolean, onOpen: () -> Unit) {
    val from = if (month) ledger.period.start else weekPace(ledger).start
    val inRange = ledger.recent.filter { dateOf(it, ledger).let { d -> !d.isBefore(from) && !d.isAfter(ledger.today) } }
    val total = inRange.filter { it.kind == TxKind.EXPENSE }.sumOf { it.categoryAmount }
    val income = inRange.filter { it.kind == TxKind.INCOME }.sumOf { it.amount }
    val days = ChronoUnit.DAYS.between(from, ledger.today) + 1
    val top = spendingByCategory(ledger, from).take(3)
    val monthName = ledger.period.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())

    SectionTop("Spending") {
        Row(Modifier.clip(RoundedCornerShape(50)).background(Kassa.colors.chip).padding(3.dp)) {
            listOf(false to "This week", true to monthName).forEach { (m, label) ->
                val on = m == month
                Text(
                    label, style = KassaType.tiny.copy(fontWeight = FontWeight.SemiBold),
                    color = if (on) Kassa.colors.onInk else Kassa.colors.ink2,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Kassa.colors.ink else Color.Transparent)
                        .clickable { setMonth(m) }.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }
    }
    BigMoney(total)
    if (top.isNotEmpty()) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        top.forEach { (c, amt) ->
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Kassa.colors.raised)
                    .then(if (c != null) Modifier.clickable(onClick = onOpen) else Modifier).padding(10.dp),
            ) {
                CategoryBadge(c, size = 26)
                Text(c?.name ?: "Uncategorized", style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                Text(Money.format(amt), style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1)
            }
        }
        repeat(3 - top.size) { Box(Modifier.weight(1f)) }
    }
    HorizontalDivider(color = Kassa.colors.line, modifier = Modifier.padding(top = 12.dp, bottom = 8.dp))
    Row {
        Column(Modifier.weight(1f)) {
            Text("Income", style = KassaType.tiny, color = Kassa.colors.ink2)
            Text(Money.format(income), style = KassaType.bodyStrong, color = if (income > 0) Kassa.colors.positive else Kassa.colors.ink)
        }
        Column(Modifier.weight(1f)) {
            Text("Daily average", style = KassaType.tiny, color = Kassa.colors.ink2)
            Text(Money.format(total / days), style = KassaType.bodyStrong, color = Kassa.colors.ink)
        }
    }
    Box(Modifier.padding(top = 6.dp)) { LinkText("Where it went", onOpen) }
}

@Composable
private fun UpcomingSection(ledger: Ledger, show: Int, onOpenCard: (Long) -> Unit, onAll: () -> Unit) {
    val items = upcoming(ledger, 30)
    SectionTop("Upcoming", "Next 30 days", onAll)
    if (items.isEmpty()) {
        Text("Nothing due in the next 30 days.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 8.dp))
        return
    }
    items.take(show).forEach { u -> UpcomingRow(u, ledger, onOpenCard) }
}

@Composable
fun UpcomingRow(u: Upcoming, ledger: Ledger, onOpenCard: (Long) -> Unit) {
    val soon = ChronoUnit.DAYS.between(ledger.today, u.date) <= 3
    Column(if (u.accountId != null) Modifier.clickable { onOpenCard(u.accountId) } else Modifier) {
        HorizontalDivider(color = Kassa.colors.line)
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                u.date.format(DateTimeFormatter.ofPattern("MMM d")).uppercase(),
                style = KassaType.tiny.copy(fontWeight = FontWeight.Bold), color = if (soon) Kassa.colors.attention else Kassa.colors.ink2,
                modifier = Modifier.widthIn(min = 56.dp),
            )
            Column(Modifier.weight(1f)) {
                Text(u.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1)
                Text(u.kind, style = KassaType.tiny, color = Kassa.colors.ink2)
            }
            Text(u.amount?.let { Money.format(it) } ?: "", style = KassaType.bodyStrong, color = Kassa.colors.ink)
        }
    }
}

@Composable
private fun NetWorthSection(ledger: Ledger, onAccounts: () -> Unit, onCards: () -> Unit) {
    val active = ledger.accounts.accounts.filter { !it.archived }
    val bal = ledger.accounts.balances
    val cash = active.filter { it.type in CASH_TYPES }.sumOf { bal[it.id] ?: 0 }
    val cardOwed = -active.filter { it.type == AccountType.CREDIT_CARD }.sumOf { bal[it.id] ?: 0 }
    val investAccts = active.filter { it.type == AccountType.INVESTMENT }
    val invest = investAccts.sumOf { (bal[it.id] ?: 0) + ledger.holdingsValue(it.id) }
    val people = ledger.personBalances().values
    val receivable = people.filter { it > 0 }.sum()
    val payable = -people.filter { it < 0 }.sum()
    val holdings = ledger.assist.holdings.filter { h -> active.any { it.id == h.accountId } }.sumOf { it.value }
    val worth = active.sumOf { bal[it.id] ?: 0 } + holdings + receivable - payable

    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Net worth", style = KassaType.section, color = Kassa.colors.ink)
        Box(Modifier.padding(start = 10.dp)) { BigMoney(worth, sizeSp = 18f) }
        Box(Modifier.weight(1f))
        LinkText("Accounts", onAccounts)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorthTile(Modifier.weight(1f), accountIcon(AccountType.CHECKING), "Cash and bank", Money.format(cash), onAccounts)
            WorthTile(Modifier.weight(1f), accountIcon(AccountType.CREDIT_CARD), "Card balances", Money.format(-cardOwed), onCards)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            WorthTile(Modifier.weight(1f), accountIcon(AccountType.INVESTMENT), "Investments", Money.format(invest), onAccounts)
            WorthTile(Modifier.weight(1f), androidx.compose.material.icons.Icons.Outlined.People, "Receivable", Money.format(receivable), onAccounts)
        }
    }
}

@Composable
private fun WorthTile(modifier: Modifier, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, onClick: () -> Unit) {
    Row(
        modifier.clip(RoundedCornerShape(14.dp)).background(Kassa.colors.raised).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(icon, size = 30)
        Column(Modifier.padding(start = 10.dp)) {
            Text(label, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1)
            Text(value, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1)
        }
    }
}

/** Every card payment and bill in the next 30 days, by date. */
@Composable
fun UpcomingPage(ledger: Ledger, contentPadding: PaddingValues, onBack: (() -> Unit)?, onOpenCard: (Long) -> Unit) {
    val items = upcoming(ledger, 30)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop("Next 30 days", onBack)
        RaisedPanel {
            Text("Total due", style = KassaType.tiny, color = Kassa.colors.ink2)
            BigMoney(items.sumOf { it.amount ?: 0 }, sizeSp = 34f)
            Text(plural(items.size, "payment"), style = KassaType.small, color = Kassa.colors.ink2)
        }
        Gap(12)
        if (items.isEmpty()) Text("Nothing due in the next 30 days.", style = KassaType.body, color = Kassa.colors.ink2)
        items.forEach { UpcomingRow(it, ledger, onOpenCard) }
        Gap(24)
    }
}

/** Everything waiting to be confirmed, on its own page. */
@Composable
fun ReviewPage(ledger: Ledger, contentPadding: PaddingValues, onBack: (() -> Unit)?, needs: NeedsYouActions) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop("To confirm", onBack)
        if (pendingCount(ledger) == 0) Text("Nothing to confirm.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(16.dp))
        NeedsYou(ledger, needs)
        Gap(24)
    }
}

/** Expenses from [from] through today, by top category, largest first. */
private fun spendingByCategory(ledger: Ledger, from: java.time.LocalDate): List<Pair<dev.krishnagurram.kassa.domain.Category?, Long>> =
    ledger.recent.filter { it.kind == TxKind.EXPENSE && dateOf(it, ledger).let { d -> !d.isBefore(from) && !d.isAfter(ledger.today) } }
        .groupBy { Spending.topOf(it.categoryId, ledger.categoryById) }
        .map { (c, l) -> c to l.sumOf { it.categoryAmount } }.sortedByDescending { it.second }

/** Where the week's or the month's spending went: by category, then every entry by day. */
@Composable
fun SpendingPage(
    ledger: Ledger,
    month: Boolean,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)?,
    onEdit: (Transaction) -> Unit,
    onOpenCategory: (Long) -> Unit,
) {
    val from = if (month) ledger.period.start else weekPace(ledger).start
    val entries = ledger.recent.filter { it.kind == TxKind.EXPENSE && dateOf(it, ledger).let { d -> !d.isBefore(from) && !d.isAfter(ledger.today) } }
        .sortedWith(compareByDescending<Transaction> { it.epochMillis }.thenByDescending { it.id })
    val byTop = spendingByCategory(ledger, from)
    val total = entries.sumOf { it.categoryAmount }
    val title = if (month) ledger.period.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " spending" else "This week's spending"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(title, onBack)
        RaisedPanel {
            Row(verticalAlignment = Alignment.Bottom) {
                Text("Spent", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.weight(1f))
                Text("${from.format(DateTimeFormatter.ofPattern("MMM d"))} to today", style = KassaType.tiny, color = Kassa.colors.ink3)
            }
            Text(Money.format(total), style = KassaType.hero.copy(fontSize = KassaType.hero.fontSize * 0.75f), color = Kassa.colors.ink)
            Text(plural(entries.size, "expense"), style = KassaType.small, color = Kassa.colors.ink2)
        }
        if (entries.isEmpty()) {
            Gap(16)
            Text("No spending yet.", style = KassaType.body, color = Kassa.colors.ink2)
            return@Column
        }
        SectionHeader("By category", "Share")
        CategoryBreakdown(byTop, onOpen = if (month) ({ c: dev.krishnagurram.kassa.domain.Category -> onOpenCategory(c.id) }) else null)
        entries.groupBy { dateOf(it, ledger) }.forEach { (day, list) ->
            SectionHeader(
                when (day) { ledger.today -> "Today"; ledger.today.minusDays(1) -> "Yesterday"; else -> day.format(DateTimeFormatter.ofPattern("EEEE, MMM d")) },
                Money.format(list.sumOf { it.categoryAmount }),
            )
            list.forEachIndexed { i, t -> TransactionRow(t, ledger, showDivider = i < list.lastIndex, onClick = onEdit) }
        }
        Gap(24)
    }
}

private fun dateOf(t: Transaction, ledger: Ledger) = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate()

@Composable
fun DateBlock(date: java.time.LocalDate, soon: Boolean) {
    val bg = if (soon) Kassa.colors.attention.copy(alpha = 0.15f) else Kassa.colors.chip
    val fg = if (soon) Kassa.colors.attention else Kassa.colors.ink
    Column(
        Modifier.size(40.dp, 44.dp).clip(RoundedCornerShape(11.dp)).background(bg),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
    ) {
        Text(date.format(DateTimeFormatter.ofPattern("MMM")), style = KassaType.tiny, color = if (soon) fg else Kassa.colors.ink2)
        Text(date.dayOfMonth.toString(), style = KassaType.bodyStrong, color = fg)
    }
}

fun inDays(n: Long): String = when {
    n == 0L -> "today"
    n == 1L -> "tomorrow"
    n < 0 -> "${-n} days ago"
    else -> "in $n days"
}
