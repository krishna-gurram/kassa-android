package dev.krishnagurram.kassa.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krishnagurram.kassa.domain.Budgets
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.CategoryKind
import dev.krishnagurram.kassa.domain.Insights
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Patterns
import dev.krishnagurram.kassa.domain.Spending
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.CategoryBadge
import dev.krishnagurram.kassa.ui.common.PageTop
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

private fun short(m: YearMonth) = m.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())

/** The full view behind each Insights tile or "All" link. */
@Composable
fun InsightDetailPage(ledger: Ledger, page: InsightPage, contentPadding: PaddingValues, onBack: (() -> Unit)?, onOpenCategory: (Category) -> Unit, onOpenCard: (Long) -> Unit) {
    val p = ledger.period
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTop(if (page == InsightPage.BUDGETS) "${p.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())} by category" else page.title, onBack)
        when (page) {
            InsightPage.BUDGETS -> {
                val lines = monthLines(ledger)
                val (spent, none) = lines.partition { it.spent > 0 }
                val (everyday, bills) = spent.partition { it.category.countsTowardDaily }
                Column {
                    CategoryLines(everyday, onOpenCategory)
                    if (bills.isNotEmpty()) { Head("Bills"); CategoryLines(bills, onOpenCategory) }
                    if (none.isNotEmpty()) { Head("No spending yet"); CategoryLines(none, onOpenCategory) }
                }
            }
            InsightPage.WEEK_CATEGORIES -> {
                val (spent, none) = weekLines(ledger).partition { it.spent > 0 }
                Column {
                    CategoryLines(spent, onOpenCategory)
                    if (none.isNotEmpty()) { Head("No spending yet"); CategoryLines(none, onOpenCategory) }
                }
            }
            InsightPage.DUES -> {
                val dues = duesThisWeek(ledger)
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Figure("Total", Money.format(dues.sumOf { it.snapshot.payable }), null, Kassa.colors.ink2, Modifier.weight(1f))
                    Figure("Cards", dues.size.toString(), null, Kassa.colors.ink2, Modifier.weight(1f))
                }
                if (dues.isEmpty()) Empty("No card payments due before Sunday.")
                Column {
                    dues.forEachIndexed { i, d ->
                        Row(Modifier.fillMaxWidth().clickable { onOpenCard(d.account.id) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(d.snapshot.due.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault()).uppercase(), style = KassaType.tiny.copy(fontWeight = FontWeight.Bold), color = Kassa.colors.ink2, modifier = Modifier.width(44.dp))
                            Column(Modifier.weight(1f)) {
                                Text(d.account.name + (d.account.last4?.takeIf { it.isNotBlank() }?.let { "  \u00b7\u00b7$it" } ?: ""), style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("Balance payable", style = KassaType.tiny, color = Kassa.colors.ink3)
                            }
                            Text(Money.format(d.snapshot.payable), style = KassaType.bodyStrong, color = Kassa.colors.ink)
                        }
                        if (i < dues.lastIndex) HorizontalDivider(color = Kassa.colors.line)
                    }
                }
            }
            InsightPage.SAVED -> {
                val months = Patterns.saved(ledger.recent, ledger.today, ledger.monthStartDay, ledger.zone)
                val now = months.last()
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Figure("Income", Money.format(now.income, wholeDollars = true), null, Kassa.colors.ink2, Modifier.weight(1f))
                    Figure("Spent", Money.format(now.spent, wholeDollars = true), null, Kassa.colors.ink2, Modifier.weight(1f))
                    Figure("Saved so far", Money.format(now.saved, wholeDollars = true), null, Kassa.colors.ink2, Modifier.weight(1f), valueColor = if (now.saved >= 0) Kassa.colors.positive else Kassa.colors.attention)
                }
                BarChart(months.map { short(it.month) to maxOf(0L, it.saved) }, months.lastIndex, Kassa.colors.positive, labels = months.map { m -> m.rate?.let { "$it%" } ?: "" })
                Column {
                    months.reversed().forEachIndexed { i, m ->
                        ListRow(short(m.month), m.rate?.let { "$it%" } ?: "", Money.format(m.saved, wholeDollars = true), showDivider = i < months.lastIndex)
                    }
                }
            }
            InsightPage.STORES -> {
                val stores = Patterns.stores(ledger.recent, ledger.categoryById, p.start, ledger.today, ledger.zone).take(15)
                if (stores.isEmpty()) Empty("No entries with a store name this month.")
                val max = stores.firstOrNull()?.total ?: 1L
                Column { stores.forEach { RankRow(it.category, it.name, it.total, max, "${it.visits}×") } }
            }
            InsightPage.SMALL -> {
                val s = Patterns.smallBuys(ledger.recent, ledger.categoryById, p.start, ledger.today, ledger.zone)
                val months = Patterns.smallBuysByMonth(ledger.recent, ledger.categoryById, ledger.today, ledger.monthStartDay, ledger.zone)
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Figure("This month", Money.format(s.total, wholeDollars = true), null, Kassa.colors.ink2, Modifier.weight(1f))
                    Figure("Buys", s.count.toString(), null, Kassa.colors.ink2, Modifier.weight(1f))
                    Figure("Each under", Money.format(Patterns.SMALL_LIMIT, wholeDollars = true), null, Kassa.colors.ink2, Modifier.weight(1f))
                }
                BarChart(months.map { short(it.first) to it.second }, months.lastIndex, Kassa.colors.ink)
                val max = s.byCategory.firstOrNull()?.second ?: 1L
                Column { s.byCategory.forEach { (c, v, n) -> RankRow(c, c.name, v, max, "$n×") } }
            }
            InsightPage.CREEP -> {
                val creep = Patterns.creeping(ledger.recent, ledger.categories, ledger.today, ledger.monthStartDay, ledger.zone)
                if (creep.isEmpty()) Empty("No category has gone up three months in a row.")
                creep.forEach { cr ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Kassa.colors.raised).padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryBadge(cr.category, size = 26)
                            Text(cr.category.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.weight(1f).padding(start = 8.dp))
                            Text("+" + Money.format(cr.rise, wholeDollars = true), style = KassaType.bodyStrong, color = Kassa.colors.attention)
                            Text(" since ${short(cr.months.first().first)}", style = KassaType.tiny, color = Kassa.colors.ink2)
                        }
                        BarChart(cr.months.map { short(it.first) to it.second }, cr.months.lastIndex, Kassa.colors.attention, height = 110)
                    }
                }
            }
            InsightPage.REPEATING -> {
                val r = Insights.repeating(ledger.recent, ledger.categoryById, ledger.zone).sortedWith(compareByDescending<Insights.RepeatingCharge> { it.change > 0 }.thenByDescending { it.amount })
                val monthly = r.sumOf { it.amount }
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Figure("Monthly", Money.format(monthly), null, Kassa.colors.ink2, Modifier.weight(1f))
                    Figure("Yearly", Money.format(monthly * 12, wholeDollars = true), null, Kassa.colors.ink2, Modifier.weight(1f))
                }
                if (r.isEmpty()) Empty("No charges have come back monthly for three months yet.")
                Column {
                    r.forEachIndexed { i, x ->
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CategoryBadge(x.category, size = 30)
                            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(x.note, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                                    if (x.change > 0) Text(
                                        "+" + Money.format(x.change), style = KassaType.tiny.copy(fontWeight = FontWeight.Bold), color = Kassa.colors.attention,
                                        modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(Kassa.colors.attention.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
                                    )
                                }
                                Text("${Money.format(x.amount * 12, wholeDollars = true)}/yr", style = KassaType.tiny, color = Kassa.colors.ink3)
                            }
                            Text(Money.format(x.amount), style = KassaType.bodyStrong, color = Kassa.colors.ink)
                        }
                        if (i < r.lastIndex) HorizontalDivider(color = Kassa.colors.line)
                    }
                }
            }
            InsightPage.WEEKDAYS -> {
                val avg = Insights.weekdayAverages(ledger.recent, ledger.categories, ledger.today, ledger.zone)
                val top = avg.maxByOrNull { it.second }
                BarChart(avg.map { it.first.getDisplayName(TextStyle.SHORT, Locale.getDefault()) to it.second }, top?.let { avg.indexOf(it) } ?: -1, Kassa.colors.ink)
                Text("Avg day · 13 weeks", style = KassaType.tiny, color = Kassa.colors.ink3)
            }
        }
    }
}

