import XCTest
import SwiftUI
import KassaShared
@testable import Kassa

/// Draws the Accounts and Activity screens of the golden backup to PNG files, for looking at in CI (the workflow uploads them).
/// Only when KASSA_SHOTS_DIR is set (xcodebuild passes it as TEST_RUNNER_KASSA_SHOTS_DIR); otherwise it does nothing.
/// ImageRenderer may not draw everything the same on every OS, so this test never fails the build: a screen that can't be
/// drawn is logged and skipped.
final class ScreenshotTests: XCTestCase {

    private var directory: URL!
    private var keys: MemoryKeyStore!

    override func setUpWithError() throws {
        directory = FileManager.default.temporaryDirectory.appendingPathComponent("kassa-shots-" + UUID().uuidString, isDirectory: true)
        keys = MemoryKeyStore()
        KassaCore.shared.installDefaults()
    }

    override func tearDownWithError() throws {
        try? FileManager.default.removeItem(at: directory)
    }

    @MainActor
    func testScreensToPNG() {
        guard let path = ProcessInfo.processInfo.environment["KASSA_SHOTS_DIR"], !path.isEmpty else { return }
        let target = URL(fileURLWithPath: path, isDirectory: true)
        do {
            try FileManager.default.createDirectory(at: target, withIntermediateDirectories: true, attributes: nil)
            let view = try GoldenFixture.view(directory: directory, keys: keys)
            let today = GoldenFixture.today
            let accounts = view.accounts(hidden: false, todayEpochDay: today, timeZoneId: "UTC")
            let hiddenAccounts = view.accounts(hidden: true, todayEpochDay: today, timeZoneId: "UTC")
            let activity = view.activity(year: 2025, month: 12, hidden: false, todayEpochDay: today, timeZoneId: "UTC")
            let todayDay = Int(today)
            draw(target, accounts: accounts, hiddenAccounts: hiddenAccounts, activity: activity, today: todayDay)
            drawAdd(target, view: view, today: todayDay)
            try drawOverviewAndSettings(target)
            try drawCardPages(target)
            try drawLoanPages(target)
            try drawInvestPages(target)
            try drawDuePages(target)
        } catch {
            print("KASSA shots skipped: \(error)")
        }
    }

    @MainActor
    private func draw(_ target: URL, accounts: AccountsData, hiddenAccounts: AccountsData, activity: ActivityData, today: Int) {
        for scheme in [ColorScheme.light, ColorScheme.dark] {
            let suffix = scheme == ColorScheme.dark ? "dark" : "light"
            let accountsView = AccountsContent(data: accounts, hidden: false, onToggleHidden: {})
            save(accountsView, scheme: scheme, to: target.appendingPathComponent("accounts-\(suffix).png"))
            let hiddenView = AccountsContent(data: hiddenAccounts, hidden: true, onToggleHidden: {})
            save(hiddenView, scheme: scheme, to: target.appendingPathComponent("accounts-hidden-\(suffix).png"))
            let activityView = ActivityContent(
                data: activity, hidden: false, today: today, lazy: false, onToggleHidden: {}, onShift: { _ in }
            )
            save(activityView, scheme: scheme, to: target.appendingPathComponent("activity-\(suffix).png"))
        }
    }

    /// Overview (shown and hidden) and Settings of the demo backup, light and dark. Overview is drawn on the demo's own "today".
    @MainActor
    private func drawOverviewAndSettings(_ target: URL) throws {
        let expected = try DemoFixture.expected()
        let demoDirectory = directory.appendingPathComponent("demo", isDirectory: true)
        let view = try DemoFixture.view(directory: demoDirectory, keys: MemoryKeyStore())
        let shown = view.overview(hidden: false, todayEpochDay: expected.today, timeZoneId: "UTC")
        let masked = view.overview(hidden: true, todayEpochDay: expected.today, timeZoneId: "UTC")
        let currencyName = "US dollar"
        for scheme in [ColorScheme.light, ColorScheme.dark] {
            let suffix = scheme == ColorScheme.dark ? "dark" : "light"
            let overview = OverviewContent(data: shown, hidden: false, onToggleHidden: {}, onAllAccounts: {})
            save(overview, scheme: scheme, to: target.appendingPathComponent("overview-\(suffix).png"))
            let hiddenOverview = OverviewContent(data: masked, hidden: true, onToggleHidden: {}, onAllAccounts: {})
            save(hiddenOverview, scheme: scheme, to: target.appendingPathComponent("overview-hidden-\(suffix).png"))
            drawInsights(target, view: view, today: expected.today, scheme: scheme, suffix: suffix)
            let settings = SettingsContent(
                hidden: false, currencyName: currencyName, working: false, message: nil, notice: nil, linked: false,
                onSetHidden: { _ in }, onBackUp: {}, onRestore: {},
                currencyDestination: AnyView(EmptyView()), noticesDestination: AnyView(EmptyView())
            )
            save(settings, scheme: scheme, to: target.appendingPathComponent("settings-\(suffix).png"))
        }
    }

