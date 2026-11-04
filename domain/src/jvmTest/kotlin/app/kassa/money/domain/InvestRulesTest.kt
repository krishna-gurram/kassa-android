package app.kassa.money.domain

import java.math.BigInteger
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** What a trade does to the holdings, mirroring `InvestmentRepository`; every figure is worked out again here with BigInteger. */
class InvestRulesTest {
    private val abc = Holding(id = 1, accountId = 6, ticker = "ABC", microShares = 12_500_000, price = 18_000, priceMillis = 10, avgCost = 16_000)

    private fun halfUp(n: BigInteger, d: BigInteger): BigInteger = n.multiply(BigInteger.TWO).add(d).divide(d.multiply(BigInteger.TWO))
    private fun cost(micro: Long, price: Long): Long = halfUp(BigInteger.valueOf(micro).multiply(BigInteger.valueOf(price)), BigInteger.valueOf(1_000_000)).toLong()

    private fun apply(list: List<Holding>, plan: TradePlan): List<Holding> {
        var result = list
        for (op in plan.ops) {
            result = when (op.kind) {
                HoldingOpKind.DELETE -> result.filter { it.id != op.holding.id }
                HoldingOpKind.UPDATE -> result.map { if (it.id == op.holding.id) op.holding else it }
                HoldingOpKind.INSERT -> result + op.holding.copy(id = (result.maxOfOrNull { it.id } ?: 0L) + 1)
            }
        }
        return result
    }

    @Test
    fun aBuyGrowsTheHoldingAndBlendsTheAverageCost() {
        // 12.5 at 160.00 plus 2.5 at 200.00: 15 shares, average (200000 + 50000) / 15 = 16666.67 -> 16667.
        val plan = InvestRules.buy(listOf(abc), 6, " abc ", 2_500_000, 20_000, 99)
        assertNull(plan.problem)
        assertEquals("ABC", plan.ticker)
        assertEquals(cost(2_500_000, 20_000), plan.total)
        val h = apply(listOf(abc), plan).single()
        assertEquals(15_000_000, h.microShares)
        assertEquals(20_000, h.price)
        assertEquals(99, h.priceMillis)
        assertEquals(halfUp(BigInteger.valueOf(12_500_000L * 16_000 + 2_500_000L * 20_000), BigInteger.valueOf(15_000_000)).toLong(), h.avgCost)
    }

    @Test
    fun aBuyOfSomethingNewStartsAHoldingAtThePrice() {
        val plan = InvestRules.buy(listOf(abc), 6, "xyz", 1_500_000, 3_333, 5)
        val ops = plan.ops
        assertEquals(1, ops.size)
        assertEquals(HoldingOpKind.INSERT, ops[0].kind)
        assertEquals(0, ops[0].holding.id)
        assertEquals(3_333, ops[0].holding.avgCost)
        assertEquals(cost(1_500_000, 3_333), plan.total)
    }

    @Test
    fun aSaleShrinksTheHoldingAndKeepsTheAverage() {
        val plan = InvestRules.sell(listOf(abc), 6, "ABC", 2_500_000, 19_000, 7)
        val h = apply(listOf(abc), plan).single()
        assertEquals(10_000_000, h.microShares)
        assertEquals(16_000, h.avgCost)
        assertEquals(cost(2_500_000, 19_000), plan.total)
    }

    @Test
    fun sellingEverythingRemovesTheHolding() {
        val plan = InvestRules.sell(listOf(abc), 6, "ABC", 12_500_000, 19_000, 7)
        assertEquals(listOf(HoldingOpKind.DELETE), plan.ops.map { it.kind })
        assertTrue(apply(listOf(abc), plan).isEmpty())
    }

    @Test
    fun refusalsUseTheRepositorysSentences() {
        assertEquals("You don't hold ZZZ here.", InvestRules.sell(listOf(abc), 6, "zzz", 1_000_000, 100, 0).problem)
        assertEquals("You hold 12.5 shares of ABC.", InvestRules.sell(listOf(abc), 6, "ABC", 12_500_001, 100, 0).problem)
        assertEquals("Enter how many shares.", InvestRules.buy(emptyList(), 6, "ABC", 0, 100, 0).problem)
        assertEquals("Price: Enter an amount.", InvestRules.buy(emptyList(), 6, "ABC", 1_000_000, 0, 0).problem)
        assertEquals("That trade comes to less than one cent.", InvestRules.buy(emptyList(), 6, "ABC", 1, 1, 0).problem)
        assertEquals("Use the ticker as listed, like VTI or BRK.B.", InvestRules.buy(emptyList(), 6, "A B", 1_000_000, 100, 0).problem)
        assertEquals("Enter the ticker.", InvestRules.buy(emptyList(), 6, "  ", 1_000_000, 100, 0).problem)
        val big = InvestRules.buy(emptyList(), 6, "ABC", 999_999_999_999_999L, AmountMax, 0).problem
        assertNotNull(big)
        assertTrue(big.startsWith("Total: "), big)
    }

