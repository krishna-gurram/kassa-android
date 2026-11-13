import Foundation
import KassaShared

/// Coach simulator on iOS ("Can I afford it?" and "Simulate my month"). Every figure, verdict, day and kind code comes from
/// `LedgerView.coachSim` (:domain's `CoachSim`); this file only keeps what the person typed, builds the what-if lines through
/// `CoachSimBridge` (never by hand), and says in words what a shared row says. Nothing here writes anything to the store, and no
/// amount is worked out here: a row carries its own money text (the mask while amounts are hidden).

// MARK: What the phone remembers

extension CoachPrefs {

    static let cushionKey = "coachSimCushion"

    /// The cushion: how low the person is happy for their cash to dip, in cents. 0 until set. In UserDefaults, not in a backup.
    var cushionCents: Int64 {
        get { return Int64(defaults.integer(forKey: CoachPrefs.cushionKey)) }
        nonmutating set { defaults.set(Int(newValue), forKey: CoachPrefs.cushionKey) }
    }
}

// MARK: What-ifs

/// One thing to try on top of the plain month. [line] is the what-if text `CoachSimBridge` built for it; the other fields are only
/// what the screen needs to word it back ("Spend $900.00 on Nov 20").
struct SimChip: Equatable, Identifiable {

    enum Kind: String {
        case spend
        case delay
        case extra
        case payday
    }

    let kind: Kind
    /// Positive cents for a purchase or a monthly cost, 0 for the others.
    let amountCents: Int64
    /// The epoch day the change is about: the day of the purchase, the day a payment moves to, the first day of a monthly cost, the new payday.
    let day: Int
    /// The `CoachSimMovableData.key` of a delayed payment, "" for the others.
    let itemKey: String
    /// The delayed payment's name, "" for the others or when it has none.
    let name: String
    /// The typed amount as the person saw it, with the currency's symbol ("$900.00"); "" for a chip without an amount.
    let amountShown: String
    let line: String

    var id: String { return line }

    fileprivate static let fieldSeparator = "\u{1D}"

    fileprivate var encoded: String {
        let parts: [String] = [kind.rawValue, String(amountCents), String(day), itemKey, name, amountShown]
        return parts.joined(separator: SimChip.fieldSeparator)
    }

    fileprivate static func decode(_ text: String) -> SimChip? {
        let parts = text.components(separatedBy: SimChip.fieldSeparator)
        if parts.count != 6 { return nil }
        guard let kind = Kind(rawValue: parts[0]), let cents = Int64(parts[1]), let day = Int(parts[2]) else { return nil }
        return SimChips.make(kind: kind, cents: cents, day: day, key: parts[3], name: parts[4], shown: parts[5])
    }
}

/// Builds chips; the lines come from `CoachSimBridge`.
enum SimChips {

    static func spend(cents: Int64, day: Int, shown: String) -> SimChip {
        return SimChip(
            kind: .spend, amountCents: cents, day: day, itemKey: "", name: "", amountShown: shown,
            line: CoachSimBridge.shared.spendLine(amountCents: cents, day: Int32(day))
        )
    }

    static func delay(key: String, name: String, toDay: Int) -> SimChip {
        return SimChip(
            kind: .delay, amountCents: 0, day: toDay, itemKey: key, name: name, amountShown: "",
            line: CoachSimBridge.shared.delayLine(itemKey: key, toDay: Int32(toDay))
        )
    }

    static func extra(cents: Int64, fromDay: Int, shown: String) -> SimChip {
        return SimChip(
            kind: .extra, amountCents: cents, day: fromDay, itemKey: "", name: "", amountShown: shown,
            line: CoachSimBridge.shared.extraMonthlyLine(amountCents: cents, fromDay: Int32(fromDay))
        )
    }

    static func payday(toDay: Int) -> SimChip {
        return SimChip(
            kind: .payday, amountCents: 0, day: toDay, itemKey: "", name: "", amountShown: "",
            line: CoachSimBridge.shared.paydayLine(toDay: Int32(toDay))
        )
    }

    static func make(kind: SimChip.Kind, cents: Int64, day: Int, key: String, name: String, shown: String) -> SimChip {
        switch kind {
        case .spend: return spend(cents: cents, day: day, shown: shown)
        case .delay: return delay(key: key, name: name, toDay: day)
        case .extra: return extra(cents: cents, fromDay: day, shown: shown)
        case .payday: return payday(toDay: day)
        }
    }

