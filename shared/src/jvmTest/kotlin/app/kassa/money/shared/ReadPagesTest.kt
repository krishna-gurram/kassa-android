package app.kassa.money.shared

import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupJson
import app.kassa.money.backup.BackupTransaction
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Batch J (the remaining read pages) against the demo backup. Every expected figure was worked out from the demo's raw rows with a
 * separate script (sums of the entries by day, category and week; the budgets are Groceries 600.00 every month and Dining 250.00 with
 * 300.00 for October 2026), not with the code under test:
 *
 * - October 2026: spending 2,763.29. Groceries 443.20 of 600.00 (156.80 left), Dining 192.23 of the 300.00 override (107.77 left).
 * - May 2026: Groceries 615.26 against 600.00 is 15.26 over; Dining 242.89 against 250.00 leaves 7.11. April's Groceries is 647.88.
 * - Thursday 22 October 2026. A week that starts on Sunday is the 18th to the 22nd: 565 + 5,399 + 0 + 9,790 + 533 = 162.87, against the same
 *   stretch of the week before (11th to 15th) 193.74. A Monday week is the 19th to the 22nd: 157.22 against 145.33. Last week's loan interest
 *   (77.01) is what went down most; Groceries (97.90 against 48.41 on the Sunday weeks) what went up most.
 * - The Travel card is due on Sunday 25 October: inside a week that starts on Monday the 19th, outside one that starts on Sunday the 18th.
 * - Saturday is the busiest weekday over the 13 weeks before 31 October: 2,364.08 over 13 weeks is 181.85 (rent landed on a Saturday in August).
 */
class ReadPagesTest {
    private val dir = "../app/src/test/resources/backup-demo/"
    private fun demo(): BackupData {
        val bytes = File(dir + "demo.kassa").readBytes()
        return BackupJson.parse(GZIPInputStream(ByteArrayInputStream(bytes.copyOfRange(6, bytes.size))).readBytes().decodeToString())
    }
    private fun view(data: BackupData = demo()): LedgerView { KassaCore.installDefaults(); return LedgerView(data, null) }
    private fun day(text: String): Int = LocalDate.parse(text).toEpochDay().toInt()
    private fun millis(text: String, hour: Int = 12): Long =
        LocalDateTime.of(LocalDate.parse(text), LocalTime.of(hour, 0)).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val mask = "\u2022\u2022\u2022\u2022"
    private val today = day("2026-11-03")
    private val lastOfOctober = day("2026-10-31")
    private val thursday = day("2026-10-22")
    private val sunday = 1
    private val monday = 2

    // ---------- budgets ----------

    @Test
    fun budgetsAreMeasuredAgainstTheEntriesOfTheMonth() {
        val b = view().budgetsPage(false, lastOfOctober, "UTC")
        assertEquals(2026, b.year)
        assertEquals(10, b.month)
        assertEquals("$900.00", b.totalText)
        assertTrue(b.hasAny)
        // Top-level expense categories, A to Z; Salary and Interest are income.
        assertEquals(listOf("Bills", "Dining", "Groceries", "Health", "Home", "Loan interest", "Subscriptions", "Transport"), b.rows.map { it.name })
        val groceries = b.rows.first { it.name == "Groceries" }
        assertEquals("$600.00", groceries.budgetText)
        assertEquals("$600.00", groceries.everyText)
        assertEquals("", groceries.monthText)
        assertEquals("$443.20", groceries.spentText)
        assertEquals("LEFT", groceries.statusKind)
        assertEquals("$156.80", groceries.statusText)
        assertFalse(groceries.over)
        assertEquals(738, groceries.barPermille)
        // October's override (300.00) is in force for Dining, the every-month 250.00 is still what is stored.
        val dining = b.rows.first { it.name == "Dining" }
        assertEquals("$300.00", dining.budgetText)
        assertEquals("$250.00", dining.everyText)
        assertEquals("$300.00", dining.monthText)
        assertEquals("$192.23", dining.spentText)
        assertEquals("$107.77", dining.statusText)
        // No budget: no words, no bar.
        val home = b.rows.first { it.name == "Home" }
        assertFalse(home.hasBudget)
        assertEquals("", home.statusKind)
        assertEquals(0, home.barPermille)
        assertEquals("$1,800.00", home.spentText)
    }

