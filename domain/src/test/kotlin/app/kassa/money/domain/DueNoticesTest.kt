package app.kassa.money.domain

import app.kassa.money.domain.DueNotices.Moment
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DueNoticesTest {
    private fun day(text: String): Int = LocalDate.parse(text).toEpochDay().toInt()
    private fun at(text: String, hour: Int = 9, minute: Int = 0) = Moment(day(text), hour * 60 + minute)

    private val rules = DueRules(DueRule(true, 0, 540), DueRule(true, 0, 540), DueRule(false, 0, 540))
    private val card = DueItem(DueKind.CARD, 1, "Card A", day("2026-11-14"), 124_030, clearKey = "card:1:2026-10-16")
    private val loan = DueItem(DueKind.LOAN, 2, "Car loan", day("2026-11-01"), 58_000)
    private val bill = DueItem(DueKind.BILL, 3, "Internet", day("2026-11-20"), 6_999)

    @Test
    fun dueDayOf31FallsOnTheLastDayOfShortMonths() {
        assertEquals(day("2026-02-28"), DueNotices.dueDayIn(2026, 2, 31))
        assertEquals(day("2028-02-29"), DueNotices.dueDayIn(2028, 2, 31))
        assertEquals(day("2028-02-29"), DueNotices.dueDayIn(2028, 2, 29))
        assertEquals(day("2026-04-30"), DueNotices.dueDayIn(2026, 4, 31))
        assertEquals(day("2026-03-31"), DueNotices.dueDayIn(2026, 3, 31))
        // 1900 was not a leap year, 2000 was.
        assertEquals(day("2100-02-28"), DueNotices.dueDayIn(2100, 2, 30))
        assertEquals(day("2000-02-29"), DueNotices.dueDayIn(2000, 2, 30))
    }

    @Test
    fun notifyDayIsTheDueDayMinusDaysBefore() {
        assertEquals(day("2026-11-14"), DueNotices.notifyDay(day("2026-11-14"), 0))
        assertEquals(day("2026-11-11"), DueNotices.notifyDay(day("2026-11-14"), 3))
        // Across a month and a year, and into a leap day.
        assertEquals(day("2026-12-29"), DueNotices.notifyDay(day("2027-01-02"), 4))
        assertEquals(day("2026-02-26"), DueNotices.notifyDayIn(2026, 3, 1, 3))
        assertEquals(day("2028-02-27"), DueNotices.notifyDayIn(2028, 3, 1, 3))
        assertEquals(day("2026-02-25"), DueNotices.notifyDayIn(2026, 2, 31, 3))
        // Out of range counts as the nearest end.
        assertEquals(day("2026-11-14"), DueNotices.notifyDay(day("2026-11-14"), -2))
        assertEquals(day("2026-10-31"), DueNotices.notifyDay(day("2026-11-14"), 40))
    }

    @Test
    fun daysBeforeIsBoundedZeroToFourteen() {
        assertNull(DueNotices.daysProblem(0))
        assertNull(DueNotices.daysProblem(14))
        assertNotNull(DueNotices.daysProblem(15))
        assertNotNull(DueNotices.daysProblem(-1))
        assertNotNull(DueNotices.daysProblem(null))
    }

    @Test
    fun theNotificationsPageShowsOnTheDueDayAndWhileOverdue() {
        assertFalse(DueNotices.isWaiting(card, day("2026-11-13")))
        assertTrue(DueNotices.isWaiting(card, day("2026-11-14")))
        assertTrue(DueNotices.isWaiting(card, day("2026-11-20")))
        assertFalse(DueNotices.isOverdue(card, day("2026-11-14")))
        assertTrue(DueNotices.isOverdue(card, day("2026-11-15")))
    }

    @Test
    fun ringsAtItsTimeOnTheDayBefore() {
        val threeDays = rules.copy(card = DueRule(true, 3, 9 * 60))
        assertTrue(DueNotices.ringing(listOf(card), threeDays, at("2026-11-10", 23, 59), emptySet(), emptySet()).isEmpty())
        assertTrue(DueNotices.ringing(listOf(card), threeDays, at("2026-11-11", 8, 59), emptySet(), emptySet()).isEmpty())
        assertEquals(listOf(card), DueNotices.ringing(listOf(card), threeDays, at("2026-11-11"), emptySet(), emptySet()))
    }

    @Test
    fun aMissedOneRingsWithinTheGraceAndIsLetGoAfter() {
        val now = at("2026-11-14", 11, 59)
        assertEquals(listOf(card), DueNotices.ringing(listOf(card), rules, now, emptySet(), emptySet()))
        assertEquals(listOf(card), DueNotices.ringing(listOf(card), rules, at("2026-11-14", 12, 0), emptySet(), emptySet()))
        assertTrue(DueNotices.ringing(listOf(card), rules, at("2026-11-14", 12, 1), emptySet(), emptySet()).isEmpty())
        // Past midnight still counts as within three hours of a late evening time.
        val late = rules.copy(card = DueRule(true, 0, 23 * 60 + 30))
        assertEquals(listOf(card), DueNotices.ringing(listOf(card), late, at("2026-11-15", 1, 0), emptySet(), emptySet()))
    }

    @Test
    fun neverTwiceForTheSameItemAndDueDate() {
        val now = at("2026-11-14", 9, 5)
        assertTrue(DueNotices.ringing(listOf(card), rules, now, setOf(card.key), emptySet()).isEmpty())
        // The next statement's due date is a new key.
        val nextMonth = card.copy(dueDay = day("2026-12-14"))
        assertEquals(listOf(nextMonth), DueNotices.ringing(listOf(nextMonth), rules, at("2026-12-14"), setOf(card.key), emptySet()))
    }

    @Test
    fun clearedAndSwitchedOffKindsAreSilent() {
        val now = at("2026-11-14")
        assertTrue(DueNotices.ringing(listOf(card), rules, now, emptySet(), setOf("card:1:2026-10-16")).isEmpty())
        // Bills are off.
        assertTrue(DueNotices.ringing(listOf(bill), rules, at("2026-11-20"), emptySet(), emptySet()).isEmpty())
        assertNull(DueNotices.momentOf(bill, rules))
    }

    @Test
    fun whatRingsTogetherGoesEarliestDueFirst() {
        val now = at("2026-11-14")
        val loanLate = loan.copy(dueDay = day("2026-11-14"))
        val other = card.copy(id = 9, name = "Card B")
        assertEquals(listOf(card, other, loanLate).map { it.key }.sorted(), DueNotices.ringing(listOf(loanLate, other, card), rules, now, emptySet(), emptySet()).map { it.key }.sorted())
        assertEquals(listOf("Card A", "Card B", "Car loan"), DueNotices.ringing(listOf(loanLate, other, card), rules, now, emptySet(), emptySet()).map { it.name })
    }

    @Test
    fun theNextAlarmIsTheEarliestItem() {
        val now = at("2026-11-01", 0, 10)
        val next = DueNotices.next(listOf(card, loan), rules, now, emptySet(), emptySet())
        assertEquals(DueNotices.Next(at("2026-11-01"), true), next)
        // Different times: the earlier one wins.
        val mixed = rules.copy(loan = DueRule(true, 3, 7 * 60 + 30))
        assertEquals(at("2026-10-29", 7, 30), DueNotices.next(listOf(loan), mixed, at("2026-10-29", 0, 10), emptySet(), emptySet()).moment)
    }

    @Test
    fun postedAndPastItemsAreSkippedWhenLookingAhead() {
        val now = at("2026-11-01", 9, 30)
        val next = DueNotices.next(listOf(loan, card), rules, now, setOf(loan.key), emptySet())
        // The loan is posted, the card is days away: only the daily look is next.
        assertEquals(DueNotices.Next(at("2026-11-02", 0, 5), false), next)
    }

    @Test
    fun withNothingSoonTheDailyLookComesJustAfterMidnight() {
        val next = DueNotices.next(listOf(card), rules, at("2026-10-30", 10, 0), emptySet(), emptySet())
        assertEquals(DueNotices.Next(at("2026-10-31", 0, 5), false), next)
        assertEquals(DueNotices.Next(at("2026-10-30", 0, 5), false), DueNotices.next(emptyList(), rules, at("2026-10-30", 0, 1), emptySet(), emptySet()))
        // At or past 00:05 the next look is tomorrow's.
        assertEquals(DueNotices.Next(at("2026-10-31", 0, 5), false), DueNotices.next(emptyList(), rules, at("2026-10-30", 0, 5), emptySet(), emptySet()))
    }

    @Test
    fun theDailyLookComesFirstWhenAnItemIsFurtherOffThanTomorrowMidnight() {
        val next = DueNotices.next(listOf(card), rules, at("2026-11-14", 9, 30), setOf(card.key), emptySet())
        assertFalse(next.forNotification)
    }

    @Test
    fun wordsFollowTheDays() {
        assertEquals(DueText("Card payment due today", "Card A: ${Money.format(124_030)}"), DueNotices.text(card, 0))
        assertEquals("Loan payment due tomorrow", DueNotices.text(loan, 1).title)
        assertEquals("Bill due in 3 days", DueNotices.text(bill, 3).title)
        assertEquals("Bill due in 14 days", DueNotices.text(bill, 14).title)
        assertEquals("Card payment overdue", DueNotices.text(card, -1).title)
    }

    @Test
    fun theAmountCanBeLeftOut() {
        assertEquals("Card A", DueNotices.text(card, 0, showAmount = false).body)
        assertEquals("Internet", DueNotices.text(bill.copy(amount = null), 0).body)
    }

    // ---------- what is due, from the data ----------

    private val zone = ZoneOffset.UTC
    private val today = LocalDate.parse("2026-10-30")
    private fun millis(d: String) = LocalDate.parse(d).atTime(12, 0).toInstant(zone).toEpochMilli()

    private val creditCard = Account(id = 1, name = "Card A", type = AccountType.CREDIT_CARD, statementDay = 16, dueDay = 14)
    private val loanAccount = Account(id = 2, name = "Car loan", type = AccountType.LOAN)
    private val terms = LoanTerms(
        accountId = 2, type = InterestType.REDUCING, rate = 60_000, payment = 58_000, nextPaymentDay = day("2026-11-01"), dueDay = 1,
    )
    private fun build(
        accounts: List<Account> = listOf(creditCard, loanAccount),
        balances: Map<Long, Long> = mapOf(1L to -30_000L, 2L to -2_000_000L),
        history: List<Transaction> = listOf(Transaction(kind = TxKind.EXPENSE, amount = 30_000, accountId = 1, categoryId = 1, epochMillis = millis("2026-10-05"), note = "Shop")),
        bills: List<Recurring> = emptyList(),
        payments: List<LoanPayment> = emptyList(),
        forPhone: Boolean = false,
    ) = DueItems.build(accounts, balances, history, bills, mapOf(2L to terms), emptyList(), payments, today, zone, forPhone)

    @Test
    fun aCardWithBalancePayableIsDueOnItsDueDate() {
        val item = build().first { it.kind == DueKind.CARD }
        assertEquals(30_000, item.amount)
        assertEquals(day("2026-11-14"), item.dueDay)
        assertEquals("card:1:2026-10-16", item.clearKey)
    }

    @Test
    fun aCardWithNothingPayableIsLeftOut() {
        assertTrue(build(balances = mapOf(1L to 0L, 2L to -2_000_000L), history = emptyList()).none { it.kind == DueKind.CARD })
    }

    @Test
    fun aCardWithoutItsOwnDueDayCantNotifyTheDefaultCanStillBeShown() {
        val noDue = creditCard.copy(dueDay = null)
        assertTrue(build(accounts = listOf(noDue), forPhone = true).isEmpty())
        assertEquals(1, build(accounts = listOf(noDue)).size)
    }

    @Test
    fun aLoanIsDueOnItsNextPaymentDayUntilThatPaymentIsRecorded() {
        val item = build().first { it.kind == DueKind.LOAN }
        assertEquals(day("2026-11-01"), item.dueDay)
        assertTrue(item.amount!! >= 58_000)
        val paid = LoanPayment(
            accountId = 2, day = day("2026-11-01"), amount = 58_000, principalEntryId = null, interestEntryId = null, otherEntryId = null,
            escrow = 0, charge = 0, edited = false, estimate = 0, scheduled = true,
        )
        assertTrue(build(payments = listOf(paid)).none { it.kind == DueKind.LOAN })
        // An older payment doesn't count for this due date.
        assertEquals(1, build(payments = listOf(paid.copy(day = day("2026-10-01")))).count { it.kind == DueKind.LOAN })
    }

    @Test
    fun aLoanWithNothingOwedOrNoTermsIsLeftOut() {
        assertTrue(build(balances = mapOf(2L to 0L)).none { it.kind == DueKind.LOAN })
        assertTrue(DueItems.build(listOf(loanAccount), mapOf(2L to -5L), emptyList(), emptyList(), emptyMap(), emptyList(), emptyList(), today, zone).isEmpty())
    }

    @Test
    fun archivedAccountsAreLeftOut() {
        assertTrue(build(accounts = listOf(creditCard.copy(archived = true), loanAccount.copy(archived = true))).isEmpty())
    }

    @Test
    fun aBillIsDueOnItsDayUnlessHandledOrAlreadyEntered() {
        val internet = Recurring(id = 3, name = "Internet", categoryId = 7, accountId = 1, dayOfMonth = 20, fixedAmount = 6_999)
        val item = build(bills = listOf(internet)).first { it.kind == DueKind.BILL }
        assertEquals(day("2026-10-20"), item.dueDay)
        assertEquals(6_999, item.amount)
        // October's isn't handled yet, so it is still waiting, overdue; handled, November's is next.
        val handled = build(bills = listOf(internet.copy(handledMonth = 202610))).first { it.kind == DueKind.BILL }
        assertEquals(day("2026-11-20"), handled.dueDay)
        val entered = Transaction(kind = TxKind.EXPENSE, amount = 6_999, accountId = 1, categoryId = 7, epochMillis = millis("2026-10-20"), note = "Internet")
        assertEquals(day("2026-11-20"), build(bills = listOf(internet), history = listOf(entered)).first { it.kind == DueKind.BILL }.dueDay)
    }
}
