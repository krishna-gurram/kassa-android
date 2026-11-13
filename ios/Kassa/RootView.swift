import SwiftUI
import KassaShared

/// The app's first screen: opening, the first-run choice, the tabs, or the can't-open message.
struct RootView: View {
    @ObservedObject var model: AppModel
    @ObservedObject var lock: AppLockModel
    @Environment(\.scenePhase) private var scenePhase

    var body: some View {
        content
            .modifier(ImportCover(model: model, importer: model.importer))
            .onAppear { model.start() }
            .onOpenURL { url in model.receiveFile(url) }
            .onChange(of: model.isReady) { ready in
                if ready {
                    model.refreshAutoBackupInfo()
                    model.autoBackupIfDue()
                }
            }
            .onChange(of: model.canTakeFiles) { ready in
                if ready { model.drainPendingFile() }
            }
            .onChange(of: scenePhase) { newPhase in
                if newPhase == .background {
                    lock.didEnterBackground()
                    model.scheduleAutoBackupRefresh()
                }
                // Coming back to the app: the day may have changed, and a minute away locks.
                if newPhase == .active {
                    lock.didBecomeActive()
                    model.refreshFigures()
                    model.refreshNotifications()
                    model.refreshAutoBackupInfo()
                    model.autoBackupIfDue()
                }
                updateCover(newPhase)
            }
            .onChange(of: lock.locked) { _ in updateCover(scenePhase) }
            .onChange(of: lock.enabled) { _ in updateCover(scenePhase) }
            .onChange(of: model.hideAmounts) { _ in updateCover(scenePhase) }
            .onAppear {
                updateCover(scenePhase)
                // A cold start with App lock on asks at once (the scene may already be active, so no change fires).
                if scenePhase == .active { lock.unlockIfNeeded() }
            }
            .alert(Text("lock.turnedOffTitle"), isPresented: noticeBinding) {
                Button("lock.ok") { lock.noticeShown() }
            } message: {
                Text("lock.turnedOffBody")
            }
    }

    private var noticeBinding: Binding<Bool> {
        return Binding<Bool>(get: { lock.turnedOffNotice }, set: { shown in if !shown { lock.noticeShown() } })
    }

