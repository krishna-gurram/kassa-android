import Foundation
import KassaShared

/// Coach on iOS (layer 1 screens). The figures are :domain's (`LedgerView.coach`); this file only keeps the phone's own choices and says
/// in words what a shared row says. No amount is worked out here: every figure is the text a row carries (the mask while hidden).
/// Coach never writes anything; the one decision it offers opens the existing budget editor, and nothing is saved until the person saves.

/// What the phone remembers about Coach, in UserDefaults (not in a backup, as on Android's `Settings`).
struct CoachPrefs {

    static let weeklyKey = "coachWeeklyCheckIn"
    static let cardsKey = "coachInsightCards"
    static let dismissedKey = "coachDismissedWeek"

    let defaults: UserDefaults

    init(defaults: UserDefaults = UserDefaults.standard) {
        self.defaults = defaults
    }

    /// The weekly check-in (Overview's card and the check-in page). On until the person turns it off.
    var weeklyCheckIn: Bool {
        get {
            if defaults.object(forKey: CoachPrefs.weeklyKey) == nil { return true }
            return defaults.bool(forKey: CoachPrefs.weeklyKey)
        }
        nonmutating set { defaults.set(newValue, forKey: CoachPrefs.weeklyKey) }
    }

    /// The safe-to-spend block and the "This week" cards. On until the person turns it off.
    var insightCards: Bool {
        get {
            if defaults.object(forKey: CoachPrefs.cardsKey) == nil { return true }
            return defaults.bool(forKey: CoachPrefs.cardsKey)
        }
        nonmutating set { defaults.set(newValue, forKey: CoachPrefs.cardsKey) }
    }

    /// The week (its start day, an epoch day) whose decision was set aside with "Not now", or 0 for none. Only the latest week is kept:
    /// a later week starts fresh, and an earlier one never comes back.
    var dismissedWeek: Int {
        get { return defaults.integer(forKey: CoachPrefs.dismissedKey) }
        nonmutating set { defaults.set(newValue, forKey: CoachPrefs.dismissedKey) }
    }

    func isDismissed(weekStart: Int) -> Bool {
        return weekStart != 0 && dismissedWeek == weekStart
    }
}

/// Where tapping a card goes.
enum CoachTarget: Equatable {
    /// Insights' category page of a top-level category.
    case category(Int64)
    /// The repeating charges page.
    case subscriptions
    /// The Accounts tab.
    case accounts
}

enum CoachTint: Equatable {
    case blue
    case pink
    case sage
}

/// One card under "This week".
struct CoachCard: Identifiable, Equatable {
    let id: String
    let symbol: String
    let tint: CoachTint
    let title: String
    let body: String
    let target: CoachTarget
}

/// The check-in page as words: what to say for the win, the watch-out and the decision, from the shared rows.
struct CheckInText: Equatable {
    struct Item: Equatable {
        let title: String
        let body: String
        let chip: String
    }

    struct Decision: Equatable {
        let title: String
        let body: String
        let categoryId: Int64
        let limitText: String
    }

    /// READY, NOT_ENOUGH_DATA or HIDDEN.
    let state: String
    let weekStart: Int
    let weekTitle: String
    let headline: String
    let subline: String
    let win: Item?
    let watch: Item?
    let decision: Decision?
}

enum CoachWording {

    // MARK: Small pieces

    static func categoryName(_ name: String) -> String {
        return name.isEmpty ? String(localized: "entry.uncategorized") : name
    }

    /// Tenths of a month as "2.1" with the phone's decimal mark.
    static func monthsText(_ tenths: Int) -> String {
        let formatter = NumberFormatter()
        formatter.minimumFractionDigits = 1
        formatter.maximumFractionDigits = 1
        return formatter.string(from: NSNumber(value: Double(tenths) / 10.0)) ?? ""
    }

    /// "rent, the card payment and your usual spending", from the parts the safe figure is spoken for by.
    static func partsPhrase(_ parts: [CoachPartData]) -> String {
        var phrases: [String] = []
        for part in parts {
            phrases.append(partPhrase(part))
        }
        return ListFormatter.localizedString(byJoining: phrases)
    }

    private static func partPhrase(_ part: CoachPartData) -> String {
        switch part.kind {
        case "BILLS":
            if part.count == 1 && !part.name.isEmpty { return part.name.localizedLowercase }
            return String(localized: "coach.part.bills")
        case "RECURRING":
            return String(localized: "coach.part.recurring")
        case "CARDS":
            return part.count == 1 ? String(localized: "coach.part.card") : String(localized: "coach.part.cards")
        case "LOANS":
            return part.count == 1 ? String(localized: "coach.part.loan") : String(localized: "coach.part.loans")
        default:
            return String(localized: "coach.part.usual")
        }
    }

