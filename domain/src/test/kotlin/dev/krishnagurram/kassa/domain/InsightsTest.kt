package dev.krishnagurram.kassa.domain

import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InsightsTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-10-22")
    private fun at(d: String) = LocalDate.parse(d).atTime(12, 0).toInstant(zone).toEpochMilli()
    private val groceries = Category(id = 1, name = "Groceries", kind = CategoryKind.EXPENSE, color = 0)
    private val car = Category(id = 2, name = "Car", kind = CategoryKind.EXPENSE, color = 0)
    private val cats = listOf(groceries, car)
    private fun e(d: String, c: Long, cat: Long, note: String = "") = Transaction(kind = TxKind.EXPENSE, amount = c, accountId = 1, categoryId = cat, epochMillis = at(d), note = note)

    private val history = buildList {
        // Usual groceries: $800 in each of Jul, Aug, Sep.
        listOf("2026-07-10", "2026-08-10", "2026-09-10").forEach { add(e(it, 80_000, 1)) }
        // Car: insurance $168 on the 1st plus $60 charging, every month.
        listOf("07", "08", "09", "10").forEach { m -> add(e("2026-$m-01", 16_800, 2, "Car insurance")); add(e("2026-$m-12", 6_000, 2)) }
        // October groceries: $900 by the 22nd, on track for about $1,268.
        add(e("2026-10-05", 50_000, 1)); add(e("2026-10-18", 40_000, 1))
    }

    @Test
    fun repeatingFindsMonthlyChargesAndPriceChanges() {
        val subs = listOf("07", "08", "09").map { e("2026-$it-07", 1_549, 2, "Netflix") } + e("2026-10-07", 1_799, 2, "Netflix")
        val r = Insights.repeating(subs + history, cats.associateBy { it.id }, zone)
        val netflix = r.first { it.note == "Netflix" }
        assertEquals(1_799, netflix.amount)
        assertEquals(250, netflix.change)
        assertTrue(r.any { it.note == "Car insurance" })
    }

}
