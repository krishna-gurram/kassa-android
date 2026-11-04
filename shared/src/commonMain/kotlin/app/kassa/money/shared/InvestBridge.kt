package app.kassa.money.shared

import app.kassa.money.backup.BackupData
import app.kassa.money.domain.AccountType
import app.kassa.money.domain.Holding
import app.kassa.money.domain.HoldingOp
import app.kassa.money.domain.HoldingOpKind
import app.kassa.money.domain.InvestRules
import app.kassa.money.domain.Money
import app.kassa.money.domain.Shares
import app.kassa.money.domain.Trade
import app.kassa.money.domain.TradePlan
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import app.kassa.money.domain.dateOfMillis
import app.kassa.money.domain.toEpochDay
import kotlinx.datetime.TimeZone
import kotlin.math.abs

/*
 * Investments on iOS (batch H): holdings, prices, buys and sales, and the waiting transfers. The rules are :domain's (`InvestRules`,
 * mirroring Android's `InvestmentRepository` and `InvestSheets.kt`); this file turns them into flat values for Swift (Strings, Ints, Longs,
 * 0 or "" for "none", kinds as names) and builds the figures the sheets show. `LedgerView.investPage` is the account page. Swift runs the SQL of a
 * plan inside one transaction (`LedgerStore+Invest.swift`) and never works a figure out.
 */

/** A change to one `holdings` row, flat: [kind] is INSERT (id 0), UPDATE or DELETE. [avgCost] counts only when [hasAvgCost]. */
class HoldingOpData(
    val kind: String,
    val id: Long,
    val accountId: Long,
    val ticker: String,
    val microShares: Long,
    val price: Long,
    val priceMillis: Long,
    val hasAvgCost: Boolean,
    val avgCost: Long,
)

/**
 * What a trade, a holding save or a price update comes to, as Swift writes it: [problem] ("" when it can be written; [field] is an
 * `InvestField` name or ""), the holdings [ops] in order, and for a trade its entry ([kind] BUY or SELL, [total] cash, [ticker]) and the
 * ids an edit or delete works on. [microShares], [price] and [epochMillis] are what the trade row and entry now hold.
 */
class InvestPlanData internal constructor(
    val problem: String,
    val field: String,
    val kind: String,
    val ticker: String,
    val total: Long,
    val accountId: Long,
    val entryId: Long,
    val tradeRowId: Long,
    val microShares: Long,
    val price: Long,
    val epochMillis: Long,
    val ops: List<HoldingOpData>,
)

/** One holding of an account's page or a trade sheet's chips. Empty text means "none". */
data class HoldingRowData(
    val id: Long,
    val ticker: String,
    val sharesText: String,
    /** "" while the average cost is not known. */
    val avgCostText: String,
    val valueText: String,
    /** False while the average cost is not known ("Add average cost"). */
    val hasProfit: Boolean,
    /** The profit as Money.format writes it with a plus, with " · 17.2%" after it unless hidden (`HoldingRow`). */
    val profitText: String,
    val profitTone: Tone,
)

/** Money moved in that is waiting for the person to say what it bought. */
data class WaitingTransferData(val id: Long, val amountText: String, val day: Int)

/** One entry of the investment page's history, with the cash the account held right after it. */
data class InvestEntryData(val row: EntryRowData, val cashAfterText: String)

/** The entries of one month (a calendar month), newest first. */
data class InvestMonthData(val year: Int, val month: Int, val entries: List<InvestEntryData>)

/**
 * An investment account's page: total value, the gain on what was put in, cash, the holdings, waiting transfers and the cash moves by month.
 * [gainKind] is NONE (nothing put in), MASKED (hidden: no size, no direction), UP or DOWN; [firstBoxIsPutIn] says whether the first
 * figure is "You put in" (else "Holdings"). While Hide balances is on, every tone is Ink and there are no percents.
 */
