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
import dev.krishnagurram.kassa.ui.common.GlyphBadge
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

/**
 * What opening the app answers: what you've spent this week and this month, which cards to pay and when,
 * and what's in cash and bank accounts. Then anything waiting for review, bills coming up and recent entries.
 */
@Composable
fun TodayScreen(
    ledger: Ledger,
    contentPadding: PaddingValues,
    onEdit: (Transaction) -> Unit,
    onOpenCard: (Long) -> Unit,
    onOpenAccount: (Long) -> Unit,
    onWeek: () -> Unit,
    onSeeAll: () -> Unit,
    needs: NeedsYouActions,
) {
    val recent = ledger.recent.sortedWith(compareByDescending<Transaction> { it.epochMillis }.thenByDescending { it.id }).take(5)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val spending: @Composable () -> Unit = { SpendingPanel(ledger, onWeek) }
        val pending: @Composable () -> Unit = { NeedsYou(ledger, needs) }
        val cards: @Composable () -> Unit = { CardsDue(ledger, onOpenCard) }
        val cash: @Composable () -> Unit = { CashAndBank(ledger, onOpenAccount) }
        val bills: @Composable () -> Unit = { UpcomingBills(ledger) }
        val recentBlock: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                SectionHeader("Recent", modifier = Modifier.weight(1f))
                Text("See all", style = KassaType.bodyStrong, color = Kassa.colors.ink2, modifier = Modifier.clickable(onClick = onSeeAll).padding(bottom = 4.dp, start = 8.dp))
            }
            if (recent.isEmpty()) Text("Nothing logged yet. Tap + to add your first entry.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 16.dp))
            recent.forEachIndexed { i, t -> TransactionRow(t, ledger, showDivider = i < recent.lastIndex, onClick = onEdit) }
        }

        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)) {
            Text(ledger.today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), style = KassaType.small, color = Kassa.colors.ink2)
            Text("Today", style = KassaType.title, color = Kassa.colors.ink)
            Gap(16)
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
                    Column(Modifier.weight(1f)) { spending(); pending(); recentBlock() }
                    Column(Modifier.weight(1f)) { cards(); cash(); bills() }
                }
            } else {
                Column(Modifier.widthIn(max = 640.dp)) { spending(); pending(); cards(); cash(); bills(); recentBlock() }
            }
            Gap(8)
        }
    }
}

/** Spent this week next to a usual week by the same weekday, then month to date. Figures only, no bar. */
@Composable
private fun SpendingPanel(ledger: Ledger, onWeek: () -> Unit) {
    val w = weekPace(ledger)
    val p = ledger.period
    val monthToDate = ledger.recent.filter { it.kind == TxKind.EXPENSE && dateOf(it, ledger).let { d -> !d.isBefore(p.start) && !d.isAfter(ledger.today) } }.sumOf { it.categoryAmount }
    val firstName = w.start.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val todayName = ledger.today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    RaisedPanel(Modifier.clickable(onClick = onWeek)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("Spent this week", style = KassaType.tiny, color = Kassa.colors.ink2, modifier = Modifier.weight(1f))
            Text(if (w.start == ledger.today) todayName else "$firstName to $todayName", style = KassaType.tiny, color = Kassa.colors.ink3)
        }
        Text(Money.format(w.spent), style = KassaType.hero.copy(fontSize = KassaType.hero.fontSize * 0.8f), color = Kassa.colors.ink)
        val usual = w.usualByNow
        if (usual != null) {
            val diff = w.spent - usual
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Figure("Usual by $todayName", Money.format(usual, wholeDollars = true), Kassa.colors.ink, Modifier.weight(1f))
                Figure(
                    "Difference", Money.format(diff, wholeDollars = true, showPlus = true),
                    if (diff > 0) Kassa.colors.attention else Kassa.colors.positive, Modifier.weight(1f),
                )
                Figure("Usual week", Money.format(w.usualWeek ?: 0, wholeDollars = true), Kassa.colors.ink, Modifier.weight(1f))
            }
        }
        Text(
            buildAnnotatedString { withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("See where it went") } },
            style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.padding(top = 12.dp),
        )
        HorizontalDivider(color = Kassa.colors.line, modifier = Modifier.padding(vertical = 14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Month to date", style = KassaType.tiny, color = Kassa.colors.ink2)
                Text(
                    "Day ${ChronoUnit.DAYS.between(p.start, ledger.today) + 1} of ${ChronoUnit.DAYS.between(p.start, p.endInclusive) + 1}",
                    style = KassaType.tiny, color = Kassa.colors.ink3,
                )
            }
            Text(Money.format(monthToDate), style = KassaType.stat, color = Kassa.colors.ink)
        }
    }
}

