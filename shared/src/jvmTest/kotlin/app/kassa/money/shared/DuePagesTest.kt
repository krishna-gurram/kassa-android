package app.kassa.money.shared

import app.kassa.money.backup.BackupBill
import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupInvestmentTransfer
import app.kassa.money.backup.BackupJson
import app.kassa.money.backup.BackupTransaction
import app.kassa.money.domain.Money
import java.io.ByteArrayInputStream
import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Due notices on iOS (batch I) against the demo backup, the way the screens ask for them. The demo's "today" is 2026-11-03 (epoch day 20760).
 * What is due on each chosen day is worked out here from the raw rows and the calendar, not with the code under test:
 * - Travel card (4): statement day 5, due day 25. Everything before 2026-10-05 adds up to 611.31 owed; the card was paid 595.32 on
 *   2026-10-25, so 15.99 of that statement is still payable and overdue since the 25th. The 5 November statement then owes the whole
 *   balance (518.53, `expected.json`; the last entry is 2026-11-02), due 2026-11-25. The figures come from summing the card's rows.
 * - Car loan (5): next payment day 20802 (2026-12-15), 350.00 a month, 1,481,404 owed.
 * - Steady monthly expenses with the same last three amounts (the only suggestions): Rent 1,800.00 (Home), Gym membership 45.00 and
 *   Streamly 15.99 (Subscriptions). Fuel, Power and water and the loan payment vary.
 */
class DuePagesTest {
    private val dir = "../app/src/test/resources/backup-demo/"
    private fun demo(): BackupData {
        val bytes = File(dir + "demo.kassa").readBytes()
        return BackupJson.parse(GZIPInputStream(ByteArrayInputStream(bytes.copyOfRange(6, bytes.size))).readBytes().decodeToString())
    }
    private fun view(data: BackupData = demo()): LedgerView { KassaCore.installDefaults(); return LedgerView(data, null) }

    private fun day(text: String): Int = LocalDate.parse(text).toEpochDay().toInt()
    private fun millis(text: String, hour: Int = 9, minute: Int = 0): Long =
        LocalDateTime.of(LocalDate.parse(text), LocalTime.of(hour, minute)).toInstant(ZoneOffset.UTC).toEpochMilli()

    private val today = day("2026-11-03")
    private val checking = 1L
    private val bills = 4L

    private fun notices(v: LedgerView, d: Int, cleared: List<String> = emptyList(), hidden: Boolean = false) =
        v.notices(hidden, d, "UTC", cleared).items

    private fun nonSuggestions(v: LedgerView, d: Int, cleared: List<String> = emptyList()) = notices(v, d, cleared).filter { it.kind != "SUGGEST" }

    /** Only the bills: the demo's card has an unpaid 15.99 from October that is on the page until 5 November. */
    private fun billNotices(v: LedgerView, d: Int, cleared: List<String> = emptyList()) = notices(v, d, cleared).filter { it.kind == "BILL" }

    private fun internet(fixed: Long? = 6_999, dayOfMonth: Int = 20, handled: Int = 0, id: Long = 1) =
        BackupBill(id, "Internet", bills, checking, dayOfMonth, fixed, true, handled)

    private fun expenseOn(date: String, amount: Long, note: String, category: Long, id: Long) =
        BackupTransaction(id = id, kind = "EXPENSE", amount = amount, accountId = checking, categoryId = category, epochMillis = millis(date, 12), note = note)

    // ---------- the page ----------

    @Test
    fun theDemoHasTheCardsUnpaidOctoberRemainderAndSuggestedBills() {
        assertEquals(today, day("2026-11-03"))
        val v = view()
        val waiting = nonSuggestions(v, today).single()
        assertEquals("CARD", waiting.kind)
        assertEquals(1_599L, waiting.amountCents)
        assertEquals("$15.99", waiting.amountText)
        assertEquals(day("2026-10-25"), waiting.dueDay)
        assertTrue(waiting.overdue)
        assertEquals("card:4:2026-10-05", waiting.key)
        assertEquals(1_599L, nonSuggestions(v, day("2026-11-04")).single().amountCents)
        val suggested = notices(v, today).filter { it.kind == "SUGGEST" }
        // Biggest yearly cost first: Rent (12 x 1,800.00), Gym (12 x 45.00), Streamly (12 x 15.99).
        assertEquals(listOf("Rent", "Gym membership", "Streamly"), suggested.map { it.name })
        assertEquals(listOf(180_000L, 4_500L, 1_599L), suggested.map { it.amountCents })
        assertEquals(listOf("$1,800.00", "$45.00", "$15.99"), suggested.map { it.amountText })
        assertTrue(suggested.all { it.kind == "SUGGEST" && it.remembers && it.dueDay == 0 && it.id == 0L })
        assertEquals(listOf(3L, 6L, 6L), suggested.map { it.categoryId })
        assertEquals(listOf(1, 8, 2), suggested.map { it.dayOfMonth })
        assertTrue(suggested.all { it.sameCount >= 3 })
        assertEquals("suggest:rent:3", suggested[0].key)
    }

