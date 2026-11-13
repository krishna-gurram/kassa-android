package app.kassa.money.ui.coach

import app.kassa.money.domain.Account
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.CoachInput
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.SimDay
import app.kassa.money.domain.SimEvent
import app.kassa.money.domain.SimEventKind
import app.kassa.money.domain.SimMovable
import app.kassa.money.domain.SimVerdict
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.domain.WhatIf
import app.kassa.money.domain.millisAt
import app.kassa.money.domain.toEpochDay
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SimModelTest {

    private val today = 20_400
    private val bills = listOf(
        SimMovable(SimEventKind.BILL, "bill:1:20405", "Rent", 20_405, 90_000L),
        SimMovable(SimEventKind.CARD_PAYMENT, "card:2:20410", "Card", 20_410, 64_000L),
        SimMovable(SimEventKind.LOAN_PAYMENT, "loan:3:20430", "Car", 20_430, 30_000L),
    )

    private fun form(amount: String = "900", day: Int = today + 5) = SimForm(amount = amount, day = day)

    // ---------- chips become what-if text ----------

    @Test fun purchaseAndExtrasMakeTheText() {
        val f = form("900.00").copy(extras = listOf(WhatIf.MovePayday(today + 9), WhatIf.ExtraMonthly(12_000L, today + 2)))
        assertEquals("SPEND|90000|${today + 5}\nPAYDAY|${today + 9}\nEXTRA|12000|${today + 2}", f.text())
        assertEquals(3, f.whatIfs().size)
    }

    @Test fun noAmountMeansNoPurchase() {
        assertEquals("", form("").text())
        assertNull(form("").purchase())
    }

    @Test fun skipABillMovesItToALaterDay() {
        val open = SimModel.startTry(form(), SimTry.SKIP_BILL, bills, today)
        assertEquals(SimTry.SKIP_BILL, open.trying)
        assertEquals("bill:1:20405", open.tryItem)
        assertEquals(today + 30, open.tryDay)
        val made = SimModel.makeTry(open, bills, today)
        assertIs<SimModel.Made.Ok>(made)
        assertEquals(WhatIf.DelayDue("bill:1:20405", today + 30), made.form.extras.single())
        assertNull(made.form.trying)
    }

    @Test fun delayOffersOnlyCardsAndLoansWithRoomLeft() {
        val items = SimModel.movableFor(SimTry.DELAY_PAYMENT, bills + SimMovable(SimEventKind.CARD_PAYMENT, "card:9", "Late", today + 30, 1L), today)
        // The loan payment on the last day has no later day to move to, and neither has the one added on it.
        assertEquals(listOf("card:2:20410"), items.map { it.key })
        assertEquals(listOf("bill:1:20405"), SimModel.movableFor(SimTry.SKIP_BILL, bills, today).map { it.key })
        assertTrue(SimModel.movableFor(SimTry.MOVE_PAYDAY, bills, today).isEmpty())
    }

    @Test fun aDelayMustGoLater() {
        val open = SimModel.startTry(form(), SimTry.SKIP_BILL, bills, today).copy(tryDay = 20_405)
        assertEquals(SimFormProblem.NO_LATER_DAY, (SimModel.makeTry(open, bills, today) as SimModel.Made.No).problem)
        val none = SimModel.startTry(form(), SimTry.SKIP_BILL, emptyList(), today)
        assertEquals(SimFormProblem.PICK_PAYMENT, (SimModel.makeTry(none, emptyList(), today) as SimModel.Made.No).problem)
    }

    @Test fun paydayAndExtraNeedADayInTheMonth() {
        val payday = SimModel.startTry(form(), SimTry.MOVE_PAYDAY, bills, today).copy(tryDay = today + 31)
        assertEquals(SimFormProblem.DAY_OUTSIDE, (SimModel.makeTry(payday, bills, today) as SimModel.Made.No).problem)
        val extra = SimModel.startTry(form(), SimTry.EXTRA_MONTHLY, bills, today)
        assertEquals(SimFormProblem.AMOUNT_NEEDED, (SimModel.makeTry(extra, bills, today) as SimModel.Made.No).problem)
        val ok = SimModel.makeTry(extra.copy(tryAmount = "15.50"), bills, today)
        assertEquals(WhatIf.ExtraMonthly(1_550L, today), (ok as SimModel.Made.Ok).form.extras.single())
    }

    @Test fun tappingTheOpenChipAgainClosesIt() {
        val open = SimModel.startTry(form(), SimTry.MOVE_PAYDAY, bills, today)
        assertNull(SimModel.startTry(open, SimTry.MOVE_PAYDAY, bills, today).trying)
    }

    @Test fun removingAWhatIf() {
        val list = listOf<WhatIf>(WhatIf.Spend(100L, today), WhatIf.MovePayday(today + 1), WhatIf.Spend(300L, today + 2))
        assertEquals(listOf(list[0], list[2]), SimModel.remove(list, 1))
        assertEquals(list, SimModel.remove(list, 7))
        val text = SimModel.whatIfText(list)
        assertEquals(SimModel.whatIfText(listOf(list[1], list[2])), SimModel.removeFromText(text, 0))
        assertEquals("", SimModel.removeFromText(SimModel.whatIfText(listOf(list[0])), 0))
        assertEquals(emptyList(), SimModel.whatIfs("not a what-if"))
    }

    // ---------- validation ----------

    @Test fun amountIsBounded() {
        assertEquals(SimFormProblem.AMOUNT_NEEDED, SimModel.problem(form(""), today))
        assertEquals(SimFormProblem.AMOUNT_BAD, SimModel.problem(form("abc"), today))
        assertEquals(SimFormProblem.AMOUNT_BAD, SimModel.problem(form("0"), today))
        assertEquals(SimFormProblem.AMOUNT_TOO_BIG, SimModel.problem(form("100000000"), today))
        assertNull(SimModel.problem(form("99999999.99"), today))
        assertTrue(SimModel.amountOk(InputRules.AMOUNT_MAX))
    }

    @Test fun aBlankAmountIsFineWhenSomethingElseIsTried() {
        assertNull(SimModel.problem(form("").copy(extras = listOf(WhatIf.MovePayday(today + 3))), today))
    }

    @Test fun theDayStaysInsideTheMonth() {
        assertNull(SimModel.problem(form(day = today), today))
        assertNull(SimModel.problem(form(day = today + 30), today))
        assertEquals(SimFormProblem.DAY_OUTSIDE, SimModel.problem(form(day = today - 1), today))
        assertEquals(SimFormProblem.DAY_OUTSIDE, SimModel.problem(form(day = today + 31), today))
        assertTrue(SimModel.dayOk(today + 30, today) && !SimModel.dayOk(today + 31, today))
    }

    @Test fun atMostEightWhatIfs() {
        val many = form().copy(extras = List(8) { WhatIf.MovePayday(today + it) })
        assertEquals(SimFormProblem.TOO_MANY, SimModel.problem(many, today))
        val open = SimModel.startTry(form().copy(extras = List(7) { WhatIf.MovePayday(today + it) }), SimTry.MOVE_PAYDAY, bills, today)
        assertEquals(SimFormProblem.TOO_MANY, (SimModel.makeTry(open, bills, today) as SimModel.Made.No).problem)
    }

    // ---------- verdicts and tones ----------

    @Test fun verdictsMapToTones() {
        assertEquals(SimTone.GREEN, SimModel.tone(SimKind.ABOVE_CUSHION))
        assertEquals(SimTone.ORANGE, SimModel.tone(SimKind.TIGHT))
        assertEquals(SimTone.ORANGE, SimModel.tone(SimKind.SHORT))
        listOf(SimKind.NOT_ENOUGH_DATA, SimKind.NO_ACCOUNTS, SimKind.PROBLEM, SimKind.HIDDEN).forEach { assertEquals(SimTone.GREY, SimModel.tone(it)) }
    }

    @Test fun hiddenNeverSaysTheSizeButKeepsWhatSaysNone() {
        assertEquals(SimKind.HIDDEN, SimModel.kindOf(SimVerdict.ABOVE_CUSHION, true))
        assertEquals(SimKind.HIDDEN, SimModel.kindOf(SimVerdict.TIGHT, true))
        assertEquals(SimKind.HIDDEN, SimModel.kindOf(SimVerdict.SHORT, true))
        assertEquals(SimKind.HIDDEN, SimModel.kindOf(SimVerdict.NOT_ENOUGH_DATA, true))
        assertEquals(SimKind.NO_ACCOUNTS, SimModel.kindOf(SimVerdict.NO_ACCOUNTS, true))
        assertEquals(SimKind.PROBLEM, SimModel.kindOf(SimVerdict.PROBLEM, true))
        assertEquals(SimKind.SHORT, SimModel.kindOf(SimVerdict.SHORT, false))
    }

    private fun input(history: Boolean = true, accounts: Boolean = true): CoachInput {
        val zone = TimeZone.UTC
        val date = LocalDate.parse("2026-11-12")
        val account = Account(id = 1, name = "Checking", type = AccountType.CHECKING, openingBalance = 500_000L)
        val spent = Transaction(id = 1, kind = TxKind.EXPENSE, amount = 4_000L, accountId = 1, epochMillis = millisAt(LocalDate.parse(if (history) "2026-10-01" else "2026-11-10"), 12, 0, zone))
        return CoachInput(
            accounts = if (accounts) listOf(account) else emptyList(), balances = mapOf(1L to 496_000L), transactions = listOf(spent),
            categories = emptyList(), today = date, zone = zone,
        )
    }

    @Test fun hiddenHasNoChartDayOrEventButAShownOneHasAMonth() {
        val shown = SimModel.run(input(), listOf(WhatIf.Spend(10_000L, input().today.toEpochDay().toInt() + 3)), 0L, hidden = false)
        assertEquals(SimKind.ABOVE_CUSHION, shown.kind)
        assertEquals(31, shown.days.size)
        assertTrue(shown.hasChart && shown.lowestDay >= 0)
        val hidden = SimModel.run(input(), listOf(WhatIf.Spend(10_000L, input().today.toEpochDay().toInt() + 3)), 0L, hidden = true)
        assertEquals(SimKind.HIDDEN, hidden.kind)
        assertTrue(hidden.days.isEmpty() && hidden.baseline.isEmpty() && !hidden.hasChart)
        assertEquals(-1, hidden.lowestDay)
        assertEquals(0L, hidden.lowest)
        assertEquals(0L, hidden.shortBy)
    }

    @Test fun aBigPurchaseIsShortAndACushionMakesItTight() {
        val day = input().today.toEpochDay().toInt() + 2
        val short = SimModel.run(input(), listOf(WhatIf.Spend(900_000L, day)), 0L, false)
        assertEquals(SimKind.SHORT, short.kind)
        assertTrue(short.shortBy > 0L)
        val tight = SimModel.run(input(), emptyList(), 10_000_000L, false)
        assertEquals(SimKind.TIGHT, tight.kind)
    }

    @Test fun noAccountsAndTooLittleHistoryAreGrey() {
        assertEquals(SimKind.NO_ACCOUNTS, SimModel.run(input(accounts = false), emptyList(), 0L, false).kind)
        assertEquals(SimKind.NOT_ENOUGH_DATA, SimModel.run(input(history = false), emptyList(), 0L, false).kind)
    }

    @Test fun aRefusedWhatIfIsAProblemWithItsIndex() {
        val v = SimModel.run(input(), listOf(WhatIf.Spend(100L, 1)), 0L, false)
        assertEquals(SimKind.PROBLEM, v.kind)
        assertEquals(0, v.problemIndex)
    }

    // ---------- the answer and the day by day list ----------

    private fun ev(kind: SimEventKind, key: String, cents: Long, name: String = "") = SimEvent(kind, key, cents, false, name)

    private val days = listOf(
        SimDay(1, 1_000L, listOf(ev(SimEventKind.USUAL_SPENDING, "usual:1", -100L))),
        SimDay(2, 900L, listOf(ev(SimEventKind.USUAL_SPENDING, "usual:2", -100L))),
        SimDay(3, 500L, listOf(ev(SimEventKind.BILL, "bill:1:3", -300L, "Rent"), ev(SimEventKind.USUAL_SPENDING, "usual:3", -100L))),
        SimDay(4, 400L, listOf(ev(SimEventKind.USUAL_SPENDING, "usual:4", -100L))),
        SimDay(5, 300L, listOf(ev(SimEventKind.USUAL_SPENDING, "usual:5", -100L))),
        SimDay(6, 3_300L, listOf(ev(SimEventKind.PAYCHECK, "paycheck:6", 3_100L, "Salary"), ev(SimEventKind.USUAL_SPENDING, "usual:6", -100L))),
        SimDay(7, 3_200L, listOf(ev(SimEventKind.USUAL_SPENDING, "usual:7", -100L))),
    )

    @Test fun usualSpendingIsGatheredIntoOneRowPerGroup() {
        val groups = SimModel.groups(days, lowestDay = 5)
        assertEquals(listOf(3, 5, 6, 7), groups.map { it.day })
        assertEquals(listOf(SimEventKind.USUAL_SPENDING, SimEventKind.BILL), groups[0].rows.map { it.kind })
        assertEquals(3, groups[0].rows[0].days)
        assertEquals(-300L, groups[0].rows[0].amount)
        assertEquals(listOf(false, true, false, false), groups.map { it.lowest })
        assertEquals(listOf(500L, 300L, 3_300L, 3_200L), groups.map { it.balance })
        // Everything is in a group: what the rows add up to is what the month moved.
        assertEquals(days.sumOf { d -> d.events.sumOf { it.amountCents } }, groups.sumOf { g -> g.rows.sumOf { it.amount } })
    }

    @Test fun whatMovesLeavesOutUsualSpendingAndCountsTheRest() {
        val moves = SimModel.whatMoves(days)
        assertEquals(listOf(3 to SimEventKind.BILL, 6 to SimEventKind.PAYCHECK), moves.map { it.first to it.second.kind })
        assertEquals(1, SimModel.whatMoves(days, limit = 1).size)
        assertEquals(1, SimModel.moreMoves(days, limit = 1))
        assertEquals(0, SimModel.moreMoves(days))
    }

    @Test fun onlyACardPaymentIsOrangeAndMoneyInIsGreen() {
        assertEquals(SimTone.ORANGE, SimModel.figureTone(SimEventKind.CARD_PAYMENT, false))
        assertEquals(SimTone.GREEN, SimModel.figureTone(SimEventKind.PAYCHECK, true))
        assertEquals(SimTone.GREY, SimModel.figureTone(SimEventKind.BILL, false))
        assertEquals(SimTone.GREY, SimModel.figureTone(SimEventKind.SPEND, false))
        assertEquals(SimTone.GREY, SimModel.figureTone(SimEventKind.LOAN_PAYMENT, false))
    }

    // ---------- the cushion setting ----------

    @Test fun theCushionStartsAtZeroAndReadsWhatIsTyped() {
        assertEquals(0L, SimModel.CUSHION_DEFAULT)
        assertEquals(0L, SimModel.cushionFromText(""))
        assertEquals(100_000L, SimModel.cushionFromText("1000"))
        assertEquals(150L, SimModel.cushionFromText("1.50"))
        assertNull(SimModel.cushionFromText("abc"))
        assertNull(SimModel.cushionFromText("-5"))
        assertNull(SimModel.cushionFromText("100000000"))
        assertEquals(InputRules.AMOUNT_MAX, SimModel.cushionFromText("99999999.99"))
    }
}
