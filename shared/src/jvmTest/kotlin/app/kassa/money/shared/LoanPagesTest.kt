package app.kassa.money.shared

import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupJson
import app.kassa.money.backup.BackupTransaction
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.time.LocalDate
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The loan page, the loan setup and balance corrections (iOS stage G1) against the demo backup. The Car loan's schedule is worked
 * out here again with plain Kotlin and BigInteger (not the code under test): it owes what expected.json says, at 6.125% a year
 * (61,250), pays 350.00 a month on the 15th from 15 December 2026, interest is balance x rate / 12 rounded half up, and a last
 * payment that would leave under 1% of the payment is folded into the one before.
 */
class LoanPagesTest {
    private val dir = "../app/src/test/resources/backup-demo/"
    private fun demo(): BackupData {
        val bytes = File(dir + "demo.kassa").readBytes()
        return BackupJson.parse(GZIPInputStream(ByteArrayInputStream(bytes.copyOfRange(6, bytes.size))).readBytes().decodeToString())
    }
    private fun num(json: String, key: String): Long = Regex("\"$key\": (-?\\d+)").find(json)!!.groupValues[1].toLong()
    private fun usd(cents: Long): String {
        val a = abs(cents)
        val whole = (a / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        return (if (cents < 0) "-" else "") + "$" + whole + "." + (a % 100).toString().padStart(2, '0')
    }
    private val expected get() = File(dir + "expected.json").readText()
    private val today get() = num(expected, "todayEpochDay").toInt()
    private val mask = "••••"
    private fun view(data: BackupData = demo()): LedgerView { KassaCore.installDefaults(); return LedgerView(data, null) }

    private val loan = 5L
    private val checking = 1L
    private val rate = 61_250L
    private val payment = 35_000L
    private val firstDue = 20_802

    private class Row(val day: Int, val principal: Long, val interest: Long, val balance: Long)

    /** Half-up x / den for non-negative values. */
    private fun halfUp(x: Long, den: Long): Long = BigInteger.valueOf(x).multiply(BigInteger.TWO).add(BigInteger.valueOf(den)).divide(BigInteger.valueOf(2 * den)).toLong()

    private fun schedule(): List<Row> {
        var balance = num(expected, "loanOwed")
        val rows = ArrayList<Row>()
        var i = 0
        while (balance > 0) {
            val interest = halfUp(balance * rate, 12_000_000L)
            var principal = minOf(payment - interest, balance)
            if (balance - principal in 1..(payment / 100)) principal = balance
            balance -= principal
            rows.add(Row(LocalDate.ofEpochDay(firstDue.toLong()).plusMonths(i.toLong()).toEpochDay().toInt(), principal, interest, balance))
            i++
        }
        return rows
    }

    private fun page(hidden: Boolean = false, back: Int = 0, all: Boolean = false, v: LedgerView = view()) =
        v.loanPage(loan, hidden, today, "UTC", back, all)

    private fun input(
        base: LoanInput, rate: String = base.rate, payment: String = base.payment, amount: String = base.amount, term: String = base.termMonths,
        balance: String = base.balance, escrow: String = base.escrow, stage: String = base.stage, kind: String = base.kind,
        firstDay: Int = base.firstDay, nextDay: Int = base.nextDay, payFrom: Long = base.payFromId, basis: String = base.basis,
        paymentsLeft: String = base.paymentsLeft, original: String = base.original, interestCategory: Long = base.interestCategoryId,
    ) = LoanInput(
        stage, base.locked, kind, base.contract, amount, rate, term, firstDay, balance, payment, nextDay, paymentsLeft, original,
        base.startDay, base.dueDay, payFrom, escrow, base.fee, base.earlyCharge, interestCategory, basis,
    )

    // ---------- the loan page ----------

    @Test
    fun theScheduleIsWhatTheEntriesAloneSay() {
        val rows = schedule()
        // The file's own total: 14,814.04 owed, paid off in a little over four years at 350.00 a month.
        assertEquals(1_481_404L, num(expected, "loanOwed"))
        assertTrue(rows.size in 44..48, "${rows.size} payments")
        assertEquals(0L, rows.last().balance)
        assertTrue(rows.dropLast(1).all { it.principal + it.interest == payment })
    }

    @Test
    fun theLoanPageFiguresEqualTheOnesWorkedOutFromTheEntries() {
        val rows = schedule()
        val p = page()
        assertTrue(p.found)
        assertTrue(p.hasTerms)
        assertEquals("Car loan", p.name)
        assertEquals(usd(num(expected, "loanOwed")), p.balanceText)
        assertEquals("Ink", p.balanceTone.name)
        assertEquals("Orange", p.heroTone.name)
        // 2,000,000 borrowed, 1,481,404 owed: 25.9% repaid.
        assertTrue(p.hasProgress)
        assertEquals(25, p.percent)
        assertEquals(259, p.permille)
        assertEquals(usd(2_000_000), p.originalText)
        assertTrue(p.paying)
        assertEquals(usd(payment), p.paymentText)
        assertEquals(firstDue, p.nextPaymentDay)
        assertEquals(rows.last().day, p.payoffDay)
        assertEquals(usd(rows.sumOf { it.interest }), p.interestLeftText)
        val paid = demo().loanPayments.sumOf { lp -> demo().transactions.first { it.id == lp.interestEntryId }.amount }
        assertEquals(usd(paid), p.interestPaidText)
        assertFalse(p.doesntCover)
        // The terms in effect today.
        assertEquals("REDUCING", p.interestType)
        assertEquals("6.125%", p.rateText)
        assertEquals(usd(payment), p.termPaymentText)
        assertFalse(p.hasEscrow)
        assertEquals(60, p.termMonths)
        assertEquals(15, p.dueDay)
        assertTrue(p.changes.isEmpty())
        // The next six rows, each worked out again.
        assertEquals(rows.size, p.scheduleCount)
        assertEquals(6, p.rows.size)
        p.rows.forEachIndexed { i, r ->
            assertEquals(rows[i].day, r.day)
            assertEquals(usd(rows[i].principal), r.principalText)
            assertEquals(usd(rows[i].interest), r.interestText)
            assertEquals(usd(rows[i].balance), r.balanceText)
        }
        assertEquals(rows.size, page(all = true).rows.size)
    }

    @Test
    fun hiddenMasksEveryFigureAndGivesNothingAway() {
        val p = page(hidden = true)
        for (text in listOf(p.balanceText, p.paymentText, p.interestPaidText, p.interestLeftText, p.termPaymentText)) assertEquals(mask, text)
        assertFalse(p.hasProgress, "no bar while hidden")
        assertEquals("Ink", p.heroTone.name)
        assertEquals("Ink", p.balanceTone.name)
        assertTrue(p.rows.all { it.principalText == mask && it.interestText == mask && it.balanceText == mask })
    }

    @Test
    fun theMonthListsTheLoansOwnEntriesWithTheirTags() {
        val v = view()
        // The 15 October payment is the last one in the file: one transfer into the loan, tagged principal.
        val lastPayment = demo().loanPayments.maxByOrNull { it.day }!!
        val principal = demo().transactions.first { it.id == lastPayment.principalEntryId }
        val p = page(back = 1, v = v)
        val entries = p.days.flatMap { it.entries }
        assertEquals(1, p.entryCount)
        assertEquals(1, entries.size)
        assertEquals("principal", entries[0].tag)
        assertEquals(usd(principal.amount), entries[0].amountText)
        assertEquals("Transfer", entries[0].kind.name)
        assertFalse(entries[0].countsAsSpending)
        assertTrue(p.hasEarlier)
        assertFalse(p.isCurrentMonth)
        assertTrue(page().isCurrentMonth)
        assertEquals(0, page().entryCount)
    }

    @Test
    fun countingTheWholePaymentAsSpendingChangesNoBalanceOnThePage() {
        val data = demo()
        val on = data.copy(loanTerms = data.loanTerms.map { it.copy(countAllAsSpending = true) })
        val v = view(on)
        val p = page(back = 1, v = v)
        assertTrue(p.days.flatMap { it.entries }.single().countsAsSpending)
        assertEquals(usd(num(expected, "loanOwed")), page(v = v).balanceText)
        // October's spending now holds the principal of the 15 October payment, as Activity counts it.
        val last = data.loanPayments.maxByOrNull { it.day }!!
        val principal = data.transactions.first { it.id == last.principalEntryId }.amount
        val before = view().activity(2026, 10, false, today, "UTC").spendingText
        val after = v.activity(2026, 10, false, today, "UTC").spendingText
        assertEquals(usd(276_329), before)
        assertEquals(usd(276_329 + principal), after)
    }

    @Test
    fun aLoanWithoutTermsHasNoLoanPageAndAnOtherAccountHasNone() {
        val data = demo()
        val bare = data.copy(loanTerms = emptyList())
        val p = view(bare).loanPage(loan, false, today, "UTC", 0, false)
        assertTrue(p.found)
        assertFalse(p.hasTerms)
        assertFalse(view(bare).hasLoanTerms(loan))
        assertTrue(view().hasLoanTerms(loan))
        assertFalse(view().loanPage(checking, false, today, "UTC", 0, false).found)
    }

    // ---------- setup ----------

    private fun starting(): LoanInput = LoanBridge.starting(today, 0)

    @Test
    fun aNewLoanIsCheckedBuiltAndPricedByTheDomainForm() {
        KassaCore.installDefaults()
        val empty = LoanBridge.termsProblems(starting(), "USD")
        assertEquals(listOf("AMOUNT", "RATE", "TERM"), empty.map { it.field })
        val form = input(starting(), amount = "30000", rate = "6", term = "60")
        assertEquals(emptyList(), LoanBridge.termsProblems(form, "USD"))
        val cells = BackupRows.columns("loan_terms").indices.associate { BackupRows.columns("loan_terms")[it] to LoanBridge.termsCell(form, 9, it) }
        // 30,000.00 at 6% over 60 months is 579.98 (the example in `LoanEngine.emi`).
        assertEquals("57998", cells["payment"])
        assertEquals("60000", cells["rate"])
        assertEquals("9", cells["accountId"])
        assertEquals("REDUCING", cells["type"])
        assertEquals("0", cells["profit"])
        assertEquals("60", cells["termMonths"])
        assertEquals("3000000", cells["originalAmount"])
        assertEquals(form.firstDay.toString(), cells["nextPaymentDay"])
        assertEquals(LocalDate.ofEpochDay(form.firstDay.toLong()).dayOfMonth.toString(), cells["dueDay"])
        assertEquals(null, cells["startDay"])
        assertEquals(null, cells["interestCategoryId"])
        assertEquals(null, cells["payFromId"])
        assertEquals("0", cells["escrow"])
        assertEquals("0", cells["countAllAsSpending"])
        assertEquals(null, cells["principalCategoryId"])
        assertEquals("30000.00", LoanBridge.owedInput(form, "USD"))
        val account = AccountInput("LOAN", "House", "", LoanBridge.owedInput(form, "USD"), "", "", "", 0)
        val accountCells = BackupRows.columns("accounts").indices.associate { BackupRows.columns("accounts")[it] to AccountBridge.cell(account, it, 3) }
        assertEquals("-3000000", accountCells["openingBalance"])
        assertEquals("LOAN", accountCells["type"])
    }

    @Test
    fun theAccountsOwnProblemsComeFirstAndANameCantRepeat() {
        KassaCore.installDefaults()
        val form = input(starting(), amount = "30000", rate = "6", term = "60")
        val account = AccountInput("LOAN", "", "", "", "", "", "", 0)
        val found = LoanBridge.problems(account, form, listOf("Car loan"), "USD")
        assertEquals(listOf("NAME"), found.map { it.field })
        val taken = LoanBridge.problems(AccountInput("LOAN", "car LOAN", "", "", "", "", "", 0), form, listOf("Car loan"), "USD")
        assertEquals(listOf("NAME"), taken.map { it.field })
        assertEquals(emptyList(), LoanBridge.problems(AccountInput("LOAN", "House", "", "", "", "", "", 0), form, listOf("Car loan"), "USD"))
    }

    @Test
    fun theSaveRefusesAGoneOrSelfPayFromAccountAndAGoneCategory() {
        KassaCore.installDefaults()
        val data = demo()
        val form = input(starting(), amount = "30000", rate = "6", term = "60")
        assertEquals("", LoanBridge.saveProblem(form, 0, data, "USD"))
        assertEquals("", LoanBridge.saveProblem(input(form, payFrom = checking), 0, data, "USD"))
        assertEquals("Pick another account to pay from.", LoanBridge.saveProblem(input(form, payFrom = 99), 0, data, "USD"))
        assertEquals("Pick another account to pay from.", LoanBridge.saveProblem(input(form, payFrom = loan), loan, data, "USD"))
        assertEquals("That category isn't there any more.", LoanBridge.saveProblem(input(form, interestCategory = 99), 0, data, "USD"))
        assertEquals("", LoanBridge.saveProblem(input(form, interestCategory = 9), 0, data, "USD"))
    }

    @Test
    fun theSharedCategoriesAreKeptUnhiddenOrCreatedLikeAndroidDoes() {
        val data = demo()
        // The demo already has "Loan interest" (id 9); there is no Escrow yet.
        assertEquals(LoanCategoryStep("KEEP", 9), LoanBridge.categoryStep(data, "INTEREST"))
        assertEquals(LoanCategoryStep("CREATE", 0), LoanBridge.categoryStep(data, "ESCROW"))
        val cells = BackupRows.columns("categories").indices.associate { BackupRows.columns("categories")[it] to LoanBridge.categoryCell(data, "ESCROW", it, 11) }
        assertEquals("Escrow", cells["name"])
        assertEquals("EXPENSE", cells["kind"])
        assertEquals("symbol:home", cells["icon"])
        assertEquals("0", cells["countsTowardDaily"])
        assertEquals("0", cells["hidden"])
        assertEquals("11", cells["sortOrder"])
        assertEquals(null, cells["id"])
        val interest = BackupRows.columns("categories").indices.associate { BackupRows.columns("categories")[it] to LoanBridge.categoryCell(data, "INTEREST", it, 11) }
        assertEquals("symbol:bank", interest["icon"])
        // Archived: brought back, not made twice. A name differing in case counts.
        val archived = data.copy(categories = data.categories.map { if (it.id == 9L) it.copy(hidden = true, name = "loan interest") else it })
        assertEquals(LoanCategoryStep("UNHIDE", 9), LoanBridge.categoryStep(archived, "INTEREST"))
        // Gone altogether: made.
        val gone = data.copy(categories = data.categories.filter { it.id != 9L })
        assertEquals("CREATE", LoanBridge.categoryStep(gone, "INTEREST").action)
        assertTrue(LoanBridge.hasEscrow(input(starting(), amount = "1000", rate = "5", term = "12", escrow = "50")))
        assertFalse(LoanBridge.hasEscrow(starting()))
    }

    @Test
    fun addingTermsToAnExistingLoanKeepsItsEntriesAndBalance() {
        val v = view()
        val data = demo()
        val form = v.loanTermsInput(loan, today)
        assertEquals("PARTWAY", form.stage)
        assertTrue(form.locked)
        assertEquals("14814.04", form.balance.replace(",", ""))
        // Same balance typed: the starting balance does not move.
        assertEquals(-1_800_000L, LoanBridge.openingBalanceAfter(form, loan, data))
        // A different balance moves it by the difference, so the account's entries stay as they are.
        val other = input(form, balance = "10000")
        assertEquals(-1_800_000L + (-1_000_000L + 1_481_404L), LoanBridge.openingBalanceAfter(other, loan, data))
    }

    @Test
    fun theLiveCalculationForALoanUnderWayMatchesTheScheduleAndMasksWhileHidden() {
        val rows = schedule()
        val form = input(view().loanTermsInput(loan, today), rate = "6.125", payment = "350", nextDay = firstDue)
        assertEquals(emptyList(), LoanBridge.termsProblems(form, "USD"))
        val p = LoanBridge.preview(form, "USD", false)
        assertTrue(p.shown)
        assertEquals(rows.size, p.paymentsLeft)
        assertEquals(rows.last().day, p.payoffDay)
        assertEquals(usd(rows.sumOf { it.interest }), p.interestLeftText)
        assertEquals("", p.warning)
        val hidden = LoanBridge.preview(form, "USD", true)
        assertEquals(mask, hidden.interestLeftText)
        // A payment that is no more than the interest never pays the loan off.
        val weak = LoanBridge.preview(input(form, payment = "50"), "USD", false)
        assertTrue(weak.warning.startsWith("The payment doesn't cover the interest."))
        // Nothing to show until the figures are valid.
        assertFalse(LoanBridge.preview(starting(), "USD", false).shown)
    }

    @Test
    fun aNewLoansPreviewGivesItsPaymentAndTotalInterest() {
        val form = input(starting(), amount = "30000", rate = "6", term = "60")
        val p = LoanBridge.preview(form, "USD", false)
        assertTrue(p.shown)
        assertEquals("$579.98", p.paymentText)
        assertTrue(p.totalInterestText.startsWith("$"))
        assertEquals(-1, p.paymentsLeft)
    }

    // ---------- balance corrections ----------

    @Test
    fun theCorrectionSheetOpensOnWhatKassaShows() {
        val v = view()
        val c = v.correctionSheet(checking, 0, false)
        assertTrue(c.found)
        assertEquals(2_064_312L, c.shownWithout)
        assertEquals("$20,643.12", c.balanceInKassaText)
        assertFalse(c.debt)
        assertEquals(0L, c.editingId)
        val l = v.correctionSheet(loan, 0, false)
        assertTrue(l.debt)
        assertTrue(l.isLoan)
        assertEquals(1_481_404L, l.shownWithout)
        assertEquals(mask, v.correctionSheet(loan, 0, true).balanceInKassaText)
        assertFalse(v.correctionSheet(99, 0, false).found)
        // A transaction that is not a correction is not one to edit.
        assertFalse(v.correctionSheet(0, demo().transactions.first().id, false).found)
    }

    @Test
    fun aCorrectionIsTheDifferenceAndADebtIsTypedAsWhatIsOwed() {
        val v = view()
        // The lender shows 14,000.00 owed; Kassa shows 14,814.04 owed: the balance goes up by 814.04 (owed goes down).
        val plan = v.correctionPlan(loan, 0, 1_400_000)
        assertEquals("", plan.problem)
        assertEquals("ADJUST_IN", plan.kind)
        assertEquals(81_404L, plan.amount)
        // An asset the other way.
        val up = v.correctionPlan(checking, 0, 2_064_312 + 1_000)
        assertEquals("ADJUST_IN", up.kind)
        assertEquals(1_000L, up.amount)
        val down = v.correctionPlan(checking, 0, 2_064_312 - 1_000)
        assertEquals("ADJUST_OUT", down.kind)
        assertEquals(1_000L, down.amount)
        assertEquals("That's the balance Kassa already shows.", v.correctionPlan(loan, 0, 1_481_404).problem)
        assertEquals("That's more than $100,000,000.", v.correctionPlan(checking, 0, 10_000_000_001).problem)
    }

    @Test
    fun thePreviewShowsTheDifferenceAsAnArrowAndMasksItWhileHidden() {
        val shown = CorrectionBridge.preview(1_481_404, 1_400_000, true, true, "USD", false)
        assertEquals("↓ $814.04", shown.differenceText)
        assertEquals("", shown.problem)
        val hidden = CorrectionBridge.preview(1_481_404, 1_400_000, true, true, "USD", true)
        assertEquals(mask, hidden.differenceText)
        val same = CorrectionBridge.preview(1_481_404, 1_481_404, true, true, "USD", false)
        assertEquals("", same.differenceText)
        assertEquals("That's the balance Kassa already shows.", same.problem)
        assertEquals(CorrectionPreviewData("", ""), CorrectionBridge.preview(1_481_404, 0, false, true, "USD", false))
    }

    @Test
    fun editingACorrectionWorksTheDifferenceOutWithoutIt() {
        val data = demo()
        // A correction already in: the loan shows 14,000.00 owed.
        val withIt = data.copy(
            transactions = data.transactions + BackupTransaction(
                id = 9_999, kind = "ADJUST_IN", amount = 81_404, accountId = loan, epochMillis = 1_786_000_000_000L, note = "",
            ),
        )
        val v = view(withIt)
        assertEquals(usd(1_400_000), page(v = v).balanceText)
        val sheet = v.correctionSheet(0, 9_999, false)
        assertTrue(sheet.found)
        assertEquals(loan, sheet.accountId)
        assertEquals(9_999L, sheet.editingId)
        assertEquals(1_481_404L, sheet.shownWithout)
        assertEquals(1_400_000L, sheet.initialShown)
        assertEquals(1_786_000_000_000L, sheet.epochMillis)
        val plan = v.correctionPlan(loan, 9_999, 1_450_000)
        assertEquals("ADJUST_IN", plan.kind)
        assertEquals(31_404L, plan.amount)
        // Typing what Kassa shows without it leaves nothing to correct.
        assertEquals("That's the balance Kassa already shows.", v.correctionPlan(loan, 9_999, 1_481_404).problem)
        // The row is a correction in Activity and cannot be edited as an ordinary entry.
        val row = v.accountPage(loan, false, today, "UTC", 0).days.flatMap { it.entries }
        assertNotNull(row)
        assertTrue(CorrectionBridge.isCorrection("ADJUST_OUT"))
        assertFalse(CorrectionBridge.isCorrection("TRANSFER"))
    }

    @Test
    fun aCorrectionRowIsNoSpendingOrIncome() {
        val data = demo()
        val withIt = data.copy(
            transactions = data.transactions + BackupTransaction(
                id = 9_999, kind = "ADJUST_OUT", amount = 50_000, accountId = checking, epochMillis = 1_786_000_000_000L, note = "",
            ),
        )
        val v = view(withIt)
        val c = v.cells(withIt)
        assertEquals(usd(2_064_312 - 50_000), c)
        val month = v.activity(2026, 10, false, today, "UTC")
        assertEquals(usd(276_329), month.spendingText)
        assertEquals(usd(520_286), month.incomeText)
    }

    private fun LedgerView.cells(data: BackupData): String =
        accounts(false, today, "UTC").groups.flatMap { it.rows }.first { it.name == "Everyday checking" }.amountText
}
