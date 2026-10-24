package dev.krishnagurram.kassa.data.repo

import androidx.room.withTransaction
import dev.krishnagurram.kassa.data.db.KassaDatabase
import dev.krishnagurram.kassa.data.db.toDomain
import dev.krishnagurram.kassa.data.db.toEntity
import dev.krishnagurram.kassa.domain.Holding
import dev.krishnagurram.kassa.domain.InputRules
import dev.krishnagurram.kassa.domain.InvestmentTransfer
import dev.krishnagurram.kassa.domain.Shares
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.domain.TxKind
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * Holdings in investment accounts, valued at prices you enter (Kassa has no network), and transfers into those
 * accounts waiting for you to say what they bought.
 */
class InvestmentRepository(private val db: KassaDatabase, private val entries: EntryRepository) {

    val holdings: Flow<List<Holding>> = db.holdings().observeAll().map { rows -> rows.map { it.toDomain() } }

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

    /** Buys shares with cash in the account: a BUY entry, and the holding grows. Clears [transferId] when given. */
    suspend fun buy(accountId: Long, ticker: String, microShares: Long, price: Long, at: Long, transferId: Long?) = db.withTransaction {
        val t = checkedTicker(ticker)
        val cost = checkedTrade(microShares, price)
        entries.insertRaw(Transaction(kind = TxKind.BUY, amount = cost, accountId = accountId, epochMillis = at, note = t))
        val existing = db.holdings().find(accountId, t)?.toDomain()
        if (existing == null) db.holdings().insert(Holding(accountId = accountId, ticker = t, microShares = microShares, price = price, priceMillis = at, avgCost = price).toEntity())
        else db.holdings().update(existing.afterBuy(microShares, price, at).toEntity())
        transferId?.let { db.investmentTransfers().delete(it) }
    }

    /**
     * Sells shares: a SELL entry brings the cash into the account, and the holding shrinks (removed at zero).
     * The average cost of what's left doesn't change.
     */
    suspend fun sell(accountId: Long, ticker: String, microShares: Long, price: Long, at: Long) = db.withTransaction {
        val t = checkedTicker(ticker)
        val proceeds = checkedTrade(microShares, price)
        val h = db.holdings().find(accountId, t) ?: throw IllegalArgumentException("You don't hold $t here.")
        require(microShares <= h.microShares) { "You hold ${Shares.format(h.microShares)} shares of $t." }
        entries.insertRaw(Transaction(kind = TxKind.SELL, amount = proceeds, accountId = accountId, epochMillis = at, note = t))
        val left = h.microShares - microShares
        if (left == 0L) db.holdings().delete(h.id) else db.holdings().update(h.copy(microShares = left, price = price, priceMillis = at))
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
