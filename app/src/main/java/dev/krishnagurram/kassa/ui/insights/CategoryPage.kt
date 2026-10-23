package dev.krishnagurram.kassa.ui.insights

import androidx.compose.ui.text.style.TextOverflow
import dev.krishnagurram.kassa.ui.common.swipeShift
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import dev.krishnagurram.kassa.domain.Period
import java.time.format.TextStyle
import java.util.Locale
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.domain.Budgets
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.PageTop
import dev.krishnagurram.kassa.ui.common.RaisedPanel
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.common.TransactionRow
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.Instant

/**
 * One category, one month at a time. Arrows (or a tap on the six-month bars) move between months: spent against that
 * month's budget, the month before, what it went to by subcategory, and every entry.
 */
@Composable
fun CategoryPage(
    ledger: Ledger, c: Category, contentPadding: PaddingValues, onBack: (() -> Unit)?, onEdit: (Transaction) -> Unit,
    load: (Long, Long) -> kotlinx.coroutines.flow.Flow<List<Transaction>>,
) {
    var back by rememberSaveable(c.id) { mutableIntStateOf(0) }
    val current = ledger.period
    val p = Period.startingIn(current.yearMonth.minusMonths(back.toLong()), ledger.monthStartDay)
    // Six months ending at the one shown, so the bars and the month before are always loaded.
    val first = Period.startingIn(p.yearMonth.minusMonths(5), ledger.monthStartDay)
    val loaded by remember(first.start, current.endInclusive) {
        load(first.start.atStartOfDay(ledger.zone).toInstant().toEpochMilli(), current.endInclusive.plusDays(1).atStartOfDay(ledger.zone).toInstant().toEpochMilli() - 1)
    }.collectAsState(initial = null)
    val txs = loaded ?: ledger.recent
    val ids = ledger.categories.filter { it.id == c.id || it.parentId == c.id }.map { it.id }.toSet()
    fun inPeriod(t: Transaction, q: Period) = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate().let { it in q }
    val kind = if (c.kind == dev.krishnagurram.kassa.domain.CategoryKind.INCOME) TxKind.INCOME else TxKind.EXPENSE
    val mine = txs.filter { it.kind == kind && it.categoryId in ids }
    val entries = mine.filter { inPeriod(it, p) }.sortedByDescending { it.epochMillis }
    val bySub = entries.groupBy { t -> ledger.categoryById[t.categoryId]?.takeIf { it.parentId == c.id }?.name ?: "Other" }
        .mapValues { (_, v) -> v.sumOf { it.categoryAmount } }.entries.sortedByDescending { it.value }
    val spent = entries.sumOf { it.categoryAmount }
    val budget = Budgets.amountFor(c.id, p.yearMonth, ledger.budgets)?.takeIf { it > 0 && kind == TxKind.EXPENSE }
    val months = (5 downTo 0).map { i -> Period.startingIn(p.yearMonth.minusMonths(i.toLong()), ledger.monthStartDay) }
    val totals = months.map { q -> mine.filter { inPeriod(it, q) }.sumOf { it.categoryAmount } }
    val prevTotal = totals[4]
    val monthName = { q: Period -> q.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault()) }

    Column(Modifier.fillMaxSize().swipeShift { d -> if (d < 0) back++ else if (back > 0) back-- }.verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(c.name, onBack)
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            RoundArrow("\u2039", enabled = true) { back++ }
            Text("${monthName(p)} ${p.yearMonth.year}", style = KassaType.bodyStrong, color = Kassa.colors.ink, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
            RoundArrow("\u203a", enabled = back > 0) { back-- }
        }
        val over = budget != null && spent > budget
        Row(verticalAlignment = Alignment.Bottom) {
            dev.krishnagurram.kassa.ui.today.BigMoney(spent, sizeSp = 34f, color = if (over) Kassa.colors.attention else Kassa.colors.ink)
            if (budget != null) Text("  of ${Money.format(budget, wholeDollars = true)} budget", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (budget != null) {
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(Kassa.colors.track)) {
                Box(Modifier.fillMaxWidth((spent.toFloat() / budget).coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(if (over) Kassa.colors.attention else Color(c.color)))
            }
        }
        Row(Modifier.padding(top = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (budget != null) Figure(if (over) "Over budget" else "Left", Money.format(kotlin.math.abs(budget - spent), wholeDollars = true), null, Kassa.colors.ink2, Modifier.weight(1f), valueColor = if (over) Kassa.colors.attention else Kassa.colors.ink)
            Figure(monthName(months[4]), Money.format(prevTotal, wholeDollars = true), null, Kassa.colors.ink2, Modifier.weight(1f))
        }
        // Six months of this category; tap a bar to jump to that month.
        val max = (totals.maxOrNull() ?: 0L).coerceAtLeast(1L)
        Row(Modifier.fillMaxWidth().height(130.dp).padding(top = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            months.forEachIndexed { i, q ->
                val sel = i == 5
                Column(
                    Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable { back += 5 - i },
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(Money.format(totals[i], wholeDollars = true), style = KassaType.tiny.copy(fontSize = 10.sp, fontWeight = FontWeight.SemiBold), color = if (sel) Kassa.colors.ink else Kassa.colors.ink2, maxLines = 1)
                    Box(Modifier.weight(1f).fillMaxWidth().padding(vertical = 4.dp), contentAlignment = Alignment.BottomCenter) {
                        Box(Modifier.fillMaxWidth().fillMaxHeight((totals[i].toFloat() / max).coerceIn(0.02f, 1f)).clip(RoundedCornerShape(6.dp)).background(if (sel) Color(c.color) else Kassa.colors.track))
                    }
                    Text(q.yearMonth.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = if (sel) Kassa.colors.ink else Kassa.colors.ink2)
                }
            }
        }
        if (bySub.size > 1) {
            SectionHeader("By subcategory")
            bySub.forEach { (name, v) ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = KassaType.body, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
                    Text(Money.format(v), style = KassaType.bodyStrong, color = Kassa.colors.ink)
                }
            }
        }
        SectionHeader("Entries", "${entries.size}")
        if (entries.isEmpty()) Text("Nothing in ${c.name} in ${monthName(p)}.", style = KassaType.body, color = Kassa.colors.ink2)
        entries.forEachIndexed { i, t -> TransactionRow(t, ledger, showDivider = i < entries.lastIndex, onClick = onEdit, showDate = true) }
        Gap(24)
    }
}

@Composable
private fun RoundArrow(glyph: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier.size(36.dp).clip(CircleShape).background(Kassa.colors.chip).clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(glyph, style = KassaType.body.copy(fontSize = 20.sp), color = if (enabled) Kassa.colors.ink else Kassa.colors.ink3) }
}