    @Test
    fun theCardIsDueOnItsDueDayWithTheWholeStatement() {
        val v = view()
        // The 5 November statement has closed: 51,853 is payable from then, due 25 November, and the October remainder is part of it.
        assertTrue(nonSuggestions(v, day("2026-11-05")).isEmpty())
        assertTrue(nonSuggestions(v, day("2026-11-24")).isEmpty())
        val due = nonSuggestions(v, day("2026-11-25")).single()
        assertEquals("CARD", due.kind)
        assertEquals(4L, due.id)
        assertEquals("Travel card", due.name)
        assertEquals(51_853L, due.amountCents)
        assertEquals("$518.53", due.amountText)
        assertEquals(day("2026-11-25"), due.dueDay)
        assertFalse(due.overdue)
        assertTrue(due.remembers)
        assertEquals("card:4:2026-11-05", due.key)
        // The next day it is overdue and still there.
        val late = nonSuggestions(v, day("2026-11-26")).single()
        assertTrue(late.overdue)
        assertEquals(day("2026-11-25"), late.dueDay)
    }

    @Test
    fun theLoanIsDueOnItsNextPaymentDayAndStaysWhileOverdue() {
        val v = view()
        assertTrue(nonSuggestions(v, day("2026-12-14")).none { it.kind == "LOAN" })
        val due = nonSuggestions(v, day("2026-12-15")).single { it.kind == "LOAN" }
        assertEquals(5L, due.id)
        assertEquals("Car loan", due.name)
        assertEquals(35_000L, due.amountCents)
        assertEquals("$350.00", due.amountText)
        assertFalse(due.overdue)
        assertEquals("loan:5:20802", due.key)
        val late = nonSuggestions(v, day("2026-12-16")).single { it.kind == "LOAN" }
        assertTrue(late.overdue)
        assertEquals(20_802, late.dueDay)
    }

    @Test
    fun clearingByKeyHidesItAndNothingElse() {
        val v = view()
        val d = day("2026-12-16")
        val before = nonSuggestions(v, d)
        assertEquals(listOf("LOAN"), before.map { it.kind })
        assertEquals(1, nonSuggestions(v, today).size)
        assertTrue(nonSuggestions(v, today, listOf("card:4:2026-10-05")).isEmpty())
        assertTrue(nonSuggestions(v, d, listOf("loan:5:20802")).isEmpty())
        // The key of another due date does not hide it.
        assertEquals(1, nonSuggestions(v, d, listOf("loan:5:20803", "card:4:2026-11-05")).size)
        val cardDay = day("2026-11-25")
        assertTrue(nonSuggestions(v, cardDay, listOf("card:4:2026-11-05")).isEmpty())
        // A suggestion is cleared by its own key and never comes back.
        assertEquals(listOf("Gym membership", "Streamly"), notices(v, today, listOf("suggest:rent:3")).filter { it.kind == "SUGGEST" }.map { it.name })
    }

    @Test
    fun aLoanPaymentRecordedOnOrAfterItsDueDayRemovesIt() {
        val data = demo()
        val paid = app.kassa.money.backup.BackupLoanPayment(
            id = 99, accountId = 5, day = day("2026-12-15"), amount = 35_000, principalEntryId = null, interestEntryId = null,
            otherEntryId = null, escrow = 0, charge = 0, edited = false, estimate = 0, scheduled = true,
        )
        val v = view(data.copy(loanPayments = data.loanPayments + paid))
        assertTrue(nonSuggestions(v, day("2026-12-16")).none { it.kind == "LOAN" })
    }

