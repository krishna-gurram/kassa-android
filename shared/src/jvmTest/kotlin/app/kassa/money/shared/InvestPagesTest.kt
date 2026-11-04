package app.kassa.money.shared

import app.kassa.money.backup.BackupData
import app.kassa.money.backup.BackupHolding
import app.kassa.money.backup.BackupInvestmentTransfer
import app.kassa.money.backup.BackupJson
import app.kassa.money.backup.BackupTrade
import app.kassa.money.backup.BackupTransaction
import app.kassa.money.domain.Balances
import java.io.ByteArrayInputStream
import java.io.File
import java.math.BigInteger
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Investments on iOS (batch H) against the demo backup: Brokerage (account 6) got a 3,000.00 transfer on 2 Feb 2026, bought 12.5 shares
 * of ABC at 160.00 the next day (entry 332, trade 1) and the holding is priced at 180.00 now, so cash is 1,000.00 and the holding is worth
 * 2,250.00 (`expected.json`: balance 100,000, holdingsValue 225,000). Every figure is worked out again here with BigInteger. The store is
 * simulated by applying a plan's cells to the `BackupData`, the way `LedgerStore+Invest.swift` applies them to SQL.
 */
class InvestPagesTest {
    private val dir = "../app/src/test/resources/backup-demo/"
    private fun demo(): BackupData {
        val bytes = File(dir + "demo.kassa").readBytes()
        return BackupJson.parse(GZIPInputStream(ByteArrayInputStream(bytes.copyOfRange(6, bytes.size))).readBytes().decodeToString())
    }
    private fun num(json: String, key: String): Long = Regex("\"$key\": (-?\\d+)").find(json)!!.groupValues[1].toLong()
    private val expected get() = File(dir + "expected.json").readText()
    private val today get() = num(expected, "todayEpochDay").toInt()
    private fun view(data: BackupData = demo()): LedgerView { KassaCore.installDefaults(); return LedgerView(data, null) }
    private val mask = "••••"

    private fun usd(cents: Long): String {
        val a = abs(cents)
        val whole = (a / 100).toString().reversed().chunked(3).joinToString(",").reversed()
        return (if (cents < 0) "-" else "") + "$" + whole + "." + (a % 100).toString().padStart(2, '0')
    }

    private val broker = 6L
    /** A price entered on 2026-09-17: the oldest in the price-age test. */
    private val vtiPricedAt = 1_790_000_000_000L
    private val noon get() = today * 86_400_000L + 43_200_000L

    private fun halfUp(n: BigInteger, d: BigInteger): BigInteger = n.multiply(BigInteger.TWO).add(d).divide(d.multiply(BigInteger.TWO))
    private fun cost(micro: Long, price: Long): Long = halfUp(BigInteger.valueOf(micro).multiply(BigInteger.valueOf(price)), BigInteger.valueOf(1_000_000)).toLong()

    private fun page(data: BackupData, hidden: Boolean = false) = view(data).investPage(broker, hidden, today, "UTC")

    private fun balance(data: BackupData, id: Long): Long {
        val parts = LedgerParts.of(data)
        return Balances.of(parts.accounts, parts.transactions).getValue(id)
    }

    // ---------- applying plans the way the store does ----------

    private fun applyOps(data: BackupData, plan: InvestPlanData): BackupData {
        val columns = BackupRows.columns("holdings")
        var holdings = data.holdings
        for (op in plan.ops) {
            fun cell(name: String) = InvestBridge.holdingCell(op, columns.indexOf(name))
            holdings = when (op.kind) {
                "DELETE" -> holdings.filter { it.id != op.id }
                "UPDATE" -> holdings.map { if (it.id == op.id) row(op.id, ::cell) else it }
                else -> holdings + row((holdings.maxOfOrNull { it.id } ?: 0L) + 1, ::cell)
            }
        }
        return data.copy(holdings = holdings)
    }

    private fun row(id: Long, cell: (String) -> String?) = BackupHolding(
        id, cell("accountId")!!.toLong(), cell("ticker")!!, cell("microShares")!!.toLong(), cell("price")!!.toLong(),
        cell("priceMillis")!!.toLong(), cell("avgCost")?.toLong(),
    )

