package app.kassa.money.shared

import app.kassa.money.domain.Account
import app.kassa.money.domain.BudgetRules
import app.kassa.money.domain.Budgets
import app.kassa.money.domain.Cadence
import app.kassa.money.domain.Category
import app.kassa.money.domain.CategoryKind
import app.kassa.money.domain.CountedSpending
import app.kassa.money.domain.EntryFilter
import app.kassa.money.domain.Insights
import app.kassa.money.domain.Money
import app.kassa.money.domain.Names
import app.kassa.money.domain.Patterns
import app.kassa.money.domain.Period
import app.kassa.money.domain.QueryWindows
import app.kassa.money.domain.Repeat
import app.kassa.money.domain.Repeats
import app.kassa.money.domain.SearchText
import app.kassa.money.domain.Spending
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.domain.WeekPace
import app.kassa.money.domain.YearMonth
import app.kassa.money.domain.dateOfMillis
import app.kassa.money.domain.isAfter
import app.kassa.money.domain.isBefore
import app.kassa.money.domain.minusMonths
import app.kassa.money.domain.minusWeeks
import app.kassa.money.domain.plusDays
import app.kassa.money.domain.toEpochDay
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlin.math.abs

/**
 * Gathers the read pages of batch J (see `ReadPages.kt`) from :domain's rules. Called only inside `LedgerView.shown`, so `Money` shows this
 * ledger's currency and Hide balances. Mirrors Android's `InsightsScreen`, `InsightPages`, `SpendingPage`, `DayPage`, `InsightStorePage`,
 * `Breakdown` and `ActivityViewModel`, in the same order of checks.
 */
internal class ReadViews(private val v: LedgerView) {

    private val parts get() = v.parts
    private val byId: Map<Long, Category> get() = v.categoryById

    // ---------- the entries, as Android's Ledger holds them ----------

    /** The recent window (130 days, like `Ledger.recent`) as spending sees it: `Ledger.counted`. */
    private fun counted(today: LocalDate, zone: TimeZone): List<Transaction> {
        val from = QueryWindows.recentFrom(today, zone)
        val recent = parts.transactions.filter { it.epochMillis >= from }
        return CountedSpending.apply(recent, parts.loanPayments, parts.loanTerms, parts.categories)
    }

    /** `Ledger.purchases`: [counted] without the entries of loan payments. */
    private fun purchases(counted: List<Transaction>): List<Transaction> = Patterns.purchases(counted, parts.loanPayments)

    private fun dayOf(t: Transaction, zone: TimeZone): LocalDate = dateOfMillis(t.epochMillis, zone)

    private fun inRange(t: Transaction, from: LocalDate, to: LocalDate, zone: TimeZone): Boolean {
        val d = dayOf(t, zone)
        return !d.isBefore(from) && !d.isAfter(to)
    }

    private fun dated(list: List<Transaction>, zone: TimeZone): List<DatedRowData> =
        list.map { DatedRowData(dayOf(it, zone).toEpochDay().toInt(), v.entryRow(it)) }

    private fun permille(value: Long, max: Long): Int =
        if (Money.isHidden || max <= 0L) 0 else (value.coerceAtLeast(0L) * 1000L / max).coerceIn(0L, 1000L).toInt()

    private fun whole(cents: Long): String = Money.format(cents, wholeDollars = true)

    private fun iconOf(c: Category?): Pair<String, String> = if (c == null) Pair("", "") else iconParts(c.icon)

    private fun monthKey(m: YearMonth): Int = m.year * 100 + m.month

    // ---------- Patterns ----------

    /** The 10 largest expenses from [from] to [to] among [purchases], largest first: `bigPurchases`. */
    private fun big(purchases: List<Transaction>, from: LocalDate, to: LocalDate, zone: TimeZone): List<Transaction> =
        purchases.filter { it.kind == TxKind.EXPENSE && inRange(it, from, to, zone) }.sortedByDescending { it.categoryAmount }.take(10)

