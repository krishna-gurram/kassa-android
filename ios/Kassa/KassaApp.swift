import SwiftUI
import KassaShared

@main
struct KassaApp: App {

    @StateObject private var model = AppModel()

    /// A unit-test run hosts the app; the screens stay out of it so tests own the database and the shared rules' globals.
    private static let isTesting = ProcessInfo.processInfo.environment["XCTestConfigurationFilePath"] != nil

    init() {
        PhoneRules.install()
    }

    var body: some Scene {
        WindowGroup {
            if KassaApp.isTesting {
                Color.clear
            } else {
                RootView(model: model)
            }
        }
    }
}
