import SwiftUI
import KassaShared

/// The app's first screen: opening, the first-run choice, the tabs, or the can't-open message.
struct RootView: View {
    @ObservedObject var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        content
            .onAppear { model.start() }
            .onChange(of: scenePhase) { newPhase in
                // Coming back to the app: the day may have changed.
                if newPhase == .active { model.refreshFigures() }
            }
    }

    @ViewBuilder
    private var content: some View {
        switch model.phase {
        case .opening:
            ProgressView()
                .frame(maxWidth: .infinity, maxHeight: .infinity)
                .background(Palette.ground.ignoresSafeArea())
        case .firstRun:
            FirstRunScreen(model: model)
        case .ready:
            AppShell(model: model)
        case .failed(let message):
            FailedScreen(message: message, onRetry: { model.retry() })
        }
    }
}

/// The database could not be opened. Android also offers restoring a backup or starting over here; that comes to iOS later.
struct FailedScreen: View {
    let message: String
    let onRetry: () -> Void

    var body: some View {
        VStack(spacing: 20) {
            Spacer()
            Text("start.cantOpenTitle")
                .font(.title2.weight(.bold))
                .multilineTextAlignment(.center)
            Text(message)
                .font(.body)
                .foregroundColor(Palette.ink2)
                .multilineTextAlignment(.center)
            Spacer()
            Button("start.tryAgain", action: onRetry)
                .buttonStyle(InkButtonStyle(filled: true))
        }
        .padding(24)
        .frame(maxWidth: 480)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(Palette.ground.ignoresSafeArea())
    }
}

enum ShellTab: Hashable {
    case overview
    case activity
    case insights
    case accounts
    case settings
}

/// The tabs: Overview, Activity, Insights, Accounts, Settings. Accounts and Activity are real; the rest are placeholders.
struct AppShell: View {
    @ObservedObject var model: AppModel

    // Kassa opens on Accounts until Overview exists (Android's "Home screen" setting will pick it later).
    @State private var tab: ShellTab = .accounts

    var body: some View {
        TabView(selection: $tab) {
            ComingSoonScreen(title: "tab.overview")
                .tabItem { Label("tab.overview", systemImage: "house") }
                .tag(ShellTab.overview)
            activityTab
                .tabItem { Label("tab.activity", systemImage: "list.bullet") }
                .tag(ShellTab.activity)
            ComingSoonScreen(title: "tab.insights")
                .tabItem { Label("tab.insights", systemImage: "chart.pie") }
                .tag(ShellTab.insights)
            accountsTab
                .tabItem { Label("tab.accounts", systemImage: "building.columns") }
                .tag(ShellTab.accounts)
            ComingSoonScreen(title: "tab.settings")
                .tabItem { Label("tab.settings", systemImage: "gearshape") }
                .tag(ShellTab.settings)
        }
        .accentColor(Palette.ink)
    }

    @ViewBuilder
    private var accountsTab: some View {
        if let data = model.accounts {
            AccountsScreen(data: data, hidden: model.hideAmounts, onToggleHidden: { model.toggleHidden() })
        } else {
            ProgressView()
        }
    }

    @ViewBuilder
    private var activityTab: some View {
        if let data = model.activity {
            ActivityScreen(
                data: data, hidden: model.hideAmounts, today: model.today,
                onToggleHidden: { model.toggleHidden() },
                onShift: { months in model.shiftMonth(months) }
            )
        } else {
            ProgressView()
        }
    }
}
