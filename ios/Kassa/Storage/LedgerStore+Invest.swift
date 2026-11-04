import Foundation
import KassaShared

/// Investments, batch H: holdings, prices, buys and sales and the waiting transfers. Every rule and every figure is :domain's
/// (`InvestRules` through `InvestBridge`, which mirror Android's `InvestmentRepository` in the order of its checks); this file only runs
/// the SQL, each change in ONE transaction so a refusal or a failure leaves nothing behind (holdings, entry and trade together).
/// `afterInsertHook` runs after the last write of a change so a test can prove the rollback.
extension LedgerStore {

    // MARK: Small statements

    /// Runs [sql] with the integers [values] bound in order.
    private func execute(_ sql: String, _ values: [Int64]) throws {
        let statement = try db.prepare(sql)
        for (slot, value) in values.enumerated() {
            try statement.bindInt(Int32(slot + 1), value)
        }
        _ = try statement.step()
    }

    /// Rewrites every column but `id` of row [id] of [table] from [cell] (by the column's position in `BackupRows`).
    func updateCells(table: String, id: Int64, cell: (Int32) -> String?) throws {
        let rows = BackupRows.shared
        let columns = rows.columns(table: table)
        let kinds = rows.columnKinds(table: table)
        var sets: [String] = []
        var positions: [Int] = []
        for (index, name) in columns.enumerated() where name != "id" {
            sets.append("\"\(name)\" = ?")
            positions.append(index)
        }
        let statement = try db.prepare("UPDATE \(table) SET \(sets.joined(separator: ", ")) WHERE id = ?")
        for (slot, index) in positions.enumerated() {
            let value = cell(Int32(index))
            try LedgerStore.bind(statement, Int32(slot + 1), kind: kinds[index], value: value)
        }
        try statement.bindInt(Int32(positions.count + 1), id)
        _ = try statement.step()
    }

    /// Refuses a trade or holding on an account that is gone or is not an investment account.
    private func requireInvestment(_ accountId: Int64) throws {
        let kind = try accountType(accountId)
        if kind != "INVESTMENT" {
            throw WriteRefusal(message: String(localized: "write.accountGone"))
        }
    }

    private func refuse(_ plan: InvestPlanData) throws {
        if !plan.problem.isEmpty { throw WriteRefusal(field: plan.field, message: plan.problem) }
    }

    /// Writes the holdings changes of a plan in order. Returns the id of the last row inserted (0 when none was).
    @discardableResult
    private func applyHoldingOps(_ ops: [HoldingOpData]) throws -> Int64 {
        var inserted: Int64 = 0
        for op in ops {
            if op.kind == "DELETE" {
                try execute("DELETE FROM holdings WHERE id = ?", [op.id])
            } else if op.kind == "UPDATE" {
                try updateCells(table: "holdings", id: op.id) { column in
                    return InvestBridge.shared.holdingCell(op: op, column: column)
                }
            } else {
                inserted = try insertRow(table: "holdings") { column in
                    return InvestBridge.shared.holdingCell(op: op, column: column)
                }
            }
        }
        return inserted
    }

    // MARK: Buy and sell

    /// Buys [shares] of [ticker] at [price] (both as typed) in investment account [accountId]: the holding grows, a BUY entry and its
    /// trade row are written, and the waiting transfer [transferId] (0 for none) is answered; all or nothing. A closed account is refused
    /// first, as Android's `requireOpen`. Returns the entry's id.
    @discardableResult
    func buyTrade(accountId: Int64, ticker: String, shares: String, price: String, epochMillis: Int64, transferId: Int64) throws -> Int64 {
        return try db.transaction { () throws -> Int64 in
            try self.requireInvestment(accountId)
            try self.requireOpen([String(accountId)])
            let currency = try self.currencyCode() ?? ""
            let data = try self.readAll()
            let plan: InvestPlanData = InvestBridge.shared.planBuy(
                data: data, accountId: accountId, ticker: ticker, shares: shares, price: price, epochMillis: epochMillis, currency: currency
            )
            return try self.writeNewTrade(plan, transferId: transferId)
        }
    }

    /// Sells [shares] of [ticker] at [price]: the holding shrinks (gone at zero), a SELL entry brings the cash in, all or nothing.
    @discardableResult
    func sellTrade(accountId: Int64, ticker: String, shares: String, price: String, epochMillis: Int64) throws -> Int64 {
        return try db.transaction { () throws -> Int64 in
            try self.requireInvestment(accountId)
            try self.requireOpen([String(accountId)])
            let currency = try self.currencyCode() ?? ""
            let data = try self.readAll()
            let plan: InvestPlanData = InvestBridge.shared.planSell(
                data: data, accountId: accountId, ticker: ticker, shares: shares, price: price, epochMillis: epochMillis, currency: currency
            )
            return try self.writeNewTrade(plan, transferId: 0)
        }
    }

    private func writeNewTrade(_ plan: InvestPlanData, transferId: Int64) throws -> Int64 {
        try refuse(plan)
        try applyHoldingOps(plan.ops)
        let entryId = try insertRow(table: "transactions") { column in
            return InvestBridge.shared.entryCell(plan: plan, column: column)
        }
        _ = try insertRow(table: "trades") { column in
            return InvestBridge.shared.tradeCell(plan: plan, entryId: entryId, column: column)
        }
        try afterInsertHook?()
        if transferId > 0 {
            try execute("DELETE FROM investment_transfers WHERE id = ?", [transferId])
        }
        return entryId
    }