    @Test
    fun aCategoryPastItsBudgetSaysOverAndIsOrange() {
        val may = day("2026-05-31")
        val b = view().budgetsPage(false, may, "UTC")
        assertEquals(5, b.month)
        // May has no override: Dining falls back to the 250.00 every month.
        assertEquals("$250.00", b.rows.first { it.name == "Dining" }.budgetText)
        val groceries = b.rows.first { it.name == "Groceries" }
        assertEquals("$615.26", groceries.spentText)
        assertTrue(groceries.over)
        assertEquals("OVER", groceries.statusKind)
        assertEquals("$15.26", groceries.statusText)
        assertEquals(1000, groceries.barPermille)
        val dining = b.rows.first { it.name == "Dining" }
        assertFalse(dining.over)
        assertEquals("LEFT", dining.statusKind)
        assertEquals("$7.11", dining.statusText)
        // April: 647.88 against 600.00.
        val april = view().budgetsPage(false, day("2026-04-30"), "UTC").rows.first { it.name == "Groceries" }
        assertEquals("OVER", april.statusKind)
        assertEquals("$47.88", april.statusText)
    }

    @Test
    fun hiddenBudgetsGiveNothingAway() {
        val v = view()
        for (d in listOf(day("2026-05-31"), lastOfOctober)) {
            val b = v.budgetsPage(true, d, "UTC")
            assertEquals(mask, b.totalText)
            for (row in b.rows) {
                assertFalse(row.over, row.name)
                assertEquals("", row.statusKind, row.name)
                assertEquals("", row.statusText, row.name)
                assertEquals(0, row.barPermille, row.name)
                assertEquals(mask, row.spentText, row.name)
                if (row.hasBudget) assertEquals(mask, row.budgetText, row.name)
            }
        }
        // The Insights lines carry the same rule: orange only when shown.
        val shown = v.insights(false, day("2026-05-31"), "UTC", 0).lines.first { it.name == "Groceries" }
        val masked = v.insights(true, day("2026-05-31"), "UTC", 0).lines.first { it.name == "Groceries" }
        assertTrue(shown.over)
        assertFalse(masked.over)
        assertEquals(0, masked.barPermille)
        assertEquals(mask, masked.refText)
    }

    @Test
    fun insightsLinesMeasureABudgetedCategoryAgainstItsBudget() {
        val may = view().insights(false, day("2026-05-31"), "UTC", 0)
        val groceries = may.lines.first { it.name == "Groceries" }
        assertEquals("$600.00", groceries.refText)
        assertTrue(groceries.over)
        assertEquals(1000, groceries.barPermille)
        val dining = may.lines.first { it.name == "Dining" }
        assertEquals("$250.00", dining.refText)
        assertFalse(dining.over)
        // 242.89 of 250.00 is 971 of 1000.
        assertEquals(971, dining.barPermille)
        val home = may.lines.first { it.name == "Home" }
        assertEquals("", home.refText)
        assertFalse(home.over)
        assertEquals(1000, home.barPermille)
    }

