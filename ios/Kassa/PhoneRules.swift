import Foundation
import KassaShared

/// Puts the phone's own language rules under the shared Kotlin ones: how it writes numbers (1,234.50, 1.234,50, 12,34,567.50)
/// and how it puts names in order. Called at start; the Kotlin side reads them on every use.
enum PhoneRules {

    static func install() {
        KassaCore.shared.installDefaults()
        let locale = Locale.current
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        let decimal = formatter.decimalSeparator ?? "."
        let grouping = formatter.groupingSeparator ?? ","
        let zero = formatter.string(from: NSNumber(value: 0)) ?? "0"
        let region = locale.region?.identifier ?? ""
        let lakh = ["IN", "BD", "NP"].contains(region)
        KassaCore.shared.useNumberStyle(decimal: decimal, grouping: grouping, zeroDigit: zero, lakh: lakh)
        KassaCore.shared.useCollator(collator: PhoneCollator())
    }
}

/// A to Z in the phone's language, ignoring case and accents, like Android's primary-strength collator.
final class PhoneCollator: NSObject, TextCollator {

    func compare(a: String, b: String) -> Int32 {
        let options: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]
        let result = a.compare(b, options: options, range: nil, locale: Locale.current)
        if result == .orderedAscending { return -1 }
        if result == .orderedDescending { return 1 }
        return 0
    }
}
