import Foundation
import KassaShared

/// The holding sheet that is open (`LedgerView.holdingSheet`): a holding you have, or "Add shares you own" for a new one.
struct HoldingSheetRequest: Identifiable {
    let id = UUID()
    let data: HoldingSheetData
}

/// The Update prices sheet that is open (`LedgerView.pricesSheet`).
struct PricesSheetRequest: Identifiable {
    let id = UUID()
    let data: PricesSheetData
}

/// The Buy or sell sheet that is open (`LedgerView.tradeSheet`).
struct TradeSheetRequest: Identifiable {
    let id = UUID()
    let data: TradeSheetData
}

/// An existing buy or sale on its sheet (`LedgerView.tradeEditSheet`).
struct TradeEditRequest: Identifiable {
    let id = UUID()
    let data: TradeEditData
}

/// The "Where did it go?" sheet of a waiting transfer (`LedgerView.allocateSheet`).
struct AllocateRequest: Identifiable {
    let id = UUID()
    let data: AllocateSheetData
}

/// Investments, batch H: the sheets of an investment account's page, their live checks and their writes. Every rule is :domain's, asked through
/// `LedgerView` and `InvestBridge` on the one queue; the writes are `LedgerStore+Invest.swift`, one transaction each.
extension AppModel {

    private func nowMillis() -> Int64 {
        return Int64(Date().timeIntervalSince1970 * 1000)
    }

    // MARK: Opening

    /// Opens a holding's sheet ([holdingId] 0 is "Add shares you own"). Does nothing when the account or holding is gone.
    func openHolding(accountId: Int64, holdingId: Int64) {
        let hidden = hideAmounts
        queue.async {
            guard let made = self.view else { return }
            let data: HoldingSheetData = made.holdingSheet(accountId: accountId, holdingId: holdingId, hidden: hidden)
            if !data.found { return }
            self.publish { self.holdingSheet = HoldingSheetRequest(data: data) }
        }
    }

    func closeHoldingSheet() {
        holdingSheet = nil
    }

    func openPrices(accountId: Int64) {
        queue.async {
            guard let made = self.view else { return }
            let data: PricesSheetData = made.pricesSheet(accountId: accountId)
            if !data.found || data.rows.isEmpty { return }
            self.publish { self.pricesSheet = PricesSheetRequest(data: data) }
        }
    }

    func closePricesSheet() {
        pricesSheet = nil
    }

    func openTrade(accountId: Int64) {
        let hidden = hideAmounts
        queue.async {
            guard let made = self.view else { return }
            let data: TradeSheetData = made.tradeSheet(accountId: accountId, hidden: hidden)
            if !data.found { return }
            self.publish { self.tradeSheet = TradeSheetRequest(data: data) }
        }
    }

    func closeTradeSheet() {
        tradeSheet = nil
    }

    func closeTradeEditSheet() {
        tradeEditSheet = nil
    }

    func openAllocate(transferId: Int64) {
        let hidden = hideAmounts
        let zone = TimeZone.current.identifier
        queue.async {
            guard let made = self.view else { return }
            let data: AllocateSheetData = made.allocateSheet(transferId: transferId, hidden: hidden, timeZoneId: zone)
            if !data.found { return }
            self.publish { self.allocateSheet = AllocateRequest(data: data) }
        }
    }

    func closeAllocateSheet() {
        allocateSheet = nil
    }

    // MARK: Live checks

    /// The problems of a trade as typed, each with its field (an `InvestField` name); empty when it can be saved. [savedTicker] is an
    /// edited entry's own ticker ("" for a new trade).
    func tradeProblems(
        accountId: Int64, buy: Bool, ticker: String, savedTicker: String, shares: String, price: String,
        done: @escaping ([FieldProblemData]) -> Void
    ) {
        queue.async {
            var found: [FieldProblemData] = []
            if let made = self.view {
                found = made.tradeProblems(accountId: accountId, buy: buy, ticker: ticker, savedTicker: savedTicker, shares: shares, price: price)
            }
            self.publish { done(found) }
        }
    }

    func addHoldingProblems(ticker: String, shares: String, paid: String, done: @escaping ([FieldProblemData]) -> Void) {
        queue.async {
            var found: [FieldProblemData] = []
            if let made = self.view {
                found = made.addHoldingProblems(ticker: ticker, shares: shares, paid: paid)
            }
            self.publish { done(found) }
        }
    }

    func holdingProblems(price: String, paid: String, done: @escaping ([FieldProblemData]) -> Void) {
        queue.async {
            var found: [FieldProblemData] = []
            if let made = self.view {
                found = made.holdingProblems(price: price, paid: paid)
            }
            self.publish { done(found) }
        }
    }

    func pricesProblems(accountId: Int64, ids: [String], prices: [String], done: @escaping ([FieldProblemData]) -> Void) {
        queue.async {
            var found: [FieldProblemData] = []
            if let made = self.view {
                found = made.pricesProblems(accountId: accountId, ids: ids, prices: prices)
            }
            self.publish { done(found) }
        }
    }

