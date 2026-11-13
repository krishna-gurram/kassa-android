package app.kassa.money.domain

import java.math.BigInteger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Coach simulator. The ledger is the Coach fixture "lived": groceries of 100.00 on the 10th and a salary of 4,000.00 on the 28th (August to
 * October), rent of 1,500.00 on the 1st (August to November), a fixed bill "Internet" of 50.00 on the 20th, 8,000.00 liquid. Today is Thursday
 * 12 November 2026, so the replay runs 12 November to 12 December (31 days). Worked out by hand: a usual day is 30,000 / 92 = 326 cents, the
 * bill leaves on 20 November, the salary arrives on 28 November, the rent leaves on 1 December. The lowest day is 27 November:
 * 800,000 - 16 x 326 - 5,000 = 789,784.
 */
class CoachSimTest {
    private val money = mapOf(1L to 500_000L, 2L to 300_000L)

    private fun lived(): CoachFx = CoachFx.lived().also { fx ->
        fx.bills.add(Recurring(id = 5, name = "Internet", categoryId = 14, accountId = 1, dayOfMonth = 20, fixedAmount = 5_000))
    }

    private fun run(fx: CoachFx, vararg w: WhatIf, cushion: Long = 0L) = CoachSim.run(fx.input(money), w.toList(), cushion)

    private val lowestBaseline = 789_784L

    @Test
    fun theReplayMatchesTheHandWork() {
        val fx = lived()
        val r = run(fx)
        assertEquals(SimVerdict.ABOVE_CUSHION, r.verdict)
        assertEquals(31, r.days.size)
        assertEquals(fx.day("2026-11-12"), r.days.first().day)
        assertEquals(fx.day("2026-12-12"), r.days.last().day)
        assertEquals(800_000L, r.startBalanceCents)
        assertEquals(SimPoint(fx.day("2026-11-27"), lowestBaseline), r.lowest)
        // First day: only a usual day. 20 Nov: the bill. 28 Nov: the salary. 1 Dec: the rent.
        assertEquals(800_000L - 326, r.days.first().endBalanceCents)
        val bill = r.days.first { it.day == fx.day("2026-11-20") }.events.first { it.kind == SimEventKind.BILL }
        assertEquals(-5_000L, bill.amountCents)
        assertEquals("Internet", bill.name)
        assertEquals(false, bill.estimated)
        val pay = r.days.first { it.day == fx.day("2026-11-28") }.events.first { it.kind == SimEventKind.PAYCHECK }
        assertEquals(400_000L, pay.amountCents)
        val rent = r.days.first { it.day == fx.day("2026-12-01") }.events.first { it.kind == SimEventKind.RECURRING }
        assertEquals(-150_000L, rent.amountCents)
        // The end balance again with independent arithmetic.
        val expected = BigInteger.valueOf(800_000).add(BigInteger.valueOf(400_000)).subtract(BigInteger.valueOf(150_000))
            .subtract(BigInteger.valueOf(5_000)).subtract(BigInteger.valueOf(326).multiply(BigInteger.valueOf(31)))
        assertEquals(expected, BigInteger.valueOf(r.days.last().endBalanceCents))
    }

    @Test
    fun theSameInputGivesTheSameAnswer() {
        val fx = lived()
        val w = listOf(WhatIf.Spend(1_234, fx.day("2026-11-15")), WhatIf.MovePayday(fx.day("2026-12-02")))
        assertEquals(CoachSim.run(fx.input(money), w, 100L), CoachSim.run(fx.input(money), w, 100L))
        assertEquals(CoachSim.movable(fx.input(money)), CoachSim.movable(fx.input(money)))
    }

    @Test
    fun withNoWhatIfsTheScenarioIsTheBaseline() {
        val r = run(lived())
        assertEquals(r.baselineBalances, r.days.map { it.endBalanceCents })
        assertEquals(r.lowest, r.baselineLowest)
    }

    @Test
    fun aSpendLowersEveryBalanceFromItsDayOnAndNothingBefore() {
        val fx = lived()
        val day = fx.day("2026-11-20")
        val r = run(fx, WhatIf.Spend(12_345, day))
        for ((i, d) in r.days.withIndex()) {
            val shift = if (d.day >= day) 12_345L else 0L
            assertEquals(r.baselineBalances[i] - shift, d.endBalanceCents, "day ${d.day}")
        }
        val spend = r.days.first { it.day == day }.events.first { it.kind == SimEventKind.SPEND }
        assertEquals(-12_345L, spend.amountCents)
    }

