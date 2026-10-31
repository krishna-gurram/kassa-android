package app.kassa.money.domain

import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BalanceCorrectionTest {
    private val zone = ZoneId.of("UTC")
    private val at = LocalDate.of(2026, 10, 20).atStartOfDay(zone).toInstant().toEpochMilli()

    private val loan = Account(id = 1, name = "Car loan", type = AccountType.LOAN, openingBalance = -2_148_000)
    private val bank = Account(id = 2, name = "Checking", type = AccountType.CHECKING, openingBalance = 100_000)

    @Test
    fun aCorrectionUpRaisesTheBalanceAndOneDownLowersIt() {
        val up = assertNotNull(BalanceCorrection.entry(0, 1, 2_480, at))
        val down = assertNotNull(BalanceCorrection.entry(0, 1, -2_480, at))
        assertEquals(TxKind.ADJUST_IN, up.kind)
        assertEquals(TxKind.ADJUST_OUT, down.kind)
        assertEquals(2_480, up.amount)
        assertEquals(2_480, down.amount)
        assertEquals(2_480, up.effectOn(1))
        assertEquals(-2_480, down.effectOn(1))
        assertEquals(0, up.effectOn(2))
        assertEquals(-2_145_520L, balanceOf(loan, listOf(up)))
        assertNull(BalanceCorrection.entry(0, 1, 0, at))
    }

    @Test
    fun theChangeIsTheDifferenceBetweenWhatTheLenderShowsAndWhatKassaShows() {
        // Kassa shows 21,480.00 owed (balance -21,480.00); the lender shows 21,455.20 owed.
        val change = BalanceCorrection.change(target = -2_145_520, balance = -2_148_000)
        assertEquals(2_480, change)
        assertEquals(TxKind.ADJUST_IN, BalanceCorrection.kindFor(change))
        assertNotNull(BalanceCorrection.problem(0))
        assertNull(BalanceCorrection.problem(change))
        assertNotNull(BalanceCorrection.problem(InputRules.AMOUNT_MAX + 1))
    }

    @Test
    fun aDebtShowsTheChangeInWhatIsOwedAndOtherAccountsInTheirBalance() {
        val up = assertNotNull(BalanceCorrection.entry(0, 1, 2_480, at))
        assertEquals(-2_480, BalanceCorrection.shown(up, debt = true))
        assertEquals(2_480, BalanceCorrection.shown(up, debt = false))
        assertEquals("↓ " + Money.format(2_480), BalanceCorrection.describe(BalanceCorrection.shown(up, debt = true)))
        assertEquals("↑ " + Money.format(2_480), BalanceCorrection.describe(BalanceCorrection.shown(up, debt = false)))
        assertEquals(0, BalanceCorrection.shown(Transaction(kind = TxKind.EXPENSE, amount = 5, accountId = 1, epochMillis = at), debt = false))
    }

    @Test
    fun aCorrectionIsNeitherSpendingNorIncomeAnywhere() {
        val spend = Transaction(kind = TxKind.EXPENSE, amount = 1_000, accountId = 2, categoryId = 7, epochMillis = at)
        val fix = listOf(
            assertNotNull(BalanceCorrection.entry(0, 2, 50_000, at)),
            assertNotNull(BalanceCorrection.entry(0, 2, -30_000, at)),
        )
        val day = LocalDate.of(2026, 10, 20)
        assertEquals(1_000, Patterns.spent(listOf(spend) + fix, day, day, zone))
        val saved = Patterns.saved(listOf(spend) + fix, day, 1, zone, count = 1)
        assertEquals(0, saved.sumOf { it.income })
        assertEquals(1_000, saved.sumOf { it.spent })
        assertTrue(Repeats.find(fix + fix + fix, day, zone).isEmpty())
        // Yet the account balance moves by both.
        assertEquals(119_000L, balanceOf(bank, listOf(spend) + fix))
    }

    @Test
    fun earlierEntriesAreUntouchedByALaterCorrection() {
        val payment = Transaction(kind = TxKind.TRANSFER, amount = 58_000, accountId = 2, toAccountId = 1, epochMillis = at - 86_400_000)
        val before = balanceOf(loan, listOf(payment))
        val fix = assertNotNull(BalanceCorrection.entry(0, 1, BalanceCorrection.change(target = before + 2_480, balance = before), at))
        assertEquals(before + 2_480, balanceOf(loan, listOf(payment, fix)))
        assertEquals(58_000, payment.amount)
    }

    @Test
    fun aDebtIsTypedAsWhatIsOwedAndOtherAccountsAsTheirBalance() {
        assertEquals(2_145_520, BalanceCorrection.shownFrom(-2_145_520, debt = true))
        assertEquals(-2_145_520, BalanceCorrection.targetFrom(2_145_520, debt = true))
        assertEquals(-500, BalanceCorrection.targetFrom(-500, debt = false))
        assertEquals(-500, BalanceCorrection.shownFrom(-500, debt = false))
    }
}
