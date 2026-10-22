package dev.krishnagurram.kassa.ui.insights

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krishnagurram.kassa.domain.Budgets
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.Insights
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Spending
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.CategoryBadge
import dev.krishnagurram.kassa.ui.common.EntryRow
import dev.krishnagurram.kassa.ui.common.Gap
import dev.krishnagurram.kassa.ui.common.SectionHeader
import dev.krishnagurram.kassa.ui.entry.Chip
import dev.krishnagurram.kassa.ui.importer.PrimaryButton
import dev.krishnagurram.kassa.ui.importer.SecondaryButton
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import java.time.format.TextStyle
import java.util.Locale

interface InsightActions {
    fun setCap(category: Category, amount: Long)
    fun dismissFlag(category: Category)
    fun openCategory(category: Category)
}

/** Red flags, where the money went against budget and pace, trend, weekday pattern and repeating charges. */
@Composable
fun InsightsScreen(
    ledger: Ledger,
    contentPadding: PaddingValues,
    sensitivity: Insights.Sensitivity,
    dismissed: Set<Long>,
    actions: InsightActions,
) {
    var week by rememberSaveable { mutableStateOf(false) }
    var recap by remember { mutableStateOf(false) }
    val period = ledger.period
    val fixedNotes = remember(ledger.assist.recurring, ledger.recent.size) {
        (ledger.assist.recurring.map { it.name } + Insights.repeating(ledger.recent, ledger.categoryById, ledger.zone).map { it.note }).map { it.lowercase() }.toSet()
    }
    val flags = Insights.flags(ledger.recent, ledger.categories, ledger.budgets, fixedNotes, ledger.today, ledger.monthStartDay, ledger.zone, sensitivity, dismissed)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val head: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f)) {
                    Text("${period.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())}, day ${java.time.temporal.ChronoUnit.DAYS.between(period.start, ledger.today) + 1} of ${period.days}", style = KassaType.small, color = Kassa.colors.ink2)
                    Text("Insights", style = KassaType.title, color = Kassa.colors.ink)
                }
                Text("Weekly recap", style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.clickable { recap = true }.padding(8.dp))
            }
            Row(Modifier.padding(top = 14.dp).clip(RoundedCornerShape(12.dp)).background(Kassa.colors.chip).padding(3.dp)) {
                listOf(false to "This month", true to "This week").forEach { (w, label) ->
                    Box(
                        Modifier.weight(1f).clip(RoundedCornerShape(9.dp)).background(if (week == w) Kassa.colors.raised else Color.Transparent)
                            .clickable { week = w }.padding(vertical = 9.dp),
                        contentAlignment = Alignment.Center,
                    ) { Text(label, style = KassaType.bodyStrong, color = if (week == w) Kassa.colors.ink else Kassa.colors.ink2) }
                }
            }
        }
        val left: @Composable () -> Unit = { if (week) WeekView(ledger) else { FlagsBlock(flags, ledger, actions); WhereItWent(ledger, actions) } }
        val right: @Composable () -> Unit = { if (!week) { Trend(ledger); Weekdays(ledger); Repeating(ledger) } }

        if (wide) {
            Column(Modifier.fillMaxSize().padding(contentPadding)) {
                head()
                Row(horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { left() }
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) { right() }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding)) { head(); left(); right() }
        }
    }
    if (recap) Recap(ledger, flags, onCap = { c, a -> actions.setCap(c, a) }, onClose = { recap = false })
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlagsBlock(flags: List<Insights.Flag>, ledger: Ledger, actions: InsightActions) {
    SectionHeader("Worth a look", if (flags.isEmpty()) "All within range" else "${flags.size} above average")
    if (flags.isEmpty()) Text("Every everyday category is within its 3-month average and budget.", style = KassaType.body, color = Kassa.colors.ink2)
    flags.take(4).forEach { f ->
        Column(Modifier.padding(vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryBadge(f.category, size = 30)
                Text("  ${f.category.name}: above your 3-month average", style = KassaType.bodyStrong, color = Kassa.colors.ink)
            }
            val month = ledger.period.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
            val budgetLine = f.budget?.takeIf { it > 0 && f.soFar > it }?.let { " That's already ${Money.format(f.soFar - it, wholeDollars = true)} over its ${Money.format(it, wholeDollars = true)} budget." } ?: ""
            Text(
                "${Money.format(f.soFar, wholeDollars = true)} month to date. At this pace $month ends near ${Money.format(f.projected, wholeDollars = true)}, " +
                    "against a 3-month average of ${Money.format(f.usual, wholeDollars = true)}: a variance of ${Money.format(maxOf(0, f.extra), wholeDollars = true)}. " +
                    "Holding to the average saves that much.$budgetLine",
                style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 4.dp),
            )
            FlowRow(Modifier.padding(top = 8.dp)) {
                Chip("Cap at ${Money.format(f.suggestedCap, wholeDollars = true)}", selected = true) { actions.setCap(f.category, f.suggestedCap) }
                Chip("See where", selected = false) { actions.openCategory(f.category) }
                Chip("It's expected", selected = false, outlined = true) { actions.dismissFlag(f.category) }
            }
        }
        HorizontalDivider(color = Kassa.colors.line)
    }
}

