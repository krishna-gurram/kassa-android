package app.kassa.money.shared

import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupJson
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The demo backup's Accounts and Activity figures from the shared rules equal the ones `tools/make_demo_backup.py` worked out from the entries alone. */
class DemoBackupTest {
    private val dir = "../app/src/test/resources/backup-demo/"
    private fun demo(): BackupData {
        val bytes = File(dir + "demo.kassa").readBytes()
        return BackupJson.parse(GZIPInputStream(ByteArrayInputStream(bytes.copyOfRange(6, bytes.size))).readBytes().decodeToString())
    }
    private fun num(json: String, key: String): Long = Regex("\"$key\": (-?\\d+)").find(json)!!.groupValues[1].toLong()
    private fun usd(cents: Long): String {
        val a = abs(cents)
        val whole = (a / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        return (if (cents < 0) "-" else "") + "$" + whole + "." + (a % 100).toString().padStart(2, '0')
    }

    @Test
    fun accountsMatchTheWorkedOutFigures() {
        KassaCore.installDefaults()
        val json = File(dir + "expected.json").readText()
        val a = LedgerView(demo(), null).accounts(false, num(json, "todayEpochDay").toInt(), "UTC")
        assertEquals(usd(num(json, "assets")), a.assetsText)
        assertEquals(usd(num(json, "liabilities")), a.liabilitiesText)
        assertEquals(usd(num(json, "netWorth")), a.netWorthText)
        val rows = a.groups.flatMap { it.rows }
        assertEquals(usd(num(json, "1")), rows.first { it.name == "Everyday checking" }.amountText)
        assertEquals(false, rows.any { it.name == "Old savings" && it.dueKind.name != "Closed" })
    }

    private val mask = "\u2022\u2022\u2022\u2022"

    /** 2026-10-31, the last day of the month `expected.json` works out (its own "today" is 3 November). */
    private val lastOfOctober = 20757

    @Test
    fun overviewFiguresOfOctober() {
        KassaCore.installDefaults()
        val json = File(dir + "expected.json").readText()
        val o = LedgerView(demo(), null).overview(false, lastOfOctober, "UTC")
        assertEquals(2026, o.year)
        assertEquals(10, o.month)
        // The months of expected.json: "2026-10": spending 276329, income 520286.
        assertEquals(usd(276_329), o.spending.totalText)
        assertEquals(usd(520_286), o.income.totalText)
        assertEquals("Green", o.income.tone.name)
        assertEquals("Ink", o.spending.tone.name)
        assertEquals(listOf("Home", "Groceries", "Dining"), o.spending.top.map { it.name })
        assertEquals(listOf(usd(180_000), usd(44_320), usd(19_223)), o.spending.top.map { it.amountText })
        assertEquals(listOf("Salary", "Interest"), o.income.top.map { it.name })
        assertEquals("symbol", o.spending.top[0].iconKind)
        assertEquals("home", o.spending.top[0].iconValue)
        // The figures of the Accounts tab are the same ones the pins show.
        val accounts = LedgerView(demo(), null).accounts(false, num(json, "todayEpochDay").toInt(), "UTC")
        val checking = accounts.groups.flatMap { it.rows }.first { it.name == "Everyday checking" }
        val pin = LedgerView(demo(), null).overview(false, num(json, "todayEpochDay").toInt(), "UTC").pins.first { it.name == "Everyday checking" }
        assertEquals(checking.amountText, pin.amountText)
    }

    @Test
    fun overviewTodayIsTheCurrentMonthUpToToday() {
        KassaCore.installDefaults()
        val json = File(dir + "expected.json").readText()
        val o = LedgerView(demo(), null).overview(false, num(json, "todayEpochDay").toInt(), "UTC")
        assertEquals(11, o.month)
        assertEquals(usd(181_599), o.spending.totalText)
        assertEquals(usd(260_000), o.income.totalText)
        assertEquals(listOf("Home", "Subscriptions"), o.spending.top.map { it.name })
    }

    @Test
    fun overviewPinsFollowAccountsOrderAndEndWithPeople() {
        KassaCore.installDefaults()
        val o = LedgerView(demo(), null).overview(false, lastOfOctober, "UTC")
        // Open accounts A to Z (Old savings is closed), then People.
        assertEquals(
            listOf("Brokerage", "Car loan", "Everyday checking", "Rainy day savings", "Travel card", "Wallet", ""),
            o.pins.map { it.name },
        )
        assertEquals(true, o.pins.last().isPeople)
        assertEquals(false, o.pins.first().isPeople)
        assertEquals(true, o.pins.size <= LedgerView.MAX_PINS)
        // A loan shows what is owed, with no minus.
        val loan = o.pins.first { it.name == "Car loan" }
        assertEquals(false, loan.amountText.contains("-"))
    }

    @Test
    fun overviewHiddenMasksEveryAmountAndColour() {
        KassaCore.installDefaults()
        val o = LedgerView(demo(), null).overview(true, lastOfOctober, "UTC")
        assertEquals(mask, o.spending.totalText)
        assertEquals(mask, o.income.totalText)
        assertEquals("Ink", o.income.tone.name)
        (o.spending.top + o.income.top).forEach { assertEquals(mask, it.amountText) }
        o.pins.forEach {
            assertEquals(mask, it.amountText)
            assertEquals("Ink", it.amountTone.name)
            if (it.dueKind.name != "Nothing") assertEquals(mask, it.dueAmountText)
        }
    }

    // ---------- Insights ----------

    /** One category's spending, worked out here from the file's entries with plain Kotlin and java.time (not the code under test). */
    private data class Split(val id: Long, val name: String, val sortOrder: Int, val cents: Long)

    private fun dayOf(millis: Long): LocalDate = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate()

    /** Expenses from [from] to [to] (inclusive) per top-level category, largest first, ties by the category's order. */
    private fun splits(data: BackupData, from: LocalDate, to: LocalDate, extra: Map<Long, Long> = emptyMap()): List<Split> {
        val byId = data.categories.associateBy { it.id }
        val sums = HashMap<Long, Long>()
        for (t in data.transactions) {
            if (t.kind != "EXPENSE") continue
            val d = dayOf(t.epochMillis)
            if (d.isBefore(from) || d.isAfter(to)) continue
            val own = byId[t.categoryId] ?: continue
            val top = own.parentId?.let { byId[it] } ?: own
            sums[top.id] = (sums[top.id] ?: 0L) + (t.myShare ?: t.amount)
        }
        for ((id, cents) in extra) sums[id] = (sums[id] ?: 0L) + cents
        return sums.entries.map { e -> Split(e.key, byId.getValue(e.key).name, byId.getValue(e.key).sortOrder, e.value) }
            .sortedWith(compareByDescending<Split> { it.cents }.thenBy { it.sortOrder })
    }

    private fun epochDay(d: LocalDate): Int = d.toEpochDay().toInt()

    @Test
    fun insightsOctoberSplitsMatchTheEntries() {
        KassaCore.installDefaults()
        val data = demo()
        val want = splits(data, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31))
        val total = want.sumOf { it.cents }
        assertEquals(276_329L, total)
        val i = LedgerView(data, null).insights(false, lastOfOctober, "UTC", 0)
        assertEquals(2026, i.year)
        assertEquals(10, i.month)
        assertEquals(true, i.isCurrentMonth)
        assertEquals(true, i.hasEarlier)
        assertEquals("Ink", i.tone.name)
        assertEquals(usd(276_329), i.totalText)
        assertEquals(want.map { it.name }, i.lines.map { it.name })
        assertEquals(want.map { it.id }, i.lines.map { it.id })
        assertEquals(want.map { usd(it.cents) }, i.lines.map { it.amountText })
        // Whole percent of the month, half up, and the bar against the largest line.
        assertEquals(want.map { Math.floor(it.cents * 100.0 / total + 0.5).toInt() }, i.lines.map { it.percent })
        // A category with a budget shows the part of it used (Groceries 600.00, Dining the 300.00 of October's override); the others the bar against the largest.
        val budgets = mapOf("Groceries" to 60_000L, "Dining" to 30_000L)
        assertEquals(want.map { (it.cents * 1000 / (budgets[it.name] ?: want[0].cents)).toInt() }, i.lines.map { it.barPermille })
        assertEquals(listOf(738, 640), i.lines.filter { it.name in budgets }.map { it.barPermille })
        assertEquals(listOf("$600.00", "$300.00"), i.lines.filter { it.name in budgets }.map { it.refText })
        assertEquals(true, i.lines.none { it.over })
        assertEquals(1000, i.lines[0].barPermille)
        assertEquals(listOf("Home", "Groceries", "Dining"), i.lines.take(3).map { it.name })
        assertEquals("home", i.lines[0].iconValue)
        // The lines add up to the hero.
        assertEquals(276_329L, want.sumOf { it.cents })
    }