    /** A new buy or sale: holdings, the entry and its trade (and the waiting transfer it answers). */
    private fun commit(data: BackupData, plan: InvestPlanData, transferId: Long = 0L): BackupData {
        assertEquals("", plan.problem)
        val withHoldings = applyOps(data, plan)
        val id = (data.transactions.maxOfOrNull { it.id } ?: 0L) + 1
        val tx = BackupRows.columns("transactions")
        fun e(name: String) = InvestBridge.entryCell(plan, tx.indexOf(name))
        val entry = BackupTransaction(id, e("kind")!!, e("amount")!!.toLong(), e("accountId")?.toLong(), null, null, null, e("epochMillis")!!.toLong(), e("note")!!)
        val tr = BackupRows.columns("trades")
        fun t(name: String) = InvestBridge.tradeCell(plan, id, tr.indexOf(name))
        val trade = BackupTrade((data.trades.maxOfOrNull { it.id } ?: 0L) + 1, t("transactionId")!!.toLong(), t("ticker")!!, t("microShares")!!.toLong(), t("price")!!.toLong())
        return withHoldings.copy(
            transactions = withHoldings.transactions + entry, trades = withHoldings.trades + trade,
            investmentTransfers = withHoldings.investmentTransfers.filter { it.id != transferId },
        )
    }

    private fun buy(data: BackupData, ticker: String, shares: String, price: String) =
        commit(data, InvestBridge.planBuy(data, broker, ticker, shares, price, noon, "USD"))

    // ---------- the demo's figures ----------

    @Test
    fun theDemoHoldingIsInTheAccountsFigures() {
        val data = demo()
        // 12.5 shares at 180.00, worked out from the file's own numbers.
        val h = data.holdings.single()
        assertEquals(num(expected, "holdingsValue"), cost(h.microShares, h.price))
        assertEquals(100_000L, balance(data, broker))
        val a = view(data).accounts(false, today, "UTC")
        val row = a.groups.flatMap { it.rows }.first { it.name == "Brokerage" }
        assertEquals(usd(100_000 + 225_000), row.amountText)
        // Assets are the cash accounts, the holding and what Sam owes: expected.json's own figure.
        val cashAccounts = listOf(1L, 2L, 3L).sumOf { balance(data, it) }
        assertEquals(cashAccounts + 100_000 + 225_000 + num(expected, "samOwesYou"), num(expected, "assets"))
        assertEquals(usd(num(expected, "assets")), a.assetsText)
    }

    @Test
    fun theInvestmentPageShowsValueGainHoldingsAndCash() {
        val p = page(demo())
        assertTrue(p.found)
        assertEquals("Brokerage", p.name)
        // Value: 1,000.00 cash and 2,250.00 of ABC. Put in: the 3,000.00 transfer. Gain 250.00, 83.33 per mille rounds to 8.3%.
        assertEquals(usd(325_000), p.totalValueText)
        assertEquals("Ink", p.totalValueTone.name)
        assertEquals("UP", p.gainKind)
        assertEquals(usd(25_000), p.gainAmountText)
        val tenths = halfUp(BigInteger.valueOf(25_000L * 1000), BigInteger.valueOf(300_000)).toInt()
        assertEquals("${tenths / 10}.${tenths % 10}%", p.gainPercentText)
        assertEquals("8.3%", p.gainPercentText)
        assertEquals("Green", p.gainTone.name)
        assertTrue(p.firstBoxIsPutIn)
        assertEquals(usd(300_000), p.firstBoxText)
        assertEquals(usd(100_000), p.cashText)
        val h = p.holdings.single()
        assertEquals("ABC", h.ticker)
        assertEquals("12.5", h.sharesText)
        assertEquals(usd(16_000), h.avgCostText)
        assertEquals(usd(225_000), h.valueText)
        assertTrue(h.hasProfit)
        // Cost 12.5 x 160.00 = 2,000.00; profit 250.00 is 12.5% of it.
        assertEquals("+" + usd(25_000) + " · 12.5%", h.profitText)
        assertEquals("Green", h.profitTone.name)
        assertTrue(p.waiting.isEmpty())
        assertTrue(p.closeProblem.isNotEmpty())
        assertEquals(0, p.closedDay)
    }

