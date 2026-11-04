package app.kassa.money.domain

import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SplitRulesTest {

    private val d = SplitRules.Draft()
    private val x = TxKind.EXPENSE

    @Test
    fun onlyAnExpenseWithSomeoneIsSplit() {
        assertFalse(SplitRules.isShared(x, d))
        assertTrue(SplitRules.isShared(x, d.copy(people = listOf(1))))
        assertFalse(SplitRules.isShared(TxKind.INCOME, d.copy(people = listOf(1))))
        assertEquals(emptyMap(), SplitRules.shares(1000, TxKind.INCOME, d.copy(people = listOf(1))))
        assertEquals(1000L, SplitRules.myShare(1000, x, d))
        assertNull(SplitRules.problem(1000, TxKind.INCOME, d.copy(people = listOf(1), exact = true, exactShares = mapOf(1L to 5000L))))
    }

    @Test
    fun equalSharesRoundToTheCentAndTheOddCentGoesToTheFirstPerson() {
        val s = d.copy(people = listOf(1, 2))
        assertEquals(mapOf(1L to 3334L, 2L to 3333L), SplitRules.shares(10_001, x, s))
        assertEquals(3334L, SplitRules.myShare(10_001, x, s))
        assertEquals(10_001L, SplitRules.shares(10_001, x, s).values.sum() + SplitRules.myShare(10_001, x, s))
        assertEquals(mapOf(1L to 4225L), SplitRules.shares(8_450, x, d.copy(people = listOf(1))))
    }

    @Test
    fun exactSharesAreWhatWasTypedAndYoursIsWhatIsLeft() {
        val s = d.copy(people = listOf(1, 2), exact = true, exactShares = mapOf(1L to 5000L))
        assertEquals(mapOf(1L to 5000L, 2L to 0L), SplitRules.shares(12_000, x, s))
        assertEquals(7000L, SplitRules.myShare(12_000, x, s))
    }

    @Test
    fun exactSharesThatAddUpToMoreAreRefusedWithAndroidsSentence() {
        val s = d.copy(people = listOf(1, 2), exact = true, exactShares = mapOf(1L to 7000L, 2L to 6000L))
        assertEquals("The shares add up to more than $120.00.", SplitRules.problem(12_000, x, s))
        assertNull(SplitRules.problem(12_000, x, s.copy(exactShares = mapOf(1L to 7000L, 2L to 5000L))))
        assertNull(SplitRules.problem(12_000, x, s.copy(exactShares = mapOf(1L to 7000L, 2L to 5000L, 3L to 1L))), "shares that add up to the total leave you nothing, which is fine")
        assertNull(SplitRules.problem(12_000, x, s.copy(exactShares = mapOf(1L to 12_000L, 2L to 0L))))
    }

    @Test
    fun badPicksAreRefused() {
        val s = d.copy(people = listOf(1, 1))
        assertEquals("Each person can only be in a split once.", SplitRules.problem(100, x, s))
        assertEquals("Pick who paid.", SplitRules.problem(100, x, d.copy(people = listOf(1), paidBy = 2)))
        val negative = d.copy(people = listOf(1), exact = true, exactShares = mapOf(1L to -5L))
        assertTrue(SplitRules.problem(100, x, negative)!!.startsWith("Each share must be between 0 and"))
        val tooMany = d.copy(people = (1L..101L).toList())
        assertEquals("Split with up to 100 people.", SplitRules.problem(100_000, x, tooMany))
    }

    @Test
    fun togglingAddsRemovesAndLetsGoOfTheirShareAndPayer() {
        var s = SplitRules.toggle(d, 1)
        s = SplitRules.toggle(s, 2)
        assertEquals(listOf(1L, 2L), s.people)
        s = SplitRules.withPayer(s, 2)
        assertEquals(2L, s.paidBy)
        s = SplitRules.useExact(1000, s)
        s = SplitRules.typedShare(s, 2, "3.50")
        assertEquals(350L, s.exactShares[2])
        s = SplitRules.toggle(s, 2)
        assertEquals(listOf(1L), s.people)
        assertNull(s.paidBy)
        assertFalse(2L in s.exactShares)
        assertEquals(s, SplitRules.toggle(s, 0), "no person 0")
    }

    @Test
    fun payerMustBeInTheSplit() {
        val s = SplitRules.toggle(d, 1)
        assertEquals(s, SplitRules.withPayer(s, 9))
        assertNull(SplitRules.withPayer(SplitRules.withPayer(s, 1), null).paidBy)
    }

    @Test
    fun switchingToExactStartsFromEqualAndKeepsWhatWasTyped() {
        val s = d.copy(people = listOf(1, 2), exactShares = mapOf(2L to 900L, 3L to 100L))
        val e = SplitRules.useExact(10_001, s)
        assertTrue(e.exact)
        assertEquals(mapOf(1L to 3334L, 2L to 900L), e.exactShares)
        assertEquals(e, SplitRules.useExact(10_001, e))
        assertFalse(SplitRules.useEqual(e).exact)
    }

    @Test
    fun typedSharesKeepTheOldOneWhenEmptyOrOutOfBounds() {
        val s = d.copy(people = listOf(1), exact = true, exactShares = mapOf(1L to 500L))
        assertEquals(s, SplitRules.typedShare(s, 1, ""))
        assertEquals(s, SplitRules.typedShare(s, 1, "abc"))
        assertEquals(s, SplitRules.typedShare(s, 2, "5"))
        assertEquals(s, SplitRules.typedShare(s, 1, "999999999"))
        assertEquals(1234L, SplitRules.typedShare(s, 1, "12.34").exactShares[1])
        assertEquals(0L, SplitRules.typedShare(s, 1, "0").exactShares[1])
    }

    @Test
    fun whenYouPaidEachPersonOwesYouTheirShare() {
        val s = d.copy(people = listOf(1, 2), exact = true, exactShares = mapOf(1L to 5000L, 2L to 4000L))
        val p = SplitRules.plan(12_000, x, s)
        assertTrue(p.shared)
        assertNull(p.paidBy)
        assertEquals(3000L, p.myShare)
        assertEquals(listOf(1L to 5000L, 2L to 4000L), p.owed)
        assertEquals(listOf(2L to 4000L), SplitRules.plan(12_000, x, s.copy(exactShares = mapOf(1L to 0L, 2L to 4000L))).owed, "a zero share is no line")
    }

    @Test
    fun whenSomeoneElsePaidYouOweThemYourShareAndNothingElseIsTracked() {
        val s = d.copy(people = listOf(1, 2), paidBy = 2)
        val p = SplitRules.plan(9_000, x, s)
        assertEquals(2L, p.paidBy)
        assertEquals(3000L, p.myShare)
        assertEquals(listOf(2L to -3000L), p.owed)
        val allTheirs = SplitRules.plan(9_000, x, s.copy(exact = true, exactShares = mapOf(1L to 0L, 2L to 9000L)))
        assertEquals(0L, allTheirs.myShare)
        assertEquals(emptyList(), allTheirs.owed)
    }

    @Test
    fun notASplitPlansNothing() {
        assertEquals(SplitRules.Plan(false, null, null, emptyList()), SplitRules.plan(100, x, d))
        assertEquals(SplitRules.Plan(false, null, null, emptyList()), SplitRules.plan(100, TxKind.TRANSFER, d.copy(people = listOf(1))))
    }

    @Test
    fun aPlanThatAddsUpToTooMuchDoesNotThrow() {
        val s = d.copy(people = listOf(1), exact = true, exactShares = mapOf(1L to 200L))
        val p = SplitRules.plan(100, x, s)
        assertEquals(-100L, p.myShare)
        assertEquals(emptyList(), p.owed)
    }

    @Test
    fun sharesAndYoursAlwaysAddUpToTheTotal() {
        for (total in listOf(1L, 2L, 99L, 100L, 10_001L, 99_999_999L)) {
            for (n in 1..5) {
                val s = d.copy(people = (1L..n.toLong()).toList())
                assertEquals(total, SplitRules.shares(total, x, s).values.sum() + SplitRules.myShare(total, x, s), "$total / $n")
                assertTrue(SplitRules.myShare(total, x, s) >= 0)
            }
        }
    }

    @Test
    fun reopeningASplitReadsItsLines() {
        val equal = SplitRules.start(x, 9_000, 5, null, 3_000, mapOf(1L to 3000L, 2L to 3000L), 0)
        assertEquals(listOf(1L, 2L), equal.people)
        assertFalse(equal.exact)
        assertNull(equal.paidBy)
        val exact = SplitRules.start(x, 9_000, 5, null, 1_000, mapOf(1L to 5000L, 2L to 3000L), 0)
        assertTrue(exact.exact)
        assertEquals(mapOf(1L to 5000L, 2L to 3000L), exact.exactShares)
        val plain = SplitRules.start(x, 9_000, 5, null, null, emptyMap(), 0)
        assertEquals(SplitRules.Draft(), plain)
        val withPerson = SplitRules.start(x, 9_000, 5, null, null, emptyMap(), 4)
        assertEquals(listOf(4L), withPerson.people)
        assertFalse(withPerson.exact, "an unsplit expense opens equal")
        assertEquals(emptyList(), SplitRules.start(x, 9_000, 5, null, 3_000, mapOf(1L to -3000L), 0).people, "what you owe is not a share")
    }

    @Test
    fun reopeningAnExpenseSomeoneElsePaidNamesThemAsPayer() {
        val s = SplitRules.start(x, 9_000, null, 2, 3_000, emptyMap(), 0)
        assertEquals(2L, s.paidBy)
        assertEquals(listOf(2L), s.people)
        assertEquals(mapOf(2L to 6000L), s.exactShares)
        val back = SplitRules.plan(9_000, x, s)
        assertEquals(3000L, back.myShare)
        assertEquals(listOf(2L to -3000L), back.owed)
    }

    @Test
    fun aSavedSplitReopensToTheSamePlan() {
        val s = d.copy(people = listOf(1, 2), exact = true, exactShares = mapOf(1L to 5000L, 2L to 4000L))
        val p = SplitRules.plan(12_000, x, s)
        val lines = p.owed.toMap()
        val again = SplitRules.start(x, 12_000, 5, null, p.myShare, lines, 0)
        assertEquals(p, SplitRules.plan(12_000, x, again))
    }

    @Test
    fun percentRoundsAndNeedsATotal() {
        assertEquals(33, SplitRules.percent(3334, 10_001))
        assertEquals(50, SplitRules.percent(1, 2))
        assertEquals(0, SplitRules.percent(5, 0))
    }

    @Test
    fun whichExpensesAPersonCanBeSplitInto() {
        fun can(kind: TxKind = x, account: Long? = 1, already: Boolean = false, loan: Boolean = false, day: Int = 100) =
            SplitRules.canSplitWith(kind, account, already, loan, day, 150)
        assertTrue(can())
        assertTrue(can(day = 90), "exactly 60 days back")
        assertFalse(can(day = 89))
        assertTrue(can(day = 151))
        assertFalse(can(kind = TxKind.INCOME))
        assertFalse(can(account = null), "someone else already paid it")
        assertFalse(can(already = true))
        assertFalse(can(loan = true))
    }

    @Test
    fun theListOfPeopleIsAToZAndKeepsArchivedOnesOnlyInTheSplit() {
        val people = listOf(Person(1, "zed"), Person(2, "Amy"), Person(3, "Old", archived = true), Person(4, "bo"))
        assertEquals(listOf("Amy", "bo", "zed"), SplitRules.pickable(people, emptyList()).map { it.name })
        assertEquals(listOf("Amy", "bo", "Old", "zed"), SplitRules.pickable(people, listOf(3)).map { it.name })
    }
}
