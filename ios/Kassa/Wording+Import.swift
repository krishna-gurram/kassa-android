import Foundation
import KassaShared

/// The sentences of the import screens, made from what the shared rules say (`ImportIssueData` and friends). The text is Android's
/// (`strings_importer.xml`), kept in Localizable.strings under `import.*`.
enum ImportWording {

    private static func text(_ key: String.LocalizationValue) -> String {
        return String(localized: key)
    }

    /// The sentence for one line of the review's "stays behind" or "worth knowing" lists.
    static func issue(_ item: ImportIssueData) -> String {
        let count = Int(item.count)
        switch item.kind {
        case "DUPLICATE_ACCOUNTS":
            return String(format: text("import.issue.DUPLICATE_ACCOUNTS"), item.first)
        case "MISMATCHED_TRANSFERS":
            return String(format: text("import.issue.MISMATCHED_TRANSFERS"), item.countText)
        case "UNPAIRED_TRANSFERS":
            return String(format: text("import.issue.UNPAIRED_TRANSFERS"), count)
        case "ZERO_AMOUNT":
            return String(format: text("import.issue.ZERO_AMOUNT"), count, item.first)
        case "SKIPPED_ROWS":
            return String(format: text("import.issue.SKIPPED_ROWS"), count, item.first)
        case "MERGED_CATEGORIES":
            return String(format: text("import.issue.MERGED_CATEGORIES"), item.first)
        case "LEFT_BEHIND":
            return String(format: text("import.issue.LEFT_BEHIND"), item.first, item.countText)
        case "REFUNDS":
            return String(format: text("import.issue.REFUNDS"), item.countText)
        case "BALANCE_CORRECTIONS":
            return String(format: text("import.issue.BALANCE_CORRECTIONS"), item.countText)
        case "TRANSFERS_AS_ENTRIES":
            return String(format: text("import.issue.TRANSFERS_AS_ENTRIES"), item.countText)
        case "OTHER_CURRENCY":
            return String(format: text("import.issue.OTHER_CURRENCY"), item.first)
        case "DELETED_ACCOUNTS":
            return String(format: text("import.issue.DELETED_ACCOUNTS"), item.countText)
        case "CARDS_NO_PAY_FROM":
            return String(format: text("import.issue.CARDS_NO_PAY_FROM"), item.countText)
        default:
            return item.first
        }
    }

    /// The account kinds a Cashew account can be given, in Android's order (an `AccountType` name each).
    static let accountKinds: [String] = [
        "CHECKING", "SAVINGS", "FIXED_DEPOSIT", "CASH", "CREDIT_CARD", "INVESTMENT", "LOAN", "PREPAID", "OTHER_ASSET", "OTHER_DEBT",
    ]

    static func kindTitle(_ kind: String) -> String {
        if kind == "INVESTMENT" { return text("import.kind.INVESTMENT") }
        if kind == "FIXED_DEPOSIT" { return text("import.kind.FIXED_DEPOSIT") }
        if kind.isEmpty { return text("import.kind.pick") }
        return Wording.accountTypeTitle(kind)
    }

    static func checkAccounts(_ review: ImportReviewData) -> String {
        if review.accountsBad == 0 { return String(format: text("import.checkAccountsOk"), Int(review.accountChecks)) }
        return String(format: text("import.checkAccountsBad"), Int(review.accountsBad), review.accountsBadText)
    }

    static func checkMonths(_ review: ImportReviewData) -> String {
        if review.monthsBad == 0 { return String(format: text("import.checkMonthsOk"), Int(review.monthChecks)) }
        return String(format: text("import.checkMonthsBad"), Int(review.monthsBad), review.firstBadMonth)
    }

    static func categories(_ review: ImportReviewData) -> String {
        let active = NumberFormatter.localizedString(from: NSNumber(value: Int(review.categoriesActive)), number: .decimal)
        let retired = NumberFormatter.localizedString(from: NSNumber(value: Int(review.categoriesRetired)), number: .decimal)
        return String(format: text("import.categoriesValue"), active, retired)
    }

    static func number(_ value: Int32) -> String {
        return NumberFormatter.localizedString(from: NSNumber(value: Int(value)), number: .decimal)
    }

    static func loanLine(_ loan: ImportLoanData) -> String {
        let who = loan.lent ? text("import.cashew.loanLent") : text("import.cashew.loanBorrowed")
        let payments: String
        if loan.payments == 0 {
            payments = text("import.cashew.loanNoPayments")
        } else if loan.payments == 1 {
            payments = String(format: text("import.cashew.loanPaymentsOne"), loan.paymentsText)
        } else {
            payments = String(format: text("import.cashew.loanPaymentsOther"), loan.paymentsText)
        }
        return String(format: text("import.cashew.loanLine"), who, loan.amountText, payments)
    }

    static func currencyDetail(_ wallet: ImportWalletData) -> String {
        if !wallet.leaveOut {
            return String(format: text("import.cashew.bringDetail"), wallet.entriesText, wallet.codeAmount, wallet.phoneAmount)
        }
        if wallet.transfers > 0 {
            return String(format: text("import.cashew.leaveDetailTransfers"), wallet.entriesText, wallet.transfersText)
        }
        return String(format: text("import.cashew.leaveDetail"), wallet.entriesText)
    }

    /// What to do next after an import, one sentence each (Android's "Done" list).
    static func nextSteps(_ review: ImportReviewData) -> [String] {
        var lines: [String] = []
        for name in review.cardsWithoutStatement { lines.append(String(format: text("import.nextStatement"), name)) }
        for name in review.cardsWithoutPayFrom { lines.append(String(format: text("import.nextPayFrom"), name)) }
        if review.people > 0 { lines.append(String(format: text("import.nextPeople"), review.peopleNames)) }
        if review.bills > 0 { lines.append(String(format: text("import.nextBills"), number(review.bills))) }
        return lines
    }
}
