import Foundation
import UserNotifications
import KassaShared

/// Where the phone stands on Kassa's notifications.
enum NoticeStatus: Equatable {
    /// Never asked: the system will show its question.
    case notAsked
    case allowed
    case denied
}

/// One local notification to hand to the system. [components] are the wall-clock moment on the phone ("fire at 09:00 on 14 November"),
/// nil for one that rings in [seconds]; [repeats] is for the daily reminder (hour and minute only). The text is generic by design: a
/// notification never carries an amount or a name on the lock screen.
struct ScheduledNotice: Equatable {
    let identifier: String
    let title: String
    let body: String
    let components: DateComponents?
    let seconds: Double
    let repeats: Bool
    let kind: String
    let id: Int64
}

/// What the scheduler asks of the system's notification centre, so a test can stand in for it.
protocol NoticeCenter: AnyObject {
    func status(_ done: @escaping (NoticeStatus) -> Void)
    /// Shows the system's permission question (once; the system never asks twice).
    func requestAuthorization(_ done: @escaping (Bool) -> Void)
    /// Removes every pending notification whose identifier starts with [prefix], then adds [notices].
    func replace(prefix: String, with notices: [ScheduledNotice], done: @escaping () -> Void)
}

/// The real centre. Nothing here touches the system until it is used, so building an `AppModel` in a test costs nothing.
final class SystemNoticeCenter: NoticeCenter {

    private var center: UNUserNotificationCenter {
        return UNUserNotificationCenter.current()
    }

    func status(_ done: @escaping (NoticeStatus) -> Void) {
        center.getNotificationSettings { settings in
            switch settings.authorizationStatus {
            case .notDetermined:
                done(.notAsked)
            case .authorized, .provisional:
                done(.allowed)
            default:
                done(.denied)
            }
        }
    }

    func requestAuthorization(_ done: @escaping (Bool) -> Void) {
        center.requestAuthorization(options: [.alert, .sound]) { granted, _ in
            done(granted)
        }
    }

    func replace(prefix: String, with notices: [ScheduledNotice], done: @escaping () -> Void) {
        let target = center
        target.getPendingNotificationRequests { pending in
            var old: [String] = []
            for request in pending where request.identifier.hasPrefix(prefix) { old.append(request.identifier) }
            target.removePendingNotificationRequests(withIdentifiers: old)
            for notice in notices {
                target.add(SystemNoticeCenter.request(notice), withCompletionHandler: nil)
            }
            done()
        }
    }

    static func request(_ notice: ScheduledNotice) -> UNNotificationRequest {
        let content = UNMutableNotificationContent()
        content.title = notice.title
        content.body = notice.body
        content.sound = UNNotificationSound.default
        content.threadIdentifier = "kassa.due"
        content.userInfo = ["kind": notice.kind, "id": NSNumber(value: notice.id)]
        let trigger: UNNotificationTrigger
        if let parts = notice.components {
            trigger = UNCalendarNotificationTrigger(dateMatching: parts, repeats: notice.repeats)
        } else {
            trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(notice.seconds, 1), repeats: false)
        }
        return UNNotificationRequest(identifier: notice.identifier, content: content, trigger: trigger)
    }
}

/// Phone notifications for payments that come due (batch I). The rules (what is due, which day and time, never twice for one due date, at
/// most 14 days ahead, a short grace for one just missed, 60 days and 60 notices of plan) are :domain's (`DueNotices.plan`), asked
/// through `LedgerView.notificationPlan`; this only hands the plan to the system as local notifications with calendar triggers, so they ring
/// at their time without the app running. iOS cannot wake the app at 00:05 as Android's alarm does: a card's new statement is noticed the
/// next time the app opens (start, coming back to it, any change), which refreshes the plan.
///
/// The text is the same for every notice and carries no amount and no name, so nothing reaches a locked screen. A notice only tells; it never
/// pays or adds anything. A tap opens the card, the loan payment or the bill (`NotificationRouter`).
final class DueScheduler {

    /// Every identifier Kassa schedules starts with this, so one call can replace them all.
    static let prefix = "kassa."
    static let duePrefix = "kassa.due."
    static let reminderIdentifier = "kassa.reminder"
    /// A key is kept this many days past its due date, then forgotten.
    static let keepDays = 60

    let settings: DueSettings
    let center: NoticeCenter

    init(settings: DueSettings, center: NoticeCenter) {
        self.settings = settings
        self.center = center
    }

    // MARK: Words

    static func publicTitle() -> String {
        return String(localized: "due.publicTitle")
    }

    static func publicBody() -> String {
        return String(localized: "due.publicBody")
    }

    // MARK: Plan to notices

    /// The wall-clock components of [millis] on [zone]'s calendar.
    static func components(millis: Int64, zone: TimeZone) -> DateComponents {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = zone
        let date = Date(timeIntervalSince1970: Double(millis) / 1000)
        return calendar.dateComponents([.year, .month, .day, .hour, .minute], from: date)
    }

