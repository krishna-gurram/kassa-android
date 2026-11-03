package app.kassa.money.domain

import org.junit.Before
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LoanFormTest {
    private fun day(y: Int, m: Int, d: Int) = LoanDates.epochDay(y, m, d)

    @Before
    fun usd() {
        Money.style = Currencies.byCode("USD")
    }

    private val nov1 = day(2026, 11, 1)

    private fun fresh(kind: LoanKind = LoanKind.REDUCING) = LoanForm(
        kind = kind, amount = "30000", rate = "6", termMonths = "60", firstDay = nov1, nextDay = nov1,
    )

    private fun partway(kind: LoanKind = LoanKind.REDUCING) = LoanForm(
        stage = LoanStage.PARTWAY, kind = kind, balance = "21480", rate = "6", payment = "580", firstDay = nov1, nextDay = nov1,
    )

    @Test
    fun aNewLoanGetsKassasPaymentAndAForecast() {
        val f = fresh()
        assertTrue(f.problems().isEmpty())
        val p = assertNotNull(f.preview())
        assertEquals(57_998, p.payment)
        assertEquals(479_909, p.totalInterest)
        assertEquals(day(2031, 10, 1), p.payoffDay)
        assertNull(p.warning)
        val t = f.toTerms(7)
        assertEquals(LoanTerms(
            accountId = 7, type = InterestType.REDUCING, rate = 60_000, payment = 57_998, termMonths = 60, originalAmount = 3_000_000,
            nextPaymentDay = nov1, dueDay = 1,
        ), t)
        assertEquals(3_000_000, f.owed)
    }

    @Test
    fun aTypedPaymentOverwritesKassasOwn() {
        val f = fresh().copy(payment = "580")
        assertEquals(58_000, f.toTerms(1).payment)
        assertEquals(58_000, f.preview()?.payment)
    }

    @Test
    fun aFlatLoanShowsItsPaymentAndTheRateOnTheBalance() {
        val p = assertNotNull(fresh(LoanKind.FLAT).preview())
        assertEquals(65_000, p.payment)
        assertEquals(900_000, p.totalInterest)
        assertEquals(108_479, p.equivalentRate)
    }

    @Test
    fun islamicContractsAreFlatOrReducingWithProfitWording() {
        val murabaha = fresh(LoanKind.ISLAMIC)
        assertEquals(InterestType.FLAT, murabaha.type)
        assertTrue(murabaha.profit)
        assertEquals("Profit", murabaha.word)
        assertEquals(65_000, murabaha.toTerms(1).payment)
        assertTrue(murabaha.toTerms(1).profit)
        val ijara = murabaha.copy(contract = IslamicContract.IJARA)
        assertEquals(InterestType.REDUCING, ijara.type)
        assertEquals(57_998, ijara.toTerms(1).payment)
        assertEquals("Interest", fresh().word)
        assertEquals("Enter the profit rate.", murabaha.copy(rate = "").problems()[LoanField.RATE])
    }

    @Test
    fun interestOnlyPaysOneMonthsInterestAndStillOwesEverything() {
        val p = assertNotNull(fresh(LoanKind.INTEREST_ONLY).preview())
        assertEquals(15_000, p.payment)
        assertEquals(3_000_000, p.owedAtEnd)
        assertNull(p.totalInterest)
    }

    @Test
    fun aLoanUnderWayIsForecastFromItsBalanceToday() {
        val f = partway()
        assertTrue(f.problems().isEmpty())
        val p = assertNotNull(f.preview())
        assertEquals(42, p.paymentsLeft)
        assertEquals(233_373, p.interestLeft)
        assertEquals(day(2030, 4, 1), p.payoffDay)
        assertNull(p.payment)
        val t = f.toTerms(3)
        assertEquals(58_000, t.payment)
        assertNull(t.originalAmount)
        assertNull(t.startDay)
        assertEquals(nov1, t.nextPaymentDay)
        assertEquals(2_148_000, f.owed)
    }

    @Test
    fun theOriginalAmountAndStartDateAreOnlyKeptWhenGiven() {
        val t = partway().copy(original = "30000", startDay = day(2025, 4, 1)).toTerms(1)
        assertEquals(3_000_000, t.originalAmount)
        assertEquals(day(2025, 4, 1), t.startDay)
        assertEquals("The original amount can't be less than what is owed.", partway().copy(original = "1000").problems()[LoanField.ORIGINAL])
    }

    @Test
    fun aFlatLoanUnderWayNeedsItsPaymentsLeftOrItsOriginalAmount() {
        val f = partway(LoanKind.FLAT).copy(balance = "18000", payment = "650")
        assertNotNull(f.problems()[LoanField.PAYMENTS_LEFT])
        val withLeft = f.copy(paymentsLeft = "36")
        assertTrue(withLeft.problems().isEmpty())
        assertEquals(3_000_000, withLeft.toTerms(1).originalAmount)
        val withOriginal = f.copy(original = "30000")
        assertTrue(withOriginal.problems().isEmpty())
        assertEquals(3_000_000, withOriginal.toTerms(1).originalAmount)
        assertNotNull(withLeft.copy(payment = "400").problems()[LoanField.PAYMENT])
    }

    @Test
    fun aPaymentThatDoesNotCoverTheInterestIsWarnedAbout() {
        val p = assertNotNull(partway().copy(payment = "50").preview())
        assertEquals("The payment doesn't cover the interest. It needs to be more than \$107.40.", p.warning)
        assertNull(p.paymentsLeft)
        assertEquals("The payment doesn't cover the profit. It needs to be more than \$107.40.",
            partway(LoanKind.ISLAMIC).copy(contract = IslamicContract.IJARA, payment = "50").preview()?.warning)
    }

    @Test
    fun interestOnlyUnderWayCanLeaveThePaymentOut() {
        val f = partway(LoanKind.INTEREST_ONLY).copy(payment = "")
        assertTrue(f.problems().isEmpty())
        assertEquals(10_740, f.toTerms(1).payment)
    }

    @Test
    fun everyFieldIsBounded() {
        val bad = fresh().copy(amount = "", rate = "101", termMonths = "601", escrow = "100000000", fee = "x", earlyCharge = "150", dueDay = "40")
        val p = bad.problems()
        assertEquals(setOf(LoanField.AMOUNT, LoanField.RATE, LoanField.TERM, LoanField.ESCROW, LoanField.FEE, LoanField.DUE_DAY, LoanField.EARLY_CHARGE), p.keys)
        assertEquals(setOf(LoanField.BALANCE, LoanField.RATE, LoanField.PAYMENT), partway().copy(balance = "0", rate = "", payment = "").problems().keys)
        assertNull(bad.preview())
    }

    @Test
    fun theDueDayDefaultsToTheDayOfTheFirstPayment() {
        assertEquals(1, fresh().toTerms(1).dueDay)
        assertEquals(15, fresh().copy(firstDay = day(2026, 11, 15)).toTerms(1).dueDay)
        assertEquals(5, fresh().copy(dueDay = "5").toTerms(1).dueDay)
    }

    @Test
    fun moreDetailsAreCarriedIntoTheTerms() {
        val t = fresh().copy(escrow = "200", fee = "150.50", earlyCharge = "1.5", interestCategoryId = 9, payFromId = 4).toTerms(1)
        assertEquals(20_000, t.escrow)
        assertEquals(15_050, t.fee)
        assertEquals(15_000, t.earlyCharge)
        assertEquals(9L, t.interestCategoryId)
        assertEquals(4L, t.payFromId)
    }

    @Test
    fun addingTermsToAnExistingLoanKeepsItsEntriesByMovingTheStartingBalance() {
        val account = Account(id = 1, name = "Car loan", type = AccountType.LOAN, openingBalance = -100_000)
        // The loan owes 1,000.00 today; the form says 800.00, so the starting balance moves by 200.00.
        val f = LoanForm.forAccount(owedToday = 100_000, today = nov1)
        assertEquals("1000", f.balance)
        assertEquals(LoanStage.PARTWAY, f.stage)
        assertTrue(f.stageLocked)
        assertEquals(-100_000, f.openingBalance(account, currentBalance = -100_000))
        assertEquals(-80_000, f.copy(balance = "800").openingBalance(account, currentBalance = -100_000))
    }

    @Test
    fun theDefaultInterestCategoryIsTheVisibleTopLevelOneByName() {
        fun cat(id: Long, name: String, parent: Long? = null, kind: CategoryKind = CategoryKind.EXPENSE, hidden: Boolean = false) =
            Category(id = id, name = name, kind = kind, parentId = parent, color = 0, hidden = hidden)
        val all = listOf(
            cat(1, "Loan interest"), cat(2, "Car loan", parent = 1), cat(3, "Bills"), cat(4, "Salary", kind = CategoryKind.INCOME),
            cat(5, "Old", hidden = true), cat(6, "apples"),
        )
        assertEquals(1L, LoanCategories.interestCategory(all)?.id)
        assertNull(LoanCategories.interestCategory(all.filter { it.id != 1L }))
        assertNull(LoanCategories.interestCategory(all.map { if (it.id == 1L) it.copy(hidden = true) else it }))
    }

    @Test
    fun theSpendingSwitchIsOffByDefaultAndTheSetupFormCarriesIt() {
        val off = fresh().toTerms(1)
        assertEquals(false, off.countAllAsSpending)
        assertNull(off.principalCategoryId)
        val on = fresh().copy(countAllAsSpending = true, principalCategoryId = 9).toTerms(1)
        assertTrue(on.countAllAsSpending)
        assertEquals(9L, on.principalCategoryId)
        // A principal category only means something while the switch is on.
        assertNull(fresh().copy(principalCategoryId = 9).toTerms(1).principalCategoryId)
    }

    @Test
    fun editDetailsKeepsAndChangesTheSpendingSwitch() {
        val terms = fresh().copy(countAllAsSpending = true, principalCategoryId = 9).toTerms(1)
        val form = LoanDetailsForm.of("Car loan", terms)
        assertTrue(form.countAllAsSpending)
        assertEquals(9L, form.principalCategoryId)
        assertEquals(terms, form.applyTo(terms))
        val off = form.copy(countAllAsSpending = false).applyTo(terms)
        assertEquals(false, off.countAllAsSpending)
        assertNull(off.principalCategoryId)
    }

    // ---------- payment with or without escrow ----------

    private fun withEscrow(basis: PaymentBasis, payment: String = "2500", escrow: String = "600") =
        partway().copy(payment = payment, escrow = escrow, paymentBasis = basis)

    @Test
    fun principalAndInterestIsTheDefaultAndStoresWhatWasTyped() {
        val f = partway().copy(payment = "1900", escrow = "600")
        assertEquals(PaymentBasis.PRINCIPAL_AND_INTEREST, f.paymentBasis)
        val t = f.toTerms(1)
        assertEquals(190_000, t.payment)
        assertEquals(60_000, t.escrow)
        assertNull(f.paymentSplit())
    }

    @Test
    fun aTotalIncludingEscrowStoresTheDifferenceAsThePayment() {
        val f = withEscrow(PaymentBasis.INCLUDES_ESCROW)
        assertTrue(f.problems().isEmpty())
        val t = f.toTerms(1)
        assertEquals(190_000, t.payment)
        assertEquals(60_000, t.escrow)
        assertEquals(PaymentSplit(190_000, 60_000), f.paymentSplit())
        // The same terms as typing principal and interest directly.
        assertEquals(partway().copy(payment = "1900", escrow = "600").toTerms(1), t)
    }

    @Test
    fun aNewLoanWithATotalStoresTheDifferenceToo() {
        val t = fresh().copy(payment = "1500", escrow = "400", paymentBasis = PaymentBasis.INCLUDES_ESCROW).toTerms(1)
        assertEquals(110_000, t.payment)
        assertEquals(40_000, t.escrow)
    }

    @Test
    fun noEscrowMeansTheChoiceChangesNothing() {
        for (escrow in listOf("", "0", "0.00")) {
            val total = partway().copy(payment = "1900", escrow = escrow, paymentBasis = PaymentBasis.INCLUDES_ESCROW)
            val plain = total.copy(paymentBasis = PaymentBasis.PRINCIPAL_AND_INTEREST)
            assertTrue(total.problems().isEmpty())
            assertEquals(plain.toTerms(1), total.toTerms(1))
            assertEquals(190_000, total.toTerms(1).payment)
            assertNull(total.paymentSplit())
        }
    }

    @Test
    fun aKassaPaymentIsUntouchedByTheChoice() {
        val plain = fresh().copy(escrow = "400")
        val total = plain.copy(paymentBasis = PaymentBasis.INCLUDES_ESCROW)
        assertEquals(57_998, total.toTerms(1).payment)
        assertEquals(plain.toTerms(1), total.toTerms(1))
        assertNull(total.paymentSplit())
    }

    @Test
    fun escrowAtOrAboveTheTotalIsAnErrorOnThePayment() {
        for (escrow in listOf("2500", "2600")) {
            val f = withEscrow(PaymentBasis.INCLUDES_ESCROW, payment = "2500", escrow = escrow)
            val message = assertNotNull(f.problems()[LoanField.PAYMENT])
            assertEquals("The total payment has to be more than the escrow.", message)
            assertNull(f.preview())
            assertNull(f.paymentSplit())
        }
        // The same figures are fine as principal and interest with escrow on top.
        assertTrue(withEscrow(PaymentBasis.PRINCIPAL_AND_INTEREST, escrow = "2600").problems().isEmpty())
        // One cent of payment left is allowed.
        assertTrue(withEscrow(PaymentBasis.INCLUDES_ESCROW, payment = "2500", escrow = "2499.99").problems().isEmpty())
    }

    @Test
    fun aBadPaymentOrEscrowStillGetsItsOwnProblem() {
        assertTrue(LoanField.PAYMENT in withEscrow(PaymentBasis.INCLUDES_ESCROW, payment = "").problems())
        assertTrue(LoanField.ESCROW in withEscrow(PaymentBasis.INCLUDES_ESCROW, escrow = "abc").problems())
    }

    @Test
    fun switchingBackAndForthKeepsWhatWasTyped() {
        val start = withEscrow(PaymentBasis.PRINCIPAL_AND_INTEREST)
        val there = start.copy(paymentBasis = PaymentBasis.INCLUDES_ESCROW)
        val back = there.copy(paymentBasis = PaymentBasis.PRINCIPAL_AND_INTEREST)
        assertEquals(start, back)
        assertEquals("2500", there.payment)
        assertEquals("600", there.escrow)
        assertEquals(250_000, start.toTerms(1).payment)
        assertEquals(190_000, there.toTerms(1).payment)
    }

    @Test
    fun aFlatLoanPartwayDerivesItsBaseFromThePrincipalAndInterest() {
        val flat = partway(LoanKind.FLAT).copy(balance = "18000", payment = "650", paymentsLeft = "36", escrow = "")
        val withTotal = flat.copy(payment = "850", escrow = "200", paymentBasis = PaymentBasis.INCLUDES_ESCROW)
        assertTrue(withTotal.problems().isEmpty())
        assertEquals(3_000_000, withTotal.toTerms(1).originalAmount)
        assertEquals(65_000, withTotal.toTerms(1).payment)
    }
}