@Composable
private fun Empty(text: String) = Text(text, style = KassaType.body, color = Kassa.colors.ink2)

@Composable
private fun BarChart(rows: List<Pair<String, Long>>, highlight: Int, color: Color, height: Int = 160, labels: List<String>? = null) {
    val max = (rows.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
    Row(Modifier.fillMaxWidth().height(height.dp).padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rows.forEachIndexed { i, (label, v) ->
            Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(labels?.getOrNull(i) ?: Money.format(v, wholeDollars = true), style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink, maxLines = 1)
                Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.BottomCenter) {
                    Box(Modifier.fillMaxWidth().fillMaxHeight((v.toFloat() / max).coerceIn(0.02f, 1f)).clip(RoundedCornerShape(6.dp)).background(if (i == highlight) color else Kassa.colors.track))
                }
                Text(label, style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2, textAlign = TextAlign.Center)
            }
        }
    }
}

@Composable
private fun RankRow(c: Category?, name: String, value: Long, max: Long, count: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryBadge(c, size = 28)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Text(name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Box(Modifier.padding(top = 6.dp).fillMaxWidth().height(5.dp).clip(RoundedCornerShape(50)).background(Kassa.colors.track)) {
                Box(Modifier.fillMaxWidth((value.toFloat() / max.coerceAtLeast(1)).coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(c?.let { Color(it.color) } ?: Kassa.colors.ink))
            }
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(Money.format(value, wholeDollars = true), style = KassaType.bodyStrong, color = Kassa.colors.ink)
            Text(count, style = KassaType.tiny, color = Kassa.colors.ink3)
        }
    }
}

