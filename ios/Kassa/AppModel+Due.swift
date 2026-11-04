import Foundation
import KassaShared

/// What the Notifications page, Overview's Upcoming rows and Settings > Bills show, worked out on the queue with the other figures.
struct DueFigures {
    let notices: NoticesData
    let upcoming: [UpcomingData]
    let bills: BillsData
}

/// The bill sheet that is open (`LedgerView.billSheet`, or a new bill, or one filled in from a suggestion).
struct BillSheetRequest: Identifiable {
    let id = UUID()
    let data: BillSheetData
}

/// The "Enter amount" sheet of a bill notice.
struct BillAmountRequest: Identifiable {
    let id = UUID()
    let notice: NoticeData
}

/// Due notices, batch I: the Notifications page and what it does, bills, the phone's notifications and where a tap goes. Every rule is
/// :domain's (`DueItems`, `DueNotices`, `BillRules`, `Repeats`), asked through `LedgerView` and `DueBridge` on the one queue; the writes are
/// `LedgerStore+Bills.swift`, one transaction each. Nothing posts by itself: every choice on the page is the person's, and Clear never adds or
/// pays anything.
extension AppModel {

    /// How many days ahead Overview's Upcoming looks (Android: 30).
    static let upcomingDays: Int32 = 30

    /// Works the due figures out of [made]. Queue only.
    func computeDue(_ made: LedgerView, _ given: Inputs) -> DueFigures {
        let day = Int32(given.today)
        let list: NoticesData = made.notices(hidden: given.hidden, todayEpochDay: day, timeZoneId: given.zone, cleared: given.cleared)
        let coming: [UpcomingData] = made.upcoming(
            hidden: given.hidden, todayEpochDay: day, timeZoneId: given.zone, days: AppModel.upcomingDays
        )
        let bills: BillsData = made.billsPage(hidden: given.hidden, todayEpochDay: day, timeZoneId: given.zone)
        return DueFigures(notices: list, upcoming: coming, bills: bills)
    }

    // MARK: The Notifications page

    func openNotices() {
        noticesOpen = true
    }

    func closeNotices() {
        noticesOpen = false
    }

    /// Clears [list] without adding or paying anything: a bill skips that month and a transfer stops asking (both in the database, in one
    /// transaction); a card payment, a loan payment or a suggested bill is remembered by its key on this phone.
    func clearNotices(_ list: [NoticeData], done: @escaping (WriteOutcome) -> Void) {
        var keys: [String] = []
        var bills: [BillMonth] = []
        var transfers: [Int64] = []
        for item in list {
            if item.remembers {
                keys.append(item.key)
            } else if item.kind == "BILL" {
                bills.append(BillMonth(id: item.id, month: item.handledMonth))
            } else if item.kind == "TRANSFER" {
                transfers.append(item.id)
            }
        }
        if !keys.isEmpty { DueSettings(defaults: defaults).clear(keys) }
        perform({ opened in
            try opened.clearNotices(bills: bills, transfers: transfers)
            return 0
        }, done: done)
    }

    /// Records the bill notice [notice] for [cents] as an expense and marks its month handled, in one transaction.
    func approveBill(_ notice: NoticeData, cents: Int64, done: @escaping (WriteOutcome) -> Void) {
        let zone = TimeZone.current.identifier
        let today = Int32(KassaDates.todayEpochDay())
        perform({ opened in
            return try opened.approveBill(billId: notice.id, amount: cents, dueDay: notice.dueDay, todayDay: today, zoneId: zone)
        }, done: done)
    }

    func openBillAmount(_ notice: NoticeData) {
        billAmountSheet = BillAmountRequest(notice: notice)
    }

    func closeBillAmountSheet() {
        billAmountSheet = nil
    }

    // MARK: Bills

    /// Opens bill [id] on its sheet, or a new bill when [id] is 0 (the day starts on today's, at most 28). Does nothing when the bill is gone.
    func openBill(id: Int64) {
        if id == 0 {
            let parts = KassaDates.gregorian.dateComponents([.day], from: KassaDates.date(epochDay: KassaDates.todayEpochDay()))
            let day: Int = min(parts.day ?? 1, 28)
            let fresh = BillSheetData(found: true, id: 0, name: "", categoryId: 0, accountId: 0, day: Int32(day), amount: "", active: true)
            billSheet = BillSheetRequest(data: fresh)
            return
        }
        queue.async {
            guard let made = self.view else { return }
            let data: BillSheetData = made.billSheet(billId: id)
            if !data.found { return }
            self.publish { self.billSheet = BillSheetRequest(data: data) }
        }
    }

