import Foundation
import KassaShared

/// What a row says, in the words of Localizable.strings. The shared code says what a row is (a kind, a note kind, a tag);
/// the sentences are made here so they can be translated and reordered. Pure functions, no state.
struct EntryWords: Equatable {
    var title: String
    var subtitle: String
    var note: String
}

enum Wording {

    private static func text(_ key: String.LocalizationValue) -> String {
        return String(localized: key)
    }

    private static func format(_ key: String.LocalizationValue, _ argument: String) -> String {
        return String(format: String(localized: key), argument)
    }

    // MARK: Accounts

    static func groupTitle(_ group: AccountsGroupData) -> String {
        switch group.key {
        case "CASH_AND_BANK": return text("accounts.group.cashAndBank")
        case "INVESTMENTS": return text("accounts.group.investments")
        case "OTHER_ASSETS": return text("accounts.group.otherAssets")
        case "CREDIT_CARDS": return text("accounts.group.creditCards")
        case "LOANS": return text("accounts.group.loans")
        case "OTHER_DEBTS": return text("accounts.group.otherDebts")
        case "PEOPLE": return text("accounts.group.people")
        case "CLOSED": return String(format: String(localized: "accounts.group.closed"), Int(group.rows.count))
        default: return group.title
        }
    }

    /// The small line under an account's name.
    static func note(_ row: AccountRowData) -> String? {
        if row.note == RowNote.totalBalance { return format("accounts.totalBalance", row.noteAmountText) }
        if row.note == RowNote.outstanding { return format("accounts.outstanding", row.noteAmountText) }
        if row.note == RowNote.nothingToPay { return text("accounts.nothingToPay") }
        if row.dueKind == DueKind.closed { return text("accounts.keptInHistory") }
        return nil
    }

    /// The small line under the main figure: when a payment is due, a deposit matures, an account was closed, or who owes whom.
    static func secondLine(_ row: AccountRowData) -> String? {
        let day = Int(row.dueDay)
        if row.dueKind == DueKind.due { return format("accounts.dueOn", KassaDates.shortDate(epochDay: day)) }
        if row.dueKind == DueKind.matures { return format("accounts.maturesOn", KassaDates.shortDate(epochDay: day)) }
        if row.dueKind == DueKind.closed { return format("accounts.closedOn", KassaDates.shortDate(epochDay: day)) }
        if row.side == PersonSide.owesYou { return text("accounts.owesYou") }
        if row.side == PersonSide.youOwe { return text("accounts.youOwe") }
        if row.side == PersonSide.settled { return text("accounts.settled") }
        return nil
    }

    // MARK: Activity

    /// Today, Yesterday, or the weekday and date.
    static func dayLabel(epochDay: Int, today: Int) -> String {
        if epochDay == today { return text("activity.today") }
        if epochDay == today - 1 { return text("activity.yesterday") }
        return KassaDates.weekdayDate(epochDay: epochDay)
    }

    static func symbol(_ kind: EntryKind) -> String {
        if kind == EntryKind.transfer || kind == EntryKind.cardPayment { return "arrow.left.arrow.right" }
        if kind == EntryKind.buy { return "chart.line.uptrend.xyaxis" }
        if kind == EntryKind.sell { return "chart.line.downtrend.xyaxis" }
        if kind == EntryKind.correction { return "slider.horizontal.3" }
        if kind == EntryKind.personIn || kind == EntryKind.personOut { return "person" }
        return "tag"
    }

    /// The title, the line under it and the small note under the amount, as Android's entry row words them.
    static func entry(_ e: EntryRowData) -> EntryWords {
        let anAccount = text("entry.anAccount")
        let uncategorized = text("entry.uncategorized")
        var words = EntryWords(title: e.note, subtitle: "", note: "")
        if e.kind == EntryKind.correction {
            if words.title.isEmpty { words.title = text("entry.balanceCorrection") }
            words.subtitle = e.accountName
        } else if e.kind == EntryKind.transfer || e.kind == EntryKind.cardPayment {
            words.title = format("entry.toAccount", e.toAccountName.isEmpty ? anAccount : e.toAccountName)
            words.subtitle = format("entry.fromAccount", e.accountName.isEmpty ? anAccount : e.accountName)
            if e.kind == EntryKind.cardPayment {
                words.note = text("entry.cardPayment")
            } else if e.countsAsSpending {
                words.note = text("entry.countsAsSpendingTitle")
            } else if !e.note.isEmpty {
                words.note = e.note
            } else {
                words.note = text("entry.transfer")
            }
        } else if e.kind == EntryKind.buy || e.kind == EntryKind.sell {
            words.title = format(e.kind == EntryKind.buy ? "entry.bought" : "entry.sold", e.note)
            words.subtitle = e.accountName
            words.note = text("entry.investing")
        } else if e.kind == EntryKind.personIn || e.kind == EntryKind.personOut {
            if words.title.isEmpty { words.title = personTitle(e) }
            words.subtitle = e.accountName
            words.note = text("entry.settleUp")
        } else {
            if words.title.isEmpty { words.title = e.categoryName.isEmpty ? uncategorized : e.categoryName }
            var parts: [String] = [e.categoryName.isEmpty ? uncategorized : e.categoryName]
            if !e.myShareText.isEmpty { parts.append(format("entry.yourShare", e.myShareText)) }
            if e.countsAsSpending { parts.append(text("entry.countsAsSpending")) }
            words.subtitle = parts.joined(separator: ", ")
            if !e.accountName.isEmpty {
                words.note = e.accountName
            } else if !e.personName.isEmpty {
                words.note = format("entry.paidBy", e.personName)
            }
        }
        return words
    }

    private static func personTitle(_ e: EntryRowData) -> String {
        let incoming = e.kind == EntryKind.personIn
        if e.personName.isEmpty {
            return incoming ? text("entry.someoneCapPaidYou") : text("entry.youPaidSomeone")
        }
        return format(incoming ? "entry.personPaidYou" : "entry.youPaidPerson", e.personName)
    }
}