    @Test
    fun delayingADueItemMovesExactlyThatEvent() {
        val fx = lived()
        val key = "bill:5:${fx.day("2026-11-20")}"
        val from = fx.day("2026-11-20")
        val to = fx.day("2026-11-25")
        val base = run(fx)
        val r = run(fx, WhatIf.DelayDue(key, to))
        for ((i, d) in r.days.withIndex()) {
            val shift = if (d.day in from until to) 5_000L else 0L
            assertEquals(base.baselineBalances[i] + shift, d.endBalanceCents, "day ${d.day}")
        }
        assertEquals(base.days.sumOf { it.events.size }, r.days.sumOf { it.events.size })
        assertEquals(listOf(key), r.days.first { it.day == to }.events.map { it.key }.filter { it.startsWith("bill") })
        // Everything else stays where it was.
        fun others(x: SimResult) = x.days.flatMap { d -> d.events.filter { it.key != key }.map { d.day to it } }
        assertEquals(others(base), others(r))
    }

    @Test
    fun movingPaydayShiftsOnlyThePaycheck() {
        val fx = lived()
        val base = run(fx)
        val to = fx.day("2026-12-05")
        val r = run(fx, WhatIf.MovePayday(to))
        fun flat(x: SimResult, paycheck: Boolean) =
            x.days.flatMap { d -> d.events.filter { (it.kind == SimEventKind.PAYCHECK) == paycheck }.map { d.day to it } }
        assertEquals(flat(base, false), flat(r, false))
        assertEquals(listOf(fx.day("2026-11-28")), flat(base, true).map { it.first })
        assertEquals(listOf(to), flat(r, true).map { it.first })
        for ((i, d) in r.days.withIndex()) {
            val shift = if (d.day >= fx.day("2026-11-28") && d.day < to) 400_000L else 0L
            assertEquals(base.baselineBalances[i] - shift, d.endBalanceCents)
        }
    }

    @Test
    fun anExtraMonthlyCostComesBackOnTheSameDayOfTheNextMonth() {
        val fx = lived()
        val r = run(fx, WhatIf.ExtraMonthly(2_500, fx.day("2026-11-12")))
        val hits = r.days.filter { d -> d.events.any { it.kind == SimEventKind.EXTRA_MONTHLY } }.map { it.day }
        assertEquals(listOf(fx.day("2026-11-12"), fx.day("2026-12-12")), hits)
        for ((i, d) in r.days.withIndex()) {
            val shift = if (d.day == fx.day("2026-12-12")) 5_000L else 2_500L
            assertEquals(r.baselineBalances[i] - shift, d.endBalanceCents)
        }
        // From a later day it starts there and has no second charge inside the horizon.
        val late = run(fx, WhatIf.ExtraMonthly(2_500, fx.day("2026-11-20")))
        assertEquals(1, late.days.sumOf { d -> d.events.count { it.kind == SimEventKind.EXTRA_MONTHLY } })
    }

    @Test
    fun allEventsAddUpToTheChangeInTheMoney() {
        val fx = lived()
        val r = run(fx, WhatIf.Spend(777, fx.day("2026-11-14")), WhatIf.ExtraMonthly(1_000, fx.day("2026-11-13")), WhatIf.MovePayday(fx.day("2026-11-30")))
        val all = r.days.flatMap { it.events }.sumOf { it.amountCents }
        assertEquals(r.days.last().endBalanceCents - r.startBalanceCents, all)
        var running = r.startBalanceCents
        for (d in r.days) {
            running += d.events.sumOf { it.amountCents }
            assertEquals(running, d.endBalanceCents)
            assertEquals(d.events.sortedBy { it.key }, d.events)
        }
    }

    @Test
    fun theVerdictChangesAtExactlyTheCushionAndAtZero() {
        val fx = lived()
        assertEquals(SimVerdict.ABOVE_CUSHION, run(fx, cushion = lowestBaseline).verdict)
        assertEquals(SimVerdict.TIGHT, run(fx, cushion = lowestBaseline + 1).verdict)
        val today = fx.day("2026-11-12")
        // Leaving exactly 0 at the lowest point is not short.
        val zero = run(fx, WhatIf.Spend(lowestBaseline, today), cushion = 0L)
        assertEquals(0L, zero.lowest?.balanceCents)
        assertEquals(SimVerdict.ABOVE_CUSHION, zero.verdict)
        assertEquals(SimVerdict.TIGHT, run(fx, WhatIf.Spend(lowestBaseline, today), cushion = 1L).verdict)
        val short = run(fx, WhatIf.Spend(lowestBaseline + 1, today), cushion = 1L)
        assertEquals(SimVerdict.SHORT, short.verdict)
        assertEquals(1L, short.shortByCents)
        assertEquals(-1L, short.lowest?.balanceCents)
        assertEquals(lowestBaseline, short.baselineLowest?.balanceCents)
        assertEquals(0L, run(fx).shortByCents)
    }

