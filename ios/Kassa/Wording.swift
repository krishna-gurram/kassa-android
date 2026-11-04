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

    /// The title of an account picker group (an `AccountSection` name).
    static func sectionTitle(_ key: String) -> String {
        switch key {
        case "CASH_AND_BANK": return text("accounts.group.cashAndBank")
        case "INVESTMENTS": return text("accounts.group.investments")
        case "OTHER_ASSETS": return text("accounts.group.otherAssets")
        case "CREDIT_CARDS": return text("accounts.group.creditCards")
        case "LOANS": return text("accounts.group.loans")
        case "OTHER_DEBTS": return text("accounts.group.otherDebts")
        default: return key
        }
    }

    /// The label of a kind chip in the account picker (an `AccountKindFilter` name).
    static func filterTitle(_ key: String) -> String {
        switch key {
        case "ALL": return text("entry.filter.all")
        case "CASH_AND_BANK": return text("accounts.group.cashAndBank")
        case "CARDS": return text("entry.filter.cards")
        case "LOANS": return text("accounts.group.loans")
        case "INVESTMENTS": return text("accounts.group.investments")
        default: return text("entry.filter.other")
        }
    }

    /// The heading of a kind group in the Add account list.
    static func kindGroupTitle(_ key: String) -> String {
        switch key {
        case "CASH_AND_BANK": return text("accounts.group.cashAndBank")
        case "CREDIT_AND_LOANS": return text("account.group.creditAndLoans")
        default: return text("account.group.other")
        }
    }

    /// The name of a kind of account (an `AccountType` name) in the Add account list and its form.
    static func accountTypeTitle(_ key: String) -> String {
        switch key {
        case "CHECKING": return text("accountType.CHECKING")
        case "SAVINGS": return text("accountType.SAVINGS")
        case "CASH": return text("accountType.CASH")
        case "PREPAID": return text("accountType.PREPAID")
        case "CREDIT_CARD": return text("accountType.CREDIT_CARD")
        case "LOAN": return text("accountType.LOAN")
        case "OTHER_ASSET": return text("accountType.OTHER_ASSET")
        case "OTHER_DEBT": return text("accountType.OTHER_DEBT")
        default: return key
        }
    }

    /// The small line under an account's name.
    static func note(_ row: AccountRowData) -> String? {
        if row.note.name == "TotalBalance" { return format("accounts.totalBalance", row.noteAmountText) }
        if row.note.name == "Outstanding" { return format("accounts.outstanding", row.noteAmountText) }
        if row.note.name == "NothingToPay" { return text("accounts.nothingToPay") }
        if row.dueKind.name == "Closed" { return text("accounts.keptInHistory") }
        return nil
    }

    /// The small line under the main figure: when a payment is due, a deposit matures, an account was closed, or who owes whom.
    static func secondLine(_ row: AccountRowData) -> String? {
        let day = Int(row.dueDay)
        if row.dueKind.name == "Due" { return format("accounts.dueOn", KassaDates.shortDate(epochDay: day)) }
        if row.dueKind.name == "Matures" { return format("accounts.maturesOn", KassaDates.shortDate(epochDay: day)) }
        if row.dueKind.name == "Closed" { return format("accounts.closedOn", KassaDates.shortDate(epochDay: day)) }
        if row.side.name == "OwesYou" { return text("accounts.owesYou") }
        if row.side.name == "YouOwe" { return text("accounts.youOwe") }
        if row.side.name == "Settled" { return text("accounts.settled") }
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
        if kind.name == "Transfer" || kind.name == "CardPayment" { return "arrow.left.arrow.right" }
        if kind.name == "Buy" { return "chart.line.uptrend.xyaxis" }
        if kind.name == "Sell" { return "chart.line.downtrend.xyaxis" }
        if kind.name == "Correction" { return "slider.horizontal.3" }
        if kind.name == "PersonIn" || kind.name == "PersonOut" { return "person" }
        return "tag"
    }

    /// The title, the line under it and the small note under the amount, as Android's entry row words them.
    static func entry(_ e: EntryRowData) -> EntryWords {
        let anAccount = text("entry.anAccount")
        let uncategorized = text("entry.uncategorized")
        var words = EntryWords(title: e.note, subtitle: "", note: "")
        if e.kind.name == "Correction" {
            if words.title.isEmpty { words.title = text("entry.balanceCorrection") }
            words.subtitle = e.accountName
        } else if e.kind.name == "Transfer" || e.kind.name == "CardPayment" {
            words.title = format("entry.toAccount", e.toAccountName.isEmpty ? anAccount : e.toAccountName)
            words.subtitle = format("entry.fromAccount", e.accountName.isEmpty ? anAccount : e.accountName)
            if e.kind.name == "CardPayment" {
                words.note = text("entry.cardPayment")
            } else if e.countsAsSpending {
                words.note = text("entry.countsAsSpendingTitle")
            } else if !e.note.isEmpty {
                words.note = e.note
            } else {
                words.note = text("entry.transfer")
            }
        } else if e.kind.name == "Buy" || e.kind.name == "Sell" {
            words.title = format(e.kind.name == "Buy" ? "entry.bought" : "entry.sold", e.note)
            words.subtitle = e.accountName
            words.note = text("entry.investing")
        } else if e.kind.name == "PersonIn" || e.kind.name == "PersonOut" {
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
        let incoming = e.kind.name == "PersonIn"
        if e.personName.isEmpty {
            return incoming ? text("entry.someoneCapPaidYou") : text("entry.youPaidSomeone")
        }
        return format(incoming ? "entry.personPaidYou" : "entry.youPaidPerson", e.personName)
    }
}