    private fun bars(values: List<Long>): List<Int> {
        val max = (values.maxOrNull() ?: 0L).coerceAtLeast(1L)
        return values.map { permille(it, max) }
    }

    fun patterns(today: LocalDate, zone: TimeZone): PatternsData {
        val p = Period.containing(today, v.monthStartDay)
        val all = counted(today, zone)
        val purchases = purchases(all)
        val big = big(purchases, p.start, today, zone)
        val stores = Patterns.stores(purchases, byId, p.start, today, zone)
        val small = Patterns.smallBuys(purchases, byId, p.start, today, zone)
        val smallMonths = Patterns.smallBuysByMonth(purchases, byId, today, v.monthStartDay, zone)
        val creep = Patterns.creeping(all, parts.categories, today, v.monthStartDay, zone)
        val repeating = v.dueViews.repeats(today, zone).filter { it.kind == TxKind.EXPENSE }
        val weekdays = Insights.weekdayAverages(all, parts.categories, today, zone)
        val busiest = weekdays.maxByOrNull { it.second }
        val changed = repeating.count { it.change != 0L }
        val hidden = Money.isHidden
        val mask = Money.format(0L)

        fun hiddenTile(kind: String) = PatternTileData(kind, true, mask, 0, "", 0, "", false, emptyList(), -1, 0, emptyList())

        val tiles = ArrayList<PatternTileData>()
        tiles.add(
            if (hidden) hiddenTile("BIG") else PatternTileData(
                "BIG", big.isNotEmpty(), big.firstOrNull()?.let { whole(it.categoryAmount) } ?: "", 0,
                if (big.isEmpty()) "" else "TOP", big.size, "", false, bars(big.map { it.categoryAmount }), 0, 0, emptyList(),
            ),
        )
        val topStore = stores.firstOrNull()
        tiles.add(
            if (hidden) hiddenTile("STORE") else PatternTileData(
                "STORE", topStore != null, topStore?.name ?: "", 0, if (topStore == null) "" else "AMOUNT", 0,
                topStore?.let { whole(it.total) } ?: "", false, bars(stores.take(5).map { it.total }), 0, topStore?.category?.color ?: 0, emptyList(),
            ),
        )
        tiles.add(
            if (hidden) hiddenTile("SMALL") else PatternTileData(
                "SMALL", true, whole(small.total), 0, "BUYS", small.count, "", false, bars(smallMonths.map { it.second }), smallMonths.size - 1, 0, emptyList(),
            ),
        )
        val topCreep = creep.firstOrNull()
        tiles.add(
            if (hidden) hiddenTile("CREEP") else if (topCreep != null) PatternTileData(
                "CREEP", true, topCreep.category.name, 0, "MONTHS", topCreep.months.size, "", true,
                bars(topCreep.months.map { it.second }), topCreep.months.size - 1, 0, emptyList(),
            ) else PatternTileData("CREEP", false, "", 0, "STEADY", 0, "", false, listOf(1000, 1000, 1000), -1, 0, emptyList()),
        )
        tiles.add(
            if (hidden) hiddenTile("REPEAT") else PatternTileData(
                "REPEAT", true, whole(repeating.sumOf { it.perMonth }), 0, if (changed > 0) "CHANGED" else "CHARGES", if (changed > 0) changed else repeating.size, "",
                changed > 0, emptyList(), -1, 0,
                repeating.take(4).map { r -> Spending.topOf(r.categoryId, byId)?.color ?: 0 },
            ),
        )
        tiles.add(
            if (hidden) hiddenTile("BUSIEST") else PatternTileData(
                "BUSIEST", busiest != null, "", busiest?.first?.isoDayNumber ?: 0, if (busiest == null) "" else "AMOUNT", 0,
                busiest?.let { whole(it.second) } ?: "", false, bars(weekdays.map { it.second }), busiest?.let { weekdays.indexOf(it) } ?: -1, 0, emptyList(),
            ),
        )
        return PatternsData(tiles)
    }

