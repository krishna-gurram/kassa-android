package app.kassa.money.domain

import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PatternsTest {
    private val zone = ZoneOffset.UTC
    private val food = Category(id = 1, name = "Dining", kind = CategoryKind.EXPENSE, color = 0)
    private val home = Category(id = 2, name = "Home", kind = CategoryKind.EXPENSE, color = 0)
    private val pay = Category(id = 9, name = "Salary", kind = CategoryKind.INCOME, color = 0)
    private val cats = listOf(food, home, pay)
    private val byId = cats.associateBy { it.id }
    private val today = LocalDate.of(2026, 10, 22)

    private fun at(d: String) = LocalDate.parse(d).atTime(12, 0).toInstant(zone).toEpochMilli()
    private fun spend(d: String, cents: Long, cat: Category = food, note: String = "", share: Long? = null) =
        Transaction(kind = TxKind.EXPENSE, amount = cents, accountId = 1, categoryId = cat.id, epochMillis = at(d), note = note, myShare = share)
    private fun earn(d: String, cents: Long) = Transaction(kind = TxKind.INCOME, amount = cents, accountId = 1, categoryId = pay.id, epochMillis = at(d))

    @Test
    fun samePointBeforeCapsAtTheEndOfTheShorterPeriod() {
        assertEquals(LocalDate.of(2026, 9, 1) to LocalDate.of(2026, 9, 22), Patterns.samePointBefore(LocalDate.of(2026, 10, 1), today, LocalDate.of(2026, 9, 1)))
        // Mar 31 compared with February: February ends on the 28th.
        val (_, end) = Patterns.samePointBefore(LocalDate.of(2026, 3, 1), LocalDate.of(2026, 3, 31), LocalDate.of(2026, 2, 1))
        assertEquals(LocalDate.of(2026, 2, 28), end)
    }

    @Test
    fun daysThisWeekLeavesFutureDaysEmpty() {
        val days = Patterns.daysThisWeek(listOf(spend("2026-10-19", 1_000), spend("2026-10-22", 500), spend("2026-10-18", 9_999)), today, zone)
        assertEquals(LocalDate.of(2026, 10, 19) to 1_000L, days[0])
        assertEquals(DayOfWeek.MONDAY, days[0].first.dayOfWeek)
        assertEquals(500L, days[3].second)
        assertNull(days[4].second)
    }

    @Test
    fun weekCanStartOnSunday() {
        val days = Patterns.daysThisWeek(listOf(spend("2026-10-18", 700)), today, zone, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 10, 18) to 700L, days[0])
        assertNull(days[5].second)
    }

    @Test
    fun savedIsIncomeMinusMyShareOfSpending() {
        val txs = listOf(earn("2026-10-01", 300_000), spend("2026-10-05", 100_000, share = 40_000), earn("2026-09-15", 200_000), spend("2026-09-20", 50_000))
        val s = Patterns.saved(txs, today, 1, zone, count = 2)
        assertEquals(YearMonth.of(2026, 9), s[0].month)
        assertEquals(150_000, s[0].saved)
        assertEquals(260_000, s[1].saved)
        assertEquals(87, s[1].rate)
    }

    @Test
    fun storesGroupByNoteIgnoringCase() {
        val txs = listOf(spend("2026-10-02", 5_000, note = "Costco"), spend("2026-10-09", 7_000, note = "costco "), spend("2026-10-10", 9_000, note = "Target"), spend("2026-09-10", 50_000, note = "Costco"))
        val st = Patterns.stores(txs, byId, LocalDate.of(2026, 10, 1), today, zone)
        assertEquals("costco", st[0].name.lowercase())
        assertEquals(12_000, st[0].total)
        assertEquals(2, st[0].visits)
        assertEquals("Target", st[1].name)
    }

    @Test
    fun smallBuysCountOnlyUnderTheLimit() {
        val txs = listOf(spend("2026-10-02", 450), spend("2026-10-03", 1_499), spend("2026-10-04", 1_500), spend("2026-10-05", 800, home))
        val s = Patterns.smallBuys(txs, byId, LocalDate.of(2026, 10, 1), today, zone)
        assertEquals(3, s.count)
        assertEquals(2_749, s.total)
        assertEquals(food, s.byCategory.first().first)
    }

    @Test
    fun creepingNeedsARiseEveryMonth() {
        val txs = listOf(
            spend("2026-07-10", 20_000), spend("2026-08-10", 26_000), spend("2026-09-10", 34_000),
            spend("2026-07-10", 20_000, home), spend("2026-08-10", 40_000, home), spend("2026-09-10", 30_000, home),
            spend("2026-10-02", 1_000),
        )
        val c = Patterns.creeping(txs, cats, today, 1, zone)
        assertEquals(listOf(food), c.map { it.category })
        assertEquals(14_000, c[0].rise)
        assertEquals(YearMonth.of(2026, 7), c[0].months.first().first)
    }

    @Test
    fun weekChangesCompareTheSameDaysLastWeek() {
        val txs = listOf(spend("2026-10-20", 8_000), spend("2026-10-13", 3_000), spend("2026-10-16", 9_000), spend("2026-10-21", 1_000, home), spend("2026-10-14", 4_000, home))
        val ch = Patterns.weekChanges(txs, cats, today, zone).associateBy { it.category }
        assertEquals(5_000, ch[food]!!.change)
        assertEquals(-3_000, ch[home]!!.change)
        assertTrue(ch.size == 2)
    }
}
