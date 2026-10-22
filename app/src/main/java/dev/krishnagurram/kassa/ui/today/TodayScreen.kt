package dev.krishnagurram.kassa.ui.today

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.TodayPlan
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.EntryRow
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.common.TransactionRow
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

@Composable
fun TodayScreen(
    ledger: Ledger,
    contentPadding: PaddingValues,
    onEdit: (Transaction) -> Unit,
    onOpenCard: (Long) -> Unit,
    onSeeAll: () -> Unit,
    onSettings: () -> Unit,
) {
    val plan = TodayPlan.compute(ledger.recent, ledger.categories, ledger.budgets, ledger.today, ledger.monthStartDay, ledger.zone)
    val dueSoon = ledger.cards()
        .filter { it.snapshot.outstanding > 0 && it.snapshot.daysUntilDue(ledger.today) in 0..7 }
        .sortedBy { it.snapshot.due }
    val recent = ledger.recent.sortedWith(compareByDescending<Transaction> { it.epochMillis }.thenByDescending { it.id }).take(6)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val hero: @Composable () -> Unit = { Hero(plan, ledger) }
        val due: @Composable () -> Unit = {
            if (dueSoon.isNotEmpty()) {
                SectionHeader("Coming up", "Next 7 days")
                dueSoon.forEachIndexed { i, c ->
                    val days = c.snapshot.daysUntilDue(ledger.today)
                    EntryRow(
                        badge = { DateBlock(c.snapshot.due, soon = days <= 2) },
                        title = c.account.name,
                        subtitle = "Card payment, due ${inDays(days)}",
                        amount = Money.format(c.snapshot.outstanding),
                        showDivider = i < dueSoon.lastIndex,
                        modifier = Modifier.clickable { onOpenCard(c.account.id) },
                    )
                }
            }
        }
        val recentBlock: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
                SectionHeader("Recent", modifier = Modifier.weight(1f))
                Text("See all", style = KassaType.bodyStrong, color = Kassa.colors.ink2, modifier = Modifier.clickable(onClick = onSeeAll).padding(bottom = 4.dp))
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
            Gap(18)
            if (wide) {
                Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    Column(Modifier.weight(1f)) { hero(); due() }
                    Column(Modifier.weight(1f)) { recentBlock() }
                }
            } else {
                Column(Modifier.widthIn(max = 640.dp)) { hero(); due(); recentBlock() }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(r: TodayPlan.Result, ledger: Ledger) {
    val p = r.plan
    if (p.budget <= 0) {
        Text("Set budgets to see today's number", style = KassaType.section, color = Kassa.colors.ink)
        Text(
            "Kassa spreads what's left of your monthly budget over the days left. Add budgets to everyday categories in Settings.",
            style = KassaType.body, color = Kassa.colors.ink2,
        )
        return
    }
    val safe = p.safeToday
    val shown by animateIntAsState((Math.abs(safe) / 100).toInt(), animationSpec = tween(550), label = "hero")
    Text(if (safe >= 0) "You can spend" else "Over today’s share by", style = KassaType.body, color = Kassa.colors.ink2)
    Text(
        "$" + java.text.NumberFormat.getIntegerInstance().format(shown),
        style = KassaType.hero, color = if (safe >= 0) Kassa.colors.ink else Kassa.colors.attention,
        modifier = Modifier.padding(vertical = 2.dp),
    )
    Text(
        if (safe >= 0) "today and stay on plan through ${r.period.endInclusive.format(DateTimeFormatter.ofPattern("MMM d"))}."
        else "The rest of the month gets a little tighter to make up for it.",
        style = KassaType.body, color = Kassa.colors.ink2,
    )
    Gap(20)
    Ribbon(r)
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text("${Money.format(p.spent, wholeDollars = true)} of ${Money.format(p.budget, wholeDollars = true)} budget", style = KassaType.small, color = Kassa.colors.ink)
        val diff = p.spent - p.evenPace
        Text(
            if (diff > 0) "${Money.format(diff, wholeDollars = true)} past even pace" else "${Money.format(-diff, wholeDollars = true)} under even pace",
            style = KassaType.small, color = Kassa.colors.ink2,
        )
    }
    FlowRow(Modifier.padding(top = 10.dp)) {
        r.slices.filter { it.spent > 0 }.take(4).forEach { s ->
            Row(Modifier.padding(end = 16.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(3.dp)).background(Color(s.category.color)))
                Text(" ${s.category.name} ", style = KassaType.small, color = Kassa.colors.ink2)
                Text(Money.format(s.spent, wholeDollars = true), style = KassaType.small.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold), color = Kassa.colors.ink)
            }
        }
    }
}

/** What this month went into, in category colors, with a line where even spending would be today. */
@Composable
private fun Ribbon(r: TodayPlan.Result) {
    val p = r.plan
    val scale = maxOf(p.budget, p.spent).toFloat().coerceAtLeast(1f)
    val track = Kassa.colors.track
    val ink = Kassa.colors.ink
    val slices = r.slices.filter { it.spent > 0 }
    Canvas(
        Modifier.fillMaxWidth().height(22.dp).semantics {
            contentDescription = "${Money.format(p.spent, wholeDollars = true)} spent of ${Money.format(p.budget, wholeDollars = true)}"
        },
    ) {
        val barH = 14.dp.toPx()
        val top = (size.height - barH) / 2
        drawRoundRect(track, Offset(0f, top), Size(size.width, barH), CornerRadius(barH / 2))
        var x = 0f
        val gap = 2.dp.toPx()
        slices.forEachIndexed { i, s ->
            val w = size.width * s.spent / scale
            val first = i == 0
            val last = i == slices.lastIndex && x + w >= size.width - 1
            drawRoundRect(
                Color(s.category.color), Offset(x, top), Size((w - gap).coerceAtLeast(1f), barH),
                CornerRadius(if (first || last) barH / 2 else 0f),
            )
            x += w
        }
        val paceX = size.width * p.evenPace / scale
        drawLine(ink, Offset(paceX, 0f), Offset(paceX, size.height), strokeWidth = 2.dp.toPx())
    }
}

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

@Suppress("unused")
private fun daysBetween(a: java.time.LocalDate, b: java.time.LocalDate) = ChronoUnit.DAYS.between(a, b)
