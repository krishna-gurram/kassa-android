package app.kassa.money.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kassa.money.domain.Budgets
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.Money
import app.kassa.money.domain.Period
import app.kassa.money.domain.Spending
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.Instant
import java.time.LocalDate

/*
 * The one way the app lists categories with amounts: Overview's spending page, Insights, Activity's month pane.
 * Change it here and it changes everywhere.
 */

/** One line: what was spent (or received), and what it's measured against (a budget, or last week) when there is one. */
data class CatLine(val category: Category, val spent: Long, val ref: Long?, val refLabel: String?)

/** Top categories in use. Archived ones are left out unless they have entries in the period being shown. */
private fun top(ledger: Ledger) = ledger.categories.filter { it.parentId == null }

private fun List<CatLine>.dropIdleArchived() = filter { !it.category.hidden || it.spent > 0 }

/** Every visible expense category in a budget month, largest first, against that month's budget. [txs] defaults to the loaded months. */
fun monthLines(ledger: Ledger, period: Period = ledger.period, txs: List<Transaction> = ledger.recent): List<CatLine> {
    val to = if (ledger.today in period) ledger.today else period.endInclusive
    val spent = Spending.byTopCategory(txs, ledger.categoryById, period.start, to, ledger.zone)
    return top(ledger).filter { it.kind == CategoryKind.EXPENSE }
        .map { c ->
            val b = Budgets.amountFor(c.id, period.yearMonth, ledger.budgets)?.takeIf { it > 0 }
            CatLine(c, spent[c.id] ?: 0L, b, b?.let { "of ${Money.format(it, wholeDollars = true)}" })
        }
        .dropIdleArchived()
        .sortedWith(compareByDescending<CatLine> { it.spent }.thenBy { it.category.sortOrder })
}

/**
 * Every visible expense category in the week starting [start], largest first, against the week before: up to the
 * same day for this week, the whole week for a past one. [txs] must cover both weeks.
 */
fun weekLines(ledger: Ledger, start: LocalDate = ledger.weekStart, txs: List<Transaction> = ledger.recent): List<CatLine> {
    val end = minOf(start.plusDays(6), ledger.today)
    val (from, to) = app.kassa.money.domain.Patterns.samePointBefore(start, end, start.minusWeeks(1))
    val now = Spending.byTopCategory(txs, ledger.categoryById, start, end, ledger.zone)
    val before = Spending.byTopCategory(txs, ledger.categoryById, from, to, ledger.zone)
    return top(ledger).filter { it.kind == CategoryKind.EXPENSE }
        .map { c -> val b = before[c.id] ?: 0L; CatLine(c, now[c.id] ?: 0L, b.takeIf { it > 0 }, "last wk ${Money.format(b, wholeDollars = true)}") }
        .dropIdleArchived()
        .sortedWith(compareByDescending<CatLine> { it.spent }.thenBy { it.category.sortOrder })
}

/** Every visible income category between two dates, largest first. Income has nothing to measure against. */
fun incomeLines(ledger: Ledger, from: LocalDate, to: LocalDate, txs: List<Transaction> = ledger.recent): List<CatLine> {
    val got = HashMap<Long, Long>()
    for (t in txs) {
        if (t.kind != TxKind.INCOME) continue
        val d = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate()
        if (d.isBefore(from) || d.isAfter(to)) continue
        val c = Spending.topOf(t.categoryId, ledger.categoryById) ?: continue
        got[c.id] = (got[c.id] ?: 0) + t.amount
    }
    return top(ledger).filter { it.kind == CategoryKind.INCOME }
        .map { CatLine(it, got[it.id] ?: 0L, null, null) }
        .dropIdleArchived()
        .sortedWith(compareByDescending<CatLine> { it.spent }.thenBy { it.category.sortOrder })
}

/** Lines with one rule: the bar is how much of the reference is used, orange past it. Without one, it's relative to the largest. */
@Composable
fun CategoryLines(lines: List<CatLine>, onOpen: (Category) -> Unit) {
    val max = (lines.maxOfOrNull { it.spent } ?: 0L).coerceAtLeast(1L)
    lines.forEach { l -> CategoryBar(l.category, l.spent, l.ref ?: max, l.refLabel, over = l.ref != null && l.spent > l.ref) { onOpen(l.category) } }
}

/** Lines with spending first, then a "No spending yet" group, for the full lists. */
@Composable
fun CategoryLinesAll(lines: List<CatLine>, emptyTitle: String, onOpen: (Category) -> Unit) {
    val (some, none) = lines.partition { it.spent > 0 }
    CategoryLines(some, onOpen)
    if (none.isNotEmpty()) {
        SectionHeader(emptyTitle)
        CategoryLines(none, onOpen)
    }
}

/** One category: name and amount, then a bar of how much of [ref] is used. Past it, the bar and amount turn orange. */
@Composable
fun CategoryBar(c: Category, spent: Long, ref: Long, refLabel: String?, over: Boolean, onClick: () -> Unit) {
    val color = if (over) figureColor(Kassa.colors.attention) else Color(c.color)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(c, size = 26)
            Text(c.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
            Text(Money.format(spent, wholeDollars = true), style = KassaType.bodyStrong, color = figureColor(if (over) Kassa.colors.attention else Kassa.colors.ink))
            if (refLabel != null) Text(" $refLabel", style = KassaType.tiny, color = Kassa.colors.ink2)
        }
        Box(Modifier.padding(start = 34.dp, top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Kassa.colors.track)) {
            Box(Modifier.fillMaxWidth((spent.toFloat() / ref.coerceAtLeast(1)).coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
        }
    }
}
