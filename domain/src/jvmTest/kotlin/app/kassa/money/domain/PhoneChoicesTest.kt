package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PhoneChoicesTest {

    @Test
    fun unknownThemeAndTabNamesFallBackToTheDefaults() {
        assertEquals(ThemeChoice.DARK, ThemeChoice.of("DARK"))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.of("purple"))
        assertEquals(ThemeChoice.SYSTEM, ThemeChoice.of(null))
        assertEquals(HomeTab.ACCOUNTS, HomeTab.of("ACCOUNTS"))
        assertEquals(HomeTab.TODAY, HomeTab.of("SETTINGS"))
        assertEquals(HomeTab.TODAY, HomeTab.of(null))
    }

    @Test
    fun theBudgetMonthStartsOnADayEveryMonthHas() {
        assertEquals(1, BudgetMonthStart.clamp(0))
        assertEquals(28, BudgetMonthStart.clamp(31))
        assertEquals(15, BudgetMonthStart.clamp(15))
        assertTrue(BudgetMonthStart.isValid("28"))
        assertTrue(BudgetMonthStart.isValid("1"))
        assertFalse(BudgetMonthStart.isValid("29"))
        assertFalse(BudgetMonthStart.isValid("0"))
        assertFalse(BudgetMonthStart.isValid(""))
        assertTrue(BudgetMonthStart.QUICK_DAYS.all { BudgetMonthStart.isValid(it.toString()) })
    }

    @Test
    fun onlyOpenPlainBalancesBecomePeople() {
        fun acct(type: AccountType, archived: Boolean = false, closed: Int? = null) =
            Account(id = 1, name = "x", type = type, archived = archived, closedDay = closed)
        assertTrue(AccountToPerson.isConvertible(acct(AccountType.CHECKING)))
        assertTrue(AccountToPerson.isConvertible(acct(AccountType.OTHER_ASSET)))
        assertTrue(AccountToPerson.isConvertible(acct(AccountType.OTHER_DEBT)))
        assertFalse(AccountToPerson.isConvertible(acct(AccountType.CREDIT_CARD)))
        assertFalse(AccountToPerson.isConvertible(acct(AccountType.LOAN)))
        assertFalse(AccountToPerson.isConvertible(acct(AccountType.INVESTMENT)))
        assertFalse(AccountToPerson.isConvertible(acct(AccountType.CHECKING, archived = true)))
        assertFalse(AccountToPerson.isConvertible(acct(AccountType.CHECKING, closed = 19_000)))
    }

    @Test
    fun theNameIsCheckedLikeAnyPersonsName() {
        assertNull(AccountToPerson.nameProblem("Sam", listOf("Ria")))
        assertEquals(true, AccountToPerson.nameProblem("  ", emptyList()) != null)
        assertEquals(true, AccountToPerson.nameProblem("ria", listOf("Ria")) != null)
        assertEquals("Sam Lee", AccountToPerson.cleanName("  Sam   Lee "))
        assertEquals("Balance from Old tab", AccountToPerson.note("Old tab"))
    }
}
