package dev.krishnagurram.kassa.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.CreditCard
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Dashboard
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.krishnagurram.kassa.KassaApplication
import dev.krishnagurram.kassa.domain.Transaction
import dev.krishnagurram.kassa.ui.accounts.AccountsScreen
import dev.krishnagurram.kassa.ui.activity.ActivityScreen
import dev.krishnagurram.kassa.ui.entry.EntryStart
import dev.krishnagurram.kassa.ui.importer.ImportScreen
import dev.krishnagurram.kassa.ui.theme.Kassa
import dev.krishnagurram.kassa.ui.theme.KassaTheme
import dev.krishnagurram.kassa.ui.today.TodayScreen
import dev.krishnagurram.kassa.ui.settings.SettingsActions
import dev.krishnagurram.kassa.ui.settings.SettingsScreen
import kotlinx.coroutines.launch

class MainActivity : androidx.fragment.app.FragmentActivity() {
    private val locked = mutableStateOf(false)
    private var hiddenAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val app = application as KassaApplication
        if (app.settings.lockEnabled.value) locked.value = true
        setContent {
            val theme by app.settings.theme.collectAsState()
            val dark = when (theme) { "DARK" -> true; "LIGHT" -> false; else -> androidx.compose.foundation.isSystemInDarkTheme() }
            LaunchedEffect(dark) {
                val t = android.graphics.Color.TRANSPARENT
                val style = if (dark) androidx.activity.SystemBarStyle.dark(t) else androidx.activity.SystemBarStyle.light(t, t)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
            }
            KassaTheme(dark = dark) {
                val count by app.repository.entryCount.collectAsState(initial = -1)
                val lockOn by app.settings.lockEnabled.collectAsState()
                LaunchedEffect(lockOn) { if (android.os.Build.VERSION.SDK_INT >= 33) setRecentsScreenshotEnabled(!lockOn) }
                if (locked.value && lockOn) {
                    LockScreen(onUnlock = ::unlock)
                    LaunchedEffect(Unit) { unlock() }
                } else {
                    KassaRoot(entryCount = count, startedFresh = app.settings.startedFresh, onStartFresh = { app.settings.startedFresh = true })
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        hiddenAt = System.currentTimeMillis()
    }

    override fun onStart() {
        super.onStart()
        val app = application as KassaApplication
        // Lock again after a minute away.
        if (app.settings.lockEnabled.value && hiddenAt > 0 && System.currentTimeMillis() - hiddenAt > 60_000) locked.value = true
    }

    private fun unlock() {
        val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val info = androidx.biometric.BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock Kassa")
            .apply {
                if (android.os.Build.VERSION.SDK_INT >= 30) setAllowedAuthenticators(auth)
                else @Suppress("DEPRECATION") setDeviceCredentialAllowed(true)
            }
            .build()
        val prompt = androidx.biometric.BiometricPrompt(this, androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) { locked.value = false }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    // No screen lock set up at all: don't trap the user out of their own data.
                    if (code == androidx.biometric.BiometricPrompt.ERROR_NO_DEVICE_CREDENTIAL || code == androidx.biometric.BiometricPrompt.ERROR_HW_NOT_PRESENT) locked.value = false
                }
            })
        prompt.authenticate(info)
    }
}

@Composable
private fun LockScreen(onUnlock: () -> Unit) {
    Box(Modifier.fillMaxSize().background(Kassa.colors.ground), contentAlignment = androidx.compose.ui.Alignment.Center) {
        androidx.compose.foundation.layout.Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
            Text("Kassa is locked", style = dev.krishnagurram.kassa.ui.theme.KassaType.section, color = Kassa.colors.ink)
            androidx.compose.material3.TextButton(onClick = onUnlock) { Text("Unlock", color = Kassa.colors.ink) }
        }
    }
}

