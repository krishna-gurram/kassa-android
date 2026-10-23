package dev.krishnagurram.kassa.ui.insights

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.krishnagurram.kassa.domain.Budgets
import dev.krishnagurram.kassa.domain.Category
import dev.krishnagurram.kassa.domain.CategoryKind
import dev.krishnagurram.kassa.domain.Insights
import dev.krishnagurram.kassa.domain.Money
import dev.krishnagurram.kassa.domain.Patterns
import dev.krishnagurram.kassa.domain.Period
import dev.krishnagurram.kassa.domain.Spending
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
import dev.krishnagurram.kassa.ui.Ledger
import dev.krishnagurram.kassa.ui.common.CategoryBadge
import dev.krishnagurram.kassa.ui.common.TransactionRow
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaType
import dev.krishnagurram.kassa.ui.today.BigMoney
import java.time.Instant
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Locale

/** The pages one tap away from Insights. */
enum class InsightPage(val title: String) {
    BUDGETS("Budgets"), WEEK_CATEGORIES("This week by category"), SAVED("Saved"), STORES("Top stores"),
    SMALL("Under $15"), CREEP("Creeping up"), REPEATING("Repeating"), WEEKDAYS("By weekday"),
}

interface InsightActions {
    fun openCategory(category: Category)
    fun open(page: InsightPage)
    fun edit(t: Transaction)
}

/**
 * What already happened, with nothing projected: spending this month or week against the same point in the one
 * before, budgets, and patterns that each open a page. Sized to fit the cover screen without scrolling.
 */
@Composable
fun InsightsScreen(ledger: Ledger, contentPadding: PaddingValues, actions: InsightActions) {
    var week by rememberSaveable { mutableStateOf(false) }
    val monthName = ledger.period.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        val head: @Composable () -> Unit = {
            Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Insights", style = KassaType.title, color = Kassa.colors.ink, modifier = Modifier.weight(1f))
                Toggle(listOf(true to "This week", false to monthName), week) { week = it }
            }
        }
        if (wide) {
            Column(Modifier.fillMaxSize().padding(contentPadding)) {
                head()
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (week) { WeekHero(ledger); WeekCategories(ledger, actions, limit = 6) } else { MonthHero(ledger); BudgetList(ledger, actions, limit = 7) }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Kassa.colors.line))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (week) { WeekStandouts(ledger); LargestBuys(ledger, actions) } else PatternTiles(ledger, actions)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                head()
                if (week) { WeekHero(ledger); WeekCategories(ledger, actions, limit = 3); WeekStandouts(ledger) }
                else { MonthHero(ledger); BudgetList(ledger, actions, limit = 4); PatternTiles(ledger, actions) }
            }
        }
    }
}

/* ---------- month ---------- */

private fun dateOf(t: Transaction, ledger: Ledger): LocalDate = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate()

@Composable
private fun MonthHero(ledger: Ledger) {
    val p = ledger.period
    val spent = Patterns.spent(ledger.recent, p.start, ledger.today, ledger.zone)
    val prev = Period.startingIn(p.yearMonth.minusMonths(1), ledger.monthStartDay)
    val (from, to) = Patterns.samePointBefore(p.start, ledger.today, prev.start)
    val before = Patterns.spent(ledger.recent, from, to, ledger.zone)
    val hasBefore = ledger.recent.any { !dateOf(it, ledger).isAfter(to) && !dateOf(it, ledger).isBefore(from) }
    Hero("Spent in ${p.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())}", spent) {
        if (hasBefore) Compare("${prev.yearMonth.month.getDisplayName(TextStyle.SHORT, Locale.getDefault())} by the ${ordinal(to.dayOfMonth)}", before)
    }
}

/** Top-level expense categories with a budget or spending this month: over budget first, then the largest. */
private fun budgetRows(ledger: Ledger): List<Triple<Category, Long, Long>> {
    val p = ledger.period
    val spent = Spending.byTopCategory(ledger.recent, ledger.categoryById, p.start, ledger.today, ledger.zone)
    return ledger.categories.filter { it.parentId == null && it.kind == CategoryKind.EXPENSE && !it.hidden }
        .map { Triple(it, spent[it.id] ?: 0L, Budgets.amountFor(it.id, p.yearMonth, ledger.budgets) ?: 0L) }
        .filter { it.second > 0 || it.third > 0 }
        .sortedWith(compareByDescending<Triple<Category, Long, Long>> { it.third > 0 && it.second > it.third }.thenByDescending { it.second })
}

