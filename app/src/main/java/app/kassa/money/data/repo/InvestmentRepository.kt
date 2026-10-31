package app.kassa.money.data.repo

import androidx.room.withTransaction
import app.kassa.money.data.db.KassaDatabase
import app.kassa.money.data.db.toDomain
import app.kassa.money.data.db.toEntity
import app.kassa.money.domain.Holding
import app.kassa.money.domain.InputRules
import app.kassa.money.domain.InvestmentTransfer
import app.kassa.money.domain.Shares
import app.kassa.money.domain.Trade
import app.kassa.money.domain.Transaction
import app.kassa.money.domain.TxKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Holdings in investment accounts, valued at prices you enter (Kassa has no network), and transfers into those
 * accounts waiting for you to say what they bought.
 */
class InvestmentRepository(private val db: KassaDatabase, private val entries: EntryRepository) {

    val holdings: Flow<List<Holding>> = db.holdings().observeAll().map { rows -> rows.map { it.toDomain() } }

    val trades: Flow<List<Trade>> = db.trades().observeAll().map { rows -> rows.map { it.toDomain() } }

    val transfers: Flow<List<InvestmentTransfer>> = db.investmentTransfers().observeAll().map { rows -> rows.map { it.toDomain() } }

    /** Remembers a transfer into an investment account so Overview can ask what it bought. */
    suspend fun awaitExplanation(transfer: Transaction) {
        val account = transfer.toAccountId ?: return
        db.investmentTransfers().insert(InvestmentTransfer(transactionId = transfer.id, accountId = account, amount = transfer.amount, epochMillis = transfer.epochMillis).toEntity())
    }

    /**
     * Stops waiting on a transfer. The money stays where it is: as cash in the account, or in holdings the user
     * already recorded. Nothing else changes.
     */
    suspend fun settle(transferId: Long) = db.investmentTransfers().delete(transferId)

    /** Buys shares with cash in the account: a BUY entry, its trade, and the holding grows. Clears [transferId] when given. */
    suspend fun buy(accountId: Long, ticker: String, microShares: Long, price: Long, at: Long, transferId: Long?) = db.withTransaction {
        db.requireOpen(accountId)
        val t = checkedTicker(ticker)
        val cost = checkedTrade(microShares, price)
        applyBuy(accountId, t, microShares, price, at)
        record(TxKind.BUY, accountId, t, microShares, price, cost, at)
        transferId?.let { db.investmentTransfers().delete(it) }
    }

    /**
     * Sells shares: a SELL entry brings the cash into the account, and the holding shrinks (removed at zero).
     * The average cost of what's left doesn't change.
     */
    suspend fun sell(accountId: Long, ticker: String, microShares: Long, price: Long, at: Long) = db.withTransaction {
        db.requireOpen(accountId)
        val t = checkedTicker(ticker)
        val proceeds = checkedTrade(microShares, price)
        applySale(accountId, t, microShares, price, at)
        record(TxKind.SELL, accountId, t, microShares, price, proceeds, at)
    }

    /** The shares and price behind a BUY or SELL entry; null for trades made before Kassa kept them. */
    suspend fun tradeFor(transactionId: Long): Trade? = db.trades().forTransaction(transactionId)?.toDomain()

    /**
     * Changes a buy or sale: the old trade comes off the holding, the new one goes on, and the entry's cash and date
     * follow. Throws [IllegalArgumentException] for a trade without recorded shares (see [moveTrade]).
     */
    suspend fun editTrade(entry: Transaction, ticker: String, microShares: Long, price: Long, at: Long) = db.withTransaction {
        val old = tradeFor(entry.id) ?: throw IllegalArgumentException("This trade's shares weren't recorded, so only its date can change.")
        val accountId = requireNotNull(entry.accountId) { "This trade has no account." }
        val t = if (ticker == old.ticker) ticker else checkedTicker(ticker)
        val total = checkedTrade(microShares, price)
        undo(entry, old)
        if (entry.kind == TxKind.BUY) applyBuy(accountId, t, microShares, price, at) else applySale(accountId, t, microShares, price, at)
        db.transactions().update(entry.copy(amount = total, epochMillis = at, note = t).toEntity())
        db.trades().update(old.copy(ticker = t, microShares = microShares, price = price).toEntity())
    }

