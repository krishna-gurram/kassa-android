package dev.krishnagurram.kassa.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.unit.dp
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType

/** Spending by category, largest first: amount, share, and a bar scaled to the largest category. */
@Composable
fun CategoryBreakdown(rows: List<Pair<Category?, Long>>, onOpen: ((Category) -> Unit)? = null) {
    val total = rows.sumOf { it.second }.coerceAtLeast(1)
    val max = rows.maxOfOrNull { it.second }?.coerceAtLeast(1) ?: 1
    rows.forEach { (c, amt) ->
        val m = if (onOpen != null && c != null) Modifier.clickable { onOpen(c) } else Modifier
        Column(m.padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(c, size = 28)
                Text(c?.name ?: "Uncategorized", style = KassaType.body, color = Kassa.colors.ink, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
                Text(Money.format(amt), style = KassaType.bodyStrong, color = Kassa.colors.ink)
                Text("  ${Math.round(amt * 100.0 / total)}%", style = KassaType.tiny, color = Kassa.colors.ink3)
            }
            Box(Modifier.padding(start = 38.dp, top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp)).background(Kassa.colors.track)) {
                Box(Modifier.fillMaxWidth(amt.toFloat() / max).height(6.dp).clip(RoundedCornerShape(3.dp)).background(c?.let { Color(it.color) } ?: Kassa.colors.ink3))
            }
        }
    }
}