@Composable
private fun KassaRoot(entryCount: Int, startedFresh: Boolean, onStartFresh: () -> Unit) {
    val nav: NavViewModel = viewModel()
    var fresh by rememberSaveable { mutableStateOf(startedFresh) }
    var allocating by remember { mutableStateOf<dev.krishnagurram.kassa.data.db.PendingEntity?>(null) }
    var paying by remember { mutableStateOf<Long?>(null) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val vm: LedgerViewModel = viewModel()
    val ledger by vm.ledger.collectAsState()
    val sensitivity by vm.sensitivity.collectAsState()
    val dismissedFlags by vm.dismissedFlags.collectAsState()
    val lockOn by vm.lockEnabled.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    // Back closes the top page; with none open it returns to Today before leaving the app.
    BackHandler(enabled = nav.stack.isNotEmpty() || nav.tab != Tab.TODAY) {
        if (nav.stack.isNotEmpty()) nav.pop() else nav.select(Tab.TODAY)
    }

    BoxWithConstraints(Modifier.fillMaxSize().background(Kassa.colors.ground)) {
        val wide = maxWidth >= 600.dp
        val split = maxWidth >= 720.dp
        val l = ledger

        /** From a list: beside it when unfolded, on top of it on the cover screen. */
        val openFromList: (Page) -> Unit = { p -> nav.open(p, besideList = split) }
        val edit: (Transaction, Boolean) -> Unit = { t, fromPage ->
            scope.launch {
                vm.loadForEdit(t.id)?.let { (tx, split2) ->
                    val page = Page.Entry(EntryStart(editing = tx, splitWith = split2))
                    if (fromPage) nav.push(page) else openFromList(page)
                }
            }
        }
        val editFromList: (Transaction) -> Unit = { edit(it, false) }
        val editFromPage: (Transaction) -> Unit = { edit(it, true) }

        val needs = object : dev.krishnagurram.kassa.ui.today.NeedsYouActions {
            fun draftFrom(p: dev.krishnagurram.kassa.data.db.PendingEntity) = dev.krishnagurram.kassa.data.EntryDraft(
                id = null, kind = if (p.isCredit) dev.krishnagurram.kassa.domain.TxKind.INCOME else dev.krishnagurram.kassa.domain.TxKind.EXPENSE,
                amount = p.amount, accountId = p.accountId, toAccountId = null, categoryId = if (p.isCredit) null else p.categoryId, personId = null,
                epochMillis = p.epochMillis, note = p.merchant ?: "", splitWith = emptyList(),
            )
            override fun addAlert(p: dev.krishnagurram.kassa.data.db.PendingEntity) {
                if (p.isCredit) { editAlert(p); return }
                vm.save(draftFrom(p)) { vm.dismissPending(p.id) }
            }
            override fun editAlert(p: dev.krishnagurram.kassa.data.db.PendingEntity) {
                openFromList(Page.Entry(EntryStart(prefill = draftFrom(p), afterSave = { vm.dismissPending(p.id) })))
            }
            override fun dismissAlert(p: dev.krishnagurram.kassa.data.db.PendingEntity) { vm.dismissPending(p.id, "Dismissed. Nothing was added.") }
            override fun approve(r: dev.krishnagurram.kassa.domain.Recurring, amount: Long) { vm.approveRecurring(r, amount) }
            override fun editRecurring(r: dev.krishnagurram.kassa.domain.Recurring, amount: Long) {
                val lg = ledger ?: return
                val due = dev.krishnagurram.kassa.domain.RecurringRules.dueDate(r, java.time.YearMonth.from(lg.today))
                openFromList(Page.Entry(EntryStart(
                    prefill = dev.krishnagurram.kassa.data.EntryDraft(null, dev.krishnagurram.kassa.domain.TxKind.EXPENSE, amount, r.accountId, null, r.categoryId, null,
                        due.atTime(9, 0).atZone(lg.zone).toInstant().toEpochMilli(), r.name, emptyList()),
                    afterSave = { vm.markHandled(r) },
                )))
            }
            override fun skip(r: dev.krishnagurram.kassa.domain.Recurring) { vm.skipRecurring(r) }
            override fun allocate(p: dev.krishnagurram.kassa.data.db.PendingEntity) { allocating = p }
        }

        /** Draws one page. [onBack] is null for the pane's default page, which has nothing to go back to. */
        @Composable
        fun PageHost(page: Page, lg: Ledger, padding: PaddingValues, onBack: (() -> Unit)?) {
            val back = onBack ?: {}
            when (page) {
                is Page.Entry -> dev.krishnagurram.kassa.ui.entry.EntryPage(
                    ledger = lg, start = page.start, contentPadding = padding,
                    loadNotes = { vm.notes(it) },
                    onSave = { vm.save(it, page.start.afterSave); nav.pop() },
                    onDelete = { vm.delete(it); nav.pop() },
                    onAddPerson = { name, then -> vm.addPerson(name, then) },
                    onBack = back,
                )
                is Page.CardDetail -> {
                    val c = lg.cards().firstOrNull { it.account.id == page.accountId }
                    if (c == null) Gone(padding, onBack)
                    else {
                        val history by remember(c.account.id) { vm.accountHistory(c.account.id) }.collectAsState(initial = emptyList())
                        dev.krishnagurram.kassa.ui.cards.CardPage(c, lg, padding, onBack, onEdit = editFromPage, onPay = { paying = c.account.id }, onEditCard = { nav.push(Page.EditAccount(c.account.id)) }, history = history)
                    }
                }
                is Page.AccountDetail -> {
                    val a = lg.accountById[page.accountId]
                    val card = lg.cards().firstOrNull { it.account.id == page.accountId }
                    when {
                        a == null -> Gone(padding, onBack)
                        card != null && !a.archived -> {
                            val history by remember(a.id) { vm.accountHistory(a.id) }.collectAsState(initial = emptyList())
                            dev.krishnagurram.kassa.ui.cards.CardPage(card, lg, padding, onBack, onEdit = editFromPage, onPay = { paying = card.account.id }, onEditCard = { nav.push(Page.EditAccount(card.account.id)) }, history = history)
                        }
                        else -> {
                            val history by remember(a.id) { vm.accountHistory(a.id) }.collectAsState(initial = emptyList())
                            dev.krishnagurram.kassa.ui.accounts.AccountPage(
                                lg, a, history, padding, onBack,
                                onEditEntry = editFromPage,
                                onEditAccount = { nav.push(Page.EditAccount(a.id)) },
                                onSaveHolding = { vm.saveHolding(it) },
                                onDeleteHolding = { vm.deleteHolding(it.id) },
                                onBuy = { tk, sh, pr -> vm.buy(a.id, tk, sh, pr, null) },
                                onSell = { tk, sh, pr -> vm.sell(a.id, tk, sh, pr) },
                            )
                        }
                    }
                }
                is Page.EditAccount -> {
                    val a = lg.accountById[page.accountId]
                    if (a == null) Gone(padding, onBack)
                    else androidx.compose.runtime.key(a.id) {
                        dev.krishnagurram.kassa.ui.accounts.EditAccountPage(
                            lg, a, padding, onBack = back,
                            onSave = { acct, group -> vm.updateAccount(acct, group); nav.pop() },
                            onArchive = { vm.setArchived(a, it); nav.pop(); if (it && (nav.top is Page.CardDetail || nav.top is Page.AccountDetail)) nav.pop() },
                        )
                    }
                }
                is Page.PersonDetail -> {
                    val p = lg.people.people.firstOrNull { it.id == page.personId }
                    if (p == null) Gone(padding, onBack)
                    else dev.krishnagurram.kassa.ui.accounts.PersonPage(p, lg.personBalances()[p.id] ?: 0, lg, padding, onBack, onSave = { vm.save(it) },
                        onSplit = { tx, share -> vm.splitExisting(tx, p.id, share) },
                        onOpenEntry = { id -> scope.launch { vm.loadForEdit(id)?.let { (tx, split2) -> nav.push(Page.Entry(EntryStart(editing = tx, splitWith = split2))) } } })
                }
                is Page.CategoryDetail -> {
                    val c = lg.categoryById[page.categoryId]
                    if (c == null) Gone(padding, onBack)
                    else dev.krishnagurram.kassa.ui.insights.CategoryPage(lg, c, padding, onBack, onEdit = editFromPage, load = vm::between)
                }
                is Page.Insight -> dev.krishnagurram.kassa.ui.insights.InsightDetailPage(lg, page.page, padding, onBack, onOpenCategory = { nav.push(Page.CategoryDetail(it.id)) }, onOpenCard = { nav.push(Page.CardDetail(it)) }, onOpenStore = { nav.push(Page.Store(it)) }, onEdit = editFromPage)
                is Page.Store -> dev.krishnagurram.kassa.ui.insights.InsightStorePage(lg, page.name, padding, onBack, onEdit = editFromPage)
                is Page.Day -> dev.krishnagurram.kassa.ui.insights.DayPage(lg, java.time.LocalDate.ofEpochDay(page.epochDay), padding, onBack, onEdit = editFromPage, load = vm::between)
                is Page.Spending -> dev.krishnagurram.kassa.ui.today.SpendingPage(lg, page.month, page.income, padding, onBack, onEdit = editFromPage, onOpenCategory = { nav.push(Page.CategoryDetail(it)) }, onOpenDay = { nav.push(Page.Day(it.toEpochDay())) })
                Page.Upcoming -> dev.krishnagurram.kassa.ui.today.UpcomingPage(lg, padding, onBack, onOpenCard = { nav.push(Page.CardDetail(it)) })
                Page.Review -> dev.krishnagurram.kassa.ui.today.ReviewPage(lg, padding, onBack, needs)
                Page.Import -> Box(Modifier.padding(padding)) {
                    ImportScreen(firstRun = false, onFinished = { nav.select(Tab.TODAY) }, onCancel = back)
                }
            }
        }

        @Composable
        fun TabContent(lg: Ledger, padding: PaddingValues, selected: Page?) {
            when (nav.tab) {
                Tab.TODAY -> TodayScreen(
                    lg, padding,
                    onOpenCard = { openFromList(Page.CardDetail(it)) },
                    onSpending = { m, inc -> openFromList(Page.Spending(m, inc)) },
                    onOpenCategory = { openFromList(Page.CategoryDetail(it)) },
                    onUpcoming = { openFromList(Page.Upcoming) },
                    onReview = { openFromList(Page.Review) },
                    onAccounts = { nav.select(Tab.ACCOUNTS) },
                    onCards = { nav.select(Tab.ACCOUNTS) },
                )
                Tab.ACTIVITY -> ActivityScreen(lg, padding, onEdit = editFromList, onOpenDay = { openFromList(Page.Day(it.toEpochDay())) }, selectedId = (selected as? Page.Entry)?.start?.editing?.id)
                Tab.INSIGHTS -> dev.krishnagurram.kassa.ui.insights.InsightsScreen(
                    lg, padding,
                    actions = object : dev.krishnagurram.kassa.ui.insights.InsightActions {
                        override fun openCategory(category: dev.krishnagurram.kassa.domain.Category) { openFromList(Page.CategoryDetail(category.id)) }
                        override fun open(page: dev.krishnagurram.kassa.ui.insights.InsightPage) { openFromList(Page.Insight(page)) }
                        override fun edit(t: Transaction) { editFromList(t) }
                        override fun openDay(date: java.time.LocalDate) { openFromList(Page.Day(date.toEpochDay())) }
                        override fun openSpending(month: Boolean) { openFromList(Page.Spending(month)) }
                    },
                )
                Tab.SETTINGS -> {
                    val alertApps by vm.alertApps.collectAsState()
                    val seenApps by vm.seenApps.collectAsState()
                    val theme by vm.theme.collectAsState()
                    SettingsScreen(
                        lg, remember(vm) { settingsActions(vm, context) { nav.push(Page.Import) } },
                        dev.krishnagurram.kassa.ui.settings.SettingsValues(alertApps, seenApps, sensitivity, lockOn, theme),
                        padding,
                    )
                }
                Tab.ACCOUNTS -> AccountsScreen(
                    lg, padding,
                    selectedAccount = (selected as? Page.AccountDetail)?.accountId,
                    onOpenAccount = { openFromList(Page.AccountDetail(it)) },
                    onOpenPerson = { openFromList(Page.PersonDetail(it)) },
                    onImportAgain = { nav.push(Page.Import) },
                    onAddAccount = { a, g -> vm.addAccount(a, g) },
                    onAddPerson = { n, o -> vm.addPerson(n, o) },
                )
            }
        }

        val top = nav.top
        when {
            entryCount < 0 || l == null -> Unit // database still opening
            entryCount == 0 && l.accounts.accounts.isEmpty() && !fresh -> Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                ImportScreen(firstRun = true, onFinished = { fresh = true; onStartFresh() })
            }
            else -> Shell(nav.tab, onTab = nav::select, onAdd = { openFromList(Page.Entry(EntryStart())) }, showAdd = nav.stack.isEmpty()) { padding ->
                // Unfolded: a list on the left and the page it opened on the right. Cards shows the first card by
                // default; Activity waits for a tap.
                val default: Page? = if (nav.tab == Tab.ACCOUNTS) dev.krishnagurram.kassa.ui.cards.cardsByDueDate(l).firstOrNull()?.let { Page.AccountDetail(it.account.id) } else null
                // The page beside the list is the newest one that opens there; Add/Edit lies over everything.
                val paneTop = nav.stack.lastOrNull { it.besideList }
                val shown = paneTop ?: default
                if (split && (paneTop != null || nav.tab == Tab.ACCOUNTS || nav.tab == Tab.ACTIVITY)) {
                    Row(Modifier.fillMaxSize()) {
                        Box(Modifier.weight(1f)) {
                            TabContent(l, PaddingValues(start = 24.dp, end = 20.dp, top = 16.dp, bottom = 32.dp), shown)
                        }
                        androidx.compose.material3.VerticalDivider(color = Kassa.colors.line)
                        Box(Modifier.weight(1f)) {
                            val pad = PaddingValues(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 32.dp)
                            if (shown != null) PageHost(shown, l, pad, onBack = if (paneTop != null) nav::pop else null)
                            else if (nav.tab == Tab.ACTIVITY) dev.krishnagurram.kassa.ui.activity.MonthBreakdown(l, pad, onOpenCategory = { openFromList(Page.CategoryDetail(it.id)) })
                            else dev.krishnagurram.kassa.ui.common.PanePlaceholder("Pick something on the left.")
                        }
                    }
                } else if (paneTop != null && wide) {
                    PageHost(paneTop, l, padding, onBack = nav::pop)
                } else {
                    TabContent(l, padding, null)
                }
            }
        }

        // Cover screen, or a page that takes the whole screen: the page slides in over the tabs, which stay
        // composed underneath so going back returns to the same scroll position.
        val fullPage = top?.takeIf { !wide || !it.besideList }
        var lastFull by remember { mutableStateOf<Page?>(null) }
        if (fullPage != null) lastFull = fullPage
        androidx.compose.animation.AnimatedVisibility(
            visible = fullPage != null && l != null,
            enter = androidx.compose.animation.slideInHorizontally(androidx.compose.animation.core.tween(240)) { it / 5 } +
                androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
            exit = androidx.compose.animation.slideOutHorizontally(androidx.compose.animation.core.tween(200)) { it / 5 } +
                androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)),
        ) {
            val pg = lastFull
            if (pg != null && l != null) Box(
                Modifier.fillMaxSize().background(Kassa.colors.ground)
                    .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                androidx.compose.runtime.key(pg) {
                    PageHost(pg, l, PaddingValues(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), onBack = nav::pop)
                }
            }
        }

        // One place for messages, above the bottom bar on the cover screen.
        SnackbarHost(
            snackbar,
            Modifier.align(androidx.compose.ui.Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = if (!wide && top == null) 88.dp else 16.dp),
        )

        allocating?.let { p ->
            if (l != null) dev.krishnagurram.kassa.ui.invest.AllocateSheet(
                p, l,
                onCash = { vm.dismissPending(p.id, "Kept as uninvested cash in the brokerage account."); allocating = null },
                onBuy = { t, sh, pr -> p.accountId?.let { vm.buy(it, t, sh, pr, p.id) }; allocating = null },
                onDismiss = { allocating = null },
            )
        }
        paying?.let { id ->
            l?.cards()?.firstOrNull { it.account.id == id }?.let { c ->
                dev.krishnagurram.kassa.ui.cards.PaySheet(c, l, onDismiss = { paying = null }, onPay = { draft -> paying = null; vm.save(draft) })
            }
        }
    }
}

