package app.kassa.money.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import java.time.LocalDateTime
import java.time.ZoneOffset
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BillRulesTest {
    private fun day(text: String): Int = LocalDate.parse(text).toEpochDay().toInt()
    private fun millis(text: String, hour: Int = 9): Long =
        LocalDateTime.of(java.time.LocalDate.parse(text), java.time.LocalTime.of(hour, 0)).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val rent = Recurring(id = 4, name = "Rent", categoryId = 3, accountId = 1, dayOfMonth = 1, fixedAmount = null)

    @Test
    fun aBillNeedsANameACategoryAndAnAccount() {
        val none = BillRules.problems("", 5, "", null, null, null)
        assertEquals(listOf("NAME", "CATEGORY", "ACCOUNT"), none.map { it.field })
        assertEquals("Pick a category.", none[1].message)
        assertEquals("Pick an account.", none[2].message)
        assertTrue(BillRules.problems("Rent", 5, "", null, 3, 1).isEmpty())
    }

    @Test
    fun theAmountIsOptionalAndZeroMeansEstimate() {
        assertTrue(BillRules.problems("Rent", 5, "0", 0L, 3, 1).isEmpty())
        assertNull(BillRules.fixedAmount(0L))
        assertNull(BillRules.fixedAmount(null))
        assertEquals(1_800_00L, BillRules.fixedAmount(1_800_00L))
        assertEquals(listOf("AMOUNT"), BillRules.problems("Rent", 5, "abc", null, 3, 1).map { it.field })
        assertEquals(listOf("AMOUNT"), BillRules.problems("Rent", 5, "999999999", 99_999_999_900L, 3, 1).map { it.field })
    }

    @Test
    fun theDayIsOneToThirtyOne() {
        assertTrue(BillRules.problems("Rent", 1, "", null, 3, 1).isEmpty())
        assertTrue(BillRules.problems("Rent", 31, "", null, 3, 1).isEmpty())
        assertEquals(listOf("DAY"), BillRules.problems("Rent", 0, "", null, 3, 1).map { it.field })
        assertEquals(listOf("DAY"), BillRules.problems("Rent", 32, "", null, 3, 1).map { it.field })
    }

    @Test
    fun theNameIsBoundedLikeAnyName() {
        assertEquals(listOf("NAME"), BillRules.problems("x".repeat(41), 5, "", null, 3, 1).map { it.field })
    }

    @Test
    fun theHandledMonthIsTheMonthOfTheDueDate() {
        assertEquals(202611, BillRules.handledMonth(day("2026-11-20")))
        assertEquals(202701, BillRules.handledMonth(day("2027-01-01")))
        assertEquals(202612, BillRules.handledMonth(day("2026-12-31")))
    }

    @Test
    fun approvingRecordsAnExpenseAtNineOnTheDueDay() {
        val d = BillRules.approveDraft(rent, 180_000, day("2026-11-01"), day("2026-11-03"), TimeZone.UTC)
        assertEquals(TxKind.EXPENSE, d.kind)
        assertEquals(180_000L, d.amount)
        assertEquals(1L, d.accountId)
        assertEquals(3L, d.categoryId)
        assertEquals("Rent", d.note)
        assertNull(d.id)
        assertEquals(millis("2026-11-01"), d.epochMillis)
    }

    @Test
    fun aBillPaidAheadIsDatedTodayWhenTheMoneyMoved() {
        val d = BillRules.approveDraft(rent, 180_000, day("2026-11-20"), day("2026-11-17"), TimeZone.UTC)
        assertEquals(millis("2026-11-17"), d.epochMillis)
    }

    @Test
    fun theDraftPassesTheEntryRules() {
        val d = BillRules.approveDraft(rent, 180_000, day("2026-11-01"), day("2026-11-03"), TimeZone.UTC)
        assertNull(EntryRules.problem(d))
    }

    private fun repeat(name: String, category: Long?, amounts: List<Long>, kind: TxKind = TxKind.EXPENSE, cadence: Cadence = Cadence.MONTHLY): Repeat {
        val dates = amounts.indices.map { LocalDate.parse("2026-08-01").let { d -> d.plusMonths(it) } }
        return Repeat(Repeats.key(name), name, kind, category, cadence, amounts, dates, true)
    }

    private fun category(id: Long, hidden: Boolean = false) = Category(
        id = id, name = "C$id", kind = CategoryKind.EXPENSE, parentId = null, icon = CategoryIcon.decode("home"), color = 1,
        countsTowardDaily = true, hidden = hidden, sortOrder = 0, externalId = null,
    )

    @Test
    fun onlySteadyMonthlyExpensesThatAreNotBillsYetAreSuggested() {
        val cats = mapOf(1L to category(1), 2L to category(2, hidden = true))
        val steady = repeat("Gym membership", 1, listOf(4500, 4500, 4500))
        val varies = repeat("Power", 1, listOf(100, 200, 100))
        val twice = repeat("Phone", 1, listOf(5000, 5000))
        val income = repeat("Payroll", 1, listOf(9, 9, 9), kind = TxKind.INCOME)
        val weekly = repeat("Coffee club", 1, listOf(5, 5, 5), cadence = Cadence.WEEKLY)
        val hiddenCategory = repeat("Old gym", 2, listOf(10, 10, 10))
        val noCategory = repeat("Mystery", null, listOf(10, 10, 10))
        val all = listOf(steady, varies, twice, income, weekly, hiddenCategory, noCategory)
        assertEquals(listOf(steady), BillRules.suggestions(all, emptyList(), cats))
        // Already a bill: matched by the loose name, so "Gym membership March" counts as the same.
        val asBill = Recurring(name = "gym membership", categoryId = 1, accountId = 1, dayOfMonth = 8, fixedAmount = null)
        assertTrue(BillRules.suggestions(all, listOf(asBill), cats).isEmpty())
    }
}
