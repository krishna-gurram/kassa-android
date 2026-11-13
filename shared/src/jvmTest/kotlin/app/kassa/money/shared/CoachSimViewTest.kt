package app.kassa.money.shared

import app.kassa.money.backup.BackupAccount
import app.kassa.money.backup.BackupBill
import app.kassa.money.backup.BackupCategory
import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupTransaction
import app.kassa.money.domain.WhatIf
import app.kassa.money.domain.WhatIfText
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `LedgerView.coachSim`. The ledger is the Coach one (groceries of 100.00 on the 10th, a salary of 4,000.00 on the 28th, rent of 1,500.00 on
 * the 1st, a fixed bill "Internet" of 50.00 on the 20th, 18,700.00 liquid, today 12 November 2026). Worked out by hand: a usual day is 326
 * cents, so the lowest day is 27 November at 18,700.00 - 16 x 3.26 - 50.00 = 18,597.84 and 12 December ends at 1,870,000 + 400,000 - 150,000 -
 * 5,000 - 31 x 326 = 2,104,894 cents.
 */
class CoachSimViewTest {
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

    private fun sim(v: LedgerView, hidden: Boolean = false, whatIfs: String = "", cushion: Long = 100_000L) =
        v.coachSim(hidden, today, zone, monday, whatIfs, cushion)

    @Test
    fun theReplayIsWorkedOutFromTheLedger() {
        val s = sim(view())
        assertEquals("ABOVE_CUSHION", s.verdict)
        assertEquals("", s.problem)
        assertEquals(today, s.startDay)
        assertEquals("$18,700.00", s.startText)
        assertEquals("$1,000.00", s.cushionText)
        assertEquals(day("2026-11-27"), s.lowestDay)
        assertEquals("$18,597.84", s.lowestText)
        assertEquals(s.lowestDay, s.baselineLowestDay)
        assertEquals(s.lowestText, s.baselineLowestText)
        assertEquals("", s.shortByText)
        assertEquals(31, s.chartDays.size)
        assertEquals(today, s.chartDays.first())
        assertEquals(today + 30, s.chartDays.last())
        assertEquals(1_870_000L - 326, s.chartBalances.first())
        assertEquals(2_104_894L, s.chartBalances.last())
        assertEquals(s.chartBalances, s.baselineBalances)
        val bill = s.events.single { it.kind == "BILL" }
        assertEquals(day("2026-11-20"), bill.day)
        assertEquals("Internet", bill.name)
        assertEquals("$50.00", bill.amountText)
        assertFalse(bill.inflow)
        assertFalse(bill.estimated)
        val pay = s.events.single { it.kind == "PAYCHECK" }
        assertEquals("$4,000.00", pay.amountText)
        assertTrue(pay.inflow)
        assertEquals("$1,500.00", s.events.single { it.kind == "RECURRING" }.amountText)
        assertEquals(31, s.events.count { it.kind == "USUAL_SPENDING" })
        assertEquals(listOf("bill:5:${day("2026-11-20")}"), s.movable.map { it.key })
        assertEquals("BILL", s.movable[0].kind)
        assertEquals("Internet", s.movable[0].name)
        assertEquals("$50.00", s.movable[0].amountText)
    }

    @Test
    fun whatIfsTravelAsTextAndChangeTheAnswer() {
        val v = view()
        val text = CoachSimBridge.join(
            listOf(
                CoachSimBridge.spendLine(1_900_000, today),
                CoachSimBridge.delayLine("bill:5:${day("2026-11-20")}", day("2026-11-30")),
                CoachSimBridge.extraMonthlyLine(2_500, today + 1),
                CoachSimBridge.paydayLine(day("2026-11-29")),
            ),
        )
        assertEquals(4, CoachSimBridge.countWhatIfs(text))
        assertEquals(
            listOf(
                WhatIf.Spend(1_900_000, today), WhatIf.DelayDue("bill:5:${day("2026-11-20")}", day("2026-11-30")),
                WhatIf.ExtraMonthly(2_500, today + 1), WhatIf.MovePayday(day("2026-11-29")),
            ),
            WhatIfText.decode(text),
        )
        val s = sim(v, whatIfs = CoachSimBridge.join(listOf(CoachSimBridge.spendLine(1_900_000, today))))
        // 1,859,784 - 1,900,000 = -40,216 at the lowest day.
        assertEquals("SHORT", s.verdict)
        assertEquals("$402.16", s.shortByText)
        assertTrue(s.lowestText.endsWith("$402.16") && !s.lowestText.startsWith("$"), s.lowestText)
        assertEquals("$18,597.84", s.baselineLowestText)
        assertTrue(s.chartBalances.zip(s.baselineBalances).all { (a, b) -> a == b - 1_900_000L })
        // Several together apply; the answer is not an exception.
        assertEquals(31, sim(v, whatIfs = text.lines().drop(1).joinToString("\n")).chartDays.size)
        // Exactly at the cushion is above it, one cent less is tight.
        assertEquals("ABOVE_CUSHION", sim(v, cushion = 1_859_784L).verdict)
        assertEquals("TIGHT", sim(v, cushion = 1_859_785L).verdict)
    }

    @Test
    fun aBadWhatIfIsAProblemCode() {
        val v = view()
        val bad = sim(v, whatIfs = CoachSimBridge.spendLine(0, today))
        assertEquals("PROBLEM", bad.verdict)
        assertEquals("BAD_AMOUNT", bad.problem)
        assertEquals(0, bad.problemIndex)
        assertTrue(bad.chartDays.isEmpty())
        val garbled = sim(v, whatIfs = "SPEND|x")
        assertEquals("PROBLEM", garbled.verdict)
        assertEquals("BAD_ENCODING", garbled.problem)
        assertEquals(-1, CoachSimBridge.countWhatIfs("SPEND|x"))
        assertEquals("TOO_MANY_WHAT_IFS", sim(v, whatIfs = (1..9).joinToString("\n") { CoachSimBridge.spendLine(100, today) }).problem)
    }

    @Test
    fun tooLittleHistoryIsNotEnoughData() {
        val s = sim(view(ledger().copy(transactions = emptyList())))
        assertEquals("NOT_ENOUGH_DATA", s.verdict)
        assertTrue(s.chartDays.isEmpty())
        assertTrue(s.events.isEmpty())
    }

    @Test
    fun hiddenCarriesNoFigureNoChartAndNoDayOfTheDip() {
        val v = view()
        for (text in listOf("", CoachSimBridge.spendLine(1_900_000, today))) {
            val s = sim(v, hidden = true, whatIfs = text)
            assertEquals("HIDDEN", s.verdict)
            assertEquals(mask, s.startText)
            assertEquals(mask, s.cushionText)
            assertEquals(mask, s.lowestText)
            assertEquals(mask, s.baselineLowestText)
            assertEquals(mask, s.shortByText)
            assertEquals(-1, s.lowestDay)
            assertEquals(-1, s.baselineLowestDay)
            assertTrue(s.chartDays.isEmpty())
            assertTrue(s.chartBalances.isEmpty())
            assertTrue(s.baselineBalances.isEmpty())
            assertTrue(s.events.isEmpty())
            // The payments a person could delay keep their name and day; only the amount is the mask.
            assertEquals(listOf(mask), s.movable.map { it.amountText })
            assertEquals("Internet", s.movable[0].name)
        }
        // Hiding says nothing about the size, so a bad what-if still says so.
        assertEquals("PROBLEM", sim(v, hidden = true, whatIfs = "nonsense").verdict)
    }
}
