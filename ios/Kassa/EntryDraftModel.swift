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
    case split
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
    /// The split: who shares an expense, who paid, and the shares (all of it :domain's `SplitRules`, kept as `SplitState`).
    @Published var split: SplitState
    /// The person whose exact share the pad is typing (0 for none), and what is typed so far.
    @Published var sharePerson: Int64 = 0
    @Published var shareTyped = ""

    private var initial = ""
    /// Opened from a person's "Split a transaction": leaving asks even before anything is changed, as on Android.
    private let opensSplit: Bool

    init(
        view: LedgerView, editing: EntryEditData?, draft: String, today: Int, zone: String,
        split startSplit: SplitState? = nil, openSplit: Bool = false
    ) {
        self.view = view
        self.editing = editing
        self.today = today
        self.zone = zone
        self.symbol = view.currencySymbol()
        self.decimalMark = String(EntryBridge.shared.display(typed: "0.").dropFirst())
        self.opensSplit = openSplit
        self.split = startSplit ?? SplitBridge.shared.none()
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
        panel = openSplit ? .split : .amount
        initial = encoded
        if editing == nil && !draft.isEmpty { restore(draft) }
    }

    // MARK: Draft kept across a scene's recreation

    private static let separator = "\u{1F}"

    /// What is typed, as one text a scene can keep (`SceneStorage`): kind, amount, ids, day and note. A new entry only.
    var encoded: String {
        let parts: [String] = [
            "2", kind, typed, String(categoryId), String(accountId), String(toAccountId), String(epochDay), note, split.encoded,
        ]
        return parts.joined(separator: EntryDraftModel.separator)
    }

    private func restore(_ text: String) {
        let parts = text.components(separatedBy: EntryDraftModel.separator)
        let current = parts.count == 9 && parts[0] == "2"
        let older = parts.count == 8 && parts[0] == "1"
        guard current || older else { return }
        let kinds = ["EXPENSE", "INCOME", "TRANSFER"]
        guard kinds.contains(parts[1]) else { return }
        kind = parts[1]
        typed = AmountPadText.clean(parts[2])
        categoryId = Int64(parts[3]) ?? 0
        accountId = Int64(parts[4]) ?? 0
        toAccountId = Int64(parts[5]) ?? 0
        epochDay = Int(parts[6]) ?? today
        note = EntryBridge.shared.typedNote(raw: parts[7])
        if current { split = SplitBridge.shared.decode(text: parts[8]) }
        panel = missingPanel ?? .amount
    }

    /// True once anything differs from how the page opened; leaving then asks first.
    var isDirty: Bool { encoded != initial || opensSplit }

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
        let name = missingName
        if name == "CATEGORY" { return .category }
        if name == "ACCOUNT" { return .account }
        if name == "TO" { return .to }
        return nil
    }

    /// The `EntryField` name still empty; when someone else paid, no account of yours is needed.
    private var missingName: String {
        return EntryBridge.shared.missing(kind: kind, categoryId: categoryId, accountId: accountId, toAccountId: toAccountId, split: split)
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

    // MARK: Split

    /// An expense is split with someone: the shares, who paid and the sentence about it are `splitView`'s.
    var isShared: Bool { return SplitBridge.shared.isShared(state: split, kind: kind) }

    /// Someone else paid it: the entry moves none of your accounts, and the account tile gives way to "Paid by".
    var someoneElsePaid: Bool { return SplitBridge.shared.someoneElsePaid(state: split, kind: kind) }

    /// The Split row is offered for an expense while there is someone to split with (or it already is split).
    var offersSplit: Bool { return kind == "EXPENSE" && (view.hasSplitPeople() || isShared) }

    var splitView: SplitViewData {
        return SplitBridge.shared.view(state: split, kind: kind, total: cents, currency: view.currencyCode())
    }

    /// The people to pick from, A to Z (an archived person only while already in the split).
    var splitPeople: [SplitPersonData] { return view.splitPeople(selected: split.people) }

    func isSelected(_ personId: Int64) -> Bool { return SplitBridge.shared.isSelected(state: split, personId: personId) }

    func toggleSplit(_ personId: Int64) {
        split = SplitBridge.shared.toggle(state: split, personId: personId)
        error = nil
    }

    func useEqualShares() {
        split = SplitBridge.shared.useEqual(state: split)
        error = nil
    }

    func useExactShares() {
        split = SplitBridge.shared.useExact(state: split, total: cents)
        error = nil
    }

    /// 0 is you, who paid when nobody else did.
    func setPayer(_ personId: Int64) {
        split = SplitBridge.shared.payer(state: split, personId: personId)
        error = nil
    }

    func startShare(_ personId: Int64) {
        sharePerson = personId
        shareTyped = ""
    }

    func pressShare(_ key: String) {
        shareTyped = EntryBridge.shared.press(typed: shareTyped, key: key)
    }

    /// Leaves the pad without taking what was typed.
    func cancelShare() {
        sharePerson = 0
        shareTyped = ""
    }

    /// Takes the share typed (an empty pad keeps the old one) and goes back to the split.
    func commitShare() {
        if sharePerson > 0 {
            split = SplitBridge.shared.typedShare(state: split, personId: sharePerson, typed: shareTyped)
        }
        sharePerson = 0
        shareTyped = ""
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
            categoryId: categoryId, epochMillis: moment, note: note, split: split
        )
    }

    /// The panel to open for a refusal about [field] (an `EntryField` name).
    func panel(forProblem field: String) -> EntryPanel {
        if field == "CATEGORY" { return .category }
        if field == "ACCOUNT" { return .account }
        if field == "TO" { return .to }
        if field == "SPLIT" { return .split }
        return .amount
    }

    /// The text of the one ink button under the pad: what comes next until everything is set, then Add or Save.
    var buttonKey: String {
        let next = missingName
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