/** Shown when a page's account, card, person or category was removed while it was open. */
@Composable
private fun Gone(padding: PaddingValues, onBack: (() -> Unit)?) {
    androidx.compose.foundation.layout.Column(Modifier.padding(padding)) {
        dev.krishnagurram.kassa.ui.common.PageTop("", onBack)
        Text("This is no longer here.", style = dev.krishnagurram.kassa.ui.theme.KassaType.body, color = Kassa.colors.ink2, modifier = Modifier.padding(16.dp))
    }
}

/**
 * Bottom navigation with a floating add button on the cover screen; on the unfolded screen (600dp or wider)
 * a navigation rail with the add button at its top. Switches by itself when the phone folds or unfolds.
 */
@Composable
private fun Shell(tab: Tab, onTab: (Tab) -> Unit, onAdd: () -> Unit, showAdd: Boolean = true, content: @Composable (PaddingValues) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val wide = maxWidth >= 600.dp
        val fab: @Composable () -> Unit = {
            FloatingActionButton(onClick = onAdd, containerColor = Kassa.colors.ink, contentColor = Kassa.colors.onInk, shape = RoundedCornerShape(18.dp)) {
                Icon(plusIcon(), "Add an entry")
            }
        }
        if (wide) {
            Scaffold(containerColor = Kassa.colors.ground) { inner ->
                Row(Modifier.fillMaxSize().padding(inner)) {
                    NavigationRail(containerColor = Kassa.colors.ground, header = { Spacer(Modifier.height(8.dp)); fab(); Spacer(Modifier.height(24.dp)) }) {
                        Tab.entries.forEach { t ->
                            NavigationRailItem(
                                selected = tab == t, onClick = { onTab(t) },
                                icon = { Icon(iconFor(t), null) }, label = { Text(t.label) },
                                colors = NavigationRailItemDefaults.colors(
                                    selectedIconColor = Kassa.colors.ink, selectedTextColor = Kassa.colors.ink,
                                    indicatorColor = Kassa.colors.chip, unselectedIconColor = Kassa.colors.ink3, unselectedTextColor = Kassa.colors.ink3,
                                ),
                            )
                        }
                    }
                    Box(Modifier.weight(1f)) { content(PaddingValues(start = 32.dp, end = 32.dp, top = 16.dp, bottom = 32.dp)) }
                }
            }
        } else {
            // Five even tabs with names; Add floats above the bar's right end, in thumb reach. It hides while a page
            // is open so it never sits over a form, and lists get extra room at the bottom so it doesn't cover the last row.
            Scaffold(
                containerColor = Kassa.colors.ground,
                floatingActionButton = { if (showAdd) fab() },
                bottomBar = {
                    NavigationBar(containerColor = Kassa.colors.raised) {
                        val colors = NavigationBarItemDefaults.colors(
                            selectedIconColor = Kassa.colors.ink, selectedTextColor = Kassa.colors.ink,
                            indicatorColor = Kassa.colors.chip, unselectedIconColor = Kassa.colors.ink3, unselectedTextColor = Kassa.colors.ink3,
                        )
                        Tab.entries.forEach { t ->
                            NavigationBarItem(
                                selected = tab == t, onClick = { onTab(t) },
                                // Labels always show, so selecting a tab only changes the highlight and nothing moves.
                                icon = { Icon(iconFor(t), null) },
                                label = { Text(t.label, fontSize = 11.sp, maxLines = 1, softWrap = false) },
                                alwaysShowLabel = true, colors = colors,
                            )
                        }
                    }
                },
            ) { inner ->
                content(PaddingValues(start = 20.dp, end = 20.dp, top = inner.calculateTopPadding() + 12.dp, bottom = inner.calculateBottomPadding() + if (showAdd) 88.dp else 16.dp))
            }
        }
    }
}

