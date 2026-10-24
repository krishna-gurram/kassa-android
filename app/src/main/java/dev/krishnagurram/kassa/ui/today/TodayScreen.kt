package dev.krishnagurram.kassa.ui.today

import androidx.compose.ui.text.style.TextOverflow
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
import androidx.compose.runtime.remember
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
import dev.krishnagurram.kassa.ui.common.swipeShift
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
    WeekPace.compute(ledger.recent, ledger.today, ledger.firstDayOfWeek, ledger.zone)

/** How many things are waiting to be confirmed: bank alerts, investment transfers and bills due. */
/** A card payment or a recurring bill coming up. [accountId] is set for card payments. */
data class Upcoming(val date: java.time.LocalDate, val name: String, val kind: String, val amount: Long?, val accountId: Long?)

fun upcoming(ledger: Ledger, days: Long): List<Upcoming> {
    val until = ledger.today.plusDays(days)
    val cards = ledger.cards().filter { it.snapshot.payable > 0 && !it.snapshot.due.isAfter(until) }
        .map { Upcoming(it.snapshot.due, it.account.name, "Card payment", it.snapshot.payable, it.account.id) }
    val bills = ledger.bills.mapNotNull { r ->
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
    onSpending: (month: Boolean, income: Boolean) -> Unit,
    onOpenCategory: (Long) -> Unit,
    onUpcoming: () -> Unit,
    onNotifications: () -> Unit,
    onAccounts: () -> Unit,
    onCards: () -> Unit,
) {
    // The month comes first: it's what budgets and statements are measured in.
    var month by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var income by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        // The unfolded inner screen is both wide and tall: a list of categories fills the height that
        // tiles would leave empty. The cover screen in landscape is wide but short, so it keeps tiles.
        val roomy = wide && maxHeight >= 560.dp
        val waiting = remember(ledger) { notices(ledger) }
        val banner: @Composable () -> Unit = { NotificationsRow(waiting, onNotifications, Modifier.padding(bottom = 14.dp)) }
        // Tall screens fit with no scrolling: Upcoming lists as many as there's room for.
        val fit = maxHeight >= dev.krishnagurram.kassa.ui.common.FIT_MIN_HEIGHT
        val spending: @Composable () -> Unit = {
            // Swipe the spending card between this week and this month, the same as its toggle.
            // The month is on the left of the toggle, so a swipe back shows it.
            Column(Modifier.swipeShift { d -> month = d < 0 }) {
                FlowSection(ledger, income, { income = it }, month, { month = it }, roomy, onOpen = { onSpending(month, income) }, onOpenCategory = onOpenCategory)
            }
        }
        val coming: @Composable (Modifier) -> Unit = { m -> UpcomingSection(ledger, if (fit) null else if (wide) 5 else 3, onOpenCard, onUpcoming, m) }
        val worth: @Composable () -> Unit = { NetWorthSection(ledger, onAccounts, onCards) }

        Column(Modifier.fillMaxSize().then(if (fit) Modifier else Modifier.verticalScroll(rememberScrollState())).padding(contentPadding)) {
            Text("Overview", style = KassaType.title, color = Kassa.colors.ink, modifier = Modifier.padding(bottom = 10.dp))
            if (wide) {
                banner()
                Row(if (fit) Modifier.weight(1f) else Modifier.height(androidx.compose.foundation.layout.IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.weight(1f)) { spending() }
                    androidx.compose.material3.VerticalDivider(color = Kassa.colors.line)
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (roomy) 28.dp else 18.dp)) {
                        coming(if (fit) Modifier.weight(1f) else Modifier); worth()
                    }
                }
            } else {
                Column(Modifier.widthIn(max = 640.dp).then(if (fit) Modifier.weight(1f) else Modifier), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column { banner(); spending() }
                    coming(if (fit) Modifier.weight(1f) else Modifier); worth()
                }
            }
        }
    }
}

