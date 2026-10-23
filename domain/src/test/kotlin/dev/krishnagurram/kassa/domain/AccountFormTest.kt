package dev.krishnagurram.kassa.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AccountFormTest {

    @Test
    fun aNewCardNeedsANameAndBothDays() {
        val form = AccountForm(type = AccountType.CREDIT_CARD, name = " ", statementDay = "0")
        assertEquals(setOf(AccountField.NAME, AccountField.STATEMENT_DAY, AccountField.DUE_DAY), form.problems(emptyList()).keys)
    }

    @Test
    fun namesAreUniqueIgnoringCase() {
        val form = AccountForm(name = "chase checking")
        assertTrue(AccountField.NAME in form.problems(listOf("Chase Checking")))
        assertTrue(form.problems(listOf("Chase Savings")).isEmpty())
    }

    @Test
    fun aDebtIsStoredNegative() {
        val card = AccountForm(type = AccountType.CREDIT_CARD, name = "Citi", balance = "250", statementDay = "16", dueDay = "10")
        val saved = card.toAccount(Account(name = "", type = AccountType.CREDIT_CARD), currentBalance = 0)
        assertEquals(-25_000, saved.openingBalance)
        assertEquals(16, saved.statementDay)
    }

    @Test
    fun editingShiftsOnlyTheStartingBalance() {
        val account = Account(id = 3, name = "Chase", type = AccountType.CHECKING, openingBalance = 10_000)
        // Entries have brought it to 400.00; the person says it's really 450.00.
        val saved = AccountForm.of(account, 40_000).copy(balance = "450").toAccount(account, 40_000)
        assertEquals(15_000, saved.openingBalance)
    }

    @Test
    fun anOverpaidCardKeepsItsCreditThroughAnEdit() {
        val card = Account(id = 1, name = "Citi", type = AccountType.CREDIT_CARD, openingBalance = 0, statementDay = 1, dueDay = 20)
        val form = AccountForm.of(card, 5_000)
        assertEquals("-50", form.balance)
        assertEquals(0, form.toAccount(card, 5_000).openingBalance)
    }

    @Test
    fun cardFieldsAreDroppedForOtherTypes() {
        val saved = AccountForm(type = AccountType.SAVINGS, name = "Ally", statementDay = "5", limit = "1000")
            .toAccount(Account(name = "", type = AccountType.SAVINGS), 0)
        assertNull(saved.statementDay)
        assertNull(saved.creditLimit)
    }
}
