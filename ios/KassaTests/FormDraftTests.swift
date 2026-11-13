import XCTest
import KassaShared
@testable import Kassa

/// Typed input kept for the scene (`FormDrafts.swift`): every draft makes the round trip through the store's text, a draft that does not
/// decode or points at something gone is dropped, a form that closes leaves nothing behind, and no secret is ever in a draft.
final class FormDraftTests: XCTestCase {

    override func setUp() {
        super.setUp()
        KassaCore.shared.installDefaults()
    }

    /// A store that behaves like a scene: its text is what `SceneStorage` would hold, and a second store adopts it.
    private func rebuilt(_ first: FormDraftStore) -> FormDraftStore {
        let next = FormDraftStore()
        next.adopt(first.encoded)
        return next
    }

    private func roundTrip<D: FormDraft>(_ draft: D, key: String, file: StaticString = #filePath, line: UInt = #line) {
        let store = FormDraftStore()
        store.save(draft, key: key)
        let again = rebuilt(store)
        XCTAssertEqual(again.load(D.self, key: key), draft, file: file, line: line)
    }

    // MARK: Round trips

    func testEveryDraftRoundTrips() {
        roundTrip(PayCardDraft(choice: "OTHER", typed: "12.5", payFromId: 3, epochDay: 20500), key: "payCard:1")
        roundTrip(PersonPaymentDraft(kind: "PERSON_OUT", typed: "40", accountId: 2, epochDay: 20501), key: "personPayment:4")
        roundTrip(CorrectionDraft(typed: "100", below: true), key: "correction:-7")
        roundTrip(HoldingDraft(ticker: "VTI", shares: "1.5", price: "210.1", paid: "200"), key: "holding:9")
        roundTrip(PricesDraft(prices: ["1.5", "", "20"]), key: "prices:3")
        roundTrip(TradeDraft(buy: false, ticker: "VTI", shares: "2", price: "3", epochDay: 20502), key: "trade:3")
        roundTrip(TradeEditDraft(ticker: "VTI", shares: "2", price: "3", epochDay: 20502), key: "tradeEdit:5")
        roundTrip(AllocateDraft(buying: true, ticker: "VOO", shares: "1", price: "2", epochDay: 20503), key: "allocate:6")
        roundTrip(BillAmountDraft(typed: "85.2"), key: "billAmount:11")
        roundTrip(EditPersonDraft(name: "Sam", theyOwe: false, amount: "15"), key: "editPerson:2")
        roundTrip(BudgetDraft(every: "400", thisMonth: "450"), key: "budget:8")
        roundTrip(CategoryDraft(name: "Pets", iconKind: "emoji", iconValue: "x", color: -123456, counts: false, hidden: true), key: "category:-1")
    }

    func testFormsBuiltFromTheDomainRoundTrip() {
        let loan = LoanDraft.starting(today: 20500)
        let add = AddAccountDraft(
            kind: "LOAN", name: "Car", last4: "", balance: "", statementDay: "", dueDay: "", limit: "", payFromId: 4,
            maturityDay: 0, theyOwe: false, loan: loan
        )
        roundTrip(add, key: "addAccount:0")
        roundTrip(loan, key: "loanTerms:3")
        let bill = BillFields(BillSheetData(found: true, id: 0, name: "Rent", categoryId: 2, accountId: 3, day: 1, amount: "900", active: true))
        roundTrip(bill, key: "bill:0")
        roundTrip(EditAccountFields(AccountInput(type: "CHECKING", name: "Main", last4: "", balance: "5", statementDay: "", dueDay: "", limit: "", payFromId: 0)), key: "editAccount:1")
        let pay = LoanPaymentFields(LoanPaymentInput(
            amount: "1000", day: 20500, principal: "700", interest: "300", edit: "", fromId: 2, categoryId: 0, estimate: 300,
            principalCategoryId: 0, countOverride: "", escrowCategoryId: 0
        ))
        roundTrip(pay, key: "loanPayment:-2")
        let change = LoanChangeFields(LoanChangeInput(kind: "RATE", day: 20500, rate: "5.5", payment: "", termMonths: "", type: "", kept: "", basis: ""))
        roundTrip(change, key: "loanChange:2")
        let details = LoanDetailsFields(LoanDetailsInput(
            name: "Car", dueDay: "5", payFromId: 2, interestCategoryId: 0, counts: true, principalCategoryId: 0, escrow: "", fee: "",
            earlyCharge: "", original: "9000", startDay: 20000
        ))
        roundTrip(details, key: "loanDetails:2")
    }

