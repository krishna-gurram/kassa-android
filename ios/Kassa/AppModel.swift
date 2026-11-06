import Foundation
import SwiftUI
import Combine
import UIKit
import KassaShared

/// Where the app is in its start.
enum AppPhase {
    case opening
    /// The database is empty and nothing was chosen yet: restore a backup or start fresh.
    case firstRun
    case ready
    /// The database could not be opened; the text is already worded for the person.
    case failed(String)
}

/// The app's state: it opens the encrypted `LedgerStore`, builds the shared `LedgerView` from it on a background queue, and
/// publishes the figures the screens lay out. Hide balances (the eye) lives here and is remembered in UserDefaults.
///
/// All store and `LedgerView` work runs on ONE serial queue (the shared rules keep a few globals while they format, so two
/// calls at once must not overlap); results come back to the main thread. Properties marked "queue only" are touched
/// nowhere else. Failures reach the screen only as plain sentences (`BackupError`, `BackupBridge.refusal`, Localizable.strings).
///
/// The currency is the backup's, else the one picked on first run (which starts as the phone's region currency when Kassa lists it,
/// and otherwise as nothing: Start fresh and Choose backup file ask first), saved in the store (`LedgerStore.currencyCode`) so moving
/// regions never changes entered amounts.
///
/// The write path (`addEntry`, `updateEntry`, `deleteEntry`, `addAccount`, `addPerson`, `startFresh`) runs on the same queue,
/// inside one store transaction each, then reads the figures again; the answer comes back on the main thread as a `WriteOutcome`.
/// Anything that words an amount (a validation sentence, the delete question) is asked on the queue too, because the shared rules
/// switch Money's currency and mask while they work. Pure pickers read `picks` on the main thread (they touch no Money).
final class AppModel: ObservableObject {

    static let hideKey = "hideAmounts"
    static let startedFreshKey = "startedFresh"

