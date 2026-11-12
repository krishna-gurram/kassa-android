package app.kassa.money.domain

import app.kassa.money.domain.CoachFacts.FactUnit
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The payload an opt-in "ask" would send: only totals, never anything the person typed. */
class CoachFactsTest {

    private val distinctive = listOf("ZQXMerchant", "ZQXAcct", "ZQXCat", "ZQXPerson", "ZQXSub", "ZQXBill", "ZQXNote", "ZQXLoan", "ZQXCard")

    /** A rich ledger where every kind of free text carries a marker word and every id is a large, recognisable number. */
    private fun marked(): CoachFx {
        val fx = CoachFx()
        fx.accounts.clear()
        fx.account(987_654_321, "ZQXAcct checking", AccountType.CHECKING, opening = 900_000)
        fx.account(987_654_322, "ZQXAcct savings", AccountType.SAVINGS, opening = 300_000)
        fx.account(987_654_323, "ZQXCard", AccountType.CREDIT_CARD, statementDay = 5, dueDay = 20)
        fx.account(987_654_324, "ZQXLoan", AccountType.LOAN, opening = -500_000)
        fx.categories.clear()
        fx.categories.add(Category(id = 876_543_210, name = "ZQXCat food", kind = CategoryKind.EXPENSE, color = 0))
        fx.categories.add(Category(id = 876_543_211, name = "ZQXCat inc", kind = CategoryKind.INCOME, color = 0))
        for (m in listOf("2026-08", "2026-09", "2026-10")) {
            fx.spend("$m-10", 10_000, 876_543_210, "ZQXMerchant visit", account = 987_654_321)
            fx.earn("$m-28", 400_000, "ZQXNote pay", 876_543_211, account = 987_654_321)
        }
        for ((i, m) in listOf("2026-08", "2026-09", "2026-10", "2026-11").withIndex()) {
            fx.spend("$m-05", if (i == 3) 1_999 else 1_599, 876_543_210, "ZQXSub", account = 987_654_321)
        }
        fx.spend("2026-10-20", 21_000, 876_543_210, "ZQXMerchant card", account = 987_654_323, person = null)
        fx.other(TxKind.PERSON_OUT, "2026-11-01", 5_000, account = 987_654_321, person = 765_432_100, note = "ZQXPerson lunch")
        fx.bills.add(Recurring(id = 654_321_000, name = "ZQXBill", categoryId = 876_543_210, accountId = 987_654_321, dayOfMonth = 20, fixedAmount = 7_000))
        fx.budgets.add(Budget(876_543_210, 0, 50_000))
        fx.loanTerms[987_654_324] = LoanTerms(
            accountId = 987_654_324, type = InterestType.REDUCING, rate = 0, payment = 30_000, nextPaymentDay = fx.day("2026-11-20"), dueDay = 20,
        )
        return fx
    }

    @Test
    fun noFreeTextOrIdCanAppearInThePayload() {
        val fx = marked()
        val report = Coach.report(fx.input())
        val text = CoachFacts.serialize(report.facts)
        assertTrue(text.isNotEmpty())
        val lower = text.lowercase()
        for (word in distinctive) assertFalse(word.lowercase() in lower, "$word leaked into the payload")
        for (id in listOf("987654321", "987654322", "987654323", "987654324", "876543210", "876543211", "765432100", "654321000")) {
            assertFalse(id in text, "id $id leaked into the payload")
        }
        // Nothing but a key and a number on each line.
        for (line in text.lines()) assertTrue(Regex("^[a-z_]+: -?\\d+(\\.\\d+)?$").matches(line), "odd line: $line")
        // Not even a stray letter of a name: the only letters are in the keys.
        for (f in report.facts) assertTrue(f.key in CoachFacts.KEYS)
        // The facts themselves hold no text beyond the key.
        assertEquals(report.facts.map { it.key }.toSet().size, report.facts.size)
    }

    @Test
    fun theKeysComeInTheDocumentedOrderAndAreStable() {
        val report = Coach.report(marked().input())
        val keys = report.facts.map { it.key }
        assertEquals(CoachFacts.KEYS.filter { it in keys }, keys)
        for (k in listOf("safe_to_spend", "upcoming_due_total", "spending_month", "spending_prev_month", "liquid_balance_months")) {
            assertTrue(k in keys, "missing $k")
        }
        for (k in CoachFacts.KEYS) assertTrue(Regex("^[a-z]+(_[a-z]+)*$").matches(k), k)
    }