    /// The demo's Travel card page (shown and hidden) and its pay-card sheet, light and dark, on the demo's own "today".
    @MainActor
    private func drawCardPages(_ target: URL) throws {
        let expected = try DemoFixture.expected()
        let demoDirectory = directory.appendingPathComponent("demo-cards", isDirectory: true)
        let view = try DemoFixture.view(directory: demoDirectory, keys: MemoryKeyStore())
        let cardId: Int64 = 4
        let today = Int(expected.today)
        let shown = view.cardPage(cardId: cardId, hidden: false, todayEpochDay: expected.today, timeZoneId: "UTC", back: 0)
        let masked = view.cardPage(cardId: cardId, hidden: true, todayEpochDay: expected.today, timeZoneId: "UTC", back: 0)
        let payData = view.cardPayData(cardId: cardId, hidden: false, todayEpochDay: expected.today, timeZoneId: "UTC")
        let sheetDirectory = directory.appendingPathComponent("demo-sheet", isDirectory: true)
        let keys = MemoryKeyStore()
        let model = AppModel(defaults: UserDefaults(suiteName: "kassa-shots-" + UUID().uuidString) ?? UserDefaults.standard, openStore: {
            try LedgerStore(directory: sheetDirectory, keyStore: keys)
        })
        for scheme in [ColorScheme.light, ColorScheme.dark] {
            let suffix = scheme == ColorScheme.dark ? "dark" : "light"
            let page = CardPageContent(
                data: shown, hidden: false, today: today, onToggleHidden: {}, onBack: {}, onPay: {}, onShift: { _ in },
                onSetClosed: { _, _ in }
            )
            save(page, scheme: scheme, to: target.appendingPathComponent("card-\(suffix).png"))
            let hiddenPage = CardPageContent(
                data: masked, hidden: true, today: today, onToggleHidden: {}, onBack: {}, onPay: {}, onShift: { _ in },
                onSetClosed: { _, _ in }
            )
            save(hiddenPage, scheme: scheme, to: target.appendingPathComponent("card-hidden-\(suffix).png"))
            let sheet = PayCardSheet(model: model, request: PaySheetRequest(data: payData))
            save(sheet, scheme: scheme, to: target.appendingPathComponent("pay-card-\(suffix).png"))
        }
    }