/** A section title on the left and a link to the full view on the right. */
@Composable
private fun SectionTop(title: String, link: String? = null, onLink: (() -> Unit)? = null, trailing: @Composable () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KassaType.section, color = Kassa.colors.ink)
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

/** Dollars large, cents smaller and lighter on the same baseline, the way bank apps show a headline figure. */
@Composable
fun BigMoney(cents: Long, sizeSp: Float = 38f, color: Color = Kassa.colors.ink) {
    val ink = Kassa.colors.ink
    val centsColor = if (color == ink) Kassa.colors.ink2 else color
    val text = Money.format(cents)
    val dot = text.lastIndexOf('.')
    Text(
        buildAnnotatedString {
            append(text.substring(0, dot))
            // Cents sit on the same baseline and smaller, so the dollars read first. A colored total (income) keeps
            // its color for the cents too; a plain one shows them a shade lighter.
            withStyle(SpanStyle(fontSize = (sizeSp * (if (sizeSp < 24f) 0.8f else 0.5f)).sp, color = centsColor, fontWeight = FontWeight.SemiBold)) {
                append(text.substring(dot))
            }
        },
        style = KassaType.hero.copy(fontSize = sizeSp.sp, letterSpacing = (-sizeSp / 32f).sp),
        color = color, maxLines = 1,
    )
}