    @Published private(set) var phase: AppPhase = .opening
    @Published private(set) var accounts: AccountsData?
    @Published private(set) var activity: ActivityData?
    @Published private(set) var overview: OverviewData?
    @Published private(set) var insights: InsightsData?
    /// The category page of Insights that is open (`openCategoryId`), once worked out.
    @Published private(set) var categoryPage: CategoryPageData?
    /// Insights' month: how many budget months before the current one (0 is the current month).
    @Published var insightsBack: Int = 0
    /// The Insights category that is open (0 is spending with no category), or nil on the Insights list.
    @Published private(set) var openCategoryId: Int64?
    /// Batch J (`AppModel+Read.swift`): Insights' Patterns tiles and week view, the open page of the read stack, the budgets list and
    /// Activity's search result, once worked out.
    @Published private(set) var patterns: PatternsData?
    @Published private(set) var week: WeekData?
    @Published private(set) var readPage: ReadPage?
    @Published private(set) var budgets: BudgetsData?
    @Published private(set) var searchResult: SearchData?
    /// Insights shows its week view instead of its month.
    @Published var insightsWeek = false
    /// Activity's Spending or Income filter (ALL, EXPENSE or INCOME) and its search text ("" is no search).
    @Published var activityFilter = "ALL"
    @Published var searchText = ""
    /// The pages opened over the tabs (spending, day, store, detail), last is on top. Empty when none is open.
    @Published var readStack: [ReadRoute] = []
    /// True while Settings > Budgets is open (it works its figures out only then).
    @Published var budgetsOpen = false
    /// The currency code the ledger shows ("USD"), or "" before the store is read.
    @Published private(set) var currency: String = ""
    @Published private(set) var shownYear: Int
    @Published private(set) var shownMonth: Int
    @Published private(set) var today: Int
    @Published private(set) var hideAmounts: Bool
    /// The sentence to show after a restore failed (or a wrong password), or nil.
    @Published var restoreMessage: String?
    @Published var working = false
    /// True while the password prompt is up for a protected backup.
    @Published var asksPassword = false
    /// The currency picked on first run, a code Kassa lists; nil until one is picked (the phone's region currency counts when listed).
    @Published var chosenCurrency: String?
    /// True while the currency list is up because a backup was read that carries no currency and none was picked.
    @Published var asksCurrencyForBackup = false
    /// The ledger the pickers read (categories, accounts, what an entry holds). Replaced after every read of the store.
    @Published private(set) var picks: LedgerView?
    /// The entry page that is open (new or editing), or nil.
    @Published var entryPage: EntryPageRequest?
    /// The account or person page that is open on the Accounts tab (`AppModel+Pages.swift`), or nil on the Accounts list.
    @Published var openPage: PageTarget?
    /// The open page's figures, once worked out: an account page, and for a card with statement days its card page too; or a person page.
    @Published var accountPage: AccountPageData?
    @Published var cardPage: CardPageData?
    @Published var personPage: PersonPageData?
    /// A loan account's page (stage G1): set instead of nothing when the open account is a loan with terms.
    @Published var loanPage: LoanPageData?
    /// True while the loan page shows its whole payment schedule ("Show full schedule").
    @Published var loanAll = false
    /// An investment account's page (batch H): set when the open account is an investment account.
    @Published var investPage: InvestPageData?
    /// The investment sheets that are open, or nil (`AppModel+Invest.swift`): a holding, Update prices, Buy or sell, an existing buy or sale,
    /// and the "Where did it go?" sheet of a waiting transfer.
    @Published var holdingSheet: HoldingSheetRequest?
    @Published var pricesSheet: PricesSheetRequest?
    @Published var tradeSheet: TradeSheetRequest?
    @Published var tradeEditSheet: TradeEditRequest?
    @Published var allocateSheet: AllocateRequest?
    /// The balance correction sheet that is open, or nil (`AppModel+Loans.swift`).
    @Published var correctionSheet: CorrectionRequest?
    /// The "Add terms" sheet of a loan that has none, or nil.
    @Published var termsSheet: TermsRequest?
    /// The loan payment sheet (new or editing), the Change terms page and the Edit details sheet that are open, or nil (`AppModel+LoanPayments.swift`).
    @Published var loanPaymentSheet: LoanPaymentRequest?
    @Published var loanChangeSheet: LoanChangeRequest?
    @Published var loanDetailsSheet: LoanDetailsRequest?
    /// How far back the open page is: months for an account page, statements for a card page (0 is the current stretch).
    @Published var pageBack: Int = 0
    /// The pay-card sheet that is open, or nil.
    @Published var paySheet: PaySheetRequest?
    /// The person payment sheet that is open (new or editing), or nil.
    @Published var personSheet: PersonSheetRequest?
    /// The "Split a transaction" sheet of a person's page, or nil (`AppModel+Split.swift`).
    @Published var splitSheet: SplitSheetRequest?
    /// What waits on the Notifications page, Overview's Upcoming rows and the bills list (`AppModel+Due.swift`), once worked out.
    @Published private(set) var notices: NoticesData?
    @Published private(set) var upcoming: [UpcomingData] = []
    @Published private(set) var billsPage: BillsData?
    /// True while the Notifications page replaces Overview.
    @Published var noticesOpen = false
    /// The bill sheet and the "Enter amount" sheet of a bill notice that are open, or nil.
    @Published var billSheet: BillSheetRequest?
    @Published var billAmountSheet: BillAmountRequest?
    /// True while Kassa explains why it wants to notify, before the system asks (after the first card with a due day or loan with terms).
    @Published var asksNotifications = false
    /// A tab a tapped notification asks the shell to show, or nil.
    @Published var requestedTab: ShellTab?
    /// The import from another app (Settings > Import from another app, and a Money Manager or Cashew file chosen on first run).
    lazy var importer: ImportModel = ImportModel(app: self)
    /// A file handed to the app (Files, "Open in Kassa") before it was ready for it; read once it is (`AppModel+OpenFile.swift`).
    var pendingOpenURL: URL?
    /// The Inbox copy of a file being imported or restored; removed when that ends (`AppModel+OpenFile.swift`).
    var openedInboxURL: URL?
    var inboxWatch: [AnyCancellable] = []
    var inboxSwept = false
    /// Counts the files that need the Settings tab (its password and replace questions live there); the tab bar watches it.
    @Published var settingsRequest: Int = 0
    /// A plain sentence about something that worked in Settings ("Restored."), or nil.
    @Published var notice: String?
    /// A backup made and waiting to be saved with the file exporter (`AppModel+Settings.swift`), or nil.
    @Published var pendingExport: BackupExport?
    /// Settings > Automatic backups (`AppModel+AutoBackup.swift`): what the page shows, whether a backup is running, and a request to open the
    /// page (the "Backup needs attention" notification was tapped).
    @Published var autoBackupInfo = AutoBackupInfo()
    @Published var autoBackupRunning = false
    @Published var backupPageRequested = false
    /// The one line about a backup read from Settings ("12 entries, 3 accounts, 8 categories"), shown in the "Replace everything?"
    /// question; nil when no such question is open.
    @Published var asksReplace: String?

