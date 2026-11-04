package app.kassa.money.shared

/*
 * The read pages of iOS batch J, as flat values for Swift: Insights' Patterns tiles and their pages, the week view, the spending page that
 * "this week" and "this month" open, a day page, a store page, budgets and Activity's search and filter. Every figure and rule is :domain's
 * (`Patterns`, `Insights`, `Repeats`, `BudgetRules`, `CountedSpending`, `SearchText`, `MonthPicking`, `EntryFilter`); `ReadViews` only gathers
 * them. Every amount of spending is read from the entries as spending sees them (`CountedSpending`). While Hide balances is on every amount is
 * the mask, no bar or arrow says how big a figure is, and no word says which side of a budget a category is on.
 *
 * No sentence is built here (a tile says what it is and Swift words it), except English :domain owns (`Cadence.label`). Days are epoch days,
 * months are yyyymm, weekdays are ISO numbers (1 Monday to 7 Sunday), colours are ARGB (0 for none).
 */

/** One bar of a small chart: [labelKind] is MONTH ([labelValue] is yyyymm) or WEEKDAY ([labelValue] is the ISO weekday). [permille] is 0 to 1000, 0 while hidden. */
data class BarData(val labelKind: String, val labelValue: Int, val valueText: String, val permille: Int, val highlight: Boolean)

/** An entry with the calendar day it falls on, for lists that are not grouped by day (the biggest purchases, search results). */
data class DatedRowData(val epochDay: Int, val entry: EntryRowData)

/** The six tiles of Insights' Patterns: [kind] is BIG, STORE, SMALL, CREEP, REPEAT or BUSIEST. */
data class PatternTileData(
    val kind: String,
    /** False when there is nothing to name yet (Swift says "None yet" or "None"). Always true while hidden. */
    val hasValue: Boolean,
    /** The tile's main text: a money text, a store's or a category's name; the mask while hidden. */
    val valueText: String,
    /** BUSIEST: the ISO weekday to name, 0 otherwise. */
    val valueWeekday: Int,
    /** What the small line says: TOP (count), BUYS (count), MONTHS (count), STEADY, CHANGED (count), CHARGES (count), AMOUNT (a money text in [subText]) or empty. */
    val subKind: String,
    val subCount: Int,
    val subText: String,
    /** Orange: the rising bars of CREEP, the "changed" line of REPEAT. Never while hidden. */
    val attention: Boolean,
    /** Mini bars, 0 to 1000 against the largest; empty while hidden. [highlight] is the bar drawn strong (-1 for none). */
    val bars: List<Int>,
    val highlight: Int,
    /** The bars' colour (a category's); 0 for ink. */
    val barColor: Int,
    /** REPEAT: the colours of up to four lines, 0 for none. */
    val lineColors: List<Int>,
)

data class PatternsData(val tiles: List<PatternTileData>)

/** One day of the week strip. [hasValue] is false for a day after today. */
data class WeekDayData(val epochDay: Int, val weekday: Int, val isToday: Boolean, val hasValue: Boolean, val valueText: String, val permille: Int)

/**
 * One category line against its reference: a budget (REFERENCE "BUDGET": "of <refText>") or last week ("LAST_WEEK": "last wk <refText>"),
 * or none. [barPermille] is how much of the reference is used, or the line against the largest without one; 0 while hidden. [over] turns the
 * bar and amount orange and is never true while hidden.
 */
data class CatLineData(
    val id: Long,
    val name: String,
    val iconKind: String,
    val iconValue: String,
    val color: Int,
    val amountText: String,
    val hasSpending: Boolean,
    val refKind: String,
    val refText: String,
    val barPermille: Int,
    val over: Boolean,
)

/** Up most or down most against last week: a category and by how much. [arrow] is false while hidden. */
data class WeekChangeData(val found: Boolean, val categoryId: Long, val name: String, val changeText: String, val arrow: Boolean)

