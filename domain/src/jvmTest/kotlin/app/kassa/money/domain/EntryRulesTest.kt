package app.kassa.money.domain

import kotlinx.datetime.TimeZone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EntryRulesTest {
    private val now = 1_767_268_800_000L // 2026-01-01 12:00:00 UTC

    private fun expense(amount: Long = 1250, category: Long? = 1, account: Long? = 2, note: String = "Lunch") =
        EntryRules.Draft(kind = TxKind.EXPENSE, amount = amount, accountId = account, categoryId = category, epochMillis = now, note = note)

    private fun transfer(from: Long? = 2, to: Long? = 3) =
        EntryRules.Draft(kind = TxKind.TRANSFER, amount = 500, accountId = from, toAccountId = to, epochMillis = now)

    @Test
    fun aCompleteEntryHasNoProblem() {
        assertNull(EntryRules.problem(expense()))
        assertNull(EntryRules.problem(expense().copy(kind = TxKind.INCOME)))
        assertNull(EntryRules.problem(transfer()))
    }

    @Test
    fun theAmountIsCheckedFirstThenTheNote() {
        assertEquals(EntryProblem(EntryField.AMOUNT, "Enter an amount."), EntryRules.problem(expense(amount = 0, category = null)))
        assertEquals(EntryField.AMOUNT, EntryRules.problem(expense(amount = -5))?.field)
        assertEquals("That's more than $100,000,000.", EntryRules.problem(expense(amount = InputRules.AMOUNT_MAX + 1))?.message)
        assertNull(EntryRules.problem(expense(amount = InputRules.AMOUNT_MAX)))
        val long = "x".repeat(InputRules.NOTE_MAX + 1)
        assertEquals(EntryProblem(EntryField.NOTE, "Keep the note to 120 characters."), EntryRules.problem(expense(note = long, category = null)))
        assertNull(EntryRules.problem(expense(note = "x".repeat(InputRules.NOTE_MAX))))
    }

    @Test
    fun expensesAndIncomeNeedACategoryThenAnAccount() {
        assertEquals(EntryProblem(EntryField.CATEGORY, "Pick a category."), EntryRules.problem(expense(category = null, account = null)))
        assertEquals(EntryProblem(EntryField.ACCOUNT, "Pick an account."), EntryRules.problem(expense(account = null)))
        assertEquals(EntryField.CATEGORY, EntryRules.problem(expense(category = null).copy(kind = TxKind.INCOME))?.field)
    }

    @Test
    fun transfersNeedAFromAndADifferentTo() {
        assertEquals(EntryProblem(EntryField.ACCOUNT, "Pick an account."), EntryRules.problem(transfer(from = null)))
        assertEquals(EntryProblem(EntryField.TO, "Pick where the money went."), EntryRules.problem(transfer(to = null)))
        assertEquals(EntryProblem(EntryField.TO, "Pick where the money went."), EntryRules.problem(transfer(from = 2, to = 2)))
    }

    @Test
    fun otherKindsCantBeSavedHere() {
        assertEquals(EntryField.AMOUNT, EntryRules.problem(expense().copy(kind = TxKind.BUY))?.field)
    }

    @Test
    fun theNextMissingInputFollowsTheTiles() {
        assertEquals(EntryField.CATEGORY, EntryRules.missing(TxKind.EXPENSE, null, null, null))
        assertEquals(EntryField.ACCOUNT, EntryRules.missing(TxKind.EXPENSE, 1, null, null))
        assertNull(EntryRules.missing(TxKind.EXPENSE, 1, 2, null))
        assertEquals(EntryField.ACCOUNT, EntryRules.missing(TxKind.TRANSFER, null, null, null))
        assertEquals(EntryField.TO, EntryRules.missing(TxKind.TRANSFER, null, 2, null))
        assertNull(EntryRules.missing(TxKind.TRANSFER, null, 2, 3))
    }

    @Test
    fun normalizingDropsWhatTheKindDoesntUseAndCleansTheNote() {
        val e = EntryRules.normalized(expense(note = "  Lunch \n at  work ").copy(toAccountId = 9))
        assertEquals("Lunch at work", e.note)
        assertNull(e.toAccountId)
        assertEquals(1L, e.categoryId)
        val t = EntryRules.normalized(transfer().copy(categoryId = 7))
        assertNull(t.categoryId)
        assertEquals(3L, t.toAccountId)
    }

    @Test
    fun onlyAccountsNewlyTouchedMustBeOpen() {
        assertEquals(listOf(2L, 3L), EntryRules.newlyTouched(transfer(), null))
        assertEquals(listOf(2L), EntryRules.newlyTouched(expense(), null))
        // Editing keeps its accounts: nothing new, even if one has been closed since.
        assertEquals(emptyList(), EntryRules.newlyTouched(transfer(), transfer()))
        // Moving the destination touches only the new one.
        assertEquals(listOf(4L), EntryRules.newlyTouched(transfer(to = 4), transfer()))
        assertEquals(listOf(5L), EntryRules.newlyTouched(expense(account = 5), expense()))
    }

    @Test
    fun theClosedAccountSentenceIsAndroids() {
        assertEquals("Old savings is closed. Reopen it first to add to it.", EntryRules.closedMessage("Old savings"))
    }

    @Test
    fun onlyATransferIntoInvestmentsWaitsToBeExplained() {
        assertTrue(EntryRules.waitsForExplanation(TxKind.TRANSFER, AccountType.INVESTMENT))
        assertFalse(EntryRules.waitsForExplanation(TxKind.TRANSFER, AccountType.SAVINGS))
        assertFalse(EntryRules.waitsForExplanation(TxKind.EXPENSE, AccountType.INVESTMENT))
        assertFalse(EntryRules.waitsForExplanation(TxKind.TRANSFER, null))
    }

    @Test
    fun whichEntriesTheEntryPageMayEdit() {
        fun editable(kind: TxKind, to: AccountType? = null, loan: Boolean = false, person: Boolean = false, split: Boolean = false) =
            EntryRules.isEditable(kind, to, loan, person, split)
        assertTrue(editable(TxKind.EXPENSE))
        assertTrue(editable(TxKind.INCOME))
        assertTrue(editable(TxKind.TRANSFER, AccountType.SAVINGS))
        assertTrue(editable(TxKind.TRANSFER, AccountType.CREDIT_CARD), "a card payment is a transfer, edited on the entry page as on Android")
        assertFalse(editable(TxKind.TRANSFER, AccountType.SAVINGS, loan = true), "a loan principal")
        assertFalse(editable(TxKind.EXPENSE, loan = true), "loan interest")
        assertFalse(editable(TxKind.EXPENSE, split = true))
        assertFalse(editable(TxKind.EXPENSE, person = true))
        for (k in listOf(TxKind.BUY, TxKind.SELL, TxKind.PERSON_IN, TxKind.PERSON_OUT, TxKind.ADJUST_IN, TxKind.ADJUST_OUT)) assertFalse(editable(k), k.name)
    }

    @Test
    fun pickersLeaveOutTheRightAccounts() {
        val checking = Account(id = 1, name = "Checking", type = AccountType.CHECKING)
        val card = Account(id = 2, name = "Card", type = AccountType.CREDIT_CARD)
        val card2 = Account(id = 3, name = "Card 2", type = AccountType.CREDIT_CARD)
        val all = listOf(checking, card, card2)
        assertEquals(emptySet(), EntryRules.excludedAccounts(TxKind.EXPENSE, EntryField.ACCOUNT, null, all))
        assertEquals(emptySet(), EntryRules.excludedAccounts(TxKind.INCOME, EntryField.ACCOUNT, null, all))
        assertEquals(setOf(2L, 3L), EntryRules.excludedAccounts(TxKind.TRANSFER, EntryField.ACCOUNT, null, all))
        assertEquals(setOf(3L), EntryRules.excludedAccounts(TxKind.TRANSFER, EntryField.ACCOUNT, 2, all))
        assertEquals(setOf(1L), EntryRules.excludedAccounts(TxKind.TRANSFER, EntryField.TO, 1, all))
        assertEquals(emptySet(), EntryRules.excludedAccounts(TxKind.TRANSFER, EntryField.TO, null, all))
    }

    @Test
    fun switchingKindDropsWhatNoLongerFits() {
        val e = expense().copy(toAccountId = null)
        val t = EntryRules.switched(e, TxKind.TRANSFER, accountIsCard = true)
        assertEquals(TxKind.TRANSFER, t.kind)
        assertNull(t.categoryId)
        assertNull(t.accountId, "a card can't be the source of a transfer")
        val keep = EntryRules.switched(e, TxKind.TRANSFER, accountIsCard = false)
        assertEquals(2L, keep.accountId)
        val back = EntryRules.switched(transfer(), TxKind.INCOME, accountIsCard = false)
        assertNull(back.toAccountId)
        assertEquals(2L, back.accountId)
        assertEquals(e, EntryRules.switched(e, TxKind.EXPENSE, accountIsCard = true))
    }

    @Test
    fun theSavedMomentKeepsOrChoosesATimeOfDay() {
        val utc = TimeZone.UTC
        val jan1 = localDateOfEpochDay(20_454)
        val jan5 = jan1.plusDays(4)
        // Today: now's time of day. Another day: noon.
        assertEquals(now, EntryRules.savedMillis(jan1, utc, now + 7_000, null) - 7_000)
        assertEquals(startOfDayMillis(jan5, utc) + 12 * 3_600_000L, EntryRules.savedMillis(jan5, utc, now, null))
        // Editing keeps the original time of day, including seconds, whatever date is picked.
        val original = startOfDayMillis(jan1, utc) + (9 * 3_600_000L) + (30 * 60_000L) + 12_345
        assertEquals(startOfDayMillis(jan5, utc) + (9 * 3_600_000L) + (30 * 60_000L) + 12_345, EntryRules.savedMillis(jan5, utc, now, original))
        assertEquals(original, EntryRules.savedMillis(jan1, utc, now, original))
    }

    @Test
    fun theSavedMomentFollowsTheZone() {
        val tokyo = TimeZone.of("Asia/Tokyo")
        val day = localDateOfEpochDay(20_454)
        val noon = EntryRules.savedMillis(day, tokyo, now, null)
        // 2026-01-01 12:00 UTC is 21:00 on 1 January in Tokyo, so "today" there is 1 January and keeps 21:00.
        assertEquals(now, noon)
        assertEquals(day, dateOfMillis(noon, tokyo))
    }
}