    func testSeveralFormsAreKeptApartAndInOrder() {
        let store = FormDraftStore()
        store.save(BudgetDraft(every: "1", thisMonth: ""), key: "budget:1")
        store.save(BudgetDraft(every: "2", thisMonth: ""), key: "budget:2")
        store.save(BudgetDraft(every: "3", thisMonth: ""), key: "budget:1")
        let again = rebuilt(store)
        XCTAssertEqual(again.keys, ["budget:2", "budget:1"])
        XCTAssertEqual(again.load(BudgetDraft.self, key: "budget:1")?.every, "3")
    }

    func testTheStoreKeepsAtMostItsLimit() {
        let store = FormDraftStore()
        for index in 0..<(FormDraftStore.limit + 5) {
            store.save(BudgetDraft(every: String(index), thisMonth: ""), key: "budget:" + String(index))
        }
        XCTAssertEqual(store.keys.count, FormDraftStore.limit)
        XCTAssertNil(store.load(BudgetDraft.self, key: "budget:0"))
        XCTAssertNotNil(store.load(BudgetDraft.self, key: "budget:" + String(FormDraftStore.limit + 4)))
    }

    // MARK: Bad and stale drafts

    func testTextThatDoesNotDecodeIsDropped() {
        let junk = FormDraftStore()
        junk.adopt("not json at all")
        XCTAssertTrue(junk.keys.isEmpty)
        let newer = FormDraftStore()
        newer.adopt("{\"v\":2,\"keys\":[\"a:1\"],\"items\":{\"a:1\":\"{}\"}}")
        XCTAssertTrue(newer.keys.isEmpty)
        let empty = FormDraftStore()
        empty.adopt("")
        XCTAssertTrue(empty.keys.isEmpty)
    }

    func testADraftOfAnotherShapeIsDroppedWhenLoaded() {
        let store = FormDraftStore()
        store.save(BudgetDraft(every: "1", thisMonth: "2"), key: "x:1")
        XCTAssertNil(store.load(PayCardDraft.self, key: "x:1"))
        // It is gone, not kept to fail again.
        XCTAssertNil(store.load(BudgetDraft.self, key: "x:1"))
    }

    func testOnlyTheFirstAdoptionReadsTheText() {
        let first = FormDraftStore()
        first.save(BudgetDraft(every: "1", thisMonth: ""), key: "budget:1")
        let text = first.encoded
        let store = FormDraftStore()
        store.adopt(text)
        store.clear("budget:1")
        store.adopt(text)
        XCTAssertTrue(store.keys.isEmpty)
    }

    func testIdsThatAreGoneBecomeNothingChosen() {
        XCTAssertEqual(FormDraftIds.keep(5, exists: { $0 == 5 }), 5)
        XCTAssertEqual(FormDraftIds.keep(5, exists: { _ in false }), 0)
        XCTAssertEqual(FormDraftIds.keep(0, exists: { _ in true }), 0)
        XCTAssertEqual(FormDraftIds.keep(-3, exists: { _ in true }), 0)
    }

    func testADayFarFromTodayIsNotBelieved() {
        XCTAssertEqual(FormDraftIds.day(20510, fallback: 20500), 20510)
        XCTAssertEqual(FormDraftIds.day(0, fallback: 20500), 20500)
        XCTAssertEqual(FormDraftIds.day(1, fallback: 20500), 20500)
        XCTAssertEqual(FormDraftIds.day(99_999, fallback: 20500), 20500)
    }

