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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
fun InsightDetailPage(
    ledger: Ledger, page: InsightPage, contentPadding: PaddingValues, onBack: (() -> Unit)?, onOpenCategory: (Category) -> Unit, onOpenCard: (Long) -> Unit,
    onOpenStore: (String) -> Unit, onEdit: (dev.krishnagurram.kassa.domain.Transaction) -> Unit,
) {
    val p = ledger.period
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTop(page.title, onBack)
        when (page) {
            InsightPage.BIG_WEEK, InsightPage.BIG_MONTH -> {
                val list = bigPurchases(ledger, week = page == InsightPage.BIG_WEEK)
                if (list.isEmpty()) Empty("No purchases yet.")
                Column { list.forEachIndexed { i, t -> dev.krishnagurram.kassa.ui.common.TransactionRow(t, ledger, showDivider = i < list.lastIndex, onClick = onEdit, showDate = true) } }
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
                Column { stores.forEach { st -> RankRow(st.category, st.name, st.total, max, "${st.visits}×") { onOpenStore(st.name) } } }
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
                Column { s.byCategory.forEach { (c, v, n) -> RankRow(c, c.name, v, max, "$n×") { onOpenCategory(c) } } }
            }
            InsightPage.CREEP -> {
                val creep = Patterns.creeping(ledger.recent, ledger.categories, ledger.today, ledger.monthStartDay, ledger.zone)
                if (creep.isEmpty()) Empty("No category has gone up three months in a row.")
                creep.forEach { cr ->
                    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Kassa.colors.raised).clickable { onOpenCategory(cr.category) }.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryBadge(cr.category, size = 26)
                            Text(cr.category.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.weight(1f).padding(start = 8.dp))
                            Text("+" + Money.format(cr.rise, wholeDollars = true), style = KassaType.bodyStrong, color = Kassa.colors.attention)
                            Text(" since ${short(cr.months.first().first)}", style = KassaType.tiny, color = Kassa.colors.ink2)
                        }
                        BarChart(cr.months.map { short(it.first) to it.second }, cr.months.lastIndex, Kassa.colors.attention, height = 110)
                    }
                }
            }
            InsightPage.REPEATING -> {
                val all = ledger.repeats
                val spent = all.filter { it.kind == dev.krishnagurram.kassa.domain.TxKind.EXPENSE }
                Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Figure("A month", Money.format(spent.sumOf { it.perMonth }, wholeDollars = true), null, Kassa.colors.ink2, Modifier.weight(1f))
                    Figure("A year", Money.format(spent.sumOf { it.perYear }, wholeDollars = true), null, Kassa.colors.ink2, Modifier.weight(1f))
                }
                if (all.isEmpty()) Empty("Nothing has repeated on a schedule yet.")
                all.groupBy { it.cadence }.toSortedMap().forEach { (cadence, list) ->
                    Text(cadence.label, style = KassaType.tiny.copy(fontWeight = FontWeight.Bold), color = Kassa.colors.ink2, modifier = Modifier.padding(top = 8.dp))
                    Column { list.forEachIndexed { i, x -> RepeatRow(x, ledger, onOpenStore, last = i == list.lastIndex) } }
                }
            }
            InsightPage.WEEKDAYS -> {
                val avg = Insights.weekdayAverages(ledger.recent, ledger.categories, ledger.today, ledger.zone)
                val top = avg.maxByOrNull { it.second }
                BarChart(avg.map { it.first.getDisplayName(TextStyle.SHORT, Locale.getDefault()) to it.second }, top?.let { avg.indexOf(it) } ?: -1, Kassa.colors.ink, height = 200)
                Text("Avg day \u00b7 last 13 weeks", style = KassaType.tiny, color = Kassa.colors.ink3)
                // Per weekday: what it mostly goes to and how many purchases over the 13 weeks.
                val from = ledger.today.minusWeeks(13)
                val byDay = ledger.recent.filter { it.kind == dev.krishnagurram.kassa.domain.TxKind.EXPENSE }
                    .map { it to java.time.Instant.ofEpochMilli(it.epochMillis).atZone(ledger.zone).toLocalDate() }
                    .filter { (_, d) -> !d.isBefore(from) && d.isBefore(ledger.today) }
                    .groupBy({ it.second.dayOfWeek }, { it.first })
                Column {
                    val sorted = avg.sortedByDescending { it.second }
                    sorted.forEachIndexed { i, (day, v) ->
                        val list = byDay[day].orEmpty()
                        val topCat = list.groupBy { Spending.topOf(it.categoryId, ledger.categoryById) }.maxByOrNull { e -> e.value.sumOf { it.categoryAmount } }?.key
                        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            CategoryBadge(topCat, size = 28)
                            Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                                Text(day.getDisplayName(TextStyle.FULL, Locale.getDefault()), style = KassaType.bodyStrong, color = Kassa.colors.ink)
                                Text(listOfNotNull(topCat?.name?.let { "Mostly $it" }, "${list.size} buys").joinToString(" \u00b7 "), maxLines = 1, overflow = TextOverflow.Ellipsis, style = KassaType.tiny, color = Kassa.colors.ink3)
                            }
                            Text(Money.format(v, wholeDollars = true), style = KassaType.bodyStrong, color = Kassa.colors.ink)
                        }
                        if (i < sorted.lastIndex) HorizontalDivider(color = Kassa.colors.line)
                    }
                }
            }
        }
    }
}