data class InvestPageData(
    val found: Boolean,
    val id: Long,
    val name: String,
    val last4: String,
    val closedDay: Int,
    val closeProblem: String,
    val totalValueText: String,
    val totalValueTone: Tone,
    val gainKind: String,
    val gainAmountText: String,
    val gainPercentText: String,
    val gainTone: Tone,
    val firstBoxIsPutIn: Boolean,
    val firstBoxText: String,
    val cashText: String,
    val holdings: List<HoldingRowData>,
    /** The oldest price among the holdings (epoch day, 0 without holdings) and how many days old it is; over 14 the page says "days old". */
    val oldestPriceDay: Int,
    val priceDays: Int,
    val waiting: List<WaitingTransferData>,
    val hasHistory: Boolean,
    val months: List<InvestMonthData>,
)

/** What a holding's sheet opens on. A new holding ([isNew]) is "shares you already own". */
data class HoldingSheetData(
    val found: Boolean,
    val isNew: Boolean,
    val accountId: Long,
    val id: Long,
    val ticker: String,
    val sharesText: String,
    /** "" while unknown. */
    val avgCostText: String,
    /** The price as it goes in a field. */
    val priceInput: String,
    /** The average cost is unknown, so the sheet asks once what was paid. */
    val askPaid: Boolean,
    val worthText: String,
    val hasProfit: Boolean,
    val profitText: String,
    val profitTone: Tone,
)

/** The figures of an existing holding's sheet for the price (and what was paid) typed so far. */
data class HoldingPreviewData(val worthText: String, val hasProfit: Boolean, val profitText: String, val profitTone: Tone)

data class PriceRowData(val id: Long, val ticker: String, val sharesText: String, val priceInput: String)

/** Every holding's price in one sheet. */
data class PricesSheetData(val found: Boolean, val accountId: Long, val rows: List<PriceRowData>)

/** A holding as a chip of a trade sheet: picking it fills the ticker and its last price. */
data class TradeChipData(val ticker: String, val priceInput: String, val sharesText: String)

/** What a new trade opens on: the cash, and the account's holdings (the first one starts picked, as on Android). */
data class TradeSheetData(
    val found: Boolean,
    val accountId: Long,
    val accountName: String,
    val cashText: String,
    val chips: List<TradeChipData>,
)

/** The line under a trade's fields: the cash it comes to and the cash the account holds. Problems are never masked. */
data class TradePreviewData(val totalText: String, val cashText: String)

/** An existing buy or sale. [hasTrade] is false for trades from before Kassa kept shares and price: only the date can change. */
data class TradeEditData(
    val found: Boolean,
    val entryId: Long,
    val accountId: Long,
    val buy: Boolean,
    val hasTrade: Boolean,
    val ticker: String,
    val sharesInput: String,
    val sharesText: String,
    val priceInput: String,
    val amountText: String,
    val day: Int,
    val epochMillis: Long,
)

/** What the "Where did it go?" sheet of a waiting transfer opens on. */
data class AllocateSheetData(
    val found: Boolean,
    val transferId: Long,
    val accountId: Long,
    val accountName: String,
    val amountText: String,
    /** The day the money moved, where the buy's date starts. */
    val day: Int,
    val chips: List<TradeChipData>,
)

/** [kind] is LEFT_OVER (nothing to buy yet), TOO_MUCH (costs more than moved) or INVESTED (cost and what stays as cash). */
data class AllocatePreviewData(val kind: String, val costText: String, val movedText: String, val leftText: String, val tone: Tone)

private fun opData(op: HoldingOp): HoldingOpData {
    val h = op.holding
    return HoldingOpData(op.kind.name, h.id, h.accountId, h.ticker, h.microShares, h.price, h.priceMillis, h.avgCost != null, h.avgCost ?: 0L)
}

private fun failedPlan(problem: String) = InvestPlanData(problem, "", "", "", 0, 0, 0, 0, 0, 0, 0, emptyList())

private fun planData(
    plan: TradePlan, kind: String, accountId: Long, entryId: Long, tradeRowId: Long, microShares: Long, price: Long, epochMillis: Long,
): InvestPlanData {
    val problem = plan.problem
    return if (problem != null) failedPlan(problem)
    else InvestPlanData("", "", kind, plan.ticker, plan.total, accountId, entryId, tradeRowId, microShares, price, epochMillis, plan.ops.map(::opData))
}