    @Test
    fun theHistoryIsByMonthWithTheCashRightAfterEachEntry() {
        val p = page(demo())
        assertTrue(p.hasHistory)
        val month = p.months.single()
        assertEquals(2026, month.year)
        assertEquals(2, month.month)
        // Newest first: the buy (cash 1,000.00 after) and the transfer in (3,000.00 after).
        assertEquals(listOf("Buy", "Transfer"), month.entries.map { it.row.kind.name })
        assertEquals(listOf(usd(100_000), usd(300_000)), month.entries.map { it.cashAfterText })
        assertEquals(332L, month.entries[0].row.id)
        assertEquals("ABC", month.entries[0].row.note)
    }

    @Test
    fun hiddenMasksEveryFigureAndGivesNoDirectionOrPercent() {
        val p = page(demo(), hidden = true)
        assertEquals(mask, p.totalValueText)
        assertEquals("MASKED", p.gainKind)
        assertEquals("", p.gainPercentText)
        assertEquals(mask, p.gainAmountText)
        assertEquals(mask, p.firstBoxText)
        assertEquals(mask, p.cashText)
        val h = p.holdings.single()
        assertEquals(mask, h.avgCostText)
        assertEquals(mask, h.valueText)
        assertEquals(mask, h.profitText)
        for (tone in listOf(p.totalValueTone, p.gainTone, h.profitTone)) assertEquals("Ink", tone.name)
        assertEquals(listOf(mask, mask), p.months.single().entries.map { it.cashAfterText })
        // Sheets mask too: the holding's worth and profit, the trade preview.
        val s = view().holdingSheet(broker, 1, true)
        assertEquals(mask, s.worthText)
        assertEquals(mask, s.profitText)
        val v = view().tradePreview(broker, "1", "100.00", true)
        assertEquals(mask, v.totalText)
        assertEquals(mask, v.cashText)
    }

    @Test
    fun aLossIsOrangeAndAGainGreenWithTheRightSigns() {
        // Priced at 150.00 the 12.5 shares are worth 1,875.00, 125.00 under what they cost.
        val data = demo().copy(holdings = demo().holdings.map { it.copy(price = 15_000) })
        val h = page(data).holdings.single()
        assertEquals(usd(187_500), h.valueText)
        assertEquals("Orange", h.profitTone.name)
        // The minus is the real minus sign, and 12,500 of a 200,000 cost is 6.25%, which rounds half up to 6.3%.
        assertEquals("\u2212" + usd(12_500) + " \u00B7 6.3%", h.profitText)
        val p = page(data)
        assertEquals("DOWN", p.gainKind)
        assertEquals("Orange", p.gainTone.name)
        // Value 2,875.00 against 3,000.00 put in: down 125.00, 4.2%.
        assertEquals(usd(12_500), p.gainAmountText)
        assertEquals("4.2%", p.gainPercentText)
    }

    @Test
    fun anUnknownAverageCostHasNoProfit() {
        val data = demo().copy(holdings = demo().holdings.map { it.copy(avgCost = null) })
        val h = page(data).holdings.single()
        assertFalse(h.hasProfit)
        assertEquals("", h.avgCostText)
        assertTrue(view(data).holdingSheet(broker, 1, false).askPaid)
    }

    // ---------- buy and sell ----------

    @Test
    fun aBuyAndItsSaleRoundTrip() {
        val data = demo()
        // 2.5 shares at 200.00 = 500.00: cash 500.00, 15 shares priced 200.00 = 3,000.00, total 3,500.00.
        val bought = buy(data, "abc", "2.5", "200.00")
        assertEquals(100_000 - cost(2_500_000, 20_000), balance(bought, broker))
        val p1 = page(bought)
        assertEquals(usd(100_000 - 50_000 + cost(15_000_000, 20_000)), p1.totalValueText)
        assertEquals("15", p1.holdings.single().sharesText)
        // The average cost blends: (12.5 x 160.00 + 2.5 x 200.00) / 15 = 166.67.
        assertEquals(usd(16_667), p1.holdings.single().avgCostText)
        assertEquals(3, bought.transactions.count { it.accountId == broker || it.toAccountId == broker })
        // Sell all 15 at 210.00 = 3,150.00 to cash: the holding goes and the cash is 3,650.00.
        val sold = commit(bought, InvestBridge.planSell(bought, broker, "ABC", "15", "210.00", noon, "USD"))
        assertTrue(page(sold).holdings.isEmpty())
        assertEquals(50_000 + cost(15_000_000, 21_000), balance(sold, broker))
        assertEquals(usd(50_000 + 315_000), page(sold).totalValueText)
        // Cash and value are the same once nothing is held.
        assertEquals(page(sold).cashText, page(sold).totalValueText)
    }

