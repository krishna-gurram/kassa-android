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
import dev.krishnagurram.kassa.ui.common.CategoryBadge
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
    onSettings: () -> Unit,
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
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text(ledger.today.format(DateTimeFormatter.ofPattern("EEEE, MMMM d")), style = KassaType.small, color = Kassa.colors.ink2)
                    Text("Today", style = KassaType.title, color = Kassa.colors.ink)
                }
                Text("Settings", style = KassaType.bodyStrong, color = Kassa.colors.ink2, modifier = Modifier.clickable(onClick = onSettings).padding(8.dp))
            }
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

/** Spent this week against a usual week by the same weekday, then month to date. No bar: this isn't a goal to fill. */
@Composable
private fun SpendingPanel(ledger: Ledger, onWeek: () -> Unit) {
    val w = weekPace(ledger)
    val p = ledger.period
    val monthToDate = ledger.recent.filter { it.kind == TxKind.EXPENSE && dateOf(it, ledger).let { d -> !d.isBefore(p.start) && !d.isAfter(ledger.today) } }.sumOf { it.categoryAmount }
    val dayName = ledger.today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val firstName = w.start.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    val todayName = ledger.today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())
    RaisedPanel(Modifier.clickable(onClick = onWeek)) {
        Text("Spent this week", style = KassaType.tiny, color = Kassa.colors.ink2)
        Text(Money.format(w.spent), style = KassaType.hero.copy(fontSize = KassaType.hero.fontSize * 0.8f), color = Kassa.colors.ink)
        val range = if (w.start == ledger.today) "Today so far." else "$firstName to $todayName."
        val usual = w.usualByNow
        val line = buildAnnotatedString {
            append("$range ")
            if (usual == null) {
                append("A comparison with your usual week appears after a full week of entries.")
            } else {
                val diff = w.spent - usual
                append("By $dayName you usually spend ${Money.format(usual, wholeDollars = true)}, so you're ")
                withStyle(SpanStyle(color = Kassa.colors.ink, fontWeight = FontWeight.SemiBold)) {
                    append(
                        when {
                            usual > 0 && Math.abs(diff) * 20 < usual -> "about on par"
                            diff > 0 -> "${Money.format(diff, wholeDollars = true)} over"
                            else -> "${Money.format(-diff, wholeDollars = true)} under"
                        }
                    )
                }
                append(".")
                w.usualWeek?.let { append(" A usual week ends near ${Money.format(it, wholeDollars = true)}.") }
            }
        }
        Text(line, style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 4.dp))
        Text(
            buildAnnotatedString { withStyle(SpanStyle(textDecoration = TextDecoration.Underline)) { append("See where it went") } },
            style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.padding(top = 10.dp),
        )
        HorizontalDivider(color = Kassa.colors.line, modifier = Modifier.padding(vertical = 14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Month to date", style = KassaType.tiny, color = Kassa.colors.ink2)
                Text(
                    "${p.start.format(DateTimeFormatter.ofPattern("MMM d"))} to today, day ${ChronoUnit.DAYS.between(p.start, ledger.today) + 1} of ${ChronoUnit.DAYS.between(p.start, p.endInclusive) + 1}",
                    style = KassaType.tiny, color = Kassa.colors.ink3,
                )
            }
            Text(Money.format(monthToDate), style = KassaType.stat, color = Kassa.colors.ink)
        }
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
            subtitle = "Recurring, ${inDays(days)}. Asks before it's added.",
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
    val max = byTop.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
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
        byTop.forEach { (c, amt) ->
            Column(Modifier.padding(vertical = 8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryBadge(c, size = 28)
                    Text(c?.name ?: "Uncategorized", style = KassaType.body, color = Kassa.colors.ink, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                    Text(Money.format(amt), style = KassaType.bodyStrong, color = Kassa.colors.ink)
                    Text("  ${Math.round(amt * 100.0 / total.coerceAtLeast(1))}%", style = KassaType.tiny, color = Kassa.colors.ink3)
                }
                Box(Modifier.padding(start = 38.dp, top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Kassa.colors.track)) {
                    Box(Modifier.fillMaxWidth(amt.toFloat() / max).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c?.let { Color(it.color) } ?: Kassa.colors.ink3))
                }
            }
        }
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
