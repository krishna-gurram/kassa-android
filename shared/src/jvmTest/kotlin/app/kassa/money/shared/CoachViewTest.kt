package app.kassa.money.shared

import app.kassa.money.backup.BackupAccount
import app.kassa.money.backup.BackupBill
import app.kassa.money.backup.BackupCategory
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
 * `LedgerView.coach`. The ledger is the Coach rules' own fixture written as a backup: groceries of 100.00 on the 10th and a salary of
 * 4,000.00 on the 28th of August to October 2026, rent of 1,500.00 on the 1st from August to November, a fixed bill "Internet" of 50.00 due on the
 * 20th, checking opening at 10,000.00 and savings opening at 3,000.00. Worked out by hand: checking ends at
 * 10,000.00 + 3 x 4,000.00 - 3 x 100.00 - 4 x 1,500.00 = 15,700.00, savings 3,000.00, so 18,700.00 is liquid. Today is 12 November 2026, the
 * next salary 28 November (16 days), a usual day is 30,000 / 92 = 326 cents, so 16 days is 52.16, and the safe figure
 * 18,700.00 - 50.00 - 52.16 = 18,597.84.
 */
class CoachViewTest {
    private val mask = "••••"
    private val today = day("2026-11-12")
    private val zone = "UTC"
    private val monday = 2

    private fun day(text: String): Int = LocalDate.parse(text).toEpochDay().toInt()
    private fun millis(text: String): Long =
        LocalDateTime.of(LocalDate.parse(text), LocalTime.of(12, 0)).toInstant(ZoneOffset.UTC).toEpochMilli()

    private fun category(id: Long, name: String, kind: String = "EXPENSE") =
        BackupCategory(id, name, kind, null, "emoji:x", 0, true, false, 0)

    private var nextId = 0L

    private fun tx(kind: String, date: String, cents: Long, category: Long?, note: String, account: Long = 1) =
        BackupTransaction(id = ++nextId, kind = kind, amount = cents, accountId = account, categoryId = category, epochMillis = millis(date), note = note)

    private fun ledger(): BackupData {
        nextId = 0
        val txs = ArrayList<BackupTransaction>()
        for (m in listOf("2026-08", "2026-09", "2026-10")) {
            txs.add(tx("EXPENSE", "$m-10", 10_000, 10, ""))
            txs.add(tx("INCOME", "$m-28", 400_000, 20, "Paycheck"))
        }
        for (m in listOf("2026-08", "2026-09", "2026-10", "2026-11")) txs.add(tx("EXPENSE", "$m-01", 150_000, 12, "Rent"))
        return BackupData(
            createdAt = 0, monthStartDay = 1, currency = "USD",
            accounts = listOf(
                BackupAccount(1, "Everyday", "CHECKING", openingBalance = 1_000_000, archived = false, sortOrder = 0),
                BackupAccount(2, "Rainy", "SAVINGS", openingBalance = 300_000, archived = false, sortOrder = 1),
            ),
            categories = listOf(category(10, "Groceries"), category(12, "Rent"), category(14, "Internet"), category(20, "Salary", "INCOME")),
            transactions = txs,
            bills = listOf(BackupBill(5, "Internet", 14, 1, 20, 5_000, true, 0)),
        )
    }

    private fun view(data: BackupData = ledger()): LedgerView { KassaCore.installDefaults(); return LedgerView(data, null) }

    @Test
    fun safeToSpendIsWorkedOutFromTheLedger() {
        val c = view().coach(false, today, zone, monday)
        assertFalse(c.hidden)
        val s = c.safe
        assertEquals("SPENDABLE", s.state)
        assertEquals("$18,597.84", s.amountText)
        assertEquals("", s.shortByText)
        assertEquals("NEXT_INCOME", s.untilKind)
        assertEquals(day("2026-11-28"), s.untilDay)
        assertEquals(16, s.daysLeft)
        assertEquals("HISTORY", s.basis)
        // 10,216 of 1,870,000 is 0.55%, which rounds to 1.
        assertEquals(1, s.usedPercent)
        assertEquals(listOf("BILLS", "USUAL_SPENDING"), s.parts.map { it.kind })
        assertEquals(listOf("$50.00", "$52.16"), s.parts.map { it.amountText })
        assertEquals("Internet", s.parts[0].name)
        assertEquals("Groceries", s.parts[1].name)
        assertEquals(10L, s.parts[1].refId)
    }

