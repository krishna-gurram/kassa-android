package app.kassa.money.ui.today

import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material.icons.outlined.People
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Money
import app.kassa.money.domain.NumberText
import app.kassa.money.domain.Spending
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.domain.WeekPace
import app.kassa.money.ui.common.monthYear
import app.kassa.money.ui.common.shortDate
import app.kassa.money.ui.Ledger
import app.kassa.money.ui.cards.cardsByDueDate
import app.kassa.money.ui.common.EntryRow
import app.kassa.money.ui.common.Gap
import app.kassa.money.ui.common.IconBadge
import app.kassa.money.ui.common.ValueRow
import app.kassa.money.ui.common.accountIcon
import app.kassa.money.ui.common.PageTop
import app.kassa.money.ui.common.RaisedPanel
import app.kassa.money.ui.common.SectionHeader
import app.kassa.money.ui.common.TransactionRow
import app.kassa.money.ui.common.plural
import app.kassa.money.ui.common.swipeShift
import app.kassa.money.ui.theme.Kassa
import app.kassa.money.ui.theme.KassaType
import java.time.Instant
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.time.temporal.WeekFields
import java.util.Locale
import app.kassa.money.ui.theme.scaledSp

/** Account types that count as cash and bank on Today. */
val CASH_TYPES = setOf(AccountType.CHECKING, AccountType.SAVINGS, AccountType.CASH, AccountType.PREPAID)

fun weekPace(ledger: Ledger): WeekPace.Result =
    WeekPace.compute(ledger.counted, ledger.today, ledger.firstDayOfWeek, ledger.zone)

/** How many things are waiting to be confirmed: bank alerts, investment transfers and bills due. */
/** A card payment or a recurring bill coming up. [accountId] is set for card payments. */
data class Upcoming(val date: java.time.LocalDate, val name: String, val kind: String, val amount: Long?, val accountId: Long?)

fun upcoming(ledger: Ledger, days: Long): List<Upcoming> {
    val until = ledger.today.plusDays(days)
    val cards = ledger.cards().filter { it.snapshot.payable > 0 && !it.snapshot.due.isAfter(until) }
        .map { Upcoming(it.snapshot.due, it.account.name, "Card payment", it.snapshot.payable, it.account.id) }
    val bills = ledger.bills.mapNotNull { r ->
        app.kassa.money.domain.RecurringRules.upcoming(r, ledger.today, days)?.let { d ->
            val est = app.kassa.money.domain.RecurringRules.estimate(r, ledger.recent, d, ledger.zone)
            Upcoming(d, r.name, if (r.fixedAmount == null) "Bill, estimated" else "Bill", est, null)
        }
    }
    return (cards + bills).sortedBy { it.date }
}

/**
 * The Overview: spending this week or this month with its top three categories, what's coming up, and the
 * pinned account tiles. The same pieces on every screen; unfolded, they sit in two columns.
 */
