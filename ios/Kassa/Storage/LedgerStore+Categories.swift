import Foundation
import KassaShared

/// Making and editing categories and subcategories, as Android's `CategoryRepository` does (`save`, `merge`). Every rule (names unique
/// among siblings, a parent that is a top-level category of the same kind, an icon, the colour a subcategory wears, what a merge may
/// target) is :domain's, asked through `LedgerView` and `CategoryIconBridge`; this file only runs the SQL, each change in ONE
/// transaction. Nothing is deleted: a category is archived (`hidden`) or merged into another.
extension LedgerStore {

    private func ledgerForCategories() throws -> LedgerView {
        return LedgerView(data: try readAll(), fallbackCurrency: nil)
    }

    /// Creates or updates a category or subcategory and returns its id. A saved top-level category's colour is also put on its
    /// subcategories, in the same transaction. Refuses (with `WriteRefusal`) what `CategoryRules` refuses.
    @discardableResult
    func saveCategory(_ input: CategoryInput) throws -> Int64 {
        return try db.transaction { () throws -> Int64 in
            let made = try self.ledgerForCategories()
            return try self.writeCategory(input, made: made)
        }
    }

    /// Adds a subcategory called [name] under the top-level category [parentId]: the parent's kind, icon, colour and daily setting.
    /// Returns its id.
    @discardableResult
    func addSubcategory(parentId: Int64, name: String) throws -> Int64 {
        return try db.transaction { () throws -> Int64 in
            let made = try self.ledgerForCategories()
            let input: CategoryInput = made.subcategoryInput(parentId: parentId, name: name)
            return try self.writeCategory(input, made: made)
        }
    }

    /// What `saveCategory` does, inside the caller's transaction.
    private func writeCategory(_ input: CategoryInput, made: LedgerView) throws -> Int64 {
        let problem: String = made.categoryProblem(input: input)
        if !problem.isEmpty { throw WriteRefusal(field: "NAME", message: problem) }
        let id: Int64
        if input.id == 0 {
            id = try insertCategory(input, made: made)
        } else {
            id = input.id
            try updateCategory(input, made: made)
        }
        let recolor: [String] = made.recolorIds(input: input)
        for text in recolor {
            guard let sub = Int64(text) else { continue }
            let statement = try db.prepare("UPDATE categories SET color = ? WHERE id = ?")
            try statement.bindInt(1, Int64(input.color))
            try statement.bindInt(2, sub)
            _ = try statement.step()
        }
        return id
    }

    private func insertCategory(_ input: CategoryInput, made: LedgerView) throws -> Int64 {
        let order = try nextSortOrder(table: "categories")
        return try insertRow(table: "categories") { column in
            return made.categoryCell(input: input, column: column, sortOrder: Int32(order))
        }
    }

    private func updateCategory(_ input: CategoryInput, made: LedgerView) throws {
        let rows = BackupRows.shared
        let columns = rows.columns(table: "categories")
        let kinds = rows.columnKinds(table: "categories")
        var sets: [String] = []
        var positions: [Int] = []
        for (index, name) in columns.enumerated() where CategoryIconBridge.shared.ownsColumn(column: Int32(index)) {
            sets.append("\"\(name)\" = ?")
            positions.append(index)
        }
        let statement = try db.prepare("UPDATE categories SET \(sets.joined(separator: ", ")) WHERE id = ?")
        for (slot, index) in positions.enumerated() {
            let value: String? = made.categoryCell(input: input, column: Int32(index), sortOrder: 0)
            try LedgerStore.bind(statement, Int32(slot + 1), kind: kinds[index], value: value)
        }
        try statement.bindInt(Int32(positions.count + 1), input.id)
        _ = try statement.step()
    }

    /// Moves every entry of category [from] into [into], then archives [from] (Android's `CategoryRepository.merge`). Refuses what
    /// `CategoryRules.mergeProblem` refuses.
    func mergeCategory(from: Int64, into: Int64) throws {
        try db.transaction { () throws -> Void in
            let made = try self.ledgerForCategories()
            let problem: String = made.mergeProblem(fromId: from, intoId: into)
            if !problem.isEmpty { throw WriteRefusal(message: problem) }
            let move = try self.db.prepare("UPDATE transactions SET categoryId = ? WHERE categoryId = ?")
            try move.bindInt(1, into)
            try move.bindInt(2, from)
            _ = try move.step()
            let archive = try self.db.prepare("UPDATE categories SET hidden = 1 WHERE id = ?")
            try archive.bindInt(1, from)
            _ = try archive.step()
        }
    }
}