    // ---------- Category lines: Android's Breakdown.kt ----------

    private class Line(val category: Category, val spent: Long, val ref: Long?, val refKind: String, val refAmount: Long)

    private fun tops(): List<Category> = parts.categories.filter { it.parentId == null }

    private fun sortLines(lines: List<Line>): List<Line> =
        lines.filter { !it.category.hidden || it.spent > 0L }
            .sortedWith(compareByDescending<Line> { it.spent }.thenBy { it.category.sortOrder })

    /** `monthLines`: every visible expense category of a budget month against that month's budget. */
    private fun monthLines(period: Period, txs: List<Transaction>, today: LocalDate, zone: TimeZone): List<Line> {
        val to = if (period.contains(today)) today else period.endInclusive
        val spent = Spending.byTopCategory(txs, byId, period.start, to, zone)
        return sortLines(
            tops().filter { it.kind == CategoryKind.EXPENSE }.map { c ->
                val b = Budgets.amountFor(c.id, period.yearMonth, parts.budgets)?.takeIf { it > 0L }
                Line(c, spent[c.id] ?: 0L, b, if (b != null) "BUDGET" else "", b ?: 0L)
            },
        )
    }

    /** `weekLines`: every visible expense category of the week starting [start] against the week before. */
    private fun weekLines(start: LocalDate, txs: List<Transaction>, today: LocalDate, zone: TimeZone): List<Line> {
        val end = minOf(start.plusDays(6), today)
        val (from, to) = Patterns.samePointBefore(start, end, start.minusWeeks(1))
        val now = Spending.byTopCategory(txs, byId, start, end, zone)
        val before = Spending.byTopCategory(txs, byId, from, to, zone)
        return sortLines(
            tops().filter { it.kind == CategoryKind.EXPENSE }.map { c ->
                val b = before[c.id] ?: 0L
                Line(c, now[c.id] ?: 0L, b.takeIf { it > 0L }, "LAST_WEEK", b)
            },
        )
    }

    /** `incomeLines`: every visible income category between two dates; nothing to measure against. */
    private fun incomeLines(from: LocalDate, to: LocalDate, txs: List<Transaction>, zone: TimeZone): List<Line> {
        val got = HashMap<Long, Long>()
        for (t in txs) {
            if (t.kind != TxKind.INCOME || !inRange(t, from, to, zone)) continue
            val c = Spending.topOf(t.categoryId, byId) ?: continue
            got[c.id] = (got[c.id] ?: 0L) + t.amount
        }
        return sortLines(tops().filter { it.kind == CategoryKind.INCOME }.map { Line(it, got[it.id] ?: 0L, null, "", 0L) })
    }

    private fun lineData(lines: List<Line>): List<CatLineData> {
        val max = (lines.maxOfOrNull { it.spent } ?: 0L).coerceAtLeast(1L)
        return lines.map { l ->
            val icon = iconParts(l.category.icon)
            val over = !Money.isHidden && l.ref != null && l.spent > l.ref
            val bar = permille(l.spent, l.ref?.coerceAtLeast(1L) ?: max)
            CatLineData(
                l.category.id, l.category.name, icon.first, icon.second, l.category.color, whole(l.spent), l.spent > 0L,
                l.refKind, if (l.refKind.isEmpty()) "" else whole(l.refAmount), bar, over,
            )
        }
    }

    // ---------- The week view ----------

    private fun change(c: Patterns.CategoryChange?, positive: Boolean): WeekChangeData {
        if (c == null) return WeekChangeData(false, 0L, "", "", false)
        return WeekChangeData(true, c.category.id, c.category.name, whole(if (positive) c.change else -c.change), !Money.isHidden)
    }

