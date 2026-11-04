package app.kassa.money.shared

import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupJson
import app.kassa.money.backup.BackupLoanPayment
import app.kassa.money.backup.BackupTransaction
import app.kassa.money.domain.Balances
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Loans part two on iOS (record, edit and delete a payment, Change terms, Edit details, the count-as-spending switch) against the demo
 * backup: the Car loan owes 1,481,404 at 6.125% (61,250) and pays 35,000 on the 15th. A new payment's split is worked out here again with
 * BigInteger (interest is balance x rate / 12 rounded half up, principal is the rest), never with the code under test. The store is
 * simulated by applying a plan's cells to the `BackupData`, the way `LedgerStore+LoanPayments.swift` applies them to SQL.
 */
class LoanPaymentsTest {
    private val dir = "../app/src/test/resources/backup-demo/"
    private fun demo(): BackupData {
        val bytes = File(dir + "demo.kassa").readBytes()
        return BackupJson.parse(GZIPInputStream(ByteArrayInputStream(bytes.copyOfRange(6, bytes.size))).readBytes().decodeToString())
    }
    private fun num(json: String, key: String): Long = Regex("\"$key\": (-?\\d+)").find(json)!!.groupValues[1].toLong()
    private val expected get() = File(dir + "expected.json").readText()
    private val today get() = num(expected, "todayEpochDay").toInt()
    private fun view(data: BackupData = demo()): LedgerView { KassaCore.installDefaults(); return LedgerView(data, null) }

    private val loan = 5L
    private val checking = 1L
    private val rate = 61_250L
    private val payment = 35_000L
    private val firstDue = 20_802
    private val interestCategory = 9L
    private val noonMillis get() = today * 86_400_000L + 43_200_000L

    private fun halfUp(x: Long, den: Long): Long =
        BigInteger.valueOf(x).multiply(BigInteger.TWO).add(BigInteger.valueOf(den)).divide(BigInteger.valueOf(2 * den)).toLong()

    private fun balance(data: BackupData, id: Long): Long {
        val parts = LedgerParts.of(data)
        return Balances.of(parts.accounts, parts.transactions).getValue(id)
    }

    private fun nextDay(data: BackupData): Int = data.loanTerms.first { it.accountId == loan }.nextPaymentDay

    private fun withFrom(i: LoanPaymentInput, from: Long) = LoanPaymentInput(
        i.amount, i.day, i.principal, i.interest, i.edit, from, i.categoryId, i.estimate, i.principalCategoryId, i.countOverride, i.escrowCategoryId,
    )

    /** The sheet for a new payment, as the store would plan saving it. */
    private fun newPlan(data: BackupData, input: LoanPaymentInput): LoanPaymentPlanData =
        LoanPaymentBridge.plan(data, loan, 0L, input, noonMillis, "USD")

    private fun sheet(data: BackupData, paymentId: Long = 0L): LoanPaymentSheetData =
        view(data).loanPaymentSheet(loan, paymentId, today, false)

    private fun cellOf(plan: LoanPaymentPlanData, which: String, name: String): String? =
        LoanPaymentBridge.entryCell(plan, which, interestCategory, 0L, BackupRows.columns("transactions").indexOf(name))

    /** Applies a plan to the data as the store does: old entries out, new entries in, the link row, the next payment date. */
    private fun commit(data: BackupData, plan: LoanPaymentPlanData): BackupData {
        val removed = plan.oldEntryIds.map { it.toLong() }.toSet()
        var next = (data.transactions.maxOfOrNull { it.id } ?: 0L) + 1
        val added = ArrayList<BackupTransaction>()
        fun tx(which: String, id: Long): BackupTransaction = BackupTransaction(
            id, cellOf(plan, which, "kind")!!, cellOf(plan, which, "amount")!!.toLong(), cellOf(plan, which, "accountId")?.toLong(),
            cellOf(plan, which, "toAccountId")?.toLong(), cellOf(plan, which, "categoryId")?.toLong(), null,
            cellOf(plan, which, "epochMillis")!!.toLong(), cellOf(plan, which, "note")!!,
        )
        var p = 0L
        var i = 0L
        var o = 0L
        if (plan.hasPrincipal) { p = next; next += 1; added.add(tx("PRINCIPAL", p)) }
        if (plan.hasInterest) { i = next; next += 1; added.add(tx("INTEREST", i)) }
        if (plan.hasOther) { o = next; next += 1; added.add(tx("OTHER", o)) }
        val columns = BackupRows.columns("loan_payments")
        fun link(name: String): String? = LoanPaymentBridge.linkCell(plan, p, i, o, columns.indexOf(name))
        val id = if (plan.linkId > 0) plan.linkId else (data.loanPayments.maxOfOrNull { it.id } ?: 0L) + 1
        val row = BackupLoanPayment(
            id, link("accountId")!!.toLong(), link("day")!!.toInt(), link("amount")!!.toLong(),
            if (p > 0) p else null, if (i > 0) i else null, if (o > 0) o else null,
            link("escrow")!!.toLong(), link("charge")!!.toLong(), link("edited") == "1", link("estimate")!!.toLong(), link("scheduled") == "1",
            link("countAllAsSpending")?.let { it == "1" }, link("principalCategoryId")?.toLong(),
        )
        return data.copy(
            transactions = data.transactions.filter { it.id !in removed } + added,
            loanPayments = data.loanPayments.filter { it.id != id } + row,
            loanTerms = data.loanTerms.map { if (it.accountId == loan) it.copy(nextPaymentDay = plan.nextPaymentDay) else it },
        )
    }