private fun tradeEntry(parts: LedgerParts, entryId: Long): Transaction? =
    parts.transactions.firstOrNull { it.id == entryId && (it.kind == TxKind.BUY || it.kind == TxKind.SELL) }

private val GONE = "That trade isn't there any more."

/** What Swift asks to write holdings, trades and prices (rules in `InvestRules`, in the order of `InvestmentRepository`). */
object InvestBridge {

    /** [text] typed into a shares field: digits and one decimal mark, at most nine whole digits and six decimals. */
    fun typedShares(raw: String): String = Shares.typed(raw)

    /** [raw] typed into a ticker field: capped, upper case, no spaces at the ends. */
    fun typedTicker(raw: String): String = app.kassa.money.domain.InputRules.typed(raw, app.kassa.money.domain.InputRules.TICKER_MAX).uppercase().trim()

    /** A buy of [shares] at [price] (both as typed), asked after the account was checked to be open (`EntryBridge.closedMessage` is its refusal). */
    fun planBuy(data: BackupData, accountId: Long, ticker: String, shares: String, price: String, epochMillis: Long, currency: String): InvestPlanData =
        withMoney(currency) {
            val parts = LedgerParts.of(data)
            val micro = Shares.parse(shares) ?: 0L
            val cents = Money.parseCents(price) ?: 0L
            val holdings = parts.holdings
            planData(InvestRules.buy(holdings, accountId, ticker, micro, cents, epochMillis), "BUY", accountId, 0, 0, micro, cents, epochMillis)
        }

    fun planSell(data: BackupData, accountId: Long, ticker: String, shares: String, price: String, epochMillis: Long, currency: String): InvestPlanData =
        withMoney(currency) {
            val parts = LedgerParts.of(data)
            val micro = Shares.parse(shares) ?: 0L
            val cents = Money.parseCents(price) ?: 0L
            planData(InvestRules.sell(parts.holdings, accountId, ticker, micro, cents, epochMillis), "SELL", accountId, 0, 0, micro, cents, epochMillis)
        }

    /** Changing buy or sale [entryId]: ticker, shares, price and moment. `InvestmentRepository.editTrade`. */
    fun planEdit(data: BackupData, entryId: Long, ticker: String, shares: String, price: String, epochMillis: Long, currency: String): InvestPlanData =
        withMoney(currency) {
            val parts = LedgerParts.of(data)
            val entry = tradeEntry(parts, entryId)
            if (entry == null) {
                failedPlan(GONE)
            } else {
                val old = parts.trades.firstOrNull { it.transactionId == entryId }
                val micro = Shares.parse(shares) ?: 0L
                val cents = Money.parseCents(price) ?: 0L
                val plan = InvestRules.edit(parts.holdings, entry, old, ticker, micro, cents, epochMillis)
                planData(plan, entry.kind.name, entry.accountId ?: 0L, entryId, old?.id ?: 0L, micro, cents, epochMillis)
            }
        }

    /** Deleting buy or sale [entryId]: the holdings it takes back and the cash it returns (`InvestmentRepository.deleteTrade`). */
    fun planDelete(data: BackupData, entryId: Long, currency: String): InvestPlanData =
        withMoney(currency) {
            val parts = LedgerParts.of(data)
            val entry = tradeEntry(parts, entryId)
            if (entry == null) {
                failedPlan(GONE)
            } else {
                val trade = parts.trades.firstOrNull { it.transactionId == entryId }
                planData(InvestRules.delete(parts.holdings, entry, trade), entry.kind.name, entry.accountId ?: 0L, entryId, trade?.id ?: 0L, trade?.microShares ?: 0L, trade?.price ?: 0L, entry.epochMillis)
            }
        }

    /** True when [entryId] is a buy or sale (the only entries the trade sheet opens). */
    fun isTrade(data: BackupData, entryId: Long): Boolean = data.transactions.any { it.id == entryId && (it.kind == "BUY" || it.kind == "SELL") }