/** One repeat: what and when on the left; the amount and what it adds up to in a year on the right. */
@Composable
private fun RepeatRow(x: dev.krishnagurram.kassa.domain.Repeat, ledger: Ledger, onOpen: (String) -> Unit, last: Boolean) {
    val income = x.kind == dev.krishnagurram.kassa.domain.TxKind.INCOME
    val next = x.next(ledger.today).format(java.time.format.DateTimeFormatter.ofPattern("MMM d"))
    val detail = listOfNotNull(
        "Next $next",
        if (!x.fixed) "${Money.format(x.low, wholeDollars = true)} to ${Money.format(x.high, wholeDollars = true)}" else null,
        if (income) "Income" else null,
    ).joinToString(" · ")
    Row(Modifier.fillMaxWidth().clickable { onOpen(x.name) }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        CategoryBadge(dev.krishnagurram.kassa.domain.Spending.topOf(x.categoryId, ledger.categoryById), size = 30)
        Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(x.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                when {
                    x.change != 0L -> Tag((if (x.change > 0) "+" else "−") + Money.format(Math.abs(x.change)), if (x.change > 0) Kassa.colors.attention else Kassa.colors.positive)
                    !x.fixed -> Tag("varies", Kassa.colors.ink2)
                }
            }
            Text(detail, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text((if (x.fixed) "" else "≈ ") + Money.format(x.typical), style = KassaType.bodyStrong, color = if (income) Kassa.colors.positive else Kassa.colors.ink, maxLines = 1)
            Text("${Money.format(x.perYear, wholeDollars = true)}/yr", style = KassaType.tiny, color = Kassa.colors.ink3, maxLines = 1)
        }
    }
    if (!last) HorizontalDivider(color = Kassa.colors.line)
}

@Composable
private fun Tag(text: String, color: androidx.compose.ui.graphics.Color) = Text(
    text, style = KassaType.tiny.copy(fontWeight = FontWeight.Bold), color = color, maxLines = 1,
    modifier = Modifier.padding(start = 6.dp).clip(RoundedCornerShape(6.dp)).background(color.copy(alpha = 0.14f)).padding(horizontal = 6.dp, vertical = 2.dp),
)

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
private fun RankRow(c: Category?, name: String, value: Long, max: Long, count: String, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
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
        Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
        Text(middle, style = KassaType.small, color = Kassa.colors.ink3, modifier = Modifier.padding(end = 12.dp))
        Text(value, style = KassaType.bodyStrong, color = Kassa.colors.ink)
    }
    if (showDivider) HorizontalDivider(color = Kassa.colors.line)
}

/** One day: what was spent and every entry that day. Opened from a day anywhere in the app (week strip, Activity, spending page). */
@Composable
fun DayPage(
    ledger: Ledger, date: java.time.LocalDate, contentPadding: PaddingValues, onBack: (() -> Unit)?,
    onEdit: (dev.krishnagurram.kassa.domain.Transaction) -> Unit,
    load: (Long, Long) -> kotlinx.coroutines.flow.Flow<List<dev.krishnagurram.kassa.domain.Transaction>>,
) {
    val loaded by androidx.compose.runtime.remember(date) {
        load(date.atStartOfDay(ledger.zone).toInstant().toEpochMilli(), date.plusDays(1).atStartOfDay(ledger.zone).toInstant().toEpochMilli() - 1)
    }.collectAsState(initial = null)
    val list = (loaded ?: ledger.recent.filter { dateOf(it, ledger) == date })
        .sortedWith(compareByDescending<dev.krishnagurram.kassa.domain.Transaction> { it.epochMillis }.thenByDescending { it.id })
    val spent = list.filter { it.kind == dev.krishnagurram.kassa.domain.TxKind.EXPENSE }.sumOf { it.categoryAmount }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(date.format(java.time.format.DateTimeFormatter.ofPattern("EEEE, MMM d", Locale.getDefault())), onBack)
        Text("Spent", style = KassaType.tiny.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2)
        dev.krishnagurram.kassa.ui.today.BigMoney(spent, sizeSp = 34f)
        if (list.isEmpty()) Empty("Nothing recorded this day.")
        Column(Modifier.padding(top = 8.dp)) {
            list.forEachIndexed { i, t -> dev.krishnagurram.kassa.ui.common.TransactionRow(t, ledger, showDivider = i < list.lastIndex, onClick = onEdit) }
        }
    }
}

/** Every entry at one store in the last few months, newest first, with this month's total. */
@Composable
fun InsightStorePage(ledger: Ledger, name: String, contentPadding: PaddingValues, onBack: (() -> Unit)?, onEdit: (dev.krishnagurram.kassa.domain.Transaction) -> Unit) {
    // Notes match the way repeats do: "Mortgage October 2026" counts as Mortgage.
    val key = dev.krishnagurram.kassa.domain.Repeats.key(name)
    val list = ledger.recent.filter { it.kind == dev.krishnagurram.kassa.domain.TxKind.EXPENSE && dev.krishnagurram.kassa.domain.Repeats.key(it.note) == key }.sortedByDescending { it.epochMillis }
    val month = list.filter { dateOf(it, ledger) in ledger.period }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        PageTop(name, onBack)
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Figure("This month", Money.format(month.sumOf { it.categoryAmount }, wholeDollars = true), null, Kassa.colors.ink2, Modifier.weight(1f))
            Figure("Purchases", month.size.toString(), null, Kassa.colors.ink2, Modifier.weight(1f))
        }
        if (list.isEmpty()) Empty("No entries for $name.")
        Column { list.forEachIndexed { i, t -> dev.krishnagurram.kassa.ui.common.TransactionRow(t, ledger, showDivider = i < list.lastIndex, onClick = onEdit, showDate = true) } }
    }
}