@Composable
private fun BudgetList(ledger: Ledger, actions: InsightActions, limit: Int) {
    val rows = budgetRows(ledger)
    Column {
        Head("Budgets", if (rows.size > limit) "All ${rows.size}" else null) { actions.open(InsightPage.BUDGETS) }
        if (rows.isEmpty()) Text("No spending yet this month.", style = KassaType.body, color = Kassa.colors.ink2)
        val maxNoBudget = rows.filter { it.third == 0L }.maxOfOrNull { it.second } ?: 1L
        rows.take(limit).forEach { (c, s, b) -> CategoryBar(c, s, if (b > 0) b else maxNoBudget, if (b > 0) "of ${Money.format(b, wholeDollars = true)}" else null, over = b > 0 && s > b) { actions.openCategory(c) } }
    }
}

@Composable
private fun PatternTiles(ledger: Ledger, actions: InsightActions) {
    val p = ledger.period
    val saved = remember(ledger) { Patterns.saved(ledger.recent, ledger.today, ledger.monthStartDay, ledger.zone) }
    val stores = remember(ledger) { Patterns.stores(ledger.recent, ledger.categoryById, p.start, ledger.today, ledger.zone) }
    val small = remember(ledger) { Patterns.smallBuys(ledger.recent, ledger.categoryById, p.start, ledger.today, ledger.zone) }
    val smallMonths = remember(ledger) { Patterns.smallBuysByMonth(ledger.recent, ledger.categoryById, ledger.today, ledger.monthStartDay, ledger.zone) }
    val creep = remember(ledger) { Patterns.creeping(ledger.recent, ledger.categories, ledger.today, ledger.monthStartDay, ledger.zone) }
    val repeating = remember(ledger) { Insights.repeating(ledger.recent, ledger.categoryById, ledger.zone) }
    val weekdays = remember(ledger) { Insights.weekdayAverages(ledger.recent, ledger.categories, ledger.today, ledger.zone) }
    val now = saved.last()
    val busiest = weekdays.maxByOrNull { it.second }
    val changed = repeating.count { it.change > 0 }

    Column {
        Head("Patterns")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PatternTile("Saved so far", Money.format(now.saved, wholeDollars = true), now.rate?.let { "$it% of income" } ?: "No income yet",
                    valueColor = if (now.saved >= 0) Kassa.colors.positive else Kassa.colors.attention, modifier = Modifier.weight(1f), onClick = { actions.open(InsightPage.SAVED) }) {
                    MiniBars(saved.map { maxOf(0L, it.saved) }, saved.lastIndex, Kassa.colors.positive)
                }
                PatternTile("Top store", stores.firstOrNull()?.name ?: "None yet", stores.firstOrNull()?.let { Money.format(it.total, wholeDollars = true) } ?: "",
                    modifier = Modifier.weight(1f), onClick = { actions.open(InsightPage.STORES) }) {
                    MiniBars(stores.take(5).map { it.total }, 0, stores.firstOrNull()?.category?.let { Color(it.color) } ?: Kassa.colors.ink)
                }
                PatternTile("Under $15", Money.format(small.total, wholeDollars = true), "${small.count} buys",
                    modifier = Modifier.weight(1f), onClick = { actions.open(InsightPage.SMALL) }) {
                    MiniBars(smallMonths.map { it.second }, smallMonths.lastIndex, Kassa.colors.ink)
                }
            }
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val top = creep.firstOrNull()
                PatternTile("Creeping up", top?.category?.name ?: "None", top?.let { "${it.months.size} months" } ?: "Steady",
                    modifier = Modifier.weight(1f), onClick = { actions.open(InsightPage.CREEP) }) {
                    MiniBars(top?.months?.map { it.second } ?: listOf(1L, 1L, 1L), (top?.months?.size ?: 3) - 1, if (top != null) Kassa.colors.attention else Kassa.colors.track)
                }
                PatternTile("Repeating", Money.format(repeating.sumOf { it.amount }, wholeDollars = true), if (changed > 0) "$changed changed" else "${repeating.size} charges",
                    subColor = if (changed > 0) Kassa.colors.attention else null, modifier = Modifier.weight(1f), onClick = { actions.open(InsightPage.REPEATING) }) {
                    RepeatLines(repeating.take(4).map { it.category?.let { c -> Color(c.color) } ?: Kassa.colors.ink3 })
                }
                PatternTile("Busiest day", busiest?.first?.getDisplayName(TextStyle.SHORT, Locale.getDefault()) ?: "None", busiest?.let { Money.format(it.second, wholeDollars = true) } ?: "",
                    modifier = Modifier.weight(1f), onClick = { actions.open(InsightPage.WEEKDAYS) }) {
                    MiniBars(weekdays.map { it.second }, busiest?.let { weekdays.indexOf(it) } ?: -1, Kassa.colors.ink)
                }
            }
        }
    }
}