@Composable
private fun WhereItWent(ledger: Ledger, actions: InsightActions) {
    val p = ledger.period
    val progress = p.progress(ledger.today).toFloat()
    val spent = Spending.byTopCategory(ledger.recent, ledger.categoryById, p.start, ledger.today, ledger.zone)
    val rows = ledger.categories.filter { it.parentId == null && it.kind == dev.krishnagurram.kassa.domain.CategoryKind.EXPENSE && !it.hidden }
        .map { Triple(it, spent[it.id] ?: 0L, Budgets.amountFor(it.id, p.yearMonth, ledger.budgets) ?: 0L) }
        .filter { it.second > 0 || it.third > 0 }
    val (daily, bills) = rows.partition { it.first.countsTowardDaily }
    SectionHeader("Where it went", "Line marks even pace")
    daily.sortedByDescending { it.second }.forEach { (c, s, b) -> BudgetBar(c, s, b, progress) { actions.openCategory(c) } }
    if (bills.isNotEmpty()) {
        SectionHeader("Bills", "Not in today's number")
        bills.sortedByDescending { it.second }.forEach { (c, s, b) -> BudgetBar(c, s, b, progress) { actions.openCategory(c) } }
    }
}

@Composable
private fun BudgetBar(c: Category, spent: Long, budget: Long, progress: Float, onClick: () -> Unit) {
    val color = Color(c.color)
    val track = Kassa.colors.track
    val ink = Kassa.colors.ink
    val warn = Kassa.colors.attention
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).clip(RoundedCornerShape(3.dp)).background(color))
            Text("  ${c.name}", style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
            Text(Money.format(spent, wholeDollars = true), style = KassaType.bodyStrong, color = Kassa.colors.ink)
            if (budget > 0) Text(" of ${Money.format(budget, wholeDollars = true)}", style = KassaType.small, color = Kassa.colors.ink2)
        }
        Canvas(Modifier.fillMaxWidth().padding(top = 8.dp).height(12.dp).semantics { contentDescription = "${c.name}: ${Money.format(spent)} of ${Money.format(budget)}" }) {
            val barH = 8.dp.toPx(); val top = (size.height - barH) / 2
            val scale = maxOf(budget, spent).toFloat().coerceAtLeast(1f)
            drawRoundRect(track, Offset(0f, top), Size(size.width, barH), CornerRadius(barH / 2))
            val within = minOf(spent, if (budget > 0) budget else spent)
            drawRoundRect(color, Offset(0f, top), Size(size.width * within / scale, barH), CornerRadius(barH / 2))
            if (budget in 1 until spent) {
                val x0 = size.width * budget / scale
                drawRect(warn, Offset(x0, top), Size(size.width - x0, barH))
            }
            if (budget > 0) {
                val px = size.width * budget * progress / scale
                drawLine(ink, Offset(px, 0f), Offset(px, size.height), strokeWidth = 2.dp.toPx())
            }
        }
    }
}

