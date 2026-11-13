import XCTest
import KassaShared
@testable import Kassa

/// Creating, renaming, colouring, archiving and merging categories through the store, read back through `LedgerView`. The rules are
/// :domain's (JVM-tested); these check that Swift runs them in one transaction and stores what the editor then shows.
final class CategoryEditTests: XCTestCase {

    private var directory: URL!
    private var store: LedgerStore!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-cat-" + UUID().uuidString, isDirectory: true)
        store = try LedgerStore(directory: directory, keyStore: MemoryKeyStore())
        try store.startFresh(currency: "USD", categories: [])
    }

    override func tearDownWithError() throws {
        store = nil
        try? FileManager.default.removeItem(at: directory)
    }

    private func ledger() throws -> LedgerView {
        return LedgerView(data: try store.export(), fallbackCurrency: nil)
    }

    private func make(_ name: String, kind: String = "EXPENSE", color: Int32? = nil) throws -> Int64 {
        let blank = try ledger().categoryNew(kind: kind).input
        let input = CategoryInput(
            id: 0, kind: kind, parentId: 0, name: name, iconKind: "emoji", iconValue: "x",
            color: color ?? blank.color, counts: true, hidden: false
        )
        return try store.saveCategory(input)
    }

    private func refused(_ body: () throws -> Void) -> Bool {
        do {
            try body()
        } catch is WriteRefusal {
            return true
        } catch {
            return false
        }
        return false
    }

    func testNewCategoriesGetDifferentColoursWhileOneIsFree() throws {
        let a = try make("Food")
        let b = try make("Fuel")
        let data = try ledger()
        let first = data.categoryEdit(categoryId: a).input.color
        let second = data.categoryEdit(categoryId: b).input.color
        XCTAssertNotEqual(first, second)
    }

    func testDuplicateNameIsRefusedAndNothingIsStored() throws {
        _ = try make("Food")
        let before = try ledger().categoryList(kind: "EXPENSE").active.count
        let input = try ledger().categoryNew(kind: "EXPENSE").input
        let dup = CategoryInput(
            id: 0, kind: "EXPENSE", parentId: 0, name: "food", iconKind: "emoji", iconValue: "x",
            color: input.color, counts: true, hidden: false
        )
        XCTAssertFalse(try ledger().categoryProblem(input: dup).isEmpty)
        XCTAssertTrue(refused { _ = try store.saveCategory(dup) })
        XCTAssertEqual(try ledger().categoryList(kind: "EXPENSE").active.count, before)
    }

    func testRenameAndRecolourReachSubcategories() throws {
        let top = try make("Food")
        let sub = try store.addSubcategory(parentId: top, name: "Lunch")
        let edit = try ledger().categoryEdit(categoryId: top)
        let other = edit.colors.first { $0.color != edit.input.color }
        let color = try XCTUnwrap(other).color
        let changed = CategoryInput(
            id: top, kind: "EXPENSE", parentId: 0, name: "Eating", iconKind: "emoji", iconValue: "x",
            color: color, counts: true, hidden: false
        )
        _ = try store.saveCategory(changed)
        let data = try ledger()
        XCTAssertEqual(data.categoryName(id: top), "Eating")
        XCTAssertEqual(data.categoryEdit(categoryId: sub).input.color, color)
    }

    func testSubcategoryNamesAreUniqueUnderTheirParent() throws {
        let top = try make("Food")
        _ = try store.addSubcategory(parentId: top, name: "Lunch")
        XCTAssertFalse(try ledger().subcategoryProblem(parentId: top, name: "lunch").isEmpty)
        XCTAssertTrue(refused { _ = try store.addSubcategory(parentId: top, name: "Lunch") })
        XCTAssertEqual(try ledger().categoryEdit(categoryId: top).subs.count, 1)
    }

    func testArchiveHidesFromTheActiveListAndRestoreBringsItBack() throws {
        let id = try make("Food")
        let archived = try ledger().categoryInputWith(categoryId: id, name: "Food", hidden: true)
        _ = try store.saveCategory(archived)
        var list = try ledger().categoryList(kind: "EXPENSE")
        XCTAssertEqual(list.active.count, 0)
        XCTAssertEqual(list.archived.count, 1)
        let back = try ledger().categoryInputWith(categoryId: id, name: "Food", hidden: false)
        _ = try store.saveCategory(back)
        list = try ledger().categoryList(kind: "EXPENSE")
        XCTAssertEqual(list.active.count, 1)
    }

    func testMergeMovesEntriesAndArchivesTheSource() throws {
        let from = try make("Food")
        let into = try make("Groceries")
        let account = try store.addAccount(
            AccountInput(type: "CASH", name: "Wallet", last4: "", balance: "", statementDay: "", dueDay: "", limit: "", payFromId: 0)
        )
        let millis: Int64 = 20522 * 86_400_000 + 43_200_000
        _ = try store.addEntry(EntryInput(
            id: 0, kind: "EXPENSE", amount: 500, accountId: account, toAccountId: 0, categoryId: from, epochMillis: millis, note: ""
        ))
        try store.mergeCategory(from: from, into: into)
        let data = try store.export()
        let moved = data.transactions.filter { $0.categoryId?.int64Value == into }
        XCTAssertEqual(moved.count, 1)
        XCTAssertEqual(try ledger().categoryList(kind: "EXPENSE").archived.count, 1)
    }

    func testEveryCatalogIconHasASymbol() {
        let names = CategoryIconBridge.shared.icons(query: "")
        XCTAssertFalse(names.isEmpty)
        for name in names where name != "sell" {
            XCTAssertNotEqual(IconSymbols.symbol(for: name), "tag", name)
        }
    }

    // MARK: Budget fields of the editor

    private var today: Int32 { return Int32(KassaDates.todayEpochDay()) }

    private func input(_ id: Int64, _ name: String, kind: String = "EXPENSE") throws -> CategoryInput {
        let found = try ledger().categoryEdit(categoryId: id).input
        return CategoryInput(
            id: id, kind: kind, parentId: found.parentId, name: name, iconKind: found.iconKind, iconValue: found.iconValue,
            color: found.color, counts: found.counts, hidden: found.hidden
        )
    }

    private func plan(_ input: CategoryInput, every: String, thisMonth: String = "") throws -> CategorySavePlanData {
        return try ledger().categorySavePlan(input: input, every: every, thisMonth: thisMonth, todayEpochDay: today)
    }

    func testANewCategoryIsSavedWithItsBudgetInOneTransaction() throws {
        let blank = try ledger().categoryNew(kind: "EXPENSE").input
        let fresh = CategoryInput(
            id: 0, kind: "EXPENSE", parentId: 0, name: "Fuel", iconKind: "emoji", iconValue: "x",
            color: blank.color, counts: true, hidden: false
        )
        let made = try plan(fresh, every: "120")
        XCTAssertTrue(made.problems.isEmpty)
        let id = try store.saveCategory(fresh, budgetChanges: made.changes)
        let stored = try ledger().budgetEdit(categoryId: id, todayEpochDay: today)
        XCTAssertEqual(stored.everyInput, "120")
        XCTAssertEqual(stored.monthInput, "")
    }

    func testEditingChangesAndRemovesBothBudgetRows() throws {
        let id = try make("Food")
        let named = try input(id, "Food")
        let set = try plan(named, every: "250", thisMonth: "300")
        try store.saveCategory(named, budgetChanges: set.changes)
        var stored = try ledger().budgetEdit(categoryId: id, todayEpochDay: today)
        XCTAssertEqual(stored.everyInput, "250")
        XCTAssertEqual(stored.monthInput, "300")
        let cleared = try plan(named, every: "", thisMonth: "")
        try store.saveCategory(named, budgetChanges: cleared.changes)
        stored = try ledger().budgetEdit(categoryId: id, todayEpochDay: today)
        XCTAssertEqual(stored.everyInput, "")
        XCTAssertEqual(stored.monthInput, "")
    }

    func testABadAmountIsAProblemByFieldAndNothingIsPlanned() throws {
        let id = try make("Food")
        let bad = try plan(try input(id, "Food"), every: "0", thisMonth: "abc")
        XCTAssertEqual(bad.problems.map { $0.field }, ["EVERY_MONTH", "THIS_MONTH"])
        XCTAssertTrue(bad.changes.isEmpty)
    }

    func testAFailureAfterTheWritesRollsTheCategoryAndItsBudgetBack() throws {
        let id = try make("Food")
        let renamed = try input(id, "Eating")
        let set = try plan(renamed, every: "400")
        struct Boom: Error {}
        store.afterInsertHook = { throw Boom() }
        XCTAssertThrowsError(try store.saveCategory(renamed, budgetChanges: set.changes))
        let blank = try ledger().categoryNew(kind: "EXPENSE").input
        let fresh = CategoryInput(
            id: 0, kind: "EXPENSE", parentId: 0, name: "Fuel", iconKind: "emoji", iconValue: "x",
            color: blank.color, counts: true, hidden: false
        )
        XCTAssertThrowsError(try store.saveCategory(fresh, budgetChanges: try plan(fresh, every: "50").changes))
        store.afterInsertHook = nil
        XCTAssertEqual(try ledger().categoryEdit(categoryId: id).input.name, "Food")
        XCTAssertEqual(try ledger().budgetEdit(categoryId: id, todayEpochDay: today).everyInput, "")
        XCTAssertEqual(try ledger().categoryList(kind: "EXPENSE").active.count, 1)
    }

    func testARefusedBudgetAmountLeavesTheRenameUnsaved() throws {
        let id = try make("Food")
        let renamed = try input(id, "Eating")
        let wrong = [BudgetChangeData(categoryId: 0, month: 0, amount: 0, isRemoval: false)]
        XCTAssertTrue(refused { _ = try store.saveCategory(renamed, budgetChanges: wrong) })
        XCTAssertEqual(try ledger().categoryEdit(categoryId: id).input.name, "Food")
    }

    func testIncomeCategoriesAndSubcategoriesHaveNoBudgetFields() throws {
        let salary = try make("Salary", kind: "INCOME")
        let top = try make("Food")
        let sub = try store.addSubcategory(parentId: top, name: "Lunch")
        XCTAssertFalse(try ledger().categoryEdit(categoryId: salary).hasBudget)
        XCTAssertFalse(try ledger().categoryEdit(categoryId: sub).hasBudget)
        let edit = try ledger().categoryEdit(categoryId: top)
        XCTAssertTrue(edit.hasBudget)
        XCTAssertTrue(edit.hasMonthBudget)
        XCTAssertTrue(try ledger().categoryNew(kind: "EXPENSE").hasBudget)
        XCTAssertFalse(try ledger().categoryNew(kind: "EXPENSE").hasMonthBudget)
    }
}
