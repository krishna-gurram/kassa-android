package app.kassa.money.domain

/*
 * Investing rules in plain Kotlin: what buying, selling, editing and deleting a trade does to the holdings, what a holding or a
 * trade as typed has to satisfy, and the figures of an investment account's page. Android's `InvestmentRepository` and `InvestSheets.kt`
 * hold the same rules next to the database and the screen; the iOS app asks them here (through `shared/.../InvestBridge.kt`) so its
 * Swift code only runs the SQL. The checks run in the order of the repository, with the sentences of the repository.
 */

/** Which input of a trade or holding a problem is about. */
enum class InvestField { TICKER, SHARES, PRICE, PAID }

/** What a plan does to one `holdings` row: INSERT [holding] (its id is 0), UPDATE the row [Holding.id], or DELETE it. */
enum class HoldingOpKind { INSERT, UPDATE, DELETE }

data class HoldingOp(val kind: HoldingOpKind, val holding: Holding)

/**
 * What a buy, a sale, an edit or a delete of a trade comes to: [problem] (null when it can be written), the cleaned [ticker], the
 * cash [total] the entry holds, and the changes of the `holdings` table in the order to write them.
 */
data class TradePlan(
    val problem: String?,
    val ticker: String,
    val total: Long,
    val ops: List<HoldingOp>,
)

/**
 * The holdings table in memory while a plan works on it: the same steps as the repository's `store` (one with no shares left goes, a
 * new one is added), found by account and ticker like its `find`, with the net change at the end as rows to insert, update or delete.
 */
internal class HoldingBook(initial: List<Holding>) {
    private val before: List<Holding> = initial.sortedBy { it.id }
    private val rows = LinkedHashMap<Long, Holding>().apply { before.forEach { put(it.id, it) } }
    /** Rows made by this plan have negative ids until they are written. */
    private var nextNew = -1L

    fun find(accountId: Long, ticker: String): Holding? =
        rows.values.firstOrNull { it.accountId == accountId && it.ticker == ticker }

    fun store(h: Holding) {
        when {
            h.microShares == 0L -> if (h.id != 0L) rows.remove(h.id)
            h.id == 0L -> { val id = nextNew--; rows[id] = h.copy(id = id) }
            else -> rows[h.id] = h
        }
    }

    fun ops(): List<HoldingOp> {
        val result = ArrayList<HoldingOp>()
        for (old in before) {
            val now = rows[old.id]
            if (now == null) result.add(HoldingOp(HoldingOpKind.DELETE, old))
            else if (now != old) result.add(HoldingOp(HoldingOpKind.UPDATE, now))
        }
        for ((id, h) in rows) if (id < 0) result.add(HoldingOp(HoldingOpKind.INSERT, h.copy(id = 0)))
        return result
    }
}

object InvestRules {

    /** The ticker as stored: trimmed and upper case. */
    fun cleanTicker(raw: String): String = raw.trim().uppercase()

    /** The ticker as stored, or [IllegalArgumentException] with the sentence `InputRules.tickerProblem` gives. */
    private fun checkedTicker(raw: String): String {
        InputRules.tickerProblem(raw)?.let { throw IllegalArgumentException(it) }
        return cleanTicker(raw)
    }

    /** The trade's cash amount, or [IllegalArgumentException] when the shares, price or total is out of range (`checkedTrade`). */
    private fun checkedTrade(microShares: Long, price: Long): Long {
        require(microShares > 0) { "Enter how many shares." }
        InputRules.amountProblem(price)?.let { throw IllegalArgumentException("Price: $it") }
        val total = Shares.cost(microShares, price)
        require(total > 0) { "That trade comes to less than one cent." }
        InputRules.amountProblem(total)?.let { throw IllegalArgumentException("Total: $it") }
        return total
    }

