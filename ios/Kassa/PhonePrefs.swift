import Foundation
import KassaShared

/// What the phone remembers about how Kassa looks and opens, in UserDefaults (the counterpart of Android's `Settings`: not in a backup).
/// The sort of Accounts > Order, the picked Overview tiles, which Accounts groups are folded, the theme and the tab Kassa opens on. The
/// valid names and the fall-backs are :domain's (`PhoneChoicesBridge`, `AccountOrderBridge`). The arrangement itself is not here: it rides
/// in a backup, so it lives in the store (`LedgerStore.setAccountArrangement`).
struct PhonePrefs {

    static let sortKey = "accountSort"
    static let pinsKey = "overviewPins"
    static let foldedKey = "accountFolds"
    static let themeKey = "theme"
    static let homeTabKey = "openTab"

    /// Closed and the archived groups start folded, as before.
    static let defaultFolded: [String] = ["CLOSED", "ARCHIVED", "ARCHIVED_PEOPLE"]

    let defaults: UserDefaults

    init(defaults: UserDefaults = UserDefaults.standard) {
        self.defaults = defaults
    }

    /// The stored sort name, or "" when none was chosen (a backup's arrangement then shows as Your order).
    var accountSort: String {
        get { return defaults.string(forKey: PhonePrefs.sortKey) ?? "" }
        nonmutating set {
            if newValue.isEmpty {
                defaults.removeObject(forKey: PhonePrefs.sortKey)
            } else {
                defaults.set(newValue, forKey: PhonePrefs.sortKey)
            }
        }
    }

    /// The picked Overview tiles as `PinRules` text, or nil while the person never picked (the first eight show).
    var pins: String? {
        get { return defaults.string(forKey: PhonePrefs.pinsKey) }
        nonmutating set {
            if let text = newValue {
                defaults.set(text, forKey: PhonePrefs.pinsKey)
            } else {
                defaults.removeObject(forKey: PhonePrefs.pinsKey)
            }
        }
    }

    var folded: [String] {
        get { return defaults.stringArray(forKey: PhonePrefs.foldedKey) ?? PhonePrefs.defaultFolded }
        nonmutating set { defaults.set(newValue, forKey: PhonePrefs.foldedKey) }
    }

    /// SYSTEM, LIGHT or DARK.
    var theme: String {
        get {
            let stored: String = defaults.string(forKey: PhonePrefs.themeKey) ?? ""
            return PhoneChoicesBridge.shared.theme(stored: stored)
        }
        nonmutating set { defaults.set(PhoneChoicesBridge.shared.theme(stored: newValue), forKey: PhonePrefs.themeKey) }
    }

    /// TODAY (Overview), ACTIVITY, INSIGHTS or ACCOUNTS.
    var homeTab: String {
        get {
            let stored: String = defaults.string(forKey: PhonePrefs.homeTabKey) ?? ""
            return PhoneChoicesBridge.shared.homeTab(stored: stored)
        }
        nonmutating set { defaults.set(PhoneChoicesBridge.shared.homeTab(stored: newValue), forKey: PhonePrefs.homeTabKey) }
    }

    /// The tab a stored home-tab name opens.
    static func shellTab(_ name: String) -> ShellTab {
        if name == "ACTIVITY" { return .activity }
        if name == "INSIGHTS" { return .insights }
        if name == "ACCOUNTS" { return .accounts }
        return .overview
    }

    /// Forgets what points at accounts that a restore or an import replaced: the sort and the picked tiles (the arrangement goes with
    /// the store). Android's `Settings.forgetAccountReferences`.
    static func forgetAccountReferences(_ defaults: UserDefaults) {
        defaults.removeObject(forKey: sortKey)
        defaults.removeObject(forKey: pinsKey)
    }
}