/* ---------- week ---------- */

@Composable
private fun WeekHero(ledger: Ledger) {
    val monday = Patterns.weekStart(ledger.today)
    val days = Patterns.daysThisWeek(ledger.recent, ledger.today, ledger.zone)
    val spent = days.sumOf { it.second ?: 0L }
    val (from, to) = Patterns.samePointBefore(monday, ledger.today, monday.minusWeeks(1))
    val before = Patterns.spent(ledger.recent, from, to, ledger.zone)
    val max = (days.maxOfOrNull { it.second ?: 0L } ?: 0L).coerceAtLeast(1L)
    Hero("Spent this week", spent) {
        Compare("Last week by ${ledger.today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}", before)
        Row(Modifier.fillMaxWidth().height(84.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { (d, v) ->
                val today = d == ledger.today.dayOfWeek
                Column(Modifier.weight(1f).fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally) {
                    Box(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Kassa.colors.ground.copy(alpha = if (v == null) 0.5f else 1f)), contentAlignment = Alignment.BottomCenter) {
                        if (v != null && v > 0) Box(Modifier.fillMaxWidth().fillMaxHeight((v.toFloat() / max).coerceAtLeast(0.04f)).clip(RoundedCornerShape(6.dp)).background(Kassa.colors.ink))
                    }
                    Text(d.getDisplayName(TextStyle.SHORT, Locale.getDefault()), style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold, textDecoration = if (today) TextDecoration.Underline else null), color = if (today) Kassa.colors.ink else Kassa.colors.ink2, modifier = Modifier.padding(top = 3.dp))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { (_, v) -> Text(v?.let { Money.format(it, wholeDollars = true) } ?: "", style = KassaType.tiny.copy(fontSize = 10.sp), color = Kassa.colors.ink2, textAlign = TextAlign.Center, maxLines = 1, modifier = Modifier.weight(1f)) }
        }
    }
}

private fun weekRows(ledger: Ledger): List<Pair<Category, Long>> {
    val monday = Patterns.weekStart(ledger.today)
    return Spending.byTopCategory(ledger.recent, ledger.categoryById, monday, ledger.today, ledger.zone)
        .mapNotNull { (id, v) -> ledger.categoryById[id]?.let { it to v } }.filter { it.second > 0 }.sortedByDescending { it.second }
}

@Composable
private fun WeekCategories(ledger: Ledger, actions: InsightActions, limit: Int) {
    val rows = weekRows(ledger)
    Column {
        Head("By category", if (rows.size > limit) "All ${rows.size}" else null) { actions.open(InsightPage.WEEK_CATEGORIES) }
        if (rows.isEmpty()) Text("Nothing spent yet this week.", style = KassaType.body, color = Kassa.colors.ink2)
        val max = rows.firstOrNull()?.second ?: 1L
        rows.take(limit).forEach { (c, v) -> CategoryBar(c, v, max, null, over = false) { actions.openCategory(c) } }
    }
}

@Composable
private fun WeekStandouts(ledger: Ledger) {
    val changes = Patterns.weekChanges(ledger.recent, ledger.categories, ledger.today, ledger.zone)
    val up = changes.filter { it.change > 0 }.maxByOrNull { it.change }
    val down = changes.filter { it.change < 0 }.minByOrNull { it.change }
    if (up == null && down == null) return
    Column {
        Head("vs last week")
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Figure("Up most", up?.category?.name ?: "None", up?.let { "+" + Money.format(it.change, wholeDollars = true) }, Kassa.colors.attention, Modifier.weight(1f))
            Figure("Down most", down?.category?.name ?: "None", down?.let { "−" + Money.format(-it.change, wholeDollars = true) }, Kassa.colors.positive, Modifier.weight(1f))
        }
    }
}

@Composable
private fun LargestBuys(ledger: Ledger, actions: InsightActions) {
    val monday = Patterns.weekStart(ledger.today)
    val list = ledger.recent.filter { it.kind == TxKind.EXPENSE && !dateOf(it, ledger).isBefore(monday) && !dateOf(it, ledger).isAfter(ledger.today) }
        .sortedByDescending { it.categoryAmount }.take(4)
    if (list.isEmpty()) return
    Column {
        Head("Largest buys")
        list.forEachIndexed { i, t -> TransactionRow(t, ledger, showDivider = i < list.lastIndex, onClick = actions::edit) }
    }
}