    // Internal (not private) so `AppModel+Write.swift` can use the one queue and the store.
    let queue = DispatchQueue(label: "app.kassa.ledger", qos: .userInitiated)
    let defaults: UserDefaults
    /// The system's notification centre (or a stand-in in tests): `AppModel+Due.swift` schedules through it.
    let noticeCenter: NoticeCenter
    private let openStore: () throws -> LedgerStore
    /// Erases the unreadable database and its key (the can't-open screen); a stand-in in tests.
    let eraseStore: () throws -> Void
    private var started = false
    var store: LedgerStore?      // queue only
    var view: LedgerView?        // queue only
    private var pendingFile: Data?       // queue only
    var pendingBackup: BackupData?   // queue only: a backup read and waiting for a currency or for the "Replace everything?" answer
    /// Queue only: true while the backup being read comes from Settings, over existing data, so it waits for the person's answer.
    var confirmReplace = false

    init(
        defaults: UserDefaults = UserDefaults.standard, openStore: @escaping () throws -> LedgerStore = { try LedgerStore.openDefault() },
        noticeCenter: NoticeCenter = SystemNoticeCenter(), eraseStore: @escaping () throws -> Void = { try LedgerStore.eraseDefault() }
    ) {
        self.eraseStore = eraseStore
        self.defaults = defaults
        self.noticeCenter = noticeCenter
        self.openStore = openStore
        self.hideAmounts = defaults.bool(forKey: AppModel.hideKey)
        self.chosenCurrency = AppModel.regionCurrency()
        let now = KassaDates.todayEpochDay()
        let current = KassaDates.yearMonth(epochDay: now)
        self.today = now
        self.shownYear = current.year
        self.shownMonth = current.month
    }

    var isFirstRun: Bool {
        if case .firstRun = phase { return true }
        return false
    }

    var isReady: Bool {
        if case .ready = phase { return true }
        return false
    }

    // MARK: Inputs

    /// What the figures depend on, read on the main thread and handed to the queue.
    struct Inputs {
        let hidden: Bool
        let year: Int
        let month: Int
        let today: Int
        let zone: String
        let insightsBack: Int
        let categoryId: Int64?
        /// The currency picked on first run (see `chosenCurrency`).
        let currency: String?
        /// The account or person page that is open, and how far back it is (see `AppModel.openPage`, `pageBack`).
        let target: PageTarget?
        let pageBack: Int
        /// The loan page lists every payment, not the next few.
        let loanAll: Bool
        /// The notices the person cleared (`DueSettings.cleared`): the Notifications page leaves them out.
        let cleared: [String]
        /// Batch J (`AppModel+Read.swift`): the phone's first weekday, the week toggle of Insights, Activity's filter and search, the page of the
        /// read stack that is open, and whether the budgets list is showing.
        var firstWeekday: Int = 1
        var insightsWeek: Bool = false
        var filter: String = "ALL"
        var query: String = ""
        var route: ReadRoute? = nil
        var budgetsOpen: Bool = false
    }

    func inputs() -> Inputs {
        return Inputs(
            hidden: hideAmounts, year: shownYear, month: shownMonth,
            today: KassaDates.todayEpochDay(), zone: TimeZone.current.identifier,
            insightsBack: insightsBack, categoryId: openCategoryId, currency: chosenCurrency,
            target: openPage, pageBack: pageBack, loanAll: loanAll, cleared: DueSettings(defaults: defaults).cleared,
            firstWeekday: Calendar.current.firstWeekday, insightsWeek: insightsWeek, filter: activityFilter, query: searchText,
            route: readStack.last, budgetsOpen: budgetsOpen
        )
    }

    func publish(_ block: @escaping () -> Void) {
        DispatchQueue.main.async(execute: block)
    }

    // MARK: Start

    /// Opens the database and decides between the first-run screen and the app. Safe to call again; it runs once.
    func start() {
        if started { return }
        started = true
        open(inputs())
    }

    /// Tries again after a failed start.
    func retry() {
        started = true
        phase = .opening
        open(inputs())
    }