    /// The demo's Car loan page (shown and hidden), the Add loan form filled in, and the correction sheet, light and dark.
    @MainActor
    private func drawLoanPages(_ target: URL) throws {
        let expected = try DemoFixture.expected()
        let demoDirectory = directory.appendingPathComponent("demo-loans", isDirectory: true)
        let view = try DemoFixture.view(directory: demoDirectory, keys: MemoryKeyStore())
        let loanId: Int64 = 5
        let today = Int(expected.today)
        let shown = view.loanPage(
            accountId: loanId, hidden: false, todayEpochDay: expected.today, timeZoneId: "UTC", monthsBack: 0, allRows: false
        )
        let masked = view.loanPage(
            accountId: loanId, hidden: true, todayEpochDay: expected.today, timeZoneId: "UTC", monthsBack: 0, allRows: false
        )
        let correction = view.correctionSheet(accountId: loanId, editingId: 0, hidden: false)
        let paySheet: LoanPaymentSheetData = view.loanPaymentSheet(accountId: loanId, paymentId: 0, todayEpochDay: expected.today, hidden: false)
        var payFields = LoanPaymentFields(paySheet.input)
        payFields.fromId = 1
        let payPreview: LoanPaymentPreviewData = view.loanPaymentPreview(
            accountId: loanId, paymentId: 0, input: payFields.input(), hidden: false
        )
        let changeSheet: LoanChangeSheetData = view.loanChangeSheet(accountId: loanId, todayEpochDay: expected.today)
        var changeFields = LoanChangeFields(changeSheet.input)
        changeFields.rate = "7"
        let changePreview: LoanChangePreviewData = view.loanChangePreview(accountId: loanId, input: changeFields.input(), hidden: false)
        let detailsSheet: LoanDetailsSheetData = view.loanDetailsSheet(accountId: loanId)
        let detailsFields = LoanDetailsFields(detailsSheet.input)
        let base: LoanInput = LoanBridge.shared.starting(todayEpochDay: expected.today, payFromId: 0)
        var draft = LoanDraft(base)
        draft.amount = "30000"
        draft.rate = "6"
        draft.termMonths = "60"
        let preview: LoanPreviewData = LoanBridge.shared.preview(loan: draft.input(), currency: "USD", hidden: false)
        let sheetDirectory = directory.appendingPathComponent("demo-loan-sheet", isDirectory: true)
        let sheetKeys = MemoryKeyStore()
        let model = AppModel(defaults: UserDefaults(suiteName: "kassa-shots-" + UUID().uuidString) ?? UserDefaults.standard, openStore: {
            try LedgerStore(directory: sheetDirectory, keyStore: sheetKeys)
        })
        for scheme in [ColorScheme.light, ColorScheme.dark] {
            let suffix = scheme == ColorScheme.dark ? "dark" : "light"
            let page = LoanPageContent(
                data: shown, hidden: false, today: today, showAll: false, onToggleHidden: {}, onBack: {}, onShift: { _ in },
                onSetClosed: { _, _ in }, onCorrect: {}, onShowAll: { _ in }, onRemoveChange: { _, _ in }
            )
            save(page, scheme: scheme, to: target.appendingPathComponent("loan-\(suffix).png"))
            let hiddenPage = LoanPageContent(
                data: masked, hidden: true, today: today, showAll: false, onToggleHidden: {}, onBack: {}, onShift: { _ in },
                onSetClosed: { _, _ in }, onCorrect: {}, onShowAll: { _ in }, onRemoveChange: { _, _ in }
            )
            save(hiddenPage, scheme: scheme, to: target.appendingPathComponent("loan-hidden-\(suffix).png"))
            let form = VStack(spacing: 12) {
                SheetBar(title: Wording.accountTypeTitle("LOAN"), onBack: {})
                LoanFormBody(
                    draft: Binding<LoanDraft>.constant(draft), problems: [:], submitted: false, preview: preview, today: today,
                    payFromName: "", payFromType: "", onPickPayFrom: {}, onPickDay: { _ in }
                )
                .padding(.horizontal, 16)
            }
            save(form, scheme: scheme, to: target.appendingPathComponent("loan-add-\(suffix).png"))
            let sheet = CorrectionSheet(model: model, request: CorrectionRequest(data: correction))
            save(sheet, scheme: scheme, to: target.appendingPathComponent("loan-correct-\(suffix).png"))
            let payment = LoanPaymentSheetContent(
                data: paySheet, fields: payFields, preview: payPreview, showProblems: false, error: nil, busy: false,
                payFrom: view.accountChoice(id: 1), interestChoice: nil, principalChoice: nil, costChoice: nil, today: today,
                onAmount: { _ in }, onPrincipal: { _ in }, onInterest: { _ in }, onCounts: { _ in }, onDay: { _ in },
                onOtherDay: {}, onPickAccount: {}, onPickInterest: {}, onPickPrincipal: {}, onPickCost: {}, onSave: {},
                onDelete: {}, onClose: {}
            )
            save(payment, scheme: scheme, to: target.appendingPathComponent("loan-payment-\(suffix).png"))
            let change = LoanChangeContent(
                data: changeSheet, fields: Binding<LoanChangeFields>.constant(changeFields), preview: changePreview,
                showProblems: false, error: nil, busy: false, onPickDay: {}, onSave: {}, onClose: {}
            )
            save(change, scheme: scheme, to: target.appendingPathComponent("loan-change-\(suffix).png"))
            let details = LoanDetailsContent(
                data: detailsSheet, fields: Binding<LoanDetailsFields>.constant(detailsFields), problems: [:], showProblems: false,
                error: nil, busy: false, payFrom: view.accountChoice(id: detailsFields.payFromId > 0 ? detailsFields.payFromId : -1),
                interestChoice: nil, principalChoice: nil, principalDefault: "", onPickAccount: {}, onPickDay: {},
                onPickInterest: {}, onPickPrincipal: {}, onSave: {}, onClose: {}
            )
            save(details, scheme: scheme, to: target.appendingPathComponent("loan-details-\(suffix).png"))
        }
    }

