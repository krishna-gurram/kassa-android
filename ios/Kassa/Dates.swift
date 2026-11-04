import Foundation

/// Dates for people, written with the phone's own pattern for each template (never a hardcoded pattern), so "14 Nov" in the
/// UK and "Nov 14" in the US. The shared Kotlin code hands over calendar days as epoch days (days since 1970-01-01) and
/// months as year and month numbers; they are formatted in UTC so a calendar day never shifts with the phone's zone.
/// Formatters are cached, so call from the main thread (screens do).
enum KassaDates {

    static let utc: TimeZone = TimeZone(secondsFromGMT: 0) ?? TimeZone.current
    private static var formatters: [String: DateFormatter] = [:]

    static var gregorian: Calendar { gregorianUTC }

    private static var gregorianUTC: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = utc
        return calendar
    }

    private static func formatter(_ template: String) -> DateFormatter {
        let key = template + "|" + Locale.current.identifier
        if let cached = formatters[key] { return cached }
        let made = DateFormatter()
        made.locale = Locale.current
        made.timeZone = utc
        made.setLocalizedDateFormatFromTemplate(template)
        formatters[key] = made
        return made
    }

    static func date(epochDay: Int) -> Date {
        return Date(timeIntervalSince1970: Double(epochDay) * 86_400)
    }

    /// The epoch day of the calendar day [date] falls on in UTC (what the graphical calendar hands back).
    static func epochDay(of date: Date) -> Int {
        return Int((date.timeIntervalSince1970 / 86_400).rounded(.down))
    }

    /// "Nov 14" / "14 Nov".
    static func shortDate(epochDay: Int) -> String {
        return formatter("MMMd").string(from: date(epochDay: epochDay))
    }

    /// "Nov 14, 2026" / "14 Nov 2026".
    static func shortDateYear(epochDay: Int) -> String {
        return formatter("yMMMd").string(from: date(epochDay: epochDay))
    }

    /// "Fri, Nov 14" / "Fri 14 Nov".
    static func weekdayDate(epochDay: Int) -> String {
        return formatter("EEEMMMd").string(from: date(epochDay: epochDay))
    }

    /// "November 2026".
    static func monthYear(year: Int, month: Int) -> String {
        var parts = DateComponents()
        parts.year = year
        parts.month = month
        parts.day = 1
        let first = gregorianUTC.date(from: parts) ?? Date(timeIntervalSince1970: 0)
        return formatter("yMMMM").string(from: first)
    }

    /// Today as an epoch day on the phone's calendar day (its own time zone).
    static func todayEpochDay(now: Date = Date()) -> Int {
        var local = Calendar(identifier: .gregorian)
        local.timeZone = TimeZone.current
        let parts = local.dateComponents([.year, .month, .day], from: now)
        let day = gregorianUTC.date(from: parts) ?? Date(timeIntervalSince1970: 0)
        return Int((day.timeIntervalSince1970 / 86_400).rounded())
    }

    /// The year and month that contain [epochDay].
    static func yearMonth(epochDay: Int) -> (year: Int, month: Int) {
        let parts = gregorianUTC.dateComponents([.year, .month], from: date(epochDay: epochDay))
        return (parts.year ?? 1970, parts.month ?? 1)
    }

    /// The month before or after, a whole number of months away.
    static func shifted(year: Int, month: Int, by months: Int) -> (year: Int, month: Int) {
        let index = year * 12 + (month - 1) + months
        let newYear = Int((Double(index) / 12).rounded(.down))
        let newMonth = index - newYear * 12 + 1
        return (newYear, newMonth)
    }
}