    private fun applyBuy(book: HoldingBook, accountId: Long, ticker: String, microShares: Long, price: Long, at: Long) {
        val existing = book.find(accountId, ticker)
        book.store(
            existing?.afterBuy(microShares, price, at)
                ?: Holding(accountId = accountId, ticker = ticker, microShares = microShares, price = price, priceMillis = at, avgCost = price),
        )
    }

    private fun applySale(book: HoldingBook, accountId: Long, ticker: String, microShares: Long, price: Long, at: Long) {
        val h = book.find(accountId, ticker) ?: throw IllegalArgumentException("You don't hold $ticker here.")
        book.store(h.afterSale(microShares, price, at))
    }

    /** Takes [trade] back off its holding, as if it never happened (`undo`). */
    private fun undo(book: HoldingBook, entry: Transaction, trade: Trade) {
        val accountId = entry.accountId ?: return
        val h = book.find(accountId, trade.ticker)
        when (entry.kind) {
            TxKind.BUY -> h?.let { book.store(it.withoutBuy(trade.microShares, trade.price)) }
            // A holding sold to zero was removed, so undoing that sale brings it back.
            TxKind.SELL -> book.store(
                h?.withoutSale(trade.microShares)
                    ?: Holding(accountId = accountId, ticker = trade.ticker, microShares = trade.microShares, price = trade.price, priceMillis = entry.epochMillis),
            )
            else -> {}
        }
    }

    private fun failed(e: IllegalArgumentException): TradePlan = TradePlan(e.message ?: "", "", 0, emptyList())

    /** Buying: the holding grows (or starts), the entry holds the cost. Mirrors `InvestmentRepository.buy` after `requireOpen`. */
    fun buy(holdings: List<Holding>, accountId: Long, ticker: String, microShares: Long, price: Long, at: Long): TradePlan = try {
        val t = checkedTicker(ticker)
        val cost = checkedTrade(microShares, price)
        val book = HoldingBook(holdings)
        applyBuy(book, accountId, t, microShares, price, at)
        TradePlan(null, t, cost, book.ops())
    } catch (e: IllegalArgumentException) {
        failed(e)
    }

    /** Selling: the holding shrinks (removed at zero) and its average cost stays. Mirrors `InvestmentRepository.sell` after `requireOpen`. */
    fun sell(holdings: List<Holding>, accountId: Long, ticker: String, microShares: Long, price: Long, at: Long): TradePlan = try {
        val t = checkedTicker(ticker)
        val proceeds = checkedTrade(microShares, price)
        val book = HoldingBook(holdings)
        applySale(book, accountId, t, microShares, price, at)
        TradePlan(null, t, proceeds, book.ops())
    } catch (e: IllegalArgumentException) {
        failed(e)
    }

    /**
     * Changing a buy or sale ([entry], with [old] its trade, null for one made before shares were kept): the old trade comes off the
     * holding and the new one goes on. Mirrors `InvestmentRepository.editTrade`; [TradePlan.total] and [TradePlan.ticker] are what the
     * entry now holds.
     */
    fun edit(holdings: List<Holding>, entry: Transaction, old: Trade?, ticker: String, microShares: Long, price: Long, at: Long): TradePlan = try {
        val trade = old ?: throw IllegalArgumentException("This trade's shares weren't recorded, so only its date can change.")
        val accountId = requireNotNull(entry.accountId) { "This trade has no account." }
        val t = if (ticker == trade.ticker) ticker else checkedTicker(ticker)
        val total = checkedTrade(microShares, price)
        val book = HoldingBook(holdings)
        undo(book, entry, trade)
        if (entry.kind == TxKind.BUY) applyBuy(book, accountId, t, microShares, price, at) else applySale(book, accountId, t, microShares, price, at)
        TradePlan(null, t, total, book.ops())
    } catch (e: IllegalArgumentException) {
        failed(e)
    }

    /** Deleting a buy or sale: a trade with recorded shares comes off its holding; an older one only returns its cash (`deleteTrade`). */
    fun delete(holdings: List<Holding>, entry: Transaction, trade: Trade?): TradePlan {
        val book = HoldingBook(holdings)
        trade?.let { undo(book, entry, it) }
        return TradePlan(null, trade?.ticker ?: entry.note, entry.amount, book.ops())
    }

