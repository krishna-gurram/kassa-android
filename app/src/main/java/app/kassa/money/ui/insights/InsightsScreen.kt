package app.kassa.money.ui.insights

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kassa.money.domain.Budgets
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.Insights
import app.kassa.money.domain.Money
import app.kassa.money.domain.Patterns
import app.kassa.money.domain.Period
import app.kassa.money.domain.Spending
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.common.CategoryBadge
import app.kassa.money.ui.common.CategoryLines
import app.kassa.money.ui.common.FIT_MIN_HEIGHT
import app.kassa.money.ui.common.figureColor
import app.kassa.money.ui.common.swipeShift
import app.kassa.money.ui.common.monthLines
import app.kassa.money.ui.common.weekLines
import app.kassa.money.ui.common.TransactionRow
import app.kassa.money.ui.common.shortDate
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import app.kassa.money.ui.theme.scaledDp
import app.kassa.money.ui.today.BigMoney
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
    var week by rememberSaveable { mutableStateOf(false) }
    val monthName = ledger.period.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())

    // Swipe between this week and this month, the same as tapping the toggle.
    BoxWithConstraints(Modifier.fillMaxSize().swipeShift { d -> week = d > 0 }) {
        val wide = maxWidth >= 720.dp
        // Tall screens fit everything with no scrolling: the category list shows as many lines as there's room for.
        val fit = maxHeight >= FIT_MIN_HEIGHT
        val scroll: @Composable () -> Modifier = { if (fit) Modifier else Modifier.verticalScroll(rememberScrollState()) }
        val grow: ColumnScope.() -> Modifier = { if (fit) Modifier.weight(1f) else Modifier }
        val head: @Composable () -> Unit = {
            // The toggle shares the title row when there's room; on a narrow screen it sits under it, so the title stays whole.
            val toggle: @Composable (Modifier) -> Unit = { m -> Toggle(listOf(false to monthName, true to "This week"), week, m) { week = it } }
            app.kassa.money.ui.common.TabTop("Insights", Modifier.padding(bottom = if (wide) 12.dp else 0.dp)) { if (wide) toggle(Modifier.widthIn(min = 240.dp)) }
            if (!wide) Box(Modifier.padding(bottom = 12.dp)) { toggle(Modifier.fillMaxWidth()) }
        }
        if (wide) {
            Column(Modifier.fillMaxSize().padding(contentPadding)) {
                head()
                Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Column(Modifier.weight(1f).then(scroll()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (week) { WeekHero(ledger, actions); WeekCategories(ledger, actions, if (fit) null else 7, grow()) } else { MonthHero(ledger); BudgetList(ledger, actions, if (fit) null else 7, grow()) }
                    }
                    Box(Modifier.width(1.dp).fillMaxHeight().background(Kassa.colors.line))
                    Column(Modifier.weight(1f).then(scroll()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        if (week) { WeekTiles(ledger, actions); LargestBuys(ledger, actions, if (fit) null else 4, grow()) } else PatternTiles(ledger, actions)
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize().then(scroll()).padding(contentPadding), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                head()
                if (week) { WeekHero(ledger, actions); WeekCategories(ledger, actions, if (fit) null else 4, grow()); WeekTiles(ledger, actions) }
                else { MonthHero(ledger); BudgetList(ledger, actions, if (fit) null else 4, grow()); PatternTiles(ledger, actions) }
            }
        }
    }
}

/** Category lines: a fixed number, or with no [limit], as many as fit the height the column gives. */
@Composable
private fun FittedLines(lines: List<app.kassa.money.ui.common.CatLine>, limit: Int?, onOpen: (Category) -> Unit, modifier: Modifier) {
    if (limit != null) CategoryLines(lines.take(limit), onOpen)
    else app.kassa.money.ui.common.FitColumn(modifier) { CategoryLines(lines, onOpen) }
}

/* ---------- month ---------- */

internal fun dateOf(t: Transaction, ledger: Ledger): LocalDate = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate()

@Composable
private fun MonthHero(ledger: Ledger) {
    val p = ledger.period
    val spent = Patterns.spent(ledger.counted, p.start, ledger.today, ledger.zone)
    val prev = Period.startingIn(p.yearMonth.minusMonths(1), ledger.monthStartDay)
    val (from, to) = Patterns.samePointBefore(p.start, ledger.today, prev.start)
    val before = Patterns.spent(ledger.counted, from, to, ledger.zone)
    val hasBefore = ledger.counted.any { !dateOf(it, ledger).isAfter(to) && !dateOf(it, ledger).isBefore(from) }
    Hero("Spent in ${p.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())}", spent) {
        if (hasBefore) Compare("Up to ${shortDate(to)}", before)
    }
}

@Composable
private fun BudgetList(ledger: Ledger, actions: InsightActions, limit: Int?, modifier: Modifier = Modifier) {
    val lines = monthLines(ledger)
    val spent = lines.filter { it.spent > 0 }
    Column(modifier) {
        Head("By category", "All ${lines.size}") { actions.openSpending(true) }
        if (spent.isEmpty()) Text("No spending yet this month.", style = KassaType.body, color = Kassa.colors.ink2)
        FittedLines(spent, limit, actions::openCategory, Modifier.weight(1f, fill = false))
    }
}

@Composable
private fun PatternTiles(ledger: Ledger, actions: InsightActions) {
    val p = ledger.period
    val monthBiggest = remember(ledger) { bigPurchases(ledger, week = false) }
    val stores = remember(ledger) { Patterns.stores(ledger.counted, ledger.categoryById, p.start, ledger.today, ledger.zone) }
    val small = remember(ledger) { Patterns.smallBuys(ledger.counted, ledger.categoryById, p.start, ledger.today, ledger.zone) }
    val smallMonths = remember(ledger) { Patterns.smallBuysByMonth(ledger.counted, ledger.categoryById, ledger.today, ledger.monthStartDay, ledger.zone) }
    val creep = remember(ledger) { Patterns.creeping(ledger.counted, ledger.categories, ledger.today, ledger.monthStartDay, ledger.zone) }
    val repeating = remember(ledger) { ledger.repeats.filter { it.kind == app.kassa.money.domain.TxKind.EXPENSE } }
    val weekdays = remember(ledger) { Insights.weekdayAverages(ledger.counted, ledger.categories, ledger.today, ledger.zone) }
    val busiest = weekdays.maxByOrNull { it.second }
    val changed = repeating.count { it.change != 0L }

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
                PatternTile("Repeating", Money.format(repeating.sumOf { it.perMonth }, wholeDollars = true), if (changed > 0) "$changed changed" else "${repeating.size} charges",
                    subColor = if (changed > 0) Kassa.colors.attention else null, modifier = Modifier.weight(1f), onClick = { actions.open(InsightPage.REPEATING) }) {
                    RepeatLines(repeating.take(4).map { r -> Spending.topOf(r.categoryId, ledger.categoryById)?.let { c -> Color(c.color) } ?: Kassa.colors.ink3 })
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
    val days = Patterns.daysThisWeek(ledger.counted, ledger.today, ledger.zone, ledger.firstDayOfWeek)
    val spent = days.sumOf { it.second ?: 0L }
    val (from, to) = Patterns.samePointBefore(monday, ledger.today, monday.minusWeeks(1))
    val before = Patterns.spent(ledger.counted, from, to, ledger.zone)
    val max = (days.maxOfOrNull { it.second ?: 0L } ?: 0L).coerceAtLeast(1L)
    Hero("Spent this week", spent) {
        Compare("Last week by ${ledger.today.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.getDefault())}", before)
        Row(Modifier.fillMaxWidth().height(scaledDp(84.dp)).padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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
private fun WeekCategories(ledger: Ledger, actions: InsightActions, limit: Int?, modifier: Modifier = Modifier) {
    val lines = weekLines(ledger)
    val spent = lines.filter { it.spent > 0 }
    Column(modifier) {
        Head("By category", "All ${lines.size}") { actions.openSpending(false) }
        if (spent.isEmpty()) Text("Nothing spent yet this week.", style = KassaType.body, color = Kassa.colors.ink2)
        FittedLines(spent, limit, actions::openCategory, Modifier.weight(1f, fill = false))
    }
}

/** Card payments due from today through Sunday, soonest first. */
internal fun duesThisWeek(ledger: Ledger) = ledger.cards()
    .filter { it.snapshot.payable > 0 && !it.snapshot.due.isBefore(ledger.today) && !it.snapshot.due.isAfter(ledger.weekStart.plusDays(6)) }
    .sortedBy { it.snapshot.due }

/** The 10 largest expenses this week or this budget month, largest first (fewer if there aren't 10). */
internal fun bigPurchases(ledger: Ledger, week: Boolean): List<Transaction> {
    val from = if (week) ledger.weekStart else ledger.period.start
    return ledger.counted.filter { it.kind == TxKind.EXPENSE && !dateOf(it, ledger).isBefore(from) && !dateOf(it, ledger).isAfter(ledger.today) }
        .sortedByDescending { it.categoryAmount }.take(10)
}

private fun weekExpenses(ledger: Ledger): List<Transaction> {
    val monday = ledger.weekStart
    return ledger.counted.filter { it.kind == TxKind.EXPENSE && !dateOf(it, ledger).isBefore(monday) && !dateOf(it, ledger).isAfter(ledger.today) }
}

/** Up and down most against last week, what's due before Sunday, and the biggest purchase: four tiles, two rows. */
@Composable
private fun WeekTiles(ledger: Ledger, actions: InsightActions) {
    val changes = Patterns.weekChanges(ledger.counted, ledger.categories, ledger.today, ledger.zone, ledger.firstDayOfWeek)
    val up = changes.filter { it.change > 0 }.maxByOrNull { it.change }
    val down = changes.filter { it.change < 0 }.minByOrNull { it.change }
    val dues = duesThisWeek(ledger)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Figure("Up most vs last wk", up?.category?.name ?: "None", up?.let { (if (Money.isHidden) "" else "↑ ") + Money.format(it.change, wholeDollars = true) }, figureColor(Kassa.colors.attention),
                Modifier.weight(1f).then(if (up != null) Modifier.clickable { actions.openCategory(up.category) } else Modifier))
            Figure("Down most vs last wk", down?.category?.name ?: "None", down?.let { (if (Money.isHidden) "" else "↓ ") + Money.format(-it.change, wholeDollars = true) }, figureColor(Kassa.colors.positive),
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
private fun LargestBuys(ledger: Ledger, actions: InsightActions, limit: Int?, modifier: Modifier = Modifier) {
    val list = weekExpenses(ledger).sortedByDescending { it.categoryAmount }.take(limit ?: 10)
    if (list.isEmpty()) return
    Column(modifier) {
        Head("Largest buys")
        if (limit != null) list.forEachIndexed { i, t -> TransactionRow(t, ledger, showDivider = i < list.lastIndex, onClick = actions::edit, showDate = true) }
        else app.kassa.money.ui.common.FitColumn(Modifier.weight(1f, fill = false)) { list.forEach { t -> TransactionRow(t, ledger, onClick = actions::edit, showDate = true) } }
    }
}

/* ---------- pieces ---------- */

@Composable
private fun Hero(label: String, amount: Long, extra: @Composable ColumnScope.() -> Unit) {
    app.kassa.money.ui.common.TonalHero(app.kassa.money.ui.common.HeroTone.GREY) {
        app.kassa.money.ui.common.HeroLabel(label)
        BigMoney(amount, sizeSp = 40f)
        extra()
    }
}

@Composable
private fun Compare(label: String, amount: Long) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = KassaType.small, color = app.kassa.money.ui.common.heroInk())
        Text("  " + Money.format(amount, wholeDollars = true), style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink)
    }
}

/** A section title with an optional link on the right: the shared section header, so Insights reads like every other list. */
@Composable
internal fun Head(title: String, link: String? = null, onLink: () -> Unit = {}) {
    app.kassa.money.ui.common.SectionHeader(title, link, onClick = if (link != null) onLink else null)
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

/** A two-way segmented control: an ink pill on a tinted track, each segment at least 44dp tall. */
@Composable
private fun Toggle(options: List<Pair<Boolean, String>>, value: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
    Row(modifier.clip(RoundedCornerShape(50)).background(Kassa.colors.chip).padding(3.dp)) {
        options.forEach { (v, label) ->
            val on = v == value
            Box(
                Modifier.weight(1f).heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(if (on) Kassa.colors.ink else Color.Transparent)
                    .clickable(role = Role.Tab) { onChange(v) }.padding(horizontal = 12.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = if (on) Kassa.colors.onInk else Kassa.colors.ink2, maxLines = 1, softWrap = false)
            }
        }
    }
}
