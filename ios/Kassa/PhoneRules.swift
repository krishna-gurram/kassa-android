import Foundation
import KassaShared

/// Puts the phone's own language rules under the shared Kotlin ones: how it writes numbers (1,234.50, 1.234,50, 12,34,567.50)
/// and how it puts names in order. Called at start; the Kotlin side reads them on every use.
enum PhoneRules {

    static func install() {
        KassaCore.shared.installDefaults()
        applyLocale()
        KassaCore.shared.useCollator(collator: PhoneCollator())
        NotificationCenter.default.addObserver(forName: NSLocale.currentLocaleDidChangeNotification, object: nil, queue: .main) { _ in
            PhoneRules.applyLocale()
        }
    }

    /// Hands the phone's locale identifier and the marks it reports to the shared rules, which decide lakh grouping from the region.
    static func applyLocale() {
        let locale: Locale = Locale.current
        let formatter = NumberFormatter()
        formatter.locale = locale
        formatter.numberStyle = .decimal
        let decimal: String = formatter.decimalSeparator ?? "."
        let grouping: String = formatter.groupingSeparator ?? ","
        let zero: String = formatter.string(from: NSNumber(value: 0)) ?? "0"
        let minus: String = formatter.minusSign ?? "-"
        KassaCore.shared.useLocale(identifier: locale.identifier, decimal: decimal, grouping: grouping, zeroDigit: zero, minus: minus)
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