    /**
     * Saving a holding as typed (a new price, shares or average cost, or a correction). A ticker saved before the ticker rules existed is
     * kept as it is until the user changes it. Mirrors `InvestmentRepository.save`; the one op is an INSERT for a new holding, else an UPDATE.
     */
    fun saveHolding(holdings: List<Holding>, h: Holding): TradePlan = try {
        val stored = if (h.id == 0L) null else holdings.firstOrNull { it.id == h.id }
        val ticker = if (stored != null && stored.ticker == h.ticker) h.ticker else checkedTicker(h.ticker)
        val clean = h.copy(ticker = ticker)
        require(clean.microShares >= 0) { "Shares can't be negative." }
        InputRules.amountProblem(clean.price, zeroAllowed = true)?.let { throw IllegalArgumentException(it) }
        clean.avgCost?.let { c -> InputRules.amountProblem(c)?.let { throw IllegalArgumentException("Average cost: $it") } }
        val kind = if (clean.id == 0L) HoldingOpKind.INSERT else HoldingOpKind.UPDATE
        TradePlan(null, ticker, clean.value, listOf(HoldingOp(kind, clean)))
    } catch (e: IllegalArgumentException) {
        failed(e)
    }

    // ---------- the sheets ----------

    /** One problem of a typed field. */
    data class FieldProblem(val field: InvestField, val message: String)

    /**
     * The problems of a trade as typed (`TradeInput.check`, then the sale checks of `TradeSheet`). [savedTicker] is the ticker an edited
     * entry already has (it passes as it is); for a sale [holdings] are what the account holds, and a sale of more than is held
     * says how much is. Empty when it can go to the repository.
     */
    fun tradeProblems(
        buy: Boolean, ticker: String, savedTicker: String?, shares: String, price: String, holdings: List<Holding>,
    ): List<FieldProblem> {
        val found = ArrayList<FieldProblem>()
        val micro = Shares.parse(shares)
        val cents = Money.parseCents(price)
        if (ticker != savedTicker) InputRules.tickerProblem(ticker)?.let { found.add(FieldProblem(InvestField.TICKER, it)) }
        Shares.problem(micro)?.let { found.add(FieldProblem(InvestField.SHARES, it)) }
        InputRules.amountProblem(cents)?.let { found.add(FieldProblem(InvestField.PRICE, it)) }
        if (found.isNotEmpty() || buy || savedTicker != null) return found
        val held = holdings.firstOrNull { it.ticker == cleanTicker(ticker) }
        if (held == null) found.add(FieldProblem(InvestField.TICKER, "Pick a holding to sell."))
        else if (micro!! > held.microShares) found.add(FieldProblem(InvestField.SHARES, "You hold ${Shares.format(held.microShares)}."))
        return found
    }

    /** The cash a trade as typed comes to, when shares and price are both typed. */
    fun tradeTotal(shares: String, price: String): Long? {
        val micro = Shares.parse(shares) ?: return null
        val cents = Money.parseCents(price) ?: return null
        return Shares.cost(micro, cents)
    }

    /** The problems of "Add shares you own" as typed: ticker, shares, and what was paid per share. */
    fun newHoldingProblems(ticker: String, shares: String, paid: String): List<FieldProblem> {
        val found = ArrayList<FieldProblem>()
        InputRules.tickerProblem(ticker)?.let { found.add(FieldProblem(InvestField.TICKER, it)) }
        Shares.problem(Shares.parse(shares))?.let { found.add(FieldProblem(InvestField.SHARES, it)) }
        InputRules.amountProblem(Money.parseCents(paid))?.let { found.add(FieldProblem(InvestField.PRICE, it)) }
        return found
    }

