import Foundation
import KassaShared

/// The input the bottom panel of the entry page shows.
enum EntryPanel: String {
    case amount
    case category
    case subcategory
    case account
    case to
    case date
}

/// Everything on the entry page: what is typed, which panel is open, and the steps from one input to the next. Every rule (what is
/// missing, what a switch of kind keeps, what the pad types, what is bounded) is :domain's, asked through `EntryBridge`; this only
/// holds the values the screen shows. Ids are 0 for "none" (nothing is preselected: a new entry starts with no account, and nothing
/// guesses one from a category or merchant).
final class EntryDraftModel: ObservableObject {

    let view: LedgerView
    let editing: EntryEditData?
    let today: Int
    let zone: String
    /// The ledger currency's bare symbol, shown beside the typed amount.
    let symbol: String
    /// The phone's decimal mark, for the pad's point key.
    let decimalMark: String

    @Published var kind: String
    @Published var typed: String
    @Published var categoryId: Int64
    @Published var accountId: Int64
    @Published var toAccountId: Int64
    @Published var epochDay: Int
    @Published private(set) var note: String
    @Published var panel: EntryPanel
    @Published var error: String?
    /// The search text and kind chip of the open picker; cleared whenever the panel changes.
    @Published var query = ""
    @Published var filter = "ALL"

    private var initial = ""

    init(view: LedgerView, editing: EntryEditData?, draft: String, today: Int, zone: String) {
        self.view = view
        self.editing = editing
        self.today = today
        self.zone = zone
        self.symbol = view.currencySymbol()
        self.decimalMark = String(EntryBridge.shared.display(typed: "0.").dropFirst())
        if let entry = editing {
            kind = entry.kind
            typed = EntryBridge.shared.typedOf(cents: entry.amount)
            categoryId = entry.categoryId
            accountId = entry.accountId
            toAccountId = entry.toAccountId
            epochDay = Int(EntryBridge.shared.dayOf(epochMillis: entry.epochMillis, zoneId: zone))
            note = entry.note
        } else {
            kind = "EXPENSE"
            typed = ""
            categoryId = 0
            accountId = 0
            toAccountId = 0
            epochDay = today
            note = ""
        }
        panel = .amount
        initial = encoded
        if editing == nil && !draft.isEmpty { restore(draft) }
    }

    // MARK: Draft kept across a scene's recreation

    private static let separator = "\u{1F}"

    /// What is typed, as one text a scene can keep (`SceneStorage`): kind, amount, ids, day and note. A new entry only.
    var encoded: String {
        let parts: [String] = [
            "1", kind, typed, String(categoryId), String(accountId), String(toAccountId), String(epochDay), note,
        ]
        return parts.joined(separator: EntryDraftModel.separator)
    }

    private func restore(_ text: String) {
        let parts = text.components(separatedBy: EntryDraftModel.separator)
        guard parts.count == 8, parts[0] == "1" else { return }
        let kinds = ["EXPENSE", "INCOME", "TRANSFER"]
        guard kinds.contains(parts[1]) else { return }
        kind = parts[1]
        typed = AmountPadText.clean(parts[2])
        categoryId = Int64(parts[3]) ?? 0
        accountId = Int64(parts[4]) ?? 0
        toAccountId = Int64(parts[5]) ?? 0
        epochDay = Int(parts[6]) ?? today
        note = EntryBridge.shared.typedNote(raw: parts[7])
        panel = missingPanel ?? .amount
    }

    /// True once anything differs from how the page opened; leaving then asks first.
    var isDirty: Bool { encoded != initial }

    // MARK: Typing

    var cents: Int64 { EntryBridge.shared.cents(typed: typed) }

    var amountShown: String { EntryBridge.shared.display(typed: typed) }

    func press(_ key: String) {
        typed = EntryBridge.shared.press(typed: typed, key: key)
        error = nil
    }

    func typeNote(_ raw: String) {
        let filtered = EntryBridge.shared.typedNote(raw: raw)
        if filtered != note { note = filtered }
        error = nil
    }

