import Foundation
import KassaShared

/// Editing an account, a card or a person (batch Q): what the forms open with, their problems under each field, and the writes. The
/// rules are :domain's (through `LedgerView` and `EditBridge`); a write runs in one store transaction, then the figures are read again.
extension AppModel {

    /// The Edit account form's starting values (nil when the account is gone), worked out on the queue.
    func accountEditData(id: Int64, done: @escaping (AccountEditData?) -> Void) {
        queue.async {
            var found: AccountEditData?
            if let made = self.view {
                let data: AccountEditData = made.accountEdit(accountId: id)
                if data.found { found = data }
            }
            self.publish { done(found) }
        }
    }

    /// The Edit person form's starting values (nil when the person is gone). While Hide balances is on the size that blocks archiving is masked.
    func personEditData(id: Int64, done: @escaping (PersonEditData?) -> Void) {
        let hidden = hideAmounts
        queue.async {
            var found: PersonEditData?
            if let made = self.view {
                let data: PersonEditData = made.personEdit(personId: id, hidden: hidden)
                if data.found { found = data }
            }
            self.publish { done(found) }
        }
    }

    /// Every problem of the account being edited (the first one `AccountForm` and `EditRules` find); empty when it can be saved.
    func accountEditProblems(id: Int64, input: AccountInput, done: @escaping ([FieldProblemData]) -> Void) {
        queue.async {
            var found: [FieldProblemData] = []
            if let made = self.view {
                found = made.accountEditProblems(accountId: id, input: input)
            }
            self.publish { done(found) }
        }
    }

    /// Every problem of the person being edited; empty when it can be saved.
    func personEditProblems(id: Int64, name: String, theyOwe: Bool, amount: String, done: @escaping ([FieldProblemData]) -> Void) {
        queue.async {
            var found: [FieldProblemData] = []
            if let made = self.view {
                let taken: [String] = made.otherPersonNames(personId: id)
                found = PersonBridge.shared.problems(
                    name: name, theyOwe: theyOwe, amount: amount, takenNames: taken, currency: made.currencyCode()
                )
            }
            self.publish { done(found) }
        }
    }

    func updateAccount(id: Int64, input: AccountInput, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.updateAccount(id: id, input: input)
            return id
        }, done: offering(done))
    }

    func setAccountArchived(id: Int64, archived: Bool, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.setAccountArchived(id: id, archived: archived)
            return id
        }, done: done)
    }

    func updatePerson(id: Int64, name: String, theyOwe: Bool, amount: String, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.updatePerson(id: id, name: name, theyOwe: theyOwe, amount: amount)
            return id
        }, done: done)
    }

    func setPersonArchived(id: Int64, archived: Bool, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.setPersonArchived(id: id, archived: archived)
            return id
        }, done: done)
    }
}