    private fun removePayment(data: BackupData, id: Long): BackupData {
        val gone = LoanPaymentBridge.deletePlan(data, id)
        assertTrue(gone.found)
        val ids = gone.entryIds.map { it.toLong() }.toSet()
        return data.copy(
            transactions = data.transactions.filter { it.id !in ids },
            loanPayments = data.loanPayments.filter { it.id != id },
            loanTerms = data.loanTerms.map { if (it.accountId == loan && gone.nextPaymentDay > 0) it.copy(nextPaymentDay = gone.nextPaymentDay) else it },
        )
    }

    // ---------- record ----------

    @Test
    fun aNewPaymentOpensOnTheScheduledAmountWithTheLoansOwnPayFrom() {
        val s = sheet(demo())
        assertTrue(s.found)
        assertEquals("Car loan", s.loanName)
        assertEquals(0L, s.editingId)
        // The demo loan names Everyday checking to pay from; nothing else is preselected.
        assertEquals(checking, s.input.fromId)
        assertEquals("350.00", s.input.amount)
        assertEquals(today, s.input.day)
    }

    @Test
    fun aNewPaymentSplitsAsIntegerMathsSays() {
        val data = demo()
        val owed = -balance(data, loan)
        assertEquals(num(expected, "loanOwed"), owed)
        val interest = halfUp(owed * rate, 12_000_000L)
        val principal = payment - interest
        val plan = newPlan(data, sheet(data).input)
        assertEquals("", plan.problem)
        assertTrue(plan.hasPrincipal && plan.hasInterest && !plan.hasOther)
        assertTrue(!plan.ensureInterest)
        assertEquals("TRANSFER", cellOf(plan, "PRINCIPAL", "kind"))
        assertEquals(principal.toString(), cellOf(plan, "PRINCIPAL", "amount"))
        assertEquals(checking.toString(), cellOf(plan, "PRINCIPAL", "accountId"))
        assertEquals(loan.toString(), cellOf(plan, "PRINCIPAL", "toAccountId"))
        assertEquals("EXPENSE", cellOf(plan, "INTEREST", "kind"))
        assertEquals(interest.toString(), cellOf(plan, "INTEREST", "amount"))
        assertEquals(interestCategory.toString(), cellOf(plan, "INTEREST", "categoryId"))
        assertEquals("Car loan principal", cellOf(plan, "PRINCIPAL", "note"))
        assertEquals("Car loan interest", cellOf(plan, "INTEREST", "note"))
        // A payment of the scheduled size moves the next date on a month.
        assertTrue(plan.scheduled)
        assertEquals(java.time.LocalDate.ofEpochDay(firstDue.toLong()).plusMonths(1).toEpochDay().toInt(), plan.nextPaymentDay)
        val after = commit(data, plan)
        assertEquals(-(owed - principal), balance(after, loan))
        assertEquals(balance(data, checking) - payment, balance(after, checking))
    }

    @Test
    fun editAndDeleteRestoreTheBalancesAndTheNextDate() {
        val data = demo()
        val owed = -balance(data, loan)
        val recorded = commit(data, newPlan(data, sheet(data).input))
        val id = recorded.loanPayments.maxOf { it.id }
        // Open it again and make it 400.00: 50.00 extra goes to principal.
        val opened = sheet(recorded, id)
        assertTrue(opened.found)
        assertEquals(id, opened.editingId)
        val v = view(recorded)
        val bigger = v.loanPaymentApply(loan, id, opened.input, "AMOUNT", "400.00")
        val interest = halfUp(owed * rate, 12_000_000L)
        val editPlan = LoanPaymentBridge.plan(recorded, loan, id, bigger, noonMillis, "USD")
        assertEquals("", editPlan.problem)
        assertEquals(id, editPlan.linkId)
        assertEquals(2, editPlan.oldEntryIds.size)
        assertEquals((40_000L - interest).toString(), cellOf(editPlan, "PRINCIPAL", "amount"))
        val edited = commit(recorded, editPlan)
        assertEquals(recorded.loanPayments.size, edited.loanPayments.size)
        assertEquals(-(owed - (40_000L - interest)), balance(edited, loan))
        assertEquals(nextDay(recorded), nextDay(edited))
        // Delete: every figure is what it was before the payment.
        val gone = removePayment(edited, id)
        assertEquals(balance(data, loan), balance(gone, loan))
        assertEquals(balance(data, checking), balance(gone, checking))
        assertEquals(data.transactions.size, gone.transactions.size)
        assertEquals(data.loanPayments.size, gone.loanPayments.size)
        assertEquals(firstDue, nextDay(gone))
    }