    /// The can't-open screen's "Restore from a backup" and "Start over", after the person confirmed: the unreadable database and its key
    /// are erased, what the phone remembers by account id is forgotten, and the app opens again, empty, on the first-run screen (where
    /// a backup can be chosen). If the erase itself fails the can't-open screen comes back with a sentence.
    func eraseAndRestart() {
        phase = .opening
        defaults.set(false, forKey: AppModel.startedFreshKey)
        DueSettings(defaults: defaults).forgetAccountReferences()
        let given = inputs()
        queue.async {
            self.store = nil
            self.view = nil
            do {
                try self.eraseStore()
            } catch {
                let text = String(localized: "start.eraseFailed")
                self.publish { self.phase = .failed(text) }
                return
            }
            self.open(given)
        }
    }

    private func open(_ given: Inputs) {
        queue.async {
            do {
                let opened = try self.openStore()
                self.store = opened
                let empty = try self.isEmpty(opened)
                let fresh = self.defaults.bool(forKey: AppModel.startedFreshKey)
                if empty && !fresh {
                    self.publish { self.phase = .firstRun }
                    return
                }
                try self.rememberRegionCurrency(opened, given)
                try self.load(opened, given)
                self.publish { self.phase = .ready }
            } catch {
                let text = AppModel.openFailure(error)
                self.publish { self.phase = .failed(text) }
            }
        }
    }

    /// Data from before the currency was stored (a backup without one) keeps showing the phone's region currency when Kassa lists
    /// it; it is saved at once, so moving the phone to another region never changes the currency under amounts already entered.
    private func rememberRegionCurrency(_ opened: LedgerStore, _ given: Inputs) throws {
        if try opened.currencyCode() != nil { return }
        guard let code = given.currency else { return }
        try opened.setCurrency(code)
    }

    /// The currency of the phone's region when Kassa lists it, else nil (Locale.current.currency alone would name one for a
    /// phone with no region, which Android's `regionCurrency` does not).
    static func regionCurrency() -> String? {
        let region = Locale.current.region?.identifier ?? ""
        let code = Locale.current.currency?.identifier ?? ""
        let listed = StartBridge.shared.regionCurrency(regionCode: region, currencyCode: code)
        return listed.isEmpty ? nil : listed
    }

    private func isEmpty(_ opened: LedgerStore) throws -> Bool {
        let accounts = try opened.rowCount(table: "accounts")
        let entries = try opened.rowCount(table: "transactions")
        let people = try opened.rowCount(table: "people")
        return accounts == 0 && entries == 0 && people == 0
    }

    private static func openFailure(_ error: Error) -> String {
        if error is KeyUnreadable { return String(localized: "start.keyMissing") }
        return String(localized: "start.cantOpen")
    }

    /// "Start fresh": the currency picked on first run and the starter categories the person ticked are saved, and the app opens with
    /// no accounts. The first-run screen asks for the currency first when none is chosen.
    func startFresh(categories names: [String]) {
        defaults.set(true, forKey: AppModel.startedFreshKey)
        let given = inputs()
        queue.async {
            guard let opened = self.store else { return }
            do {
                try opened.startFresh(currency: given.currency, categories: names)
                try self.load(opened, given)
                self.publish { self.phase = .ready }
            } catch {
                let text = AppModel.openFailure(error)
                self.publish { self.phase = .failed(text) }
            }
        }
    }

    /// The person picked a currency on the first-run screen (a code from `StartBridge.currencies()`).
    func chooseCurrency(_ code: String) {
        chosenCurrency = code
    }

    // MARK: Figures

    /// Reads the whole store into a new `LedgerView` and publishes its figures. Queue only.
    func load(_ opened: LedgerStore, _ given: Inputs) throws {
        let data = try opened.export()
        let made = LedgerView(data: data, fallbackCurrency: AppModel.phoneCurrency())
        view = made
        publish { self.picks = made }
        compute(made, given)
        scheduleNotifications(made)
    }

    private static func phoneCurrency() -> String? {
        return Locale.current.currency?.identifier
    }