    @Test
    fun hidingBalancesMasksTheAmountsButKeepsTheRows() {
        val v = view()
        val shown = nonSuggestions(v, day("2026-11-25"))
        val masked = notices(v, day("2026-11-25"), hidden = true).filter { it.kind != "SUGGEST" }
        assertEquals(shown.map { it.name }, masked.map { it.name })
        assertEquals(Money.MASK, masked.single().amountText)
        assertTrue(notices(v, today, hidden = true).size == 4 && notices(v, today, hidden = true).all { it.amountText == Money.MASK })
        // The globals are back to what they were.
        assertFalse(Money.isHidden)
    }

    // ---------- bills ----------

    @Test
    fun aBillIsDueOnItsDayWithItsFixedAmount() {
        val v = view(demo().let { it.copy(bills = listOf(internet())) })
        assertTrue(billNotices(v, day("2026-11-19")).isEmpty())
        val due = billNotices(v, day("2026-11-20")).single()
        assertEquals("BILL", due.kind)
        assertEquals(1L, due.id)
        assertEquals("Internet", due.name)
        assertEquals(6_999L, due.amountCents)
        assertEquals("$69.99", due.amountText)
        assertFalse(due.estimated)
        assertFalse(due.remembers)
        assertEquals(202_611, due.handledMonth)
        val overdue = billNotices(v, day("2026-11-24")).single()
        assertTrue(overdue.overdue)
    }

    @Test
    fun aBillWithoutAFixedAmountIsEstimatedFromTheLastThreeEntries() {
        val history = listOf(
            expenseOn("2026-08-20", 6_000, "Internet", bills, 9_001),
            expenseOn("2026-09-20", 6_600, "Internet September", bills, 9_002),
            expenseOn("2026-10-20", 7_200, "internet", bills, 9_003),
        )
        val data = demo().let { it.copy(transactions = it.transactions + history, bills = listOf(internet(fixed = null))) }
        val due = billNotices(view(data), day("2026-11-20")).single()
        // (6,000 + 6,600 + 7,200) / 3 = 6,600.
        assertEquals(6_600L, due.amountCents)
        assertTrue(due.estimated)
        assertEquals(3, due.lastCount)
        assertEquals("$72, $66, $60", due.lastText)
    }

    @Test
    fun aBillWithNoHistoryAndNoAmountHasNoAmount() {
        val due = billNotices(view(demo().let { it.copy(bills = listOf(internet(fixed = null))) }), day("2026-11-20")).single()
        assertEquals("", due.amountText)
        assertEquals(0L, due.amountCents)
        assertTrue(due.estimated)
        assertEquals(0, due.lastCount)
    }

    @Test
    fun aBillAlreadyEnteredOrHandledThisMonthIsNotAsked() {
        val entered = expenseOn("2026-11-18", 6_999, "Internet", bills, 9_010)
        val a = view(demo().let { it.copy(transactions = it.transactions + entered, bills = listOf(internet())) })
        // Entered by hand this month: the next due date is next month's.
        assertTrue(billNotices(a, day("2026-11-20")).isEmpty())
        assertEquals(day("2026-12-20"), a.upcoming(false, day("2026-12-01"), "UTC", 30).single { it.kind == "BILL" }.dueDay)
        val handled = view(demo().let { it.copy(bills = listOf(internet(handled = 202_611))) })
        assertTrue(billNotices(handled, day("2026-11-20")).isEmpty())
        assertEquals(1, billNotices(handled, day("2026-12-20")).size)
    }

    @Test
    fun approvingRecordsTheEntryOnTheDueDayOrTodayWhenPaidAhead() {
        val onDay = BillBridge.approveEntry("Internet", bills, checking, 6_999, day("2026-11-20"), day("2026-11-20"), "UTC")
        assertEquals("EXPENSE", onDay.kind)
        assertEquals(6_999L, onDay.amount)
        assertEquals(checking, onDay.accountId)
        assertEquals(0L, onDay.toAccountId)
        assertEquals(bills, onDay.categoryId)
        assertEquals("Internet", onDay.note)
        assertEquals(millis("2026-11-20"), onDay.epochMillis)
        val late = BillBridge.approveEntry("Internet", bills, checking, 6_999, day("2026-11-20"), day("2026-11-24"), "UTC")
        assertEquals(millis("2026-11-20"), late.epochMillis)
        val early = BillBridge.approveEntry("Internet", bills, checking, 6_999, day("2026-11-20"), day("2026-11-17"), "UTC")
        assertEquals(millis("2026-11-17"), early.epochMillis)
        assertEquals(202_611, BillBridge.handledMonth(day("2026-11-20")))
        assertNull(EntryBridge.problem(onDay, "USD"))
    }

