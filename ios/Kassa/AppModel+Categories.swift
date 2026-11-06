import Foundation
import KassaShared

/// Writing categories and subcategories (batch Q). The rules are :domain's (through `LedgerView`); a write runs in one store
/// transaction, then the figures are read again. The editor's reads (`categoryList`, `categoryEdit`, `categoryNew`) are pure and
/// are asked of `picks` on the main thread, like the pickers.
extension AppModel {

    /// Creates or updates a category or subcategory; the outcome's id is its own.
    func saveCategory(_ input: CategoryInput, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in try opened.saveCategory(input) }, done: done)
    }

    /// Adds a subcategory under the top-level category [parentId] (the picker's "+ New"); the outcome's id is the new one.
    func addSubcategory(parentId: Int64, name: String, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in try opened.addSubcategory(parentId: parentId, name: name) }, done: done)
    }

    /// Moves every entry of [from] into [into], then archives [from].
    func mergeCategory(from: Int64, into: Int64, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.mergeCategory(from: from, into: into)
            return into
        }, done: done)
    }

    /// The "+ New" action the category choosers take: adds a subcategory under a parent and hands back the outcome.
    var subcategoryAdder: (Int64, String, @escaping (WriteOutcome) -> Void) -> Void {
        return { parent, name, done in self.addSubcategory(parentId: parent, name: name, done: done) }
    }
}