@Composable
private fun Trend(ledger: Ledger) {
    val totals = Insights.monthTotals(ledger.recent, ledger.categories, ledger.today, ledger.monthStartDay, ledger.zone)
    val budget = ledger.categories.filter { it.parentId == null && it.countsTowardDaily && !it.hidden }.sumOf { Budgets.amountFor(it.id, ledger.period.yearMonth, ledger.budgets) ?: 0 }
    val max = (totals.maxOfOrNull { it.projected ?: it.spent } ?: 1L).coerceAtLeast(budget).coerceAtLeast(1) * 1.1f
    val ink = Kassa.colors.ink; val track = Kassa.colors.track; val ink3 = Kassa.colors.ink3
    SectionHeader("Five months", "Everyday spending")
    Row(Modifier.fillMaxWidth().height(170.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Bottom) {
        totals.forEachIndexed { i, t ->
            val current = i == totals.lastIndex
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Money.format(t.projected ?: t.spent, wholeDollars = true), style = KassaType.tiny, color = Kassa.colors.ink, maxLines = 1)
                Canvas(Modifier.fillMaxWidth().height(120.dp)) {
                    val h = size.height
                    val full = h * ((t.projected ?: t.spent) / max)
                    val actual = h * (t.spent / max)
                    if (t.projected != null) drawRoundRect(ink3, Offset(0f, h - full), Size(size.width, full - actual), CornerRadius(6.dp.toPx()),
                        style = androidx.compose.ui.graphics.drawscope.Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
                    drawRoundRect(if (current) ink else track, Offset(0f, h - actual), Size(size.width, actual), CornerRadius(6.dp.toPx()))
                    if (budget > 0) {
                        val y = h - h * (budget / max)
                        drawLine(ink3, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f)))
                    }
                }
                Text(t.month.month.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = KassaType.tiny, color = Kassa.colors.ink2)
            }
        }
    }
    Text(
        "Dashed outline: where this month lands at the current pace. Dashed line: this month's budget, ${Money.format(budget, wholeDollars = true)}.",
        style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun Weekdays(ledger: Ledger) {
    val avg = Insights.weekdayAverages(ledger.recent, ledger.categories, ledger.today, ledger.zone)
    val max = (avg.maxOfOrNull { it.second } ?: 1L).coerceAtLeast(1)
    val top = avg.maxByOrNull { it.second }
    val ink = Kassa.colors.ink; val track = Kassa.colors.track
    SectionHeader("When you spend", "Average day, last 13 weeks")
    Row(Modifier.fillMaxWidth().height(150.dp).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
        avg.forEach { (d, v) ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(Money.format(v, wholeDollars = true), style = KassaType.tiny.copy(fontSize = 10.sp), color = Kassa.colors.ink, maxLines = 1)
                Canvas(Modifier.fillMaxWidth().height(100.dp)) {
                    val hh = size.height * (v.toFloat() / max)
                    drawRoundRect(if (d == top?.first) ink else track, Offset(0f, size.height - hh), Size(size.width, hh.coerceAtLeast(2f)), CornerRadius(5.dp.toPx()))
                }
                Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault()).take(3), style = KassaType.tiny, color = Kassa.colors.ink2)
            }
        }
    }
    if (top != null && top.second > 0) {
        val weekday = avg.take(4).map { it.second }.average()
        Text(
            "${top.first.getDisplayName(TextStyle.FULL, Locale.getDefault())}s average ${Money.format(top.second, wholeDollars = true)}" +
                if (weekday > 0) ", about ${"%.1f".format(top.second / weekday)} times a Monday to Thursday." else ".",
            style = KassaType.small, color = Kassa.colors.ink2, modifier = Modifier.padding(top = 6.dp),
        )
    }
}

@Composable
private fun Repeating(ledger: Ledger) {
    val r = Insights.repeating(ledger.recent, ledger.categoryById, ledger.zone).filter { it.category?.countsTowardDaily != false || it.amount < 50_000 }
    if (r.isEmpty()) return
    SectionHeader("Repeating charges", "${Money.format(r.sumOf { it.amount })} a month")
    r.forEachIndexed { i, x ->
        EntryRow(
            badge = { CategoryBadge(x.category) },
            title = x.note,
            subtitle = if (x.change > 0) "Up ${Money.format(x.change)} in ${x.lastMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())}" else "Same amount each month",
            amount = Money.format(x.amount),
            amountColor = Kassa.colors.ink,
            note = "${Money.format(x.amount * 12, wholeDollars = true)} a year",
            showDivider = i < r.lastIndex,
        )
    }
}