    @Test
    fun insightsCurrentMonthRunsUpToTodayAndComparesTheSameStretch() {
        KassaCore.installDefaults()
        val data = demo()
        val json = File(dir + "expected.json").readText()
        val today = num(json, "todayEpochDay").toInt()
        assertEquals(epochDay(LocalDate.of(2026, 11, 3)), today)
        val i = LedgerView(data, null).insights(false, today, "UTC", 0)
        assertEquals(11, i.month)
        assertEquals(true, i.isCurrentMonth)
        val want = splits(data, LocalDate.of(2026, 11, 1), LocalDate.of(2026, 11, 3))
        assertEquals(usd(181_599), i.totalText)
        assertEquals(want.map { it.name }, i.lines.map { it.name })
        // Nov 1 to 3 against Oct 1 to 3: "Up to Oct 3".
        val before = splits(data, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 3)).sumOf { it.cents }
        assertEquals(true, i.hasCompare)
        assertEquals(epochDay(LocalDate.of(2026, 10, 3)), i.compareDay)
        assertEquals(usd(before), i.compareText)
    }

    @Test
    fun insightsEarlierMonthsAreWholeAndHaveNoComparison() {
        KassaCore.installDefaults()
        val data = demo()
        val view = LedgerView(data, null)
        val october = view.insights(false, epochDay(LocalDate.of(2026, 11, 3)), "UTC", 1)
        assertEquals(10, october.month)
        assertEquals(false, october.isCurrentMonth)
        assertEquals(false, october.hasCompare)
        assertEquals(usd(276_329), october.totalText)
        val september = view.insights(false, epochDay(LocalDate.of(2026, 11, 3)), "UTC", 2)
        assertEquals(9, september.month)
        val want = splits(data, LocalDate.of(2026, 9, 1), LocalDate.of(2026, 9, 30))
        assertEquals(usd(want.sumOf { it.cents }), september.totalText)
        assertEquals(want.map { it.name }, september.lines.map { it.name })
        // Far enough back there is nothing, and nothing earlier.
        val ancient = view.insights(false, epochDay(LocalDate.of(2026, 11, 3)), "UTC", 120)
        assertEquals(true, ancient.lines.isEmpty())
        assertEquals(false, ancient.hasEarlier)
        assertEquals(usd(0), ancient.totalText)
        // A negative count is the current month.
        assertEquals(11, view.insights(false, epochDay(LocalDate.of(2026, 11, 3)), "UTC", -3).month)
    }

    @Test
    fun insightsHiddenMasksEverythingAndGivesNoShares() {
        KassaCore.installDefaults()
        val data = demo()
        val view = LedgerView(data, null)
        val i = view.insights(true, lastOfOctober, "UTC", 0)
        assertEquals(mask, i.totalText)
        assertEquals("Ink", i.tone.name)
        assertEquals(true, i.lines.isNotEmpty())
        i.lines.forEach {
            assertEquals(mask, it.amountText)
            assertEquals(-1, it.percent)
            assertEquals(0, it.barPermille)
        }
        val now = view.insights(true, epochDay(LocalDate.of(2026, 11, 3)), "UTC", 0)
        assertEquals(mask, now.compareText)
        // Hiding then showing again gives the figures back (Money's globals are put back after each call).
        assertEquals(usd(276_329), view.insights(false, lastOfOctober, "UTC", 0).totalText)
    }

    @Test
    fun categoryPageListsTheMonthsEntriesOfOneCategory() {
        KassaCore.installDefaults()
        val data = demo()
        val view = LedgerView(data, null)
        val groceries = data.categories.first { it.name == "Groceries" }
        val ids = data.transactions
            .filter { it.kind == "EXPENSE" && it.categoryId == groceries.id }
            .filter { dayOf(it.epochMillis).let { d -> d.year == 2026 && d.monthValue == 10 } }
            .map { it.id }.toSet()
        val cents = data.transactions.filter { it.id in ids }.sumOf { it.myShare ?: it.amount }
        assertEquals(6, ids.size)
        val page = view.categoryPage(false, lastOfOctober, "UTC", 0, groceries.id)
        assertEquals(true, page.found)
        assertEquals("Groceries", page.name)
        assertEquals(usd(cents), page.totalText)
        assertEquals(ids.size, page.entryCount)
        assertEquals(ids, page.days.flatMap { it.entries }.map { it.id }.toSet())
        // Newest day first, and the page total equals the line on Insights.
        assertEquals(page.days.map { it.epochDay }.sortedDescending(), page.days.map { it.epochDay })
        val line = view.insights(false, lastOfOctober, "UTC", 0).lines.first { it.id == groceries.id }
        assertEquals(line.amountText, page.totalText)
        page.days.forEach { day ->
            val perDay = data.transactions.filter { it.id in day.entries.map { e -> e.id } }.sumOf { it.myShare ?: it.amount }
            assertEquals(usd(perDay), day.spentText)
            day.entries.forEach { assertEquals("Expense", it.kind.name) }
        }
    }

    @Test
    fun categoryPageHiddenAndMissing() {
        KassaCore.installDefaults()
        val data = demo()
        val view = LedgerView(data, null)
        val home = data.categories.first { it.name == "Home" }
        val page = view.categoryPage(true, lastOfOctober, "UTC", 0, home.id)
        assertEquals(mask, page.totalText)
        assertEquals(1, page.entryCount)
        page.days.forEach { day ->
            assertEquals(mask, day.spentText)
            day.entries.forEach { assertEquals(mask, it.amountText) }
        }
        assertEquals(false, view.categoryPage(false, lastOfOctober, "UTC", 0, 9_999L).found)
        // Nothing with no category in the demo.
        val none = view.categoryPage(false, lastOfOctober, "UTC", 0, 0L)
        assertEquals(true, none.found)
        assertEquals(0, none.entryCount)
        assertTrue(none.days.isEmpty())
    }

    @Test
    fun aLoanPrincipalThatCountsIsSpendingInInsightsAndOnItsCategoryPage() {
        KassaCore.installDefaults()
        val data = demo()
        // The loan's principal entry of October, found from the payment rows alone.
        val principalIds = data.loanPayments.mapNotNull { it.principalEntryId }.toSet()
        val principal = data.transactions
            .filter { it.id in principalIds && dayOf(it.epochMillis).let { d -> d.year == 2026 && d.monthValue == 10 } }
        assertEquals(1, principal.size)
        val extra = principal.sumOf { it.amount }
        val interest = data.categories.first { it.name == "Loan interest" }
        val counting = data.copy(loanTerms = data.loanTerms.map { it.copy(countAllAsSpending = true) })
        val want = splits(data, LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), mapOf(interest.id to extra))
        val view = LedgerView(counting, null)
        val i = view.insights(false, lastOfOctober, "UTC", 0)
        assertEquals(usd(276_329 + extra), i.totalText)
        assertEquals(want.map { it.name }, i.lines.map { it.name })
        assertEquals(want.map { usd(it.cents) }, i.lines.map { it.amountText })
        val page = view.categoryPage(false, lastOfOctober, "UTC", 0, interest.id)
        assertEquals(2, page.entryCount)
        assertEquals(usd(want.first { it.id == interest.id }.cents), page.totalText)
        // Without the switch the principal stays a transfer and out of spending.
        assertEquals(usd(276_329), LedgerView(data, null).insights(false, lastOfOctober, "UTC", 0).totalText)
    }
}