@Composable
private fun ListRow(title: String, middle: String, value: String, showDivider: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
        Text(middle, style = KassaType.small, color = Kassa.colors.ink3, modifier = Modifier.padding(end = 12.dp))
        Text(value, style = KassaType.bodyStrong, color = Kassa.colors.ink)
    }
    if (showDivider) HorizontalDivider(color = Kassa.colors.line)
}

/** Everything spent on one day, from a tap on the week strip. */
@Composable
fun InsightDayPage(ledger: Ledger, date: java.time.LocalDate, contentPadding: PaddingValues, onBack: (() -> Unit)?, onEdit: (dev.krishnagurram.kassa.domain.Transaction) -> Unit) {
    val list = ledger.recent.filter { java.time.Instant.ofEpochMilli(it.epochMillis).atZone(ledger.zone).toLocalDate() == date && it.kind == dev.krishnagurram.kassa.domain.TxKind.EXPENSE }
        .sortedByDescending { it.epochMillis }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(date.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault()), onBack)
        Text(date.format(java.time.format.DateTimeFormatter.ofPattern("EEE, MMM d", Locale.getDefault())), style = KassaType.tiny.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2)
        dev.krishnagurram.kassa.ui.today.BigMoney(list.sumOf { it.categoryAmount }, sizeSp = 34f)
        if (list.isEmpty()) Empty("Nothing spent this day.")
        Column(Modifier.padding(top = 8.dp)) {
            list.forEachIndexed { i, t -> dev.krishnagurram.kassa.ui.common.TransactionRow(t, ledger, showDivider = i < list.lastIndex, onClick = onEdit) }
        }
    }
}
