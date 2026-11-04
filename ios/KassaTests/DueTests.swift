import XCTest
import KassaShared
@testable import Kassa

/// Batch I through the store and the phone's settings: approving a bill is all or nothing, Clear adds nothing and is remembered, the due
/// rules persist, and the scheduler hands the system what :domain planned. The rules themselves are JVM-tested against the demo backup.
final class DueTests: XCTestCase {

    private var directory: URL!
    private var store: LedgerStore!
    private var defaults: UserDefaults!
    private var suite = ""

    /// 2026-03-10.
    private let day: Int32 = 20522

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-due-" + UUID().uuidString, isDirectory: true)
        store = try LedgerStore(directory: directory, keyStore: MemoryKeyStore())
        try store.startFresh(currency: "USD", categories: ["Groceries"])
        suite = "kassa-due-tests-" + UUID().uuidString
        defaults = UserDefaults(suiteName: suite)
    }

    override func tearDownWithError() throws {
        store = nil
        defaults.removePersistentDomain(forName: suite)
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: Helpers

    private func ledger() throws -> LedgerView {
        return LedgerView(data: try store.export(), fallbackCurrency: nil)
    }

    private func mainAccount() throws -> Int64 {
        let input = AccountInput(type: "CHECKING", name: "Main", last4: "", balance: "1000", statementDay: "", dueDay: "", limit: "", payFromId: 0)
        return try store.addAccount(input)
    }

    private func groceries() throws -> Int64 {
        let found: [CategoryChoiceData] = try ledger().categoryChoices(kind: "EXPENSE", query: "")
        return found[0].id
    }

    private func rent(account: Int64, category: Int64) -> BillInput {
        return BillInput(id: 0, name: "Rent", categoryId: category, accountId: account, day: 1, amount: "500", active: true)
    }

    private func mainText() throws -> String? {
        let data = try ledger().accounts(hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        for group in data.groups {
            for row in group.rows where row.name == "Main" { return row.amountText }
        }
        return nil
    }

    private func handledMonth(_ id: Int64) throws -> Int32? {
        for bill in try store.export().bills where bill.id == id { return bill.handledMonth }
        return nil
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

    // MARK: Bills

    func testASavedBillIsListedEditedAndDeleted() throws {
        let main = try mainAccount()
        let id = try store.saveBill(rent(account: main, category: try groceries()))
        XCTAssertGreaterThan(id, 0)
        XCTAssertEqual(try ledger().billsPage(hidden: false, todayEpochDay: Int(day), timeZoneId: "UTC").rows.count, 1)
        let edited = BillInput(id: id, name: "Rent flat", categoryId: try groceries(), accountId: main, day: 3, amount: "", active: false)
        _ = try store.saveBill(edited)
        let sheet = try ledger().billSheet(billId: id)
        XCTAssertEqual(sheet.name, "Rent flat")
        XCTAssertEqual(sheet.day, 3)
        XCTAssertFalse(sheet.active)
        try store.deleteBill(id: id)
        XCTAssertEqual(try store.rowCount(table: "recurring"), 0)
    }

    func testApprovingABillAddsTheEntryAndMarksTheMonthTogether() throws {
        let main = try mainAccount()
        let id = try store.saveBill(rent(account: main, category: try groceries()))
        let entry = try store.approveBill(billId: id, amount: 50_000, dueDay: day, todayDay: day, zoneId: "UTC")
        XCTAssertGreaterThan(entry, 0)
        XCTAssertEqual(try store.rowCount(table: "transactions"), 1)
        XCTAssertEqual(try mainText(), "$500.00")
        XCTAssertEqual(try handledMonth(id), BillBridge.shared.handledMonth(dueDay: day))
        XCTAssertEqual(BillBridge.shared.handledMonth(dueDay: day), 202_603)
    }

    func testAFailureAfterTheEntryRollsBackTheBillToo() throws {
        struct Boom: Error {}
        let main = try mainAccount()
        let id = try store.saveBill(rent(account: main, category: try groceries()))
        store.afterInsertHook = { throw Boom() }
        XCTAssertThrowsError(try store.approveBill(billId: id, amount: 50_000, dueDay: day, todayDay: day, zoneId: "UTC"))
        store.afterInsertHook = nil
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
        XCTAssertEqual(try mainText(), "$1,000.00")
        XCTAssertEqual(try handledMonth(id), 0)
        // The bill still asks, and approving works afterwards.
        _ = try store.approveBill(billId: id, amount: 50_000, dueDay: day, todayDay: day, zoneId: "UTC")
        XCTAssertEqual(try mainText(), "$500.00")
    }

    func testApprovingABillThatIsGoneIsRefusedAndAddsNothing() throws {
        _ = try mainAccount()
        let refused = refusal { _ = try store.approveBill(billId: 999, amount: 100, dueDay: day, todayDay: day, zoneId: "UTC") }
        XCTAssertNotNil(refused)
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
    }

    // MARK: Clear

    func testClearingABillMarksTheMonthAndAddsNothing() throws {
        let main = try mainAccount()
        let id = try store.saveBill(rent(account: main, category: try groceries()))
        let month: Int32 = BillBridge.shared.handledMonth(dueDay: day)
        try store.clearNotices(bills: [BillMonth(id: id, month: month)], transfers: [])
        XCTAssertEqual(try handledMonth(id), month)
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
        XCTAssertEqual(try mainText(), "$1,000.00")
    }

    func testClearedKeysAreRememberedAndForgottenOnRestore() {
        let settings = DueSettings(defaults: defaults)
        XCTAssertEqual(settings.cleared, [])
        settings.clear(["loan:3:20530", "suggest:rent:2"])
        settings.clear(["loan:3:20530"])
        XCTAssertEqual(DueSettings(defaults: defaults).cleared, ["loan:3:20530", "suggest:rent:2"])
        settings.posted = ["card:1:20500"]
        settings.forgetAccountReferences()
        XCTAssertEqual(settings.cleared, [])
        XCTAssertEqual(settings.posted, [])
    }

    // MARK: Rules

    func testTheDueRulesStartAtTheDomainDefaultsAndPersist() {
        let settings = DueSettings(defaults: defaults)
        XCTAssertTrue(settings.rule("CARD").enabled)
        XCTAssertTrue(settings.rule("LOAN").enabled)
        XCTAssertFalse(settings.rule("BILL").enabled)
        XCTAssertEqual(settings.rule("CARD").minute, 9 * 60)
        XCTAssertEqual(settings.rule("CARD").daysBefore, 0)
        settings.setRule("BILL", DueRuleValue(enabled: true, daysBefore: 3, minute: 8 * 60 + 30))
        let again = DueSettings(defaults: defaults).rule("BILL")
        XCTAssertEqual(again, DueRuleValue(enabled: true, daysBefore: 3, minute: 510))
        XCTAssertTrue(settings.anyEnabled)
    }

    func testTheDueRulesAreKeptWithinTheirLimits() {
        let settings = DueSettings(defaults: defaults)
        settings.setRule("CARD", DueRuleValue(enabled: true, daysBefore: 99, minute: 5_000))
        let rule = settings.rule("CARD")
        XCTAssertEqual(rule.daysBefore, Int(DueBridge.shared.maxDays()))
        XCTAssertEqual(rule.minute, 1_439)
        XCTAssertEqual(rule.daysBefore, 14)
    }

    func testTheReminderAndTheAskArePersisted() {
        let settings = DueSettings(defaults: defaults)
        XCTAssertFalse(settings.reminderOn)
        XCTAssertFalse(settings.notifyAsked)
        settings.reminderOn = true
        settings.reminderMinute = 7 * 60
        settings.notifyAsked = true
        let again = DueSettings(defaults: defaults)
        XCTAssertTrue(again.reminderOn)
        XCTAssertEqual(again.reminderMinute, 420)
        XCTAssertTrue(again.notifyAsked)
    }

    // MARK: The system's notifications

    func testThePlanBecomesCalendarTriggersAndALateNoticeRingsSoon() {
        let zone = TimeZone(identifier: "UTC")!
        let now: Int64 = 1_800_000_000_000
        let later = PlannedNotice(
            key: "card:1:20500", epochMillis: now + 86_400_000, title: "Payment due", body: "Open Kassa", late: false, kind: "CARD", id: 1
        )
        let late = PlannedNotice(
            key: "loan:2:20500", epochMillis: now + 5_000, title: "Payment due", body: "Open Kassa", late: true, kind: "LOAN", id: 2
        )
        let out = DueScheduler.notices(plan: [later, late], nowMillis: now, zone: zone, reminderOn: true, reminderMinute: 21 * 60)
        XCTAssertEqual(out.count, 3)
        XCTAssertEqual(out[0].identifier, "kassa.due.card:1:20500")
        XCTAssertNotNil(out[0].components)
        XCTAssertNil(out[1].components)
        XCTAssertEqual(out[1].seconds, 5, accuracy: 0.001)
        XCTAssertEqual(out[2].identifier, "kassa.reminder")
        XCTAssertTrue(out[2].repeats)
        XCTAssertEqual(out[2].components?.hour, 21)
        for item in out {
            XCTAssertFalse(item.body.contains("$"))
            XCTAssertFalse(item.title.contains("Rent"))
        }
    }

    func testWhatHasBeenRungIsKeptUntilItsDueDateIsOld() {
        let now: Int64 = 1_800_000_000_000
        let today = 20_500
        let plan: [String: Double] = [
            "card:1:20500": Double(now - 1_000),
            "card:2:20510": Double(now + 1_000)
        ]
        let kept = DueScheduler.postedKeys(posted: ["bill:9:20000", "transfer:4"], lastPlan: plan, nowMillis: now, today: today)
        XCTAssertTrue(kept.contains("card:1:20500"))
        XCTAssertFalse(kept.contains("card:2:20510"))
        XCTAssertFalse(kept.contains("bill:9:20000"))
        XCTAssertTrue(kept.contains("transfer:4"))
    }
}