    /// The what-if text `coachSim` takes: the chips' lines, one per line.
    static func text(_ chips: [SimChip]) -> String {
        return CoachSimBridge.shared.join(lines: chips.map { $0.line })
    }
}

/// What is typed on the "Can I afford it?" form: the amount (as the number pad types it), the day, and the extra changes added under
/// "Or try". Kept for the scene (`SceneStorage`) as one text, like the new-entry draft.
struct SimDraft: Equatable {

    var typed: String = ""
    /// The day of the purchase; 0 until picked (that means today).
    var day: Int = 0
    var extras: [SimChip] = []

    private static let separator = "\u{1F}"
    private static let extraSeparator = "\u{1E}"

    /// True while nothing was typed or picked.
    var isEmpty: Bool {
        return typed.isEmpty && day == 0 && extras.isEmpty
    }

    /// The day of the purchase, kept between today and [horizon] days on (a draft kept overnight never points at the past).
    func resolvedDay(today: Int, horizon: Int) -> Int {
        if day == 0 { return today }
        return min(max(day, today), today + horizon)
    }

    var encoded: String {
        let list = extras.map { $0.encoded }.joined(separator: SimDraft.extraSeparator)
        return ["1", typed, String(day), list].joined(separator: SimDraft.separator)
    }

    /// A draft from [text]; an empty draft for anything that is not one. The amount is only ever what the pad could have typed.
    static func decode(_ text: String, maxExtras: Int) -> SimDraft {
        let parts = text.components(separatedBy: separator)
        if parts.count != 4 || parts[0] != "1" { return SimDraft() }
        var draft = SimDraft()
        draft.typed = AmountPadText.clean(parts[1])
        draft.day = Int(parts[2]) ?? 0
        if !parts[3].isEmpty {
            for piece in parts[3].components(separatedBy: extraSeparator) {
                if draft.extras.count >= maxExtras { break }
                if let chip = SimChip.decode(piece) { draft.extras.append(chip) }
            }
        }
        return draft
    }
}

// MARK: Words

/// The hero's tint: green when the lowest point stays above the cushion, orange when it is tight or short, grey for everything else.
enum SimTone: Equatable {
    case green
    case orange
    case grey
}

/// The words of the hero.
struct SimHeroText: Equatable {
    let tone: SimTone
    let label: String
    /// The big figure (a money text, or the mask while hidden); "" when the hero has none.
    let figure: String
    let sub: String
    let note: String
}

/// One day of the month list: the day, the money at its end (nil when the data has none) and what happened.
struct SimDayGroup {
    let day: Int
    let balance: Int64?
    var events: [CoachSimEventData]
}

enum CoachSimWording {

    /// The verdicts `coachSim` gives.
    static let verdicts: [String] = ["ABOVE_CUSHION", "TIGHT", "SHORT", "NOT_ENOUGH_DATA", "NO_ACCOUNTS", "PROBLEM", "HIDDEN"]

    /// The problem codes `coachSim` gives (`SimProblem`).
    static let problems: [String] = [
        "TOO_MANY_WHAT_IFS", "BAD_AMOUNT", "DAY_OUT_OF_RANGE", "UNKNOWN_ITEM", "NOT_LATER", "NO_PAYDAY", "BAD_CUSHION", "BAD_ENCODING",
    ]

    /// True when the verdict has a chart and a list of days: the three that were judged against the cushion.
    static func hasMonth(_ verdict: String) -> Bool {
        return verdict == "ABOVE_CUSHION" || verdict == "TIGHT" || verdict == "SHORT"
    }

    static func tone(_ verdict: String) -> SimTone {
        switch verdict {
        case "ABOVE_CUSHION": return .green
        case "TIGHT", "SHORT": return .orange
        default: return .grey
        }
    }

    // MARK: Problems

    /// A plain sentence for a `SimProblem` name. Any other code reads as the general one.
    static func problemText(_ code: String) -> String {
        switch code {
        case "TOO_MANY_WHAT_IFS": return String(localized: "coachsim.problem.TOO_MANY_WHAT_IFS")
        case "BAD_AMOUNT": return String(localized: "coachsim.problem.BAD_AMOUNT")
        case "DAY_OUT_OF_RANGE": return String(localized: "coachsim.problem.DAY_OUT_OF_RANGE")
        case "UNKNOWN_ITEM": return String(localized: "coachsim.problem.UNKNOWN_ITEM")
        case "NOT_LATER": return String(localized: "coachsim.problem.NOT_LATER")
        case "NO_PAYDAY": return String(localized: "coachsim.problem.NO_PAYDAY")
        case "BAD_CUSHION": return String(localized: "coachsim.problem.BAD_CUSHION")
        case "BAD_ENCODING": return String(localized: "coachsim.problem.BAD_ENCODING")
        default: return String(localized: "coachsim.problem.other")
        }
    }