/** The week view of Insights: the week that holds today, starting on the phone's first weekday. */
data class WeekData(
    val startDay: Int,
    val totalText: String,
    /** The same stretch of last week, up to [compareWeekday] (today's ISO weekday). */
    val compareWeekday: Int,
    val compareText: String,
    val days: List<WeekDayData>,
    val lines: List<CatLineData>,
    val up: WeekChangeData,
    val down: WeekChangeData,
    val dueText: String,
    val dueCount: Int,
    val dueFirstWeekday: Int,
    val bigText: String,
    val bigCount: Int,
    val largest: List<DatedRowData>,
)

/**
 * The spending (or income) page of a month or a week: the total and every category. [back] periods before the current one; [canBack] is
 * false when nothing was entered earlier. [categoryBack] is how many months back a category opens on.
 */
data class SpendingPageData(
    val month: Boolean,
    val income: Boolean,
    val back: Int,
    val canBack: Boolean,
    val fromDay: Int,
    val toDay: Int,
    val toIsToday: Boolean,
    val periodYear: Int,
    val periodMonth: Int,
    val totalText: String,
    /** Green for income above zero, Ink otherwise and always while hidden. */
    val tone: Tone,
    val entryCount: Int,
    val lines: List<CatLineData>,
    val categoryBack: Int,
)

data class DayPageData(val epochDay: Int, val spentText: String, val entries: List<EntryRowData>)

data class StorePageData(val name: String, val monthText: String, val monthCount: Int, val days: List<DayData>)

/** One ranked row: a store (STORES, [key] is its name) or a category (SMALL). [countKind] is TIMES. */
data class RankRowData(
    val key: String,
    val categoryId: Long,
    val name: String,
    val iconKind: String,
    val iconValue: String,
    val color: Int,
    val valueText: String,
    val count: Int,
    val permille: Int,
)

/** A category that rose three months in a row, with its months. */
data class CreepRowData(
    val categoryId: Long,
    val name: String,
    val iconKind: String,
    val iconValue: String,
    val color: Int,
    val riseText: String,
    /** yyyymm of the first month. */
    val sinceMonth: Int,
    val bars: List<BarData>,
)

/** One charge that repeats. [tag] is CHANGE (the last charge went [tagArrow] up or down by [tagText]), VARIES or empty. */
data class RepeatRowData(
    val name: String,
    val categoryId: Long,
    val iconKind: String,
    val iconValue: String,
    val color: Int,
    val income: Boolean,
    val nextDay: Int,
    val fixed: Boolean,
    val lowText: String,
    val highText: String,
    val tag: String,
    val tagText: String,
    /** 1 up, -1 down, 0 none (and none while hidden). */
    val tagArrow: Int,
    val tagTone: Tone,
    val typicalText: String,
    val typicalTone: Tone,
    val perYearText: String,
)

data class RepeatGroupData(val cadence: String, val label: String, val rows: List<RepeatRowData>)

data class WeekdayRowData(
    val weekday: Int,
    val topCategoryId: Long,
    val topName: String,
    val topIconKind: String,
    val topIconValue: String,
    val topColor: Int,
    val buys: Int,
    val valueText: String,
)

data class DueRowData(val accountId: Long, val name: String, val last4: String, val dueDay: Int, val dueWeekday: Int, val payableText: String)

/** A figure at the top of a detail page: [kind] is TOTAL, CARDS, THIS_MONTH, BUYS, EACH_UNDER, A_MONTH or A_YEAR. */
data class FigureData(val kind: String, val text: String)

/** One page behind a Patterns tile or a week tile. [kind] is BIG_MONTH, BIG_WEEK, STORES, SMALL, CREEP, REPEATING, WEEKDAYS or DUES; only the lists that page uses are filled. */
data class InsightDetailData(
    val kind: String,
    val figures: List<FigureData>,
    val bars: List<BarData>,
    val ranks: List<RankRowData>,
    val creeps: List<CreepRowData>,
    val repeats: List<RepeatGroupData>,
    val weekdays: List<WeekdayRowData>,
    val dues: List<DueRowData>,
    val entries: List<DatedRowData>,
)

// ---------- Budgets ----------

