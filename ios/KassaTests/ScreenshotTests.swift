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
            let settings = SettingsContent(
                hidden: false, currencyName: currencyName, working: false, message: nil, notice: nil, linked: false,
                onSetHidden: { _ in }, onBackUp: {}, onRestore: {},
                currencyDestination: AnyView(EmptyView()), noticesDestination: AnyView(EmptyView())
            )
            save(settings, scheme: scheme, to: target.appendingPathComponent("settings-\(suffix).png"))
        }
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