    @Test
    fun aSaleOfMoreThanIsHeldIsRefusedAndWritesNothing() {
        val data = demo()
        val plan = InvestBridge.planSell(data, broker, "ABC", "12.500001", "100.00", noon, "USD")
        assertEquals("You hold 12.5 shares of ABC.", plan.problem)
        assertTrue(plan.ops.isEmpty())
        assertEquals("You don't hold NOPE here.", InvestBridge.planSell(data, broker, "nope", "1", "100.00", noon, "USD").problem)
        assertEquals("Enter how many shares.", InvestBridge.planBuy(data, broker, "ABC", "", "100.00", noon, "USD").problem)
        assertEquals("Price: Enter an amount.", InvestBridge.planBuy(data, broker, "ABC", "1", "", noon, "USD").problem)
    }

    @Test
    fun editingAndDeletingTheDemoBuy() {
        val data = demo()
        // The demo buy is 12.5 at 160.00 and the only source of the holding. Edit it to 10 at 150.00 = 1,500.00.
        val edit = InvestBridge.planEdit(data, 332, "ABC", "10", "150.00", noon, "USD")
        assertEquals("", edit.problem)
        assertEquals(150_000, edit.total)
        assertEquals(1L, edit.tradeRowId)
        assertEquals(listOf("DELETE", "INSERT"), edit.ops.map { it.kind })
        val edited = applyOps(data, edit)
        assertEquals(10_000_000, edited.holdings.single().microShares)
        assertEquals(15_000, edited.holdings.single().avgCost)
        // Delete the buy: the shares come off and nothing is held (the cash comes back with the entry).
        val gone = InvestBridge.planDelete(data, 332, "USD")
        assertEquals("", gone.problem)
        assertEquals(200_000, gone.total)
        assertEquals(1L, gone.tradeRowId)
        assertTrue(applyOps(data, gone).holdings.isEmpty())
        // Anything that isn't a buy or sale is not a trade.
        assertEquals("That trade isn't there any more.", InvestBridge.planDelete(data, 1, "USD").problem)
        assertFalse(InvestBridge.isTrade(data, 1))
        assertTrue(InvestBridge.isTrade(data, 332))
    }

    @Test
    fun aTradeFromBeforeSharesWereKeptOnlyChangesItsDate() {
        val data = demo().copy(trades = emptyList())
        val s = view(data).tradeEditSheet(332, false, "UTC")
        assertTrue(s.found)
        assertFalse(s.hasTrade)
        assertEquals("ABC", s.ticker)
        assertEquals(usd(200_000), s.amountText)
        assertEquals(
            "This trade's shares weren't recorded, so only its date can change.",
            InvestBridge.planEdit(data, 332, "ABC", "1", "100.00", noon, "USD").problem,
        )
        // Deleting it only returns its cash: no holding changes.
        val gone = InvestBridge.planDelete(data, 332, "USD")
        assertEquals("", gone.problem)
        assertTrue(gone.ops.isEmpty())
        assertEquals(0L, gone.tradeRowId)
    }