    // MARK: Hero

    private static func lowestLine(_ data: CoachSimData, format: String) -> String {
        if data.lowestDay < 0 { return "" }
        return String(format: format, KassaDates.weekdayDate(epochDay: Int(data.lowestDay)))
    }

    /// The hero's words for [data]. [cushionCents] only decides between "above your cushion" and "above zero".
    static func hero(_ data: CoachSimData, cushionCents: Int64) -> SimHeroText {
        let tone = CoachSimWording.tone(data.verdict)
        switch data.verdict {
        case "ABOVE_CUSHION":
            let label = cushionCents > 0 ? String(localized: "coachsim.hero.aboveCushion") : String(localized: "coachsim.hero.aboveZero")
            return SimHeroText(tone: tone, label: label, figure: data.lowestText, sub: lowestLine(data, format: String(localized: "coachsim.hero.lowest")), note: "")
        case "TIGHT":
            let note = String(format: String(localized: "coachsim.hero.belowCushion"), data.cushionText)
            return SimHeroText(
                tone: tone, label: String(localized: "coachsim.hero.tight"), figure: data.lowestText,
                sub: lowestLine(data, format: String(localized: "coachsim.hero.lowest")), note: note
            )
        case "SHORT":
            return SimHeroText(
                tone: tone, label: String(localized: "coachsim.hero.short"), figure: data.shortByText,
                sub: lowestLine(data, format: String(localized: "coachsim.hero.shortAt")), note: ""
            )
        case "NOT_ENOUGH_DATA":
            return SimHeroText(
                tone: tone, label: String(localized: "coachsim.hero.notEnough"), figure: "",
                sub: String(localized: "coachsim.hero.notEnoughBody"), note: ""
            )
        case "NO_ACCOUNTS":
            return SimHeroText(
                tone: tone, label: String(localized: "coachsim.hero.noAccounts"), figure: "",
                sub: String(localized: "coachsim.hero.noAccountsBody"), note: ""
            )
        case "HIDDEN":
            return SimHeroText(
                tone: tone, label: String(localized: "coachsim.hero.hidden"), figure: data.lowestText,
                sub: String(localized: "coachsim.hero.hiddenBody"), note: ""
            )
        default:
            return SimHeroText(
                tone: tone, label: String(localized: "coachsim.hero.problem"), figure: "",
                sub: problemText(data.problem), note: ""
            )
        }
    }

    // MARK: What-if chips

    static func chipLabel(_ chip: SimChip) -> String {
        let when = KassaDates.shortDate(epochDay: chip.day)
        switch chip.kind {
        case .spend:
            return String(format: String(localized: "coachsim.chip.spend"), chip.amountShown, when)
        case .delay:
            let name = chip.name.isEmpty ? String(localized: "coachsim.chip.aPayment") : chip.name
            return String(format: String(localized: "coachsim.chip.delay"), name, when)
        case .extra:
            return String(format: String(localized: "coachsim.chip.extra"), chip.amountShown, when)
        case .payday:
            return String(format: String(localized: "coachsim.chip.payday"), when)
        }
    }

    // MARK: Events

    static func kindLabel(_ kind: String) -> String {
        switch kind {
        case "PAYCHECK": return String(localized: "coachsim.kind.paycheck")
        case "BILL": return String(localized: "coachsim.kind.bill")
        case "CARD_PAYMENT": return String(localized: "coachsim.kind.card")
        case "LOAN_PAYMENT": return String(localized: "coachsim.kind.loan")
        case "RECURRING": return String(localized: "coachsim.kind.recurring")
        case "USUAL_SPENDING": return String(localized: "coachsim.kind.usual")
        case "SPEND": return String(localized: "coachsim.kind.spend")
        case "EXTRA_MONTHLY": return String(localized: "coachsim.kind.extra")
        default: return String(localized: "coachsim.kind.other")
        }
    }