    @Test
    fun aPaymentNeedsAnAccountAndAClosedOneIsRefused() {
        val data = demo()
        val input = sheet(data).input
        assertEquals("Pick the account it's paid from.", newPlan(data, withFrom(input, 0L)).problem)
        val closed = data.copy(accounts = data.accounts.map { if (it.id == checking) it.copy(closedDay = today - 1) else it })
        assertEquals("Everyday checking is closed. Reopen it first to add to it.", newPlan(closed, sheet(closed).input).problem)
    }

    @Test
    fun moreThanIsOwedIsRefusedAndNothingIsPlanned() {
        val data = demo()
        val v = view(data)
        val input = sheet(data).input
        val huge = v.loanPaymentApply(loan, 0L, input, "AMOUNT", "99999.00")
        val plan = newPlan(data, huge)
        assertTrue(plan.problem.isNotEmpty())
        assertTrue(!plan.hasPrincipal)
    }

    @Test
    fun aScheduledPaymentOnTheOldTermsStillMovesTheDateBackWhenDeleted() {
        val data = demo()
        val recorded = commit(data, newPlan(data, sheet(data).input))
        assertEquals(LocalDate.ofEpochDay(firstDue.toLong()).plusMonths(1).toEpochDay().toInt(), nextDay(recorded))
        val id = recorded.loanPayments.maxOf { it.id }
        assertEquals(firstDue, LoanPaymentBridge.deletePlan(recorded, id).nextPaymentDay)
        assertEquals(false, LoanPaymentBridge.deletePlan(recorded, 9_999L).found)
    }

    @Test
    fun anActivityRowOfAPaymentNamesItsPayment() {
        val data = demo()
        val rows = view(data).activity(2026, 10, false, today, "UTC").days.flatMap { it.entries }
        val payments = rows.filter { it.loanPaymentId > 0 }
        assertEquals(2, payments.size)
        assertTrue(payments.all { !it.editable })
        assertEquals(1, payments.map { it.loanPaymentId }.distinct().size)
        assertTrue(rows.any { it.loanPaymentId == 0L })
    }

    // ---------- the count-as-spending switch ----------

