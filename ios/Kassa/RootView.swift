import SwiftUI
import KassaShared

/// The app's first screen: opening, the first-run choice, the tabs, or the can't-open message.
struct RootView: View {
    @ObservedObject var model: AppModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        content
            .modifier(ImportCover(model: model, importer: model.importer))
            .onAppear { model.start() }
            .onOpenURL { url in model.receiveFile(url) }
            .onChange(of: model.canTakeFiles) { ready in
                if ready { model.drainPendingFile() }
            }
            .onChange(of: scenePhase) { newPhase in
                // Coming back to the app: the day may have changed.
                if newPhase == .active {
                    model.refreshFigures()
                    model.refreshNotifications()
                }
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

/// The import from another app takes the whole screen while it runs (first run and Settings alike), and hands back to what was under it.
struct ImportCover: ViewModifier {
    let model: AppModel
    @ObservedObject var importer: ImportModel

    func body(content: Content) -> some View {
        let shown = Binding<Bool>(get: { importer.isActive }, set: { _ in })
        return content.fullScreenCover(isPresented: shown) {
            ImportScreen(model: model, importer: importer)
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
        .onChange(of: model.settingsRequest) { _ in tab = .settings }
        .overlay {
            if !model.readStack.isEmpty {
                ReadHost(model: model)
            }
        }
        .fullScreenCover(item: $model.entryPage) { request in
            if let ledger = model.picks {
                EntryPageHost(model: model, request: request, ledger: ledger, keptDraft: $keptDraft)
            }
        }
        .sheet(isPresented: $addingAccount) {
            AddAccountSheet(model: model, onClose: { addingAccount = false })
        }
        .sheet(item: $model.paySheet) { request in
            PayCardSheet(model: model, request: request)
        }
        .sheet(item: $model.personSheet) { request in
            PersonPaymentSheet(model: model, request: request)
        }
        .sheet(item: $model.correctionSheet) { request in
            CorrectionSheet(model: model, request: request)
        }
        .sheet(item: $model.termsSheet) { request in
            LoanTermsSheet(model: model, request: request)
        }
        .sheet(item: $model.loanPaymentSheet) { request in
            LoanPaymentSheet(model: model, request: request)
        }
        .sheet(item: $model.loanChangeSheet) { request in
            LoanChangeScreen(model: model, request: request)
        }
        .sheet(item: $model.loanDetailsSheet) { request in
            LoanDetailsSheet(model: model, request: request)
        }
        .sheet(item: $model.holdingSheet) { request in
            HoldingSheet(model: model, request: request)
        }
        .sheet(item: $model.pricesSheet) { request in
            PricesSheet(model: model, request: request)
        }
        .sheet(item: $model.tradeSheet) { request in
            TradeSheet(model: model, request: request)
        }
        .sheet(item: $model.tradeEditSheet) { request in
            TradeEditSheet(model: model, request: request)
        }
        .sheet(item: $model.allocateSheet) { request in
            AllocateSheet(model: model, request: request)
        }
        .sheet(item: $model.billSheet) { request in
            BillSheet(model: model, request: request)
        }
        .sheet(item: $model.billAmountSheet) { request in
            BillAmountSheet(model: model, request: request)
        }
        .alert(Text("notif.askTitle"), isPresented: $model.asksNotifications) {
            Button("notif.askAllow") { model.answerNotificationsAsk(allow: true) }
            Button("notif.askNotNow", role: .cancel) { model.answerNotificationsAsk(allow: false) }
        } message: {
            Text("notif.askBody")
        }
        .onChange(of: model.requestedTab) { requested in
            if let wanted = requested {
                tab = wanted
                model.requestedTab = nil
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: NotificationRouter.opened)) { _ in
            openTappedNotice()
        }
        .onAppear { openTappedNotice() }
    }

    /// A notification that was tapped (also one that launched the app) goes to its page once.
    private func openTappedNotice() {
        if let route = NotificationRouter.shared.takePending() {
            model.openDue(route)
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
        if model.noticesOpen {
            NoticesScreen(model: model)
        } else if let data = model.overview {
            OverviewScreen(
                data: data, hidden: model.hideAmounts, onToggleHidden: { model.toggleHidden() },
                onAllAccounts: { tab = .accounts },
                notices: model.notices?.items ?? [], upcoming: model.upcoming,
                onOpenNotices: { model.openNotices() }, onOpenCard: { id in model.openAccountPage(id); tab = .accounts },
                onOpenCategory: { id in model.openCategory(id) },
                onOpenSpending: { income, month in model.openRead(ReadRoute.spending(month: month, income: income, back: 0)) }
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
                onOpenCategory: { id in model.openInsightsCategory(id) },
                read: InsightsRead(
                    patterns: model.patterns, week: model.week, showWeek: model.insightsWeek, today: model.today,
                    onSetWeek: { week in model.setInsightsWeek(week) },
                    onOpenDetail: { kind in model.openRead(ReadRoute.detail(kind)) },
                    onOpenSpending: { month in model.openRead(ReadRoute.spending(month: month, income: false, back: 0)) },
                    onOpenEntry: { row in model.openEntry(id: row.id) }
                )
            )
        } else {
            ProgressView()
        }
    }

    @ViewBuilder
    private var accountsTab: some View {
        if let target = model.openPage {
            PageHost(model: model, target: target)
        } else if let data = model.accounts {
            AccountsScreen(
                data: data, hidden: model.hideAmounts, onToggleHidden: { model.toggleHidden() },
                onAdd: { addingAccount = true },
                onOpenRow: { row, isPerson in
                    if isPerson { model.openPersonPage(row.id) } else { model.openAccountPage(row.id) }
                }
            )
        } else {
            ProgressView()
        }
    }

    @ViewBuilder
    private var activityTab: some View {
        if let data = model.activity {
            ActivityHost(model: model, data: data)
        } else {
            ProgressView()
        }
    }
}