    /// The line under the safe figure, or "" when nothing is set aside.
    static func safeNote(_ safe: CoachSafeData) -> String {
        if safe.parts.isEmpty { return String(localized: "coach.safe.afterNone") }
        return String(format: String(localized: "coach.safe.after"), partsPhrase(safe.parts))
    }

    static func safeLabel(_ safe: CoachSafeData) -> String {
        let when = KassaDates.shortDate(epochDay: Int(safe.untilDay))
        if safe.state == "SHORTFALL" { return String(format: String(localized: "coach.safe.short"), when) }
        return String(format: String(localized: "coach.safe.until"), when)
    }

    // MARK: Cards under "This week"

    /// The pace rows that are flagged (at most two), the biggest subscription rise, and the emergency fund. Only rows that exist; none while
    /// hidden or when the cards are turned off.
    static func cards(_ data: CoachData, enabled: Bool) -> [CoachCard] {
        if !enabled || data.hidden { return [] }
        var out: [CoachCard] = []
        var taken: Int = 0
        for row in data.pace.rows {
            if taken >= 2 { break }
            if !(row.aboveLast || row.overBudget || row.paceOverBudget) { continue }
            out.append(paceCard(row))
            taken += 1
        }
        if let rise = data.subscriptions.rows.first {
            out.append(subscriptionCard(rise, total: data.subscriptions.monthlyTotalText))
        }
        if let fund = fundCard(data.fund) {
            out.append(fund)
        }
        return out
    }

    static func paceTitle(_ row: CoachPaceRowData) -> String {
        let name = categoryName(row.categoryName)
        if row.overBudget { return String(format: String(localized: "coach.pace.over.title"), name) }
        if row.paceOverBudget { return String(format: String(localized: "coach.pace.budget.title"), name) }
        return String(format: String(localized: "coach.pace.last.title"), name)
    }

    private static func paceBody(_ row: CoachPaceRowData) -> String {
        if row.overBudget { return String(format: String(localized: "coach.pace.over.body"), row.spentText, row.budgetText) }
        if row.paceOverBudget { return String(format: String(localized: "coach.pace.budget.body"), row.spentText, row.projectionText) }
        return String(format: String(localized: "coach.pace.last.body"), row.spentText, row.lastTotalText)
    }

    private static func paceCard(_ row: CoachPaceRowData) -> CoachCard {
        return CoachCard(
            id: "pace-\(row.categoryId)", symbol: "chart.bar.xaxis", tint: CoachTint.blue,
            title: paceTitle(row), body: paceBody(row), target: CoachTarget.category(row.categoryId)
        )
    }

    private static func subscriptionCard(_ row: CoachSubscriptionRowData, total: String) -> CoachCard {
        return CoachCard(
            id: "subscription", symbol: "tv", tint: CoachTint.pink,
            title: String(format: String(localized: "coach.sub.title"), row.name, row.changeText),
            body: String(format: String(localized: "coach.sub.body"), total), target: CoachTarget.subscriptions
        )
    }

    private static func fundCard(_ fund: CoachFundData) -> CoachCard? {
        if fund.state != "READY" || fund.tenths < 0 { return nil }
        let months = monthsText(Int(fund.tenths))
        let title: String
        if fund.tenths == 10 {
            title = String(localized: "coach.fund.titleOne")
        } else {
            title = String(format: String(localized: "coach.fund.title"), months)
        }
        let body: String
        if fund.targetTenths > 0 && !fund.weeklyText.isEmpty {
            body = String(format: String(localized: "coach.fund.bodyTarget"), fund.weeklyText, monthsText(Int(fund.targetTenths)))
        } else {
            body = String(localized: "coach.fund.body")
        }
        return CoachCard(id: "fund", symbol: "checkmark.shield", tint: CoachTint.sage, title: title, body: body, target: CoachTarget.accounts)
    }

    // MARK: The weekly check-in

    static func watchTitle(_ watch: CoachWatchData) -> String {
        if watch.kind == "SUBSCRIPTION_RISE" {
            return String(format: String(localized: "coach.sub.title"), watch.name, watch.overByText)
        }
        let name = categoryName(watch.categoryName)
        if watch.referenceKind == "BUDGET" { return String(format: String(localized: "coach.pace.budget.title"), name) }
        if watch.referenceKind == "LAST_MONTH" { return String(format: String(localized: "coach.pace.last.title"), name) }
        return String(format: String(localized: "coach.pace.high.title"), name)
    }