    private fun octoberPrincipal(data: BackupData): Long {
        val from = LocalDate.of(2026, 10, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val to = LocalDate.of(2026, 11, 1).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        return data.loanPayments.mapNotNull { p -> data.transactions.firstOrNull { it.id == p.principalEntryId } }
            .filter { it.epochMillis >= from && it.epochMillis < to }.sumOf { it.amount }
    }

    private fun octoberSpending(data: BackupData): String = view(data).insights(false, 20757, "UTC", 0).totalText

    private fun usd(cents: Long): String {
        val whole = (cents / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        return "$" + whole + "." + (cents % 100).toString().padStart(2, '0')
    }

    @Test
    fun turningTheSwitchOnCountsThePrincipalAsSpendingAndOffRestoresIt() {
        val data = demo()
        val principal = octoberPrincipal(data)
        assertTrue(principal > 0)
        assertEquals(usd(276_329), octoberSpending(data))
        val on = data.copy(loanTerms = data.loanTerms.map { if (it.accountId == loan) it.copy(countAllAsSpending = true) else it })
        assertEquals(usd(276_329 + principal), octoberSpending(on))
        // Overview and Activity agree with Insights.
        val overview = view(on).overview(false, 20757, "UTC").spending.totalText
        assertEquals(usd(276_329 + principal), overview)
        val off = on.copy(loanTerms = on.loanTerms.map { if (it.accountId == loan) it.copy(countAllAsSpending = false) else it })
        assertEquals(usd(276_329), octoberSpending(off))
        // Balances never change.
        assertEquals(balance(data, loan), balance(on, loan))
    }

    @Test
    fun aPaymentCanCountItselfOnceAndThePrincipalCategoryMustBeAnExpense() {
        val data = demo()
        val s = sheet(data)
        val v = view(data)
        val counted = v.loanPaymentApply(loan, 0L, s.input, "COUNTS", "1")
        assertEquals("1", counted.countOverride)
        val plan = newPlan(data, counted)
        assertEquals("", plan.problem)
        assertEquals("1", LoanPaymentBridge.linkCell(plan, 1, 2, 0, BackupRows.columns("loan_payments").indexOf("countAllAsSpending")))
        // 7 is the Salary income category.
        val wrong = LoanPaymentInput(
            counted.amount, counted.day, counted.principal, counted.interest, counted.edit, counted.fromId, counted.categoryId, counted.estimate,
            7L, counted.countOverride, counted.escrowCategoryId,
        )
        assertEquals("Pick an expense category for the principal.", newPlan(data, wrong).problem)
        // Turning it back to what the loan does clears the override.
        assertEquals("", v.loanPaymentApply(loan, 0L, counted, "COUNTS", "0").countOverride)
    }

    // ---------- change terms and details ----------

    private fun change(day: Int, rate: String) = LoanChangeInput("RATE", day, rate, "", "", "", "PAYMENT", "PRINCIPAL_AND_INTEREST")

    @Test
    fun aRateChangeSavesOneDatedRow() {
        val data = demo()
        val v = view(data)
        val s = v.loanChangeSheet(loan, today)
        assertTrue(s.found)
        assertEquals(minOf(firstDue, today), s.input.day)
        val input = change(firstDue, "7")
        val preview = v.loanChangePreview(loan, input, false)
        assertTrue(preview.shown)
        assertTrue(preview.problems.isEmpty())
        val plan = LoanChangeBridge.plan(data, loan, input, "USD")
        assertEquals("", plan.problem)
        val columns = BackupRows.columns("loan_term_changes")
        assertEquals("70000", LoanChangeBridge.cell(plan, columns.indexOf("rate")))
        assertEquals(firstDue.toString(), LoanChangeBridge.cell(plan, columns.indexOf("effectiveDay")))
        assertEquals(loan.toString(), LoanChangeBridge.cell(plan, columns.indexOf("accountId")))
    }

    @Test
    fun aChangeCannotStartBeforeTheLoanDid() {
        val data = demo()
        val start = data.loanTerms.first { it.accountId == loan }.startDay!!
        val plan = LoanChangeBridge.plan(data, loan, change(start - 1, "7"), "USD")
        assertEquals("The change can't start before the loan did.", plan.problem)
        assertEquals("Enter the new interest rate.", LoanChangeBridge.plan(data, loan, change(firstDue, ""), "USD").problem)
    }

    @Test
    fun editDetailsMovesTheNextDateWithTheDueDayAndKeepsTheRest() {
        val data = demo()
        val v = view(data)
        val s = v.loanDetailsSheet(loan)
        assertTrue(s.found)
        assertEquals("Car loan", s.input.name)
        assertEquals("15", s.input.dueDay)
        val i = s.input
        val edited = LoanDetailsInput(
            "Truck loan", "20", i.payFromId, i.interestCategoryId, true, 0L, i.escrow, i.fee, i.earlyCharge, i.original, i.startDay,
        )
        assertTrue(v.loanDetailsProblems(loan, edited).isEmpty())
        val plan = LoanDetailsBridge.plan(data, loan, edited, "USD")
        assertEquals("", plan.problem)
        assertEquals("Truck loan", plan.name)
        val columns = BackupRows.columns("loan_terms")
        assertEquals("20", LoanDetailsBridge.termsCell(plan, columns.indexOf("dueDay")))
        assertEquals((firstDue + 5).toString(), LoanDetailsBridge.termsCell(plan, columns.indexOf("nextPaymentDay")))
        assertEquals("1", LoanDetailsBridge.termsCell(plan, columns.indexOf("countAllAsSpending")))
        assertEquals("61250", LoanDetailsBridge.termsCell(plan, columns.indexOf("rate")))
        assertEquals("35000", LoanDetailsBridge.termsCell(plan, columns.indexOf("payment")))
        // A name another account has is refused.
        val taken = LoanDetailsInput(
            "Wallet", i.dueDay, i.payFromId, i.interestCategoryId, false, 0L, i.escrow, i.fee, i.earlyCharge, i.original, i.startDay,
        )
        assertEquals("Wallet is already used.", LoanDetailsBridge.plan(data, loan, taken, "USD").problem)
        // The principal category goes away with the switch.
        val off = LoanDetailsInput(
            i.name, i.dueDay, i.payFromId, i.interestCategoryId, false, 3L, i.escrow, i.fee, i.earlyCharge, i.original, i.startDay,
        )
        val offPlan = LoanDetailsBridge.plan(data, loan, off, "USD")
        assertEquals("", offPlan.problem)
        assertEquals(null, LoanDetailsBridge.termsCell(offPlan, columns.indexOf("principalCategoryId")))
    }
}
