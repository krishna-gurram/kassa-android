import SwiftUI
import KassaShared

@main
struct KassaApp: App {

    init() {
        KassaCore.shared.installDefaults()
    }

    var body: some Scene {
        WindowGroup {
            ContentView()
        }
    }
}
