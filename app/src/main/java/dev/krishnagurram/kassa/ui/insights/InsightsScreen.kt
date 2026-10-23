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
import dev.krishnagurram.kassa.ui.common.CategoryLines
import dev.krishnagurram.kassa.ui.common.monthLines
import dev.krishnagurram.kassa.ui.common.weekLines
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
    SAVED("Saved"), STORES("Top stores"),
    SMALL("Small purchases"), CREEP("Creeping up"), REPEATING("Repeating"), WEEKDAYS("By weekday"), DUES("Due this week"),
    BIG_WEEK("Big purchases this week"), BIG_MONTH("Big purchases this month"),
}

interface InsightActions {
    fun openCategory(category: Category)
    fun open(page: InsightPage)
    fun edit(t: Transaction)
    fun openDay(date: LocalDate)
    /** The full spending page for this week or this month, the same one Overview opens. */
    fun openSpending(month: Boolean)
}

/**
 * What already happened, with nothing projected: spending this month or week against the same point in the one
 * before, budgets, and patterns that each open a page. Sized to fit the cover screen without scrolling.
 */
@Composable
fun InsightsScreen(ledger: Ledger, contentPadding: PaddingValues, actions: InsightActions) {
    var week by rememberSaveable { mutableStateOf(true) }
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
                        if (week) { WeekHero(ledger, actions); WeekCategories(ledger, actions, limit = 7) } else { MonthHero(ledger); BudgetList(ledger, actions, limit = 7) }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Kassa.colors.line))
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (week) { WeekTiles(ledger, actions); LargestBuys(ledger, actions) } else PatternTiles(ledger, actions)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                head()
                if (week) { WeekHero(ledger, actions); WeekCategories(ledger, actions, limit = 4); WeekTiles(ledger, actions) }
                else { MonthHero(ledger); BudgetList(ledger, actions, limit = 4); PatternTiles(ledger, actions) }
            }
        }
    }
}

/* ---------- month ---------- */

internal fun dateOf(t: Transaction, ledger: Ledger): LocalDate = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate()

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

@Composable
private fun BudgetList(ledger: Ledger, actions: InsightActions, limit: Int) {
    val lines = monthLines(ledger)
    val spent = lines.filter { it.spent > 0 }
    Column {
        Head("By category", "All ${lines.size}") { actions.openSpending(true) }
        if (spent.isEmpty()) Text("No spending yet this month.", style = KassaType.body, color = Kassa.colors.ink2)
        CategoryLines(spent.take(limit), actions::openCategory)
    }
}

