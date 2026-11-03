import Foundation
import KassaShared

/// How a write ended, handed to the main thread.
enum WriteOutcome: Equatable {
    /// Saved; the figures are already read again. The id is the new row's (the entry's own for an edit).
    case saved(Int64)
    /// A rule refused it: the input it is about (an `EntryField`, `AccountField` or `PersonField` name, or "") and the sentence.
    case refused(field: String, message: String)
    /// Anything else went wrong: a plain sentence, never raw error text.
    case failed(String)
}

/// The entry page that is open. [editing] is nil for a new entry; [amountText] is the amount as Money.format writes it (the mask
/// while amounts are hidden), for the delete question.
struct EntryPageRequest: Identifiable {
    let id = UUID()
    let editing: EntryEditData?
    let amountText: String
    /// An unfinished new entry to carry on with (`EntryDraftModel.encoded`), or "".
    let draft: String
}

/// The write path of `AppModel`: one transaction on the one queue, then the figures again, then the answer on the main thread.
extension AppModel {

    private static let failedText = String(localized: "write.failed")

    /// Runs [work] on the store, reads the figures again, and calls [done] on the main thread.
    private func perform(_ work: @escaping (LedgerStore) throws -> Int64, done: @escaping (WriteOutcome) -> Void) {
        let given = inputs()
        queue.async {
            let outcome = self.run(work, given)
            self.publish { done(outcome) }
        }
    }

    private func run(_ work: (LedgerStore) throws -> Int64, _ given: Inputs) -> WriteOutcome {
        guard let opened = store else { return .failed(AppModel.failedText) }
        do {
            let id = try work(opened)
            try load(opened, given)
            return .saved(id)
        } catch let refusal as WriteRefusal {
            return .refused(field: refusal.field, message: refusal.message)
        } catch {
            return .failed(AppModel.failedText)
        }
    }

    func addEntry(_ input: EntryInput, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in try opened.addEntry(input) }, done: done)
    }

    func updateEntry(_ input: EntryInput, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.updateEntry(input)
            return input.id
        }, done: done)
    }

    func deleteEntry(id: Int64, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.deleteEntry(id: id)
            return id
        }, done: done)
    }

    func addAccount(_ input: AccountInput, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in try opened.addAccount(input) }, done: done)
    }

    func addPerson(name: String, theyOwe: Bool, amount: String, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in try opened.addPerson(name: name, theyOwe: theyOwe, amount: amount) }, done: done)
    }

    /// Every field's problem of an account being added (so the form can show each under its field); empty when it can be saved.
    func accountProblems(_ input: AccountInput, done: @escaping ([FieldProblemData]) -> Void) {
        queue.async {
            var found: [FieldProblemData] = []
            if let made = self.view {
                let currency = made.currencyCode()
                found = AccountBridge.shared.problems(input: input, takenNames: made.accountNames(), currency: currency)
            }
            self.publish { done(found) }
        }
    }

    /// Every field's problem of a person being added; empty when it can be saved.
    func personProblems(name: String, theyOwe: Bool, amount: String, done: @escaping ([FieldProblemData]) -> Void) {
        queue.async {
            var found: [FieldProblemData] = []
            if let made = self.view {
                let currency = made.currencyCode()
                found = PersonBridge.shared.problems(
                    name: name, theyOwe: theyOwe, amount: amount, takenNames: made.personNames(), currency: currency
                )
            }
            self.publish { done(found) }
        }
    }

    // MARK: The entry page

    /// Opens the entry page on a new entry, or on [draft], the unfinished one a scene kept (see `EntryDraftModel.encoded`).
    func openAdd(draft: String = "") {
        entryPage = EntryPageRequest(editing: nil, amountText: "", draft: draft)
    }

    /// Opens the entry page on entry [id] when the page may edit it; any other entry does nothing yet.
    func openEntry(id: Int64) {
        let hidden = hideAmounts
        queue.async {
            guard let made = self.view, let edit = made.entryEdit(id: id) else { return }
            let amount = made.entryAmountText(id: id, hidden: hidden)
            self.publish { self.entryPage = EntryPageRequest(editing: edit, amountText: amount, draft: "") }
        }
    }

    func closeEntryPage() {
        entryPage = nil
    }
}