    @Test
    fun editingBudgetsPlansTheWritesAndRefusesBadAmounts() {
        val v = view()
        val edit = v.budgetEdit(2, lastOfOctober)
        assertTrue(edit.found)
        assertEquals("Dining", edit.name)
        assertEquals(202610, edit.monthKey)
        // Saving what the editor opened with changes nothing in effect: the same two rows.
        val same = v.budgetPlan(2, edit.everyInput, edit.monthInput, lastOfOctober)
        assertTrue(same.problems.isEmpty())
        assertEquals(
            listOf(BudgetChangeData(2, 0, 25_000, false), BudgetChangeData(2, 202610, 30_000, false)),
            same.changes,
        )
        // Blank both: both rows go.
        val cleared = v.budgetPlan(2, "", "", lastOfOctober)
        assertEquals(listOf(BudgetChangeData(2, 0, 0, true), BudgetChangeData(2, 202610, 0, true)), cleared.changes)
        // A new budget for Home, and a typed zero or text is a problem that writes nothing.
        assertEquals(listOf(BudgetChangeData(3, 0, 12_000, false)), v.budgetPlan(3, "120", "", lastOfOctober).changes)
        val bad = v.budgetPlan(3, "0", "abc", lastOfOctober)
        assertEquals(listOf("EVERY_MONTH", "THIS_MONTH"), bad.problems.map { it.field })
        assertTrue(bad.changes.isEmpty())
        // Income categories and subcategories have no budget.
        assertFalse(v.budgetEdit(7, lastOfOctober).found)
        assertFalse(v.budgetEdit(999, lastOfOctober).found)
    }

    // ---------- the week ----------