    /** Moves a trade to another date, nothing else. The only change for trades made before shares were kept. */
    suspend fun moveTrade(entry: Transaction, at: Long) = db.transactions().update(entry.copy(epochMillis = at).toEntity())

    /** Deletes a buy or sale. A trade with recorded shares is taken off its holding; an older one only returns its cash. */
    suspend fun deleteTrade(entry: Transaction) = db.withTransaction {
        tradeFor(entry.id)?.let { undo(entry, it) }
        db.trades().deleteForTransaction(entry.id)
        entries.delete(entry.id)
    }

    private suspend fun record(kind: TxKind, accountId: Long, ticker: String, microShares: Long, price: Long, total: Long, at: Long) {
        val id = entries.insertRaw(Transaction(kind = kind, amount = total, accountId = accountId, epochMillis = at, note = ticker))
        db.trades().insert(Trade(transactionId = id, ticker = ticker, microShares = microShares, price = price).toEntity())
    }

    private suspend fun applyBuy(accountId: Long, ticker: String, microShares: Long, price: Long, at: Long) {
        val existing = db.holdings().find(accountId, ticker)?.toDomain()
        store(existing?.afterBuy(microShares, price, at)
            ?: Holding(accountId = accountId, ticker = ticker, microShares = microShares, price = price, priceMillis = at, avgCost = price))
    }

    private suspend fun applySale(accountId: Long, ticker: String, microShares: Long, price: Long, at: Long) {
        val h = db.holdings().find(accountId, ticker)?.toDomain() ?: throw IllegalArgumentException("You don't hold $ticker here.")
        store(h.afterSale(microShares, price, at))
    }

    /** Takes [trade] back off its holding, as if it never happened. */
    private suspend fun undo(entry: Transaction, trade: Trade) {
        val accountId = entry.accountId ?: return
        val h = db.holdings().find(accountId, trade.ticker)?.toDomain()
        when (entry.kind) {
            TxKind.BUY -> h?.let { store(it.withoutBuy(trade.microShares, trade.price)) }
            // A holding sold to zero was removed, so undoing that sale brings it back.
            TxKind.SELL -> store(h?.withoutSale(trade.microShares)
                ?: Holding(accountId = accountId, ticker = trade.ticker, microShares = trade.microShares, price = trade.price, priceMillis = entry.epochMillis))
            else -> {}
        }
    }

    /** Saves a holding; one with no shares left is removed. */
    private suspend fun store(h: Holding) = when {
        h.microShares == 0L -> { if (h.id != 0L) db.holdings().delete(h.id) else Unit }
        h.id == 0L -> { db.holdings().insert(h.toEntity()); Unit }
        else -> db.holdings().update(h.toEntity())
    }

    /**
     * Saves a holding as typed (a new price, shares or average cost, or a correction). A ticker saved before the
     * ticker rules existed is kept as it is until the user changes it.
     */
    suspend fun save(h: Holding) {
        val stored = if (h.id == 0L) null else db.holdings().byId(h.id)
        val ticker = if (stored != null && stored.ticker == h.ticker) h.ticker else checkedTicker(h.ticker)
        val clean = h.copy(ticker = ticker)
        require(clean.microShares >= 0) { "Shares can't be negative." }
        InputRules.amountProblem(clean.price, zeroAllowed = true)?.let { throw IllegalArgumentException(it) }
        clean.avgCost?.let { c -> InputRules.amountProblem(c)?.let { throw IllegalArgumentException("Average cost: $it") } }
        if (clean.id == 0L) db.holdings().insert(clean.toEntity()) else db.holdings().update(clean.toEntity())
    }

    suspend fun delete(holdingId: Long) = db.holdings().delete(holdingId)

    /** The trade's cash amount, or [IllegalArgumentException] when the shares, price or total is out of range. */
    private fun checkedTrade(microShares: Long, price: Long): Long {
        require(microShares > 0) { "Enter how many shares." }
        InputRules.amountProblem(price)?.let { throw IllegalArgumentException("Price: $it") }
        val total = Shares.cost(microShares, price)
        require(total > 0) { "That trade comes to less than one cent." }
        InputRules.amountProblem(total)?.let { throw IllegalArgumentException("Total: $it") }
        return total
    }

    private fun checkedTicker(raw: String): String {
        InputRules.tickerProblem(raw)?.let { throw IllegalArgumentException(it) }
        return raw.trim().uppercase()
    }
}
