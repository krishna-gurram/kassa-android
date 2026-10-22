package dev.krishnagurram.kassa.domain

import org.junit.Test
import java.time.LocalDate
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MoneyAndPeriodTest {

    @Test
    fun parsesAndRoundsToCents() {
        assertEquals(123_450, Money.parseCents("1,234.50"))
        assertEquals(1_200, Money.parseCents("$12"))
        assertEquals(1, Money.parseCents("0.005"))
        assertNull(Money.parseCents("abc"))
        assertNull(Money.parseCents(""))
    }

    @Test
    fun formatsWithRealMinusSign() {
        assertEquals("$1,234.56", Money.format(123_456))
        assertEquals("−$12.00", Money.format(-1_200))
        assertEquals("+$5", Money.format(500, wholeDollars = true, showPlus = true))
    }

    @Test
    fun periodWithCustomStartDay() {
        val p = Period.containing(LocalDate.of(2026, 10, 3), startDay = 15)
        assertEquals(LocalDate.of(2026, 9, 15), p.start)
        assertEquals(LocalDate.of(2026, 10, 14), p.endInclusive)
        assertEquals(YearMonth.of(2026, 9), p.yearMonth)
    }

    @Test
    fun safeToSpendSpreadsWhatIsLeft() {
        val p = Period.startingIn(YearMonth.of(2026, 10), 1)
        val today = LocalDate.of(2026, 10, 22)
        // $3,120 budget, $2,620 spent before today, 10 days left including today -> $50 a day.
        val r = DailyPlan.compute(312_000, 262_000, 1_000, p, today)
        assertEquals(10, r.daysLeft)
        assertEquals(4_000, r.safeToday)
        assertEquals(Math.round(312_000 * 22 / 31.0), r.evenPace)
    }

    @Test
    fun iconsRoundTrip() {
        val icons = listOf(CategoryIcon.Emoji("🛒"), CategoryIcon.Symbol("fitness_center"), CategoryIcon.Image("cat_12.png"))
        icons.forEach { assertEquals(it, CategoryIcon.decode(it.encode())) }
        assertEquals(CategoryIcon.DEFAULT, CategoryIcon.decode("nonsense"))
    }

    @Test
    fun balancesFollowTransactionKinds() {
        val checking = Account(id = 1, name = "Checking", type = AccountType.CHECKING, openingBalance = 10_000)
        val txs = listOf(
            Transaction(kind = TxKind.INCOME, amount = 5_000, accountId = 1, epochMillis = 0),
            Transaction(kind = TxKind.EXPENSE, amount = 2_000, accountId = 1, epochMillis = 0),
            Transaction(kind = TxKind.TRANSFER, amount = 1_000, accountId = 1, toAccountId = 2, epochMillis = 0),
            Transaction(kind = TxKind.TRANSFER, amount = 500, accountId = 2, toAccountId = 1, epochMillis = 0),
        )
        assertEquals(12_500, balanceOf(checking, txs))
    }
}