    /// The demo's Brokerage page (shown and hidden), the holding sheet and the Buy or sell sheet, light and dark.
    /// The demo's Notifications page, Overview with its bar and Upcoming rows, and Bills, light and dark, on the demo's own "today".
    @MainActor
    private func drawDuePages(_ target: URL) throws {
        let expected = try DemoFixture.expected()
        let demoDirectory = directory.appendingPathComponent("demo-due", isDirectory: true)
        let view = try DemoFixture.view(directory: demoDirectory, keys: MemoryKeyStore())
        let today = Int(expected.today)
        let notices: NoticesData = view.notices(hidden: false, todayEpochDay: expected.today, timeZoneId: "UTC", cleared: [])
        let upcoming: [UpcomingData] = view.upcoming(hidden: false, todayEpochDay: expected.today, timeZoneId: "UTC", days: 30)
        let bills: BillsData = view.billsPage(hidden: false, todayEpochDay: expected.today, timeZoneId: "UTC")
        let overview = view.overview(hidden: false, todayEpochDay: expected.today, timeZoneId: "UTC")
        for scheme in [ColorScheme.light, ColorScheme.dark] {
            let suffix = scheme == ColorScheme.dark ? "dark" : "light"
            let page = NoticesContent(
                items: notices.items, today: today, hidden: false, message: nil, actions: NoticeActions(), onBack: {}, onClearAll: {}
            )
            save(page, scheme: scheme, to: target.appendingPathComponent("notices-\(suffix).png"))
            let home = OverviewContent(
                data: overview, hidden: false, onToggleHidden: {}, onAllAccounts: {}, notices: notices.items, upcoming: upcoming
            )
            save(home, scheme: scheme, to: target.appendingPathComponent("overview-due-\(suffix).png"))
            let list = BillsContent(rows: bills.rows, onOpen: { _ in }, onAdd: {})
            save(list, scheme: scheme, to: target.appendingPathComponent("bills-\(suffix).png"))
        }
    }

    @MainActor
    private func drawInvestPages(_ target: URL) throws {
        let expected = try DemoFixture.expected()
        let demoDirectory = directory.appendingPathComponent("demo-invest", isDirectory: true)
        let view = try DemoFixture.view(directory: demoDirectory, keys: MemoryKeyStore())
        let brokerId: Int64 = 6
        let today = Int(expected.today)
        let shown: InvestPageData = view.investPage(accountId: brokerId, hidden: false, todayEpochDay: expected.today, timeZoneId: "UTC")
        let masked: InvestPageData = view.investPage(accountId: brokerId, hidden: true, todayEpochDay: expected.today, timeZoneId: "UTC")
        var holdingId: Int64 = 0
        for row in shown.holdings { holdingId = row.id }
        let holdingData: HoldingSheetData = view.holdingSheet(accountId: brokerId, holdingId: holdingId, hidden: false)
        let tradeData: TradeSheetData = view.tradeSheet(accountId: brokerId, hidden: false)
        let sheetDirectory = directory.appendingPathComponent("demo-invest-sheet", isDirectory: true)
        let sheetKeys = MemoryKeyStore()
        let model = AppModel(defaults: UserDefaults(suiteName: "kassa-shots-" + UUID().uuidString) ?? UserDefaults.standard, openStore: {
            try LedgerStore(directory: sheetDirectory, keyStore: sheetKeys)
        })
        for scheme in [ColorScheme.light, ColorScheme.dark] {
            let suffix = scheme == ColorScheme.dark ? "dark" : "light"
            let page = InvestPageContent(
                data: shown, hidden: false, today: today, onToggleHidden: {}, onBack: {}, onSetClosed: { _, _ in }
            )
            save(page, scheme: scheme, to: target.appendingPathComponent("invest-\(suffix).png"))
            let hiddenPage = InvestPageContent(
                data: masked, hidden: true, today: today, onToggleHidden: {}, onBack: {}, onSetClosed: { _, _ in }
            )
            save(hiddenPage, scheme: scheme, to: target.appendingPathComponent("invest-hidden-\(suffix).png"))
            let holdingSheet = HoldingSheet(model: model, request: HoldingSheetRequest(data: holdingData))
            save(holdingSheet, scheme: scheme, to: target.appendingPathComponent("invest-holding-\(suffix).png"))
            let tradeSheet = TradeSheet(model: model, request: TradeSheetRequest(data: tradeData))
            save(tradeSheet, scheme: scheme, to: target.appendingPathComponent("invest-trade-\(suffix).png"))
        }
    }