    @Test
    fun badWhatIfsAreProblemCodesNotExceptions() {
        val fx = lived()
        val today = fx.day("2026-11-12")
        val end = today + CoachSim.HORIZON_DAYS
        fun problem(vararg w: WhatIf, cushion: Long = 0L): SimProblem? = run(fx, *w, cushion = cushion).also {
            assertEquals(SimVerdict.PROBLEM, it.verdict)
            assertTrue(it.days.isEmpty())
        }.problem
        assertEquals(SimProblem.TOO_MANY_WHAT_IFS, problem(*Array(9) { WhatIf.Spend(100, today) }))
        assertEquals(SimVerdict.ABOVE_CUSHION, run(fx, *Array(8) { WhatIf.Spend(100, today) }).verdict)
        assertEquals(SimProblem.BAD_AMOUNT, problem(WhatIf.Spend(0, today)))
        assertEquals(SimProblem.BAD_AMOUNT, problem(WhatIf.Spend(-5, today)))
        assertEquals(SimProblem.BAD_AMOUNT, problem(WhatIf.Spend(InputRules.AMOUNT_MAX + 1, today)))
        assertEquals(SimProblem.BAD_AMOUNT, problem(WhatIf.ExtraMonthly(0, today)))
        assertEquals(SimVerdict.SHORT, run(fx, WhatIf.Spend(InputRules.AMOUNT_MAX, today)).verdict)
        assertEquals(SimProblem.DAY_OUT_OF_RANGE, problem(WhatIf.Spend(100, today - 1)))
        assertEquals(SimProblem.DAY_OUT_OF_RANGE, problem(WhatIf.Spend(100, end + 1)))
        assertEquals(SimVerdict.ABOVE_CUSHION, run(fx, WhatIf.Spend(100, end)).verdict)
        assertEquals(SimProblem.DAY_OUT_OF_RANGE, problem(WhatIf.ExtraMonthly(100, end + 1)))
        assertEquals(SimProblem.DAY_OUT_OF_RANGE, problem(WhatIf.MovePayday(end + 1)))
        assertEquals(SimProblem.DAY_OUT_OF_RANGE, problem(WhatIf.DelayDue("bill:5:${today + 8}", end + 1)))
        assertEquals(SimProblem.UNKNOWN_ITEM, problem(WhatIf.DelayDue("bill:99:1", today + 3)))
        assertEquals(SimProblem.NOT_LATER, problem(WhatIf.DelayDue("bill:5:${today + 8}", today + 8)))
        assertEquals(SimProblem.BAD_CUSHION, problem(cushion = -1L))
        // The index of the bad one is reported.
        val r = run(fx, WhatIf.Spend(100, today), WhatIf.Spend(0, today))
        assertEquals(1, r.problemIndex)
        // No payday to move.
        val noIncome = CoachFx()
        noIncome.monthly(CoachFx.AUG_TO_OCT, 10, 10_000, 10, "")
        assertEquals(SimProblem.NO_PAYDAY, CoachSim.run(noIncome.input(money), listOf(WhatIf.MovePayday(today + 1)), 0L).problem)
    }

    @Test
    fun tooLittleHistoryIsNotEnoughData() {
        fun fx(first: String) = CoachFx().also { it.spend(first, 1_000, 10); it.opening(1, 100_000) }
        // 14 days of entries (30 Oct to 12 Nov) are enough, 13 are not (the rule of the weekly check-in).
        val ok = CoachFx().also { it.spend("2026-10-30", 1_000, 10); it.opening(1, 100_000) }
        val few = fx("2026-10-31")
        assertEquals(WeeklyCheckIn.MIN_HISTORY_DAYS, CoachSim.MIN_HISTORY_DAYS)
        assertEquals(SimVerdict.ABOVE_CUSHION, CoachSim.run(ok.input(), emptyList(), 0L).verdict)
        val r = CoachSim.run(few.input(), listOf(WhatIf.Spend(0, 0)), 0L)
        assertEquals(SimVerdict.NOT_ENOUGH_DATA, r.verdict)
        assertTrue(r.days.isEmpty())
        assertNull(r.lowest)
        assertEquals(SimVerdict.NOT_ENOUGH_DATA, CoachSim.run(CoachFx().input(), emptyList(), 0L).verdict)
    }

