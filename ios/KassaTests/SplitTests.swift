import XCTest
import KassaShared
@testable import Kassa

/// Splitting an expense on the entry page: the rules are :domain's `SplitRules` (JVM-tested), these tests check that Swift writes what
/// they say in one transaction (the entry, your share, who paid and one line per person), reads it back for an edit, removes it with the
/// entry, keeps it through a backup, and that the page's draft model carries the pick. The suite runs as en-US.
final class SplitTests: XCTestCase {

    private var directory: URL!
    private var keys: MemoryKeyStore!
    private var store: LedgerStore!

    /// 2026-03-10, at noon UTC.
    private let day: Int32 = 20522
    private let millis: Int64 = 20522 * 86_400_000 + 43_200_000

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-split-" + UUID().uuidString, isDirectory: true)
        keys = MemoryKeyStore()
        store = try LedgerStore(directory: directory, keyStore: keys)
    }

    override func tearDownWithError() throws {
        store = nil
        try? FileManager.default.removeItem(at: directory)
    }

    // MARK: Helpers

    private func ledger() throws -> LedgerView {
        return LedgerView(data: try store.export(), fallbackCurrency: nil)
    }

    private struct Ids {
        let main: Int64
        let groceries: Int64
        let sam: Int64
        let amy: Int64
    }

    /// A store with a currency, a category, an account holding 1,000.00 and two people.
    private func seeded() throws -> Ids {
        try store.startFresh(currency: "USD", categories: ["Groceries"])
        let account = AccountInput(
            type: "CHECKING", name: "Main", last4: "", balance: "1000", statementDay: "", dueDay: "", limit: "", payFromId: 0
        )
        let main = try store.addAccount(account)
        let found: [CategoryChoiceData] = try ledger().categoryChoices(kind: "EXPENSE", query: "")
        let groceries: Int64 = try XCTUnwrap(found.first).id
        let sam = try store.addPerson(name: "Sam", theyOwe: true, amount: "")
        let amy = try store.addPerson(name: "Amy", theyOwe: true, amount: "")
        return Ids(main: main, groceries: groceries, sam: sam, amy: amy)
    }

    private func pick(_ ids: [Int64]) -> SplitState {
        var state: SplitState = SplitBridge.shared.none()
        for id in ids {
            state = SplitBridge.shared.toggle(state: state, personId: id)
        }
        return state
    }

    private func entry(
        _ ids: Ids, id: Int64 = 0, cents: Int64 = 12_000, account: Int64? = nil, split: SplitState
    ) -> EntryInput {
        let from: Int64 = account ?? ids.main
        return EntryInput(
            id: id, kind: "EXPENSE", amount: cents, accountId: from, toAccountId: 0, categoryId: ids.groceries,
            epochMillis: millis, note: "Dinner", split: split
        )
    }

    private func mainBalance() throws -> String? {
        let data = try ledger().accounts(hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        for group in data.groups {
            for row in group.rows where row.name == "Main" { return row.amountText }
        }
        return nil
    }

    private func person(_ id: Int64) throws -> PersonPageData {
        return try ledger().personPage(personId: id, hidden: false, timeZoneId: "UTC")
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

    // MARK: Saving

    func testAnEqualSplitYouPaidKeepsYourShareAndEachPersonOwesTheirs() throws {
        let ids = try seeded()
        let id = try store.addEntry(entry(ids, split: pick([ids.sam, ids.amy])))
        XCTAssertEqual(try mainBalance(), "$880.00", "you paid all of it")
        XCTAssertEqual(try store.rowCount(table: "loan_entries"), 2)
        let sam = try person(ids.sam)
        XCTAssertEqual(sam.balanceText, "$40.00")
        XCTAssertEqual(sam.side.name, "OwesYou")
        XCTAssertEqual(try person(ids.amy).balanceText, "$40.00")
        let month = try ledger().activity(year: 2026, month: 3, hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        let rows: [EntryRowData] = month.days.flatMap { $0.entries }
        XCTAssertEqual(rows.first?.id, id)
        XCTAssertEqual(rows.first?.myShareText, "$40.00")
        XCTAssertEqual(month.spendingText, "$40.00", "spending counts only your share")
    }

    func testExactSharesAreStoredAsTyped() throws {
        let ids = try seeded()
        var split = SplitBridge.shared.useExact(state: pick([ids.sam, ids.amy]), total: 12_000)
        split = SplitBridge.shared.typedShare(state: split, personId: ids.sam, typed: "50")
        split = SplitBridge.shared.typedShare(state: split, personId: ids.amy, typed: "40")
        _ = try store.addEntry(entry(ids, split: split))
        XCTAssertEqual(try person(ids.sam).balanceText, "$50.00")
        XCTAssertEqual(try person(ids.amy).balanceText, "$40.00")
        let month = try ledger().activity(year: 2026, month: 3, hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        XCTAssertEqual(month.spendingText, "$30.00")
    }

    func testSharesThatAddUpToMoreAreRefusedAndNothingIsWritten() throws {
        let ids = try seeded()
        var split = SplitBridge.shared.useExact(state: pick([ids.sam, ids.amy]), total: 12_000)
        split = SplitBridge.shared.typedShare(state: split, personId: ids.sam, typed: "70")
        split = SplitBridge.shared.typedShare(state: split, personId: ids.amy, typed: "60")
        let found = refusal { _ = try store.addEntry(entry(ids, split: split)) }
        XCTAssertEqual(found?.field, "SPLIT")
        XCTAssertEqual(found?.message, "The shares add up to more than $120.00.")
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
        XCTAssertEqual(try store.rowCount(table: "loan_entries"), 0)
        XCTAssertEqual(try mainBalance(), "$1,000.00")
    }

    func testAPersonWhoIsGoneIsRefused() throws {
        let ids = try seeded()
        let found = refusal { _ = try store.addEntry(entry(ids, split: pick([999]))) }
        XCTAssertEqual(found?.message, "This person is gone.")
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
    }

    func testWhenSomeoneElsePaidNoAccountOfYoursMovesAndYouOweThemYourShare() throws {
        let ids = try seeded()
        let split = SplitBridge.shared.payer(state: pick([ids.sam, ids.amy]), personId: ids.sam)
        let input = entry(ids, cents: 9_000, account: 0, split: split)
        let id = try store.addEntry(input)
        XCTAssertEqual(try mainBalance(), "$1,000.00", "none of your accounts moved")
        let sam = try person(ids.sam)
        XCTAssertEqual(sam.balanceText, "$30.00")
        XCTAssertEqual(sam.side.name, "YouOwe")
        XCTAssertEqual(try person(ids.amy).side.name, "Settled", "what the others owe the payer is not tracked")
        XCTAssertEqual(try store.rowCount(table: "loan_entries"), 1)
        let reopened: SplitState = try ledger().splitStart(entryId: id, withPersonId: 0)
        XCTAssertEqual(reopened.paidBy, ids.sam)
        XCTAssertNotNil(try ledger().entryEdit(id: id), "it is edited on the entry page")
    }

    func testWithoutAnAccountAndNobodyPaidTheEntryIsRefused() throws {
        let ids = try seeded()
        let found = refusal { _ = try store.addEntry(entry(ids, account: 0, split: pick([ids.sam]))) }
        XCTAssertEqual(found?.message, "Pick an account.")
    }

    func testOnlyAnExpenseIsSplit() throws {
        let ids = try seeded()
        let input = EntryInput(
            id: 0, kind: "INCOME", amount: 5_000, accountId: ids.main, toAccountId: 0, categoryId: ids.groceries,
            epochMillis: millis, note: "Refund", split: pick([ids.sam])
        )
        _ = try store.addEntry(input)
        XCTAssertEqual(try store.rowCount(table: "loan_entries"), 0)
        XCTAssertEqual(try person(ids.sam).side.name, "Settled")
    }

    func testAFailureInsideTheTransactionLeavesNoEntryAndNoLines() throws {
        let ids = try seeded()
        struct Boom: Error {}
        store.afterInsertHook = { throw Boom() }
        XCTAssertThrowsError(try store.addEntry(entry(ids, split: pick([ids.sam]))))
        store.afterInsertHook = nil
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
        XCTAssertEqual(try store.rowCount(table: "loan_entries"), 0)
        XCTAssertEqual(try mainBalance(), "$1,000.00")
    }

    // MARK: Editing and deleting

    func testReopeningASplitGivesItsPeopleAndSharesBack() throws {
        let ids = try seeded()
        let id = try store.addEntry(entry(ids, split: pick([ids.sam, ids.amy])))
        let state: SplitState = try ledger().splitStart(entryId: id, withPersonId: 0)
        XCTAssertEqual(state.people, String(ids.sam) + "," + String(ids.amy))
        XCTAssertFalse(state.exact)
        XCTAssertEqual(state.paidBy, 0)
        XCTAssertTrue(SplitBridge.shared.isSelected(state: state, personId: ids.amy))
    }

    func testEditingASplitRewritesItsLines() throws {
        let ids = try seeded()
        let id = try store.addEntry(entry(ids, split: pick([ids.sam, ids.amy])))
        try store.updateEntry(entry(ids, id: id, cents: 9_000, split: pick([ids.sam])))
        XCTAssertEqual(try store.rowCount(table: "loan_entries"), 1)
        XCTAssertEqual(try person(ids.sam).balanceText, "$45.00")
        XCTAssertEqual(try person(ids.amy).side.name, "Settled")
        XCTAssertEqual(try mainBalance(), "$910.00")
    }

    func testEditingASplitIntoAPlainExpenseLeavesNoShareAndNoLines() throws {
        let ids = try seeded()
        let id = try store.addEntry(entry(ids, split: pick([ids.sam])))
        try store.updateEntry(entry(ids, id: id, split: SplitBridge.shared.none()))
        XCTAssertEqual(try store.rowCount(table: "loan_entries"), 0)
        XCTAssertEqual(try person(ids.sam).side.name, "Settled")
        let month = try ledger().activity(year: 2026, month: 3, hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        XCTAssertEqual(month.spendingText, "$120.00")
        let rows: [EntryRowData] = month.days.flatMap { $0.entries }
        XCTAssertEqual(rows.first?.myShareText, "")
    }

    func testAPlainExpenseCanBecomeASplitAndAPaidByOneCanBecomeYours() throws {
        let ids = try seeded()
        let id = try store.addEntry(entry(ids, split: SplitBridge.shared.none()))
        try store.updateEntry(entry(ids, id: id, split: SplitBridge.shared.payer(state: pick([ids.sam]), personId: ids.sam)))
        XCTAssertEqual(try person(ids.sam).side.name, "YouOwe")
        XCTAssertEqual(try person(ids.sam).balanceText, "$60.00")
        try store.updateEntry(entry(ids, id: id, split: pick([ids.sam])))
        XCTAssertEqual(try person(ids.sam).side.name, "OwesYou")
        XCTAssertEqual(try mainBalance(), "$880.00")
    }

    func testDeletingASplitRemovesItsLines() throws {
        let ids = try seeded()
        let id = try store.addEntry(entry(ids, split: pick([ids.sam, ids.amy])))
        try store.deleteEntry(id: id)
        XCTAssertEqual(try store.rowCount(table: "loan_entries"), 0)
        XCTAssertEqual(try store.rowCount(table: "transactions"), 0)
        XCTAssertEqual(try person(ids.sam).side.name, "Settled")
    }

    func testAFailedEditKeepsTheOldSplit() throws {
        let ids = try seeded()
        let id = try store.addEntry(entry(ids, split: pick([ids.sam, ids.amy])))
        var over = SplitBridge.shared.useExact(state: pick([ids.sam]), total: 12_000)
        over = SplitBridge.shared.typedShare(state: over, personId: ids.sam, typed: "500")
        let found = refusal { try store.updateEntry(entry(ids, id: id, split: over)) }
        XCTAssertEqual(found?.field, "SPLIT")
        XCTAssertEqual(try store.rowCount(table: "loan_entries"), 2)
        XCTAssertEqual(try person(ids.amy).balanceText, "$40.00")
    }

    // MARK: Split a transaction

    func testSplitATransactionOffersExpensesYouPaidNotYetSplitWithThePerson() throws {
        let ids = try seeded()
        let plain = try store.addEntry(entry(ids, split: SplitBridge.shared.none()))
        let withSam = try store.addEntry(entry(ids, split: pick([ids.sam])))
        let forSam: [DatedRowData] = try ledger().splitCandidates(personId: ids.sam, hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        XCTAssertEqual(forSam.map { $0.entry.id }, [plain])
        let forAmy: [DatedRowData] = try ledger().splitCandidates(personId: ids.amy, hidden: false, todayEpochDay: day, timeZoneId: "UTC")
        XCTAssertEqual(Set(forAmy.map { $0.entry.id }), Set([plain, withSam]))
        let old: [DatedRowData] = try ledger().splitCandidates(personId: ids.amy, hidden: false, todayEpochDay: day + 61, timeZoneId: "UTC")
        XCTAssertTrue(old.isEmpty, "older than 60 days")
    }

    func testOpeningAPlainExpenseWithAPersonAddsThemToTheSplit() throws {
        let ids = try seeded()
        let id = try store.addEntry(entry(ids, split: SplitBridge.shared.none()))
        let state: SplitState = try ledger().splitStart(entryId: id, withPersonId: ids.sam)
        XCTAssertEqual(state.people, String(ids.sam))
        XCTAssertFalse(state.exact)
    }

    // MARK: The backup keeps it

    func testASplitSurvivesABackupAndARestore() throws {
        let ids = try seeded()
        var split = SplitBridge.shared.useExact(state: pick([ids.sam, ids.amy]), total: 12_000)
        split = SplitBridge.shared.typedShare(state: split, personId: ids.sam, typed: "50")
        split = SplitBridge.shared.typedShare(state: split, personId: ids.amy, typed: "40")
        let id = try store.addEntry(entry(ids, split: split))
        let paid = try store.addEntry(entry(ids, cents: 9_000, account: 0, split: SplitBridge.shared.payer(state: pick([ids.sam]), personId: ids.sam)))
        let text: String = BackupBridge.shared.write(data: try store.export())
        let parsed: BackupData = try BackupBridge.shared.parse(text: text)
        let other = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-split2-" + UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: other) }
        let second = try LedgerStore(directory: other, keyStore: MemoryKeyStore())
        try second.replace(with: parsed)
        XCTAssertEqual(try second.rowCount(table: "loan_entries"), try store.rowCount(table: "loan_entries"))
        let view = LedgerView(data: try second.export(), fallbackCurrency: nil)
        XCTAssertEqual(view.personPage(personId: ids.sam, hidden: false, timeZoneId: "UTC").balanceText, "$5.00", "50.00 owed to you minus 45.00 you owe Sam")
        XCTAssertEqual(view.personPage(personId: ids.amy, hidden: false, timeZoneId: "UTC").balanceText, "$40.00")
        let again: SplitState = view.splitStart(entryId: id, withPersonId: 0)
        XCTAssertTrue(again.exact)
        XCTAssertEqual(again.shares, String(ids.sam) + ":5000," + String(ids.amy) + ":4000")
        let reopenedPaid: SplitState = view.splitStart(entryId: paid, withPersonId: 0)
        XCTAssertEqual(reopenedPaid.paidBy, ids.sam)
    }

    // MARK: The page's draft model

    private func model(_ view: LedgerView, editing: EntryEditData? = nil, split: SplitState? = nil, open: Bool = false) -> EntryDraftModel {
        return EntryDraftModel(
            view: view, editing: editing, draft: "", today: Int(day), zone: "UTC", split: split, openSplit: open
        )
    }

    func testTheDraftModelOffersTheSplitRowOnlyForAnExpenseWithPeople() throws {
        let ids = try seeded()
        let page = model(try ledger())
        XCTAssertTrue(page.offersSplit)
        page.switchKind(to: "INCOME")
        XCTAssertFalse(page.offersSplit)
        page.switchKind(to: "EXPENSE")
        XCTAssertTrue(page.offersSplit)
        XCTAssertEqual(page.splitPeople.map { $0.name }, ["Amy", "Sam"], "A to Z")
        XCTAssertFalse(page.isShared)
        page.toggleSplit(ids.sam)
        XCTAssertTrue(page.isShared)
    }

    func testTheDraftModelWorksOutSharesAndWhoPaid() throws {
        let ids = try seeded()
        let page = model(try ledger())
        for key in ["1", "2", "0"] { page.press(key) }
        XCTAssertEqual(page.cents, 12_000)
        page.toggleSplit(ids.sam)
        page.toggleSplit(ids.amy)
        XCTAssertEqual(page.splitView.myShareText, "$40.00")
        XCTAssertEqual(page.splitView.lines.count, 3)
        page.useExactShares()
        page.startShare(ids.sam)
        for key in ["7", "0"] { page.pressShare(key) }
        page.commitShare()
        page.startShare(ids.amy)
        for key in ["6", "0"] { page.pressShare(key) }
        page.commitShare()
        XCTAssertTrue(page.splitView.myShareNegative)
        XCTAssertEqual(page.splitView.problem, "The shares add up to more than $120.00.")
        page.useEqualShares()
        XCTAssertEqual(page.splitView.problem, "")
        page.setPayer(ids.amy)
        XCTAssertTrue(page.someoneElsePaid)
        page.toggleSplit(ids.amy)
        XCTAssertFalse(page.someoneElsePaid, "taking the payer out gives the payment back to you")
    }

    func testSomeoneElsePayingTakesTheNeedForAnAccountAway() throws {
        let ids = try seeded()
        let page = model(try ledger())
        page.switchKind(to: "EXPENSE")
        page.categoryId = ids.groceries
        XCTAssertEqual(page.missingPanel, .account)
        page.toggleSplit(ids.sam)
        page.setPayer(ids.sam)
        XCTAssertNil(page.missingPanel)
        XCTAssertEqual(page.buttonKey, "entry.add")
    }

    func testTheDraftInputCarriesTheSplitAndTheSplitRefusalOpensItsPanel() throws {
        let ids = try seeded()
        let page = model(try ledger())
        for key in ["9", "0"] { page.press(key) }
        page.categoryId = ids.groceries
        page.accountId = ids.main
        page.toggleSplit(ids.sam)
        let input = page.input()
        XCTAssertEqual(input.splitWith, String(ids.sam))
        XCTAssertEqual(input.paidBy, 0)
        XCTAssertEqual(page.panel(forProblem: "SPLIT"), .split)
    }

    func testTheDraftIsKeptWithItsSplitAndOpeningWithAPersonAsksBeforeLeaving() throws {
        let ids = try seeded()
        let page = model(try ledger())
        page.toggleSplit(ids.sam)
        page.toggleSplit(ids.amy)
        page.setPayer(ids.amy)
        let kept = page.encoded
        let again = EntryDraftModel(view: try ledger(), editing: nil, draft: kept, today: Int(day), zone: "UTC")
        XCTAssertEqual(again.split.encoded, page.split.encoded)
        XCTAssertTrue(again.someoneElsePaid)
        let opened = model(try ledger(), split: SplitBridge.shared.none(), open: true)
        XCTAssertEqual(opened.panel, .split)
        XCTAssertTrue(opened.isDirty, "opened from a person's page, leaving asks")
        XCTAssertFalse(model(try ledger()).isDirty)
    }
}
