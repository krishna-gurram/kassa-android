package app.kassa.money.domain

import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PaymentsTest {

    private val zone = TimeZone.UTC
    private fun millis(date: String) = millisAt(LocalDate.parse(date), 12, 0, zone)

    private val card = Account(id = 10, name = "Card", type = AccountType.CREDIT_CARD, statementDay = 5, dueDay = 25, payFromId = 1)
    private val checking = Account(id = 1, name = "Checking", type = AccountType.CHECKING)

    private fun charge(date: String, cents: Long) = Transaction(kind = TxKind.EXPENSE, amount = cents, accountId = 10, epochMillis = millis(date))
    private fun payment(date: String, cents: Long) =
        Transaction(kind = TxKind.TRANSFER, amount = cents, accountId = 1, toAccountId = 10, epochMillis = millis(date))

    /** Charges 100 before the statement of 5 Oct, 40 after it; today is 12 Oct. */
    private val history = listOf(charge("2026-09-20", 10_000), charge("2026-10-08", 4_000))
    private val today = LocalDate(2026, 10, 12)
    private fun snap(extra: List<Transaction> = emptyList()) = CardCycle.snapshot(card, history + extra, today, zone)

    // ---------- CardPayment ----------

    @Test
    fun theSheetOffersWhatIsOwedAndStartsOnTheFirstOfIt() {
        val s = snap()
        assertEquals(10_000, s.payable)
        assertEquals(14_000, s.totalBalance)
        val options = CardPayment.options(s)
        assertEquals(listOf(CardPayment.Choice.PAYABLE, CardPayment.Choice.TOTAL, CardPayment.Choice.OTHER), options.map { it.choice })
        assertEquals(listOf(10_000L, 14_000L, 0L), options.map { it.amount })
        assertEquals(CardPayment.Choice.PAYABLE, CardPayment.defaultChoice(s))
    }

    @Test
    fun withNothingPayableTheTotalIsOfferedIfAnythingIsOwed() {
        val paidOff = snap(listOf(payment("2026-10-09", 10_000)))
        assertEquals(0, paidOff.payable)
        assertEquals(4_000, paidOff.totalBalance)
        assertEquals(listOf(CardPayment.Choice.TOTAL, CardPayment.Choice.OTHER), CardPayment.options(paidOff).map { it.choice })
        assertEquals(CardPayment.Choice.TOTAL, CardPayment.defaultChoice(paidOff))
        val clear = snap(listOf(payment("2026-10-09", 14_000)))
        assertEquals(listOf(CardPayment.Choice.OTHER), CardPayment.options(clear).map { it.choice })
        assertEquals(CardPayment.Choice.OTHER, CardPayment.defaultChoice(clear))
    }

    @Test
    fun onlyTheCardsOwnOpenPayFromAccountIsPreselected() {
        assertEquals(1L, CardPayment.defaultPayFrom(card, listOf(checking, card)))
        assertNull(CardPayment.defaultPayFrom(card, listOf(checking.copy(closedDay = 100), card)), "closed")
        assertNull(CardPayment.defaultPayFrom(card, listOf(checking.copy(archived = true), card)), "archived")
        assertNull(CardPayment.defaultPayFrom(card.copy(payFromId = null), listOf(checking, card)), "nothing is guessed")
    }

    @Test
    fun aCardPaymentNeedsAnAmountWithinBoundsAndAnAccount() {
        Money.style = Currencies.fallback
        assertEquals(EntryField.AMOUNT to "Enter an amount.", CardPayment.problem(0, 1))
        assertEquals(EntryField.AMOUNT, CardPayment.problem(InputRules.AMOUNT_MAX + 1, 1)?.first)
        assertEquals(EntryField.ACCOUNT to "Pick the account it's paid from.", CardPayment.problem(100, null))
        assertNull(CardPayment.problem(100, 1))
        assertNull(CardPayment.problem(InputRules.AMOUNT_MAX, 1))
    }

    // ---------- CardPageRules ----------

    @Test
    fun theDueLineFollowsTheWording() {
        val s = snap()
        assertEquals(CardPageRules.DueTone.NEUTRAL, CardPageRules.dueTone(s, today))
        // Due on 25 Oct: 13 days away.
        assertEquals(CardPageRules.StatusLine(CardPageRules.Status.DUE_IN_DAYS, 13), CardPageRules.status(s, today, false))
        assertEquals(CardPageRules.StatusLine(CardPageRules.Status.DUE_TOMORROW, 0), CardPageRules.status(s, LocalDate(2026, 10, 24), false))
        assertEquals(CardPageRules.StatusLine(CardPageRules.Status.DUE_TODAY, 0), CardPageRules.status(s, LocalDate(2026, 10, 25), false))
        assertEquals(CardPageRules.StatusLine(CardPageRules.Status.DAYS_LATE, 2), CardPageRules.status(s, LocalDate(2026, 10, 27), false))
        assertEquals(CardPageRules.DueTone.ATTENTION, CardPageRules.dueTone(s, LocalDate(2026, 10, 23)))
        val full = snap(listOf(payment("2026-10-09", 10_000)))
        assertEquals(CardPageRules.Status.PAID_IN_FULL, CardPageRules.status(full, today, false).status)
        assertEquals(CardPageRules.DueTone.POSITIVE, CardPageRules.dueTone(full, today))
        val extra = snap(listOf(payment("2026-10-09", 15_900)))
        assertEquals(CardPageRules.Status.PAID_EXTRA, CardPageRules.status(extra, today, false).status)
        val none = CardCycle.snapshot(card, emptyList(), today, zone)
        assertEquals(CardPageRules.Status.NOTHING_DUE, CardPageRules.status(none, today, false).status)
    }

    @Test
    fun whileHiddenTheLineOnlySaysWhenItIsDue() {
        for (extra in listOf(emptyList(), listOf(payment("2026-10-09", 10_000)), listOf(payment("2026-10-09", 15_900)))) {
            assertEquals(CardPageRules.Status.DUE_ON, CardPageRules.status(snap(extra), today, true).status)
        }
    }

    @Test
    fun aStatementRunsUpToTheDayBeforeItsOwnDate() {
        val s = snap()
        val current = CardPageRules.window(s, 5, 0, today)
        assertEquals(LocalDate(2026, 10, 5), current.from)
        assertEquals(today, current.to)
        val last = CardPageRules.window(s, 5, 1, today)
        assertEquals(LocalDate(2026, 9, 5), last.from)
        assertEquals(LocalDate(2026, 10, 5), last.to)
        val before = CardPageRules.window(s, 5, 2, today)
        assertEquals(LocalDate(2026, 8, 5), before.from)
        assertEquals(LocalDate(2026, 9, 5), before.to)
        // The statement date itself starts the next cycle.
        assertTrue(current.holds(LocalDate(2026, 10, 5)))
        assertFalse(last.holds(LocalDate(2026, 10, 5)))
        assertTrue(last.holds(LocalDate(2026, 10, 4)))
        assertFalse(current.holds(LocalDate(2026, 10, 13)), "tomorrow is not yet")
    }

    @Test
    fun eachWindowListsItsOwnEntriesNewestFirstAndAddsThemUp() {
        val all = history + listOf(payment("2026-10-09", 2_500), charge("2026-10-05", 700))
        val s = snap(listOf(payment("2026-10-09", 2_500), charge("2026-10-05", 700)))
        val current = CardPageRules.entriesIn(10, all, CardPageRules.window(s, 5, 0, today), zone)
        assertEquals(listOf(millis("2026-10-09"), millis("2026-10-08"), millis("2026-10-05")), current.map { it.epochMillis })
        assertEquals(4_700, CardPageRules.charges(10, current))
        assertEquals(2_500, CardPageRules.credits(10, current))
        val last = CardPageRules.entriesIn(10, all, CardPageRules.window(s, 5, 1, today), zone)
        assertEquals(listOf(millis("2026-09-20")), last.map { it.epochMillis })
        assertEquals(10_000, CardPageRules.charges(10, last))
        assertEquals(0, CardPageRules.credits(10, last))
        val oldest = LocalDate(2026, 9, 20)
        assertTrue(CardPageRules.canGoBack(oldest, CardPageRules.window(s, 5, 0, today)), "the last statement still has an entry")
        assertFalse(CardPageRules.canGoBack(oldest, CardPageRules.window(s, 5, 1, today)), "nothing earlier than the oldest entry")
        assertFalse(CardPageRules.canGoBack(null, CardPageRules.window(s, 5, 0, today)))
    }

    // ---------- PersonPayment ----------

    @Test
    fun aPaymentStartsOnSettlingTheBalance() {
        assertEquals(TxKind.PERSON_IN, PersonPayment.defaultKind(11_500), "they owe you: they pay")
        assertEquals(TxKind.PERSON_OUT, PersonPayment.defaultKind(-300), "you owe them: you pay")
        assertEquals(TxKind.PERSON_IN, PersonPayment.defaultKind(0))
        assertEquals(11_500, PersonPayment.suggestedAmount(11_500))
        assertEquals(300, PersonPayment.suggestedAmount(-300))
        assertEquals(0, PersonPayment.suggestedAmount(0))
    }

    @Test
    fun theGeneratedNoteFollowsTheDirectionAndAWrittenOneStays() {
        assertEquals("Sam paid you", PersonPayment.defaultNote(TxKind.PERSON_IN, "Sam"))
        assertEquals("You paid Sam", PersonPayment.defaultNote(TxKind.PERSON_OUT, "Sam"))
        assertEquals("Sam paid you", PersonPayment.noteFor(TxKind.PERSON_IN, "Sam", null, null))
        assertEquals("Lent for a deposit", PersonPayment.noteFor(TxKind.PERSON_OUT, "Sam", TxKind.PERSON_OUT, "Lent for a deposit"))
        assertEquals("Sam paid you", PersonPayment.noteFor(TxKind.PERSON_IN, "Sam", TxKind.PERSON_OUT, "Lent for a deposit"), "direction changed")
        assertEquals("You paid Sam", PersonPayment.noteFor(TxKind.PERSON_OUT, "Sam", TxKind.PERSON_OUT, "  "), "a blank note is replaced")
    }

    @Test
    fun aPersonPaymentNeedsAnAmountAnAccountAndAPaymentKind() {
        Money.style = Currencies.fallback
        fun draft(kind: TxKind = TxKind.PERSON_IN, amount: Long = 500, account: Long? = 1, note: String = "") =
            PersonPayment.Draft(null, 7, kind, amount, account, 0, note)
        assertNull(PersonPayment.problem(draft()))
        assertEquals(EntryField.AMOUNT to "Enter an amount.", PersonPayment.problem(draft(amount = 0)))
        assertEquals(EntryField.ACCOUNT to "Pick an account.", PersonPayment.problem(draft(account = null)))
        assertEquals(EntryField.NOTE, PersonPayment.problem(draft(note = "x".repeat(121)))?.first)
        assertEquals(EntryField.AMOUNT, PersonPayment.problem(draft(kind = TxKind.EXPENSE))?.first)
        assertEquals(EntryField.AMOUNT, PersonPayment.problem(draft(amount = InputRules.AMOUNT_MAX + 1))?.first)
    }

    @Test
    fun paymentsGoThroughOpenBankCashPrepaidAndCardAccountsAndKeepTheEditedOne() {
        val accounts = listOf(
            checking, Account(id = 2, name = "Savings", type = AccountType.SAVINGS), Account(id = 3, name = "Wallet", type = AccountType.CASH),
            Account(id = 4, name = "Prepaid", type = AccountType.PREPAID), card, Account(id = 5, name = "Loan", type = AccountType.LOAN),
            Account(id = 6, name = "Shares", type = AccountType.INVESTMENT), Account(id = 7, name = "Old", type = AccountType.SAVINGS, closedDay = 9),
            Account(id = 8, name = "Gone", type = AccountType.CHECKING, archived = true),
        )
        assertEquals(setOf(1L, 2L, 3L, 4L, 10L), PersonPayment.sources(accounts).map { it.id }.toSet())
        assertEquals(setOf(1L, 2L, 3L, 4L, 10L, 7L), PersonPayment.sources(accounts, keep = 7).map { it.id }.toSet())
    }

    @Test
    fun onlyAnAccountNewlyTouchedHasToBeOpen() {
        assertEquals(listOf(3L), PersonPayment.newlyTouched(3, null))
        assertEquals(emptyList(), PersonPayment.newlyTouched(3, 3), "an edit that keeps its account")
        assertEquals(listOf(4L), PersonPayment.newlyTouched(4, 3))
    }

    // ---------- PersonHistory ----------

    @Test
    fun aPersonsHistoryIsSplitsStartingBalanceAndPaymentsNewestFirst() {
        val loans = listOf(
            LoanEntry(1, 7, 4_500, millis("2026-01-10"), "Dinner split", 99),
            LoanEntry(2, 7, 2_000, millis("2026-01-01"), "Starting balance"),
            LoanEntry(3, 8, 9_999, millis("2026-01-02")),
        )
        val txs = listOf(
            Transaction(id = 10, kind = TxKind.PERSON_OUT, amount = 10_000, accountId = 1, personId = 7, epochMillis = millis("2026-03-12"), note = "Lent"),
            Transaction(id = 11, kind = TxKind.PERSON_IN, amount = 3_000, accountId = 1, personId = 7, epochMillis = millis("2026-05-20")),
            Transaction(id = 12, kind = TxKind.PERSON_IN, amount = 1_000, accountId = 1, personId = 8, epochMillis = millis("2026-05-21")),
            Transaction(id = 13, kind = TxKind.EXPENSE, amount = 700, accountId = 1, epochMillis = millis("2026-05-22")),
        )
        val lines = PersonHistory.lines(7, loans, txs)
        assertEquals(listOf(-3_000L, 10_000L, 4_500L, 2_000L), lines.map { it.amount })
        assertEquals(listOf(true, true, false, false), lines.map { it.payment })
        assertEquals(listOf<Long?>(11, 10, 99, null), lines.map { it.transactionId })
        assertEquals(listOf(TxKind.PERSON_IN, TxKind.PERSON_OUT, null, null), lines.map { it.kind })
        assertEquals(13_500, lines.sumOf { it.amount }, "what they owe: 2000 + 4500 + 10000 - 3000")
        assertEquals(millis("2026-01-01"), PersonHistory.openSince(lines))
    }

    @Test
    fun theBalanceIsOpenSinceItLastLeftZero() {
        fun line(day: String, amount: Long) = PersonHistory.Line(millis(day), "", amount, null, false, null)
        assertNull(PersonHistory.openSince(emptyList()))
        assertEquals(millis("2026-02-01"), PersonHistory.openSince(listOf(line("2026-03-01", -200), line("2026-02-15", 300), line("2026-02-01", 200))))
        // Settled on 1 March, owed again from 10 March.
        assertEquals(millis("2026-03-10"), PersonHistory.openSince(listOf(line("2026-03-10", 800), line("2026-03-01", -500), line("2026-02-01", 500))))
        assertNull(PersonHistory.openSince(listOf(line("2026-03-01", -500), line("2026-02-01", 500))), "settled")
    }
}