    @Test
    fun withoutALiquidAccountThereIsNothingToReplay() {
        val fx = CoachFx()
        fx.accounts.clear()
        fx.account(1, "Card", AccountType.CREDIT_CARD, statementDay = 5, dueDay = 25)
        fx.spend("2026-08-10", 1_000, 10, account = 1)
        assertEquals(SimVerdict.NO_ACCOUNTS, CoachSim.run(fx.input(), emptyList(), 0L).verdict)
        assertTrue(CoachSim.movable(fx.input()).isEmpty())
    }

    /** Lived, plus a credit card with a payable of 150.00 due 25 November and a loan with a payment of 250.00 due 20 November. */
    private fun withCardAndLoan(): CoachFx {
        val fx = lived()
        fx.account(3, "Travel card", AccountType.CREDIT_CARD, opening = -30_000, statementDay = 5, dueDay = 25)
        fx.account(4, "Car loan", AccountType.LOAN, opening = -1_000_000)
        // Past card payments and loan payments are ordinary entries; none of them may leak into the usual day.
        for (m in CoachFx.AUG_TO_OCT) {
            fx.other(TxKind.TRANSFER, "$m-25", 5_000, account = 1, to = 3, note = "Card payment")
        }
        fx.loanTerms[4] = LoanTerms(
            accountId = 4, type = InterestType.REDUCING, rate = 60_000, payment = 25_000, nextPaymentDay = fx.day("2026-11-20"), dueDay = 20,
        )
        for ((i, m) in CoachFx.AUG_TO_OCT.withIndex()) {
            val principal = fx.other(TxKind.TRANSFER, "$m-20", 20_000, account = 1, to = 4)
            val interest = fx.spend("$m-20", 5_000, null, "Car loan interest", account = 1)
            fx.loanPayments.add(LoanPayment(i + 1L, 4, fx.day("$m-20"), 25_000, principal, interest, null, 0, 0, false, 0, true))
        }
        return fx
    }

    @Test
    fun cardAndLoanPaymentsAreNotAlsoUsualSpending() {
        val fx = withCardAndLoan()
        val r = CoachSim.run(fx.input(money), emptyList(), 0L)
        assertEquals(SimVerdict.ABOVE_CUSHION, r.verdict)
        val events = r.days.flatMap { d -> d.events.map { d.day to it } }
        // Usual spending is the groceries alone (326 a day), not groceries plus transfers or loan interest.
        val usual = events.filter { it.second.kind == SimEventKind.USUAL_SPENDING }
        assertEquals(31, usual.size)
        assertTrue(usual.all { it.second.amountCents == -326L })
        // The card is paid once, for its balance payable (opening 300.00 owed, 150.00 already paid, 150.00 left), on its due day.
        val card = events.filter { it.second.kind == SimEventKind.CARD_PAYMENT }
        assertEquals(listOf(fx.day("2026-11-25") to -15_000L), card.map { it.first to it.second.amountCents })
        assertEquals("Travel card", card.single().second.name)
        // The loan is paid once, the scheduled payment, on its day.
        val loan = events.filter { it.second.kind == SimEventKind.LOAN_PAYMENT }
        assertEquals(listOf(fx.day("2026-11-20") to -25_000L), loan.map { it.first to it.second.amountCents })
        // Nothing else leaves: the known payments plus 31 usual days account for every cent.
        val total = events.sumOf { it.second.amountCents }
        val expected = 400_000L - 150_000L - 5_000L - 15_000L - 25_000L - 31 * 326L
        assertEquals(expected, total)
        assertEquals(800_000L + expected, r.days.last().endBalanceCents)
    }