    /// Shows or hides the window over everything: the neutral screen while the app is not active (App lock or Hide balances on), the
    /// lock screen while locked. Sheets are under it too, which a view in this tree could not manage.
    private func updateCover(_ phase: ScenePhase) {
        let neutral: Bool = LockBridge.shared.privacyCover(lockOn: lock.enabled, hideAmounts: model.hideAmounts, appActive: phase == .active)
        if neutral || lock.lockShown {
            CoverWindow.shared.show(CoverRoot(lock: lock, neutral: neutral))
        } else {
            CoverWindow.shared.hide()
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
            AppShell(model: model, lock: lock)
        case .failed(let message):
            FailedScreen(message: message, onRetry: { model.retry() }, onErase: { model.eraseAndRestart() })
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

/// The database could not be opened. Try again, or (after asking) erase what can't be read and go on to the first-run screen, which
/// restores a backup or starts empty, as on Android.
struct FailedScreen: View {
    let message: String
    let onRetry: () -> Void
    let onErase: () -> Void

    @State private var confirmRestore = false
    @State private var confirmStartOver = false

    var body: some View {
        ScrollView {
            VStack(spacing: 14) {
                Text("start.cantOpenTitle")
                    .font(.title2.weight(.bold))
                    .multilineTextAlignment(.center)
                Text(message)
                    .font(.body)
                    .foregroundColor(Palette.ink2)
                    .multilineTextAlignment(.center)
                Button("start.tryAgain", action: onRetry)
                    .buttonStyle(InkButtonStyle(filled: true))
                    .padding(.top, 8)
                Button("start.restoreFromBackup") { confirmRestore = true }
                    .buttonStyle(InkButtonStyle(filled: false))
                Button(action: { confirmStartOver = true }) {
                    Text("start.startOver")
                        .font(.body.weight(.semibold))
                        .foregroundColor(Palette.orange)
                        .frame(maxWidth: .infinity, minHeight: 48)
                }
                Text("start.nothingErased")
                    .font(.footnote)
                    .foregroundColor(Palette.ink2)
                    .multilineTextAlignment(.center)
            }
            .padding(24)
            .frame(maxWidth: 480)
            .frame(maxWidth: .infinity)
        }
        .background(Palette.ground.ignoresSafeArea())
        .alert(Text("start.eraseRestoreTitle"), isPresented: $confirmRestore) {
            Button("start.eraseContinue", role: .destructive, action: onErase)
            Button("start.cancel", role: .cancel) {}
        } message: {
            Text("start.eraseRestoreBody")
        }
        .alert(Text("start.startOverTitle"), isPresented: $confirmStartOver) {
            Button("start.eraseStartOver", role: .destructive, action: onErase)
            Button("start.cancel", role: .cancel) {}
        } message: {
            Text("start.startOverBody")
        }
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
    @ObservedObject var lock: AppLockModel
    @Environment(\.horizontalSizeClass) private var sizeClass

    // Kassa opens on the tab Settings > Open Kassa on names (Overview until the person picks another).
    @State private var tab: ShellTab
    @State private var addingAccount = false
    /// The scene's kept copy of an unfinished new entry, so folding or a scene recreation never loses what was typed.
    @SceneStorage("kassa.entryDraft") private var keptDraft = ""
    /// The same for an unfinished "Can I afford it?" form (`SimDraft.encoded`).
    @SceneStorage("kassa.simDraft") private var keptSim = ""

    init(model: AppModel, lock: AppLockModel) {
        self.model = model
        self.lock = lock
        _tab = State(initialValue: PhonePrefs.shellTab(model.homeTab))
    }

    /// The five tabs. The native bar is hidden (`KassaTabBar` below replaces it), so each tab keeps its own state as before. At regular
    /// width (`layout.rail`) the rail replaces the bar and holds Add, and from 720 pt a page opens beside its tab's list (`paned`).
    private func tabs(_ layout: ShellLayout) -> some View {
        let split: Bool = layout.content == .listDetail
        return TabView(selection: $tab) {
            withAdd(paned(.overview, overviewTab, split), layout)
                .tabItem { Label("tab.overview", systemImage: "house") }
                .tag(ShellTab.overview)
            withAdd(paned(.activity, activityTab, split), layout)
                .tabItem { Label("tab.activity", systemImage: "list.bullet") }
                .tag(ShellTab.activity)
            withAdd(paned(.insights, insightsTab(split), split), layout)
                .tabItem { Label("tab.insights", systemImage: "chart.pie") }
                .tag(ShellTab.insights)
            withAdd(paned(.accounts, accountsTab(split), split), layout)
                .tabItem { Label("tab.accounts", systemImage: "building.columns") }
                .tag(ShellTab.accounts)
            withAdd(SettingsScreen(model: model, lock: lock), layout)
                .tabItem { Label("tab.settings", systemImage: "gearshape") }
                .tag(ShellTab.settings)
        }
    }

    /// List-detail (720 pt and up): the tab's list with the page that is open beside it, when one is. The list stays in the same place in
    /// the tree, so opening or closing a page never resets it. Below 720 [split] is false and the tab is as before.
    @ViewBuilder
    private func paned<Base: View>(_ which: ShellTab, _ base: Base, _ split: Bool) -> some View {
        if split {
            ListDetailSplit(list: base, detail: detailPage(which))
        } else {
            base
        }
    }

    /// The page beside a tab's list: a read page (spending, day, store, detail, Coach) on any tab, an account, card, loan or person page
    /// on Accounts, a category page on Insights. Add and Edit are never here (they take the whole screen).
    private func detailPage(_ which: ShellTab) -> AnyView? {
        if which == .settings { return nil }
        if !model.readStack.isEmpty {
            return AnyView(ReadHost(model: model, keptSim: $keptSim))
        }
        if which == .accounts, !model.arranging, let target = model.openPage {
            return AnyView(PageHost(model: model, target: target))
        }
        if which == .insights, let openId = model.openCategoryId {
            return AnyView(insightsCategory(openId))
        }
        return nil
    }

    static let barItems: [TabBarItem] = [
        TabBarItem(tab: .overview, title: "tab.overview", symbol: "house", identifier: "tab.overview"),
        TabBarItem(tab: .activity, title: "tab.activity", symbol: "list.bullet", identifier: "tab.activity"),
        TabBarItem(tab: .insights, title: "tab.insights", symbol: "chart.pie", identifier: "tab.insights"),
        TabBarItem(tab: .accounts, title: "tab.accounts", symbol: "building.columns", identifier: "tab.accounts"),
        TabBarItem(tab: .settings, title: "tab.settings", symbol: "gearshape", identifier: "tab.settings"),
    ]

    var body: some View {
        GeometryReader { proxy in
            let layout: ShellLayout = ShellLayout.of(width: proxy.size.width, regular: sizeClass == .regular)
            shell(layout)
                .environment(\.layoutMode, layout.content)
                .frame(width: proxy.size.width, height: proxy.size.height)
        }
    }

    private func shell(_ layout: ShellLayout) -> some View {
        HStack(spacing: 0) {
            if layout.rail {
                KassaTabRail(
                    items: AppShell.barItems, selection: tab, onSelect: { picked in tab = picked },
                    onAdd: { model.openAdd(draft: keptDraft) }
                )
            }
            VStack(spacing: 0) {
                tabs(layout)
                if !layout.rail {
                    KassaTabBar(items: AppShell.barItems, selection: tab, onSelect: { picked in tab = picked })
                }
            }
        }
        .background(Palette.ground.ignoresSafeArea())
        .accentColor(Palette.ink)
        .onChange(of: model.settingsRequest) { _ in tab = .settings }
        .overlay {
            if !model.readStack.isEmpty && (layout.content != .listDetail || tab == .settings) {
                ReadHost(model: model, keptSim: $keptSim)
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
        .sheet(item: $model.splitSheet) { request in
            SplitPickerSheet(model: model, request: request)
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

    /// Hides the native tab bar and, below the rail's width, floats Add above the bar. With the rail Add sits at its foot.
    private func withAdd<Content: View>(_ content: Content, _ layout: ShellLayout) -> some View {
        content
            .toolbar(.hidden, for: .tabBar)
            .overlay(alignment: .bottomTrailing) {
                if !layout.rail {
                    AddFab(action: { model.openAdd(draft: keptDraft) })
                        .padding(.trailing, 20)
                        .padding(.bottom, 16)
                }
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
                onOpenCategory: { id, back in model.openCategory(id, monthsBack: back) },
                onOpenSpending: { income, month in model.openRead(ReadRoute.spending(month: month, income: income, back: 0)) },
                coachLine: CoachWording.overviewLine(model.coach, weeklyOn: model.coachWeekly),
                onOpenCoach: { model.openCoach() }
            )
        } else {
            ProgressView()
        }
    }

    /// [split]: with a page beside the list, the category page is that page (`detailPage`) and this is the list alone.
    @ViewBuilder
    private func insightsTab(_ split: Bool) -> some View {
        if !split, let openId = model.openCategoryId {
            insightsCategory(openId)
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
    private func insightsCategory(_ openId: Int64) -> some View {
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
    }

    /// [split]: with a page beside the list, the page is that page (`detailPage`) and this is the list alone.
    @ViewBuilder
    private func accountsTab(_ split: Bool) -> some View {
        if !split, let target = model.openPage {
            PageHost(model: model, target: target)
        } else if let data = model.accounts, model.arranging {
            ArrangeAccountsScreen(
                data: data, onSave: { text in model.saveArrangement(text) }, onDone: { model.stopArranging() }
            )
        } else if let data = model.accounts {
            AccountsScreen(
                data: data, hidden: model.hideAmounts, onToggleHidden: { model.toggleHidden() },
                onAdd: { addingAccount = true },
                onOpenRow: { row, isPerson in
                    if isPerson { model.openPersonPage(row.id) } else { model.openAccountPage(row.id) }
                },
                order: orderHooks
            )
            .overlay(alignment: .bottom) { orderMessageBar }
        } else {
            ProgressView()
        }
    }

    private var orderHooks: AccountsOrderHooks {
        return AccountsOrderHooks(
            savedFolded: model.phone.folded,
            onFolded: { keys in model.saveFolded(keys) },
            onPick: { name in model.chooseSort(name) },
            onArrange: { model.startArranging() },
            onReset: { model.resetOrder() }
        )
    }

    @ViewBuilder
    private var orderMessageBar: some View {
        if let message = model.orderMessage {
            OrderMessageBar(
                message: message, onUndo: { model.undoOrder() }, onGone: { id in model.dismissOrderMessage(id) }
            )
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
