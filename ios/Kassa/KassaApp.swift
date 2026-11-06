import SwiftUI
import KassaShared

@main
struct KassaApp: App {

    @StateObject private var model = AppModel()
    /// App lock lives with the app, not a scene, so a scene closing and reopening keeps it locked.
    @StateObject private var lock = AppLockModel()

    /// A unit-test run hosts the app; the screens stay out of it so tests own the database and the shared rules' globals.
    private static let isTesting = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil

    init() {
        PhoneRules.install()
        if !KassaApp.isTesting { NotificationRouter.shared.install() }
    }

    var body: some Scene {
        WindowGroup {
            if KassaApp.isTesting {
                Color.clear
            } else {
                RootView(model: model, lock: lock)
            }
        }
        // Best effort only: iOS decides if and when this runs. The sure daily check is when the app becomes active (RootView).
        .backgroundTask(.appRefresh(AutoBackupSchedule.identifier)) {
            await AutoBackupSchedule.runInBackground()
        }
    }
}