    /// Works the screens' figures out of [made] and publishes them. Queue only.
    private func compute(_ made: LedgerView, _ given: Inputs) {
        let day = Int32(given.today)
        let a = made.accounts(hidden: given.hidden, todayEpochDay: day, timeZoneId: given.zone)
        let o = made.overview(hidden: given.hidden, todayEpochDay: day, timeZoneId: given.zone, firstWeekday: Int32(given.firstWeekday))
        let i = made.insights(hidden: given.hidden, todayEpochDay: day, timeZoneId: given.zone, monthsBack: Int32(given.insightsBack))
        var page: CategoryPageData? = nil
        if let id = given.categoryId {
            page = made.categoryPage(
                hidden: given.hidden, todayEpochDay: day, timeZoneId: given.zone,
                monthsBack: Int32(given.insightsBack), categoryId: id
            )
        }
        let pages = computePages(made, given)
        let due: DueFigures = computeDue(made, given)
        let code = made.currencyCode()
        let m = made.activityFiltered(
            year: Int32(given.year), month: Int32(given.month), filter: given.filter, hidden: given.hidden, todayEpochDay: day,
            timeZoneId: given.zone
        )
        let read: ReadFigures = computeRead(made, given)
        publish {
            self.patterns = read.patterns
            self.week = read.week
            self.readPage = read.page
            self.budgets = read.budgets
            self.searchResult = read.search
            self.accounts = a
            self.overview = o
            self.insights = i
            self.categoryPage = page
            self.currency = code
            self.activity = m
            self.notices = due.notices
            self.upcoming = due.upcoming
            self.billsPage = due.bills
            self.today = given.today
            // A page that was closed while this was worked out stays closed.
            if self.openPage == given.target {
                self.accountPage = pages.account
                self.cardPage = pages.card
                self.personPage = pages.person
                self.loanPage = pages.loan
                self.investPage = pages.invest
            }
        }
    }

    /// Works the figures out again for the current switch, month and day (no new read of the store).
    func refreshFigures() {
        let given = inputs()
        queue.async {
            guard let made = self.view else { return }
            self.compute(made, given)
        }
    }

    func toggleHidden() {
        setHidden(!hideAmounts)
    }

    /// Turns Hide balances on or off, remembers it, and works every figure out again with the mask or without.
    func setHidden(_ hidden: Bool) {
        hideAmounts = hidden
        defaults.set(hidden, forKey: AppModel.hideKey)
        refreshFigures()
    }

    /// Moves Activity by [months]; never past the current month.
    func shiftMonth(_ months: Int) {
        let next = KassaDates.shifted(year: shownYear, month: shownMonth, by: months)
        let now = KassaDates.yearMonth(epochDay: KassaDates.todayEpochDay())
        if next.year * 12 + next.month > now.year * 12 + now.month { return }
        shownYear = next.year
        shownMonth = next.month
        refreshFigures()
    }

    /// Shows [year] and [month] in Activity (the caller checked the picker rules).
    func setMonth(year: Int, month: Int) {
        shownYear = year
        shownMonth = month
        refreshFigures()
    }

    /// Moves Insights (and its open category page) by [months]: -1 is the month before. Never past the current month.
    func shiftInsights(_ months: Int) {
        let next = insightsBack - months
        if next < 0 { return }
        insightsBack = next
        refreshFigures()
    }

    /// Opens the category page of Insights for the top-level category [id] (0 for spending with no category).
    func openInsightsCategory(_ id: Int64) {
        openCategoryId = id
        categoryPage = nil
        refreshFigures()
    }

    func closeInsightsCategory() {
        openCategoryId = nil
        categoryPage = nil
    }

    // MARK: Restore

    /// A file chosen in the file picker. Reads it, asks for a password when it has one, and replaces the (empty) store. From Settings
    /// ([replacing] true) the store has data, so once the file is read the person is asked first (`asksReplace`) and nothing changes
    /// until `confirmReplaceNow()`.
    func chooseBackup(_ url: URL, replacing: Bool = false) {
        working = true
        restoreMessage = nil
        notice = nil
        let given = inputs()
        queue.async {
            self.confirmReplace = replacing
            let scoped = url.startAccessingSecurityScopedResource()
            defer {
                if scoped { url.stopAccessingSecurityScopedResource() }
            }
            do {
                let bytes = try Data(contentsOf: url)
                self.restore(bytes, password: nil, given)
            } catch {
                self.fail(Failures.readFile(error))
            }
        }
    }

    func submitPassword(_ password: String) {
        asksPassword = false
        working = true
        restoreMessage = nil
        let given = inputs()
        queue.async {
            guard let bytes = self.pendingFile else {
                self.fail(String(localized: "restore.cannotRead"))
                return
            }
            self.restore(bytes, password: password, given)
        }
    }

    func cancelPassword() {
        asksPassword = false
        queue.async {
            self.pendingFile = nil
            self.confirmReplace = false
        }
    }

    func dismissMessage() {
        restoreMessage = nil
        notice = nil
    }

