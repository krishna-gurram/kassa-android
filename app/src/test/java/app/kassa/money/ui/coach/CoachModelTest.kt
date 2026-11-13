package app.kassa.money.ui.coach

import app.kassa.money.domain.CoachFacts
import app.kassa.money.domain.CoachReport
import app.kassa.money.domain.EmergencyFund
import app.kassa.money.domain.Money
import app.kassa.money.domain.SafeToSpend
import app.kassa.money.domain.SpendingPace
import app.kassa.money.domain.SubscriptionChange
import app.kassa.money.domain.WeeklyCheckIn
import app.kassa.money.ui.NavViewModel
import app.kassa.money.ui.Page
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CoachModelTest {
    /** The Overview card rule with Coach switched on, so these tests keep proving the rule whatever [CoachFeature.VISIBLE] is. */
    private fun shownTeaser(v: CoachView, enabled: Boolean, dismissed: Int) = CoachModel.teaser(v, enabled, dismissed, visible = true)

    @Test
    fun hiddenCoachShowsNoOverviewCard() {
        val v = CoachModel.view(report(), hidden = false)
        assertNotNull(CoachModel.teaser(v, true, Int.MIN_VALUE, visible = true))
        assertNull(CoachModel.teaser(v, true, Int.MIN_VALUE, visible = false))
        // The shipped default follows the switch: hidden means no card.
        assertEquals(CoachFeature.VISIBLE, CoachModel.teaser(v, true, Int.MIN_VALUE) != null)
    }

    @Test
    fun hiddenCoachIgnoresEveryCoachRoute() {
        val coachPages = listOf(Page.Coach, Page.CheckIn, Page.CanIAfford, Page.SimulateMonth(), Page.SimulateMonth("x"))
        for (p in coachPages) {
            assertTrue(CoachFeature.isCoachPage(p))
            assertEquals(false, CoachFeature.allows(p, visible = false))
            assertEquals(true, CoachFeature.allows(p, visible = true))
        }
        assertEquals(true, CoachFeature.allows(Page.Upcoming, visible = false))
        assertEquals(true, CoachFeature.allows(Page.Notifications, visible = false))
        // With the shipped default, navigation drops a Coach route (only while hidden) and keeps others.
        val nav = NavViewModel()
        nav.open(Page.Coach, besideList = true)
        nav.push(Page.CheckIn)
        assertEquals(if (CoachFeature.VISIBLE) 2 else 0, nav.stack.size)
        nav.push(Page.Upcoming)
        assertEquals(if (CoachFeature.VISIBLE) 3 else 1, nav.stack.size)
    }

    private fun safe(kind: SafeToSpend.Kind = SafeToSpend.Kind.SPENDABLE) = SafeToSpend.Result(
        kind, 48_200L, 0L, 20_000, SafeToSpend.UntilKind.NEXT_INCOME, 3, 40, 100_000L, 4_000L, SafeToSpend.Basis.HISTORY, emptyList(),
    )

    private fun row(id: Long, over: Boolean = false, pace: Boolean = false, above: Boolean = false) =
        SpendingPace.Row(id, 47_100L, 40_000L, 52_000L, 59_000L, 52_000L, true, above, over, pace, 7_000L)

    private val sub = SubscriptionChange.Row("streaming", "Streaming", 5L, 1_599L, 1_999L, 400L, 20_000)

    private fun fund(kind: EmergencyFund.Kind = EmergencyFund.Kind.READY) = EmergencyFund.Result(kind, 200_000L, 90_000L, 3, 21, 25, 5_000L)

    private val win = WeeklyCheckIn.Win(WeeklyCheckIn.WinKind.UNDER_USUAL, 10L, 8_000L, 12_800L, 4_800L)
    private val watch = WeeklyCheckIn.Watch(
        WeeklyCheckIn.WatchKind.CATEGORY_PACE, 11L, "", "", 47_100L, 59_000L, 52_000L, WeeklyCheckIn.Reference.BUDGET, 7_000L, 12, 30, 91,
    )
    private val decision = WeeklyCheckIn.Decision(11L, 40_000L, 3, 47_100L, null)

    private fun checkIn(kind: WeeklyCheckIn.Kind = WeeklyCheckIn.Kind.READY, w: WeeklyCheckIn.Win? = win, a: WeeklyCheckIn.Watch? = watch, d: WeeklyCheckIn.Decision? = decision) =
        WeeklyCheckIn.Result(kind, 20_400, 31_200L, 35_000L, 30_000L, 3, w, a, d)

    private fun report(
        s: SafeToSpend.Result = safe(),
        rows: List<SpendingPace.Row> = listOf(row(11, pace = true)),
        subs: List<SubscriptionChange.Row> = listOf(sub),
        f: EmergencyFund.Result = fund(),
        c: WeeklyCheckIn.Result = checkIn(),
    ) = CoachReport(
        s, SpendingPace.Result(12, 30, false, rows), SubscriptionChange.Result(subs, 6_297L, subs.sumOf { it.change }, 3), f, c,
        listOf(CoachFacts.Fact("safe_to_spend", 48_200L, CoachFacts.FactUnit.CENTS), CoachFacts.Fact("safe_to_spend_days", 3L, CoachFacts.FactUnit.DAYS)),
    )

    @Test
    fun cardsAreOnlyTheRowsThatExistInAFixedOrder() {
        val all = CoachModel.view(report(), hidden = false).cards
        assertEquals(3, all.size)
        assertTrue(all[0] is InsightCard.Pace)
        assertTrue(all[1] is InsightCard.Subscription)
        assertTrue(all[2] is InsightCard.Fund)
        val none = CoachModel.view(report(rows = emptyList(), subs = emptyList(), f = fund(EmergencyFund.Kind.NOT_ENOUGH_DATA)), hidden = false).cards
        assertTrue(none.isEmpty())
        // A category row nothing flags is no card.
        assertTrue(CoachModel.view(report(rows = listOf(row(11)), subs = emptyList(), f = fund(EmergencyFund.Kind.NO_SPENDING)), hidden = false).cards.isEmpty())
    }

    @Test
    fun paceKindIsTheStrongestFlag() {
        assertEquals(PaceKind.OVER_BUDGET, CoachModel.paceKind(row(1, over = true, pace = true, above = true)))
        assertEquals(PaceKind.PACE_OVER_BUDGET, CoachModel.paceKind(row(1, pace = true, above = true)))
        assertEquals(PaceKind.ABOVE_LAST, CoachModel.paceKind(row(1, above = true)))
    }

    @Test
    fun aWatchOutsPaceKindFollowsItsReference() {
        assertEquals(PaceKind.PACE_OVER_BUDGET, CoachModel.paceKind(watch))
        assertEquals(PaceKind.OVER_BUDGET, CoachModel.paceKind(watch.copy(amount = 60_000L)))
        assertEquals(PaceKind.ABOVE_LAST, CoachModel.paceKind(watch.copy(referenceKind = WeeklyCheckIn.Reference.LAST_MONTH)))
    }

    @Test
    fun tapsGoToTheExistingPages() {
        val cards = CoachModel.view(report(), hidden = false).cards
        assertEquals(Target.Category(11L), CoachModel.target(cards[0]))
        assertEquals(Target.Repeating, CoachModel.target(cards[1]))
        // The emergency fund card goes nowhere: the Accounts tab would show credit cards and their due lines.
        assertNull(CoachModel.target(cards[2]))
    }

    @Test
    fun hiddenBalancesTakeAwayStateCardsAndTheCheckInsContent() {
        val v = CoachModel.view(report(), hidden = true)
        assertEquals(SafeKind.HIDDEN, v.safeKind)
        assertTrue(v.cards.isEmpty())
        assertEquals(CheckInState.HIDDEN, v.checkIn.state)
        assertNull(v.checkIn.win)
        assertNull(v.checkIn.watch)
        assertNull(v.checkIn.decision)
        // No account to read is a fact about the data, not about a figure, so it stays.
        assertEquals(SafeKind.NO_ACCOUNTS, CoachModel.view(report(s = safe(SafeToSpend.Kind.NO_ACCOUNTS)), hidden = true).safeKind)
    }

    @Test
    fun shortfallAndInsightCardsSwitch() {
        assertEquals(SafeKind.SHORTFALL, CoachModel.view(report(s = safe(SafeToSpend.Kind.SHORTFALL)), hidden = false).safeKind)
        assertTrue(CoachModel.view(report(), hidden = false, showCards = false).cards.isEmpty())
    }

    @Test
    fun headlineFollowsWhatWasFound() {
        fun h(w: WeeklyCheckIn.Win?, a: WeeklyCheckIn.Watch?) = CoachModel.view(report(c = checkIn(w = w, a = a)), hidden = false).checkIn.headline
        assertEquals(Headline.WIN_AND_WATCH, h(win, watch))
        assertEquals(Headline.WIN, h(win, null))
        assertEquals(Headline.WATCH, h(null, watch))
        assertEquals(Headline.QUIET, h(null, null))
    }

    @Test
    fun theOverviewCardShowsOnlyWhenThereIsSomethingToSay() {
        val v = CoachModel.view(report(), hidden = false)
        val none = Int.MIN_VALUE
        assertEquals(TeaserKind.WATCH, shownTeaser(v, true, none)?.kind)
        assertEquals(TeaserKind.WIN, shownTeaser(CoachModel.view(report(c = checkIn(a = null, d = null)), false), true, none)?.kind)
        // Nothing to say, too little data, switched off.
        assertNull(shownTeaser(CoachModel.view(report(c = checkIn(w = null, a = null, d = null)), false), true, none))
        assertNull(shownTeaser(CoachModel.view(report(c = checkIn(WeeklyCheckIn.Kind.NOT_ENOUGH_DATA)), false), true, none))
        assertNull(shownTeaser(v, false, none))
    }

    @Test
    fun notNowQuietsOnlyThatWeek() {
        val v = CoachModel.view(report(), hidden = false)
        assertNull(shownTeaser(v, true, v.checkIn.weekStartDay))
        assertNotNull(shownTeaser(v, true, v.checkIn.weekStartDay - 7))
    }

    @Test
    fun hiddenBalancesLeaveOnlyANeutralCard() {
        val t = shownTeaser(CoachModel.view(report(), hidden = true), true, Int.MIN_VALUE)
        assertEquals(TeaserKind.NEUTRAL, t?.kind)
        assertNull(t?.watch)
        assertNull(t?.win)
    }

    @Test
    fun weekAgainstTheUsualWeek() {
        fun c(spent: Long, usual: Long, has: Boolean = true) = CoachModel.view(report(c = checkIn()), false).checkIn.copy(spent = spent, usualByNow = usual, hasUsual = has)
        assertEquals(CoachModel.Versus.UNDER, CoachModel.versus(c(100, 150)))
        assertEquals(50L, CoachModel.versusAmount(c(100, 150)))
        assertEquals(CoachModel.Versus.OVER, CoachModel.versus(c(200, 150)))
        assertEquals(CoachModel.Versus.SAME, CoachModel.versus(c(150, 150)))
        assertEquals(CoachModel.Versus.NONE, CoachModel.versus(c(150, 0, has = false)))
    }

    @Test
    fun textHelpers() {
        assertEquals("2.1", CoachModel.tenthsText(21, "."))
        assertEquals("0,5", CoachModel.tenthsText(5, ","))
        assertEquals(0f, CoachModel.usedFraction(-1))
        assertEquals(1f, CoachModel.usedFraction(140))
        assertEquals(0.4f, CoachModel.usedFraction(40))
        val join = { a: String, b: String -> "$a, $b" }
        val last = { a: String, b: String -> "$a and $b" }
        assertEquals("", CoachModel.joinList(emptyList(), join, last))
        assertEquals("rent", CoachModel.joinList(listOf("rent"), join, last))
        assertEquals("rent and cards", CoachModel.joinList(listOf("rent", "cards"), join, last))
        assertEquals("rent, cards and food", CoachModel.joinList(listOf("rent", "cards", "food"), join, last))
    }

    @Test
    fun whatAQuestionSendsIsMaskedWhileHiddenExceptDays() {
        val facts = report().facts
        assertEquals(listOf("safe_to_spend" to "482.00", "safe_to_spend_days" to "3"), CoachModel.factRows(facts, hidden = false))
        assertEquals(listOf("safe_to_spend" to Money.MASK, "safe_to_spend_days" to "3"), CoachModel.factRows(facts, hidden = true))
    }
}