@Composable
fun TodayScreen(
    ledger: Ledger,
    contentPadding: PaddingValues,
    onOpenCard: (Long) -> Unit,
    onSpending: (month: Boolean, income: Boolean) -> Unit,
    onOpenCategory: (Long) -> Unit,
    onUpcoming: () -> Unit,
    onNotifications: () -> Unit,
    onAccounts: () -> Unit,
    onOpenAccount: (Long) -> Unit,
    onPinAccounts: () -> Unit,
) {
    // The month comes first: it's what budgets and statements are measured in.
    var month by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(true) }
    var income by androidx.compose.runtime.saveable.rememberSaveable { androidx.compose.runtime.mutableStateOf(false) }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 720.dp
        // The unfolded inner screen is wide and tall enough for a larger total.
        val roomy = wide && maxHeight >= 560.dp
        val waiting = remember(ledger) { notices(ledger) }
        val banner: @Composable () -> Unit = {
            TipCard(Modifier.padding(bottom = 10.dp))
            NotificationsRow(waiting, onNotifications, Modifier.padding(bottom = 10.dp))
        }
        val spending: @Composable () -> Unit = {
            // Swipe the spending card between this week and this month, the same as its toggle.
            // The month is on the left of the toggle, so a swipe back shows it.
            Column(Modifier.swipeShift { d -> month = d < 0 }) {
                FlowSection(ledger, income, { income = it }, month, { month = it }, roomy, onOpen = { onSpending(month, income) }, onOpenCategory = onOpenCategory)
            }
        }
        val most = if (wide) 5 else 3
        val worth: @Composable () -> Unit = { AccountTiles(ledger, onOpenAccount, onPeople = onAccounts, onAll = onAccounts, onPin = onPinAccounts) }

        // One layout, three ways to run it. PROBE measures with a single Upcoming row; FIT fills the screen with no
        // scrolling, Upcoming showing as many rows (up to [most]) as the space left allows; SCROLL is the last resort
        // when even one row doesn't fit (very large text, a small phone, landscape), so nothing is ever cut off.
        val page: @Composable (Fit) -> Unit = { mode ->
            val coming: @Composable (Modifier) -> Unit = { m ->
                UpcomingSection(ledger, if (mode == Fit.PROBE) 1 else most, fill = mode == Fit.FIT, onOpenCard, onUpcoming, m)
            }
            val outer = when (mode) {
                Fit.FIT -> Modifier.fillMaxSize()
                Fit.SCROLL -> Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                Fit.PROBE -> Modifier
            }
            Column(outer.padding(contentPadding)) {
                app.kassa.money.ui.common.TabTop("Overview")
                if (wide) {
                    banner()
                    Row(if (mode == Fit.FIT) Modifier.weight(1f, fill = false) else Modifier, horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(if (roomy) 24.dp else 14.dp)) {
                            spending(); coming(if (mode == Fit.FIT) Modifier.weight(1f, fill = false) else Modifier)
                        }
                        Column(Modifier.weight(1f)) { worth() }
                    }
                } else {
                    Column(Modifier.widthIn(max = 640.dp).then(if (mode == Fit.FIT) Modifier.weight(1f, fill = false) else Modifier), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Column { banner(); spending() }
                        coming(if (mode == Fit.FIT) Modifier.weight(1f, fill = false) else Modifier); worth()
                    }
                }
            }
        }
        FitOrScroll(page)
    }
}

private enum class Fit { PROBE, FIT, SCROLL }

/**
 * Measures [page] with one Upcoming row; if that fits the screen, shows the page filled to fit with no scrolling,
 * otherwise as a scrolling page.
 */
@Composable
private fun FitOrScroll(page: @Composable (Fit) -> Unit) {
    androidx.compose.ui.layout.SubcomposeLayout(Modifier.fillMaxSize()) { constraints ->
        val loose = constraints.copy(minHeight = 0, maxHeight = androidx.compose.ui.unit.Constraints.Infinity)
        val need = subcompose(Fit.PROBE) { page(Fit.PROBE) }.maxOfOrNull { it.measure(loose).height } ?: 0
        val mode = if (need <= constraints.maxHeight) Fit.FIT else Fit.SCROLL
        val placeables = subcompose(mode) { page(mode) }.map { it.measure(constraints) }
        layout(constraints.maxWidth, constraints.maxHeight) { placeables.forEach { it.place(0, 0) } }
    }
}

@Composable
internal fun LinkText(text: String, onClick: () -> Unit) {
    Row(Modifier.clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 4.dp, horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = Kassa.colors.ink2)
        androidx.compose.material3.Icon(app.kassa.money.ui.activity.Chevron(left = false), null, tint = Kassa.colors.ink3, modifier = Modifier.size(18.dp))
    }
}

/** Dollars large, cents smaller and lighter on the same baseline, the way bank apps show a headline figure. */
@Composable
fun BigMoney(cents: Long, sizeSp: Float = 38f, color: Color = Kassa.colors.ink) {
    val ink = Kassa.colors.ink
    val centsColor = if (color == ink) Kassa.colors.ink2 else color
    val text = Money.format(cents)
    // Hidden amounts are a mask with no decimal point, so there are no cents to set apart.
    val dot = text.lastIndexOf(NumberText.decimalSeparator())
    val centsSize = scaledSp(sizeSp * (if (sizeSp < 24f) 0.8f else 0.5f))
    Text(
        buildAnnotatedString {
            if (dot < 0) append(text)
            else {
                append(text.substring(0, dot))
                // Cents sit on the same baseline and smaller, so the dollars read first. A colored total (income) keeps
                // its color for the cents too; a plain one shows them a shade lighter.
                withStyle(SpanStyle(fontSize = centsSize, color = centsColor, fontWeight = FontWeight.SemiBold)) {
                    append(text.substring(dot))
                }
            }
        },
        style = KassaType.hero.copy(fontSize = scaledSp(sizeSp), letterSpacing = (-sizeSp / 32f).sp),
        color = color, maxLines = 1, softWrap = false,
    )
}

