package app.kassa.money.ui

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import app.kassa.money.data.jobs.DueNotifier
import app.kassa.money.ui.activity.MonthBreakdown
import app.kassa.money.ui.cards.PaySheet
import app.kassa.money.ui.accounts.BalanceCorrectionSheet
import app.kassa.money.ui.loans.LoanDetailsSheet
import app.kassa.money.ui.loans.LoanPaymentSheet
import app.kassa.money.ui.loans.LoanTermsSheet
import app.kassa.money.ui.cards.cardsByDueDate
import app.kassa.money.ui.common.ConfirmDialog
import app.kassa.money.domain.Category
import app.kassa.money.domain.subcategory
import app.kassa.money.ui.common.LocalAddSubcategory
import app.kassa.money.ui.common.LocalMessages
import app.kassa.money.ui.common.MessageHost
import app.kassa.money.ui.common.Messages
import app.kassa.money.ui.common.PanePlaceholder
import app.kassa.money.ui.entry.EntryStart
import app.kassa.money.ui.importer.ImportScreen
import app.kassa.money.ui.start.StartFresh
import app.kassa.money.ui.start.StartingScreen
import app.kassa.money.ui.accounts.PaymentSheet
import app.kassa.money.ui.invest.AllocateSheet
import app.kassa.money.ui.invest.TradeEditSheet
import app.kassa.money.ui.theme.Kassa

/**
 * Everything inside the activity: first-run import, the tabs, pages (over the tabs on the cover screen, beside the
 * list when unfolded), messages and the sheets that float over everything.
 *
 * [entryCount] is -1 while the database opens. [openAdd] asks for a new entry (from the daily reminder); [openDue]
 * asks for a card, loan or bill (from a payment-due notification); [openBackup] asks for Settings > Backup (from the
 * "Backup needs attention" notification).
 */