    @Test
    fun afterApprovingTheBillAsksNextMonth() {
        val approved = BillBridge.approveEntry("Internet", bills, checking, 6_999, day("2026-11-20"), day("2026-11-20"), "UTC")
        val entry = BackupTransaction(id = 9_100, kind = approved.kind, amount = approved.amount, accountId = approved.accountId, categoryId = approved.categoryId, epochMillis = approved.epochMillis, note = approved.note)
        val data = demo()
        val after = view(data.copy(transactions = data.transactions + entry, bills = listOf(internet(handled = BillBridge.handledMonth(day("2026-11-20"))))))
        assertTrue(billNotices(after, day("2026-11-20")).isEmpty())
        assertEquals(1, billNotices(after, day("2026-12-20")).size)
    }

    @Test
    fun aBillThatIsAlreadyOneIsNotSuggested() {
        val rent = BackupBill(7, "Rent", 3, checking, 1, 180_000, true, 0)
        val v = view(demo().let { it.copy(bills = listOf(rent)) })
        assertEquals(listOf("Gym membership", "Streamly"), notices(v, today).filter { it.kind == "SUGGEST" }.map { it.name })
    }

    // ---------- investment transfers ----------

    @Test
    fun aWaitingTransferIsOnThePageUntilAnsweredOrCleared() {
        val data = demo()
        val moved = BackupInvestmentTransfer(1, 3, 6, 5_000, millis("2026-11-01", 12))
        val v = view(data.copy(investmentTransfers = listOf(moved)))
        val list = notices(v, today)
        val transfer = list.single { it.kind == "TRANSFER" }
        assertEquals(1L, transfer.id)
        assertEquals("Brokerage", transfer.accountName)
        assertEquals("$50.00", transfer.amountText)
        assertEquals(day("2026-11-01"), transfer.dueDay)
        assertFalse(transfer.remembers)
        // By date: the card due on 25 October, the transfer of 1 November, then the undated suggestions.
        assertEquals(listOf("CARD", "TRANSFER", "SUGGEST", "SUGGEST", "SUGGEST"), list.map { it.kind })
        assertTrue(notices(v, today, listOf("transfer:1")).none { it.kind == "TRANSFER" })
    }

    // ---------- upcoming ----------

    @Test
    fun upcomingListsCardsAndBillsWithinThirtyDaysEarliestFirst() {
        val v = view(demo().let { it.copy(bills = listOf(internet())) })
        // 3 November: the card's unpaid 15.99 (overdue since 25 October, still listed) and the bill, 17 days away.
        val a = v.upcoming(false, today, "UTC", 30)
        assertEquals(listOf("CARD", "BILL"), a.map { it.kind })
        assertEquals(listOf(day("2026-10-25"), day("2026-11-20")), a.map { it.dueDay })
        assertEquals("$15.99", a[0].amountText)
        val bill = a[1]
        assertEquals("Internet", bill.name)
        assertEquals("$69.99", bill.amountText)
        assertEquals(1L, bill.billId)
        assertFalse(bill.estimated)
        // 6 November: the statement closed, the card is due on the 25th, after the bill on the 20th.
        val b = v.upcoming(false, day("2026-11-06"), "UTC", 30)
        assertEquals(listOf("BILL", "CARD"), b.map { it.kind })
        assertEquals(listOf(day("2026-11-20"), day("2026-11-25")), b.map { it.dueDay })
        assertEquals(4L, b.last().accountId)
        assertEquals("$518.53", b.last().amountText)
        // A bill further off than the window is not listed.
        assertTrue(v.upcoming(false, today, "UTC", 10).none { it.kind == "BILL" })
        assertTrue(v.upcoming(true, day("2026-11-06"), "UTC", 30).all { it.amountText == Money.MASK })
    }

    @Test
    fun anOverdueCardStaysInUpcoming() {
        val v = view()
        val a = v.upcoming(false, day("2026-11-27"), "UTC", 30)
        assertEquals(listOf(day("2026-11-25")), a.filter { it.kind == "CARD" }.map { it.dueDay })
    }

    // ---------- the bills page and its sheet ----------