    /** Saves a holding of [accountId] as the sheet typed it: [holdingId] 0 adds shares you own, else it is the holding changed. */
    fun planSaveHolding(
        data: BackupData, accountId: Long, holdingId: Long, ticker: String, shares: String, price: String, paid: String,
        nowMillis: Long, currency: String,
    ): InvestPlanData = withMoney(currency) {
        val parts = LedgerParts.of(data)
        val h: Holding?
        val checks: List<InvestRules.FieldProblem>
        if (holdingId == 0L) {
            checks = InvestRules.addHoldingProblems(ticker, shares, paid)
            h = if (checks.isEmpty()) InvestRules.newHolding(accountId, ticker, shares, paid, nowMillis) else null
        } else {
            val stored = parts.holdings.firstOrNull { it.id == holdingId }
            checks = if (stored == null) emptyList() else InvestRules.holdingProblems(price, paid)
            h = if (stored != null && checks.isEmpty()) InvestRules.updatedHolding(stored, price, paid, nowMillis) else null
        }
        val first = checks.firstOrNull()
        if (first != null) {
            InvestPlanData(first.message, first.field.name, "", "", 0, accountId, 0, 0, 0, 0, 0, emptyList())
        } else if (h == null) {
            failedPlan("That holding isn't there any more.")
        } else {
            planData(InvestRules.saveHolding(parts.holdings, h), "", accountId, 0, 0, h.microShares, h.price, nowMillis)
        }
    }

    /** Saves every holding of [accountId] listed in [ids] (decimal text) at the matching typed price in [prices], dated [nowMillis], as one plan. */
    fun planPrices(data: BackupData, accountId: Long, ids: List<String>, prices: List<String>, nowMillis: Long, currency: String): InvestPlanData =
        withMoney(currency) {
            val parts = LedgerParts.of(data)
            val wanted = ids.mapNotNull { it.toLongOrNull() }
            val held = wanted.mapNotNull { id -> parts.holdings.firstOrNull { it.id == id && it.accountId == accountId } }
            val typed = held.map { h -> prices.getOrNull(ids.indexOf(h.id.toString())).orEmpty() }
            val (repriced, problems) = InvestRules.repriced(held, typed, nowMillis)
            val first = problems.entries.firstOrNull()
            if (first != null) {
                InvestPlanData(first.value, first.key.toString(), "", "", 0, accountId, 0, 0, 0, 0, 0, emptyList())
            } else {
                val ops = ArrayList<HoldingOp>()
                var failure: String? = null
                for (h in repriced) {
                    val plan = InvestRules.saveHolding(parts.holdings, h)
                    if (plan.problem != null) { failure = plan.problem; break }
                    ops.addAll(plan.ops)
                }
                if (failure != null) failedPlan(failure) else planData(TradePlan(null, "", 0, ops), "", accountId, 0, 0, 0, 0, nowMillis)
            }
        }

    // ---------- the cells of the rows Swift writes (the columns of `BackupRows`) ----------

    private fun holdingColumns() = BackupRows.columns("holdings")

    /** One cell of a `holdings` row for [op]; null for the id and for an unknown average cost. */
    fun holdingCell(op: HoldingOpData, column: Int): String? = when (holdingColumns().getOrNull(column)) {
        "accountId" -> op.accountId.toString()
        "ticker" -> op.ticker
        "microShares" -> op.microShares.toString()
        "price" -> op.price.toString()
        "priceMillis" -> op.priceMillis.toString()
        "avgCost" -> if (op.hasAvgCost) op.avgCost.toString() else null
        else -> null
    }

    /** One cell of the BUY or SELL entry of [plan] (the `transactions` columns): the cash moves in the account, the note holds the ticker. */
    fun entryCell(plan: InvestPlanData, column: Int): String? = when (BackupRows.columns("transactions").getOrNull(column)) {
        "kind" -> plan.kind
        "amount" -> plan.total.toString()
        "accountId" -> plan.accountId.toString()
        "epochMillis" -> plan.epochMillis.toString()
        "note" -> plan.ticker
        else -> null
    }