    @Test
    fun theTradeSheetOpensOnTheEntrysShareAndPrice() {
        val s = view().tradeEditSheet(332, false, "UTC")
        assertTrue(s.found)
        assertTrue(s.hasTrade)
        assertTrue(s.buy)
        assertEquals("ABC", s.ticker)
        assertEquals("12.5", s.sharesInput)
        assertEquals("160", s.priceInput)
        assertEquals(usd(200_000), s.amountText)
        assertEquals(332L, s.entryId)
        assertEquals(broker, s.accountId)
        assertFalse(view().tradeEditSheet(1, false, "UTC").found)
        val n = view().tradeSheet(broker, false)
        assertTrue(n.found)
        assertEquals(usd(100_000), n.cashText)
        assertEquals(listOf("ABC"), n.chips.map { it.ticker })
        assertEquals("180", n.chips.single().priceInput)
        assertFalse(view().tradeSheet(1, false).found)
    }

    @Test
    fun tradeProblemsAreTheSheetsPerField() {
        val v = view()
        assertEquals(listOf("TICKER", "SHARES", "PRICE"), v.tradeProblems(broker, true, "", "", "", "").map { it.field })
        val sell = v.tradeProblems(broker, false, "ABC", "", "13", "10.00")
        assertEquals(listOf("SHARES"), sell.map { it.field })
        assertEquals("You hold 12.5.", sell.single().message)
        assertEquals("Pick a holding to sell.", v.tradeProblems(broker, false, "XYZ", "", "1", "10.00").single().message)
        assertTrue(v.tradeProblems(broker, false, "ABC", "", "12.5", "10.00").isEmpty())
    }

    // ---------- holdings and prices ----------

    @Test
    fun savingAHoldingsPriceMovesItsDateOnlyWhenThePriceMoves() {
        val data = demo()
        val same = InvestBridge.planSaveHolding(data, broker, 1, "", "", "180.00", "", noon, "USD")
        assertEquals("", same.problem)
        assertEquals(1_793_707_200_000L, same.ops.single().priceMillis)
        val moved = InvestBridge.planSaveHolding(data, broker, 1, "", "", "185.50", "", noon, "USD")
        assertEquals(noon, moved.ops.single().priceMillis)
        assertEquals(18_550, moved.ops.single().price)
        assertEquals(12_500_000, moved.ops.single().microShares)
        assertEquals("PRICE", InvestBridge.planSaveHolding(data, broker, 1, "", "", "", "", noon, "USD").field)
    }

    @Test
    fun sharesYouAlreadyOwnAreAddedAtWhatYouPaid() {
        val data = demo()
        val plan = InvestBridge.planSaveHolding(data, broker, 0, " vti ", "3.25", "0", "210.00", noon, "USD")
        assertEquals("", plan.problem)
        val op = plan.ops.single()
        assertEquals("INSERT", op.kind)
        assertEquals("VTI", op.ticker)
        assertEquals(3_250_000, op.microShares)
        assertEquals(21_000, op.price)
        assertTrue(op.hasAvgCost)
        assertEquals(21_000, op.avgCost)
        val added = applyOps(data, plan)
        // No cash moves: the account's cash is unchanged and the value grows by 3.25 x 210.00 = 682.50.
        assertEquals(100_000, balance(added, broker))
        assertEquals(usd(325_000 + cost(3_250_000, 21_000)), page(added).totalValueText)
        assertEquals(listOf("TICKER", "SHARES", "PRICE"), view().addHoldingProblems("", "", "").map { it.field })
        assertEquals("TICKER", InvestBridge.planSaveHolding(data, broker, 0, "A B", "1", "", "10", noon, "USD").field)
    }

    @Test
    fun theHoldingSheetPreviewsThePriceTyped() {
        val v = view()
        val s = v.holdingSheet(broker, 1, false)
        assertTrue(s.found)
        assertFalse(s.isNew)
        assertEquals("ABC", s.ticker)
        assertEquals("12.5", s.sharesText)
        assertEquals("180", s.priceInput)
        assertEquals(usd(16_000), s.avgCostText)
        assertFalse(s.askPaid)
        // At 200.00: worth 2,500.00; profit 2,500.00 - 2,000.00 = 500.00, 25.0% of the cost.
        val p = v.holdingPreview(broker, 1, "200.00", "", false)
        assertEquals(usd(250_000), p.worthText)
        assertEquals("+" + usd(50_000) + " · 25.0%", p.profitText)
        // A price that isn't a number leaves the saved price in the figures.
        assertEquals(usd(225_000), v.holdingPreview(broker, 1, "", "", false).worthText)
        assertTrue(v.holdingSheet(broker, 0, false).isNew)
        assertFalse(v.holdingSheet(broker, 99, false).found)
    }