    func tradePreview(accountId: Int64, shares: String, price: String, done: @escaping (TradePreviewData) -> Void) {
        let hidden = hideAmounts
        queue.async {
            guard let made = self.view else { return }
            let preview: TradePreviewData = made.tradePreview(accountId: accountId, shares: shares, price: price, hidden: hidden)
            self.publish { done(preview) }
        }
    }

    func holdingPreview(accountId: Int64, holdingId: Int64, price: String, paid: String, done: @escaping (HoldingPreviewData) -> Void) {
        let hidden = hideAmounts
        queue.async {
            guard let made = self.view else { return }
            let preview: HoldingPreviewData = made.holdingPreview(
                accountId: accountId, holdingId: holdingId, price: price, paid: paid, hidden: hidden
            )
            self.publish { done(preview) }
        }
    }

    func allocatePreview(transferId: Int64, shares: String, price: String, done: @escaping (AllocatePreviewData) -> Void) {
        let hidden = hideAmounts
        queue.async {
            guard let made = self.view else { return }
            let preview: AllocatePreviewData = made.allocatePreview(transferId: transferId, shares: shares, price: price, hidden: hidden)
            self.publish { done(preview) }
        }
    }

    // MARK: Writes

    /// The moment a trade on [epochDay] is stored at: a new trade for today uses now, another day noon; an edited one keeps its time of
    /// day while the day is unchanged ([originalMillis] is the entry's, or 0 for a new trade or a moved day), as Android's `momentOn`.
    private func tradeMoment(epochDay: Int, originalMillis: Int64) -> Int64 {
        return EntryBridge.shared.savedMillis(
            epochDay: Int32(epochDay), zoneId: TimeZone.current.identifier, nowMillis: nowMillis(), originalMillis: originalMillis
        )
    }

    /// Buys [shares] of [ticker] at [price] (as typed) on [epochDay]; [transferId] (0 for none) is the waiting transfer it answers.
    func buy(
        accountId: Int64, ticker: String, shares: String, price: String, epochDay: Int, transferId: Int64,
        done: @escaping (WriteOutcome) -> Void
    ) {
        let moment = tradeMoment(epochDay: epochDay, originalMillis: 0)
        perform({ opened in
            return try opened.buyTrade(
                accountId: accountId, ticker: ticker, shares: shares, price: price, epochMillis: moment, transferId: transferId
            )
        }, done: done)
    }

    func sell(
        accountId: Int64, ticker: String, shares: String, price: String, epochDay: Int, done: @escaping (WriteOutcome) -> Void
    ) {
        let moment = tradeMoment(epochDay: epochDay, originalMillis: 0)
        perform({ opened in
            return try opened.sellTrade(accountId: accountId, ticker: ticker, shares: shares, price: price, epochMillis: moment)
        }, done: done)
    }

    /// Saves a change of the buy or sale on [data]'s sheet: ticker, shares and price as typed, on [epochDay].
    func saveTradeEdit(
        data: TradeEditData, ticker: String, shares: String, price: String, epochDay: Int, done: @escaping (WriteOutcome) -> Void
    ) {
        let original: Int64 = epochDay == Int(data.day) ? data.epochMillis : 0
        let moment = tradeMoment(epochDay: epochDay, originalMillis: original)
        perform({ opened in
            try opened.editTrade(entryId: data.entryId, ticker: ticker, shares: shares, price: price, epochMillis: moment)
            return data.entryId
        }, done: done)
    }

    /// Moves a trade made before shares were kept to [epochDay]: the only thing that can change.
    func moveTrade(data: TradeEditData, epochDay: Int, done: @escaping (WriteOutcome) -> Void) {
        let original: Int64 = epochDay == Int(data.day) ? data.epochMillis : 0
        let moment = tradeMoment(epochDay: epochDay, originalMillis: original)
        perform({ opened in
            try opened.moveTrade(entryId: data.entryId, epochMillis: moment)
            return data.entryId
        }, done: done)
    }

    func deleteTrade(entryId: Int64, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.deleteTrade(entryId: entryId)
            return entryId
        }, done: done)
    }

    /// Saves a holding as typed ([holdingId] 0 adds shares you already own).
    func saveHolding(
        accountId: Int64, holdingId: Int64, ticker: String, shares: String, price: String, paid: String,
        done: @escaping (WriteOutcome) -> Void
    ) {
        let now = nowMillis()
        perform({ opened in
            return try opened.saveHolding(
                accountId: accountId, holdingId: holdingId, ticker: ticker, shares: shares, price: price, paid: paid, nowMillis: now
            )
        }, done: done)
    }

    func saveHoldingPrices(accountId: Int64, ids: [String], prices: [String], done: @escaping (WriteOutcome) -> Void) {
        let now = nowMillis()
        perform({ opened in
            try opened.saveHoldingPrices(accountId: accountId, ids: ids, prices: prices, nowMillis: now)
            return accountId
        }, done: done)
    }

    func deleteHolding(id: Int64, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.deleteHolding(id: id)
            return id
        }, done: done)
    }

    /// Stops waiting on transfer [id]: kept as cash, already recorded, or cleared. Nothing else changes.
    func settleTransfer(id: Int64, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.settleTransfer(id: id)
            return id
        }, done: done)
    }
}