/* ---------- pieces ---------- */

@Composable
private fun Hero(label: String, amount: Long, extra: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Kassa.colors.raised).padding(14.dp)) {
        Text(label, style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2)
        BigMoney(amount, sizeSp = 34f)
        extra()
    }
}

@Composable
private fun Compare(label: String, amount: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = KassaType.small, color = Kassa.colors.ink2)
        Text("  " + Money.format(amount, wholeDollars = true), style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink)
    }
}

@Composable
internal fun Head(title: String, link: String? = null, onLink: () -> Unit = {}) {
    Row(Modifier.fillMaxWidth().height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, style = KassaType.bodyStrong.copy(fontSize = 17.sp), color = Kassa.colors.ink, modifier = Modifier.weight(1f))
        if (link != null) Text("$link  ›", style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2,
            modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onLink).padding(horizontal = 4.dp, vertical = 4.dp))
    }
}

/** One category: name and amount, then a bar of how much of [ref] is used. Past it, the bar turns orange. */
@Composable
internal fun CategoryBar(c: Category, spent: Long, ref: Long, refLabel: String?, over: Boolean, onClick: () -> Unit) {
    val color = if (over) Kassa.colors.attention else Color(c.color)
    Column(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryBadge(c, size = 26)
            Text(c.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
            Text(Money.format(spent, wholeDollars = true), style = KassaType.bodyStrong, color = if (over) Kassa.colors.attention else Kassa.colors.ink)
            if (refLabel != null) Text(" $refLabel", style = KassaType.tiny, color = Kassa.colors.ink2)
        }
        Box(Modifier.padding(start = 34.dp, top = 6.dp).fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(Kassa.colors.track)) {
            Box(Modifier.fillMaxWidth((spent.toFloat() / ref.coerceAtLeast(1)).coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
        }
    }
}

@Composable
private fun PatternTile(
    label: String, value: String, sub: String, modifier: Modifier, onClick: () -> Unit,
    valueColor: Color = Kassa.colors.ink, subColor: Color? = null, visual: @Composable () -> Unit,
) {
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Kassa.colors.raised).clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp)) {
        Box(Modifier.fillMaxWidth().height(22.dp)) { visual() }
        Text(label, style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2, maxLines = 1, modifier = Modifier.padding(top = 6.dp))
        Text(value, style = KassaType.bodyStrong, color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub.isNotEmpty()) Text(sub, style = KassaType.tiny, color = subColor ?: Kassa.colors.ink3, maxLines = 2)
    }
}

@Composable
private fun MiniBars(values: List<Long>, highlight: Int, color: Color) {
    val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(3.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v ->
            Box(Modifier.weight(1f).fillMaxHeight((v.toFloat() / max).coerceIn(0.2f, 1f)).clip(RoundedCornerShape(3.dp)).background(if (i == highlight) color else Kassa.colors.track))
        }
    }
}

@Composable
private fun RepeatLines(colors: List<Color>) {
    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically)) {
        listOf(1f, 0.7f, 0.45f, 0.3f).forEachIndexed { i, w ->
            Box(Modifier.fillMaxWidth(w).height(4.dp).clip(RoundedCornerShape(2.dp)).background(colors.getOrNull(i) ?: Kassa.colors.track))
        }
    }
}

@Composable
internal fun Figure(label: String, value: String, sub: String?, subColor: Color, modifier: Modifier, valueColor: Color = Kassa.colors.ink) {
    Column(modifier.fillMaxHeight().clip(RoundedCornerShape(14.dp)).background(Kassa.colors.raised).padding(horizontal = 12.dp, vertical = 10.dp)) {
        Text(label, style = KassaType.tiny.copy(fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2)
        Text(value, style = KassaType.bodyStrong.copy(fontSize = 17.sp), color = valueColor, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (sub != null) Text(sub, style = KassaType.small, color = subColor)
    }
}

@Composable
private fun Toggle(options: List<Pair<Boolean, String>>, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(50)).background(Kassa.colors.chip).padding(3.dp)) {
        options.forEach { (v, label) ->
            val on = v == value
            Text(
                label, style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = if (on) Kassa.colors.onInk else Kassa.colors.ink2,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(if (on) Kassa.colors.ink else Color.Transparent).clickable { onChange(v) }.padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

internal fun ordinal(n: Int): String = n.toString() + when {
    n % 100 in 11..13 -> "th"
    n % 10 == 1 -> "st"; n % 10 == 2 -> "nd"; n % 10 == 3 -> "rd"
    else -> "th"
}