    @Test
    fun aWeekThatStartsOnSundayIsNotAWeekThatStartsOnMonday() {
        val v = view()
        val sun = v.weekView(false, thursday, "UTC", sunday)
        assertEquals(day("2026-10-18"), sun.startDay)
        assertEquals("$163", sun.totalText)
        assertEquals("$194", sun.compareText)
        assertEquals(4, sun.compareWeekday)
        assertEquals(listOf(7, 1, 2, 3, 4, 5, 6), sun.days.map { it.weekday })
        assertEquals(listOf("$6", "$54", "$0", "$98", "$5", "", ""), sun.days.map { it.valueText })
        assertEquals(listOf(true, true, true, true, true, false, false), sun.days.map { it.hasValue })
        assertEquals(thursday, sun.days.first { it.isToday }.epochDay)
        val mon = v.weekView(false, thursday, "UTC", monday)
        assertEquals(day("2026-10-19"), mon.startDay)
        assertEquals("$157", mon.totalText)
        assertEquals("$145", mon.compareText)
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 7), mon.days.map { it.weekday })
        assertEquals(listOf("$54", "$0", "$98", "$5", "", "", ""), mon.days.map { it.valueText })
        // Largest day is the tallest bar; days after today have none.
        assertEquals(1000, mon.days[2].permille)
        assertEquals(0, mon.days[5].permille)
    }

    @Test
    fun theWeekComparesCategoriesWithTheSameStretchOfLastWeek() {
        val sun = view().weekView(false, thursday, "UTC", sunday)
        // Largest first, then the idle categories in their own order (Loan interest had 77.01 last week).
        assertEquals(listOf("Groceries", "Dining"), sun.lines.take(2).map { it.name })
        assertEquals(listOf("$98", "$65"), sun.lines.take(2).map { it.amountText })
        assertEquals(listOf("LAST_WEEK", "LAST_WEEK"), sun.lines.take(2).map { it.refKind })
        assertEquals(listOf("$48", "$68"), sun.lines.take(2).map { it.refText })
        val idle = sun.lines.first { it.name == "Loan interest" }
        assertEquals("$0", idle.amountText)
        assertEquals("$77", idle.refText)
        assertFalse(idle.hasSpending)
        assertEquals(8, sun.lines.size)
        assertEquals("Groceries", sun.up.name)
        assertEquals("$49", sun.up.changeText)
        assertEquals("Loan interest", sun.down.name)
        assertEquals("$77", sun.down.changeText)
        assertTrue(sun.up.arrow)
        // The Monday week: Groceries had nothing last week.
        val mon = view().weekView(false, thursday, "UTC", monday)
        assertEquals("Groceries", mon.up.name)
        assertEquals("$98", mon.up.changeText)
        assertEquals("Loan interest", mon.down.name)
    }

    @Test
    fun aCardDueOnSundayBelongsToTheMondayWeekOnly() {
        val v = view()
        val mon = v.weekView(false, thursday, "UTC", monday)
        assertEquals(1, mon.dueCount)
        assertEquals(7, mon.dueFirstWeekday)
        val sun = v.weekView(false, thursday, "UTC", sunday)
        assertEquals(0, sun.dueCount)
        assertEquals("$0", sun.dueText)
        val page = v.insightDetail("DUES", false, thursday, "UTC", monday)
        assertEquals(1, page.dues.size)
        assertEquals("Travel card", page.dues[0].name)
        assertEquals(day("2026-10-25"), page.dues[0].dueDay)
        // The statement of 5 October: everything before it adds up to 611.31 and nothing has been paid yet on the 22nd.
        assertEquals("$611.31", page.dues[0].payableText)
        assertEquals("$611", mon.dueText)
        assertEquals(listOf("TOTAL", "CARDS"), page.figures.map { it.kind })
        assertEquals("$611.31", page.figures[0].text)
        assertTrue(v.insightDetail("DUES", false, thursday, "UTC", sunday).dues.isEmpty())
    }

    @Test
    fun theWeekMasksEveryFigureAndTheirArrowsWhenHidden() {
        val w = view().weekView(true, thursday, "UTC", monday)
        assertEquals(mask, w.totalText)
        assertEquals(mask, w.compareText)
        assertEquals(mask, w.dueText)
        for (d in w.days) {
            assertEquals(0, d.permille)
            if (d.hasValue) assertEquals(mask, d.valueText)
        }
        assertFalse(w.up.arrow)
        assertFalse(w.down.arrow)
        for (line in w.lines) {
            assertEquals(0, line.barPermille)
            assertFalse(line.over)
        }
    }

    // ---------- the spending page ----------

    @Test
    fun theMonthPageHoldsTheTotalAndEveryCategoryAgainstItsBudget() {
        val p = view().spendingPage(false, lastOfOctober, "UTC", monday, true, false, 0)
        assertEquals("$2,763.29", p.totalText)
        assertEquals(20, p.entryCount)
        assertEquals(2026, p.periodYear)
        assertEquals(10, p.periodMonth)
        assertEquals(day("2026-10-01"), p.fromDay)
        assertEquals(lastOfOctober, p.toDay)
        assertTrue(p.toIsToday)
        assertEquals("Ink", p.tone.name)
        assertEquals("Home", p.lines[0].name)
        assertEquals("$1,800", p.lines[0].amountText)
        val groceries = p.lines.first { it.name == "Groceries" }
        assertEquals("BUDGET", groceries.refKind)
        assertEquals("$600", groceries.refText)
        assertEquals("$443", groceries.amountText)
        assertEquals(738, groceries.barPermille)
        assertFalse(groceries.over)
        // A category with nothing this month is still listed, below the ones with spending.
        val idle = p.lines.first { it.name == "Health" }
        assertFalse(idle.hasSpending)
        assertTrue(p.lines.indexOf(idle) > p.lines.indexOf(groceries))
        // A month before: the page opens on September, and a category opened from it is that many months back.
        val september = view().spendingPage(false, lastOfOctober, "UTC", monday, true, false, 1)
        assertEquals(9, september.periodMonth)
        assertEquals(1, september.categoryBack)
        assertFalse(september.toIsToday)
        assertEquals(day("2026-09-30"), september.toDay)
    }

    @Test
    fun theIncomePageIsGreenAndHasNothingToMeasureAgainst() {
        val p = view().spendingPage(false, lastOfOctober, "UTC", monday, true, true, 0)
        assertEquals("$5,202.86", p.totalText)
        assertEquals("Green", p.tone.name)
        assertEquals(3, p.entryCount)
        assertEquals(listOf("Salary", "Interest"), p.lines.take(2).map { it.name })
        assertEquals(listOf("$5,200", "$3"), p.lines.take(2).map { it.amountText })
        assertTrue(p.lines.all { it.refKind == "" && !it.over })
        assertEquals("Ink", view().spendingPage(true, lastOfOctober, "UTC", monday, true, true, 0).tone.name)
    }

    @Test
    fun theWeekPageStepsBackAWeekAtATimeFromTheWeekStart() {
        val v = view()
        val now = v.spendingPage(false, thursday, "UTC", monday, false, false, 0)
        assertEquals(day("2026-10-19"), now.fromDay)
        assertEquals(thursday, now.toDay)
        assertEquals("$157.22", now.totalText)
        assertEquals(0, now.categoryBack)
        // Last week, the whole week (the 12th to the 18th), 326.54; on Sunday weeks the 11th to the 17th, 369.30.
        val last = v.spendingPage(false, thursday, "UTC", monday, false, false, 1)
        assertEquals(day("2026-10-12"), last.fromDay)
        assertEquals(day("2026-10-18"), last.toDay)
        assertFalse(last.toIsToday)
        assertEquals("$326.54", last.totalText)
        assertEquals("$369.30", v.spendingPage(false, thursday, "UTC", sunday, false, false, 1).totalText)
        // A week of September opens its categories on the month that week starts in: two months back from October's week.
        val september = v.spendingPage(false, thursday, "UTC", monday, false, false, 6)
        assertEquals(1, september.categoryBack)
        assertFalse(v.spendingPage(false, day("2025-11-02"), "UTC", monday, false, false, 0).canBack)
        assertTrue(now.canBack)
    }

    // ---------- day and store ----------

    @Test
    fun aDayShowsItsEntriesAndWhatWasSpent() {
        val v = view()
        val d = v.dayPage(day("2026-10-21"), false, "UTC")
        assertEquals("$97.90", d.spentText)
        assertEquals(listOf("Grocery run"), d.entries.map { it.note })
        assertEquals(listOf("Coffee"), v.dayPage(thursday, false, "UTC").entries.map { it.note })
        assertEquals("$5.33", v.dayPage(thursday, false, "UTC").spentText)
        val empty = v.dayPage(day("2026-10-20"), false, "UTC")
        assertEquals("$0.00", empty.spentText)
        assertTrue(empty.entries.isEmpty())
        val hidden = v.dayPage(day("2026-10-21"), true, "UTC")
        assertEquals(mask, hidden.spentText)
        assertEquals(mask, hidden.entries[0].amountText)
    }

    @Test
    fun aStorePageGathersEveryEntryWithTheSameNote() {
        val v = view()
        // Today is 3 November: Rent was entered on the 1st of each month, the last five inside the 130-day window.
        val rent = v.storePage("Rent", false, today, "UTC")
        assertEquals("$1,800.00", rent.monthText)
        assertEquals(1, rent.monthCount)
        assertEquals(5, rent.days.size)
        assertEquals(day("2026-11-01"), rent.days[0].epochDay)
        assertTrue(rent.days.all { it.entries.size == 1 })
        // Notes match the way repeats do: a month or number on the end does not make another store.
        val loose = v.storePage("rent october 2026", false, today, "UTC")
        assertEquals(5, loose.days.size)
        val groceries = v.storePage("Grocery run", false, lastOfOctober, "UTC")
        assertEquals("$443.20", groceries.monthText)
        assertEquals(6, groceries.monthCount)
        assertEquals(0, v.storePage("Nowhere", false, today, "UTC").days.size)
        assertEquals(mask, v.storePage("Rent", true, today, "UTC").monthText)
    }

    // ---------- Patterns ----------

    @Test
    fun thePatternTilesReadTheMonthsPurchases() {
        val tiles = view().patterns(false, lastOfOctober, "UTC").tiles
        assertEquals(listOf("BIG", "STORE", "SMALL", "CREEP", "REPEAT", "BUSIEST"), tiles.map { it.kind })
        val big = tiles[0]
        assertEquals("$1,800", big.valueText)
        assertEquals("TOP", big.subKind)
        assertEquals(10, big.subCount)
        assertEquals(10, big.bars.size)
        assertEquals(1000, big.bars[0])
        val store = tiles[1]
        assertEquals("Rent", store.valueText)
        assertEquals("$1,800", store.subText)
        // Small purchases (under 15.00): 22.49 over 4 buys in October, a loan payment's interest is not one.
        val small = tiles[2]
        assertEquals("$22", small.valueText)
        assertEquals("BUYS", small.subKind)
        assertEquals(4, small.subCount)
        assertEquals(4, small.bars.size)
        assertEquals(3, small.highlight)
        // No category went up three months in a row.
        val creep = tiles[3]
        assertFalse(creep.hasValue)
        assertEquals("STEADY", creep.subKind)
        assertFalse(creep.attention)
        val repeat = tiles[4]
        assertTrue(repeat.hasValue)
        // Rent 1,800.00 + Power and water 150.83 + Fuel 78.71 + Loan payment 77.01 + Gym 45.00 + Streamly 15.99 a month is 2,167.54.
        assertEquals("$2,168", repeat.valueText)
        // Fuel went up 11.01 and the loan's interest down 1.38 on their last charges; the interest income is not an expense.
        assertEquals("CHANGED", repeat.subKind)
        assertEquals(2, repeat.subCount)
        assertTrue(repeat.attention)
        assertTrue(repeat.lineColors.size <= 4)
        val busiest = tiles[5]
        assertEquals(6, busiest.valueWeekday)
        assertEquals("$182", busiest.subText)
        assertEquals(7, busiest.bars.size)
        assertEquals(5, busiest.highlight)
        assertEquals(1000, busiest.bars[5])
    }

    @Test
    fun hiddenPatternTilesAreAllMask() {
        val tiles = view().patterns(true, lastOfOctober, "UTC").tiles
        assertEquals(6, tiles.size)
        for (t in tiles) {
            assertEquals(mask, t.valueText, t.kind)
            assertTrue(t.hasValue, t.kind)
            assertEquals("", t.subKind, t.kind)
            assertEquals("", t.subText, t.kind)
            assertTrue(t.bars.isEmpty(), t.kind)
            assertFalse(t.attention, t.kind)
            assertEquals(0, t.valueWeekday, t.kind)
        }
    }

    @Test
    fun theTilePagesListWhatTheyCount() {
        val v = view()
        val stores = v.insightDetail("STORES", false, lastOfOctober, "UTC", monday)
        assertEquals(listOf("Rent", "Grocery run", "Dinner out"), stores.ranks.take(3).map { it.name })
        assertEquals(listOf("$1,800", "$443", "$170"), stores.ranks.take(3).map { it.valueText })
        assertEquals(listOf(1, 6, 4), stores.ranks.take(3).map { it.count })
        assertEquals(1000, stores.ranks[0].permille)
        val big = v.insightDetail("BIG_MONTH", false, lastOfOctober, "UTC", monday)
        assertEquals(
            listOf("$1,800.00", "$111.15", "$103.79", "$97.90", "$96.85", "$78.71", "$56.81", "$53.99", "$52.03", "$48.41"),
            big.entries.map { it.entry.amountText },
        )
        assertEquals(day("2026-10-01"), big.entries[0].epochDay)
        val small = v.insightDetail("SMALL", false, lastOfOctober, "UTC", monday)
        assertEquals(listOf("THIS_MONTH", "BUYS", "EACH_UNDER"), small.figures.map { it.kind })
        assertEquals(listOf("$22", "4", "$15"), small.figures.map { it.text })
        assertEquals(listOf(202607, 202608, 202609, 202610), small.bars.map { it.labelValue })
        assertEquals(listOf(false, false, false, true), small.bars.map { it.highlight })
        val weekdays = v.insightDetail("WEEKDAYS", false, lastOfOctober, "UTC", monday)
        assertEquals(listOf(6, 4, 2), weekdays.weekdays.take(3).map { it.weekday })
        assertEquals(listOf("$182", "$168", "$165"), weekdays.weekdays.take(3).map { it.valueText })
        assertEquals(7, weekdays.bars.size)
        assertEquals(listOf(6), weekdays.bars.filter { it.highlight }.map { it.labelValue })
        val repeating = v.insightDetail("REPEATING", false, lastOfOctober, "UTC", monday)
        val hiddenRepeats = v.insightDetail("REPEATING", true, lastOfOctober, "UTC", monday)
        assertTrue(hiddenRepeats.repeats.flatMap { it.rows }.all { it.tagArrow == 0 && it.tagTone.name == "Ink" })
        val monthly = repeating.repeats.first { it.cadence == "MONTHLY" }
        assertEquals("Monthly", monthly.label)
        assertEquals("$1,800.00", monthly.rows.first { it.name == "Rent" }.typicalText)
        // The last rent was entered on 1 November, so the next is 1 December.
        assertEquals(day("2026-12-01"), monthly.rows.first { it.name == "Rent" }.nextDay)
        val fuel = monthly.rows.first { it.name == "Fuel" }
        assertEquals("CHANGE", fuel.tag)
        assertEquals("$11.01", fuel.tagText)
        assertEquals(1, fuel.tagArrow)
        // A dearer expense is against you; a cheaper loan payment is in your favour.
        assertEquals("Orange", fuel.tagTone.name)
        assertEquals("Green", monthly.rows.first { it.name == "Loan payment" }.tagTone.name)
        assertEquals(-1, monthly.rows.first { it.name == "Loan payment" }.tagArrow)
        assertEquals("VARIES", monthly.rows.first { it.name == "Power and water" }.tag)
        assertEquals("", monthly.rows.first { it.name == "Rent" }.tag)
        assertEquals("$45.00", monthly.rows.first { it.name == "Gym membership" }.typicalText)
        assertTrue(monthly.rows.first { it.name == "Rent" }.fixed)
        assertEquals(listOf("A_MONTH", "A_YEAR"), repeating.figures.map { it.kind })
        assertTrue(v.insightDetail("CREEP", false, lastOfOctober, "UTC", monday).creeps.isEmpty())
    }

    // ---------- Activity ----------

    @Test
    fun spendingAndIncomeFilterTheDaysButNotTheTotals() {
        val v = view()
        val all = v.activity(2026, 10, false, lastOfOctober, "UTC")
        val spending = v.activityFiltered(2026, 10, "EXPENSE", false, lastOfOctober, "UTC")
        val income = v.activityFiltered(2026, 10, "INCOME", false, lastOfOctober, "UTC")
        assertEquals(27, all.entryCount)
        assertEquals(27, all.days.sumOf { it.entries.size })
        assertEquals(20, spending.entryCount)
        assertEquals(19, spending.days.size)
        assertTrue(spending.days.flatMap { it.entries }.all { it.kind.name == "Expense" })
        assertEquals(3, income.entryCount)
        assertEquals(3, income.days.size)
        assertTrue(income.days.flatMap { it.entries }.all { it.kind.name == "Income" })
        // The Spending and Income totals do not move with the filter.
        for (a in listOf(spending, income)) {
            assertEquals(all.spendingText, a.spendingText)
            assertEquals(all.incomeText, a.incomeText)
        }
        assertEquals("$2,763.29", spending.spendingText)
        assertEquals("$5,202.86", income.incomeText)
        assertEquals(all.days.map { it.epochDay }, v.activityFiltered(2026, 10, "ALL", false, lastOfOctober, "UTC").days.map { it.epochDay })
        assertEquals(all.entryCount, v.activityFiltered(2026, 10, "nonsense", false, lastOfOctober, "UTC").entryCount)
        // While hidden every day of a filtered list still shows the mask.
        assertTrue(v.activityFiltered(2026, 10, "INCOME", true, lastOfOctober, "UTC").days.all { it.spentText == mask })
    }

    @Test
    fun searchLooksAtEveryMonthAndNeverActsAsAPattern() {
        val v = view()
        val rent = v.activitySearch("rent", false, "UTC")
        assertEquals(13, rent.count)
        assertEquals(13, rent.months.sumOf { it.entries.size })
        assertEquals("$23,400.00", rent.spentText)
        assertTrue(rent.hasSpent)
        // Newest month first, and every hit knows its day.
        assertEquals(listOf(2026 to 11, 2026 to 10), rent.months.take(2).map { it.year to it.month })
        assertEquals(day("2026-11-01"), rent.months[0].entries[0].epochDay)
        // A category's or an account's name finds its entries too.
        assertEquals(6, v.activitySearch("groceries", false, "UTC").months.first { it.month == 10 && it.year == 2026 }.entries.count { it.entry.categoryName == "Groceries" })
        // The exact amount finds it: 45.00 is the gym membership, twelve times.
        val amount = v.activitySearch("45.00", false, "UTC")
        assertEquals(12, amount.count)
        assertEquals("$540.00", amount.spentText)
        // A pattern character is only text: nothing in the demo has a percent sign or underscore.
        assertEquals(0, v.activitySearch("%", false, "UTC").count)
        assertEquals(0, v.activitySearch("_", false, "UTC").count)
        assertEquals(0, v.activitySearch("\\", false, "UTC").count)
        assertEquals(0, v.activitySearch("   ", false, "UTC").count)
        assertEquals(0, v.activitySearch("zzz", false, "UTC").months.size)
        // Hidden: every figure is the mask, even for a search that found nothing.
        val hidden = v.activitySearch("zzz", true, "UTC")
        assertTrue(hidden.hasSpent)
        assertEquals(mask, hidden.spentText)
        assertEquals(mask, v.activitySearch("rent", true, "UTC").months[0].entries[0].entry.amountText)
    }

    @Test
    fun searchEscapesPercentUnderscoreAndBackslashInWhatWasTyped() {
        fun note(id: Long, text: String) = BackupTransaction(
            id = id, kind = "EXPENSE", amount = 1_234 + id, accountId = 1, categoryId = 1, epochMillis = millis("2026-10-20"), note = text,
        )
        val data = demo()
        val extra = listOf(note(9001, "50% off sale"), note(9002, "500 items"), note(9003, "a_b"), note(9004, "axb"), note(9005, "C:\\temp"), note(9006, "Ctemp"))
        val v = view(data.copy(transactions = data.transactions + extra))
        fun notes(q: String) = v.activitySearch(q, false, "UTC").months.flatMap { it.entries }.map { it.entry.note }.sorted()
        // "% off" is text: it finds only the note that holds a percent sign followed by " off".
        assertEquals(listOf("50% off sale"), notes("% off"))
        assertEquals(listOf("50% off sale"), notes("0% o"))
        assertEquals(listOf("500 items"), notes("items"))
        // A bare "50" or "50%" is also the amount 50.00 (an income and a transfer in the demo), as on Android.
        assertTrue(notes("50%").containsAll(listOf("50% off sale", "Refund")))
        assertFalse(notes("50%").contains("500 items"))
        assertEquals(listOf("a_b"), notes("a_b"))
        assertEquals(listOf("C:\\temp"), notes("c:\\t"))
        assertEquals(listOf("50% off sale"), notes("%"))
        assertEquals(listOf("a_b"), notes("_"))
        assertEquals(listOf("C:\\temp"), notes("\\"))
    }

    @Test
    fun theMonthPickerStopsAtTheCurrentMonth() {
        val sheet = ActivityBridge.monthPick(2026, 2026, 11)
        assertEquals(2026, sheet.year)
        assertEquals((1..10).toList(), sheet.cells.filter { it.enabled }.map { it.month }.take(10))
        assertEquals(listOf(11), sheet.cells.filter { it.enabled }.map { it.month }.drop(10))
        assertEquals(listOf(12), sheet.cells.filter { !it.enabled }.map { it.month })
        assertFalse(sheet.canNextYear)
        assertTrue(sheet.canPreviousYear)
        // A year past the latest is held at it.
        assertEquals(2026, ActivityBridge.monthPick(2030, 2026, 11).year)
        assertTrue(ActivityBridge.canPickMonth(2026, 11, 2026, 11))
        assertFalse(ActivityBridge.canPickMonth(2026, 12, 2026, 11))
        assertEquals("EXPENSE", ActivityBridge.toggledFilter("ALL", "EXPENSE"))
        assertEquals("ALL", ActivityBridge.toggledFilter("EXPENSE", "EXPENSE"))
        assertEquals("a b", ActivityBridge.typedSearch("a\nb"))
        assertEquals(60, ActivityBridge.typedSearch("x".repeat(100)).length)
    }
}