/**
 * One top-level expense category against its budget in the current budget month. [budgetText] is what is in force ("" with none), [everyText]
 * and [monthText] the stored amounts ("" for none). [statusKind] is OVER or LEFT with [statusText] its amount, and empty with no budget and
 * always while hidden (a word would give the size away). [over] and [barPermille] are never set while hidden.
 */
data class BudgetRowData(
    val categoryId: Long,
    val name: String,
    val iconKind: String,
    val iconValue: String,
    val color: Int,
    val hasBudget: Boolean,
    val budgetText: String,
    val everyText: String,
    val monthText: String,
    val spentText: String,
    val over: Boolean,
    val barPermille: Int,
    val statusKind: String,
    val statusText: String,
)

data class BudgetsData(val year: Int, val month: Int, val totalText: String, val hasAny: Boolean, val rows: List<BudgetRowData>)

/** The budget editor of one category: the stored amounts as typed text (never masked), and the month an override is for. */
data class BudgetEditData(
    val found: Boolean,
    val categoryId: Long,
    val name: String,
    val iconKind: String,
    val iconValue: String,
    val color: Int,
    val everyInput: String,
    val monthInput: String,
    val monthKey: Int,
    val year: Int,
    val month: Int,
)

/** One write of saving a budget editor: set the row to [amount], or remove it. */
data class BudgetChangeData(val categoryId: Long, val month: Int, val amount: Long, val isRemoval: Boolean)

/** What saving the editor does: the problems by field (EVERY_MONTH, THIS_MONTH), or the writes to run together. */
data class BudgetPlanData(val problems: List<FieldProblemData>, val changes: List<BudgetChangeData>)

// ---------- Activity: search and month picking ----------

/** One calendar month of search hits, newest first. */
data class SearchMonthData(val year: Int, val month: Int, val entries: List<DatedRowData>)

/** The hits of a search over every month. [spentText] is shown when [hasSpent]. */
data class SearchData(val query: String, val count: Int, val hasSpent: Boolean, val spentText: String, val months: List<SearchMonthData>)

/** One month of the picker sheet. */
data class MonthCellData(val month: Int, val enabled: Boolean)

/** What the "Choose month" sheet needs for [year]: its twelve months and whether the year arrows can move. */
data class MonthPickData(val year: Int, val cells: List<MonthCellData>, val canPreviousYear: Boolean, val canNextYear: Boolean)

/** The rules of Activity's search box, month picker and filter, for Swift. */
object ActivityBridge {

    /** What a search field keeps of what was typed: no line breaks, at most 60 characters (as Android's field). */
    fun typedSearch(raw: String): String = app.kassa.money.domain.InputRules.typed(raw, 60)

    fun monthPick(year: Int, latestYear: Int, latestMonth: Int): MonthPickData {
        val latest = app.kassa.money.domain.YearMonth(latestYear, latestMonth.coerceIn(1, 12))
        val shown = year.coerceIn(app.kassa.money.domain.MonthPicking.EARLIEST_YEAR, latest.year)
        return MonthPickData(
            year = shown,
            cells = app.kassa.money.domain.MonthPicking.cells(shown, latest).map { MonthCellData(it.month, it.enabled) },
            canPreviousYear = app.kassa.money.domain.MonthPicking.canPreviousYear(shown),
            canNextYear = app.kassa.money.domain.MonthPicking.canNextYear(shown, latest),
        )
    }

    fun canPickMonth(year: Int, month: Int, latestYear: Int, latestMonth: Int): Boolean =
        app.kassa.money.domain.MonthPicking.canPick(year, month, app.kassa.money.domain.YearMonth(latestYear, latestMonth.coerceIn(1, 12)))

    /** What tapping the Spending or Income total does from [current]: the same one again shows all. */
    fun toggledFilter(current: String, tapped: String): String =
        app.kassa.money.domain.EntryFilter.named(current).toggled(app.kassa.money.domain.EntryFilter.named(tapped)).name

    /** The budget amount's own check, for the store that writes it again. Null when it can be saved. */
    fun budgetProblem(cents: Long): String? = app.kassa.money.domain.BudgetRules.problem(cents)
}