    func testIdsAreCheckedAgainstTheLedger() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-draft-" + UUID().uuidString, isDirectory: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let store = try LedgerStore(directory: directory, keyStore: MemoryKeyStore())
        try store.startFresh(currency: "USD", categories: ["Groceries"])
        let account = AccountInput(type: "CHECKING", name: "Main", last4: "", balance: "5", statementDay: "", dueDay: "", limit: "", payFromId: 0)
        _ = try store.addAccount(account)
        let view = LedgerView(data: try store.export(), fallbackCurrency: nil)
        let known: Int64 = view.accountNames().isEmpty ? 0 : 1
        XCTAssertEqual(known, 1)
        let alive: (Int64) -> Bool = { view.accountChoice(id: $0) != nil }
        XCTAssertEqual(FormDraftIds.keep(99_999, exists: alive), 0)
        let anyId: Int64 = view.categoryChoices(kind: "EXPENSE", query: "").first?.id ?? 0
        XCTAssertGreaterThan(anyId, 0)
        XCTAssertEqual(FormDraftIds.keep(anyId, exists: { view.categoryChoice(id: $0) != nil }), anyId)
        XCTAssertEqual(FormDraftIds.keep(99_999, exists: { view.categoryChoice(id: $0) != nil }), 0)
    }

    // MARK: Cleared

    func testSavingOrCancellingClearsTheFormWhateverItsTarget() {
        let store = FormDraftStore()
        var persisted: [String] = []
        store.persist = { persisted.append($0) }
        store.save(PayCardDraft(choice: "OTHER", typed: "1", payFromId: 0, epochDay: 1), key: "payCard:1")
        store.save(PayCardDraft(choice: "OTHER", typed: "2", payFromId: 0, epochDay: 1), key: "payCard:2")
        store.save(BudgetDraft(every: "1", thisMonth: ""), key: "budget:1")
        store.clear(form: "payCard")
        XCTAssertEqual(store.keys, ["budget:1"])
        XCTAssertNil(store.load(PayCardDraft.self, key: "payCard:2"))
        // The scene's copy follows, so a rebuilt scene does not bring it back.
        let scene = FormDraftStore()
        scene.adopt(persisted.last ?? "")
        XCTAssertEqual(scene.keys, ["budget:1"])
        store.clear(form: "budget")
        XCTAssertEqual(persisted.last, "")
    }

    func testAFormPrefixIsNotAnotherFormsKey() {
        let store = FormDraftStore()
        store.save(BudgetDraft(every: "1", thisMonth: ""), key: "loan:1")
        store.save(BudgetDraft(every: "2", thisMonth: ""), key: "loanPayment:1")
        store.clear(form: "loan")
        XCTAssertEqual(store.keys, ["loanPayment:1"])
    }

    func testClearAllLeavesNothing() {
        let store = FormDraftStore()
        var last = "x"
        store.persist = { last = $0 }
        store.save(BudgetDraft(every: "1", thisMonth: ""), key: "budget:1")
        store.clearAll()
        XCTAssertTrue(store.keys.isEmpty)
        XCTAssertEqual(last, "")
    }

    func testSavingTheSameDraftAgainDoesNotWriteAgain() {
        let store = FormDraftStore()
        var writes = 0
        store.persist = { _ in writes += 1 }
        store.save(BudgetDraft(every: "1", thisMonth: ""), key: "budget:1")
        store.save(BudgetDraft(every: "1", thisMonth: ""), key: "budget:1")
        XCTAssertEqual(writes, 1)
    }

    // MARK: Secrets

    /// No draft has a field for a password, and the text a store keeps holds none: the draft types are the only things that can be saved.
    func testNoDraftCarriesASecret() throws {
        let drafts: [Encodable] = [
            PayCardDraft(choice: "", typed: "", payFromId: 0, epochDay: 0),
            PersonPaymentDraft(kind: "", typed: "", accountId: 0, epochDay: 0),
            CorrectionDraft(typed: "", below: false),
            HoldingDraft(ticker: "", shares: "", price: "", paid: ""),
            PricesDraft(prices: []),
            TradeDraft(buy: true, ticker: "", shares: "", price: "", epochDay: 0),
            TradeEditDraft(ticker: "", shares: "", price: "", epochDay: 0),
            AllocateDraft(buying: true, ticker: "", shares: "", price: "", epochDay: 0),
            BillAmountDraft(typed: ""),
            EditPersonDraft(name: "", theyOwe: true, amount: ""),
            BudgetDraft(every: "", thisMonth: ""),
            CategoryDraft(name: "", iconKind: "", iconValue: "", color: 0, counts: true, hidden: false),
            AddAccountDraft(
                kind: "", name: "", last4: "", balance: "", statementDay: "", dueDay: "", limit: "", payFromId: 0, maturityDay: 0,
                theyOwe: false, loan: LoanDraft.starting(today: 20500)
            ),
        ]
        for draft in drafts {
            let text = String(data: try JSONEncoder().encode(AnyEncodable(draft)), encoding: .utf8) ?? ""
            XCTAssertFalse(text.lowercased().contains("password"), text)
            XCTAssertFalse(text.lowercased().contains("secret"), text)
        }
    }

    private struct AnyEncodable: Encodable {
        let value: Encodable
        init(_ value: Encodable) { self.value = value }
        func encode(to encoder: Encoder) throws { try value.encode(to: encoder) }
    }
}