    /// Opens a new bill filled in from a suggested one: its name, usual day, category and amount; the account is left to pick.
    func openBill(suggested notice: NoticeData) {
        let amount: String = BillBridge.shared.amountText(cents: notice.amountCents)
        let data = BillSheetData(
            found: true, id: 0, name: notice.name, categoryId: notice.categoryId, accountId: 0, day: notice.dayOfMonth, amount: amount, active: true
        )
        billSheet = BillSheetRequest(data: data)
    }

    func closeBillSheet() {
        billSheet = nil
    }

    /// Every problem of the bill as typed (a field name and a sentence each); empty when it can be saved.
    func billChecks(_ input: BillInput, done: @escaping ([FieldProblemData]) -> Void) {
        queue.async {
            var found: [FieldProblemData] = []
            if let made = self.view { found = made.billProblems(input: input) }
            self.publish { done(found) }
        }
    }

    func saveBill(_ input: BillInput, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in return try opened.saveBill(input) }, done: done)
    }

    func deleteBill(id: Int64, done: @escaping (WriteOutcome) -> Void) {
        perform({ opened in
            try opened.deleteBill(id: id)
            return id
        }, done: done)
    }

    // MARK: Phone notifications

    /// Hands what is due to the system as local notifications (`DueScheduler`). Queue only; called after every read of the store.
    func scheduleNotifications(_ made: LedgerView) {
        DueScheduler(settings: DueSettings(defaults: defaults), center: noticeCenter).refresh(view: made)
    }

    /// Plans again, for the app coming back to the front or a change of the rules.
    func refreshNotifications() {
        queue.async {
            guard let made = self.view else { return }
            self.scheduleNotifications(made)
        }
    }

    /// The person changed one kind's rule in Settings > Notifications.
    func setDueRule(_ kind: String, _ value: DueRuleValue) {
        let settings = DueSettings(defaults: defaults)
        settings.setRule(kind, value)
        refreshNotifications()
    }

    func setReminder(on: Bool, minute: Int) {
        let settings = DueSettings(defaults: defaults)
        settings.reminderOn = on
        settings.reminderMinute = minute
        refreshNotifications()
    }

    func noticeStatus(_ done: @escaping (NoticeStatus) -> Void) {
        noticeCenter.status { status in
            self.publish { done(status) }
        }
    }

    /// Shows the system's permission question from Settings (the person turned something on).
    func requestNoticePermission(done: @escaping (NoticeStatus) -> Void) {
        noticeCenter.requestAuthorization { _ in
            self.refreshNotifications()
            self.noticeStatus(done)
        }
    }

    /// After a card with a due day or a loan with terms was saved: Kassa explains why it wants to notify, once, and only where the system has not
    /// been asked yet. Never asked twice.
    func offerNotificationsIfNeeded() {
        queue.async {
            guard let made = self.view else { return }
            let settings = DueSettings(defaults: self.defaults)
            if settings.notifyAsked || !made.hasNotifiable() { return }
            self.noticeCenter.status { status in
                if status == .notAsked { self.publish { self.asksNotifications = true } }
            }
        }
    }

    /// Wraps the answer of a write that may have saved a card or a loan: once saved, the explanation may be offered.
    func offering(_ done: @escaping (WriteOutcome) -> Void) -> (WriteOutcome) -> Void {
        return { outcome in
            if case .saved = outcome { self.offerNotificationsIfNeeded() }
            done(outcome)
        }
    }

    /// The person answered the explanation: never asked again, and with a yes the system's question follows.
    func answerNotificationsAsk(allow: Bool) {
        let settings = DueSettings(defaults: defaults)
        settings.notifyAsked = true
        asksNotifications = false
        if allow {
            noticeCenter.requestAuthorization { _ in self.refreshNotifications() }
        }
    }

    // MARK: A tapped notification

    /// Goes where a tapped notification points: the card's page, the loan's page with its payment sheet, the bill's sheet, or a new entry for
    /// the daily reminder. Opening pays and adds nothing. A card, loan or bill that is gone leaves the Overview.
    func openDue(_ route: DueRoute) {
        queue.async {
            guard let made = self.view else { return }
            let account: AccountChoiceData? = made.accountChoice(id: route.id)
            let bill: BillSheetData = made.billSheet(billId: route.id)
            self.publish {
                self.noticesOpen = false
                switch route.kind {
                case "CARD":
                    self.show(account: account)
                case "LOAN":
                    self.show(account: account)
                    if let found = account, found.type == "LOAN" { self.openNewLoanPayment(accountId: found.id) }
                case "BILL":
                    self.requestedTab = .overview
                    if bill.found { self.billSheet = BillSheetRequest(data: bill) }
                case "REMINDER":
                    self.requestedTab = .overview
                    self.openAdd()
                default:
                    self.requestedTab = .overview
                }
            }
        }
    }

    private func show(account: AccountChoiceData?) {
        guard let found = account else {
            requestedTab = .overview
            return
        }
        requestedTab = .accounts
        openAccountPage(found.id)
    }
}