    @Test
    fun theFundAndTheSubscriptionsAndThePace() {
        val c = view().coach(false, today, zone, monday)
        // Spending is 1,600.00 a month; 18,700.00 over it is 11.68 -> 11.6 months, which is past the 6 month goal.
        assertEquals("READY", c.fund.state)
        assertEquals("$1,600.00", c.fund.monthlySpendingText)
        assertEquals(116, c.fund.tenths)
        assertEquals(0, c.fund.targetTenths)
        assertEquals("", c.fund.weeklyText)
        assertEquals(3, c.fund.monthsUsed)
        // Rent is the one monthly fixed charge, at 1,500.00, and it did not rise.
        assertEquals(1, c.subscriptions.count)
        assertEquals("$1,500.00", c.subscriptions.monthlyTotalText)
        assertEquals("$0.00", c.subscriptions.increaseText)
        assertTrue(c.subscriptions.rows.isEmpty())
        // The rent paid on the 1st is not a rate of spending: nothing is flagged.
        assertEquals(12, c.pace.dayOfPeriod)
        assertEquals(30, c.pace.daysInPeriod)
        assertEquals(listOf("Rent"), c.pace.rows.map { it.categoryName })
        assertFalse(c.pace.rows[0].aboveLast)
        assertEquals("$1,500.00", c.pace.rows[0].projectionText)
        assertEquals("", c.pace.rows[0].budgetText)
    }

    @Test
    fun theCheckInHasNothingToSayAboutASteadyLedger() {
        val ci = view().coach(false, today, zone, monday).checkIn
        assertEquals("READY", ci.state)
        assertEquals(day("2026-11-09"), ci.weekStartDay)
        assertEquals("$0.00", ci.spentText)
        assertEquals(4, ci.weeksCompared)
        assertTrue(ci.watches.isEmpty())
        assertTrue(ci.decisions.isEmpty())
    }

    @Test
    fun aShortfallNamesHowFarShort() {
        val d = ledger().let { it.copy(accounts = it.accounts.map { a -> a.copy(openingBalance = if (a.id == 1L) -1_560_000 else 0) }) }
        // Checking ends at -1,560,000 + 1,200,000 - 30,000 - 600,000 = -990,000 cents; savings 0.
        val s = view(d).coach(false, today, zone, monday).safe
        assertEquals("SHORTFALL", s.state)
        assertEquals("$0.00", s.amountText)
        // 990,000 + 5,000 + 5,216 = 1,000,216 short.
        assertEquals("$10,002.16", s.shortByText)
        assertEquals(100, s.usedPercent)
    }

    @Test
    fun hiddenCarriesNoFigureNoSizeAndNoState() {
        val v = view(ledger().let { it.copy(accounts = it.accounts.map { a -> a.copy(openingBalance = if (a.id == 1L) -1_560_000 else 0) }) })
        val c = v.coach(true, today, zone, monday)
        assertTrue(c.hidden)
        // A shortfall must not show: the state is neutral and every amount is the mask.
        assertEquals("HIDDEN", c.safe.state)
        assertEquals(mask, c.safe.amountText)
        assertEquals(mask, c.safe.shortByText)
        assertEquals(-1, c.safe.usedPercent)
        assertTrue(c.safe.parts.all { it.amountText == mask })
        assertTrue(c.pace.rows.isEmpty())
        assertTrue(c.subscriptions.rows.isEmpty())
        assertEquals(mask, c.subscriptions.monthlyTotalText)
        assertEquals("HIDDEN", c.fund.state)
        assertEquals(-1, c.fund.tenths)
        assertEquals(-1, c.fund.targetTenths)
        assertEquals("HIDDEN", c.checkIn.state)
        assertEquals(mask, c.checkIn.spentText)
        assertTrue(c.checkIn.wins.isEmpty() && c.checkIn.watches.isEmpty() && c.checkIn.decisions.isEmpty())
        // Every fact value is the mask, except the plain day counts of the calendar.
        assertTrue(c.facts.isNotEmpty())
        for (f in c.facts) assertTrue(f.valueText == mask || f.key.endsWith("_days") || f.key == "budget_month_day", "${f.key}: ${f.valueText}")
        // No currency symbol anywhere in what the screen is given.
        assertFalse("$" in c.toString())
    }

