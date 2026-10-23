package dev.krishnagurram.kassa.ui

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
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
import dev.krishnagurram.kassa.ui.activity.MonthBreakdown
import dev.krishnagurram.kassa.ui.cards.PaySheet
import dev.krishnagurram.kassa.ui.cards.cardsByDueDate
import dev.krishnagurram.kassa.ui.common.PanePlaceholder
import dev.krishnagurram.kassa.ui.entry.EntryStart
import dev.krishnagurram.kassa.ui.importer.ImportScreen
import dev.krishnagurram.kassa.ui.invest.AllocateSheet
import dev.krishnagurram.kassa.ui.theme.Kassa

/**
 * Everything inside the activity: first-run import, the tabs, pages (over the tabs on the cover screen, beside the
 * list when unfolded), messages and the sheets that float over everything.
 *
 * [entryCount] is -1 while the database opens. [openAdd] asks for a new entry (from the daily reminder).
 */
@Composable
fun AppRoot(entryCount: Int, startedFresh: Boolean, onStartFresh: () -> Unit, openAdd: Boolean, onAddOpened: () -> Unit) {
    val nav: NavViewModel = viewModel()
    val vm: LedgerViewModel = viewModel()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val app = remember(vm, nav) { AppController(vm, nav, scope, context) }
    val ledger by vm.ledger.collectAsState()
    var fresh by rememberSaveable { mutableStateOf(startedFresh) }
    val snackbar = remember { SnackbarHostState() }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
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

    BoxWithConstraints(Modifier.fillMaxSize().background(Kassa.colors.ground)) {
        val wide = maxWidth >= 600.dp
        app.split = maxWidth >= 720.dp
        val l = ledger
        val top = nav.top

        when {
            entryCount < 0 || l == null -> Unit
            entryCount == 0 && l.accounts.accounts.isEmpty() && !fresh -> Box(Modifier.windowInsetsPadding(WindowInsets.safeDrawing)) {
                ImportScreen(firstRun = true, onFinished = { fresh = true; onStartFresh() })
            }
            else -> Shell(nav.tab, onTab = nav::select, onAdd = app::newEntry, showAdd = nav.stack.isEmpty()) { padding ->
                Tabs(app, l, padding, wide)
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
                key(page) { PageContent(app, page, l, PaddingValues(start = 12.dp, end = 16.dp, top = 4.dp, bottom = 24.dp), onBack = nav::pop) }
            }
        }

        // Messages sit above the Add button on the cover screen's tabs, so Add always works while one shows.
        SnackbarHost(
            snackbar,
            Modifier.align(Alignment.BottomCenter).windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(bottom = if (!wide && top == null) 168.dp else 16.dp),
        )

        if (l != null) {
            app.allocating?.let { t ->
                AllocateSheet(
                    t, l,
                    onCash = { app.vm.keepTransferAsCash(t.id); app.allocating = null },
                    onBuy = { ticker, shares, price -> app.vm.buy(t.accountId, ticker, shares, price, t.id); app.allocating = null },
                    onDismiss = { app.allocating = null },
                )
            }
            app.paying?.let { id ->
                l.cards().firstOrNull { it.account.id == id }?.let { card ->
                    PaySheet(card, l, onDismiss = { app.paying = null }, onPay = { draft -> app.paying = null; app.vm.save(draft) })
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