    private static func watchBody(_ watch: CoachWatchData) -> String {
        if watch.kind == "SUBSCRIPTION_RISE" {
            return String(format: String(localized: "coach.ci.watch.sub.body"), watch.referenceText, watch.amountText)
        }
        if watch.referenceKind == "BUDGET" {
            return String(format: String(localized: "coach.ci.watch.budget.body"), watch.projectionText, watch.overByText)
        }
        if watch.referenceKind == "LAST_MONTH" {
            return String(format: String(localized: "coach.ci.watch.last.body"), watch.projectionText, watch.overByText)
        }
        return String(format: String(localized: "coach.ci.watch.none.body"), watch.amountText)
    }

    private static func watchChip(_ watch: CoachWatchData) -> String {
        if watch.kind != "CATEGORY_PACE" || watch.referenceKind != "BUDGET" || watch.usedPercent < 0 { return "" }
        return String(
            format: String(localized: "coach.ci.watch.chip"), watch.referenceText, Int(watch.usedPercent), Int(watch.dayOfPeriod)
        )
    }

    static func winTitle(_ win: CoachWinData) -> String {
        let name = categoryName(win.categoryName)
        if win.kind == "UNDER_BUDGET" { return String(format: String(localized: "coach.ci.win.budget.title"), name, win.savedByText) }
        return String(format: String(localized: "coach.ci.win.usual.title"), name, win.savedByText)
    }

    private static func winBody(_ win: CoachWinData) -> String {
        if win.kind == "UNDER_BUDGET" { return String(format: String(localized: "coach.ci.win.budget.body"), win.spentText, win.referenceText) }
        return String(format: String(localized: "coach.ci.win.usual.body"), win.spentText, win.referenceText)
    }

    private static func decisionOf(_ rows: [CoachDecisionData], weekStart: Int, dismissed: Int) -> CheckInText.Decision? {
        guard let row = rows.first else { return nil }
        if weekStart != 0 && dismissed == weekStart { return nil }
        let name = categoryName(row.categoryName)
        return CheckInText.Decision(
            title: String(format: String(localized: "coach.ci.decision.title"), row.weeklyLimitText, name),
            body: String(format: String(localized: "coach.ci.decision.body"), row.usualWeekText, name),
            categoryId: row.categoryId, limitText: row.weeklyLimitText
        )
    }

    /// The check-in page's words. [dismissedWeek] is `CoachPrefs.dismissedWeek`: the decision of that week stays out.
    static func checkIn(_ c: CoachCheckInData, dismissedWeek: Int) -> CheckInText {
        let weekStart = Int(c.weekStartDay)
        let title = String(format: String(localized: "coach.ci.title"), KassaDates.shortDate(epochDay: weekStart))
        if c.state != "READY" {
            return CheckInText(
                state: c.state, weekStart: weekStart, weekTitle: title, headline: "", subline: "", win: nil, watch: nil, decision: nil
            )
        }
        var win: CheckInText.Item? = nil
        if let first = c.wins.first {
            win = CheckInText.Item(title: winTitle(first), body: winBody(first), chip: "")
        }
        var watch: CheckInText.Item? = nil
        if let first = c.watches.first {
            watch = CheckInText.Item(title: watchTitle(first), body: watchBody(first), chip: watchChip(first))
        }
        let head: String
        if watch != nil {
            head = String(localized: "coach.ci.headWatch")
        } else if win != nil {
            head = String(localized: "coach.ci.headWin")
        } else {
            head = String(localized: "coach.ci.headQuiet")
        }
        let sub: String
        if c.weeksCompared > 0 {
            sub = String(format: String(localized: "coach.ci.sub"), c.spentText, c.usualByNowText)
        } else {
            sub = String(format: String(localized: "coach.ci.subNoUsual"), c.spentText)
        }
        return CheckInText(
            state: c.state, weekStart: weekStart, weekTitle: title, headline: head, subline: sub, win: win, watch: watch,
            decision: decisionOf(c.decisions, weekStart: weekStart, dismissed: dismissedWeek)
        )
    }

    /// The first line of Overview's "Your week in 3 points" card, or nil when the card stays away: the check-in is off, hidden, not
    /// ready, or has neither a watch-out nor a win to say.
    static func teaser(_ data: CoachData?, weeklyOn: Bool) -> String? {
        guard let found = data else { return nil }
        if !weeklyOn || found.hidden || found.checkIn.state != "READY" { return nil }
        if let watch = found.checkIn.watches.first { return watchTitle(watch) }
        if let win = found.checkIn.wins.first { return winTitle(win) }
        return nil
    }

    /// True when the check-in page has something to open (also what the Coach page's link needs).
    static func checkInReady(_ data: CoachData, weeklyOn: Bool) -> Bool {
        return weeklyOn && !data.hidden && data.checkIn.state == "READY"
    }

    /// "Suggested weekly limit: $120.00. Budgets are per month, so enter the month's amount." for the budget editor.
    static func limitHint(_ limitText: String) -> String {
        return String(format: String(localized: "coach.ci.suggest"), limitText)
    }
}
