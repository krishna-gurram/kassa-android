package dev.krishnagurram.kassa.ui.insights

import androidx.compose.foundation.background
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

/** A category this month: spent against its budget, last month, what it went to by subcategory, and every entry. */
@Composable
fun CategoryPage(ledger: Ledger, c: Category, contentPadding: PaddingValues, onBack: (() -> Unit)?, onEdit: (Transaction) -> Unit) {
    val p = ledger.period
    val ids = ledger.categories.filter { it.id == c.id || it.parentId == c.id }.map { it.id }.toSet()
    val entries = ledger.recent.filter { t ->
        t.kind == TxKind.EXPENSE && t.categoryId in ids && Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate().let { it in p }
    }.sortedByDescending { it.epochMillis }
    val bySub = entries.groupBy { t -> ledger.categoryById[t.categoryId]?.takeIf { it.parentId == c.id }?.name ?: "Other" }
        .mapValues { (_, v) -> v.sumOf { it.categoryAmount } }.entries.sortedByDescending { it.value }
    val spent = entries.sumOf { it.categoryAmount }
    val budget = Budgets.amountFor(c.id, p.yearMonth, ledger.budgets)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop(c.name, onBack)
        val prev = dev.krishnagurram.kassa.domain.Period.startingIn(p.yearMonth.minusMonths(1), ledger.monthStartDay)
        val lastMonth = dev.krishnagurram.kassa.domain.Spending.byTopCategory(ledger.recent, ledger.categoryById, prev.start, prev.endInclusive, ledger.zone)[c.id] ?: 0L
        val over = budget != null && budget > 0 && spent > budget
        Row(verticalAlignment = Alignment.Bottom) {
            dev.krishnagurram.kassa.ui.today.BigMoney(spent, sizeSp = 34f, color = if (over) Kassa.colors.attention else Kassa.colors.ink)
            if (budget != null && budget > 0) Text("  of ${Money.format(budget, wholeDollars = true)} budget", style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (budget != null && budget > 0) {
            Box(Modifier.padding(top = 10.dp).fillMaxWidth().height(10.dp).clip(RoundedCornerShape(50)).background(Kassa.colors.track)) {
                Box(Modifier.fillMaxWidth((spent.toFloat() / budget).coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(if (over) Kassa.colors.attention else Color(c.color)))
            }
        }
        Row(Modifier.padding(top = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (budget != null && budget > 0) Figure(if (over) "Over budget" else "Left", Money.format(kotlin.math.abs(budget - spent), wholeDollars = true), null, Kassa.colors.ink2, Modifier.weight(1f), valueColor = if (over) Kassa.colors.attention else Kassa.colors.ink)
            Figure(prev.yearMonth.month.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.getDefault()), Money.format(lastMonth, wholeDollars = true), null, Kassa.colors.ink2, Modifier.weight(1f))
        }
        if (bySub.size > 1) {
            SectionHeader("By subcategory")
            bySub.forEach { (name, v) ->
                Row(Modifier.padding(vertical = 6.dp)) {
                    Text(name, style = KassaType.body, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
                    Text(Money.format(v), style = KassaType.bodyStrong, color = Kassa.colors.ink)
                }
            }
        }
        SectionHeader("Entries", "${entries.size}")
        if (entries.isEmpty()) Text("Nothing in ${c.name} this month.", style = KassaType.body, color = Kassa.colors.ink2)
        entries.forEachIndexed { i, t -> TransactionRow(t, ledger, showDivider = i < entries.lastIndex, onClick = onEdit) }
        Gap(24)
    }
}