    /** Card payments due from today through the last day of the week, soonest first: `duesThisWeek`. */
    private fun cardsDue(today: LocalDate, weekStart: LocalDate, zone: TimeZone): List<Pair<Account, app.kassa.money.domain.CardSnapshot>> {
        val snaps = v.cardSnapshots(today.toEpochDay().toInt(), zone)
        val end = weekStart.plusDays(6)
        return v.accountById.values.filter { snaps.containsKey(it.id) }
            .map { it to snaps.getValue(it.id) }
            .filter { it.second.payable > 0L && !it.second.due.isBefore(today) && !it.second.due.isAfter(end) }
            .sortedBy { it.second.due }
    }

    fun weekView(today: LocalDate, zone: TimeZone, firstDay: DayOfWeek): WeekData {
        val all = counted(today, zone)
        val purchases = purchases(all)
        val monday = WeekPace.startOf(today, firstDay)
        val days = Patterns.daysThisWeek(all, today, zone, firstDay)
        val spent = days.sumOf { it.second ?: 0L }
        val (from, to) = Patterns.samePointBefore(monday, today, monday.minusWeeks(1))
        val before = Patterns.spent(all, from, to, zone)
        val max = (days.maxOfOrNull { it.second ?: 0L } ?: 0L).coerceAtLeast(1L)
        val changes = Patterns.weekChanges(all, parts.categories, today, zone, firstDay)
        val up = changes.filter { it.change > 0L }.maxByOrNull { it.change }
        val down = changes.filter { it.change < 0L }.minByOrNull { it.change }
        val due = cardsDue(today, monday, zone)
        val bigWeek = big(purchases, monday, today, zone)
        val weekExpenses = all.filter { it.kind == TxKind.EXPENSE && inRange(it, monday, today, zone) }
        return WeekData(
            startDay = monday.toEpochDay().toInt(),
            totalText = whole(spent),
            compareWeekday = today.dayOfWeek.isoDayNumber,
            compareText = whole(before),
            days = days.map { (date, value) ->
                WeekDayData(
                    date.toEpochDay().toInt(), date.dayOfWeek.isoDayNumber, date == today, value != null,
                    if (value == null) "" else whole(value), if (value == null) 0 else permille(value, max),
                )
            },
            lines = lineData(weekLines(monday, all, today, zone)),
            up = change(up, true),
            down = change(down, false),
            dueText = whole(due.sumOf { it.second.payable }),
            dueCount = due.size,
            dueFirstWeekday = due.firstOrNull()?.second?.due?.dayOfWeek?.isoDayNumber ?: 0,
            bigText = bigWeek.firstOrNull()?.let { whole(it.categoryAmount) } ?: "",
            bigCount = bigWeek.size,
            largest = dated(weekExpenses.sortedByDescending { it.categoryAmount }.take(10), zone),
        )
    }

    // ---------- The spending page ----------

    private fun monthsBetween(a: YearMonth, b: YearMonth): Int = (b.year - a.year) * 12 + (b.month - a.month)

    fun spendingPage(today: LocalDate, zone: TimeZone, firstDay: DayOfWeek, month: Boolean, income: Boolean, back: Int): SpendingPageData {
        val steps = back.coerceAtLeast(0)
        val current = Period.containing(today, v.monthStartDay)
        val period = Period.startingIn(current.yearMonth.minusMonths(steps), v.monthStartDay)
        val weekStart = WeekPace.startOf(today, firstDay)
        val from = if (month) period.start else weekStart.minusWeeks(steps)
        val to = minOf(if (month) period.endInclusive else from.plusDays(6), today)
        val loadFrom = if (month) from else from.minusWeeks(1)
        val txs = v.countedBetween(loadFrom, to, zone)
        val kind = if (income) TxKind.INCOME else TxKind.EXPENSE
        val entries = txs.filter { it.kind == kind && inRange(it, from, to, zone) }
        val lines = when {
            income -> incomeLines(from, to, txs, zone)
            month -> monthLines(period, txs, today, zone)
            else -> weekLines(from, txs, today, zone)
        }
        val total = entries.sumOf { it.categoryAmount }
        val labelMonth = if (month) period.yearMonth else YearMonth.from(from)
        val categoryBack = if (month) steps else monthsBetween(Period.containing(from, v.monthStartDay).yearMonth, current.yearMonth)
        return SpendingPageData(
            month = month, income = income, back = steps, canBack = v.hasEarlier(from, zone),
            fromDay = from.toEpochDay().toInt(), toDay = to.toEpochDay().toInt(), toIsToday = to == today,
            periodYear = labelMonth.year, periodMonth = labelMonth.month,
            totalText = Money.format(total), tone = if (income && total > 0L) v.figurePositive() else Tone.Ink,
            entryCount = entries.size, lines = lineData(lines), categoryBack = categoryBack.coerceAtLeast(0),
        )
    }

