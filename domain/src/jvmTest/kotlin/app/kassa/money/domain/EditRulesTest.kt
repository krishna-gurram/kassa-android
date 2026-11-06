package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditRulesTest {

    @Test
    fun aLoanAndAnInvestmentAccountKeepTheirKind() {
        assertTrue(EditRules.typeChoices(AccountType.LOAN).isEmpty())
        assertTrue(EditRules.typeChoices(AccountType.INVESTMENT).isEmpty())
    }

    @Test
    fun otherKindsMayChangeAmongTheSimpleOnes() {
        val choices = EditRules.typeChoices(AccountType.CHECKING)
        assertTrue(AccountType.CREDIT_CARD in choices)
        assertTrue(AccountType.FIXED_DEPOSIT in choices)
        assertFalse(AccountType.LOAN in choices)
        assertFalse(AccountType.INVESTMENT in choices)
    }

    @Test
    fun keepingTheKindIsAlwaysFine() {
        assertNull(EditRules.typeProblem(AccountType.LOAN, AccountType.LOAN))
        assertNull(EditRules.typeProblem(AccountType.CHECKING, AccountType.CREDIT_CARD))
        assertEquals("That kind of account can't be changed to that.", EditRules.typeProblem(AccountType.CHECKING, AccountType.LOAN))
        assertEquals("That kind of account can't be changed to that.", EditRules.typeProblem(AccountType.INVESTMENT, AccountType.CASH))
    }

    @Test
    fun aClosedAccountRefusesABalanceChangeButNotARename() {
        val closed = Account(id = 4, name = "Old savings", type = AccountType.SAVINGS, openingBalance = 0, closedDay = 20_000)
        assertEquals("Old savings is closed. Reopen it first to add to it.", EditRules.closedProblem(closed, closed.copy(openingBalance = 500)))
        assertNull(EditRules.closedProblem(closed, closed.copy(name = "Old pot")))
        assertNull(EditRules.closedProblem(closed.copy(closedDay = null), closed.copy(closedDay = null, openingBalance = 500)))
    }

    @Test
    fun aCardCantBePaidFromItself() {
        assertEquals("A card can't be paid from itself.", EditRules.payFromProblem(7, 7))
        assertNull(EditRules.payFromProblem(7, 8))
        assertNull(EditRules.payFromProblem(7, null))
    }

    @Test
    fun onlyASettledPersonArchives() {
        assertFalse(EditRules.archiveBlocked(0))
        assertTrue(EditRules.archiveBlocked(-1))
        assertTrue(EditRules.archiveBlocked(2_500))
        assertEquals(2_500, EditRules.archiveBlockedSize(-2_500))
    }

    @Test
    fun aStartingBalanceChangesOnlyWhenTyped() {
        assertNull(EditRules.startingChange(1_000, 1_000))
        assertEquals(0L, EditRules.startingChange(1_000, 0))
        assertEquals(-300L, EditRules.startingChange(0, -300))
    }

    @Test
    fun theEditFormOpensOnWhatWasOwed() {
        val owed = EditRules.personForm("Sam", 12_345)
        assertTrue(owed.theyOwe)
        assertEquals("123.45", owed.amount)
        val owing = EditRules.personForm("Sam", -500)
        assertFalse(owing.theyOwe)
        assertEquals(Money.toInput(500), owing.amount)
        assertEquals("", EditRules.personForm("Sam", 0).amount)
    }

    @Test
    fun anEditedPersonKeepsItsOwnNameFreeButNotAnothers() {
        val form = EditRules.personForm("Sam", 0)
        assertTrue(form.problems(listOf("Alex")).isEmpty())
        assertTrue(PersonField.NAME in form.copy(name = "alex").problems(listOf("Alex")))
    }
}