    /** The problems of an existing holding's sheet: today's price, and what was paid when it is typed ([paid] blank means not given). */
    fun holdingProblems(price: String, paid: String): List<FieldProblem> {
        val found = ArrayList<FieldProblem>()
        InputRules.amountProblem(Money.parseCents(price))?.let { found.add(FieldProblem(InvestField.PRICE, it)) }
        if (paid.isNotBlank()) InputRules.amountProblem(Money.parseCents(paid))?.let { found.add(FieldProblem(InvestField.PAID, it)) }
        return found
    }

    /**
     * What "Add shares you own" saves: no cash moves, the price starts at what was paid and so does the average cost. Call after
     * [newHoldingProblems] came back empty.
     */
    fun newHolding(accountId: Long, ticker: String, shares: String, paid: String, nowMillis: Long): Holding {
        val cents = Money.parseCents(paid) ?: 0L
        return Holding(
            accountId = accountId, ticker = cleanTicker(ticker), microShares = Shares.parse(shares) ?: 0L, price = cents,
            priceMillis = nowMillis, avgCost = cents,
        )
    }

    /**
     * What the sheet of a holding you have saves: the typed price (the price date only moves when the price does) and, while the
     * average cost is unknown, what was paid. Call after [holdingProblems] came back empty.
     */
    fun updatedHolding(h: Holding, price: String, paid: String, nowMillis: Long): Holding {
        val cents = Money.parseCents(price) ?: h.price
        val paidCents = if (paid.isBlank()) null else Money.parseCents(paid)
        return h.copy(
            price = cents,
            priceMillis = if (cents != h.price) nowMillis else h.priceMillis,
            avgCost = h.avgCost ?: paidCents,
        )
    }

    /** The holding as the sheet previews it: the typed price (kept while it isn't a number) and, while unknown, the average cost typed. */
    fun preview(h: Holding, price: String, paid: String): Holding =
        h.copy(price = Money.parseCents(price) ?: h.price, avgCost = h.avgCost ?: Money.parseCents(paid))

    /** The price sheet's save: every holding at its typed price, dated [nowMillis]. A price that isn't a number is left out of the result and named in [problems]. */
    fun repriced(holdings: List<Holding>, prices: List<String>, nowMillis: Long): Pair<List<Holding>, Map<Long, String>> {
        val problems = LinkedHashMap<Long, String>()
        val result = ArrayList<Holding>()
        holdings.forEachIndexed { i, h ->
            val typed = prices.getOrNull(i).orEmpty()
            val cents = Money.parseCents(typed)
            val problem = InputRules.amountProblem(cents)
            if (problem != null) problems[h.id] = problem else result.add(h.copy(price = cents!!, priceMillis = nowMillis))
        }
        return result to problems
    }

    // ---------- figures ----------

    /** [part] as a percent of [whole] with one decimal, like "17.2%" (half up, from whole numbers only). */
    fun percentOf(part: Long, whole: Long): String {
        val tenths = (BigInt.of(part) * BigInt.of(1000L)).divHalfUp(BigInt.of(whole)).toLongWrapped()
        return "${tenths / 10}.${tenths % 10}%"
    }

    /**
     * An investment account's totals: cash plus holdings is its value; what you put in is the starting amount, transfers in minus
     * transfers out and income paid in (trades move money inside the account and don't count); the gain is the value less that.
     */
    class Summary(val cash: Long, val holdingsValue: Long, val value: Long, val putIn: Long) {
        val gain: Long get() = value - putIn
    }

    fun summary(account: Account, cash: Long, holdings: List<Holding>, history: List<Transaction>): Summary {
        val holdingsValue = holdings.sumOf { it.value }
        val transfers = history.filter { it.kind == TxKind.TRANSFER }.sumOf {
            when (account.id) {
                it.toAccountId -> it.amount
                it.accountId -> -it.amount
                else -> 0L
            }
        }
        val income = history.filter { it.kind == TxKind.INCOME && it.accountId == account.id }.sumOf { it.amount }
        return Summary(cash, holdingsValue, cash + holdingsValue, account.openingBalance + transfers + income)
    }
}