private fun line(name: String, build: androidx.compose.ui.graphics.vector.PathBuilder.() -> Unit): ImageVector =
    ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).path(
        stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round,
        pathBuilder = build,
    ).build()

private fun plusIcon() = line("plus") { moveTo(12f, 5f); lineTo(12f, 19f); moveTo(5f, 12f); lineTo(19f, 12f) }

private fun iconFor(t: Tab): ImageVector = when (t) {
    Tab.TODAY -> androidx.compose.material.icons.Icons.Outlined.Dashboard
    Tab.ACTIVITY -> androidx.compose.material.icons.Icons.Outlined.ReceiptLong
    Tab.INSIGHTS -> androidx.compose.material.icons.Icons.Outlined.Insights
    Tab.ACCOUNTS -> androidx.compose.material.icons.Icons.Outlined.AccountBalance
    Tab.SETTINGS -> androidx.compose.material.icons.Icons.Outlined.Settings
}

private fun settingsActions(vm: LedgerViewModel, context: android.content.Context, onImport: () -> Unit) = object : SettingsActions {
    override fun saveCategory(category: dev.krishnagurram.kassa.domain.Category, budget: Long?, then: (Long) -> Unit) { vm.saveCategory(category, budget, then) }
    override fun setMonthBudget(categoryId: Long, amount: Long?) {
        val l = vm.ledger.value ?: return
        vm.setMonthBudget(categoryId, l.period.yearMonth, amount)
    }
    override fun mergeCategory(from: dev.krishnagurram.kassa.domain.Category, into: dev.krishnagurram.kassa.domain.Category) { vm.mergeCategory(from, into) }
    override fun convertToPerson(account: dev.krishnagurram.kassa.domain.Account, balance: Long, name: String) { vm.convertToPerson(account, balance, name) }
    override fun setMonthStartDay(day: Int) = vm.setMonthStartDay(day)
    override fun importAgain() = onImport()
    override suspend fun savePhoto(uri: android.net.Uri): String? = vm.saveIconImage(uri)
    override fun saveRecurring(r: dev.krishnagurram.kassa.domain.Recurring) { vm.saveRecurring(r) }
    override fun deleteRecurring(id: Long) { vm.deleteRecurring(id) }
    override fun setAlertApp(pkg: String, on: Boolean) = vm.setAlertApp(pkg, on)
    override fun addRule(pkg: String, last4: String?, accountId: Long) { vm.addRule(pkg, last4, accountId) }
    override fun deleteRule(id: Long) { vm.deleteRule(id) }
    override fun openNotificationAccess() {
        context.startActivity(android.content.Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
    }
    override fun hasNotificationAccess(): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)
    override fun setSensitivity(v: String) = vm.setSensitivity(v)
    override fun setLock(on: Boolean) = vm.setLock(on)
    override fun setTheme(v: String) = vm.setTheme(v)
    override suspend fun exportTo(uri: android.net.Uri, passphrase: CharArray): String = vm.exportTo(uri, passphrase)
    override suspend fun readBackup(uri: android.net.Uri, passphrase: CharArray) = vm.readBackup(uri, passphrase)
    override fun restore(json: org.json.JSONObject) { vm.restore(json) }
    override fun appLabel(pkg: String): String = runCatching {
        context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)
}