@Composable
fun AppRoot(
    entryCount: Int, startedFresh: Boolean, onStartFresh: () -> Unit, openAdd: Boolean, onAddOpened: () -> Unit,
    openDue: DueTarget?, onDueOpened: () -> Unit, openBackup: Boolean = false, onBackupOpened: () -> Unit = {},
) {
    val nav: NavViewModel = viewModel()
    val openTab = LocalSettings.current.openTab.value
    remember(nav) { nav.start(Tab.entries.firstOrNull { it.name == openTab } ?: Tab.TODAY); true }
    val vm: LedgerViewModel = viewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = remember(vm, nav) { AppController(vm, nav, scope, context) }
    val ledger by vm.ledger.collectAsState()
    var fresh by rememberSaveable { mutableStateOf(startedFresh) }
    // First run: "Start fresh" opens the setup (starter categories and a first account).
    var settingUp by rememberSaveable { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val messages = remember(snackbar, scope) { Messages(snackbar, scope) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    // If Kassa had to turn App lock off (the phone's screen lock is gone), say so once the app is showing.
    val settings = LocalSettings.current
    LaunchedEffect(Unit) { settings.takeLockNotice()?.let { snackbar.showSnackbar(it) } }
    LaunchedEffect(openBackup, ledger != null) {
        if (openBackup && ledger != null) {
            app.openBackup()
            onBackupOpened()
        }
    }
    LaunchedEffect(openDue, ledger != null) {
        if (openDue != null && ledger != null) {
            app.openDue(openDue)
            onDueOpened()
        }
    }
    // Asked once, after the first card with a due day or loan with terms is saved: explain, then Android's prompt.
    val askNotifications by vm.askNotifications.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { DueNotifier.refresh(context) }
    if (askNotifications) ConfirmDialog(
        "Remind you of payments due?",
        "Kassa can notify you when a card or loan payment is due. Android asks for permission next. " +
            "Reminders are made on this phone and nothing leaves it. Change this any time in Settings > Notifications.",
        "Continue", onConfirm = { permission.launch(Manifest.permission.POST_NOTIFICATIONS) }, onDismiss = vm::askedNotifications,
        dismiss = "Not now", destructive = false,
    )
    LaunchedEffect(openAdd, entryCount) {
        if (openAdd && (entryCount > 0 || startedFresh)) {
            if (nav.top !is Page.Entry) nav.push(Page.Entry(EntryStart()))
            onAddOpened()
        }
    }
    // Back closes the top page; with none open it returns to Overview before leaving the app.
    BackHandler(enabled = nav.stack.isNotEmpty() || nav.tab != Tab.TODAY) {
        if (nav.stack.isNotEmpty()) nav.pop() else nav.select(Tab.TODAY)
    }

    // The category picker's "+ New" makes a subcategory through the view model, wherever the picker is shown.
    val addSubcategory: (Category, String, (Long) -> Unit) -> Unit = remember(vm) {
        { parent, name, then -> vm.saveCategory(parent.subcategory(name), null, then = then); Unit }
    }
    CompositionLocalProvider(LocalAddSubcategory provides addSubcategory) {
        BoxWithConstraints(Modifier.fillMaxSize().background(Kassa.colors.ground)) {
            val wide = maxWidth >= 600.dp
            app.split = maxWidth >= 720.dp
            val l = ledger
            val top = nav.top

            when {
                entryCount < 0 || l == null -> StartingScreen()
                entryCount == 0 && l.accounts.accounts.isEmpty() && !fresh -> Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                    if (settingUp) StartFresh(
                        onDone = { categories, account -> vm.startFresh(categories, account); fresh = true; onStartFresh() },
                        onBack = { settingUp = false },
                    )
                    else ImportScreen(firstRun = true, onFinished = { fresh = true; onStartFresh() }, onStartFresh = { settingUp = true })
                }
                else -> Shell(nav.tab, onTab = nav::select, onAdd = { app.newEntry(from = nav.top) }, showAdd = nav.stack.isEmpty()) { padding ->
                    CompositionLocalProvider(LocalMessages provides messages) { Tabs(app, l, padding, wide) }
                }
            }

            // A page that takes the whole screen slides in over the tabs, which stay composed underneath so going
            // back returns to the same scroll position.
            val fullPage = top?.takeIf { !wide || !it.besideList }
            var lastFull by remember { mutableStateOf<Page?>(null) }
            if (fullPage != null) lastFull = fullPage
            AnimatedVisibility(
                visible = fullPage != null && l != null,
                enter = slideInHorizontally(tween(240)) { it / 5 } + fadeIn(tween(200)),
                exit = slideOutHorizontally(tween(200)) { it / 5 } + fadeOut(tween(160)),
            ) {
                val page = lastFull
                if (page != null && l != null) Box(
                    Modifier.fillMaxSize().background(Kassa.colors.ground)
                        // Swallows touches so nothing underneath reacts.
                        .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
                        .windowInsetsPadding(WindowInsets.safeDrawing),
                ) {
                    // Card, account and category pages keep Add, which starts the entry with that card or category picked.
                    val offersAdd = app.presetFor(page) != null
                    val bottom = if (offersAdd) ADD_BUTTON_CLEARANCE else 24.dp
                    key(page) { PageContent(app, page, l, PaddingValues(start = 12.dp, end = 16.dp, top = 4.dp, bottom = bottom), onBack = nav::pop) }
                    if (offersAdd) Box(Modifier.align(Alignment.BottomEnd).padding(16.dp)) { AddButton { app.newEntry(from = page) } }
                }
            }

            // Messages sit bottom left, level with Add and stopping short of it, so Add always works while one shows.
            MessageHost(
                snackbar,
                Modifier.align(Alignment.BottomStart).windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(start = 16.dp, end = ADD_BUTTON_CLEARANCE, bottom = if (!wide && top == null) NAV_BAR_HEIGHT + 16.dp else 16.dp),
            )

            if (l != null) {
                app.allocating?.let { t ->
                    AllocateSheet(
                        t, l,
                        onCash = { app.vm.keepTransferAsCash(t.id); app.allocating = null },
                        onRecorded = { app.vm.clearTransfer(t.id); app.allocating = null },
                        onBuy = { o -> app.vm.buy(t.accountId, o.ticker, o.microShares, o.price, o.at, transferId = t.id); app.allocating = null },
                        onDismiss = { app.allocating = null },
                    )
                }
                app.paying?.let { id ->
                    l.cards().firstOrNull { it.account.id == id }?.let { card ->
                        PaySheet(card, l, onDismiss = { app.paying = null }, onPay = { draft -> app.paying = null; app.vm.save(draft) })
                    }
                }
                app.payingLoan?.let { id ->
                    val loan = l.accountById[id]
                    val terms = l.loans.terms[id]
                    val close = { app.payingLoan = null }
                    if (loan != null && terms != null) LoanPaymentSheet(
                        loan, terms, l, editing = null, onDismiss = close,
                        onSave = { draft -> app.vm.recordLoanPayment(draft, onDone = close) },
                    )
                }
                app.editingLoanPayment?.let { detail ->
                    val loan = l.accountById[detail.payment.accountId]
                    val terms = l.loans.terms[detail.payment.accountId]
                    val close = { app.editingLoanPayment = null }
                    if (loan != null && terms != null) LoanPaymentSheet(
                        loan, terms, l, editing = detail, onDismiss = close,
                        onSave = { draft -> app.vm.recordLoanPayment(draft, onDone = close) },
                        onDelete = { app.vm.deleteLoanPayment(detail.payment.id, onDone = close) },
                    )
                }
                app.addingTermsTo?.let { id ->
                    l.accountById[id]?.let { account ->
                        LoanTermsSheet(
                            account, l, onDismiss = { app.addingTermsTo = null },
                            onSave = { updated, terms, done -> app.vm.addLoanTerms(updated, terms, onDone = done) },
                        )
                    }
                }
                app.editingLoanDetails?.let { id ->
                    val loan = l.accountById[id]
                    val terms = l.loans.terms[id]
                    if (loan != null && terms != null) LoanDetailsSheet(
                        loan, terms, l, onDismiss = { app.editingLoanDetails = null },
                        onSave = { updated, newTerms, done -> app.vm.updateLoanDetails(updated, newTerms, onDone = done) },
                    )
                }
                app.correcting?.let { c ->
                    val account = l.accountById[c.accountId]
                    val close = { app.correcting = null }
                    if (account != null) BalanceCorrectionSheet(
                        l, account, c.editing, onDismiss = close,
                        onSave = { target, at, done -> app.vm.correctBalance(account.id, target, at, c.editing?.id, onDone = done) },
                        onDelete = c.editing?.let { t -> val delete: () -> Unit = { app.vm.deleteCorrection(t.id, onDone = close) }; delete },
                    )
                }
                app.editingTrade?.let { t ->
                    val close = { app.editingTrade = null }
                    TradeEditSheet(
                        t, l.trades[t.id], l.today, l.zone,
                        onSave = { o -> app.vm.editTrade(t, o.ticker, o.microShares, o.price, o.at, close) },
                        onMove = { at -> app.vm.moveTrade(t, at, close) },
                        onDelete = { app.vm.deleteTrade(t, close) },
                        onDismiss = close,
                    )
                }
                app.editingPayment?.let { t ->
                    val close = { app.editingPayment = null }
                    l.people.people.firstOrNull { it.id == t.personId }?.let { person ->
                        PaymentSheet(
                            person, l.personBalances()[person.id] ?: 0, l,
                            onDismiss = close,
                            onSave = { draft -> app.vm.save(draft, onDone = close) },
                            editing = t,
                            onDelete = { app.vm.delete(t.id); close() },
                        )
                    }
                }
            }
        }
    }
}

/**
 * The tab, and when unfolded the page beside it. Accounts shows its first card there by default; Activity shows the
 * month's categories until an entry is picked.
 */
@Composable
private fun Tabs(app: AppController, l: Ledger, padding: PaddingValues, wide: Boolean) {
    val nav = app.nav
    val paneTop = nav.stack.lastOrNull { it.besideList }
    val default: Page? = if (nav.tab == Tab.ACCOUNTS) cardsByDueDate(l).firstOrNull()?.let { Page.AccountDetail(it.account.id) } else null
    val shown = paneTop ?: default
    when {
        app.split && (paneTop != null || nav.tab == Tab.ACCOUNTS || nav.tab == Tab.ACTIVITY) -> Row(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) { TabContent(app, l, PaddingValues(start = 24.dp, end = 20.dp, top = 16.dp, bottom = 32.dp), shown) }
            VerticalDivider(color = Kassa.colors.line)
            Box(Modifier.weight(1f)) {
                val pad = PaddingValues(start = 16.dp, end = 24.dp, top = 8.dp, bottom = 32.dp)
                when {
                    shown != null -> PageContent(app, shown, l, pad, onBack = if (paneTop != null) nav::pop else null)
                    nav.tab == Tab.ACTIVITY -> MonthBreakdown(l, pad, onOpenCategory = { app.openFromList(Page.CategoryDetail(it.id)) })
                    else -> PanePlaceholder("Pick something on the left.")
                }
            }
        }
        paneTop != null && wide -> PageContent(app, paneTop, l, padding, onBack = nav::pop)
        else -> TabContent(app, l, padding, null)
    }
}
