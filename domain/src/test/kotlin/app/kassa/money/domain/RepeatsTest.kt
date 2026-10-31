package app.kassa.money.domain

import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RepeatsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-10-24")
    private fun at(d: LocalDate) = d.atTime(12, 0).toInstant(zone).toEpochMilli()
    private fun e(d: LocalDate, cents: Long, note: String, kind: TxKind = TxKind.EXPENSE) =
        Transaction(kind = kind, amount = cents, accountId = 1, categoryId = 7, epochMillis = at(d), note = note)

    /** One entry a month from [from] for [n] months on [day]; note and amount can change with the month. */
    private fun monthly(from: String, n: Int, day: Int, note: (LocalDate) -> String, cents: (Int) -> Long) =
        (0 until n).map { i -> LocalDate.parse(from).plusMonths(i.toLong()).withDayOfMonth(day).let { d -> e(d, cents(i), note(d)) } }

    private fun find(ts: List<Transaction>) = Repeats.find(ts, today, zone)

    @Test
    fun changingWordsAtTheEndAreIgnored() {
        assertEquals("mortgage", Repeats.key("Mortgage October 2026"))
        assertEquals("mortgage", Repeats.key("mortgage - nov"))
        assertEquals("streaming a", Repeats.key("Streaming A #48213"))
        assertEquals("Water bill", Repeats.name("Water bill 10/2026"))
        assertEquals("2026", Repeats.key("2026"))
    }

    @Test
    fun notesThatChangeEachMonthAreOneCharge() {
        val ts = monthly("2026-05-01", 6, 1, { d -> "Mortgage ${d.month.name.lowercase()} ${d.year}" }) { 215_000 }
        val r = find(ts).single()
        assertEquals(Cadence.MONTHLY, r.cadence)
        assertTrue(r.fixed)
        assertTrue(r.steadyMonthly)
        assertEquals(LocalDate.parse("2026-11-01"), r.next(today))
        assertEquals(2_580_000, r.perYear)
    }

    @Test
    fun billsThatVaryAreFoundButNotSteady() {
        val amounts = listOf(13_100L, 26_000, 51_300, 22_000, 18_000, 30_100)
        val ts = monthly("2026-05-02", 6, 2, { "Electricity" }) { amounts[it] }
        val r = find(ts).single()
        assertFalse(r.fixed)
        assertFalse(r.steadyMonthly)
        assertEquals(13_100, r.low)
        assertEquals(51_300, r.high)
    }

    @Test
    fun twoChargesAtOneStoreAreTwoRepeats() {
        val ts = monthly("2026-04-05", 7, 5, { "Apple" }) { 1_090 } + monthly("2026-04-22", 7, 22, { "Apple" }) { 215 }
        val r = find(ts)
        assertEquals(listOf(1_090L, 215L), r.map { it.typical })
        assertTrue(r.all { it.cadence == Cadence.MONTHLY })
    }

    @Test
    fun daysCanDriftAndAMonthCanBeMissed() {
        val days = listOf("2026-03-01", "2026-04-03", "2026-04-29", "2026-06-02", "2026-08-01", "2026-09-01", "2026-10-02")
        val r = find(days.map { e(LocalDate.parse(it), 4_999, "Gym") }).single()
        assertEquals(Cadence.MONTHLY, r.cadence)
    }

    @Test
    fun everyTwoWeeksAndYearly() {
        val pay = (0 until 8).map { e(LocalDate.parse("2026-07-10").plusWeeks(2L * it), 248_000, "Salary", TxKind.INCOME) }
        val dues = listOf("2025-06-19", "2026-06-19").map { e(LocalDate.parse(it), 13_900, "Membership") }
        val r = find(pay + dues).associateBy { it.key }
        assertEquals(Cadence.BIWEEKLY, r.getValue("salary").cadence)
        assertEquals(TxKind.INCOME, r.getValue("salary").kind)
        assertEquals(Cadence.YEARLY, r.getValue("membership").cadence)
    }

    @Test
    fun stoppedTooFewAndIrregularAreLeftOut() {
        val stopped = monthly("2025-12-03", 4, 3, { "VPN" }) { 1_299 }
        val twice = monthly("2026-08-10", 2, 10, { "Magazine" }) { 900 }
        val random = listOf("2026-01-04", "2026-01-09", "2026-03-30", "2026-07-15", "2026-10-01").map { e(LocalDate.parse(it), 2_000, "Hardware store") }
        assertTrue(find(stopped + twice + random).isEmpty())
    }

    @Test
    fun aPriceRiseShowsAsAChange() {
        val ts = monthly("2026-06-07", 4, 7, { "Streaming A" }) { if (it < 3) 1_549 else 1_799 }
        val r = find(ts).single()
        assertEquals(1_799, r.typical)
        assertEquals(250, r.change)
        assertFalse(r.steadyMonthly)
    }

    @Test
    fun namesSortAtoZIgnoringCase() {
        assertEquals(listOf("apple", "Banana", "cherry"), listOf("cherry", "Banana", "apple").sortedWith(Names.order { it }))
    }
}