    @Test
    fun theWholeOfThePricesSheetSavesTogetherOrNotAtAll() {
        val data = demo().copy(holdings = demo().holdings + BackupHolding(2, broker, "VTI", 2_000_000, 25_000, vtiPricedAt, null))
        val v = view(data)
        val s = v.pricesSheet(broker)
        // By value: ABC 2,250.00 then VTI 500.00.
        assertEquals(listOf("ABC", "VTI"), s.rows.map { it.ticker })
        val ids = s.rows.map { it.id.toString() }
        assertEquals(listOf("2"), v.pricesProblems(broker, ids, listOf("181.00", "")).map { it.field })
        val bad = InvestBridge.planPrices(data, broker, ids, listOf("181.00", ""), noon, "USD")
        assertEquals("2", bad.field)
        assertTrue(bad.ops.isEmpty())
        val good = InvestBridge.planPrices(data, broker, ids, listOf("181.00", "260.00"), noon, "USD")
        assertEquals("", good.problem)
        val after = applyOps(data, good)
        assertEquals(listOf(18_100L, 26_000L), after.holdings.sortedBy { it.id }.map { it.price })
        assertEquals(listOf(noon, noon), after.holdings.sortedBy { it.id }.map { it.priceMillis })
        // The price age follows the oldest price.
        val old = page(data)
        assertEquals((vtiPricedAt / 86_400_000L).toInt(), old.oldestPriceDay)
        assertEquals(today - old.oldestPriceDay, old.priceDays)
    }

    // ---------- waiting transfers ----------

    private fun waiting(data: BackupData) = data.copy(investmentTransfers = listOf(BackupInvestmentTransfer(1, 331, broker, 300_000, 1_770_033_600_000)))

    @Test
    fun aWaitingTransferShowsOnThePageAndAnswersWithABuy() {
        val data = waiting(demo())
        val p = page(data)
        val w = p.waiting.single()
        assertEquals(1L, w.id)
        assertEquals(usd(300_000), w.amountText)
        assertEquals(java.time.LocalDate.of(2026, 2, 2).toEpochDay().toInt(), w.day)
        val sheet = view(data).allocateSheet(1, false, "UTC")
        assertTrue(sheet.found)
        assertEquals("Brokerage", sheet.accountName)
        assertEquals(usd(300_000), sheet.amountText)
        assertEquals(w.day, sheet.day)
        assertEquals(listOf("ABC"), sheet.chips.map { it.ticker })
        // 1 share at 200.00 of 3,000.00 moved: 200.00 invested, 2,800.00 stays as cash. 20 at 200.00 is too much.
        val v = view(data)
        val invested = v.allocatePreview(1, "1", "200.00", false)
        assertEquals("INVESTED", invested.kind)
        assertEquals(usd(20_000), invested.costText)
        assertEquals(usd(300_000 - 20_000), invested.leftText)
        assertEquals("TOO_MUCH", v.allocatePreview(1, "20", "200.00", false).kind)
        assertEquals("Orange", v.allocatePreview(1, "20", "200.00", false).tone.name)
        assertEquals("Ink", v.allocatePreview(1, "20", "200.00", true).tone.name)
        assertEquals("LEFT_OVER", v.allocatePreview(1, "", "", false).kind)
        // Buying answers it: the row goes with the buy.
        val answered = commit(data, InvestBridge.planBuy(data, broker, "ABC", "1", "200.00", noon, "USD"), transferId = 1)
        assertTrue(page(answered).waiting.isEmpty())
        assertFalse(view(data).allocateSheet(7, false, "UTC").found)
    }

    // ---------- Activity ----------

    @Test
    fun aBuyIsAnActivityRowOfKindBuyThatNoLongerOpensTheEntryPage() {
        val a = view().activity(2026, 2, false, today, "UTC")
        val rows = a.days.flatMap { it.entries }
        val buy = rows.first { it.id == 332L }
        assertEquals("Buy", buy.kind.name)
        assertFalse(buy.editable)
        assertTrue(buy.amountMuted)
    }
}
