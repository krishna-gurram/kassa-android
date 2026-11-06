import Foundation
import SwiftUI
import KassaShared

/// The message after "Reset to A to Z", with what Undo puts back. [sort] is the sort that was in force (an `AccountSort` name) and
/// [order] the shown order then (`AccountsData.order`), which is the arrangement when the sort was Your order.
struct OrderMessage: Equatable, Identifiable {
    let id = UUID()
    let sort: String
    let order: String
}

/// Batch R: Accounts > Order and Arrange, Overview's pinned accounts, and the small Settings items (theme, the tab Kassa opens on, the
/// budget month, Account to person). Every rule is :domain's, asked through `AccountOrderBridge`, `PhoneChoicesBridge` and `LedgerView`;
/// this only keeps the choices (UserDefaults for the phone's own, the store for what a backup carries) and reads the figures again.
extension AppModel {

    var phone: PhonePrefs {
        return PhonePrefs(defaults: defaults)
    }

    /// Hands the phone's own choices to a ledger before it works its figures out. Queue only.
    func applyPhonePrefs(_ made: LedgerView) {
        let prefs = PhonePrefs(defaults: defaults)
        made.accountSort = prefs.accountSort
        made.pinnedKeys = prefs.pins
    }

    // MARK: Order

    /// Name, Balance or Due date: sets that sort and drops any arrangement. (Your order goes through `startArranging`.)
    func chooseSort(_ name: String) {
        phone.accountSort = AccountOrderBridge.shared.sortOf(stored: name)
        perform({ opened in
            try opened.setAccountArrangement(nil)
            return 0
        }, done: { _ in })
    }

    /// Stores [text] (a shown order from `AccountOrderBridge`) as the arrangement and makes Your order the sort. Saved at once.
    func saveArrangement(_ text: String) {
        phone.accountSort = "CUSTOM"
        perform({ opened in
            try opened.setAccountArrangement(text)
            return 0
        }, done: { _ in })
    }

    /// Your order: starts from what is shown now (whatever it was sorted by) and opens Arrange mode.
    func startArranging() {
        if let data = accounts, data.sort != "CUSTOM", !data.order.isEmpty {
            saveArrangement(data.order)
        }
        arranging = true
    }

    func stopArranging() {
        arranging = false
    }

    /// Reset to A to Z: applies at once and offers Undo.
    func resetOrder() {
        let sort: String = accounts?.sort ?? "NAME"
        let order: String = accounts?.order ?? ""
        arranging = false
        chooseSort("NAME")
        orderMessage = OrderMessage(sort: sort, order: order)
    }

    /// Undo of a reset: the previous arrangement when it was Your order, else the previous sort.
    func undoOrder() {
        guard let message = orderMessage else { return }
        orderMessage = nil
        if message.sort == "CUSTOM" && !message.order.isEmpty {
            saveArrangement(message.order)
        } else {
            chooseSort(message.sort)
        }
    }

    func dismissOrderMessage(_ id: UUID) {
        if orderMessage?.id == id { orderMessage = nil }
    }

    // MARK: Folded groups

    func saveFolded(_ keys: [String]) {
        phone.folded = keys
    }

    // MARK: Settings

    func setTheme(_ name: String) {
        phone.theme = name
        theme = phone.theme
    }

    func setHomeTab(_ name: String) {
        phone.homeTab = name
        homeTab = phone.homeTab
    }

    /// The first day of the budget month (kept to 1 to 28); the figures are read again.
    func setMonthStartDay(_ day: Int) {
        perform({ opened in
            try opened.setMonthStartDay(day)
            return 0
        }, done: { _ in })
    }

    /// Ticks or unticks an Overview tile; the first change saves the whole set (the rule is `PinRules.toggled`).
    func togglePin(_ key: String) {
        queue.async {
            guard let made = self.view else { return }
            let next: String = made.pinToggled(key: key)
            PhonePrefs(defaults: self.defaults).pins = next
            self.publish { self.refreshFigures() }
        }
    }

    /// Account to person: turns the account into a person with the same balance and archives it.
    func convertAccount(id: Int64, name: String, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in try opened.convertAccountToPerson(accountId: id, name: name) }, done: done)
    }
}
