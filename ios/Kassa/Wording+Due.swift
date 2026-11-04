import Foundation
import KassaShared

/// The words of the due notices that depend on a date. English grammar lives in Localizable.strings; the dates come from `KassaDates`.
enum DueWording {

    /// "Due today", "Due Thu, Nov 26" or "Overdue, was due Tue, Oct 20".
    static func dueText(dueDay: Int, today: Int) -> String {
        if dueDay == today { return String(localized: "due.dueToday") }
        let day = KassaDates.weekdayDate(epochDay: dueDay)
        if dueDay < today { return String(format: String(localized: "due.overdue"), day) }
        return String(format: String(localized: "due.dueOn"), day)
    }

    /// The same sentence starting in lower case, for the line "Card payment, due today".
    static func lowerFirst(_ text: String) -> String {
        guard let first = text.first else { return text }
        return String(first).lowercased() + text.dropFirst()
    }

    static func kicker(_ item: NoticeData, today: Int) -> String {
        let due = lowerFirst(dueText(dueDay: Int(item.dueDay), today: today))
        switch item.kind {
        case "CARD":
            return String(format: String(localized: "due.card.kicker"), due)
        case "LOAN":
            return String(format: String(localized: "due.loan.kicker"), due)
        case "BILL":
            var line = dueText(dueDay: Int(item.dueDay), today: today)
            if item.estimated && item.lastCount > 0 {
                line += String(format: String(localized: "due.bill.last"), Int(item.lastCount), item.lastText)
            }
            return line
        case "TRANSFER":
            return String(format: String(localized: "due.transfer.kicker"), KassaDates.shortDate(epochDay: Int(item.dueDay)))
        default:
            return String(localized: "due.suggest.kicker")
        }
    }

    static func title(_ item: NoticeData) -> String {
        if item.kind == "TRANSFER" { return String(localized: "due.transfer.title") }
        return item.name
    }

    static func subtitle(_ item: NoticeData) -> String {
        switch item.kind {
        case "CARD":
            return String(localized: "due.balancePayable")
        case "LOAN":
            return String(localized: "due.scheduled")
        case "BILL":
            if !item.estimated { return String(localized: "due.bill.fixed") }
            if item.amountText.isEmpty { return String(localized: "due.bill.noHistory") }
            return String(localized: "due.bill.estimated")
        case "TRANSFER":
            let place = item.accountName.isEmpty ? String(localized: "due.transfer.fallback") : item.accountName
            return String(format: String(localized: "due.transfer.subtitle"), place)
        default:
            return String(format: String(localized: "due.suggest.subtitle"), Int(item.sameCount))
        }
    }

    /// The label of an Upcoming row's second line: "Nov 25 · Card payment".
    static func upcomingLine(_ row: UpcomingData) -> String {
        let kind: String
        if row.kind == "CARD" {
            kind = String(localized: "due.kind.card")
        } else if row.estimated {
            kind = String(localized: "due.kind.billEstimated")
        } else {
            kind = String(localized: "due.kind.bill")
        }
        return String(format: String(localized: "due.upcoming.line"), KassaDates.shortDate(epochDay: Int(row.dueDay)), kind)
    }
}