/** A small two-way switch. [dark] uses the filled style for the period; the other style reads as a title. */
@Composable
private fun Switch2(options: List<Pair<Boolean, String>>, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Kassa.colors.chip).padding(3.dp)) {
        options.forEach { (v, label) ->
            val on = v == value
            Text(
                label, style = KassaType.tiny.copy(fontWeight = FontWeight.SemiBold),
                color = if (on) Kassa.colors.onInk else Kassa.colors.ink2,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Kassa.colors.ink else Color.Transparent)
                    .clickable { onChange(v) }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Spending or income, this week or this month, in the same layout: the total and the top three categories.
 * The title itself switches between spending and income.
 */
@Composable
private fun FlowSection(ledger: Ledger, income: Boolean, setIncome: (Boolean) -> Unit, month: Boolean, setMonth: (Boolean) -> Unit, roomy: Boolean, onOpen: () -> Unit, onOpenCategory: (Long) -> Unit) {
    val from = if (month) ledger.period.start else weekPace(ledger).start
    val kind = if (income) TxKind.INCOME else TxKind.EXPENSE
    val rows = spendingByCategory(ledger, from, kind)
    val total = rows.sumOf { it.second }
    val top = rows.take(if (roomy) 6 else 3)
    val monthName = ledger.period.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())

    Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        listOf(false to "Spending", true to "Income").forEach { (v, label) ->
            Text(
                label, style = KassaType.section,
                color = if (v == income) Kassa.colors.ink else Kassa.colors.ink3,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { setIncome(v) }.padding(end = 14.dp, top = 2.dp, bottom = 2.dp),
            )
        }
        Box(Modifier.weight(1f))
        Switch2(listOf(true to monthName, false to "This week"), month, setMonth)
    }
    BigMoney(total, sizeSp = if (roomy) 46f else 38f, color = if (income && total > 0) Kassa.colors.positive else Kassa.colors.ink)
    // Tiles in rows of three: one row on the cover screen, two on the taller inner screen.
    if (top.isEmpty()) Text(if (income) "No income yet." else "No spending yet.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 18.dp))
    top.chunked(3).forEach { rowItems -> Row(Modifier.fillMaxWidth().padding(top = if (roomy) 12.dp else 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rowItems.forEach { (c, amt) ->
            Column(Modifier.weight(1f).clip(RoundedCornerShape(14.dp)).background(Kassa.colors.raised).clickable { if (c != null) onOpenCategory(c.id) else onOpen() }.padding(if (roomy) 14.dp else 10.dp)) {
                CategoryBadge(c, size = 26)
                Text(c?.name ?: "Uncategorized", style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
                Text(Money.format(amt), style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1)
            }
        }
        repeat(3 - rowItems.size) { Box(Modifier.weight(1f)) }
    } }
    Box(Modifier.padding(top = 6.dp)) { LinkText(if (income) "Where it came from" else "Where it went", onOpen) }
}

@Composable
private fun UpcomingSection(ledger: Ledger, show: Int?, onOpenCard: (Long) -> Unit, onAll: () -> Unit, modifier: Modifier = Modifier) {
    val items = upcoming(ledger, 30)
    Column(modifier) {
        SectionTop("Upcoming", "Next 30 days", onAll)
        if (items.isEmpty()) Text("Nothing due in the next 30 days.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 8.dp))
        // With no fixed count, as many as fit the space left on the screen.
        else if (show != null) items.take(show).forEach { u -> UpcomingRow(u, ledger, onOpenCard) }
        else dev.krishnagurram.kassa.ui.common.FitColumn(Modifier.weight(1f, fill = false)) { items.forEach { u -> UpcomingRow(u, ledger, onOpenCard) } }
    }
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
    val holdings = ledger.holdings.filter { h -> active.any { it.id == h.accountId } }.sumOf { it.value }
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

/** Expenses from [from] through today, by top category, largest first. */
private fun spendingByCategory(ledger: Ledger, from: java.time.LocalDate, kind: TxKind = TxKind.EXPENSE): List<Pair<dev.krishnagurram.kassa.domain.Category?, Long>> =
    ledger.recent.filter { it.kind == kind && dateOf(it, ledger).let { d -> !d.isBefore(from) && !d.isAfter(ledger.today) } }
        .groupBy { Spending.topOf(it.categoryId, ledger.categoryById) }
        .map { (c, l) -> c to l.sumOf { it.categoryAmount } }.sortedByDescending { it.second }

/**
 * The one page for "this week" or "this month": the total and every category (the same lines Insights uses). Overview's "Where it went" and Insights' "All" open this same page.
 */
@Composable
fun SpendingPage(
    ledger: Ledger,
    month: Boolean,
    income: Boolean,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)?,
    onEdit: (Transaction) -> Unit,
    onOpenCategory: (Long) -> Unit,
    onOpenDay: (java.time.LocalDate) -> Unit,
) {
    val from = if (month) ledger.period.start else ledger.weekStart
    val kind = if (income) TxKind.INCOME else TxKind.EXPENSE
    val entries = ledger.recent.filter { it.kind == kind && dateOf(it, ledger).let { d -> !d.isBefore(from) && !d.isAfter(ledger.today) } }
        .sortedWith(compareByDescending<Transaction> { it.epochMillis }.thenByDescending { it.id })
    val lines = when {
        income -> dev.krishnagurram.kassa.ui.common.incomeLines(ledger, from, ledger.today)
        month -> dev.krishnagurram.kassa.ui.common.monthLines(ledger)
        else -> dev.krishnagurram.kassa.ui.common.weekLines(ledger)
    }
    val total = entries.sumOf { it.categoryAmount }
    val what = if (income) "income" else "spending"
    val title = if (month) ledger.period.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) + " " + what else "This week's $what"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(title, onBack)
        RaisedPanel {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(if (income) "Received" else "Spent", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.weight(1f))
                Text("${from.format(DateTimeFormatter.ofPattern("MMM d"))} to today", style = KassaType.tiny, color = Kassa.colors.ink3)
            }
            BigMoney(total, sizeSp = 34f, color = if (income && total > 0) Kassa.colors.positive else Kassa.colors.ink)
            Text(plural(entries.size, if (income) "entry" else "expense", if (income) "entries" else "expenses"), style = KassaType.small, color = Kassa.colors.ink2)
        }
        SectionHeader("By category")
        dev.krishnagurram.kassa.ui.common.CategoryLinesAll(lines, if (income) "Nothing received yet" else "No spending yet") { onOpenCategory(it.id) }
        Gap(24)
    }
}

private fun dateOf(t: Transaction, ledger: Ledger) = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate()