    /// Insights (shown, hidden) and a category page of the demo backup on its own "today", for one colour scheme.
    @MainActor
    private func drawInsights(_ target: URL, view: LedgerView, today: Int32, scheme: ColorScheme, suffix: String) {
        let zone = "UTC"
        let shown = view.insights(hidden: false, todayEpochDay: today, timeZoneId: zone, monthsBack: 0)
        let masked = view.insights(hidden: true, todayEpochDay: today, timeZoneId: zone, monthsBack: 0)
        let insights = InsightsContent(data: shown, hidden: false, onToggleHidden: {}, onShift: { _ in }, onOpenCategory: { _ in })
        save(insights, scheme: scheme, to: target.appendingPathComponent("insights-\(suffix).png"))
        let hiddenInsights = InsightsContent(data: masked, hidden: true, onToggleHidden: {}, onShift: { _ in }, onOpenCategory: { _ in })
        save(hiddenInsights, scheme: scheme, to: target.appendingPathComponent("insights-hidden-\(suffix).png"))
        // October, not the demo's current month, so the category page has entries to list.
        let october = view.insights(hidden: false, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: zone, monthsBack: 0)
        guard let first = october.lines.first else { return }
        let page = view.categoryPage(
            hidden: false, todayEpochDay: DemoFixture.lastOfOctober, timeZoneId: zone, monthsBack: 0, categoryId: first.id
        )
        let pageView = InsightsCategoryContent(
            data: page, hidden: false, today: Int(DemoFixture.lastOfOctober), lazy: false,
            onToggleHidden: {}, onShift: { _ in }, onBack: {}
        )
        save(pageView, scheme: scheme, to: target.appendingPathComponent("insights-category-\(suffix).png"))
    }

    /// The Add page (empty, and filled in with the first category and account) and the Add account sheet, light and dark.
    @MainActor
    private func drawAdd(_ target: URL, view: LedgerView, today: Int) {
        let zone = "UTC"
        let categories: [CategoryChoiceData] = view.categoryChoices(kind: "EXPENSE", query: "")
        let picked: [AccountChoiceData] = view.accountPick(
            entryKind: "EXPENSE", field: "ACCOUNT", accountId: 0, filter: "ALL", query: "", recentLimit: 0
        ).groups.flatMap { $0.accounts }
        let defaults = UserDefaults(suiteName: "kassa-shots-" + UUID().uuidString) ?? UserDefaults.standard
        let model = AppModel(defaults: defaults, openStore: { throw KeyUnreadable() })
        for scheme in [ColorScheme.light, ColorScheme.dark] {
            let suffix = scheme == ColorScheme.dark ? "dark" : "light"
            let empty = EntryDraftModel(view: view, editing: nil, draft: "", today: today, zone: zone)
            let emptyPage = AddEntryScreen(draft: empty, amountText: "", onSave: { _, _ in }, onDelete: { _, _ in }, onClose: {})
            save(emptyPage, scheme: scheme, to: target.appendingPathComponent("add-empty-\(suffix).png"))

            let filled = EntryDraftModel(view: view, editing: nil, draft: "", today: today, zone: zone)
            for key in ["1", "2", ".", "5", "0"] { filled.press(key) }
            if let category = categories.first { filled.pick(category: category) }
            if let account = picked.first { filled.pick(account: account.id) }
            let filledPage = AddEntryScreen(draft: filled, amountText: "", onSave: { _, _ in }, onDelete: { _, _ in }, onClose: {})
            save(filledPage, scheme: scheme, to: target.appendingPathComponent("add-filled-\(suffix).png"))

            let picking = EntryDraftModel(view: view, editing: nil, draft: "", today: today, zone: zone)
            picking.show(.category)
            let pickingPage = AddEntryScreen(draft: picking, amountText: "", onSave: { _, _ in }, onDelete: { _, _ in }, onClose: {})
            save(pickingPage, scheme: scheme, to: target.appendingPathComponent("add-category-\(suffix).png"))

            let sheet = AddAccountSheet(model: model, onClose: {})
            save(sheet, scheme: scheme, to: target.appendingPathComponent("add-account-\(suffix).png"))
        }
    }

    /// One screen at 390 x 844 pt, scale 2. Anything that goes wrong is printed, never thrown.
    @MainActor
    private func save<V: View>(_ content: V, scheme: ColorScheme, to file: URL) {
        let framed = content
            .frame(width: 390, height: 844, alignment: .top)
            .background(Palette.ground)
            .environment(\.colorScheme, scheme)
        let renderer = ImageRenderer(content: framed)
        renderer.scale = 2
        renderer.proposedSize = ProposedViewSize(width: 390, height: 844)
        guard let image = renderer.uiImage, let png = image.pngData() else {
            print("KASSA shot not drawn: \(file.lastPathComponent)")
            return
        }
        do {
            try png.write(to: file)
        } catch {
            print("KASSA shot not written: \(file.lastPathComponent)")
        }
    }
}