    /** One cell of the `trades` row of [plan] for the entry [entryId] that was just written. */
    fun tradeCell(plan: InvestPlanData, entryId: Long, column: Int): String? = when (BackupRows.columns("trades").getOrNull(column)) {
        "transactionId" -> entryId.toString()
        "ticker" -> plan.ticker
        "microShares" -> plan.microShares.toString()
        "price" -> plan.price.toString()
        else -> null
    }
}

/** The sheets' own figures, read from the ledger as it stands. */
internal class InvestSheetRules(private val parts: LedgerParts, private val balances: Map<Long, Long>, private val zone: (String) -> TimeZone) {

    private fun account(id: Long) = parts.accounts.firstOrNull { it.id == id && it.type == AccountType.INVESTMENT }

    fun holdingsOf(accountId: Long): List<Holding> = parts.holdings.filter { it.accountId == accountId }.sortedByDescending { it.value }

    private fun profitTone(p: Long): Tone = if (Money.isHidden) Tone.Ink else if (p < 0) Tone.Orange else Tone.Green

    fun profitText(h: Holding): String {
        val p = h.profit ?: return ""
        val cost = h.cost ?: 0L
        val text = Money.format(p, showPlus = true)
        return if (cost > 0 && !Money.isHidden) text + " · " + InvestRules.percentOf(abs(p), cost) else text
    }

    fun row(h: Holding): HoldingRowData = HoldingRowData(
        id = h.id, ticker = h.ticker, sharesText = Shares.format(h.microShares), avgCostText = h.avgCost?.let { Money.format(it) } ?: "",
        valueText = Money.format(h.value), hasProfit = h.profit != null, profitText = profitText(h), profitTone = h.profit?.let(::profitTone) ?: Tone.Ink,
    )

    private fun preview(h: Holding) =
        HoldingPreviewData(Money.format(h.value), h.profit != null, profitText(h), h.profit?.let(::profitTone) ?: Tone.Ink)

    fun holdingSheet(accountId: Long, holdingId: Long): HoldingSheetData {
        val a = account(accountId)
        if (a == null) return HoldingSheetData(false, holdingId == 0L, accountId, holdingId, "", "", "", "", false, "", false, "", Tone.Ink)
        if (holdingId == 0L) return HoldingSheetData(true, true, accountId, 0, "", "", "", "", false, "", false, "", Tone.Ink)
        val h = parts.holdings.firstOrNull { it.id == holdingId && it.accountId == accountId }
            ?: return HoldingSheetData(false, false, accountId, holdingId, "", "", "", "", false, "", false, "", Tone.Ink)
        val p = preview(h)
        return HoldingSheetData(
            true, false, accountId, h.id, h.ticker, Shares.format(h.microShares), h.avgCost?.let { Money.format(it) } ?: "",
            Money.toInput(h.price), h.avgCost == null, p.worthText, p.hasProfit, p.profitText, p.profitTone,
        )
    }

    fun holdingPreview(accountId: Long, holdingId: Long, price: String, paid: String): HoldingPreviewData {
        val h = parts.holdings.firstOrNull { it.id == holdingId && it.accountId == accountId } ?: return HoldingPreviewData("", false, "", Tone.Ink)
        return preview(InvestRules.preview(h, price, paid))
    }

    fun pricesSheet(accountId: Long): PricesSheetData {
        if (account(accountId) == null) return PricesSheetData(false, accountId, emptyList())
        return PricesSheetData(true, accountId, holdingsOf(accountId).map { PriceRowData(it.id, it.ticker, Shares.format(it.microShares), Money.toInput(it.price)) })
    }

    private fun chips(accountId: Long): List<TradeChipData> =
        holdingsOf(accountId).map { TradeChipData(it.ticker, Money.toInput(it.price), Shares.format(it.microShares)) }