    private val AmountMax = InputRules.AMOUNT_MAX

    @Test
    fun aBuyThenItsDeleteLeavesTheHoldingAsItWas() {
        val buy = InvestRules.buy(listOf(abc), 6, "ABC", 2_500_000, 20_000, 99)
        val after = apply(listOf(abc), buy)
        val entry = Transaction(id = 50, kind = TxKind.BUY, amount = buy.total, accountId = 6, epochMillis = 99, note = "ABC")
        val trade = Trade(id = 3, transactionId = 50, ticker = "ABC", microShares = 2_500_000, price = 20_000)
        val gone = apply(after, InvestRules.delete(after, entry, trade))
        assertEquals(abc.microShares, gone.single().microShares)
        // 15 x 16667 = 250005; less 2.5 x 200.00 = 50000 leaves 200005 over 12.5 shares: 16000.4 -> 16000.
        assertEquals(16_000, gone.single().avgCost)
    }

    @Test
    fun aSaleThenItsDeleteBringsBackAHoldingSoldToZero() {
        val sale = InvestRules.sell(listOf(abc), 6, "ABC", 12_500_000, 19_000, 7)
        val empty = apply(listOf(abc), sale)
        assertTrue(empty.isEmpty())
        val entry = Transaction(id = 51, kind = TxKind.SELL, amount = sale.total, accountId = 6, epochMillis = 7, note = "ABC")
        val trade = Trade(id = 4, transactionId = 51, ticker = "ABC", microShares = 12_500_000, price = 19_000)
        val back = apply(empty, InvestRules.delete(empty, entry, trade))
        assertEquals(12_500_000, back.single().microShares)
        assertEquals(19_000, back.single().price)
        assertNull(back.single().avgCost)
    }

    @Test
    fun editingABuyUndoesTheOldTradeAndAppliesTheNew() {
        // The demo's shape: one buy of 12.5 at 160.00 made the holding.
        val held = listOf(abc)
        val entry = Transaction(id = 332, kind = TxKind.BUY, amount = 200_000, accountId = 6, epochMillis = 5, note = "ABC")
        val trade = Trade(id = 1, transactionId = 332, ticker = "ABC", microShares = 12_500_000, price = 16_000)
        val plan = InvestRules.edit(held, entry, trade, "ABC", 10_000_000, 15_000, 8)
        assertNull(plan.problem)
        assertEquals(150_000, plan.total)
        // Undone: the holding is gone (all its shares came from this buy); applied: 10 shares at 150.00, average 150.00.
        assertEquals(listOf(HoldingOpKind.DELETE, HoldingOpKind.INSERT), plan.ops.map { it.kind })
        val h = apply(held, plan).single()
        assertEquals(10_000_000, h.microShares)
        assertEquals(15_000, h.avgCost)
    }

    @Test
    fun editingNeedsRecordedShares() {
        val entry = Transaction(id = 9, kind = TxKind.BUY, amount = 100, accountId = 6, epochMillis = 5, note = "ABC")
        assertEquals(
            "This trade's shares weren't recorded, so only its date can change.",
            InvestRules.edit(listOf(abc), entry, null, "ABC", 1_000_000, 100, 0).problem,
        )
    }

    @Test
    fun savingAHoldingChecksLikeTheRepository() {
        assertEquals("Average cost: Enter an amount.", InvestRules.saveHolding(listOf(abc), abc.copy(avgCost = 0)).problem)
        assertEquals("Shares can't be negative.", InvestRules.saveHolding(listOf(abc), abc.copy(microShares = -1)).problem)
        // A ticker saved before the rules stays until it is changed.
        val old = abc.copy(ticker = "old ticker!")
        assertNull(InvestRules.saveHolding(listOf(old), old.copy(price = 100)).problem)
        assertEquals("Use the ticker as listed, like VTI or BRK.B.", InvestRules.saveHolding(listOf(old), old.copy(ticker = "no way!")).problem)
        val added = InvestRules.saveHolding(listOf(abc), Holding(accountId = 6, ticker = "vti", microShares = 1_000_000, price = 100, priceMillis = 1, avgCost = 100))
        assertEquals(HoldingOpKind.INSERT, added.ops.single().kind)
        assertEquals("VTI", added.ops.single().holding.ticker)
    }