    // MARK: Steps

    /// The next required input still empty, in the order the tiles read; nil when the entry has all it needs.
    var missingPanel: EntryPanel? {
        let name = EntryBridge.shared.missing(kind: kind, categoryId: categoryId, accountId: accountId, toAccountId: toAccountId)
        if name == "CATEGORY" { return .category }
        if name == "ACCOUNT" { return .account }
        if name == "TO" { return .to }
        return nil
    }

    func show(_ next: EntryPanel) {
        query = ""
        filter = "ALL"
        error = nil
        panel = next
    }

    /// After a pick: the next empty input, then the pad with the one ink button under it.
    func advance() {
        show(missingPanel ?? .amount)
    }

    func switchKind(to next: String) {
        if next == kind { return }
        let isCard = accountId > 0 && view.accountChoice(id: accountId)?.type == "CREDIT_CARD"
        let kept = EntryBridge.shared.switched(
            kind: kind, to: next, categoryId: categoryId, accountId: accountId, toAccountId: toAccountId, accountIsCard: isCard
        )
        let ids = kept.components(separatedBy: ",").map { Int64($0) ?? 0 }
        if ids.count == 3 {
            categoryId = ids[0]
            accountId = ids[1]
            toAccountId = ids[2]
        }
        kind = next
        show(typed.isEmpty ? .amount : (missingPanel ?? .amount))
    }

    /// A category was picked. A top-level one with subcategories opens them; any other choice is the answer.
    func pick(category choice: CategoryChoiceData) {
        categoryId = choice.id
        if choice.hasSubcategories && choice.parentName.isEmpty && query.isEmpty {
            show(.subcategory)
        } else {
            advance()
        }
    }

    func pickSubcategory(_ id: Int64) {
        categoryId = id
        advance()
    }

    func pick(account id: Int64) {
        if panel == .to {
            toAccountId = id
        } else {
            accountId = id
            if toAccountId == id { toAccountId = 0 }
        }
        advance()
    }

    func chooseDay(_ day: Int) {
        epochDay = day
        error = nil
    }

    // MARK: Saving

    /// The entry as the store takes it; the moment is the picked day with the right time of day (`EntryRules.savedMillis`).
    func input(now: Date = Date()) -> EntryInput {
        let millis = Int64(now.timeIntervalSince1970 * 1000)
        let moment = EntryBridge.shared.savedMillis(
            epochDay: Int32(epochDay), zoneId: zone, nowMillis: millis, originalMillis: editing?.epochMillis ?? 0
        )
        return EntryInput(
            id: editing?.id ?? 0, kind: kind, amount: cents, accountId: accountId, toAccountId: toAccountId,
            categoryId: categoryId, epochMillis: moment, note: note
        )
    }

    /// The panel to open for a refusal about [field] (an `EntryField` name).
    func panel(forProblem field: String) -> EntryPanel {
        if field == "CATEGORY" { return .category }
        if field == "ACCOUNT" { return .account }
        if field == "TO" { return .to }
        return .amount
    }

    /// The text of the one ink button under the pad: what comes next until everything is set, then Add or Save.
    var buttonKey: String {
        let next = EntryBridge.shared.missing(kind: kind, categoryId: categoryId, accountId: accountId, toAccountId: toAccountId)
        if next == "CATEGORY" { return "entry.next.category" }
        if next == "TO" { return "entry.next.to" }
        if next == "ACCOUNT" {
            if kind == "TRANSFER" { return "entry.next.from" }
            return kind == "INCOME" ? "entry.next.into" : "entry.next.paidWith"
        }
        return editing == nil ? "entry.add" : "entry.save"
    }
}

/// A restored amount is only ever what the pad could have typed.
enum AmountPadText {
    static func clean(_ text: String) -> String {
        var result = ""
        for character in text {
            result = EntryBridge.shared.press(typed: result, key: String(character))
        }
        return result
    }
}