    /// The system notices of a plan: one per planned notice (a late one rings in a few seconds), and the daily reminder when it is on.
    static func notices(
        plan: [PlannedNotice], nowMillis: Int64, zone: TimeZone, reminderOn: Bool, reminderMinute: Int
    ) -> [ScheduledNotice] {
        var out: [ScheduledNotice] = []
        for item in plan {
            var parts: DateComponents? = nil
            var seconds: Double = 0
            if item.late {
                seconds = Double(item.epochMillis - nowMillis) / 1000
            } else {
                parts = DueScheduler.components(millis: item.epochMillis, zone: zone)
            }
            out.append(ScheduledNotice(
                identifier: DueScheduler.duePrefix + item.key, title: item.title, body: item.body, components: parts, seconds: seconds,
                repeats: false, kind: item.kind, id: item.id
            ))
        }
        if reminderOn {
            var daily = DateComponents()
            daily.hour = reminderMinute / 60
            daily.minute = reminderMinute % 60
            out.append(ScheduledNotice(
                identifier: DueScheduler.reminderIdentifier, title: String(localized: "due.reminderTitle"),
                body: String(localized: "due.reminderBody"), components: daily, seconds: 0, repeats: true, kind: "REMINDER", id: 0
            ))
        }
        return out
    }

    /// The posted keys after [now]: those already posted plus every key of the last plan whose moment has passed, minus keys more than
    /// [keepDays] past their due date (the last part of a key is its due day, or an id for a key that has none, which is kept).
    static func postedKeys(
        posted: [String], lastPlan: [String: Double], nowMillis: Int64, today: Int
    ) -> [String] {
        var keys: [String] = posted
        for (key, millis) in lastPlan where Int64(millis) <= nowMillis && !keys.contains(key) { keys.append(key) }
        var kept: [String] = []
        for key in keys {
            let parts: [Substring] = key.split(separator: ":")
            if parts.count >= 3, let day = Int(String(parts[parts.count - 1])), day < today - DueScheduler.keepDays { continue }
            kept.append(key)
        }
        return kept
    }

    // MARK: Refresh

    /// Works the plan out of [view] (queue only: it reads the ledger) and replaces what the system holds. Safe to repeat. Without permission
    /// nothing is scheduled and nothing is marked as rung, so it can still ring once the permission comes.
    func refresh(view: LedgerView, now: Date = Date(), zone: TimeZone = TimeZone.current, done: @escaping () -> Void = {}) {
        let nowMillis = Int64(now.timeIntervalSince1970 * 1000)
        let today = KassaDates.todayEpochDay(now: now)
        let posted = DueScheduler.postedKeys(posted: settings.posted, lastPlan: settings.lastPlan, nowMillis: nowMillis, today: today)
        let wanted: Bool = settings.anyEnabled || settings.reminderOn
        var plan: [PlannedNotice] = []
        if settings.anyEnabled {
            plan = view.notificationPlan(
                rules: settings.input(), nowMillis: nowMillis, timeZoneId: zone.identifier, posted: posted, cleared: settings.cleared,
                title: DueScheduler.publicTitle(), body: DueScheduler.publicBody()
            )
        }
        let notices = DueScheduler.notices(
            plan: plan, nowMillis: nowMillis, zone: zone, reminderOn: settings.reminderOn, reminderMinute: settings.reminderMinute
        )
        let remember = settings
        var planned: [String: Double] = [:]
        for item in plan { planned[item.key] = Double(item.epochMillis) }
        let target = center
        target.status { status in
            if !wanted || status != .allowed {
                remember.posted = posted
                remember.lastPlan = [:]
                target.replace(prefix: DueScheduler.prefix, with: [], done: done)
                return
            }
            remember.posted = posted
            remember.lastPlan = planned
            target.replace(prefix: DueScheduler.prefix, with: notices, done: done)
        }
    }

    /// Takes every notification of Kassa down and forgets what was rung (a restore replaced the accounts they name).
    func forget(done: @escaping () -> Void = {}) {
        settings.forgetAccountReferences()
        center.replace(prefix: DueScheduler.prefix, with: [], done: done)
    }
}

/// Where a tapped notification goes: what it was about ([kind] is CARD, LOAN, BILL or REMINDER) and the card's, loan's or bill's id.
struct DueRoute: Equatable {
    let kind: String
    let id: Int64
}

/// The delegate of the notification centre: shows a notification that arrives while Kassa is open, and turns a tap into a `DueRoute`.
/// A tap that arrives before the app can act on it (a cold start) waits in [pending]; the shell takes it once it is up.
final class NotificationRouter: NSObject, UNUserNotificationCenterDelegate {

    static let shared = NotificationRouter()
    static let opened = Notification.Name("app.kassa.openDue")

    private(set) var pending: DueRoute?

    func install() {
        UNUserNotificationCenter.current().delegate = self
    }

    func takePending() -> DueRoute? {
        let route = pending
        pending = nil
        return route
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter, willPresent notification: UNNotification,
        withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void
    ) {
        completionHandler([.banner, .list, .sound])
    }

    func userNotificationCenter(
        _ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse, withCompletionHandler completionHandler: @escaping () -> Void
    ) {
        let info = response.notification.request.content.userInfo
        if let kind = info["kind"] as? String {
            let number: NSNumber? = info["id"] as? NSNumber
            pending = DueRoute(kind: kind, id: number?.int64Value ?? 0)
            NotificationCenter.default.post(name: NotificationRouter.opened, object: nil)
        }
        completionHandler()
    }
}
