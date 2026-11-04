import XCTest
import KassaShared
@testable import Kassa

/// Investments (batch H) through the store, on the demo backup: Brokerage (account 6) holds 12.5 shares of ABC priced at 180.00 (worth
/// 2,250.00) and 1,000.00 of cash; Checking is account 1. A trade writes its holding change, its BUY/SELL entry and its trade row in ONE
/// transaction; a refusal or a failure leaves nothing behind. The rules are :domain's (JVM-tested in `InvestRulesTest` and
/// `InvestPagesTest`); these check that the Swift side runs them atomically and stores what the screens show.
final class InvestTests: XCTestCase {

    private var directory: URL!
    private var store: LedgerStore!
    private var today: Int32 = 0

    private let mask = "\u{2022}\u{2022}\u{2022}\u{2022}"
    private let broker: Int64 = 6
    private let checking: Int64 = 1

    override func setUpWithError() throws {
        KassaCore.shared.installDefaults()
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-invest-" + UUID().uuidString, isDirectory: true)
        store = try LedgerStore(directory: directory, keyStore: MemoryKeyStore())
        try store.replace(with: try DemoFixture.data())
        today = try DemoFixture.expected().today
    }

    override func tearDownWithError() throws {
        store = nil
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: Helpers

    private var moment: Int64 { return Int64(today) * 86_400_000 + 43_200_000 }

    private func ledger() throws -> LedgerView {
        return LedgerView(data: try store.export(), fallbackCurrency: nil)
    }

    private func page() throws -> InvestPageData {
        return try ledger().investPage(accountId: broker, hidden: false, todayEpochDay: today, timeZoneId: "UTC")
    }

    private func refusal(_ body: () throws -> Void) -> WriteRefusal? {
        do {
            try body()
        } catch let found as WriteRefusal {
            return found
        } catch {
            return nil
        }
        return nil
    }

    private func holding(_ ticker: String) throws -> BackupHolding? {
        for row in try store.export().holdings where row.accountId == broker && row.ticker == ticker { return row }
        return nil
    }

    private func microShares(_ ticker: String) throws -> Int64 {
        return try holding(ticker)?.microShares ?? 0
    }

    private func closeBroker() throws {
        let close = try store.db.prepare("UPDATE accounts SET closedDay = ? WHERE id = ?")
        try close.bindInt(1, Int64(today) - 1)
        try close.bindInt(2, broker)
        _ = try close.step()
    }

    private func anExpenseCategory() throws -> Int64 {
        for row in try store.export().transactions where row.kind == "EXPENSE" {
            if let found = row.categoryId { return found.int64Value }
        }
        return 0
    }

    // MARK: The figures

    func testTheDemoHoldingIsInTheAccountsTotals() throws {
        let data = try page()
        XCTAssertTrue(data.found)
        XCTAssertEqual(data.holdings.count, 1)
        XCTAssertEqual(data.holdings[0].ticker, "ABC")
        let file = try XCTUnwrap(Bundle(for: SettingsTests.self).url(forResource: "expected", withExtension: "json"))
        let object = try JSONSerialization.jsonObject(with: try Data(contentsOf: file))
        let root = try XCTUnwrap(object as? [String: Any])
        XCTAssertEqual(root["holdingsValue"] as? Int, 225_000)
        // 12.5 shares at 180.00 is 2,250.00; the page's holdings value is that, and its total is cash plus holdings.
        XCTAssertEqual(try microShares("ABC"), 12_500_000)
        XCTAssertEqual(try holding("ABC")?.price, 18_000)
    }

    func testHiddenMasksEveryFigureOfThePage() throws {
        let data = try ledger().investPage(accountId: broker, hidden: true, todayEpochDay: today, timeZoneId: "UTC")
        XCTAssertEqual(data.totalValueText, mask)
        XCTAssertEqual(data.cashText, mask)
        XCTAssertEqual(data.holdings[0].valueText, mask)
        XCTAssertNotEqual(data.gainKind, "UP")
        XCTAssertNotEqual(data.gainKind, "DOWN")
        XCTAssertEqual(data.totalValueTone.name, "Ink")
    }

    // MARK: A buy and a sale

    func testABuySavesHoldingEntryAndTradeTogether() throws {
        let entries = try store.rowCount(table: "transactions")
        let trades = try store.rowCount(table: "trades")
        let id = try store.buyTrade(accountId: broker, ticker: "XYZ", shares: "2", price: "50", epochMillis: moment, transferId: 0)
        XCTAssertGreaterThan(id, 0)
        XCTAssertEqual(try store.rowCount(table: "transactions"), entries + 1)
        XCTAssertEqual(try store.rowCount(table: "trades"), trades + 1)
        XCTAssertEqual(try microShares("XYZ"), 2_000_000)
    }

    func testAFailureAfterTheLastWriteRollsBackEverything() throws {
        struct Boom: Error {}
        let entries = try store.rowCount(table: "transactions")
        let trades = try store.rowCount(table: "trades")
        let before = try microShares("ABC")
        store.afterInsertHook = { throw Boom() }
        XCTAssertThrowsError(
            try store.buyTrade(accountId: broker, ticker: "ABC", shares: "1", price: "100", epochMillis: moment, transferId: 0)
        )
        store.afterInsertHook = nil
        XCTAssertEqual(try store.rowCount(table: "transactions"), entries)
        XCTAssertEqual(try store.rowCount(table: "trades"), trades)
        XCTAssertEqual(try microShares("ABC"), before)
        // The store still works afterwards.
        _ = try store.buyTrade(accountId: broker, ticker: "ABC", shares: "1", price: "100", epochMillis: moment, transferId: 0)
        XCTAssertEqual(try microShares("ABC"), before + 1_000_000)
    }

    func testASaleAndTheWholeRoundTrip() throws {
        let entries = try store.rowCount(table: "transactions")
        let before = try microShares("ABC")
        let bought = try store.buyTrade(accountId: broker, ticker: "ABC", shares: "2", price: "190", epochMillis: moment, transferId: 0)
        XCTAssertEqual(try microShares("ABC"), before + 2_000_000)
        let sold = try store.sellTrade(accountId: broker, ticker: "ABC", shares: "2", price: "190", epochMillis: moment)
        XCTAssertGreaterThan(sold, bought)
        XCTAssertEqual(try microShares("ABC"), before)
        try store.deleteTrade(entryId: sold)
        XCTAssertEqual(try microShares("ABC"), before + 2_000_000)
        try store.deleteTrade(entryId: bought)
        XCTAssertEqual(try microShares("ABC"), before)
        XCTAssertEqual(try store.rowCount(table: "transactions"), entries)
    }

    func testSellingMoreThanYouHoldIsRefusedAndWritesNothing() throws {
        let entries = try store.rowCount(table: "transactions")
        let found = refusal {
            _ = try store.sellTrade(accountId: broker, ticker: "ABC", shares: "99", price: "180", epochMillis: moment)
        }
        XCTAssertNotNil(found)
        XCTAssertEqual(try store.rowCount(table: "transactions"), entries)
        XCTAssertEqual(try microShares("ABC"), 12_500_000)
    }

    func testEditingATradeMovesTheHoldingAndTheEntryTogether() throws {
        let id = try store.buyTrade(accountId: broker, ticker: "XYZ", shares: "2", price: "50", epochMillis: moment, transferId: 0)
        try store.editTrade(entryId: id, ticker: "XYZ", shares: "3", price: "50", epochMillis: moment)
        XCTAssertEqual(try microShares("XYZ"), 3_000_000)
        let data = try store.export()
        var amount: Int64 = 0
        for row in data.transactions where row.id == id { amount = row.amount }
        XCTAssertEqual(amount, 15_000)
        try store.moveTrade(entryId: id, epochMillis: moment - 86_400_000)
        XCTAssertEqual(try microShares("XYZ"), 3_000_000)
    }

    func testAFailureInAnEditLeavesTheTradeAsItWas() throws {
        struct Boom: Error {}
        let id = try store.buyTrade(accountId: broker, ticker: "XYZ", shares: "2", price: "50", epochMillis: moment, transferId: 0)
        store.afterInsertHook = { throw Boom() }
        XCTAssertThrowsError(try store.editTrade(entryId: id, ticker: "XYZ", shares: "5", price: "50", epochMillis: moment))
        XCTAssertThrowsError(try store.deleteTrade(entryId: id))
        store.afterInsertHook = nil
        XCTAssertEqual(try microShares("XYZ"), 2_000_000)
    }

    func testAClosedAccountTakesNoNewTrade() throws {
        try closeBroker()
        let entries = try store.rowCount(table: "transactions")
        let bought = refusal {
            _ = try store.buyTrade(accountId: broker, ticker: "ABC", shares: "1", price: "100", epochMillis: moment, transferId: 0)
        }
        XCTAssertTrue(bought?.message.hasSuffix("is closed. Reopen it first to add to it.") ?? false)
        let sold = refusal {
            _ = try store.sellTrade(accountId: broker, ticker: "ABC", shares: "1", price: "100", epochMillis: moment)
        }
        XCTAssertTrue(sold?.message.hasSuffix("is closed. Reopen it first to add to it.") ?? false)
        XCTAssertEqual(try store.rowCount(table: "transactions"), entries)
    }

    func testATradeOnAnAccountThatIsNotInvestmentIsRefused() throws {
        let entries = try store.rowCount(table: "transactions")
        let found = refusal {
            _ = try store.buyTrade(accountId: checking, ticker: "ABC", shares: "1", price: "100", epochMillis: moment, transferId: 0)
        }
        XCTAssertNotNil(found)
        XCTAssertEqual(try store.rowCount(table: "transactions"), entries)
    }

    func testInputsAreBounded() throws {
        let entries = try store.rowCount(table: "transactions")
        let long = String(repeating: "A", count: 60)
        let found = refusal {
            _ = try store.buyTrade(accountId: broker, ticker: long, shares: "1", price: "100", epochMillis: moment, transferId: 0)
        }
        // Either refused, or the ticker was cut to its limit: never stored longer.
        if found == nil {
            for row in try store.export().holdings { XCTAssertLessThanOrEqual(row.ticker.count, 12) }
        } else {
            XCTAssertEqual(try store.rowCount(table: "transactions"), entries)
        }
        let huge = refusal {
            _ = try store.buyTrade(accountId: broker, ticker: "ABC", shares: "1", price: "999999999", epochMillis: moment, transferId: 0)
        }
        XCTAssertNotNil(huge)
    }

    // MARK: Holdings and prices

    func testAHoldingIsAddedPricedAndRemoved() throws {
        let entries = try store.rowCount(table: "transactions")
        let id = try store.saveHolding(
            accountId: broker, holdingId: 0, ticker: "OLD", shares: "4", price: "10", paid: "8", nowMillis: moment
        )
        XCTAssertGreaterThan(id, 0)
        XCTAssertEqual(try microShares("OLD"), 4_000_000)
        try store.saveHolding(accountId: broker, holdingId: id, ticker: "OLD", shares: "", price: "12", paid: "", nowMillis: moment)
        XCTAssertEqual(try holding("OLD")?.price, 1_200)
        try store.saveHoldingPrices(accountId: broker, ids: [String(id)], prices: ["13"], nowMillis: moment)
        XCTAssertEqual(try holding("OLD")?.price, 1_300)
        try store.deleteHolding(id: id)
        XCTAssertNil(try holding("OLD"))
        // No cash moved at any point.
        XCTAssertEqual(try store.rowCount(table: "transactions"), entries)
    }

    func testOneBadPriceSavesNoPrice() throws {
        let id = try store.saveHolding(
            accountId: broker, holdingId: 0, ticker: "TWO", shares: "1", price: "10", paid: "8", nowMillis: moment
        )
        let abc = try holding("ABC")?.id ?? 0
        let found = refusal {
            try store.saveHoldingPrices(accountId: broker, ids: [String(abc), String(id)], prices: ["200", "abc"], nowMillis: moment)
        }
        XCTAssertNotNil(found)
        XCTAssertEqual(try holding("ABC")?.price, 18_000)
    }

    // MARK: Waiting transfers

    private func waitingTransfer() throws -> Int64 {
        let entry = EntryInput(
            id: 0, kind: "TRANSFER", amount: 50_000, accountId: checking, toAccountId: broker, categoryId: 0,
            epochMillis: moment, note: ""
        )
        return try store.addEntry(entry)
    }

    private func transferRows(for entry: Int64) throws -> Int {
        var count = 0
        for row in try store.export().investmentTransfers where row.transactionId == entry { count += 1 }
        return count
    }

    func testATransferIntoInvestmentsWaitsAndFollowsItsEntry() throws {
        let id = try waitingTransfer()
        XCTAssertEqual(try transferRows(for: id), 1)
        let waiting = try page().waiting
        XCTAssertEqual(waiting.count, 1)
        // Edited to another amount: still waiting, with the new amount.
        let edit = EntryInput(
            id: id, kind: "TRANSFER", amount: 60_000, accountId: checking, toAccountId: broker, categoryId: 0,
            epochMillis: moment, note: ""
        )
        try store.updateEntry(edit)
        XCTAssertEqual(try transferRows(for: id), 1)
        var amount: Int64 = 0
        for row in try store.export().investmentTransfers where row.transactionId == id { amount = row.amount }
        XCTAssertEqual(amount, 60_000)
        // Deleted: the row goes with it.
        try store.deleteEntry(id: id)
        XCTAssertEqual(try transferRows(for: id), 0)
    }

    func testAnEditToANonInvestmentAccountEndsTheWait() throws {
        let id = try waitingTransfer()
        let edit = EntryInput(
            id: id, kind: "TRANSFER", amount: 50_000, accountId: checking, toAccountId: 2, categoryId: 0, epochMillis: moment, note: ""
        )
        try store.updateEntry(edit)
        XCTAssertEqual(try transferRows(for: id), 0)
    }

    func testBuyingAnswersTheWaitingTransferInTheSameTransaction() throws {
        let id = try waitingTransfer()
        let waiting = try page().waiting
        XCTAssertEqual(waiting.count, 1)
        let transferId = waiting[0].id
        _ = try store.buyTrade(accountId: broker, ticker: "ABC", shares: "1", price: "100", epochMillis: moment, transferId: transferId)
        XCTAssertEqual(try transferRows(for: id), 0)
    }

    func testAFailedBuyKeepsTheTransferWaiting() throws {
        struct Boom: Error {}
        let id = try waitingTransfer()
        let transferId = try page().waiting[0].id
        store.afterInsertHook = { throw Boom() }
        XCTAssertThrowsError(
            try store.buyTrade(accountId: broker, ticker: "ABC", shares: "1", price: "100", epochMillis: moment, transferId: transferId)
        )
        store.afterInsertHook = nil
        XCTAssertEqual(try transferRows(for: id), 1)
    }

    func testClearingStopsAskingAndChangesNothingElse() throws {
        let id = try waitingTransfer()
        let entries = try store.rowCount(table: "transactions")
        let transferId = try page().waiting[0].id
        try store.settleTransfer(id: transferId)
        XCTAssertEqual(try transferRows(for: id), 0)
        XCTAssertEqual(try store.rowCount(table: "transactions"), entries)
    }

    func testAnEntryOfTheClosedAccountMessageIsTheSharedOne() throws {
        try closeBroker()
        let found = refusal { _ = try waitingTransfer() }
        XCTAssertTrue(found?.message.hasSuffix("is closed. Reopen it first to add to it.") ?? false)
    }

    // MARK: Categories

    func testEveryExpenseAndIncomeEntryHasARealCategory() throws {
        let category = try anExpenseCategory()
        XCTAssertGreaterThan(category, 0)
        _ = try store.addEntry(EntryInput(
            id: 0, kind: "EXPENSE", amount: 1_000, accountId: checking, toAccountId: 0, categoryId: category, epochMillis: moment, note: ""
        ))
        _ = try store.buyTrade(accountId: broker, ticker: "ABC", shares: "1", price: "100", epochMillis: moment, transferId: 0)
        let data = try store.export()
        var known: Set<Int64> = []
        for row in data.categories { known.insert(row.id) }
        for row in data.transactions where row.kind == "EXPENSE" || row.kind == "INCOME" {
            guard let found = row.categoryId else {
                XCTFail("entry \(row.id) has no category")
                continue
            }
            XCTAssertTrue(known.contains(found.int64Value), "entry \(row.id) points at a missing category")
        }
    }
}