    @Test
    fun theValuesAreTheTotals() {
        val fx = CoachFx.lived()
        fx.bills.add(Recurring(id = 5, name = "Internet", categoryId = 14, accountId = 1, dayOfMonth = 20, fixedAmount = 5_000))
        fx.budgets.add(Budget(10, 0, 20_000))
        fx.spend("2026-11-04", 4_000, 10)
        val r = Coach.report(fx.input(mapOf(1L to 500_000L, 2L to 300_000L)))
        val byKey = r.facts.associate { it.key to it.value }
        assertEquals(r.safe.amount, byKey["safe_to_spend"])
        assertEquals(0L, byKey["safe_to_spend_short_by"])
        assertEquals(16L, byKey["safe_to_spend_days"])
        assertEquals(800_000L, byKey["liquid_balance"])
        assertEquals(5_000L, byKey["upcoming_due_total"])
        assertEquals(5_000L, byKey["upcoming_bills_total"])
        assertEquals(0L, byKey["upcoming_cards_total"])
        assertEquals(326L, byKey["usual_daily_spending"])
        // November so far: the rent (1 Nov) and the groceries.
        assertEquals(154_000L, byKey["spending_month"])
        // October: rent 1,500.00 + groceries 100.00 (on the 10th, so inside the same 12 days).
        assertEquals(160_000L, byKey["spending_prev_month"])
        assertEquals(160_000L, byKey["spending_prev_month_same_day"])
        assertEquals(0L, byKey["income_month"])
        assertEquals(400_000L, byKey["income_prev_month"])
        assertEquals(20_000L, byKey["budget_total"])
        assertEquals(4_000L, byKey["budget_spent_in_budgeted"])
        assertEquals(12L, byKey["budget_month_day"])
        assertEquals(30L, byKey["budget_month_days"])
        assertEquals(0L, byKey["categories_over_budget"])
        assertEquals(150_000L, byKey["subscriptions_monthly_total"])
        assertEquals(0L, byKey["subscriptions_increase"])
        // Fixed expenses are 1,500.00 a month on a monthly 1,600.00: 8,000.00 / 1,600.00 = 5.0 months.
        assertEquals(50L, byKey["liquid_balance_months"])
    }

    @Test
    fun anEmptyLedgerSendsOnlyWhatItKnows() {
        val fx = CoachFx()
        fx.accounts.clear()
        val r = Coach.report(fx.input())
        val keys = r.facts.map { it.key }
        assertFalse("safe_to_spend" in keys)
        assertFalse("liquid_balance_months" in keys)
        assertFalse("week_spending" in keys)
        assertFalse("budget_total" in keys)
        assertTrue("spending_month" in keys)
        for (line in CoachFacts.serialize(r.facts).lines()) assertTrue(Regex("^[a-z_]+: -?\\d+(\\.\\d+)?$").matches(line))
    }

    @Test
    fun valuesAreWrittenTheSameInEveryLanguage() {
        assertEquals("482.00", CoachFacts.valueText(CoachFacts.Fact("k", 48_200, FactUnit.CENTS)))
        assertEquals("0.05", CoachFacts.valueText(CoachFacts.Fact("k", 5, FactUnit.CENTS)))
        assertEquals("-12.50", CoachFacts.valueText(CoachFacts.Fact("k", -1_250, FactUnit.CENTS)))
        assertEquals("1234567.89", CoachFacts.valueText(CoachFacts.Fact("k", 123_456_789, FactUnit.CENTS)))
        assertEquals("2.1", CoachFacts.valueText(CoachFacts.Fact("k", 21, FactUnit.TENTHS_OF_MONTH)))
        assertEquals("0.0", CoachFacts.valueText(CoachFacts.Fact("k", 0, FactUnit.TENTHS_OF_MONTH)))
        assertEquals("7", CoachFacts.valueText(CoachFacts.Fact("k", 7, FactUnit.COUNT)))
        assertEquals("a: 1.00\nb: 3", CoachFacts.serialize(listOf(CoachFacts.Fact("a", 100, FactUnit.CENTS), CoachFacts.Fact("b", 3, FactUnit.DAYS))))
        // The phone's number style never changes it.
        val style = NumberStyle(',', '.', '0', false, "-")
        Money.withStyle(style) {
            assertEquals("482.00", CoachFacts.valueText(CoachFacts.Fact("k", 48_200, FactUnit.CENTS)))
        }
    }

    @Test
    fun theReportIsTheSameEveryTime() {
        val fx = marked()
        assertEquals(CoachFacts.serialize(Coach.report(fx.input()).facts), CoachFacts.serialize(Coach.report(fx.input()).facts))
    }
}
