import Foundation
import KassaShared

/// One kind's phone notification rule: on or off, how many days before the due day (0 to 14) and the time of day in minutes.
struct DueRuleValue: Equatable {
    var enabled: Bool
    var daysBefore: Int
    var minute: Int
}

/// What the phone remembers about payment-due notices, in UserDefaults (the counterpart of Android's `Settings` due rules, `notifyAsked`
/// and `cleared`): one rule per kind (CARD, LOAN, BILL), the daily reminder, whether the permission was asked, the notices the person cleared,
/// and what the scheduler has rung. The defaults and the limits are :domain's (`DueBridge`).
struct DueSettings {

    static let kinds: [String] = ["CARD", "LOAN", "BILL"]
    static let reminderDefaultMinute = 21 * 60

    static let reminderKey = "dailyReminder"
    static let reminderMinuteKey = "dailyReminderMinute"
    static let notifyAskedKey = "notifyAsked"
    static let clearedKey = "clearedNotices"
    static let postedKey = "dueNotified"
    static let planKey = "duePlan"

    let defaults: UserDefaults

    init(defaults: UserDefaults = UserDefaults.standard) {
        self.defaults = defaults
    }

    // MARK: Rules

    private func prefix(_ kind: String) -> String {
        return "due_" + kind.lowercased()
    }

    func rule(_ kind: String) -> DueRuleValue {
        let base = prefix(kind)
        let storedOn: Bool? = defaults.object(forKey: base + "_on") as? Bool
        let storedDays: Int? = defaults.object(forKey: base + "_days") as? Int
        let storedMinute: Int? = defaults.object(forKey: base + "_minute") as? Int
        let on: Bool = storedOn ?? DueBridge.shared.defaultOn(kind: kind)
        let days: Int = storedDays ?? 0
        let minute: Int = storedMinute ?? Int(DueBridge.shared.defaultMinute())
        return DueRuleValue(enabled: on, daysBefore: clampDays(days), minute: clampMinute(minute))
    }

    func setRule(_ kind: String, _ value: DueRuleValue) {
        let base = prefix(kind)
        defaults.set(value.enabled, forKey: base + "_on")
        defaults.set(clampDays(value.daysBefore), forKey: base + "_days")
        defaults.set(clampMinute(value.minute), forKey: base + "_minute")
    }

    private func clampDays(_ days: Int) -> Int {
        let top = Int(DueBridge.shared.maxDays())
        return min(max(days, 0), top)
    }

    private func clampMinute(_ minute: Int) -> Int {
        return min(max(minute, 0), 1_439)
    }

    /// The three rules as the shared rules take them.
    func input() -> DueRulesInput {
        let card = rule("CARD")
        let loan = rule("LOAN")
        let bill = rule("BILL")
        return DueRulesInput(
            cardOn: card.enabled, cardDays: Int32(card.daysBefore), cardMinute: Int32(card.minute),
            loanOn: loan.enabled, loanDays: Int32(loan.daysBefore), loanMinute: Int32(loan.minute),
            billOn: bill.enabled, billDays: Int32(bill.daysBefore), billMinute: Int32(bill.minute)
        )
    }

    var anyEnabled: Bool {
        for kind in DueSettings.kinds where rule(kind).enabled { return true }
        return false
    }

    /// How many kinds are on ("2 on" in Settings).
    var enabledCount: Int {
        var count = 0
        for kind in DueSettings.kinds where rule(kind).enabled { count += 1 }
        return count
    }

    // MARK: Daily reminder

    var reminderOn: Bool {
        get { return defaults.bool(forKey: DueSettings.reminderKey) }
        nonmutating set { defaults.set(newValue, forKey: DueSettings.reminderKey) }
    }

    var reminderMinute: Int {
        get {
            let stored: Int? = defaults.object(forKey: DueSettings.reminderMinuteKey) as? Int
            return clampMinute(stored ?? DueSettings.reminderDefaultMinute)
        }
        nonmutating set { defaults.set(clampMinute(newValue), forKey: DueSettings.reminderMinuteKey) }
    }

    // MARK: Permission asked

    /// Set once Kassa has explained and asked for permission to notify, so it never asks twice.
    var notifyAsked: Bool {
        get { return defaults.bool(forKey: DueSettings.notifyAskedKey) }
        nonmutating set { defaults.set(newValue, forKey: DueSettings.notifyAskedKey) }
    }

    // MARK: Cleared notices

    /// The notices the person cleared, by key (a card's statement, a loan's due date, a suggested bill). They don't come back.
    var cleared: [String] {
        return defaults.stringArray(forKey: DueSettings.clearedKey) ?? []
    }

    func clear(_ keys: [String]) {
        var next: [String] = cleared
        for key in keys where !next.contains(key) { next.append(key) }
        defaults.set(next, forKey: DueSettings.clearedKey)
    }

    // MARK: What was rung

    /// Keys whose notification time has passed: never scheduled again for that due date.
    var posted: [String] {
        get { return defaults.stringArray(forKey: DueSettings.postedKey) ?? [] }
        nonmutating set { defaults.set(newValue, forKey: DueSettings.postedKey) }
    }

    /// The last plan handed to the system: key and the epoch milliseconds it was set for.
    var lastPlan: [String: Double] {
        get { return defaults.dictionary(forKey: DueSettings.planKey) as? [String: Double] ?? [:] }
        nonmutating set { defaults.set(newValue, forKey: DueSettings.planKey) }
    }

    /// Forgets everything the phone remembers by account or bill id: the cleared notices and what was rung. For a restore or an import,
    /// so nothing points at an id that is gone or now means another account (Android's `forgetAccountReferences` and `forgetPosted`).
    func forgetAccountReferences() {
        defaults.removeObject(forKey: DueSettings.clearedKey)
        defaults.removeObject(forKey: DueSettings.postedKey)
        defaults.removeObject(forKey: DueSettings.planKey)
        PhonePrefs.forgetAccountReferences(defaults)
    }

    // MARK: Time of day

    /// A Date today at [minute] minutes after midnight, for a time picker.
    static func date(minute: Int) -> Date {
        let calendar = Calendar.current
        let start = calendar.startOfDay(for: Date())
        return calendar.date(byAdding: .minute, value: minute, to: start) ?? start
    }

    /// Minutes after midnight of [date] on the phone's clock.
    static func minute(of date: Date) -> Int {
        let parts = Calendar.current.dateComponents([.hour, .minute], from: date)
        let hour: Int = parts.hour ?? 0
        let minute: Int = parts.minute ?? 0
        return hour * 60 + minute
    }
}