    /// Changes buy or sale [entryId]: the old trade comes off its holding, the new one goes on, and the entry's cash, note and moment
    /// and the trade row follow. A trade from before Kassa kept shares is refused (only its date can change: `moveTrade`).
    func editTrade(entryId: Int64, ticker: String, shares: String, price: String, epochMillis: Int64) throws {
        try db.transaction { () throws -> Void in
            let currency = try self.currencyCode() ?? ""
            let data = try self.readAll()
            let plan: InvestPlanData = InvestBridge.shared.planEdit(
                data: data, entryId: entryId, ticker: ticker, shares: shares, price: price, epochMillis: epochMillis, currency: currency
            )
            try self.refuse(plan)
            try self.applyHoldingOps(plan.ops)
            let entry = try self.db.prepare("UPDATE transactions SET amount = ?, epochMillis = ?, note = ? WHERE id = ?")
            try entry.bindInt(1, plan.total)
            try entry.bindInt(2, plan.epochMillis)
            try entry.bindText(3, plan.ticker)
            try entry.bindInt(4, entryId)
            _ = try entry.step()
            let trade = try self.db.prepare("UPDATE trades SET ticker = ?, microShares = ?, price = ? WHERE id = ?")
            try trade.bindText(1, plan.ticker)
            try trade.bindInt(2, plan.microShares)
            try trade.bindInt(3, plan.price)
            try trade.bindInt(4, plan.tradeRowId)
            _ = try trade.step()
            try self.afterInsertHook?()
        }
    }

    /// Moves a buy or sale to another moment, nothing else: the only change for trades made before shares were kept.
    func moveTrade(entryId: Int64, epochMillis: Int64) throws {
        try db.transaction { () throws -> Void in
            if !(try self.exists("SELECT 1 FROM transactions WHERE id = ? AND kind IN ('BUY', 'SELL')", entryId)) {
                throw WriteRefusal(message: String(localized: "write.entryGone"))
            }
            try self.execute("UPDATE transactions SET epochMillis = ? WHERE id = ?", [epochMillis, entryId])
            try self.afterInsertHook?()
        }
    }

    /// Deletes a buy or sale: a trade with recorded shares comes off its holding (an older one only returns its cash), the trade row, any
    /// split line and waiting row and the entry go.
    func deleteTrade(entryId: Int64) throws {
        try db.transaction { () throws -> Void in
            let currency = try self.currencyCode() ?? ""
            let data = try self.readAll()
            let plan: InvestPlanData = InvestBridge.shared.planDelete(data: data, entryId: entryId, currency: currency)
            try self.refuse(plan)
            try self.applyHoldingOps(plan.ops)
            try self.execute("DELETE FROM trades WHERE transactionId = ?", [entryId])
            try self.execute("DELETE FROM loan_entries WHERE txId = ?", [entryId])
            try self.execute("DELETE FROM investment_transfers WHERE transactionId = ?", [entryId])
            try self.execute("DELETE FROM transactions WHERE id = ?", [entryId])
            try self.afterInsertHook?()
        }
    }

    // MARK: Holdings and prices

    /// Saves a holding as the sheet typed it: [holdingId] 0 adds shares you already own (no cash moves), else it changes that holding's price
    /// (and what was paid while unknown). Returns the holding's id (0 for a new one: the database numbers it).
    @discardableResult
    func saveHolding(
        accountId: Int64, holdingId: Int64, ticker: String, shares: String, price: String, paid: String, nowMillis: Int64
    ) throws -> Int64 {
        return try db.transaction { () throws -> Int64 in
            try self.requireInvestment(accountId)
            let currency = try self.currencyCode() ?? ""
            let data = try self.readAll()
            let plan: InvestPlanData = InvestBridge.shared.planSaveHolding(
                data: data, accountId: accountId, holdingId: holdingId, ticker: ticker, shares: shares, price: price, paid: paid,
                nowMillis: nowMillis, currency: currency
            )
            try self.refuse(plan)
            let inserted = try self.applyHoldingOps(plan.ops)
            try self.afterInsertHook?()
            return holdingId > 0 ? holdingId : inserted
        }
    }

    /// Saves every price of the Update prices sheet at once ([ids] are holding ids as text, [prices] what was typed for each, same order);
    /// one bad price saves none.
    func saveHoldingPrices(accountId: Int64, ids: [String], prices: [String], nowMillis: Int64) throws {
        try db.transaction { () throws -> Void in
            try self.requireInvestment(accountId)
            let currency = try self.currencyCode() ?? ""
            let data = try self.readAll()
            let plan: InvestPlanData = InvestBridge.shared.planPrices(
                data: data, accountId: accountId, ids: ids, prices: prices, nowMillis: nowMillis, currency: currency
            )
            try self.refuse(plan)
            try self.applyHoldingOps(plan.ops)
            try self.afterInsertHook?()
        }
    }

    /// Removes a holding. No cash moves and trades stay as they are (Android's `delete`).
    func deleteHolding(id: Int64) throws {
        try db.transaction { () throws -> Void in
            try self.execute("DELETE FROM holdings WHERE id = ?", [id])
            try self.afterInsertHook?()
        }
    }

    // MARK: Waiting transfers

    /// Stops waiting on a transfer into investments ("It's uninvested cash", "Already recorded", Clear): the money stays where it is and
    /// nothing else changes (`InvestmentRepository.settle`).
    func settleTransfer(id: Int64) throws {
        try db.transaction { () throws -> Void in
            try self.execute("DELETE FROM investment_transfers WHERE id = ?", [id])
        }
    }
}