@Composable
private fun WeekView(ledger: Ledger) {
    val w = Insights.week(ledger.recent, ledger.categories, ledger.today, ledger.zone)
    Row(Modifier.fillMaxWidth().padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Since Monday", style = KassaType.tiny, color = Kassa.colors.ink2)
            Text(Money.format(w.soFar, wholeDollars = true), style = KassaType.stat.copy(fontSize = 28.sp), color = Kassa.colors.ink)
        }
        Column(Modifier.weight(1f)) {
            Text("A typical week by now", style = KassaType.tiny, color = Kassa.colors.ink2)
            Text(Money.format(w.typical, wholeDollars = true), style = KassaType.stat.copy(fontSize = 28.sp), color = Kassa.colors.ink2)
        }
    }
    SectionHeader("This week by category", "Line marks a typical week so far")
    val max = (w.byCategory.maxOfOrNull { maxOf(it.second, it.third) } ?: 1L).coerceAtLeast(1)
    w.byCategory.forEach { (c, now, typ) ->
        val color = Color(c.color); val track = Kassa.colors.track; val ink = Kassa.colors.ink
        Column(Modifier.padding(vertical = 10.dp)) {
            Row {
                Text(c.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
                Text("${Money.format(now, wholeDollars = true)} ", style = KassaType.bodyStrong, color = Kassa.colors.ink)
                Text("usually ${Money.format(typ, wholeDollars = true)}", style = KassaType.small, color = Kassa.colors.ink2)
            }
            Canvas(Modifier.fillMaxWidth().padding(top = 8.dp).height(12.dp)) {
                val barH = 8.dp.toPx(); val top = (size.height - barH) / 2
                drawRoundRect(track, Offset(0f, top), Size(size.width, barH), CornerRadius(barH / 2))
                drawRoundRect(color, Offset(0f, top), Size(size.width * now / max, barH), CornerRadius(barH / 2))
                val x = size.width * typ / max
                drawLine(ink, Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
            }
        }
    }
}

/** A short story you tap through: your week, the biggest leak, one win, and one change to try. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Recap(ledger: Ledger, flags: List<Insights.Flag>, onCap: (Category, Long) -> Unit, onClose: () -> Unit) {
    val w = Insights.week(ledger.recent, ledger.categories, ledger.today, ledger.zone)
    val win = Insights.wins(ledger.recent, ledger.categories, ledger.budgets, ledger.today, ledger.monthStartDay, ledger.zone).firstOrNull()
    val leak = flags.firstOrNull()
    val day = ledger.today.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.getDefault())
    data class Slide(val kicker: String, val title: String, val body: String, val cap: Insights.Flag? = null)
    val slides = buildList {
        add(Slide("Your week", "${Money.format(w.soFar, wholeDollars = true)} on everyday things since Monday.",
            "A typical week by $day is ${Money.format(w.typical, wholeDollars = true)}. " +
                if (w.soFar > w.typical) "You're ${Money.format(w.soFar - w.typical, wholeDollars = true)} ahead of that." else "You're ${Money.format(w.typical - w.soFar, wholeDollars = true)} under it."))
        if (leak != null) add(Slide("Biggest leak", "${leak.category.name} is on track for ${Money.format(leak.projected, wholeDollars = true)}.",
            "That's ${Money.format(maxOf(0, leak.extra), wholeDollars = true)} more than your usual month of ${Money.format(leak.usual, wholeDollars = true)}."))
        if (win != null) add(Slide("One win", "${win.category.name} is ${Money.format(win.underPace, wholeDollars = true)} under pace.",
            "Keep it there and that money stays free for the rest of the month."))
        if (leak != null) add(Slide("Try this", "Cap ${leak.category.name} at ${Money.format(leak.suggestedCap, wholeDollars = true)} a month.",
            "Holding to your usual saves about ${Money.format(maxOf(0, leak.extra), wholeDollars = true)} this month.", cap = leak))
    }
    var i by remember { mutableIntStateOf(0) }
    val s = slides[i.coerceAtMost(slides.lastIndex)]
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true), containerColor = Kassa.colors.raised) {
        Column(Modifier.padding(horizontal = 24.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                slides.indices.forEach { j -> Box(Modifier.weight(1f).height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (j <= i) Kassa.colors.ink else Kassa.colors.line)) }
            }
            Gap(28)
            Text(s.kicker, style = KassaType.bodyStrong, color = Kassa.colors.ink2)
            Text(s.title, style = KassaType.title, color = Kassa.colors.ink, modifier = Modifier.padding(top = 10.dp))
            Text(s.body, style = KassaType.body.copy(fontSize = 16.sp), color = Kassa.colors.ink2, modifier = Modifier.padding(top = 12.dp))
            Gap(40)
            s.cap?.let { f -> PrimaryButton("Set the cap") { onCap(f.category, f.suggestedCap); onClose() }; Gap(8) }
            SecondaryButton(if (i < slides.lastIndex) "Next" else "Done", { if (i < slides.lastIndex) i++ else onClose() })
        }
    }
}