@Composable
private fun Figure(label: String, value: String, color: Color, modifier: Modifier) {
    Column(modifier) {
        Text(label, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1)
        Text(value, style = KassaType.bodyStrong, color = color, maxLines = 1)
    }
}

@Composable
private fun CardsDue(ledger: Ledger, onOpenCard: (Long) -> Unit) {
    val due = cardsByDueDate(ledger).filter { it.snapshot.payable > 0 }
    if (ledger.cards().isEmpty()) return
    SectionHeader("Card payments due", if (due.isEmpty()) null else "${Money.format(due.sumOf { it.snapshot.payable })} payable")
    if (due.isEmpty()) Text("No balance payable on any card.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 8.dp))
    due.forEachIndexed { i, c -> CardRow(c, ledger.today, showDivider = i < due.lastIndex) { onOpenCard(c.account.id) } }
}

@Composable
private fun CashAndBank(ledger: Ledger, onOpenAccount: (Long) -> Unit) {
    val accounts = ledger.accounts.accounts.filter { !it.archived && it.type in CASH_TYPES }
    if (accounts.isEmpty()) return
    val balances = ledger.accounts.balances
    SectionHeader("Cash and bank", Money.format(accounts.sumOf { balances[it.id] ?: 0 }))
    accounts.forEachIndexed { i, a ->
        EntryRow(
            badge = { GlyphBadge(a.name.take(1).uppercase()) },
            title = a.name, subtitle = a.type.label,
            amount = Money.format(balances[a.id] ?: 0),
            showDivider = i < accounts.lastIndex,
            modifier = Modifier.clickable { onOpenAccount(a.id) },
        )
    }
}

@Composable
private fun UpcomingBills(ledger: Ledger) {
    val bills = ledger.assist.recurring.mapNotNull { r -> dev.krishnagurram.kassa.domain.RecurringRules.upcoming(r, ledger.today, 7)?.let { r to it } }
    if (bills.isEmpty()) return
    SectionHeader("Upcoming bills", "Next 7 days")
    bills.forEachIndexed { i, (r, d) ->
        val est = dev.krishnagurram.kassa.domain.RecurringRules.estimate(r, ledger.recent, d, ledger.zone)
        val days = ChronoUnit.DAYS.between(ledger.today, d)
        EntryRow(
            badge = { DateBlock(d, soon = days <= 2) },
            title = r.name,
            subtitle = "Recurring, ${inDays(days)}",
            amount = est?.let { Money.format(it) } ?: "",
            note = if (r.fixedAmount == null && est != null) "estimate" else null,
            showDivider = i < bills.lastIndex,
        )
    }
}

/** This week's spending by category, then every entry by day. */
@Composable
fun WeekPage(ledger: Ledger, contentPadding: PaddingValues, onBack: (() -> Unit)?, onEdit: (Transaction) -> Unit) {
    val w = weekPace(ledger)
    val entries = ledger.recent.filter { it.kind == TxKind.EXPENSE && dateOf(it, ledger).let { d -> !d.isBefore(w.start) && !d.isAfter(ledger.today) } }
        .sortedWith(compareByDescending<Transaction> { it.epochMillis }.thenByDescending { it.id })
    val byTop = entries.groupBy { Spending.topOf(it.categoryId, ledger.categoryById) }
        .map { (c, l) -> c to l.sumOf { it.categoryAmount } }.sortedByDescending { it.second }
    val total = entries.sumOf { it.categoryAmount }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop("Where it went", onBack)
        Column(Modifier.padding(horizontal = 4.dp)) {
            Text("${w.start.format(DateTimeFormatter.ofPattern("EEE, MMM d"))} to today", style = KassaType.small, color = Kassa.colors.ink2)
            Text(Money.format(total), style = KassaType.hero.copy(fontSize = KassaType.hero.fontSize * 0.75f), color = Kassa.colors.ink)
            Text(plural(entries.size, "expense"), style = KassaType.small, color = Kassa.colors.ink2)
        }
        if (entries.isEmpty()) {
            Gap(16)
            Text("No spending this week yet.", style = KassaType.body, color = Kassa.colors.ink2)
            return@Column
        }
        SectionHeader("By category", "Share of the week")
        CategoryBreakdown(byTop)
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