    @Test
    fun hiddenAndShownShareTheSameStructure() {
        val v = view()
        val shown = v.coach(false, today, zone, monday)
        val hidden = v.coach(true, today, zone, monday)
        assertEquals(shown.safe.untilDay, hidden.safe.untilDay)
        assertEquals(shown.safe.daysLeft, hidden.safe.daysLeft)
        assertEquals(shown.facts.map { it.key }, hidden.facts.map { it.key })
        // Asking for one does not leave the other's setting behind.
        assertEquals("$18,597.84", v.coach(false, today, zone, monday).safe.amountText)
    }

    @Test
    fun theAskPayloadHasTotalsAndNoNames() {
        val v = view()
        val text = v.coachAskPayload(today, zone, monday)
        assertTrue(text.startsWith("safe_to_spend: 18597.84"))
        for (word in listOf("Everyday", "Rainy", "Groceries", "Rent", "Paycheck", "Internet", "Salary")) {
            assertFalse(word.lowercase() in text.lowercase(), "$word leaked")
        }
        for (line in text.lines()) assertTrue(Regex("^[a-z_]+: -?\\d+(\\.\\d+)?$").matches(line), line)
        // The same keys the screen lists as "what a question sends".
        assertEquals(v.coach(false, today, zone, monday).facts.map { it.key }, text.lines().map { it.substringBefore(':') })
        // The payload is the real figures even while the screen is hidden: it is only ever sent when the person asks.
        assertEquals(text, v.coachAskPayload(today, zone, monday))
    }

    @Test
    fun anEmptyLedgerIsQuiet() {
        val c = view(BackupData(createdAt = 0, monthStartDay = 1, currency = "EUR")).coach(false, today, zone, monday)
        assertEquals("NO_ACCOUNTS", c.safe.state)
        assertEquals(-1, c.safe.usedPercent)
        assertEquals("NOT_ENOUGH_DATA", c.fund.state)
        assertEquals("NOT_ENOUGH_DATA", c.checkIn.state)
        assertTrue(c.pace.rows.isEmpty())
        assertEquals(0, c.subscriptions.count)
        assertEquals("€0.00", c.subscriptions.monthlyTotalText)
        // Hidden with no accounts is still "no accounts": that gives no figure away.
        assertEquals("NO_ACCOUNTS", view(BackupData(createdAt = 0, monthStartDay = 1)).coach(true, today, zone, monday).safe.state)
    }

    @Test
    fun theDemoBackupGivesSaneFigures() {
        val bytes = File("../app/src/test/resources/backup-demo/demo.kassa").readBytes()
        val data = BackupJson.parse(GZIPInputStream(ByteArrayInputStream(bytes.copyOfRange(6, bytes.size))).readBytes().decodeToString())
        val v = view(data)
        for (d in listOf("2026-10-22", "2026-10-31", "2026-11-03", "2026-11-12")) {
            for (first in listOf(1, 2, 7)) {
                val c = v.coach(false, day(d), zone, first)
                assertTrue(c.safe.state in setOf("SPENDABLE", "SHORTFALL", "NO_ACCOUNTS"), c.safe.state)
                assertTrue(c.safe.usedPercent in -1..100)
                assertTrue(c.safe.daysLeft >= 1)
                assertTrue(c.safe.untilDay > day(d))
                assertFalse(c.safe.amountText.startsWith("-") || c.safe.amountText.startsWith("−"))
                assertTrue(c.checkIn.wins.size <= 1 && c.checkIn.watches.size <= 1 && c.checkIn.decisions.size <= 1)
                assertTrue(c.fund.tenths >= 0)
                val payload = v.coachAskPayload(day(d), zone, first)
                for (line in payload.lines()) assertTrue(Regex("^[a-z_]+: -?\\d+(\\.\\d+)?$").matches(line), line)
                val h = v.coach(true, day(d), zone, first)
                assertTrue(h.safe.state == "HIDDEN" || h.safe.state == "NO_ACCOUNTS")
                assertFalse("$" in h.toString())
            }
        }
    }
}
