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

/// The tabs: Overview, Activity, Insights, Accounts, Settings. Add is a floating button above the
/// bar on every tab; it opens the entry page over the whole screen, so it is never shown over a page.
struct AppShell: View {
    @ObservedObject var model: AppModel

    // Kassa opens on Overview, as Android does by default (its "Home screen" setting will pick another tab later).
    @State private var tab: ShellTab = .overview
    @State private var addingAccount = false
    /// The scene's kept copy of an unfinished new entry, so folding or a scene recreation never loses what was typed.
    @SceneStorage("kassa.entryDraft") private var keptDraft = ""

    var body: some View {
        TabView(selection: $tab) {
            withAdd(overviewTab)
                .tabItem { Label("tab.overview", systemImage: "house") }
                .tag(ShellTab.overview)
            withAdd(activityTab)
                .tabItem { Label("tab.activity", systemImage: "list.bullet") }
                .tag(ShellTab.activity)
            withAdd(insightsTab)
                .tabItem { Label("tab.insights", systemImage: "chart.pie") }
                .tag(ShellTab.insights)
            withAdd(accountsTab)
                .tabItem { Label("tab.accounts", systemImage: "building.columns") }
                .tag(ShellTab.accounts)
            withAdd(SettingsScreen(model: model))
                .tabItem { Label("tab.settings", systemImage: "gearshape") }
                .tag(ShellTab.settings)
        }
        .accentColor(Palette.ink)
        .fullScreenCover(item: $model.entryPage) { request in
            if let ledger = model.picks {
                EntryPageHost(model: model, request: request, ledger: ledger, keptDraft: $keptDraft)
            }
        }
        .sheet(isPresented: $addingAccount) {
            AddAccountSheet(model: model, onClose: { addingAccount = false })
        }
    }

    private func withAdd<Content: View>(_ content: Content) -> some View {
        content.overlay(alignment: .bottomTrailing) {
            Button(action: { model.openAdd(draft: keptDraft) }) {
                Image(systemName: "plus")
                    .font(.system(size: 24, weight: .semibold))
                    .foregroundColor(Palette.ground)
                    .frame(width: 56, height: 56)
                    .background(Circle().fill(Palette.ink))
                    .shadow(color: Color.black.opacity(0.2), radius: 6, x: 0, y: 3)
            }
            .accessibilityLabel(Text("a11y.add"))
            .padding(.trailing, 16)
            .padding(.bottom, 16)
        }
    }

    @ViewBuilder
    private var overviewTab: some View {
        if let data = model.overview {
            OverviewScreen(
                data: data, hidden: model.hideAmounts, onToggleHidden: { model.toggleHidden() },
                onAllAccounts: { tab = .accounts }
            )
        } else {
            ProgressView()
        }
    }

    @ViewBuilder
    private var insightsTab: some View {
        if let openId = model.openCategoryId {
            if let page = model.categoryPage, page.id == openId {
                InsightsCategoryScreen(
                    data: page, hidden: model.hideAmounts, today: model.today,
                    onToggleHidden: { model.toggleHidden() },
                    onShift: { months in model.shiftInsights(months) },
                    onBack: { model.closeInsightsCategory() },
                    onOpen: { row in model.openEntry(id: row.id) }
                )
            } else {
                ProgressView()
            }
        } else if let data = model.insights {
            InsightsScreen(
                data: data, hidden: model.hideAmounts, onToggleHidden: { model.toggleHidden() },
                onShift: { months in model.shiftInsights(months) },
                onOpenCategory: { id in model.openInsightsCategory(id) }
            )
        } else {
            ProgressView()
        }
    }

    @ViewBuilder
    private var accountsTab: some View {
        if let data = model.accounts {
            AccountsScreen(
                data: data, hidden: model.hideAmounts, onToggleHidden: { model.toggleHidden() },
                onAdd: { addingAccount = true }
            )
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
                onShift: { months in model.shiftMonth(months) },
                onOpen: { row in model.openEntry(id: row.id) }
            )
        } else {
            ProgressView()
        }
    }
}