    /// The person said "Replace" to the question after a backup was read from Settings. A backup with no currency keeps the one the
    /// ledger shows now.
    func confirmReplaceNow() {
        asksReplace = nil
        working = true
        let given = inputs()
        queue.async {
            guard let parsed = self.pendingBackup else {
                self.fail(String(localized: "restore.failed"))
                return
            }
            let carried = StartBridge.shared.listed(code: parsed.currency ?? "")
            let kept: String? = self.view?.currencyCode()
            self.apply(parsed, currency: carried.isEmpty ? kept : nil, given)
        }
    }

    /// The person said no: the backup read from Settings is dropped and nothing changed.
    func cancelReplace() {
        asksReplace = nil
        queue.async {
            self.pendingBackup = nil
            self.confirmReplace = false
        }
    }

    /// Opens [bytes] and replaces everything with them. Queue only. Every failure ends as a plain sentence. A backup that carries no
    /// currency takes the one picked on first run, or waits for the person to pick one (`asksCurrencyForBackup`).
    private func restore(_ bytes: Data, password: String?, _ given: Inputs) {
        guard store != nil else {
            fail(String(localized: "restore.failed"))
            return
        }
        do {
            let json = try BackupFile.open(bytes, password: password)
            if let refusal = BackupBridge.shared.refusal(text: json) {
                fail(refusal)
                return
            }
            let parsed = try BackupBridge.shared.parse(text: json)
            pendingFile = nil
            if confirmReplace {
                pendingBackup = parsed
                let line = BackupBridge.shared.summary(data: parsed)
                publish {
                    self.working = false
                    self.restoreMessage = nil
                    self.asksReplace = line
                }
                return
            }
            let carried = StartBridge.shared.listed(code: parsed.currency ?? "")
            if carried.isEmpty && given.currency == nil {
                pendingBackup = parsed
                publish {
                    self.working = false
                    self.restoreMessage = nil
                    self.asksCurrencyForBackup = true
                }
                return
            }
            apply(parsed, currency: carried.isEmpty ? given.currency : nil, given)
        } catch let problem as BackupError {
            if problem == .needsPassword || problem == .wrongPassword {
                pendingFile = bytes
                let text: String? = problem == .wrongPassword ? problem.message : nil
                publish {
                    self.working = false
                    self.restoreMessage = text
                    self.asksPassword = true
                }
            } else {
                fail(problem.message)
            }
        } catch {
            fail(String(localized: "restore.failed"))
        }
    }

    /// Replaces the store with [parsed] (storing [currency] when the backup has none) and opens the app. Queue only.
    private func apply(_ parsed: BackupData, currency: String?, _ given: Inputs) {
        guard let opened = store else {
            fail(String(localized: "restore.failed"))
            return
        }
        do {
            try opened.replace(with: parsed, currency: currency)
            // What the phone remembers by account or bill id (cleared notices, what was rung) means nothing over the restored accounts.
            DueSettings(defaults: defaults).forgetAccountReferences()
            pendingBackup = nil
            confirmReplace = false
            try load(opened, given)
            let done = String(localized: "settings.restored")
            publish {
                self.working = false
                self.asksPassword = false
                self.asksCurrencyForBackup = false
                self.asksReplace = nil
                self.restoreMessage = nil
                self.notice = done
                self.phase = .ready
            }
        } catch {
            fail(String(localized: "restore.failed"))
        }
    }

    /// Queue only. An import from another app was saved and the figures are read: opens the app on the result (`AppModel+Import.swift`).
    func finishImport() {
        publish {
            self.working = false
            self.asksPassword = false
            self.asksCurrencyForBackup = false
            self.asksReplace = nil
            self.restoreMessage = nil
            self.notice = nil
            self.phase = .ready
        }
    }

    /// The currency was picked for a backup that had none: the restore goes on with it.
    func finishBackup(currency code: String) {
        chosenCurrency = code
        asksCurrencyForBackup = false
        working = true
        let given = inputs()
        queue.async {
            guard let parsed = self.pendingBackup else {
                self.fail(String(localized: "restore.failed"))
                return
            }
            self.apply(parsed, currency: code, given)
        }
    }

    /// The person left the currency list without picking: the backup is not restored.
    func cancelCurrency() {
        asksCurrencyForBackup = false
        queue.async {
            self.pendingBackup = nil
        }
    }

    func fail(_ text: String) {
        publish {
            self.working = false
            self.asksPassword = false
            self.asksReplace = nil
            self.restoreMessage = text
        }
    }
}