    fun tradeSheet(accountId: Long): TradeSheetData {
        val a = account(accountId) ?: return TradeSheetData(false, accountId, "", "", emptyList())
        return TradeSheetData(true, accountId, a.name, Money.format(balances[accountId] ?: 0L), chips(accountId))
    }

    fun tradePreview(accountId: Long, shares: String, price: String): TradePreviewData =
        TradePreviewData(Money.format(InvestRules.tradeTotal(shares, price) ?: 0L), Money.format(balances[accountId] ?: 0L))

    fun tradeProblems(accountId: Long, buy: Boolean, ticker: String, savedTicker: String, shares: String, price: String): List<FieldProblemData> =
        InvestRules.tradeProblems(buy, ticker, savedTicker.ifEmpty { null }, shares, price, holdingsOf(accountId)).map { FieldProblemData(it.field.name, it.message) }

    fun tradeEdit(entryId: Long, zoneId: String): TradeEditData {
        val none = TradeEditData(false, entryId, 0, false, false, "", "", "", "", "", 0, 0)
        val entry = tradeEntry(parts, entryId) ?: return none
        val trade: Trade? = parts.trades.firstOrNull { it.transactionId == entryId }
        return TradeEditData(
            found = true, entryId = entryId, accountId = entry.accountId ?: 0L, buy = entry.kind == TxKind.BUY, hasTrade = trade != null,
            ticker = trade?.ticker ?: entry.note, sharesInput = trade?.let { Shares.format(it.microShares) } ?: "",
            sharesText = trade?.let { Shares.format(it.microShares) } ?: "", priceInput = trade?.let { Money.toInput(it.price) } ?: "",
            amountText = Money.format(entry.amount), day = dateOfMillis(entry.epochMillis, zone(zoneId)).toEpochDay().toInt(),
            epochMillis = entry.epochMillis,
        )
    }

    fun allocateSheet(transferId: Long, zoneId: String): AllocateSheetData {
        val t = parts.transfers.firstOrNull { it.id == transferId } ?: return AllocateSheetData(false, transferId, 0, "", "", 0, emptyList())
        val a = account(t.accountId) ?: return AllocateSheetData(false, transferId, t.accountId, "", "", 0, emptyList())
        return AllocateSheetData(true, t.id, t.accountId, a.name, Money.format(t.amount), dateOfMillis(t.epochMillis, zone(zoneId)).toEpochDay().toInt(), chips(t.accountId))
    }

    fun allocatePreview(transferId: Long, shares: String, price: String): AllocatePreviewData {
        val t = parts.transfers.firstOrNull { it.id == transferId } ?: return AllocatePreviewData("LEFT_OVER", "", "", "", Tone.Ink)
        val cost = InvestRules.tradeTotal(shares, price)
        return when {
            cost == null -> AllocatePreviewData("LEFT_OVER", "", Money.format(t.amount), "", Tone.Ink)
            cost > t.amount -> AllocatePreviewData("TOO_MUCH", Money.format(cost), Money.format(t.amount), "", if (Money.isHidden) Tone.Ink else Tone.Orange)
            else -> AllocatePreviewData("INVESTED", Money.format(cost), Money.format(t.amount), Money.format(t.amount - cost), Tone.Ink)
        }
    }

    fun addHoldingProblems(ticker: String, shares: String, paid: String) =
        InvestRules.addHoldingProblems(ticker, shares, paid).map { FieldProblemData(it.field.name, it.message) }

    fun holdingProblems(price: String, paid: String) =
        InvestRules.holdingProblems(price, paid).map { FieldProblemData(it.field.name, it.message) }

    fun pricesProblems(accountId: Long, ids: List<String>, prices: List<String>): List<FieldProblemData> {
        val held = ids.mapNotNull { it.toLongOrNull() }.mapNotNull { id -> parts.holdings.firstOrNull { it.id == id && it.accountId == accountId } }
        val typed = held.map { h -> prices.getOrNull(ids.indexOf(h.id.toString())).orEmpty() }
        return InvestRules.repriced(held, typed, 0L).second.map { FieldProblemData(it.key.toString(), it.value) }
    }
}