    // ---------- Day and store pages ----------

    fun dayPage(day: LocalDate, zone: TimeZone): DayPageData {
        val list = v.countedBetween(day, day, zone)
            .sortedWith(compareByDescending<Transaction> { it.epochMillis }.thenByDescending { it.id })
        val spent = list.filter { it.kind == TxKind.EXPENSE }.sumOf { it.categoryAmount }
        return DayPageData(day.toEpochDay().toInt(), Money.format(spent), list.map { v.entryRow(it) })
    }

    fun storePage(name: String, today: LocalDate, zone: TimeZone): StorePageData {
        val key = Repeats.key(name)
        val list = counted(today, zone).filter { it.kind == TxKind.EXPENSE && Repeats.key(it.note) == key }
            .sortedWith(compareByDescending<Transaction> { it.epochMillis }.thenByDescending { it.id })
        val period = Period.containing(today, v.monthStartDay)
        val month = list.filter { period.contains(dayOf(it, zone)) }
        val days = ArrayList<DayData>()
        for ((day, entries) in list.groupBy { dayOf(it, zone) }) {
            days.add(DayData(day.toEpochDay().toInt(), Money.format(entries.sumOf { it.categoryAmount }), entries.map { v.entryRow(it) }))
        }
        return StorePageData(name, Money.format(month.sumOf { it.categoryAmount }), month.size, days)
    }

    // ---------- The pages behind the tiles ----------

    private fun bar(kind: String, value: Int, cents: Long, max: Long, highlight: Boolean) =
        BarData(kind, value, Money.format(cents, wholeDollars = true), permille(cents, max), highlight)