    @Test
    fun theSheetsCheckEachFieldAndSalesCheckWhatIsHeld() {
        val none = InvestRules.tradeProblems(true, "", null, "", "", emptyList())
        assertEquals(listOf(InvestField.TICKER, InvestField.SHARES, InvestField.PRICE), none.map { it.field })
        val tooMany = InvestRules.tradeProblems(false, "abc", null, "20", "100", listOf(abc))
        assertEquals(listOf(InvestField.SHARES), tooMany.map { it.field })
        assertEquals("You hold 12.5.", tooMany.single().message)
        assertEquals("Pick a holding to sell.", InvestRules.tradeProblems(false, "xyz", null, "1", "100", listOf(abc)).single().message)
        assertTrue(InvestRules.tradeProblems(false, "ABC", null, "1", "100", listOf(abc)).isEmpty())
        // An edited entry's own ticker passes as it is, and its sale is checked by the repository, not here.
        assertTrue(InvestRules.tradeProblems(false, "legacy name", "legacy name", "1", "100", emptyList()).isEmpty())
    }

    @Test
    fun holdingSheetsValuesAndProblems() {
        assertEquals(listOf(InvestField.PRICE), InvestRules.holdingProblems("", "").map { it.field })
        assertEquals(listOf(InvestField.PAID), InvestRules.holdingProblems("10", "0").map { it.field })
        assertTrue(InvestRules.holdingProblems("10", "  ").isEmpty())
        val unknown = abc.copy(avgCost = null)
        val saved = InvestRules.updatedHolding(unknown, "180.00", "150.00", 77)
        assertEquals(15_000, saved.avgCost)
        // The price date moves only when the price does.
        assertEquals(10, InvestRules.updatedHolding(abc, "180.00", "", 77).priceMillis)
        assertEquals(77, InvestRules.updatedHolding(abc, "181.00", "", 77).priceMillis)
        val preview = InvestRules.preview(unknown, "200.00", "100.00")
        assertEquals(cost(12_500_000, 20_000), preview.value)
        assertEquals(preview.value - cost(12_500_000, 10_000), preview.profit)
    }

    @Test
    fun percentsRoundHalfUpFromWholeNumbers() {
        assertEquals("8.3%", InvestRules.percentOf(25_000, 300_000))
        assertEquals("12.5%", InvestRules.percentOf(25_000, 200_000))
        assertEquals("0.1%", InvestRules.percentOf(1, 1_000))
        assertEquals("0.0%", InvestRules.percentOf(1, 10_000))
        assertEquals("100.0%", InvestRules.percentOf(7, 7))
    }

    @Test
    fun anAccountsSummaryCountsWhatWasPutInAndNotTrades() {
        val account = Account(id = 6, name = "Brokerage", type = AccountType.INVESTMENT, openingBalance = 10_000)
        val history = listOf(
            Transaction(id = 1, kind = TxKind.TRANSFER, amount = 300_000, accountId = 2, toAccountId = 6, epochMillis = 1),
            Transaction(id = 2, kind = TxKind.BUY, amount = 200_000, accountId = 6, epochMillis = 2, note = "ABC"),
            Transaction(id = 3, kind = TxKind.TRANSFER, amount = 20_000, accountId = 6, toAccountId = 2, epochMillis = 3),
            Transaction(id = 4, kind = TxKind.INCOME, amount = 5_000, accountId = 6, categoryId = 1, epochMillis = 4),
        )
        val cash = 10_000 + 300_000 - 200_000 - 20_000 + 5_000L
        val s = InvestRules.summary(account, cash, listOf(abc), history)
        assertEquals(225_000, s.holdingsValue)
        assertEquals(cash + 225_000, s.value)
        assertEquals(10_000 + 300_000 - 20_000 + 5_000L, s.putIn)
        assertEquals(s.value - s.putIn, s.gain)
    }
}