    @Test
    fun anOverdueLoanPaymentComesInTodayAndOneAlreadyRecordedIsSkipped() {
        val fx = withCardAndLoan()
        fx.loanTerms[4] = fx.loanTerms.getValue(4).copy(nextPaymentDay = fx.day("2026-11-05"), dueDay = 5)
        fun loans(f: CoachFx) = CoachSim.run(f.input(money), emptyList(), 0L).days.flatMap { d -> d.events.filter { it.kind == SimEventKind.LOAN_PAYMENT }.map { d.day to it } }
        // Still waiting since 5 November: it comes in today, then again on 5 December (worked out from a smaller balance, so an estimate).
        val waiting = loans(fx)
        assertEquals(listOf(fx.day("2026-11-12"), fx.day("2026-12-05")), waiting.map { it.first })
        assertEquals(listOf(false, true), waiting.map { it.second.estimated })
        assertEquals("loan:4:${fx.day("2026-11-05")}", waiting[0].second.key)
        assertEquals(-25_000L, waiting[0].second.amountCents)
        // A payment recorded on 6 November without moving the next payment day covers that occurrence: only 5 December is left.
        fx.loanPayments.add(LoanPayment(9, 4, fx.day("2026-11-06"), 25_000, null, null, null, 0, 0, false, 0, false))
        assertEquals(listOf(fx.day("2026-12-05")), loans(fx).map { it.first })
    }

    @Test
    fun movableItemsAreTheKeysAWhatIfCanDelay() {
        val fx = withCardAndLoan()
        val m = CoachSim.movable(fx.input(money))
        assertEquals(
            listOf(
                Triple(SimEventKind.LOAN_PAYMENT, "loan:4:${fx.day("2026-11-20")}", 25_000L),
                Triple(SimEventKind.BILL, "bill:5:${fx.day("2026-11-20")}", 5_000L),
                Triple(SimEventKind.CARD_PAYMENT, "card:3:${fx.day("2026-11-25")}", 15_000L),
            ).sortedWith(compareBy({ if (it.first == SimEventKind.CARD_PAYMENT) 1 else 0 }, { it.second })),
            m.map { Triple(it.kind, it.key, it.amountCents) },
        )
        // Delaying the card payment past the salary moves exactly that one.
        val key = m.first { it.kind == SimEventKind.CARD_PAYMENT }.key
        val base = CoachSim.run(fx.input(money), emptyList(), 0L)
        val moved = CoachSim.run(fx.input(money), listOf(WhatIf.DelayDue(key, fx.day("2026-11-30"))), 0L)
        val diff = base.days.zip(moved.days).filter { (a, b) -> a.endBalanceCents != b.endBalanceCents }.map { it.first.day }
        assertEquals((fx.day("2026-11-25")..fx.day("2026-11-29")).toList(), diff)
        assertNotNull(moved.days.first { it.day == fx.day("2026-11-30") }.events.firstOrNull { it.key == key })
    }

    @Test
    fun anOverdueBillComesInOnTodayAndAMissingAmountIsNeverGuessed() {
        val fx = lived()
        fx.bills.add(Recurring(id = 6, name = "Water", categoryId = 14, accountId = 1, dayOfMonth = 3, fixedAmount = 4_000))
        fx.bills.add(Recurring(id = 7, name = "Mystery", categoryId = 14, accountId = 1, dayOfMonth = 15, fixedAmount = null))
        val r = CoachSim.run(fx.input(money), emptyList(), 0L)
        val water = r.days.first().events.first { it.key.startsWith("bill:6:") }
        assertEquals(-4_000L, water.amountCents)
        assertTrue(r.days.flatMap { it.events }.none { it.key.startsWith("bill:7:") })
    }

    @Test
    fun whatIfsRoundTripThroughText() {
        val list = listOf(
            WhatIf.Spend(12_345, 20_400), WhatIf.DelayDue("card:3:20410", 20_415), WhatIf.ExtraMonthly(999, 20_401), WhatIf.MovePayday(20_410),
        )
        assertEquals("SPEND|12345|20400\nDELAY|card:3:20410|20415\nEXTRA|999|20401\nPAYDAY|20410", WhatIfText.encode(list))
        assertEquals(list, WhatIfText.decode(WhatIfText.encode(list)))
        assertEquals(emptyList(), WhatIfText.decode(""))
        assertEquals(list, WhatIfText.decode("\n  " + WhatIfText.encode(list).replace("\n", "\n\n") + "\n"))
        for (bad in listOf("SPEND|x|1", "SPEND|1", "PAYDAY|1|2", "NOPE|1|2", "DELAY||5", "EXTRA|1|2|3")) assertNull(WhatIfText.decode(bad), bad)
    }
}