    @Test
    fun theBillsPageListsEachBillByDayWithItsAccountAndAmount() {
        val data = demo().let { it.copy(bills = listOf(internet(), BackupBill(2, "Rent", 3, 2, 1, null, false, 0))) }
        val page = view(data).billsPage(false, today, "UTC")
        assertEquals(listOf("Rent", "Internet"), page.rows.map { it.name })
        val net = page.rows[1]
        assertEquals("Everyday checking", net.accountName)
        assertEquals("$69.99", net.amountText)
        assertFalse(net.approx)
        val rent = page.rows[0]
        assertFalse(rent.active)
        assertEquals("Rainy day savings", rent.accountName)
        // No fixed amount: estimated from the last three Rent entries (all 1,800.00), shown as approximate.
        assertEquals("$1,800.00", rent.amountText)
        assertTrue(rent.approx)
    }

    @Test
    fun theBillSheetOpensOnTheBillAndOnNothingForAGoneOne() {
        val v = view(demo().let { it.copy(bills = listOf(internet())) })
        val sheet = v.billSheet(1)
        assertTrue(sheet.found)
        assertEquals("Internet", sheet.name)
        assertEquals(bills, sheet.categoryId)
        assertEquals(checking, sheet.accountId)
        assertEquals(20, sheet.day)
        assertEquals("69.99", sheet.amount)
        assertTrue(sheet.active)
        assertFalse(v.billSheet(42).found)
    }

    @Test
    fun theBillFormChecksWhatTheRulesCheck() {
        val v = view()
        val empty = v.billProblems(BillInput(0, "", 0, 0, 5, "", true))
        assertEquals(listOf("NAME", "CATEGORY", "ACCOUNT"), empty.map { it.field })
        assertTrue(v.billProblems(BillInput(0, "Internet", bills, checking, 20, "69.99", true)).isEmpty())
        assertEquals(listOf("DAY"), v.billProblems(BillInput(0, "Internet", bills, checking, 40, "", true)).map { it.field })
    }

    @Test
    fun theStoreCellsMakeTheRowTheRepositoryWould() {
        val input = BillInput(0, "  Internet   plan ", bills, checking, 20, "69.99", true)
        val names = BackupRows.columns("recurring")
        val cells = names.indices.associate { names[it] to BillBridge.cell(input, it) }
        assertNull(cells["id"])
        assertEquals("Internet plan", cells["name"])
        assertEquals("4", cells["categoryId"])
        assertEquals("1", cells["accountId"])
        assertEquals("20", cells["dayOfMonth"])
        assertEquals("6999", cells["fixedAmount"])
        assertEquals("1", cells["active"])
        assertEquals("0", cells["handledMonth"])
        assertNull(BillBridge.cell(BillInput(0, "x", 1, 1, 1, "", true), names.indexOf("fixedAmount")))
        assertNull(BillBridge.cell(BillInput(0, "x", 1, 1, 1, "0", true), names.indexOf("fixedAmount")))
        // An edit never rewrites the id or the handled month.
        assertEquals(listOf("name", "categoryId", "accountId", "dayOfMonth", "fixedAmount", "active"), names.indices.filter { BillBridge.ownsColumn(it) }.map { names[it] })
    }

    // ---------- the plan ----------

    private val title = "Payment due"
    private val body = "Open Kassa to see what is waiting."
    private val defaults = DueRulesInput(true, 0, 540, true, 0, 540, false, 0, 540)

    private fun plan(v: LedgerView, nowMillis: Long, rules: DueRulesInput = defaults, posted: List<String> = emptyList(), cleared: List<String> = emptyList()) =
        v.notificationPlan(rules, nowMillis, "UTC", posted, cleared, title, body)

    @Test
    fun theDefaultsAreCardsAndLoansOnBillsOffAtNine() {
        assertTrue(DueBridge.defaultOn("CARD"))
        assertTrue(DueBridge.defaultOn("LOAN"))
        assertFalse(DueBridge.defaultOn("BILL"))
        assertEquals(540, DueBridge.defaultMinute())
        assertEquals(14, DueBridge.maxDays())
        assertNull(DueBridge.daysProblem(14))
        assertEquals("Enter 0 to 14 days.", DueBridge.daysProblem(15))
    }

