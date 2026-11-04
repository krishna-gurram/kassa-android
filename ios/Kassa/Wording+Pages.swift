import Foundation
import KassaShared

/// What the card, account and person pages say, in the words of Localizable.strings. The shared code says what a figure is (a status,
/// a kind); the sentences are made here so they can be translated and reordered.
extension Wording {

    // MARK: Card

    /// The line under Balance payable.
    static func cardStatus(_ card: CardPageData) -> String {
        let name = card.status.name
        if name == "PaidExtra" { return String(format: String(localized: "cardPage.status.paidExtra"), card.statusAmountText) }
        if name == "PaidInFull" { return String(localized: "cardPage.status.paidInFull") }
        if name == "NothingDue" { return String(localized: "cardPage.status.nothingDue") }
        if name == "DaysLate" { return String(format: String(localized: "cardPage.status.daysLate"), Int(card.statusDays)) }
        if name == "DueToday" { return String(localized: "cardPage.status.dueToday") }
        if name == "DueTomorrow" { return String(localized: "cardPage.status.dueTomorrow") }
        if name == "DueInDays" { return String(format: String(localized: "cardPage.status.dueInDays"), Int(card.statusDays)) }
        return String(format: String(localized: "cardPage.status.dueOn"), KassaDates.shortDate(epochDay: Int(card.dueDay)))
    }

    /// The title of the statement navigator: what is charged since the last statement, or the statement that closed on a date.
    static func cardStatementTitle(_ card: CardPageData) -> String {
        if card.back == 0 { return String(localized: "cardPage.outstanding") }
        return String(format: String(localized: "cardPage.statementOn"), KassaDates.shortDate(epochDay: Int(card.toDay)))
    }

    /// The line under it: the stretch it covers (a statement runs up to the day before its own date).
    static func cardStatementRange(_ card: CardPageData) -> String {
        if card.back == 0 {
            return String(
                format: String(localized: "cardPage.sinceCloses"),
                KassaDates.shortDate(epochDay: Int(card.lastCloseDay)), KassaDates.shortDate(epochDay: Int(card.nextCloseDay))
            )
        }
        if card.back == 1 { return String(format: String(localized: "cardPage.status.dueOn"), KassaDates.shortDate(epochDay: Int(card.dueDay))) }
        return String(
            format: String(localized: "cardPage.dateRange"),
            KassaDates.shortDate(epochDay: Int(card.fromDay)), KassaDates.shortDate(epochDay: Int(card.toDay) - 1)
        )
    }

    /// What is left to pay on the last statement, when the page shows it (never while hidden).
    static func cardLeft(_ card: CardPageData) -> String? {
        let name = card.left.name
        if name == "LeftToPay" { return String(format: String(localized: "cardPage.leftToPay"), card.leftText) }
        if name == "PaidExtra" { return String(format: String(localized: "cardPage.status.paidExtra"), card.leftText) }
        if name == "PaidInFull" { return String(localized: "cardPage.status.paidInFull") }
        return nil
    }

    // MARK: Pay card

    /// The small line under an option of the pay-card sheet.
    static func payOptionDetail(_ option: CardPayOptionData) -> String {
        if option.choice == "PAYABLE" { return String(format: String(localized: "payCard.dueOn"), KassaDates.weekdayDate(epochDay: Int(option.dueDay))) }
        if option.choice == "TOTAL" {
            if option.outstandingText.isEmpty { return String(localized: "payCard.everythingOwed") }
            return String(format: String(localized: "payCard.payablePlusOutstanding"), option.outstandingText)
        }
        return String(localized: "payCard.partialOrExtra")
    }

    static func payOptionTitle(_ option: CardPayOptionData) -> String {
        if option.choice == "PAYABLE" { return String(format: String(localized: "payCard.optionPayable"), option.amountText) }
        if option.choice == "TOTAL" { return String(format: String(localized: "payCard.optionTotal"), option.amountText) }
        return String(localized: "payCard.optionOther")
    }

    // MARK: Account

    static func accountBalanceTitle(_ page: AccountPageData) -> String {
        let name = page.balanceKind.name
        if name == "OutstandingBalance" { return String(localized: "accountPage.outstandingBalance") }
        if name == "TotalValue" { return String(localized: "accountPage.totalValue") }
        if name == "TotalBalance" { return String(localized: "accountPage.totalBalance") }
        return String(localized: "accountPage.balance")
    }

    // MARK: Person

    /// The label over the balance: who owes whom; only "Balance with" while hidden, so a direction or a zero is never given away.
    static func personBalanceTitle(_ page: PersonPageData) -> String {
        let side = page.side.name
        if side == "OwesYou" { return String(format: String(localized: "personPage.owesYou"), page.name) }
        if side == "YouOwe" { return String(format: String(localized: "personPage.youOwe"), page.name) }
        if side == "Settled" { return String(localized: "personPage.settled") }
        return String(format: String(localized: "personPage.balanceWith"), page.name)
    }

    /// The note of one line of the history: the person's own, else "Split" or who paid whom.
    static func personLineNote(_ line: PersonLineData, name: String) -> String {
        let kind = line.noteKind
        if kind == "SPLIT" { return String(localized: "personPage.split") }
        if kind == "PAID_YOU" { return String(format: String(localized: "entry.personPaidYou"), name) }
        if kind == "YOU_PAID" { return String(format: String(localized: "entry.youPaidPerson"), name) }
        return line.note
    }
}
