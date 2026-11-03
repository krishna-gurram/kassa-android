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