    @Test
    fun theDemoPlanHasTheLoanOnItsDayAtNine() {
        val v = view()
        // 3 November, midday: the card has nothing payable, the loan is due 15 December (42 days ahead).
        val out = plan(v, millis("2026-11-03", 12))
        assertEquals(1, out.size)
        val n = out.single()
        assertEquals("loan:5:20802", n.key)
        assertEquals(millis("2026-12-15"), n.epochMillis)
        assertEquals("LOAN", n.kind)
        assertEquals(5L, n.id)
        assertFalse(n.late)
        assertEquals(title, n.title)
        assertEquals(body, n.body)
    }

    @Test
    fun theCardJoinsTheDayItsStatementCloses() {
        val v = view()
        val out = plan(v, millis("2026-11-06", 8))
        assertEquals(listOf("card:4:${day("2026-11-25")}", "loan:5:20802"), out.map { it.key })
        assertEquals(millis("2026-11-25"), out[0].epochMillis)
        assertEquals("CARD", out[0].kind)
        assertEquals(4L, out[0].id)
    }

    @Test
    fun daysBeforeMovesTheMomentAndNeverMoreThanFourteen() {
        val v = view()
        val three = plan(v, millis("2026-11-06", 8), DueRulesInput(true, 3, 7 * 60, true, 20, 540, false, 0, 540))
        assertEquals(millis("2026-11-22", 7), three[0].epochMillis)
        // 20 days before is clamped to 14: 1 December.
        assertEquals(millis("2026-12-01"), three[1].epochMillis)
    }

    @Test
    fun nothingTheTextCarriesGivesAnAmountOrANameAway() {
        val out = plan(view(), millis("2026-11-06", 8))
        for (n in out) {
            for (secret in listOf("Travel", "Car loan", "518", "350", "51853", "35000", "4242")) {
                assertFalse(secret in n.title || secret in n.body, secret)
            }
        }
    }

    @Test
    fun postedAndClearedKeysAreNotPlannedAgain() {
        val v = view()
        val now = millis("2026-11-06", 8)
        val cardKey = "card:4:${day("2026-11-25")}"
        assertEquals(listOf("loan:5:20802"), plan(v, now, posted = listOf(cardKey)).map { it.key })
        assertEquals(listOf("loan:5:20802"), plan(v, now, cleared = listOf("card:4:2026-11-05")).map { it.key })
        assertTrue(plan(v, now, posted = listOf(cardKey, "loan:5:20802")).isEmpty())
    }

    @Test
    fun aKindThatIsOffIsNotPlanned() {
        val off = DueRulesInput(false, 0, 540, false, 0, 540, false, 0, 540)
        assertTrue(plan(view(), millis("2026-11-06", 8), off).isEmpty())
    }

    @Test
    fun billsJoinThePlanOnceTheirKindIsOn() {
        val v = view(demo().let { it.copy(bills = listOf(internet())) })
        val now = millis("2026-11-03", 12)
        assertTrue(plan(v, now).none { it.kind == "BILL" })
        val on = plan(v, now, DueRulesInput(true, 0, 540, true, 0, 540, true, 1, 8 * 60))
        val bill = on.single { it.kind == "BILL" }
        assertEquals(millis("2026-11-19", 8), bill.epochMillis)
        assertEquals("bill:1:${day("2026-11-20")}", bill.key)
    }

    @Test
    fun aCardWithoutItsOwnDueDayIsNotPlannedButStillOnThePage() {
        val data = demo()
        val noDue = data.copy(accounts = data.accounts.map { if (it.id == 4L) it.copy(dueDay = null) else it })
        val v = view(noDue)
        assertTrue(plan(v, millis("2026-11-06", 8)).none { it.kind == "CARD" })
    }

    @Test
    fun hasNotifiableIsTrueForACardWithADueDayOrALoanWithTerms() {
        assertTrue(view().hasNotifiable())
        val data = demo()
        val bare = data.copy(
            accounts = data.accounts.filter { it.id !in setOf(4L, 5L) }, loanTerms = emptyList(), loanChanges = emptyList(),
            loanPayments = emptyList(), transactions = emptyList(), loans = emptyList(),
        )
        assertFalse(view(bare).hasNotifiable())
        val cardNoDue = bare.copy(accounts = bare.accounts + data.accounts.first { it.id == 4L }.copy(dueDay = null))
        assertFalse(view(cardNoDue).hasNotifiable())
        val card = bare.copy(accounts = bare.accounts + data.accounts.first { it.id == 4L })
        assertTrue(view(card).hasNotifiable())
    }
}