/**
 * Spending or income, this week or this month, in one tonal hero: the two titles switch between spending and income,
 * a small menu picks the period, then the total with a chevron to the full breakdown. The top three categories sit just
 * under it as tinted tiles; a tile opens its category. The hero turns grey while balances are hidden.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun FlowSection(ledger: Ledger, income: Boolean, setIncome: (Boolean) -> Unit, month: Boolean, setMonth: (Boolean) -> Unit, roomy: Boolean, onOpen: () -> Unit, onOpenCategory: (Long) -> Unit) {
    val from = if (month) ledger.period.start else weekPace(ledger).start
    val kind = if (income) TxKind.INCOME else TxKind.EXPENSE
    val rows = spendingByCategory(ledger, from, kind)
    val total = rows.sumOf { it.second }
    val top = rows.take(3)
    val monthName = ledger.period.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    var menu by remember { androidx.compose.runtime.mutableStateOf(false) }

    app.kassa.money.ui.common.TonalHero(app.kassa.money.ui.common.HeroTone.GREEN) {
        // Titles on the left, the period menu on the right; on a narrow screen or a large font the menu moves under the titles.
        androidx.compose.foundation.layout.FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.align(Alignment.CenterVertically)) {
                listOf(false to "Spending", true to "Income").forEach { (v, label) ->
                    Text(
                        label, style = KassaType.section, maxLines = 1, softWrap = false,
                        color = if (v == income) Kassa.colors.ink else app.kassa.money.ui.common.heroInk(),
                        modifier = Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(role = androidx.compose.ui.semantics.Role.Tab) { setIncome(v) }
                            .padding(end = 14.dp).wrapContentHeight(Alignment.CenterVertically),
                    )
                }
            }
            Box(Modifier.align(Alignment.CenterVertically)) {
                Row(
                    Modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(8.dp)).clickable(onClickLabel = "Choose period", role = androidx.compose.ui.semantics.Role.Button) { menu = true },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(if (month) monthName else "This week", style = KassaType.small.copy(fontWeight = FontWeight.SemiBold), color = app.kassa.money.ui.common.heroInk(), maxLines = 1)
                    androidx.compose.material3.Icon(app.kassa.money.ui.activity.ChevronDown(), null, tint = app.kassa.money.ui.common.heroInk(), modifier = Modifier.size(18.dp))
                }
                androidx.compose.material3.DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    androidx.compose.material3.DropdownMenuItem(text = { Text(monthName, style = KassaType.body) }, onClick = { setMonth(true); menu = false })
                    androidx.compose.material3.DropdownMenuItem(text = { Text("This week", style = KassaType.body) }, onClick = { setMonth(false); menu = false })
                }
            }
        }
        val openLabel = if (income) "Where it came from" else "Where it went"
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f)) {
                BigMoney(total, sizeSp = if (roomy) 46f else 40f, color = app.kassa.money.ui.common.figureColor(if (income && total > 0) Kassa.colors.positive else Kassa.colors.ink))
            }
            androidx.compose.material3.IconButton(onClick = onOpen, modifier = Modifier.size(48.dp)) {
                androidx.compose.material3.Icon(app.kassa.money.ui.activity.Chevron(left = false), openLabel, tint = app.kassa.money.ui.common.heroInk())
            }
        }
        if (top.isEmpty()) Text(if (income) "No income yet." else "No spending yet.", style = KassaType.body, color = app.kassa.money.ui.common.heroInk(), modifier = Modifier.padding(top = 4.dp, bottom = 4.dp))
    }
    if (top.isNotEmpty()) TopTiles(top, onOpenCategory, onOpen)
}

/**
 * The top three categories as tinted tiles just under the hero, each as wide as its share of the three (with a floor
 * so a small one still shows its name and amount): a stripe of the category's color, its icon, name and amount. Two
 * categories may share a color; the tiles keep them apart. Spending and income use the same tiles.
 */