    /// The SF Symbol of a kind.
    static func symbol(_ kind: String) -> String {
        switch kind {
        case "PAYCHECK": return "plus"
        case "BILL": return "doc.text"
        case "CARD_PAYMENT": return "creditcard"
        case "LOAN_PAYMENT": return "building.columns"
        case "RECURRING": return "repeat"
        case "USUAL_SPENDING": return "minus"
        case "SPEND": return "cart"
        case "EXTRA_MONTHLY": return "repeat.circle"
        default: return "circle"
        }
    }

    /// The badge colours of a kind: income teal, bills and loans blue, cards pink, everything else quiet.
    static func tint(_ kind: String) -> TintPair {
        switch kind {
        case "PAYCHECK": return Palette.teal
        case "BILL", "LOAN_PAYMENT", "RECURRING", "SPEND", "EXTRA_MONTHLY": return Palette.blue
        case "CARD_PAYMENT": return Palette.pink
        default: return Palette.neutral
        }
    }

    /// True for the figures that are against the person beyond ordinary spending (a card or loan payment coming due).
    static func isAgainst(_ kind: String) -> Bool {
        return kind == "CARD_PAYMENT" || kind == "LOAN_PAYMENT"
    }

    static func eventTitle(_ event: CoachSimEventData) -> String {
        return event.name.isEmpty ? kindLabel(event.kind) : event.name
    }

    /// The small line under an event: its kind when it has a name of its own, "Estimated" when it is, and the day when [withDate].
    static func eventDetail(_ event: CoachSimEventData, withDate: Bool) -> String {
        var parts: [String] = []
        if !event.name.isEmpty { parts.append(kindLabel(event.kind)) }
        if event.estimated { parts.append(String(localized: "coachsim.estimated")) }
        if withDate { parts.append(KassaDates.shortDate(epochDay: Int(event.day))) }
        return parts.joined(separator: " \u{00B7} ")
    }

    /// The signed figure of an event: "+" for money in, "-" for money out, in front of the money text the row carries.
    static func figure(_ event: CoachSimEventData) -> String {
        let format = event.inflow ? String(localized: "coachsim.fig.in") : String(localized: "coachsim.fig.out")
        return String(format: format, event.amountText)
    }

    /// What moves the month: every event but the usual daily spending, in day order, at most [limit].
    static func whatMoves(_ data: CoachSimData, limit: Int = 8) -> [CoachSimEventData] {
        var out: [CoachSimEventData] = []
        for event in data.events {
            if event.kind == "USUAL_SPENDING" { continue }
            out.append(event)
            if out.count >= limit { break }
        }
        return out
    }

    /// The events grouped by day, each day with the money at its end (from the chart's balances).
    static func dayGroups(_ data: CoachSimData) -> [SimDayGroup] {
        let days: [Int] = data.chartDays.map { $0.intValue }
        let balances: [Int64] = data.chartBalances.map { $0.int64Value }
        var groups: [SimDayGroup] = []
        for event in data.events {
            let day = Int(event.day)
            if let last = groups.last, last.day == day {
                groups[groups.count - 1].events.append(event)
                continue
            }
            var balance: Int64? = nil
            if let index = days.firstIndex(of: day), index < balances.count { balance = balances[index] }
            groups.append(SimDayGroup(day: day, balance: balance, events: [event]))
        }
        return groups
    }

    // MARK: Chart

    /// The chart in words, for VoiceOver: where the cash starts, its lowest point and day, and where it ends. "" without a chart.
    static func chartSummary(_ data: CoachSimData, symbol: String) -> String {
        let balances: [Int64] = data.chartBalances.map { $0.int64Value }
        guard let last = balances.last else { return "" }
        let end = KassaCore.shared.formatMoney(cents: last, symbol: symbol)
        let lowDay = data.lowestDay >= 0 ? KassaDates.weekdayDate(epochDay: Int(data.lowestDay)) : ""
        return String(
            format: String(localized: "coachsim.chart.a11y"), balances.count - 1, data.startText, data.lowestText, lowDay, end
        )
    }

    /// True when the what-ifs changed the line: only then is the plain month drawn dashed beside it.
    static func baselineDiffers(_ data: CoachSimData) -> Bool {
        let a: [Int64] = data.chartBalances.map { $0.int64Value }
        let b: [Int64] = data.baselineBalances.map { $0.int64Value }
        return a != b
    }
}