@Composable
private fun PatternTiles(ledger: Ledger, actions: InsightActions) {
    val p = ledger.period
    val monthBiggest = remember(ledger) { bigPurchases(ledger, week = false) }
    val stores = remember(ledger) { Patterns.stores(ledger.recent, ledger.categoryById, p.start, ledger.today, ledger.zone) }
    val small = remember(ledger) { Patterns.smallBuys(ledger.recent, ledger.categoryById, p.start, ledger.today, ledger.zone) }
    val smallMonths = remember(ledger) { Patterns.smallBuysByMonth(ledger.recent, ledger.categoryById, ledger.today, ledger.monthStartDay, ledger.zone) }
    val creep = remember(ledger) { Patterns.creeping(ledger.recent, ledger.categories, ledger.today, ledger.monthStartDay, ledger.zone) }
    val repeating = remember(ledger) { Insights.repeating(ledger.recent, ledger.categoryById, ledger.zone) }
    val weekdays = remember(ledger) { Insights.weekdayAverages(ledger.recent, ledger.categories, ledger.today, ledger.zone) }
    val busiest = weekdays.maxByOrNull { it.second }
    val changed = repeating.count { it.change > 0 }

    Column {
        Head("Patterns")
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val biggest = monthBiggest
                PatternTile("Big purchases", biggest.firstOrNull()?.let { Money.format(it.categoryAmount, wholeDollars = true) } ?: "None yet",
                    if (biggest.isEmpty()) "" else "Top ${biggest.size}",
                    modifier = Modifier.weight(1f), onClick = { actions.open(InsightPage.BIG_MONTH) }) {
                    MiniBars(biggest.map { it.categoryAmount }, 0, Kassa.colors.ink)
                }
                PatternTile("Top store", stores.firstOrNull()?.name ?: "None yet", stores.firstOrNull()?.let { Money.format(it.total, wholeDollars = true) } ?: "",
                    modifier = Modifier.weight(1f), onClick = { actions.open(InsightPage.STORES) }) {
                    MiniBars(stores.take(5).map { it.total }, 0, stores.firstOrNull()?.category?.let { Color(it.color) } ?: Kassa.colors.ink)
                }
                PatternTile("Small purchases", Money.format(small.total, wholeDollars = true), "${small.count} buys",
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
private fun WeekHero(ledger: Ledger, actions: InsightActions) {
    val monday = ledger.weekStart
    val days = Patterns.daysThisWeek(ledger.recent, ledger.today, ledger.zone, ledger.firstDayOfWeek)
    val spent = days.sumOf { it.second ?: 0L }
    val (from, to) = Patterns.samePointBefore(monday, ledger.today, monday.minusWeeks(1))
    val before = Patterns.spent(ledger.recent, from, to, ledger.zone)
    val max = (days.maxOfOrNull { it.second ?: 0L } ?: 0L).coerceAtLeast(1L)
    Hero("Spent this week", spent) {
        Compare("Last week by ${ledger.today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}", before)
        Row(Modifier.fillMaxWidth().height(84.dp).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            days.forEach { (date, v) ->
                val today = date == ledger.today
                val d = date.dayOfWeek
                Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(8.dp)).clickable(enabled = v != null) { actions.openDay(date) }, horizontalAlignment = Alignment.CenterHorizontally) {
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

@Composable
private fun WeekCategories(ledger: Ledger, actions: InsightActions, limit: Int) {
    val lines = weekLines(ledger)
    val spent = lines.filter { it.spent > 0 }
    Column {
        Head("By category", "All ${lines.size}") { actions.openSpending(false) }
        if (spent.isEmpty()) Text("Nothing spent yet this week.", style = KassaType.body, color = Kassa.colors.ink2)
        CategoryLines(spent.take(limit), actions::openCategory)
    }
}

/** Card payments due from today through Sunday, soonest first. */
internal fun duesThisWeek(ledger: Ledger) = ledger.cards()
    .filter { it.snapshot.payable > 0 && !it.snapshot.due.isBefore(ledger.today) && !it.snapshot.due.isAfter(ledger.weekStart.plusDays(6)) }
    .sortedBy { it.snapshot.due }

/** The 10 largest expenses this week or this budget month, largest first (fewer if there aren't 10). */
internal fun bigPurchases(ledger: Ledger, week: Boolean): List<Transaction> {
    val from = if (week) ledger.weekStart else ledger.period.start
    return ledger.recent.filter { it.kind == TxKind.EXPENSE && !dateOf(it, ledger).isBefore(from) && !dateOf(it, ledger).isAfter(ledger.today) }
        .sortedByDescending { it.categoryAmount }.take(10)
}

private fun weekExpenses(ledger: Ledger): List<Transaction> {
    val monday = ledger.weekStart
    return ledger.recent.filter { it.kind == TxKind.EXPENSE && !dateOf(it, ledger).isBefore(monday) && !dateOf(it, ledger).isAfter(ledger.today) }
}

/** Up and down most against last week, what's due before Sunday, and the biggest purchase: four tiles, two rows. */
@Composable
private fun WeekTiles(ledger: Ledger, actions: InsightActions) {
    val changes = Patterns.weekChanges(ledger.recent, ledger.categories, ledger.today, ledger.zone, ledger.firstDayOfWeek)
    val up = changes.filter { it.change > 0 }.maxByOrNull { it.change }
    val down = changes.filter { it.change < 0 }.minByOrNull { it.change }
    val dues = duesThisWeek(ledger)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Figure("Up most vs last wk", up?.category?.name ?: "None", up?.let { "+" + Money.format(it.change, wholeDollars = true) }, Kassa.colors.attention,
                Modifier.weight(1f).then(if (up != null) Modifier.clickable { actions.openCategory(up.category) } else Modifier))
            Figure("Down most vs last wk", down?.category?.name ?: "None", down?.let { "\u2212" + Money.format(-it.change, wholeDollars = true) }, Kassa.colors.positive, 
                Modifier.weight(1f).then(if (down != null) Modifier.clickable { actions.openCategory(down.category) } else Modifier))
        }
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Figure(
                "Due this week", Money.format(dues.sumOf { it.snapshot.payable }, wholeDollars = true),
                if (dues.isEmpty()) "No cards due" else "${dues.size} ${if (dues.size == 1) "card" else "cards"} \u00b7 next ${dues.first().snapshot.due.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}",
                Kassa.colors.ink3, Modifier.weight(1f).clickable { actions.open(InsightPage.DUES) },
            )
            val big = bigPurchases(ledger, week = true)
            Figure(
                "Big purchases", big.firstOrNull()?.let { Money.format(it.categoryAmount, wholeDollars = true) } ?: "None yet",
                if (big.isEmpty()) null else "Top ${big.size}",
                Kassa.colors.ink3, Modifier.weight(1f).clickable { actions.open(InsightPage.BIG_WEEK) },
            )
        }
    }
}

@Composable
private fun LargestBuys(ledger: Ledger, actions: InsightActions) {
    val list = weekExpenses(ledger).sortedByDescending { it.categoryAmount }.take(4)
    if (list.isEmpty()) return
    Column {
        Head("Largest buys")
        list.forEachIndexed { i, t -> TransactionRow(t, ledger, showDivider = i < list.lastIndex, onClick = actions::edit, showDate = true) }
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