    fun detail(kind: String, today: LocalDate, zone: TimeZone, firstDay: DayOfWeek): InsightDetailData {
        val p = Period.containing(today, v.monthStartDay)
        val all = counted(today, zone)
        val purchases = purchases(all)
        var figures: List<FigureData> = emptyList()
        var bars: List<BarData> = emptyList()
        var ranks: List<RankRowData> = emptyList()
        var creeps: List<CreepRowData> = emptyList()
        var repeats: List<RepeatGroupData> = emptyList()
        var weekdays: List<WeekdayRowData> = emptyList()
        var dues: List<DueRowData> = emptyList()
        var entries: List<DatedRowData> = emptyList()
        when (kind) {
            "BIG_MONTH" -> entries = dated(big(purchases, p.start, today, zone), zone)
            "BIG_WEEK" -> entries = dated(big(purchases, WeekPace.startOf(today, firstDay), today, zone), zone)
            "STORES" -> {
                val stores = Patterns.stores(purchases, byId, p.start, today, zone).take(15)
                val max = stores.firstOrNull()?.total ?: 1L
                ranks = stores.map { s ->
                    val icon = iconOf(s.category)
                    RankRowData(s.name, s.category?.id ?: 0L, s.name, icon.first, icon.second, s.category?.color ?: 0, whole(s.total), s.visits, permille(s.total, max))
                }
            }
            "SMALL" -> {
                val s = Patterns.smallBuys(purchases, byId, p.start, today, zone)
                val months = Patterns.smallBuysByMonth(purchases, byId, today, v.monthStartDay, zone)
                figures = listOf(
                    FigureData("THIS_MONTH", whole(s.total)), FigureData("BUYS", s.count.toString()),
                    FigureData("EACH_UNDER", whole(Patterns.SMALL_LIMIT)),
                )
                val max = (months.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
                bars = months.mapIndexed { i, m -> bar("MONTH", monthKey(m.first), m.second, max, i == months.lastIndex) }
                val top = s.byCategory.firstOrNull()?.second ?: 1L
                ranks = s.byCategory.map { (c, total, n) ->
                    val icon = iconOf(c)
                    RankRowData("", c.id, c.name, icon.first, icon.second, c.color, whole(total), n, permille(total, top))
                }
            }
            "CREEP" -> {
                creeps = Patterns.creeping(all, parts.categories, today, v.monthStartDay, zone).map { cr ->
                    val icon = iconOf(cr.category)
                    val max = (cr.months.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
                    CreepRowData(
                        cr.category.id, cr.category.name, icon.first, icon.second, cr.category.color, whole(cr.rise), monthKey(cr.months.first().first),
                        cr.months.mapIndexed { i, m -> bar("MONTH", monthKey(m.first), m.second, max, i == cr.months.lastIndex) },
                    )
                }
            }
            "REPEATING" -> {
                val found = v.dueViews.repeats(today, zone)
                val spent = found.filter { it.kind == TxKind.EXPENSE }
                figures = listOf(FigureData("A_MONTH", whole(spent.sumOf { it.perMonth })), FigureData("A_YEAR", whole(spent.sumOf { it.perYear })))
                repeats = Cadence.entries.mapNotNull { cadence ->
                    val list = found.filter { it.cadence == cadence }
                    if (list.isEmpty()) null else RepeatGroupData(cadence.name, cadence.label, list.map { repeatRow(it, today) })
                }
            }
            "WEEKDAYS" -> {
                val avg = Insights.weekdayAverages(all, parts.categories, today, zone)
                val top = avg.maxByOrNull { it.second }
                val max = (avg.maxOfOrNull { it.second } ?: 0L).coerceAtLeast(1L)
                bars = avg.map { (d, value) -> bar("WEEKDAY", d.isoDayNumber, value, max, top != null && d == top.first) }
                val from = today.minusWeeks(13)
                val byDay = all.filter { it.kind == TxKind.EXPENSE }
                    .map { it to dayOf(it, zone) }
                    .filter { (_, d) -> !d.isBefore(from) && d.isBefore(today) }
                    .groupBy({ it.second.dayOfWeek }, { it.first })
                weekdays = avg.sortedByDescending { it.second }.map { (day, value) ->
                    val list = byDay[day].orEmpty()
                    val topCat = list.groupBy { Spending.topOf(it.categoryId, byId) }.maxByOrNull { e -> e.value.sumOf { it.categoryAmount } }?.key
                    val icon = iconOf(topCat)
                    WeekdayRowData(day.isoDayNumber, topCat?.id ?: 0L, topCat?.name ?: "", icon.first, icon.second, topCat?.color ?: 0, list.size, whole(value))
                }
            }
            "DUES" -> {
                val due = cardsDue(today, WeekPace.startOf(today, firstDay), zone)
                figures = listOf(FigureData("TOTAL", Money.format(due.sumOf { it.second.payable })), FigureData("CARDS", due.size.toString()))
                dues = due.map { (a, s) ->
                    DueRowData(a.id, a.name, a.last4 ?: "", s.due.toEpochDay().toInt(), s.due.dayOfWeek.isoDayNumber, Money.format(s.payable))
                }
            }
        }
        return InsightDetailData(kind, figures, bars, ranks, creeps, repeats, weekdays, dues, entries)
    }

    private fun repeatRow(r: Repeat, today: LocalDate): RepeatRowData {
        val income = r.kind == TxKind.INCOME
        val cat = Spending.topOf(r.categoryId, byId)
        val icon = iconOf(cat)
        val tag: String
        var tagText = ""
        var arrow = 0
        var tagTone = Tone.Ink
        when {
            r.change != 0L -> {
                tag = "CHANGE"
                tagText = Money.format(abs(r.change))
                arrow = if (Money.isHidden) 0 else if (r.change > 0L) 1 else -1
                // A cheaper charge is good news and a dearer income too; a minus always means against you.
                val favour = if (income) r.change else -r.change
                tagTone = if (Money.isHidden) Tone.Ink else if (favour > 0L) Tone.Green else Tone.Orange
            }
            !r.fixed -> tag = "VARIES"
            else -> tag = ""
        }
        return RepeatRowData(
            name = r.name, categoryId = cat?.id ?: 0L, iconKind = icon.first, iconValue = icon.second, color = cat?.color ?: 0,
            income = income, nextDay = r.next(today).toEpochDay().toInt(), fixed = r.fixed,
            lowText = whole(r.low), highText = whole(r.high), tag = tag, tagText = tagText, tagArrow = arrow, tagTone = tagTone,
            typicalText = Money.format(r.typical), typicalTone = if (income && !Money.isHidden) Tone.Green else Tone.Ink,
            perYearText = whole(r.perYear),
        )
    }

    // ---------- Budgets ----------

    private fun budgetCategories(): List<Category> =
        parts.categories.filter { it.kind == CategoryKind.EXPENSE && it.parentId == null && !it.hidden }.sortedWith(Names.order { c: Category -> c.name })

    fun budgetsPage(today: LocalDate, zone: TimeZone): BudgetsData {
        val period = Period.containing(today, v.monthStartDay)
        val month = period.yearMonth
        val spent = Spending.byTopCategory(v.countedBetween(period.start, minOf(period.endInclusive, today), zone), byId, period.start, period.endInclusive, zone)
        val cats = budgetCategories()
        val rows = cats.map { c ->
            val line = BudgetRules.lineFor(c.id, month, parts.budgets, spent[c.id] ?: 0L)
            val icon = iconParts(c.icon)
            val hidden = Money.isHidden
            val budget = line.budget
            val every = BudgetRules.everyMonth(c.id, parts.budgets)
            val override = BudgetRules.forMonth(c.id, month, parts.budgets)
            val statusKind = if (hidden || budget == null) "" else if (line.over) "OVER" else "LEFT"
            BudgetRowData(
                categoryId = c.id, name = c.name, iconKind = icon.first, iconValue = icon.second, color = c.color,
                hasBudget = budget != null, budgetText = if (budget == null) "" else Money.format(budget),
                everyText = if (every == null) "" else Money.format(every), monthText = if (override == null) "" else Money.format(override),
                spentText = Money.format(line.spent), over = !hidden && line.over, barPermille = if (hidden) 0 else line.usedPermille,
                statusKind = statusKind, statusText = if (statusKind.isEmpty()) "" else Money.format(abs(line.left)),
            )
        }
        val total = BudgetRules.total(cats.map { it.id }, month, parts.budgets)
        return BudgetsData(month.year, month.month, Money.format(total), rows.any { it.hasBudget }, rows)
    }

    fun budgetEdit(categoryId: Long, today: LocalDate): BudgetEditData {
        val month = Period.containing(today, v.monthStartDay).yearMonth
        val c = byId[categoryId]
        if (c == null || c.parentId != null || c.kind != CategoryKind.EXPENSE) {
            return BudgetEditData(false, categoryId, "", "", "", 0, "", "", monthKey(month), month.year, month.month)
        }
        val icon = iconParts(c.icon)
        val every = BudgetRules.everyMonth(categoryId, parts.budgets)
        val override = BudgetRules.forMonth(categoryId, month, parts.budgets)
        return BudgetEditData(
            true, categoryId, c.name, icon.first, icon.second, c.color,
            if (every == null) "" else Money.toInput(every), if (override == null) "" else Money.toInput(override),
            monthKey(month), month.year, month.month,
        )
    }

    fun budgetPlan(categoryId: Long, every: String, thisMonth: String, today: LocalDate): BudgetPlanData {
        val month = Period.containing(today, v.monthStartDay).yearMonth
        val e = every.trim()
        val m = thisMonth.trim()
        val plan = BudgetRules.plan(
            categoryId, month, parts.budgets,
            everyTyped = e.isNotEmpty(), everyCents = if (e.isEmpty()) null else Money.parseCents(e),
            monthTyped = m.isNotEmpty(), monthCents = if (m.isEmpty()) null else Money.parseCents(m),
        )
        return BudgetPlanData(
            plan.problems.map { FieldProblemData(it.field.name, it.message) },
            plan.changes.map { BudgetChangeData(it.categoryId, it.month, it.amount, it.delete) },
        )
    }

    // ---------- Activity ----------

    fun activity(year: Int, month: Int, filter: EntryFilter, today: LocalDate, zone: TimeZone): ActivityData {
        val shownMonth = YearMonth(year, month.coerceIn(1, 12))
        val range = QueryWindows.monthRange(shownMonth, zone)
        val inMonth = parts.transactions.filter { it.epochMillis in range }
        val list = CountedSpending.apply(inMonth, parts.loanPayments, parts.loanTerms, parts.categories)
        val spent = list.filter { it.kind == TxKind.EXPENSE }.sumOf { it.categoryAmount }
        val income = list.filter { it.kind == TxKind.INCOME }.sumOf { it.amount }
        val shown = list.filter { filter.keeps(it.kind) }
        val days = ArrayList<DayData>()
        for ((day, entries) in shown.groupBy { dayOf(it, zone) }) {
            val daySpent = entries.filter { it.kind == TxKind.EXPENSE }.sumOf { it.categoryAmount }
            // A zero would give away which days had spending, so while hidden every day shows the mask.
            val spentText = if (daySpent > 0L || Money.isHidden) Money.format(daySpent) else ""
            days.add(DayData(day.toEpochDay().toInt(), spentText, entries.map { v.entryRow(it) }))
        }
        return ActivityData(
            year = shownMonth.year, month = shownMonth.month, isCurrentMonth = shownMonth == YearMonth.from(today),
            spendingText = Money.format(spent), incomeText = Money.format(income), incomeTone = v.figurePositive(),
            entryCount = shown.size, days = days,
        )
    }

    /** Every entry whose note, category, parent category, account, destination account or person contains [typed], or whose amount is exactly it: `EntryRepository.search`. */
    fun search(typed: String, zone: TimeZone): SearchData {
        val text = typed.trim()
        if (text.isEmpty()) return SearchData("", 0, false, "", emptyList())
        val cents = Money.parseCents(text)?.takeIf { it > 0L } ?: -1L
        val people = parts.people.associateBy { it.id }
        val hits = parts.transactions.filter { t ->
            val category = t.categoryId?.let { byId[it] }
            val parent = category?.parentId?.let { byId[it] }
            val fields = listOf(
                t.note, category?.name ?: "", parent?.name ?: "", t.accountId?.let { v.accountById[it]?.name } ?: "",
                t.toAccountId?.let { v.accountById[it]?.name } ?: "", t.personId?.let { people[it]?.name } ?: "",
            )
            SearchText.matches(text, fields, t.amount, cents)
        }.take(SEARCH_LIMIT)
        val counted = CountedSpending.apply(hits, parts.loanPayments, parts.loanTerms, parts.categories)
        val spent = counted.filter { it.kind == TxKind.EXPENSE }.sumOf { it.categoryAmount }
        val months = ArrayList<SearchMonthData>()
        for ((m, entries) in counted.groupBy { YearMonth.from(dayOf(it, zone)) }) {
            months.add(SearchMonthData(m.year, m.month, dated(entries, zone)))
        }
        return SearchData(text, counted.size, spent > 0L || Money.isHidden, Money.format(spent), months)
    }

    companion object {
        /** `LIMIT 1000` of Android's search query. */
        const val SEARCH_LIMIT = 1000
    }
}