@Composable
private fun TopTiles(top: List<Pair<app.kassa.money.domain.Category?, Long>>, onOpenCategory: (Long) -> Unit, onOpen: () -> Unit) {
    val sum = top.sumOf { it.second }.coerceAtLeast(1)
    val none = Kassa.colors.ink3
    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp).height(androidx.compose.foundation.layout.IntrinsicSize.Min),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        top.forEach { (c, amt) ->
            val color = c?.let { Color(it.color) } ?: none
            val name = c?.name ?: "Uncategorized"
            Row(
                Modifier.weight((amt.toFloat() / sum).coerceAtLeast(0.3f)).fillMaxHeight().heightIn(min = 48.dp).clip(RoundedCornerShape(10.dp))
                    .background(color.copy(alpha = 0.18f)).clickable { if (c != null) onOpenCategory(c.id) else onOpen() },
            ) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(color))
                Column(Modifier.padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        app.kassa.money.ui.common.CategoryIconView(c?.icon, color, 30, fallback = name.take(1))
                        Text(name, style = KassaType.tiny, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
                    }
                    Text(Money.format(amt, wholeDollars = true), style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun UpcomingSection(ledger: Ledger, show: Int, fill: Boolean, onOpenCard: (Long) -> Unit, onAll: () -> Unit, modifier: Modifier = Modifier) {
    val items = upcoming(ledger, 30).take(show)
    Column(modifier) {
        SectionHeader("Upcoming", "Next 30 days", onClick = onAll)
        if (items.isEmpty()) Text("Nothing due in the next 30 days.", style = KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(vertical = 12.dp))
        // Filling the screen, the rows that don't fit whole are left out; Next 30 days lists them all.
        else if (fill) app.kassa.money.ui.common.FitColumn(Modifier.weight(1f, fill = false)) { items.forEach { u -> UpcomingRow(u, ledger, onOpenCard) } }
        else items.forEach { u -> UpcomingRow(u, ledger, onOpenCard) }
    }
}

@Composable
fun UpcomingRow(u: Upcoming, ledger: Ledger, onOpenCard: (Long) -> Unit) {
    // A card payment is money you owe, so its amount is orange (plain ink while balances are hidden).
    Column(if (u.accountId != null) Modifier.clickable { onOpenCard(u.accountId) } else Modifier) {
        HorizontalDivider(color = Kassa.colors.line)
        Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(u.name, style = KassaType.bodyStrong, color = Kassa.colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(shortDate(u.date) + " \u00b7 " + u.kind, style = KassaType.small, color = Kassa.colors.ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(
                u.amount?.let { Money.format(it) } ?: "", style = KassaType.bodyStrong, maxLines = 1,
                color = if (u.accountId != null) app.kassa.money.ui.common.figureColor(Kassa.colors.attention) else Kassa.colors.ink,
                modifier = Modifier.padding(start = 8.dp),
            )
            if (u.accountId != null) androidx.compose.material3.Icon(app.kassa.money.ui.activity.Chevron(left = false), null, tint = Kassa.colors.ink3, modifier = Modifier.padding(start = 4.dp).size(18.dp))
        }
    }
}

/** Every card payment and bill in the next 30 days, by date. */
@Composable
fun UpcomingPage(ledger: Ledger, contentPadding: PaddingValues, onBack: (() -> Unit)?, onOpenCard: (Long) -> Unit) {
    val items = upcoming(ledger, 30)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp)) {
        PageTop("Next 30 days", onBack)
        app.kassa.money.ui.common.TonalHero(app.kassa.money.ui.common.HeroTone.ORANGE) {
            app.kassa.money.ui.common.HeroLabel("Total due")
            BigMoney(items.sumOf { it.amount ?: 0 }, sizeSp = 40f)
            app.kassa.money.ui.common.HeroLabel(plural(items.size, "payment"))
        }
        Gap(12)
        if (items.isEmpty()) Text("Nothing due in the next 30 days.", style = KassaType.body, color = Kassa.colors.ink2)
        items.forEach { UpcomingRow(it, ledger, onOpenCard) }
        Gap(24)
    }
}

/** Expenses from [from] through today, by top category, largest first. */
private fun spendingByCategory(ledger: Ledger, from: java.time.LocalDate, kind: TxKind = TxKind.EXPENSE): List<Pair<app.kassa.money.domain.Category?, Long>> =
    ledger.counted.filter { it.kind == kind && dateOf(it, ledger).let { d -> !d.isBefore(from) && !d.isAfter(ledger.today) } }
        .groupBy { Spending.topOf(it.categoryId, ledger.categoryById) }
        .map { (c, l) -> c to l.sumOf { it.categoryAmount } }.sortedByDescending { it.second }

/**
 * The one page for a month's or a week's spending (or income): the total and every category (the same lines Insights
 * uses). It opens on the current month or week; ‹ › or a swipe steps through earlier ones, loaded as needed.
 */
@Composable
fun SpendingPage(
    ledger: Ledger,
    month: Boolean,
    income: Boolean,
    contentPadding: PaddingValues,
    onBack: (() -> Unit)?,
    load: (Long, Long) -> kotlinx.coroutines.flow.Flow<List<Transaction>>,
    /** A category and how many months back the page was showing, so it opens on the same month. */
    onOpenCategory: (Long, Int) -> Unit,
) {
    var back by androidx.compose.runtime.saveable.rememberSaveable(month) { androidx.compose.runtime.mutableIntStateOf(0) }
    val period = app.kassa.money.domain.Period.startingIn(ledger.period.yearMonth.minusMonths(back.toLong()), ledger.monthStartDay)
    val from = if (month) period.start else ledger.weekStart.minusWeeks(back.toLong())
    val to = minOf(if (month) period.endInclusive else from.plusDays(6), ledger.today)
    // The week before is loaded too, for the weekly comparison.
    val loadFrom = if (month) from else from.minusWeeks(1)
    val loaded by remember(loadFrom, to) {
        load(loadFrom.atStartOfDay(ledger.zone).toInstant().toEpochMilli(), to.plusDays(1).atStartOfDay(ledger.zone).toInstant().toEpochMilli() - 1)
    }.collectAsState(initial = null)
    val txs = loaded ?: ledger.counted
    val kind = if (income) TxKind.INCOME else TxKind.EXPENSE
    val entries = txs.filter { it.kind == kind && dateOf(it, ledger).let { d -> !d.isBefore(from) && !d.isAfter(to) } }
    val lines = when {
        income -> app.kassa.money.ui.common.incomeLines(ledger, from, to, txs)
        month -> app.kassa.money.ui.common.monthLines(ledger, period, txs)
        else -> app.kassa.money.ui.common.weekLines(ledger, from, txs)
    }
    val total = entries.sumOf { it.categoryAmount }
    val what = if (income) "income" else "spending"
    val monthName = period.yearMonth.month.getDisplayName(TextStyle.FULL, Locale.getDefault())
    val title = when {
        month -> "$monthName $what"
        back == 0 -> "This week's $what"
        else -> "Week of ${shortDate(from)}"
    }
    val label = if (month) monthYear(period.yearMonth) else "${shortDate(from)} to ${shortDate(from.plusDays(6))}"
    val span = "${shortDate(from)} to " + if (to == ledger.today) "today" else shortDate(to)

    Column(
        Modifier.fillMaxSize().swipeShift { d -> if (d < 0) back++ else if (back > 0) back-- }
            .verticalScroll(rememberScrollState()).padding(contentPadding).widthIn(max = 720.dp),
    ) {
        PageTop(title, onBack) {
            if (back > 0) androidx.compose.material3.TextButton(onClick = { back = 0 }) {
                Text(if (month) "This month" else "This week", style = KassaType.bodyStrong, color = Kassa.colors.ink)
            }
        }
        app.kassa.money.ui.common.PeriodSwitcher(label, sub = null, canBack = true, canForward = back > 0, onBack = { back++ }, onForward = { if (back > 0) back-- })
        app.kassa.money.ui.common.TonalHero(app.kassa.money.ui.common.HeroTone.GREEN) {
            Row(verticalAlignment = Alignment.Bottom) {
                app.kassa.money.ui.common.HeroLabel(if (income) "Received" else "Spent", Modifier.weight(1f))
                app.kassa.money.ui.common.HeroLabel(span)
            }
            BigMoney(total, sizeSp = 40f, color = app.kassa.money.ui.common.figureColor(if (income && total > 0) Kassa.colors.positive else Kassa.colors.ink))
            app.kassa.money.ui.common.HeroLabel(plural(entries.size, if (income) "entry" else "expense", if (income) "entries" else "expenses"))
        }
        SectionHeader("By category")
        // A category opens on the month shown here; from a week, on the month that week starts in.
        val categoryBack = if (month) back else java.time.temporal.ChronoUnit.MONTHS.between(
            app.kassa.money.domain.Period.containing(from, ledger.monthStartDay).yearMonth, ledger.period.yearMonth,
        ).toInt()
        app.kassa.money.ui.common.CategoryLinesAll(lines, if (income) "Nothing received" else "No spending") { onOpenCategory(it.id, categoryBack) }
        Gap(24)
    }
}

private fun dateOf(t: Transaction, ledger: Ledger) = Instant.ofEpochMilli(t.epochMillis).atZone(ledger.zone).toLocalDate()

