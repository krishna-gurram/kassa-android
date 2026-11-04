import Foundation
import KassaShared

/// The words of the loan screens (stage G1). The shared code says which kind, which type, which stage; the sentences are made here so
/// they can be translated and reordered.
extension Wording {

    /// A `LoanKind` name on the setup form's chips.
    static func loanKindTitle(_ key: String) -> String {
        switch key {
        case "REDUCING": return String(localized: "loan.kind.REDUCING")
        case "DAILY": return String(localized: "loan.kind.DAILY")
        case "FLAT": return String(localized: "loan.kind.FLAT")
        case "INTEREST_ONLY": return String(localized: "loan.kind.INTEREST_ONLY")
        case "ISLAMIC": return String(localized: "loan.kind.ISLAMIC")
        default: return key
        }
    }

    /// The one line under the kind chips saying what the picked kind means.
    static func loanKindHint(_ key: String) -> String {
        switch key {
        case "REDUCING": return String(localized: "loan.hint.REDUCING")
        case "DAILY": return String(localized: "loan.hint.DAILY")
        case "FLAT": return String(localized: "loan.hint.FLAT")
        case "INTEREST_ONLY": return String(localized: "loan.hint.INTEREST_ONLY")
        case "ISLAMIC": return String(localized: "loan.hint.ISLAMIC")
        default: return ""
        }
    }

    /// An `IslamicContract` name.
    static func loanContractTitle(_ key: String) -> String {
        switch key {
        case "MURABAHA": return String(localized: "loan.contract.MURABAHA")
        case "IJARA": return String(localized: "loan.contract.IJARA")
        default: return key
        }
    }

    /// A `PaymentBasis` name.
    static func loanBasisTitle(_ key: String) -> String {
        switch key {
        case "INCLUDES_ESCROW": return String(localized: "loan.basis.INCLUDES_ESCROW")
        default: return String(localized: "loan.basis.PRINCIPAL_AND_INTEREST")
        }
    }

    /// An `InterestType` name on the loan page's Terms.
    static func loanTypeTitle(_ key: String) -> String {
        switch key {
        case "REDUCING": return String(localized: "loan.kind.REDUCING")
        case "DAILY": return String(localized: "loan.kind.DAILY")
        case "FLAT": return String(localized: "loan.kind.FLAT")
        case "INTEREST_ONLY": return String(localized: "loan.kind.INTEREST_ONLY")
        default: return key
        }
    }

    /// "Interest" or, for an Islamic loan, "Profit", for the lines that name it.
    static func interestWord(_ profit: Bool) -> String {
        if profit { return String(localized: "loan.profit") }
        return String(localized: "loan.interest")
    }

    /// "<Interest> paid" and "<Interest> remaining" and "<Interest> rate".
    static func interestPaidLabel(_ profit: Bool) -> String {
        return String(format: String(localized: "loan.paidSoFar"), interestWord(profit))
    }

    static func interestLeftLabel(_ profit: Bool) -> String {
        return String(format: String(localized: "loan.remaining"), interestWord(profit))
    }

    static func rateLabel(_ profit: Bool) -> String {
        return String(format: String(localized: "loan.rateOf"), interestWord(profit))
    }

    /// The tag under a loan payment's entry ("principal", "interest", "escrow", "charge") is :domain's.
    static func payoffLine(day: Int) -> String {
        if day <= 0 { return String(localized: "loan.noPayoff") }
        return KassaDates.shortDateYear(epochDay: day)
    }

    static func dueLine(day: Int) -> String {
        return String(format: String(localized: "loan.due"), KassaDates.shortDateYear(epochDay: day))
    }

    static func termMonthsLine(_ months: Int) -> String {
        return String(format: String(localized: "loan.termMonths"), months)
    }

    static func dueDayLine(_ day: Int) -> String {
        return String(format: String(localized: "loan.dueDay"), day)
    }
}
